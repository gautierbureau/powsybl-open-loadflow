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
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.hash.TIntHashSet;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
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
 *     holds its voltage and the active balance of the slack bus are entries of it: what the outer loops read. It is
 *     NaN where the equation cannot be evaluated: a disabled bus's.</li>
 * </ul>
 *
 * <p>The pattern only grows, and only when an active equation needs entries it does not hold. With
 * {@link Scope#ALL} it holds from the start every equation on its own slot and on the candidates its
 * {@link Placement} gives: it then grows only when an equation takes a slot that is neither. With
 * {@link Scope#ACTIVE} it also grows the first time an equation becomes active.
 *
 * <p>When OLF creates an equation the structure has not seen, the arrays are compiled again, and the pattern is
 * kept if the new arrays fit in it.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class FixedEquationSystem<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>
        implements EquationSystemListener<V, E>, AutoCloseable {

    /** Which equations have their entries in the pattern from the start. */
    public enum Scope {
        /** Every equation OLF created, on the slot it is paired with and on its candidates. */
        ALL,
        /** The active equations only. */
        ACTIVE
    }

    private final EquationSystem<V, E> equationSystem;
    private final Placement<V, E> placement;
    private final Scope scope;

    private FixedStructure<V, E> structure;
    /** The structure that was discarded, until the arrays are compiled again: the new one takes its pattern if it can. */
    private FixedStructure<V, E> discarded;
    private final TIntArrayList placedEquations = new TIntArrayList();
    private final TIntArrayList placedSlots = new TIntArrayList();
    /** The placements the pattern holds: the equations on their own slot as a bit set, the others as pairs. */
    private final BitSet placedOnOwnSlot = new BitSet();
    private final Set<Long> placed = new HashSet<>();
    private boolean patternValid;
    private boolean placementValid;
    /** The single equations that have other terms than when their rows were computed. */
    private final TIntHashSet changedTerms = new TIntHashSet();

    private int[] slotOfEquation;
    private int[] equationOfSlot;
    /** The row of the variable of each slot in OLF's index, as of the last placement: negative when it is not in the system. */
    private int[] rowOfSlot;
    /** OLF's state vector, by row, during a fill. */
    private double[] state;
    /** The linear terms of the structure, with their activity as of the last placement. */
    private FixedStructure.LinearTerms<V, E> linearTerms;
    /** For the search of a placement: the slots already tried for the equation being placed. */
    private int[] visited;
    private int stamp;

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
        if (structure != null && !changedTerms.isEmpty() && !addRows()) {
            invalidate(true);
        }
        changedTerms.clear();
        if (structure == null) {
            structure = new FixedStructure<>(equationSystem, placement);
            structureBuildCount++;
            slotOfEquation = new int[structure.getEquationCount()];
            equationOfSlot = new int[structure.getSlotCount()];
            rowOfSlot = new int[structure.getSlotCount()];
            visited = new int[structure.getSlotCount()];
            stamp = 0;
            balance = new double[structure.getSlotCount()];
            equationValues = new double[structure.getSlotCount()];
            placed.clear();
            placedOnOwnSlot.clear();
            placedEquations.resetQuick();
            placedSlots.resetQuick();
            patternValid = discarded != null && structure.adoptPattern(discarded);
            discarded = null;
            placementValid = false;
            if (scope == Scope.ALL) {
                for (int equation = 0; equation < structure.getEquationCount(); equation++) {
                    if (structure.getOwnSlot(equation) >= 0) {
                        addToPattern(equation, structure.getOwnSlot(equation));
                    }
                    for (int slot : structure.getCandidates(equation)) {
                        addToPattern(equation, slot);
                    }
                }
            }
        }
        boolean placedAgain = !placementValid;
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
        structure.updateDestinations();
        // an activation always calls for a placement: the activity of the linear terms is read again with it
        FixedStructure.LinearTerms<V, E> current = structure.getLinearTerms();
        if (placedAgain && current == linearTerms) {
            current.updateActivity();
        }
        linearTerms = current;
    }

    /**
     * The equations whose terms changed take the rows of their new terms; the pattern stands when the columns they
     * are placed in already hold them. Returns false when a new term depends on a variable that has no slot.
     */
    private boolean addRows() {
        for (TIntIterator it = changedTerms.iterator(); it.hasNext();) {
            if (!structure.addRows(it.next())) {
                return false;
            }
        }
        for (int i = 0; i < placedEquations.size() && patternValid; i++) {
            if (changedTerms.contains(placedEquations.getQuick(i))) {
                patternValid = structure.holds(placedSlots.getQuick(i), placedEquations.getQuick(i));
            }
        }
        return true;
    }

    private void addToPattern(int equation, int slot) {
        if (slot == structure.getOwnSlot(equation)) {
            if (placedOnOwnSlot.get(equation)) {
                return;
            }
            placedOnOwnSlot.set(equation);
        } else if (!placed.add(((long) equation << 32) | slot)) {
            return;
        }
        placedEquations.add(equation);
        placedSlots.add(slot);
        // the pattern stands when the column already holds the rows of the equation
        patternValid = patternValid && structure.holds(slot, equation);
    }

    /**
     * Gives each active equation a slot. A slot is live when an active term depends on its variable: OLF's index keeps
     * a row for exactly those variables. Each equation takes the slot it is paired with, when it depends on its
     * variable; when two claim one, the one that fixes the variable keeps it. The others are placed one by one on a
     * slot they may take and whose variable they depend on: the one they had before or a candidate, which are in the
     * pattern already. An equation in the way moves the same way. So no slot holds an equation that does not depend on
     * its variable, a zero on the diagonal, as long as the candidates allow it. Any placement gives the same system,
     * with the equations in another order.
     */
    private void place() {
        equationSystem.getIndex().getSortedVariablesToFind();
        for (int slot = 0; slot < rowOfSlot.length; slot++) {
            rowOfSlot[slot] = structure.getVariable(slot).getRow();
        }
        Arrays.fill(slotOfEquation, -1);
        Arrays.fill(equationOfSlot, -1);
        TIntArrayList displaced = new TIntArrayList();
        for (int equation = 0; equation < structure.getEquationCount(); equation++) {
            if (!structure.isActive(equation)) {
                continue;
            }
            int own = structure.getOwnSlot(equation);
            if (own < 0 || !isLive(own) || !structure.dependsOn(equation, own)) {
                displaced.add(equation);
            } else if (equationOfSlot[own] < 0) {
                take(equation, own);
            } else if (structure.fixesVariable(equation) && !structure.fixesVariable(equationOfSlot[own])) {
                displaced.add(equationOfSlot[own]);
                slotOfEquation[equationOfSlot[own]] = -1;
                take(equation, own);
            } else {
                displaced.add(equation);
            }
        }
        // a displaced equation takes a slot it may take and depends on; an equation in its way moves the same way
        TIntArrayList left = new TIntArrayList();
        for (int i = 0; i < displaced.size(); i++) {
            int equation = displaced.getQuick(i);
            stamp++;
            if (!move(equation, true)) {
                left.add(equation);
            }
        }
        // what is left has no such slot. It takes one it may take all the same, which leaves a zero on the diagonal
        // and keeps the pattern; only then any free one
        for (int i = 0; i < left.size(); i++) {
            int equation = left.getQuick(i);
            stamp++;
            if (!move(equation, false)) {
                int slot = findFreeSlot(equation);
                if (slot < 0) {
                    throw new PowsyblException("More active equations than variables: no slot left for "
                            + structure.getType(equation) + " of element " + structure.getElementNum(equation));
                }
                take(equation, slot);
            }
        }
        for (int slot = 0; slot < equationOfSlot.length; slot++) {
            if (isFree(slot)) {
                throw new PowsyblException("More variables than active equations: no equation for " + structure.getVariable(slot));
            }
        }
        for (int equation = 0; equation < slotOfEquation.length; equation++) {
            int slot = slotOfEquation[equation];
            if (slot >= 0) {
                structure.setDestinationSlot(equation, slot);
                addToPattern(equation, slot);
            }
        }
    }

    /**
     * Gives the equation one of the slots it may take: its own, the one it had or a candidate. When the slot is
     * held, its equation is moved the same way first: one that has no other slot to go to keeps it. This is the
     * search of an augmenting path. With {@code dependent}, only slots whose variable the equations depend on are
     * taken: together the moves then leave no zero on the diagonal when such a placement exists.
     */
    private boolean move(int equation, boolean dependent) {
        int own = structure.getOwnSlot(equation);
        if (own >= 0 && moveTo(equation, own, dependent)) {
            return true;
        }
        for (int slot : structure.getCandidates(equation)) {
            if (moveTo(equation, slot, dependent)) {
                return true;
            }
        }
        return false;
    }

    private boolean moveTo(int equation, int slot, boolean dependent) {
        if (visited[slot] == stamp || !isLive(slot) || dependent && !structure.dependsOn(equation, slot)) {
            return false;
        }
        visited[slot] = stamp;
        int other = equationOfSlot[slot];
        if (other >= 0 && !move(other, dependent)) {
            return false;
        }
        take(equation, slot);
        return true;
    }

    private void take(int equation, int slot) {
        slotOfEquation[equation] = slot;
        equationOfSlot[slot] = equation;
    }

    private boolean isLive(int slot) {
        return rowOfSlot[slot] >= 0;
    }

    private boolean isFree(int slot) {
        return equationOfSlot[slot] < 0 && isLive(slot);
    }

    private int findFreeSlot(int equation) {
        for (int slot : structure.getCandidates(equation)) {
            if (isFree(slot)) {
                return slot;
            }
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
     * The same, with the Jacobian values written at the start of the given array: the value array of a sparse matrix
     * built on the pattern of {@link #getStructure()}, so that a solver fills its matrix in place.
     */
    public void fill(double[] jacobianValues) {
        update();
        if (jacobianValues.length < structure.getValueCount()) {
            throw new IllegalArgumentException("The pattern has " + structure.getValueCount() + " values, the array " + jacobianValues.length);
        }
        Arrays.fill(jacobianValues, 0, structure.getValueCount(), 0);
        Arrays.fill(balance, 0);
        state = equationSystem.getStateVector().get();
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
                    if (slot >= 0) {
                        // the destinations are for the slot the equation holds, its own or another
                        int[] destination = terms.destination[term];
                        for (int k = 0; k < destination.length; k++) {
                            jacobianValues[destination[k]] += termDerivatives[localIndex[k]][element];
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

    /**
     * Adds the derivatives of the active object terms of an equation in a column (none when negative) and returns their
     * value. An equation that holds no slot can have an active term on a variable that left the system, a disabled
     * bus's: it then has no value, and the result is NaN.
     */
    private double addSingleTerms(double[] jacobianValues, int equation, int column) {
        if (!structure.isMixed(equation)) {
            return addLinearTerms(jacobianValues, equation, column);
        }
        FixedStructure.ObjectTerms<V, E> compiled = structure.getObjectTerms(equation);
        if (compiled == null) {
            return 0;
        }
        double value = 0;
        for (int t = 0; t < compiled.row.length; t++) {
            SingleEquationTerm<V, E> term = compiled.terms.get(t);
            if (term.isActive()) {
                List<Variable<V>> variables = compiled.variables.get(t);
                if (column < 0 && !isInSystem(variables)) {
                    return Double.NaN;
                }
                value += term.eval();
                if (column >= 0) {
                    for (int k = 0; k < compiled.row[t].length; k++) {
                        jacobianValues[compiled.destination[t][k]] += term.der(variables.get(k));
                    }
                }
            }
        }
        return value;
    }

    /**
     * The same for an equation whose object terms are all linear, from the arrays of the structure: a coefficient
     * times the value of a variable, and the coefficient as the derivative. No object is called, unless a term
     * computes its coefficient.
     */
    private double addLinearTerms(double[] jacobianValues, int equation, int column) {
        double value = 0;
        for (int i = linearTerms.start[equation]; i < linearTerms.start[equation + 1]; i++) {
            if (linearTerms.active[i]) {
                int slot = linearTerms.slot[i];
                if (column < 0 && rowOfSlot[slot] < 0) {
                    return Double.NaN;
                }
                double coefficient = linearTerms.coefficient[i];
                if (Double.isNaN(coefficient)) {
                    coefficient = linearTerms.terms.get(i).der(linearTerms.variables.get(i));
                }
                value += coefficient * state[rowOfSlot[slot]];
                if (column >= 0) {
                    jacobianValues[linearTerms.destination[i]] += coefficient;
                }
            }
        }
        return value;
    }

    private boolean isInSystem(List<Variable<V>> variables) {
        for (int k = 0; k < variables.size(); k++) {
            if (variables.get(k).getRow() < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * An equation that is removed and then created again, with other terms, is the same equation for the structure:
     * it keeps its number and its slot. OLF does so with the distribution equations of a voltage control when a zero
     * impedance network splits or merges.
     */
    @Override
    public void onEquationChange(SingleEquation<V, E> equation, EquationEventType eventType) {
        boolean kept = switch (eventType) {
            case EQUATION_REMOVED -> structure != null && structure.unbind(equation);
            case EQUATION_CREATED -> structure != null && termsChanged(structure.bind(equation));
            default -> true;
        };
        invalidate(!kept);
    }

    @Override
    public void onEquationArrayChange(EquationArray<V, E> equationArray, int elementNum, EquationEventType eventType) {
        invalidate(eventType == EquationEventType.EQUATION_CREATED || eventType == EquationEventType.EQUATION_REMOVED);
    }

    @Override
    public void onEquationTermChange(SingleEquationTerm<V, E> term, EquationTermEventType eventType) {
        invalidate(eventType == EquationTermEventType.EQUATION_TERM_ADDED
                && !(structure != null && termsChanged(structure.getNumber(term.getEquation()))));
    }

    /** Notes that a single equation of the structure has other terms than when its rows were computed. */
    private boolean termsChanged(int equation) {
        if (equation >= 0) {
            changedTerms.add(equation);
        }
        return equation >= 0;
    }

    @Override
    public void onEquationTermArrayChange(EquationTermArray<V, E> equationTermArray, int termNum, EquationTermEventType eventType) {
        invalidate(eventType == EquationTermEventType.EQUATION_TERM_ADDED);
    }

    @Override
    public void onEquationTermConstantsChange() {
        // values only
    }

    /** An activation moves equations between slots; an equation or a term the structure cannot keep changes the compiled arrays. */
    private void invalidate(boolean compiledArrays) {
        placementValid = false;
        if (compiledArrays && structure != null) {
            discarded = structure;
            structure = null;
        }
    }

    public FixedStructure<V, E> getStructure() {
        update();
        return structure;
    }

    /**
     * How many slots hold an equation that does not depend, in the current state, on their variable: the zeros on
     * the diagonal. A factorisation without pivoting needs none.
     */
    public int getZeroDiagonalCount() {
        update();
        int count = 0;
        for (int slot = 0; slot < equationOfSlot.length; slot++) {
            if (equationOfSlot[slot] >= 0 && !structure.dependsOnNow(equationOfSlot[slot], slot)) {
                count++;
            }
        }
        return count;
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
