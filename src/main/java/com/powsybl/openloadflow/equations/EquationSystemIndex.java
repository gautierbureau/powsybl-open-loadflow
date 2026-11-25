/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.commons.PowsyblException;
import gnu.trove.list.array.TIntArrayList;
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

    /*
     * The rows and the element numbers of the variables to find of each type (by ordinal), in the order of
     * sortedVariablesToFind: computed when first asked for after the variables are sorted again.
     */
    private int[][] rowsByType;
    private int[][] elementNumsByType;

    private int columnCount = 0;

    private int columnCountFromArrayEquations = 0;

    private int rowCount = 0;

    private boolean equationsIndexValid = false;

    private boolean variablesIndexValid = false;

    private final List<EquationSystemIndexListener<V, E>> listeners = new ArrayList<>();

    /*
     * Ghost mode: an equation deactivated while it has a column keeps it, as a ghost, and a variable no active term
     * refers to anymore keeps its row, as an orphan. The columns and the rows then do not change when a contingency
     * isolates buses and when they come back: the vectors keep their layout, zero at the ghost columns, and the index
     * is not built again. A ghost equation and an orphan variable are not in the system they solve: a solver that keeps
     * the identity for the variables that no equation holds (the fixed structure) solves it unchanged. A real change
     * of structure (an equation or a variable that has no column or row, an equation removed) builds the index again,
     * as without ghost mode, the ghosts and the orphans being removed first.
     */
    private boolean ghostMode;
    private final Set<SingleEquation<V, E>> ghostSingleEquations = new LinkedHashSet<>();
    private final Set<Variable<V>> orphanVariables = new LinkedHashSet<>();
    /** The active single equations without a column, and the variables referred to without a row, since the last build. */
    private final Set<SingleEquation<V, E>> newEquations = new HashSet<>();
    private final Set<Variable<V>> newVariables = new HashSet<>();
    /** Ghosts, orphans or new equations and variables changed since the last update. */
    private boolean ghostsChanged;
    /**
     * An equation removed from the system: the index is built again at the next update. Equations and variables
     * without column or row are judged at the update, on the net effect of the changes: the activations made while a
     * bus is enabled again, and undone before the update, change nothing.
     */
    private boolean rebuildNeeded;
    private int[] ghostColumns = new int[0];

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

    private void notifyEquationTermConstantsChanged() {
        listeners.forEach(EquationSystemIndexListener::onEquationTermConstantsChanged);
    }

    private void notifyVariableOrphanChange(Variable<V> variable, boolean orphan) {
        listeners.forEach(listener -> listener.onVariableOrphanChange(variable, orphan));
    }

    public boolean isGhostMode() {
        return ghostMode;
    }

    /**
     * Enables or disables the ghost mode (see its fields). Disabling it removes the ghosts and the orphans: the index
     * is then built again if there were any.
     */
    public void setGhostMode(boolean ghostMode) {
        if (ghostMode == this.ghostMode) {
            return;
        }
        update();
        if (!ghostMode) {
            removeGhosts();
        }
        this.ghostMode = ghostMode;
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            equationArray.setGhostMode(ghostMode);
        }
        ghostsChanged = false;
        rebuildNeeded = false;
    }

    /**
     * Resolves the pending changes: in ghost mode the index is built again only when it is read. A listener that reads
     * the index after deciding what to update calls this first, so that it is notified of what changed before.
     */
    public void validate() {
        update();
    }

    /** True when the variable keeps its row as an orphan: no active term refers to it. */
    public boolean isOrphan(Variable<V> variable) {
        return !orphanVariables.isEmpty() && orphanVariables.contains(variable);
    }

    /** The columns of the ghost equations, sorted: zero in the vectors, and not equations of the system solved. */
    public int[] getGhostColumns() {
        update();
        return ghostColumns;
    }

    /**
     * Resolves the changes since the last update: the ghosts absorb them, or the index is built again, the ghosts and
     * the orphans being removed first and the listeners notified of what they would have been without ghost mode.
     */
    private boolean hasNewEquationsOrVariables() {
        if (!newEquations.isEmpty() || !newVariables.isEmpty()) {
            return true;
        }
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            if (equationArray.getNewActiveCount() > 0) {
                return true;
            }
        }
        return false;
    }

    private void updateGhosts() {
        if (!rebuildNeeded && !ghostsChanged && equationsIndexValid && variablesIndexValid) {
            return;
        }
        if (rebuildNeeded || !equationsIndexValid || !variablesIndexValid || hasNewEquationsOrVariables()) {
            rebuildNeeded = false;
            ghostsChanged = false;
            List<Variable<V>> addedVariables = new ArrayList<>(newVariables);
            List<SingleEquation<V, E>> addedEquations = new ArrayList<>(newEquations);
            newEquations.clear();
            newVariables.clear();
            removeGhosts();
            equationsIndexValid = false;
            variablesIndexValid = false;
            for (Variable<V> variable : addedVariables) {
                notifyVariableChange(variable, EquationSystemIndexListener.ChangeType.ADDED);
            }
            for (SingleEquation<V, E> equation : addedEquations) {
                notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.ADDED);
            }
        } else if (ghostsChanged) {
            ghostsChanged = false;
            ghostColumns = computeGhostColumns();
            listeners.forEach(EquationSystemIndexListener::onGhostChange);
        }
    }

    private int[] computeGhostColumns() {
        TIntArrayList columns = new TIntArrayList();
        for (SingleEquation<V, E> equation : ghostSingleEquations) {
            columns.add(equation.getColumn());
        }
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            if (equationArray.getGhostCount() > 0) {
                int[] elementNumToColumn = equationArray.getElementNumToColumn();
                for (int elementNum = 0; elementNum < equationArray.getElementCount(); elementNum++) {
                    if (equationArray.isElementGhost(elementNum)) {
                        columns.add(elementNumToColumn[elementNum]);
                    }
                }
            }
        }
        columns.sort();
        return columns.toArray();
    }

    /** The ghosts lose their column and the orphans their row; the listeners are notified as without ghost mode. */
    private void removeGhosts() {
        List<SingleEquation<V, E>> ghosts = new ArrayList<>(ghostSingleEquations);
        ghostSingleEquations.clear();
        for (SingleEquation<V, E> equation : ghosts) {
            equation.setColumn(-1);
            sortedSetEquationsToSolve.remove(equation);
        }
        boolean arrayGhosts = false;
        for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
            arrayGhosts |= equationArray.getGhostCount() > 0;
            equationArray.removeGhosts();
        }
        List<Variable<V>> orphans = new ArrayList<>(orphanVariables);
        orphanVariables.clear();
        for (Variable<V> variable : orphans) {
            variable.setRow(-1);
            sortedMapVariablesToFindRefCount.remove(variable);
        }
        ghostColumns = new int[0];
        if (!ghosts.isEmpty() || arrayGhosts || !orphans.isEmpty()) {
            equationsIndexValid = false;
            variablesIndexValid = false;
        }
        for (Variable<V> variable : orphans) {
            notifyVariableOrphanChange(variable, false);
            notifyVariableChange(variable, EquationSystemIndexListener.ChangeType.REMOVED);
        }
        for (SingleEquation<V, E> equation : ghosts) {
            notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.REMOVED);
        }
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

    private void updateVariablesToFind(Predicate<V> isSeparatedInFirstPart) {
        rowsByType = null;
        elementNumsByType = null;
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
        if (ghostMode) {
            updateGhosts();
        }
        if (!equationsIndexValid) {
            updateEquationsToSolve();
            if (ghostMode) {
                for (EquationArray<V, E> equationArray : equationSystem.getEquationArrays()) {
                    equationArray.snapshotStructure();
                }
            }
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
                if (ghostMode) {
                    // no row yet: the index is built again at the next update if it still has none then
                    newVariables.add(variable);
                    ghostsChanged = true;
                } else {
                    variablesIndexValid = false;
                    notifyVariableChange(variable, EquationSystemIndexListener.ChangeType.ADDED);
                }
            } else {
                if (variableRefCount.intValue() == 0 && orphanVariables.remove(variable)) {
                    ghostsChanged = true;
                    notifyVariableOrphanChange(variable, false);
                }
                variableRefCount.increment();
            }
        }
    }

    private void addEquation(SingleEquation<V, E> equation) {
        if (ghostMode) {
            if (ghostSingleEquations.remove(equation)) {
                // a ghost active again, on its column
                ghostsChanged = true;
            } else {
                // no column yet: the index is built again at the next update if it is still active then
                sortedSetEquationsToSolve.add(equation);
                newEquations.add(equation);
                ghostsChanged = true;
            }
            addEquationTerms(equation);
            return;
        }
        sortedSetEquationsToSolve.add(equation);
        equationsIndexValid = false;
        addEquationTerms(equation);
        notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.ADDED);
    }

    private void addEquationTerms(SingleEquation<V, E> equation) {
        for (SingleEquationTerm<V, E> term : equation.getTerms()) {
            if (term.isActive()) {
                addTerm(term);
            }
        }
    }

    private void removeEquationTerms(SingleEquation<V, E> equation) {
        for (SingleEquationTerm<V, E> term : equation.getTerms()) {
            if (term.isActive()) {
                removeTerm(term);
            }
        }
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
                    if (ghostMode && newVariables.remove(variable)) {
                        // referred to and no more since the last build: it never had a row
                        sortedMapVariablesToFindRefCount.remove(variable);
                    } else if (ghostMode && variable.getRow() >= 0) {
                        // keeps its row, as an orphan
                        orphanVariables.add(variable);
                        ghostsChanged = true;
                        notifyVariableOrphanChange(variable, true);
                    } else {
                        variable.setRow(-1);
                        sortedMapVariablesToFindRefCount.remove(variable);
                        variablesIndexValid = false;
                        notifyVariableChange(variable, EquationSystemIndexListener.ChangeType.REMOVED);
                    }
                }
            }
        }
    }

    /** An equation deactivated, or removed from the system when {@code removed}. */
    private void removeEquation(SingleEquation<V, E> equation, boolean removed) {
        if (ghostMode && !removed) {
            if (newEquations.remove(equation)) {
                // activated and deactivated since the last build: it never had a column
                sortedSetEquationsToSolve.remove(equation);
            } else if (equation.getColumn() >= 0) {
                // keeps its column, as a ghost
                ghostSingleEquations.add(equation);
                ghostsChanged = true;
            } else {
                sortedSetEquationsToSolve.remove(equation);
                rebuildNeeded = true;
            }
            removeEquationTerms(equation);
            return;
        }
        if (ghostMode) {
            newEquations.remove(equation);
            rebuildNeeded = true;
        }
        equation.setColumn(-1);
        sortedSetEquationsToSolve.remove(equation);
        equationsIndexValid = false;
        removeEquationTerms(equation);
        notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.REMOVED);
    }

    @Override
    public void onEquationChange(SingleEquation<V, E> equation, EquationEventType eventType) {
        switch (eventType) {
            case EQUATION_REMOVED:
                if (equation.isActive()) {
                    removeEquation(equation, true);
                } else if (ghostSingleEquations.remove(equation)) {
                    // a ghost removed from the system: its terms have left the index already
                    equation.setColumn(-1);
                    sortedSetEquationsToSolve.remove(equation);
                    equationsIndexValid = false;
                    rebuildNeeded = true;
                    notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.REMOVED);
                }
                break;

            case EQUATION_DEACTIVATED:
                removeEquation(equation, false);
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
                if (ghostMode) {
                    ghostsChanged = true;
                } else {
                    equationsIndexValid = false;
                    notifyEquationArrayChange(equationArray, EquationSystemIndexListener.ChangeType.REMOVED);
                }
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
                if (ghostMode) {
                    ghostsChanged = true;
                } else {
                    equationsIndexValid = false;
                    notifyEquationArrayChange(equationArray, EquationSystemIndexListener.ChangeType.ADDED);
                }
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

    @Override
    public void onEquationTermConstantsChange() {
        notifyEquationTermConstantsChanged();
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

    public List<Variable<V>> getSortedVariablesToFind() {
        update();
        return sortedVariablesToFind;
    }

    /**
     * The rows of the variables to find of a type, in the order of {@link #getSortedVariablesToFind()}: the state of
     * the variables of a type is read without going through each variable.
     */
    public int[] getRowsOfType(V type) {
        updateVariablesByType();
        return type.ordinal() < rowsByType.length ? rowsByType[type.ordinal()] : NO_ROW;
    }

    /** The element numbers of the variables of {@link #getRowsOfType(Enum)}, in the same order. */
    public int[] getElementNumsOfType(V type) {
        updateVariablesByType();
        return type.ordinal() < elementNumsByType.length ? elementNumsByType[type.ordinal()] : NO_ROW;
    }

    private static final int[] NO_ROW = new int[0];

    private void updateVariablesByType() {
        update();
        if (rowsByType != null) {
            return;
        }
        int typeCount = 0;
        for (Variable<V> variable : sortedVariablesToFind) {
            typeCount = Math.max(typeCount, variable.getType().ordinal() + 1);
        }
        int[] counts = new int[typeCount];
        for (Variable<V> variable : sortedVariablesToFind) {
            counts[variable.getType().ordinal()]++;
        }
        int[][] rows = new int[typeCount][];
        int[][] elementNums = new int[typeCount][];
        for (int t = 0; t < typeCount; t++) {
            rows[t] = new int[counts[t]];
            elementNums[t] = new int[counts[t]];
        }
        int[] next = new int[typeCount];
        for (Variable<V> variable : sortedVariablesToFind) {
            int t = variable.getType().ordinal();
            rows[t][next[t]] = variable.getRow();
            elementNums[t][next[t]] = variable.getElementNum();
            next[t]++;
        }
        rowsByType = rows;
        elementNumsByType = elementNums;
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
