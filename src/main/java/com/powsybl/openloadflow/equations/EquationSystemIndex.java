/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.commons.PowsyblException;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class EquationSystemIndex<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>
        implements EquationSystemListener<V, E> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EquationSystemIndex.class);

    private final EquationSystem<V, E> equationSystem;

    private final Set<SingleEquation<V, E>> sortedSetEquationsToSolve = new TreeSet<>();

    // variable reference counting in equation terms
    private final Map<Variable<V>, MutableInt> sortedMapVariablesToFindRefCount = new TreeMap<>();

    private List<SingleEquation<V, E>> sortedSingleEquationsToSolve = Collections.emptyList();

    private List<EquationArray<V, E>> sortedEquationArraysToSolve = Collections.emptyList();

    private List<Variable<V>> sortedVariablesToFind = Collections.emptyList();

    private int columnCount = 0;

    private int columnCountFromArrayEquations = 0;

    private int rowCount = 0;

    private boolean equationsIndexValid = false;

    private boolean variablesIndexValid = false;

    // Incremental variables update: the variables whose membership changed since the last variables update. A
    // security analysis applies and restores one contingency at a time, which adds or removes a handful of the
    // tens of thousands of variables, and rebuilding the whole sorted list from the TreeMap each time dominated
    // the per-contingency index cost. The incremental path produces the same list, in the same order, with the
    // same rows as the full rebuild (OLF_VARIDX_CHECK=1 asserts it on every update).
    // a LIST, not an identity set: the first full build touches every variable, and an IdentityHashMap keeps that
    // capacity, so each later clear() cost the whole table (0.6 s on network.xiidm). Duplicates are harmless: the
    // incremental pass is idempotent per variable. Past the limit it stops recording and the update is a full one.
    private final List<Variable<V>> touchedVariables = new ArrayList<>();

    private boolean touchedOverflow = false;

    // what the LAST variables update changed, for a consumer that keeps its own copy of the rows: its generation, the
    // first row whose variable may differ from the previous update's (0 after a full rebuild) and the variables it
    // removed (rows now -1)
    private long variablesUpdateGeneration = 0;

    private int lastFirstChangedRow = 0;

    private List<Variable<V>> lastRemovedVariables = Collections.emptyList();

    private boolean lastUpdateIncremental = false;

    private boolean variablesEverBuilt = false;

    private boolean lastVariablesUpdateSeparated = false;

    private static final int INCREMENTAL_VARIABLES_MAX = 256;

    private static final boolean VARIABLES_INDEX_CHECK = System.getenv("OLF_VARIDX_CHECK") != null;

    private final List<EquationSystemIndexListener<V, E>> listeners = new ArrayList<>();

    public EquationSystemIndex(EquationSystem<V, E> equationSystem) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
        equationSystem.addListener(this);
    }

    public void addListener(EquationSystemIndexListener<V, E> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(EquationSystemIndexListener<V, E> listener) {
        listeners.remove(Objects.requireNonNull(listener));
    }

    private void notifyEquationChange(SingleEquation<V, E> equation, EquationSystemIndexListener.ChangeType changeType) {
        listeners.forEach(listener -> listener.onEquationChange(equation, changeType));
    }

    private void notifyVariableChange(Variable<V> variable, EquationSystemIndexListener.ChangeType changeType) {
        listeners.forEach(listener -> listener.onVariableChange(variable, changeType));
    }

    private void notifyEquationTermChange(SingleEquationTerm<V, E> term) {
        listeners.forEach(listener -> listener.onEquationTermChange(term));
    }

    private void notifyEquationArrayChange(EquationArray<V, E> equationArray, EquationSystemIndexListener.ChangeType changeType) {
        listeners.forEach(listener -> listener.onEquationArrayChange(equationArray, changeType));
    }

    private void notifyEquationTermArrayChange(EquationTermArray<V, E> equationTermArray, int termNum, EquationSystemIndexListener.ChangeType changeType) {
        listeners.forEach(listener -> listener.onEquationTermArrayChange(equationTermArray, termNum, changeType));
    }

    private void notifyEquationIndexOrderChange() {
        listeners.forEach(EquationSystemIndexListener::onEquationIndexOrderChanged);
    }

    private void updateEquationColumns(Collection<SingleEquation<V, E>> singleEquations, Collection<EquationArray<V, E>> equationArrays) {
        for (SingleEquation<V, E> equation : singleEquations) {
            equation.setColumn(columnCount++);
        }
        for (EquationArray<V, E> equationArray : equationArrays) {
            equationArray.setFirstColumn(columnCount);
            columnCount += equationArray.getLength();
            columnCountFromArrayEquations += equationArray.getLength();
            sortedEquationArraysToSolve.add(equationArray);
        }
    }

    private void updateEquationsToSolve() {
        updateEquationsToSolve(null);
    }

    private void updateEquationsToSolve(Predicate<E> isSeparatedInFirstPart) {
        sortedSingleEquationsToSolve = sortedSetEquationsToSolve.stream().toList();
        sortedEquationArraysToSolve = new ArrayList<>();
        columnCount = 0;
        columnCountFromArrayEquations = 0;
        if (isSeparatedInFirstPart == null) {
            // If there is no need of separating, columns are arranged in this order :
            // - Single equations
            // - Equation arrays
            updateEquationColumns(sortedSingleEquationsToSolve, equationSystem.getEquationArrays());
        } else {
            // If there is a separating predicate (Used for Fast Decoupled), columns are arranged in this order :
            // - First part of single equations
            // - First part of equation arrays
            // - Second part of single equations
            // - Second part of equation arrays
            Map<Boolean, List<SingleEquation<V, E>>> separatedSingleEquationsToSolve = sortedSingleEquationsToSolve.stream()
                    .collect(Collectors.partitioningBy(e -> isSeparatedInFirstPart.test(e.getType())));
            Map<Boolean, List<EquationArray<V, E>>> separatedEquationArrays = equationSystem.getEquationArrays().stream()
                    .collect(Collectors.partitioningBy(e -> isSeparatedInFirstPart.test(e.getType())));
            updateEquationColumns(separatedSingleEquationsToSolve.get(true), separatedEquationArrays.get(true)); // Filling first part
            updateEquationColumns(separatedSingleEquationsToSolve.get(false), separatedEquationArrays.get(false)); // Filling second part
            sortedSingleEquationsToSolve = Stream.concat(separatedSingleEquationsToSolve.get(true).stream(), separatedSingleEquationsToSolve.get(false).stream()).toList();
        }
        equationsIndexValid = true;
        notifyEquationIndexOrderChange();
        LOGGER.debug("Equations index updated ({} columns including {} from array equations)",
                columnCount, columnCountFromArrayEquations);

    }

    private void updateVariablesToFind() {
        updateVariablesToFind(null);
    }

    private void touch(Variable<V> variable) {
        if (!touchedOverflow) {
            if (touchedVariables.size() >= INCREMENTAL_VARIABLES_MAX) {
                touchedOverflow = true;
                touchedVariables.clear();
            } else {
                touchedVariables.add(variable);
            }
        }
    }

    /** Generation of the last variables update (incremented by every one, full or incremental). */
    public long getVariablesUpdateGeneration() {
        return variablesUpdateGeneration;
    }

    /** True when the last variables update was incremental: then {@link #getLastFirstChangedRow()} and
     *  {@link #getLastRemovedVariables()} describe everything it changed relative to the previous one. */
    public boolean isLastVariablesUpdateIncremental() {
        return lastUpdateIncremental;
    }

    public int getLastFirstChangedRow() {
        return lastFirstChangedRow;
    }

    public List<Variable<V>> getLastRemovedVariables() {
        return lastRemovedVariables;
    }

    private boolean updateVariablesToFindIncrementally() {
        List<Variable<V>> list = new ArrayList<>(sortedVariablesToFind);
        int minPos = list.size();
        List<Variable<V>> removed = new ArrayList<>();
        for (Variable<V> v : touchedVariables) {
            int pos = Collections.binarySearch(list, v);
            boolean present = sortedMapVariablesToFindRefCount.containsKey(v);
            if (present && pos < 0) {
                pos = -pos - 1;
                list.add(pos, v);
            } else if (!present && pos >= 0) {
                list.remove(pos);
                removed.add(v);
            } else if (pos < 0) {
                pos = -pos - 1;                              // added then removed again: absent before and after
            }
            // a variable removed and re-added keeps its place but had its row reset to -1: rows from here on are
            // re-assigned, which also covers every row an insertion or removal at this position shifts
            minPos = Math.min(minPos, pos);
        }
        for (int i = minPos; i < list.size(); i++) {
            list.get(i).setRow(i);
        }
        sortedVariablesToFind = Collections.unmodifiableList(list);
        rowCount = list.size();
        lastFirstChangedRow = minPos;
        lastRemovedVariables = removed;
        if (VARIABLES_INDEX_CHECK) {
            List<Variable<V>> full = sortedMapVariablesToFindRefCount.keySet().stream().sorted().toList();
            if (!full.equals(sortedVariablesToFind)) {
                throw new IllegalStateException("Incremental variables index differs from the full rebuild");
            }
            for (int i = 0; i < full.size(); i++) {
                if (full.get(i).getRow() != i) {
                    throw new IllegalStateException("Incremental variables index: row of " + full.get(i) + " is "
                            + full.get(i).getRow() + ", expected " + i);
                }
            }
        }
        return true;
    }

    private void updateVariablesToFind(Predicate<V> isSeparatedInFirstPart) {
        variablesUpdateGeneration++;
        if (isSeparatedInFirstPart == null && variablesEverBuilt && !lastVariablesUpdateSeparated
                && !touchedOverflow && updateVariablesToFindIncrementally()) {
            touchedVariables.clear();
            lastUpdateIncremental = true;
            variablesIndexValid = true;
            LOGGER.debug("Variables index updated incrementally ({} rows)", rowCount);
            return;
        }
        touchedVariables.clear();
        touchedOverflow = false;
        lastUpdateIncremental = false;
        lastFirstChangedRow = 0;
        lastRemovedVariables = Collections.emptyList();
        variablesEverBuilt = true;
        lastVariablesUpdateSeparated = isSeparatedInFirstPart != null;
        sortedVariablesToFind = sortedMapVariablesToFindRefCount.keySet().stream().sorted().toList();
        rowCount = 0;
        if (isSeparatedInFirstPart == null) {
            for (Variable<V> variable : sortedVariablesToFind) {
                variable.setRow(rowCount++);
            }
        } else {
            // If there is a separating predicate (Used for Fast Decoupled), rows are arranged in two parts :
            Map<Boolean, List<Variable<V>>> separatedVariables = sortedVariablesToFind.stream()
                    .collect(Collectors.partitioningBy(v -> isSeparatedInFirstPart.test(v.getType())));
            for (boolean isFirstPart : List.of(true, false)) {
                for (Variable<V> variable : separatedVariables.get(isFirstPart)) {
                    variable.setRow(rowCount++);
                }
            }
            sortedVariablesToFind = Stream.concat(separatedVariables.get(true).stream(), separatedVariables.get(false).stream()).toList();
        }
        variablesIndexValid = true;
        LOGGER.debug("Variables index updated ({} rows)", rowCount);
    }

    private void update() {
        if (!equationsIndexValid) {
            updateEquationsToSolve();
        }

        if (!variablesIndexValid) {
            updateVariablesToFind();
        }
    }

    public void updateWithSeparation(Predicate<E> isEquationSeparatedInFirstPart, Predicate<V> isVariableSeparatedInFirstPart) {
        // Sort equations to solve with a column order that separates in two parts
        updateEquationsToSolve(isEquationSeparatedInFirstPart);
        // Sort variables to find with a row order that separates in two parts
        updateVariablesToFind(isVariableSeparatedInFirstPart);
    }

    private void addTerm(SingleEquationTerm<V, E> term) {
        notifyEquationTermChange(term);
        addVariables(term.getVariables());
    }

    private void addVariables(List<Variable<V>> variables) {
        for (Variable<V> variable : variables) {
            MutableInt variableRefCount = sortedMapVariablesToFindRefCount.get(variable);
            if (variableRefCount == null) {
                variableRefCount = new MutableInt(1);
                sortedMapVariablesToFindRefCount.put(variable, variableRefCount);
                variablesIndexValid = false;
                touch(variable);
                notifyVariableChange(variable, EquationSystemIndexListener.ChangeType.ADDED);
            } else {
                variableRefCount.increment();
            }
        }
    }

    private void addEquation(SingleEquation<V, E> equation) {
        sortedSetEquationsToSolve.add(equation);
        equationsIndexValid = false;
        for (SingleEquationTerm<V, E> term : equation.getTerms()) {
            if (term.isActive()) {
                addTerm(term);
            }
        }
        notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.ADDED);
    }

    private void removeTerm(SingleEquationTerm<V, E> term) {
        notifyEquationTermChange(term);
        removeVariables(term.getVariables());
    }

    private void removeVariables(List<Variable<V>> variables) {
        for (Variable<V> variable : variables) {
            MutableInt variableRefCount = sortedMapVariablesToFindRefCount.get(variable);
            if (variableRefCount != null) {
                variableRefCount.decrement();
                if (variableRefCount.intValue() == 0) {
                    variable.setRow(-1);
                    sortedMapVariablesToFindRefCount.remove(variable);
                    variablesIndexValid = false;
                    touch(variable);
                    notifyVariableChange(variable, EquationSystemIndexListener.ChangeType.REMOVED);
                }
            }
        }
    }

    private void removeEquation(SingleEquation<V, E> equation) {
        equation.setColumn(-1);
        sortedSetEquationsToSolve.remove(equation);
        equationsIndexValid = false;
        for (SingleEquationTerm<V, E> term : equation.getTerms()) {
            if (term.isActive()) {
                removeTerm(term);
            }
        }
        notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.REMOVED);
    }

    @Override
    public void onEquationChange(SingleEquation<V, E> equation, EquationEventType eventType) {
        switch (eventType) {
            case EQUATION_REMOVED:
                if (equation.isActive()) {
                    removeEquation(equation);
                }
                break;

            case EQUATION_DEACTIVATED:
                removeEquation(equation);
                break;

            case EQUATION_CREATED:
                if (equation.isActive()) {
                    addEquation(equation);
                }
                break;

            case EQUATION_ACTIVATED:
                addEquation(equation);
                break;

            default:
                throw new IllegalStateException("Event type not supported: " + eventType);
        }
    }

    @Override
    public void onEquationTermChange(SingleEquationTerm<V, E> term, EquationTermEventType eventType) {
        if (term.getEquation().isActive()) {
            switch (eventType) {
                case EQUATION_TERM_ADDED:
                    if (term.isActive()) {
                        addTerm(term);
                    }
                    break;

                case EQUATION_TERM_ACTIVATED:
                    addTerm(term);
                    break;

                case EQUATION_TERM_DEACTIVATED:
                    removeTerm(term);
                    break;

                default:
                    throw new IllegalStateException("Event type not supported: " + eventType);
            }
        }
    }

    @Override
    public void onEquationArrayChange(EquationArray<V, E> equationArray, int elementNum, EquationEventType eventType) {
        switch (eventType) {
            case EQUATION_DEACTIVATED:
                for (var equationTermArray : equationArray.getTermArrays()) {
                    for (int termNum : equationTermArray.getTermNumsForEquationElementNum(elementNum).toArray()) {
                        if (equationTermArray.isTermActive(termNum)) {
                            List<Variable<V>> variables = equationTermArray.getTermDerivatives(termNum).stream().map(Derivative::getVariable).toList();
                            removeVariables(variables);
                        }
                    }
                }
                for (var singleTerm : equationArray.getSingleEquationTerms(elementNum)) {
                    if (singleTerm.isActive()) {
                        List<Variable<V>> variables = singleTerm.getVariables();
                        removeVariables(variables);
                    }
                }
                equationsIndexValid = false;
                notifyEquationArrayChange(equationArray, EquationSystemIndexListener.ChangeType.REMOVED);
                break;

            case EQUATION_ACTIVATED:
                for (var equationTermArray : equationArray.getTermArrays()) {
                    int[] termNumsConcatenatedStartIndices = equationTermArray.getTermNumsConcatenatedStartIndices();
                    int iStart = termNumsConcatenatedStartIndices[elementNum];
                    int iEnd = termNumsConcatenatedStartIndices[elementNum + 1];
                    var termNums = equationTermArray.getTermNumsConcatenated();
                    for (int i = iStart; i < iEnd; i++) {
                        int termNum = termNums.get(i);
                        if (equationTermArray.isTermActive(termNum)) {
                            List<Variable<V>> variables = equationTermArray.getTermDerivatives(termNum).stream().map(Derivative::getVariable).toList();
                            addVariables(variables);
                        }
                    }
                }
                for (var singleTerm : equationArray.getSingleEquationTerms(elementNum)) {
                    if (singleTerm.isActive()) {
                        List<Variable<V>> variables = singleTerm.getVariables();
                        addVariables(variables);
                    }
                }
                equationsIndexValid = false;
                notifyEquationArrayChange(equationArray, EquationSystemIndexListener.ChangeType.ADDED);
                break;

            default:
                throw new IllegalStateException("Event type not supported: " + eventType);
        }
    }

    @Override
    public void onEquationTermArrayChange(EquationTermArray<V, E> equationTermArray, int termNum, EquationTermEventType eventType) {
        var variables = equationTermArray.getTermDerivatives(termNum).stream().map(Derivative::getVariable).toList();
        int equationElementNum = equationTermArray.getEquationElementNum(termNum);
        if (equationTermArray.getEquationArray().isElementActive(equationElementNum)) {
            switch (eventType) {
                case EQUATION_TERM_ADDED:
                    if (equationTermArray.isTermActive(termNum)) {
                        addVariables(variables);
                    }
                    notifyEquationTermArrayChange(equationTermArray, termNum, EquationSystemIndexListener.ChangeType.ADDED);
                    break;

                case EQUATION_TERM_ACTIVATED:
                    addVariables(variables);
                    notifyEquationTermArrayChange(equationTermArray, termNum, EquationSystemIndexListener.ChangeType.ADDED);
                    break;

                case EQUATION_TERM_DEACTIVATED:
                    removeVariables(variables);
                    notifyEquationTermArrayChange(equationTermArray, termNum, EquationSystemIndexListener.ChangeType.REMOVED);
                    break;

                default:
                    throw new IllegalStateException("Event type not supported: " + eventType);
            }
        }
    }

    public List<SingleEquation<V, E>> getSortedSingleEquationsToSolve() {
        update();
        return sortedSingleEquationsToSolve;
    }

    public List<EquationArray<V, E>> getSortedEquationArraysToSolve() {
        update();
        return sortedEquationArraysToSolve;
    }

    public Equation<V, E> getEquationAtColumn(int column) {
        update();
        if (sortedEquationArraysToSolve.isEmpty()) {
            return sortedSingleEquationsToSolve.get(column);
        }
        int equationsFromArrayExplored = 0;
        for (EquationArray<V, E> equationArray : sortedEquationArraysToSolve) {
            if (column < equationArray.getFirstColumn()) {
                return sortedSingleEquationsToSolve.get(column - equationsFromArrayExplored);
            }
            if (column >= equationArray.getFirstColumn()
                    && column < equationArray.getFirstColumn() + equationArray.getLength()) {
                int elementNum = equationArray.getColumnToElementNum(column);
                return equationArray.getElement(elementNum);
            }
            equationsFromArrayExplored += equationArray.getLength();
        }
        throw new PowsyblException("Equation of column " + column + " not found");
    }

    /**
     * The sorted variables to find, bringing only the VARIABLE side of the index up to date (rows assigned).
     * For a caller that writes a state and reads values through variable rows but never solves: the equation
     * side stays as it is and is brought up to date by the next caller that needs equation columns, exactly
     * as it would have been after {@link #getSortedVariablesToFind()} - the two sides are independent.
     */
    public List<Variable<V>> getSortedVariablesToFindOnly() {
        if (!variablesIndexValid) {
            updateVariablesToFind();
        }
        return sortedVariablesToFind;
    }

    public List<Variable<V>> getSortedVariablesToFind() {
        update();
        return sortedVariablesToFind;
    }

    public int getColumnCount() {
        update();
        return columnCount;
    }

    public int getRowCount() {
        update();
        return rowCount;
    }
}
