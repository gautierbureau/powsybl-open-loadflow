/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.contingency.ContingencyContext;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.network.LfElement;
import com.powsybl.openloadflow.sensi.AbstractSensitivityAnalysis.LfSensitivityFactor.Status;
import com.powsybl.sensitivity.SensitivityFunctionType;
import com.powsybl.sensitivity.SensitivityVariableType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structure-of-Arrays (SoA) store for <b>single-variable</b> sensitivity factors — a memory-lean alternative to one
 * {@code SingleVariableLfSensitivityFactor} object per factor. Each factor becomes a row (a dense index that is also
 * the factor {@code index}); its fields live in parallel column arrays rather than in a per-factor heap object.
 *
 * <p>Every reference field (ids, elements, equation, contingency context) is <b>interned</b> into a small pool and the
 * columns store {@code int} pool indices, not references. This is the key to the garbage-collector win: an {@code int[]}
 * column holds no references, so a GC mark pass skips it entirely, whereas an {@code Object[]} column (or millions of
 * per-factor objects) forces the collector to scan one reference slot per factor per field. In an N&times;M factor
 * matrix the pools hold only the N+M distinct functions/variables, so the collector traces thousands of references
 * instead of tens of millions.
 *
 * <p>This is the foundation of the flyweight factor-model proposal (see
 * {@code patches/sensitivity-flyweight-factor-model.md}); a reusable {@code LfFactorCursor} implementing
 * {@code LfSensitivityFactor} over a {@code (store, row)} pair then lets the existing pipeline iterate factors without
 * allocating one object each.
 *
 * <p>Not thread-safe: a store is built and consumed within a single {@code analyzeContingencySet} invocation, which is
 * itself confined to one thread in the multi-threaded path.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
final class LfSensitivityFactorStore<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

    static final SensitivityFunctionType[] FUNCTION_TYPES = SensitivityFunctionType.values();
    static final SensitivityVariableType[] VARIABLE_TYPES = SensitivityVariableType.values();
    static final Status[] STATUSES = Status.values();

    /** Deduplicating pool: maps distinct values to dense int ids so columns can store ids instead of references. */
    private static final class Pool<T> {
        private final Map<T, Integer> ids = new HashMap<>();
        private final List<T> values = new ArrayList<>();

        int intern(T value) {
            if (value == null) {
                return -1;
            }
            Integer id = ids.get(value);
            if (id != null) {
                return id;
            }
            int newId = values.size();
            values.add(value);
            ids.put(value, newId);
            return newId;
        }

        T get(int id) {
            return id < 0 ? null : values.get(id);
        }
    }

    private int size;

    // interning pools (small: one entry per distinct value)
    private final Pool<String> idPool = new Pool<>();
    private final Pool<LfElement> elementPool = new Pool<>();
    private final Pool<Equation<V, E>> equationPool = new Pool<>();
    private final Pool<ContingencyContext> contingencyContextPool = new Pool<>();

    // write-once columns (int pool indices, so no references for the GC to trace)
    private int[] variableIdRef;
    private int[] functionIdRef;
    private int[] functionElementRef;
    private int[] variableElementRef;
    private int[] variableEquationRef;
    private int[] contingencyContextRef;
    private byte[] functionType;   // SensitivityFunctionType.ordinal()
    private byte[] variableType;   // SensitivityVariableType.ordinal()

    // mutable columns
    private byte[] status;         // Status.ordinal()
    private int[] groupIndex;      // RHS column, -1 until grouped
    private double[] functionReference;
    private double[] sensitivityPredefined;
    private boolean[] sensitivityPredefinedSet;
    private double[] functionPredefined;
    private boolean[] functionPredefinedSet;

    LfSensitivityFactorStore(int initialCapacity) {
        int cap = Math.max(1, initialCapacity);
        variableIdRef = new int[cap];
        functionIdRef = new int[cap];
        functionElementRef = new int[cap];
        variableElementRef = new int[cap];
        variableEquationRef = new int[cap];
        contingencyContextRef = new int[cap];
        functionType = new byte[cap];
        variableType = new byte[cap];
        status = new byte[cap];
        groupIndex = new int[cap];
        functionReference = new double[cap];
        sensitivityPredefined = new double[cap];
        sensitivityPredefinedSet = new boolean[cap];
        functionPredefined = new double[cap];
        functionPredefinedSet = new boolean[cap];
    }

    int size() {
        return size;
    }

    /**
     * Appends a single-variable factor and returns its row (which is also its factor index).
     */
    int add(String variableId, String functionId, LfElement functionElement, SensitivityFunctionType functionType,
            LfElement variableElement, Equation<V, E> variableEquation, SensitivityVariableType variableType,
            ContingencyContext contingencyContext, Status initialStatus) {
        ensureCapacity(size + 1);
        int row = size;
        this.variableIdRef[row] = idPool.intern(variableId);
        this.functionIdRef[row] = idPool.intern(functionId);
        this.functionElementRef[row] = elementPool.intern(functionElement);
        this.functionType[row] = (byte) functionType.ordinal();
        this.variableElementRef[row] = elementPool.intern(variableElement);
        this.variableEquationRef[row] = equationPool.intern(variableEquation);
        this.variableType[row] = (byte) variableType.ordinal();
        this.contingencyContextRef[row] = contingencyContextPool.intern(contingencyContext);
        this.status[row] = (byte) initialStatus.ordinal();
        this.groupIndex[row] = -1;
        size++;
        return row;
    }

    private void ensureCapacity(int capacity) {
        if (capacity <= variableIdRef.length) {
            return;
        }
        int newCap = Math.max(capacity, variableIdRef.length + (variableIdRef.length >> 1));
        variableIdRef = Arrays.copyOf(variableIdRef, newCap);
        functionIdRef = Arrays.copyOf(functionIdRef, newCap);
        functionElementRef = Arrays.copyOf(functionElementRef, newCap);
        variableElementRef = Arrays.copyOf(variableElementRef, newCap);
        variableEquationRef = Arrays.copyOf(variableEquationRef, newCap);
        contingencyContextRef = Arrays.copyOf(contingencyContextRef, newCap);
        functionType = Arrays.copyOf(functionType, newCap);
        variableType = Arrays.copyOf(variableType, newCap);
        status = Arrays.copyOf(status, newCap);
        groupIndex = Arrays.copyOf(groupIndex, newCap);
        functionReference = Arrays.copyOf(functionReference, newCap);
        sensitivityPredefined = Arrays.copyOf(sensitivityPredefined, newCap);
        sensitivityPredefinedSet = Arrays.copyOf(sensitivityPredefinedSet, newCap);
        functionPredefined = Arrays.copyOf(functionPredefined, newCap);
        functionPredefinedSet = Arrays.copyOf(functionPredefinedSet, newCap);
    }

    // --- row accessors (what LfFactorCursor delegates to) ---

    String getVariableId(int row) {
        return idPool.get(variableIdRef[row]);
    }

    String getFunctionId(int row) {
        return idPool.get(functionIdRef[row]);
    }

    LfElement getFunctionElement(int row) {
        return elementPool.get(functionElementRef[row]);
    }

    LfElement getVariableElement(int row) {
        return elementPool.get(variableElementRef[row]);
    }

    Equation<V, E> getVariableEquation(int row) {
        return equationPool.get(variableEquationRef[row]);
    }

    SensitivityFunctionType getFunctionType(int row) {
        return FUNCTION_TYPES[functionType[row]];
    }

    SensitivityVariableType getVariableType(int row) {
        return VARIABLE_TYPES[variableType[row]];
    }

    ContingencyContext getContingencyContext(int row) {
        return contingencyContextPool.get(contingencyContextRef[row]);
    }

    Status getStatus(int row) {
        return STATUSES[status[row]];
    }

    void setStatus(int row, Status s) {
        status[row] = (byte) s.ordinal();
    }

    int getGroupIndex(int row) {
        return groupIndex[row];
    }

    void setGroupIndex(int row, int index) {
        groupIndex[row] = index;
    }

    double getFunctionReference(int row) {
        return functionReference[row];
    }

    void setFunctionReference(int row, double value) {
        functionReference[row] = value;
    }

    Double getSensitivityValuePredefinedResult(int row) {
        return sensitivityPredefinedSet[row] ? sensitivityPredefined[row] : null;
    }

    void setSensitivityValuePredefinedResult(int row, Double value) {
        if (value == null) {
            sensitivityPredefinedSet[row] = false;
        } else {
            sensitivityPredefined[row] = value;
            sensitivityPredefinedSet[row] = true;
        }
    }

    Double getFunctionPredefinedResult(int row) {
        return functionPredefinedSet[row] ? functionPredefined[row] : null;
    }

    void setFunctionPredefinedResult(int row, Double value) {
        if (value == null) {
            functionPredefinedSet[row] = false;
        } else {
            functionPredefined[row] = value;
            functionPredefinedSet[row] = true;
        }
    }
}
