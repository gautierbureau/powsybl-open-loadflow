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

import java.util.Arrays;

/**
 * Structure-of-Arrays (SoA) store for <b>single-variable</b> sensitivity factors — a memory-lean alternative to one
 * {@code SingleVariableLfSensitivityFactor} object per factor. Each factor becomes a row (a dense index that is also
 * the factor {@code index}); its fields live in parallel column arrays rather than in a per-factor heap object.
 *
 * <p>At extreme factor counts (millions) this removes the per-object header + padding and, more importantly, replaces
 * millions of long-lived objects the garbage collector must trace/copy on every young collection with a handful of flat
 * arrays. This is the foundation of the flyweight factor-model proposal (see
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

    private int size;

    // write-once columns
    private String[] variableId;
    private String[] functionId;
    private LfElement[] functionElement;
    private LfElement[] variableElement;
    private Equation<V, E>[] variableEquation;
    private ContingencyContext[] contingencyContext;
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

    @SuppressWarnings("unchecked")
    LfSensitivityFactorStore(int initialCapacity) {
        int cap = Math.max(1, initialCapacity);
        variableId = new String[cap];
        functionId = new String[cap];
        functionElement = new LfElement[cap];
        variableElement = new LfElement[cap];
        variableEquation = (Equation<V, E>[]) new Equation[cap];
        contingencyContext = new ContingencyContext[cap];
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
        this.variableId[row] = variableId;
        this.functionId[row] = functionId;
        this.functionElement[row] = functionElement;
        this.functionType[row] = (byte) functionType.ordinal();
        this.variableElement[row] = variableElement;
        this.variableEquation[row] = variableEquation;
        this.variableType[row] = (byte) variableType.ordinal();
        this.contingencyContext[row] = contingencyContext;
        this.status[row] = (byte) initialStatus.ordinal();
        this.groupIndex[row] = -1;
        size++;
        return row;
    }

    private void ensureCapacity(int capacity) {
        if (capacity <= variableId.length) {
            return;
        }
        int newCap = Math.max(capacity, variableId.length + (variableId.length >> 1));
        variableId = Arrays.copyOf(variableId, newCap);
        functionId = Arrays.copyOf(functionId, newCap);
        functionElement = Arrays.copyOf(functionElement, newCap);
        variableElement = Arrays.copyOf(variableElement, newCap);
        variableEquation = Arrays.copyOf(variableEquation, newCap);
        contingencyContext = Arrays.copyOf(contingencyContext, newCap);
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
        return variableId[row];
    }

    String getFunctionId(int row) {
        return functionId[row];
    }

    LfElement getFunctionElement(int row) {
        return functionElement[row];
    }

    LfElement getVariableElement(int row) {
        return variableElement[row];
    }

    Equation<V, E> getVariableEquation(int row) {
        return variableEquation[row];
    }

    SensitivityFunctionType getFunctionType(int row) {
        return FUNCTION_TYPES[functionType[row]];
    }

    SensitivityVariableType getVariableType(int row) {
        return VARIABLE_TYPES[variableType[row]];
    }

    ContingencyContext getContingencyContext(int row) {
        return contingencyContext[row];
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
