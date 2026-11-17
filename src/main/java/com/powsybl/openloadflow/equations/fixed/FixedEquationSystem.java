/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

import com.powsybl.commons.PowsyblException;
import com.powsybl.openloadflow.equations.EquationArray;
import com.powsybl.openloadflow.equations.EquationEventType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationSystemListener;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.EquationTermEventType;
import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.equations.SingleEquation;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import gnu.trove.list.array.TIntArrayList;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * OLF's equation system seen as a matrix whose shape does not change: one slot per variable, and each active equation
 * written in the column of the variable it is paired with. A slot no equation holds keeps its variable where it is
 * ({@code x = x}). What OLF activates or deactivates changes the values and which equation a slot holds, not the
 * pattern.
 *
 * <p>Nothing of the model is re-derived: the equations, the terms, the formulas and the activations are OLF's, read
 * through its own API and followed through its events. The only model knowledge is the {@link Placement}.
 *
 * <p>A fill computes three arrays:
 * <ul>
 * <li>the Jacobian values, on the pattern of the {@link FixedStructure};</li>
 * <li>the value of the equation each slot holds, 0 for a slot that holds none;</li>
 * <li>the <b>balance</b> of each slot: the value of the equation paired with it that does not fix its variable (a
 *     bus's active or reactive balance), whether or not that equation is active. The reactive balance of a bus that
 *     holds its voltage and the active balance of the slack bus are entries of it: what the outer loops read.</li>
 * </ul>
 *
 * <p>The pattern only grows, and only when an active equation needs entries it does not hold: with
 * {@link Scope#ALL}, when a balance takes a slot it never took; with {@link Scope#ACTIVE}, also the first time an
 * equation becomes active.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class FixedEquationSystem<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>
        implements EquationSystemListener<V, E>, AutoCloseable {

    /** Which equations have their entries in the pattern from the start. */
    public enum Scope {
        /** Every equation OLF created, on the slot it is paired with. */
        ALL,
        /** The active equations only. */
        ACTIVE
    }

    private final EquationSystem<V, E> equationSystem;
    private final Placement<V, E> placement;
    private final Scope scope;

    private FixedStructure<V, E> structure;
    private final TIntArrayList placedEquations = new TIntArrayList();
    private final TIntArrayList placedSlots = new TIntArrayList();
    private final Set<Long> placed = new HashSet<>();
    private boolean patternValid;
    private boolean placementValid;

    private int[] slotOfEquation;
    private int[] equationOfSlot;
    /** For an equation that took a free slot: the slot, kept as long as it is free, so that the pattern stops growing. */
    private int[] movedTo;

    private double[] values;
    private double[] balance;
    private double[] equationValues;

    private int structureBuildCount;
    private int patternBuildCount;

    public FixedEquationSystem(EquationSystem<V, E> equationSystem, Placement<V, E> placement, Scope scope) {
        this.equationSystem = equationSystem;
        this.placement = placement;
        this.scope = scope;
        equationSystem.addListener(this);
    }

    @Override
    public void close() {
        equationSystem.removeListener(this);
    }

    private void update() {
        if (structure == null) {
            structure = new FixedStructure<>(equationSystem, placement);
            structureBuildCount++;
            slotOfEquation = new int[structure.getEquationCount()];
            movedTo = new int[structure.getEquationCount()];
            Arrays.fill(movedTo, -1);
            equationOfSlot = new int[structure.getSlotCount()];
            balance = new double[structure.getSlotCount()];
            equationValues = new double[structure.getSlotCount()];
            placed.clear();
            placedEquations.resetQuick();
            placedSlots.resetQuick();
            patternValid = false;
            placementValid = false;
            if (scope == Scope.ALL) {
                for (int equation = 0; equation < structure.getEquationCount(); equation++) {
                    if (structure.getOwnSlot(equation) >= 0) {
                        addToPattern(equation, structure.getOwnSlot(equation));
                    }
                }
            }
        }
        if (!placementValid) {
            place();
            placementValid = true;
        }
        if (!patternValid) {
            structure.buildPattern(placedEquations, placedSlots);
            values = new double[structure.getValueCount()];
            patternBuildCount++;
            patternValid = true;
        }
    }

    private void addToPattern(int equation, int slot) {
        if (placed.add(((long) equation << 32) | slot)) {
            placedEquations.add(equation);
            placedSlots.add(slot);
            // the pattern stands when the column already holds the rows of the equation
            patternValid = patternValid && structure.holds(slot, equation);
        }
    }

    /**
     * Gives each active equation a slot. A slot is live when an active term depends on its variable: OLF's index keeps
     * a row for exactly those variables. Each equation takes the slot it is paired with; when two claim one, the one
     * that fixes the variable keeps it. The others take a free live slot: the one they had before, else one of a
     * variable they depend on, else any. Any choice gives the same system, with the equations in another order.
     */
    private void place() {
        equationSystem.getIndex().getSortedVariablesToFind();
        Arrays.fill(slotOfEquation, -1);
        Arrays.fill(equationOfSlot, -1);
        TIntArrayList displaced = new TIntArrayList();
        for (int equation = 0; equation < structure.getEquationCount(); equation++) {
            if (!structure.isActive(equation)) {
                continue;
            }
            int own = structure.getOwnSlot(equation);
            int other = own >= 0 && isLive(own) ? equationOfSlot[own] : Integer.MAX_VALUE;
            if (other < 0) {
                take(equation, own);
            } else if (other != Integer.MAX_VALUE && structure.fixesVariable(equation) && !structure.fixesVariable(other)) {
                slotOfEquation[other] = -1;
                displaced.add(other);
                take(equation, own);
            } else {
                displaced.add(equation);
            }
        }
        for (int i = 0; i < displaced.size(); i++) {
            int equation = displaced.getQuick(i);
            int slot = findFreeSlot(equation);
            if (slot < 0) {
                throw new PowsyblException("More active equations than variables: no slot left for "
                        + structure.getType(equation) + " of element " + structure.getElementNum(equation));
            }
            take(equation, slot);
            movedTo[equation] = slot;
        }
        for (int slot = 0; slot < equationOfSlot.length; slot++) {
            if (isFree(slot)) {
                throw new PowsyblException("More variables than active equations: no equation for " + structure.getVariable(slot));
            }
        }
        for (int equation = 0; equation < slotOfEquation.length; equation++) {
            if (slotOfEquation[equation] >= 0) {
                addToPattern(equation, slotOfEquation[equation]);
            }
        }
    }

    private void take(int equation, int slot) {
        slotOfEquation[equation] = slot;
        equationOfSlot[slot] = equation;
    }

    private boolean isLive(int slot) {
        return structure.getVariable(slot).getRow() >= 0;
    }

    private boolean isFree(int slot) {
        return equationOfSlot[slot] < 0 && isLive(slot);
    }

    private int findFreeSlot(int equation) {
        if (movedTo[equation] >= 0 && isFree(movedTo[equation])) {
            return movedTo[equation];
        }
        for (int row : structure.getRows(equation)) {
            if (isFree(row)) {
                return row;
            }
        }
        for (int slot = 0; slot < equationOfSlot.length; slot++) {
            if (isFree(slot)) {
                return slot;
            }
        }
        return -1;
    }

    /** Computes the Jacobian values, the equation values and the balances at the current state of OLF's equation system. */
    public void fill() {
        update();
        fill(values);
    }

    /**
     * The same, with the Jacobian values written in the given array: the value array of a sparse matrix built on the
     * pattern of {@link #getStructure()}, so that a solver fills its matrix in place.
     */
    public void fill(double[] jacobianValues) {
        update();
        if (jacobianValues.length != structure.getValueCount()) {
            throw new IllegalArgumentException("The pattern has " + structure.getValueCount() + " values, the array " + jacobianValues.length);
        }
        Arrays.fill(jacobianValues, 0);
        Arrays.fill(balance, 0);
        for (FixedStructure.ArrayTerms<V, E> terms : structure.getArrayTerms()) {
            double[] termValues = terms.termArray.eval();
            double[][] termDerivatives = terms.termArray.evalDer();
            for (int term = 0; term < terms.equation.length; term++) {
                if (terms.termArray.isTermActive(term)) {
                    int equation = terms.equation[term];
                    int element = terms.element[term];
                    int own = structure.getOwnSlot(equation);
                    int slot = slotOfEquation[equation];
                    int[] localIndex = terms.localIndex[term];
                    balance[own] += termValues[element];
                    if (slot == own) {
                        int[] destination = terms.destination[term];
                        for (int k = 0; k < destination.length; k++) {
                            jacobianValues[destination[k]] += termDerivatives[localIndex[k]][element];
                        }
                    } else if (slot >= 0) {
                        // a balance that took another slot: rare, so its entries are searched
                        for (int k = 0; k < localIndex.length; k++) {
                            add(jacobianValues, slot, terms.row[term][k], termDerivatives[localIndex[k]][element]);
                        }
                    }
                }
            }
        }
        for (int equation : structure.getArrayElementsWithSingleTerms()) {
            balance[structure.getOwnSlot(equation)] += addSingleTerms(jacobianValues, equation, slotOfEquation[equation]);
        }
        // the single equations: derivatives where one is placed; its value there, and as the balance of its own slot
        for (int equation = 0; equation < structure.getSingleEquationCount(); equation++) {
            int slot = slotOfEquation[equation];
            boolean isBalance = structure.getOwnSlot(equation) >= 0 && !structure.fixesVariable(equation);
            if (slot >= 0 || isBalance) {
                double value = addSingleTerms(jacobianValues, equation, slot);
                if (slot >= 0) {
                    equationValues[slot] = value;
                }
                if (isBalance) {
                    balance[structure.getOwnSlot(equation)] = value;
                }
            }
        }
        int[] diagonal = structure.getDiagonal();
        for (int slot = 0; slot < equationOfSlot.length; slot++) {
            int equation = equationOfSlot[slot];
            if (equation < 0) {
                jacobianValues[diagonal[slot]] = 1;
                equationValues[slot] = 0;
            } else if (structure.isArrayElement(equation)) {
                equationValues[slot] = balance[structure.getOwnSlot(equation)];
            }
        }
    }

    /** Adds the derivatives of the active object terms of an equation in a column (none when negative); returns their value. */
    private double addSingleTerms(double[] jacobianValues, int equation, int column) {
        double value = 0;
        for (SingleEquationTerm<V, E> term : structure.getSingleTerms(equation)) {
            if (term.isActive()) {
                value += term.eval();
                if (column >= 0) {
                    for (Variable<V> variable : term.getVariables()) {
                        add(jacobianValues, column, structure.getSlot(variable), term.der(variable));
                    }
                }
            }
        }
        return value;
    }

    private void add(double[] jacobianValues, int column, int row, double value) {
        int index = structure.index(column, row);
        if (index < 0) {
            throw new IllegalStateException("No entry at row " + row + " of column " + column);
        }
        jacobianValues[index] += value;
    }

    @Override
    public void onEquationChange(SingleEquation<V, E> equation, EquationEventType eventType) {
        invalidate(eventType == EquationEventType.EQUATION_CREATED || eventType == EquationEventType.EQUATION_REMOVED);
    }

    @Override
    public void onEquationArrayChange(EquationArray<V, E> equationArray, int elementNum, EquationEventType eventType) {
        invalidate(eventType == EquationEventType.EQUATION_CREATED || eventType == EquationEventType.EQUATION_REMOVED);
    }

    @Override
    public void onEquationTermChange(SingleEquationTerm<V, E> term, EquationTermEventType eventType) {
        invalidate(eventType == EquationTermEventType.EQUATION_TERM_ADDED);
    }

    @Override
    public void onEquationTermArrayChange(EquationTermArray<V, E> equationTermArray, int termNum, EquationTermEventType eventType) {
        invalidate(eventType == EquationTermEventType.EQUATION_TERM_ADDED);
    }

    @Override
    public void onEquationTermConstantsChange() {
        // values only
    }

    /** An activation moves equations between slots; a new or removed equation or term changes the compiled arrays. */
    private void invalidate(boolean compiledArrays) {
        placementValid = false;
        if (compiledArrays) {
            structure = null;
        }
    }

    public FixedStructure<V, E> getStructure() {
        update();
        return structure;
    }

    /**
     * Writes the structure as text, one line per slot: its variable, the equation it holds now, and the variables of
     * the rows of its column. Variables and equations are named by type and element number, so the text does not
     * depend on any numbering: the same text written from another structure of the same network compares the two.
     */
    public void write(Writer writer) throws IOException {
        update();
        int[] columnStart = structure.getColumnStart();
        for (int slot = 0; slot < structure.getSlotCount(); slot++) {
            int equation = equationOfSlot[slot];
            writer.append(name(structure.getVariable(slot))).append(" <- ")
                    .append(equation < 0 ? "none" : structure.getType(equation) + ":" + structure.getElementNum(equation))
                    .append(" |");
            for (int i = columnStart[slot]; i < columnStart[slot + 1]; i++) {
                writer.append(' ').append(name(structure.getVariable(structure.getRowIndices()[i])));
            }
            writer.append('\n');
        }
    }

    private String name(Variable<V> variable) {
        return variable.getType() + ":" + variable.getElementNum();
    }

    /** The slot an equation holds, -1 when it is not active. */
    public int getSlotOfEquation(int equation) {
        update();
        return slotOfEquation[equation];
    }

    /** The equation a slot holds, -1 when it holds none: its variable is then fixed. */
    public int getEquationOfSlot(int slot) {
        update();
        return equationOfSlot[slot];
    }

    /** The Jacobian values of the last {@link #fill()}. */
    public double[] getValues() {
        return values;
    }

    public double[] getEquationValues() {
        return equationValues;
    }

    public double[] getBalance() {
        return balance;
    }

    /** How many times the arrays were compiled: once, plus once per equation or term OLF created or removed afterwards. */
    public int getStructureBuildCount() {
        return structureBuildCount;
    }

    /** How many times the pattern was built: once, plus once each time an equation needed entries it did not hold. */
    public int getPatternBuildCount() {
        return patternBuildCount;
    }
}
