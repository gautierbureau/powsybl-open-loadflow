/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

import com.powsybl.commons.PowsyblException;
import com.powsybl.openloadflow.equations.Equation;
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
import gnu.trove.set.hash.TLongHashSet;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

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

    /** The mode of a slot that holds no equation: its variable keeps its value. */
    public static final int MODE_NONE = -1;

    /** The mode of a slot that holds an equation outside its alternatives: one the placement moved to a free slot. */
    public static final int MODE_OTHER = -2;

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
    private final TLongHashSet placed = new TLongHashSet();
    private boolean patternValid;
    private boolean placementValid;
    /** The single equations that have other terms than when their rows were computed. */
    private final TIntHashSet changedTerms = new TIntHashSet();

    private int[] slotOfEquation;
    private int[] equationOfSlot;
    /** The row of the variable of each slot in OLF's index, as of the last placement: negative when it is not in the system. */
    private int[] rowOfSlot;
    /** For each equation, its column in OLF's index, during an evaluation of the equations. */
    private int[] columnOfEquation;
    /** OLF's state vector, by row, during a fill. */
    private double[] state;
    /** The linear terms of the structure, with their activity as of the last placement. */
    private FixedStructure.LinearTerms<V, E> linearTerms;
    /** Its terms that refer to an element of a term array, with their activity as of the last placement. */
    private FixedStructure.ElementTerms<V, E> elementTerms;
    /** For the search of a placement: the slots already tried for the equation being placed. */
    private int[] visited;
    private int stamp;

    /*
     * The first pass of the placement, kept from one placement to the next: each equation on its own slot when it
     * depends on its live variable. It is local to the equations of one slot, so after an activation only the slots
     * of the equations that changed, and the slots whose variable entered or left the system, are resolved again.
     */
    /** For each slot, the equations whose own slot it is, in their order. */
    private int[][] ownersOfSlot;
    /** The equations that have no own slot. */
    private int[] withoutOwnSlot;
    /** For each slot, the equation the first pass puts on it, or -1. */
    private int[] firstPassEquationOfSlot;
    /**
     * The equations the first pass leaves without a slot, each at the position the full pass would list it: its own
     * number, or the number of the equation that took its slot from it.
     */
    private int[] displacedAt;
    private final BitSet displacedPositions = new BitSet();
    /** False when the first pass has to be computed for every equation: a new structure, an equation created or removed. */
    private boolean firstPassValid;
    /** The equations whose activity, or the activity of a term, changed since the last placement. */
    private final TIntHashSet changedEquations = new TIntHashSet();
    /** The slot of each equation at the last placement, to visit only the equations that moved. */
    private int[] lastSlotOfEquation;
    /** False when every equation has to be visited after the placement: a new structure. */
    private boolean lastPlacementValid;
    private int placementCount;

    /** For each slot, the position of the equation it holds among its alternatives, as of the last placement. */
    private int[] modes;
    private boolean modesValid;

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
        if (!changedTerms.isEmpty()) {
            // a trove set clears its whole table, even when it is empty
            changedTerms.clear();
        }
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
            initFirstPass();
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
            modesValid = false;
        }
        if (placedAgain || columnOfEquation == null || columnOfEquation.length != structure.getEquationCount()) {
            // the columns of OLF's index change only with an activation, which calls for a placement: they are read
            // once by placement, not at each evaluation
            columnOfEquation = new int[structure.getEquationCount()];
            for (int equation = 0; equation < columnOfEquation.length; equation++) {
                columnOfEquation[equation] = structure.getColumnInEquationSystem(equation);
            }
        }
        if (!patternValid) {
            structure.buildPattern(placedEquations, placedSlots);
            values = new double[structure.getValueCount()];
            patternBuildCount++;
            patternValid = true;
        }
        structure.updateDestinations();
        // an activation always calls for a placement: the activity of the terms evaluated from arrays is read again with it
        FixedStructure.LinearTerms<V, E> current = structure.getLinearTerms();
        if (placedAgain && current == linearTerms) {
            current.updateActivity();
        }
        linearTerms = current;
        FixedStructure.ElementTerms<V, E> currentElementTerms = structure.getElementTerms();
        if (placedAgain && currentElementTerms == elementTerms) {
            currentElementTerms.updateActivity();
        }
        elementTerms = currentElementTerms;
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
        placementCount++;
        equationSystem.getIndex().getSortedVariablesToFind();
        TIntHashSet changedSlots = new TIntHashSet();
        for (int slot = 0; slot < rowOfSlot.length; slot++) {
            int row = structure.getVariable(slot).getRow();
            if ((row >= 0) != (rowOfSlot[slot] >= 0)) {
                changedSlots.add(slot);
            }
            rowOfSlot[slot] = row;
        }
        updateFirstPass(changedSlots);
        assert firstPassIsTheFullOne() : "The first pass kept from the last placement is not the one computed again";
        System.arraycopy(firstPassEquationOfSlot, 0, equationOfSlot, 0, equationOfSlot.length);
        Arrays.fill(slotOfEquation, -1);
        for (int slot = 0; slot < equationOfSlot.length; slot++) {
            if (equationOfSlot[slot] >= 0) {
                slotOfEquation[equationOfSlot[slot]] = slot;
            }
        }
        TIntArrayList displaced = new TIntArrayList();
        for (int position = displacedPositions.nextSetBit(0); position >= 0; position = displacedPositions.nextSetBit(position + 1)) {
            displaced.add(displacedAt[position]);
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
        // an equation on the slot it had at the last placement already has its destinations and its entries in the pattern
        for (int equation = 0; equation < slotOfEquation.length; equation++) {
            int slot = slotOfEquation[equation];
            if (slot >= 0 && (!lastPlacementValid || slot != lastSlotOfEquation[equation])) {
                structure.setDestinationSlot(equation, slot);
                addToPattern(equation, slot);
            }
        }
        System.arraycopy(slotOfEquation, 0, lastSlotOfEquation, 0, slotOfEquation.length);
        lastPlacementValid = true;
    }

    private void initFirstPass() {
        int equationCount = structure.getEquationCount();
        int slotCount = structure.getSlotCount();
        TIntArrayList[] owners = new TIntArrayList[slotCount];
        TIntArrayList without = new TIntArrayList();
        for (int equation = 0; equation < equationCount; equation++) {
            int own = structure.getOwnSlot(equation);
            if (own < 0) {
                without.add(equation);
            } else {
                if (owners[own] == null) {
                    owners[own] = new TIntArrayList(2);
                }
                owners[own].add(equation);
            }
        }
        ownersOfSlot = new int[slotCount][];
        for (int slot = 0; slot < slotCount; slot++) {
            ownersOfSlot[slot] = owners[slot] == null ? new int[0] : owners[slot].toArray();
        }
        withoutOwnSlot = without.toArray();
        firstPassEquationOfSlot = new int[slotCount];
        displacedAt = new int[equationCount];
        lastSlotOfEquation = new int[equationCount];
        Arrays.fill(rowOfSlot, -1);
        firstPassValid = false;
        lastPlacementValid = false;
        changedEquations.clear();
    }

    /** Resolves the first pass again for every slot, or for the slots that may have changed only. */
    private void updateFirstPass(TIntHashSet changedSlots) {
        if (!firstPassValid) {
            Arrays.fill(firstPassEquationOfSlot, -1);
            Arrays.fill(displacedAt, -1);
            displacedPositions.clear();
            for (int slot = 0; slot < ownersOfSlot.length; slot++) {
                resolveSlot(slot);
            }
            for (int equation : withoutOwnSlot) {
                resolveWithoutOwnSlot(equation);
            }
            firstPassValid = true;
        } else {
            for (TIntIterator it = changedEquations.iterator(); it.hasNext();) {
                int equation = it.next();
                int own = structure.getOwnSlot(equation);
                if (own >= 0) {
                    changedSlots.add(own);
                } else {
                    resolveWithoutOwnSlot(equation);
                }
            }
            for (TIntIterator it = changedSlots.iterator(); it.hasNext();) {
                resolveSlot(it.next());
            }
        }
        changedEquations.clear();
    }

    /** The first pass on one slot: what the full pass does for the equations whose own slot it is, in their order. */
    private void resolveSlot(int slot) {
        int[] owners = ownersOfSlot[slot];
        for (int equation : owners) {
            setDisplaced(equation, -1);
        }
        int holder = -1;
        for (int equation : owners) {
            if (!structure.isActive(equation)) {
                continue;
            }
            if (!isLive(slot) || !structure.dependsOn(equation, slot)) {
                setDisplaced(equation, equation);
            } else if (holder < 0) {
                holder = equation;
            } else if (structure.fixesVariable(equation) && !structure.fixesVariable(holder)) {
                // the equation that held the slot is listed where the full pass meets the one that takes it
                setDisplaced(equation, holder);
                holder = equation;
            } else {
                setDisplaced(equation, equation);
            }
        }
        firstPassEquationOfSlot[slot] = holder;
    }

    private void resolveWithoutOwnSlot(int equation) {
        setDisplaced(equation, structure.isActive(equation) ? equation : -1);
    }

    private void setDisplaced(int position, int equation) {
        displacedAt[position] = equation;
        displacedPositions.set(position, equation >= 0);
    }

    /** For the assertion of {@link #place()}: the first pass computed for every slot gives the one kept. */
    private boolean firstPassIsTheFullOne() {
        int[] keptEquationOfSlot = firstPassEquationOfSlot.clone();
        int[] keptDisplacedAt = displacedAt.clone();
        BitSet keptPositions = (BitSet) displacedPositions.clone();
        firstPassValid = false;
        updateFirstPass(new TIntHashSet());
        return Arrays.equals(keptEquationOfSlot, firstPassEquationOfSlot) && Arrays.equals(keptDisplacedAt, displacedAt)
                && keptPositions.equals(displacedPositions);
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

    /**
     * The value of each active equation at the current state of OLF's equation system, written at its column in OLF's
     * index: OLF's equation vector, from the arrays of the structure. The term arrays are read as the fill reads them,
     * the terms held as objects from the two tables of the structure, in the order of their creation, so that each
     * sum is the one OLF computes, to the last bit. Only an equation that holds a term of another kind calls its
     * objects. Returns the number of columns written: OLF's column count when the structure holds every active
     * equation.
     */
    public int evalEquations(double[] values) {
        update();
        Arrays.fill(values, 0);
        state = equationSystem.getStateVector().get();
        int equationCount = structure.getEquationCount();
        assert columnsAreCurrent() : "The columns of the equations kept from the last placement are not those of OLF's index";
        for (FixedStructure.ArrayTerms<V, E> terms : structure.getArrayTerms()) {
            double[] termValues = terms.termArray.eval();
            for (int term = 0; term < terms.equation.length; term++) {
                int column = columnOfEquation[terms.equation[term]];
                if (column >= 0 && terms.termArray.isTermActive(term)) {
                    values[column] += termValues[terms.element[term]];
                }
            }
        }
        elementTerms.readTermArrays();
        int written = 0;
        for (int equation : structure.getArrayElementsWithSingleTerms()) {
            int column = columnOfEquation[equation];
            if (column >= 0) {
                values[column] = addObjectTermValues(equation, values[column], false);
            }
        }
        for (int equation = 0; equation < equationCount; equation++) {
            int column = columnOfEquation[equation];
            if (column >= 0) {
                written++;
                if (equation < structure.getSingleEquationCount()) {
                    values[column] = addObjectTermValues(equation, 0, true);
                }
            }
        }
        return written;
    }

    /**
     * Adds the values of the active terms an equation holds as objects to a sum, in the order the terms were created:
     * from the tables of the structure, two cursors following the kind of each term. A single equation adds the left
     * hand side of each term, as OLF does, and an element of an equation array its value.
     */
    private double addObjectTermValues(int equation, double sum, boolean lhs) {
        FixedStructure.ObjectTerms<V, E> compiled = structure.getObjectTerms(equation);
        double value = sum;
        if (compiled == null || structure.isMixed(equation)) {
            for (SingleEquationTerm<V, E> term : structure.getSingleTerms(equation)) {
                if (term.isActive()) {
                    value += lhs ? term.evalLhs() : term.eval();
                }
            }
            return value;
        }
        int linear = linearTerms.start[equation];
        int element = elementTerms.start[equation];
        for (byte kind : compiled.kind) {
            if (kind == FixedStructure.LINEAR) {
                if (linearTerms.active[linear]) {
                    double coefficient = linearTerms.coefficient[linear];
                    if (Double.isNaN(coefficient)) {
                        coefficient = linearTerms.terms.get(linear).der(linearTerms.variables.get(linear));
                    }
                    value += coefficient * state[rowOfSlot[linearTerms.slot[linear]]];
                }
                linear++;
            } else {
                if (elementTerms.active[element]) {
                    double coefficient = elementTerms.coefficient[element];
                    if (Double.isNaN(coefficient)) {
                        coefficient = elementTerms.computeCoefficient(element);
                    }
                    value += coefficient * elementTerms.values[elementTerms.termArray[element]][elementTerms.element[element]];
                }
                element++;
            }
        }
        return value;
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
        elementTerms.readTermArrays();
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
            return addLinearTerms(jacobianValues, equation, column) + addElementTerms(jacobianValues, equation, column);
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

    /**
     * The same for the terms that refer to an element of a term array: a coefficient times the value the array holds
     * for the element, and times each of its derivatives. No object is called, unless a term computes its coefficient.
     */
    private double addElementTerms(double[] jacobianValues, int equation, int column) {
        double value = 0;
        for (int i = elementTerms.start[equation]; i < elementTerms.start[equation + 1]; i++) {
            if (elementTerms.active[i]) {
                int[] slot = elementTerms.slot[i];
                if (column < 0 && !isInSystem(slot)) {
                    return Double.NaN;
                }
                double coefficient = elementTerms.coefficient[i];
                if (Double.isNaN(coefficient)) {
                    coefficient = elementTerms.computeCoefficient(i);
                }
                int element = elementTerms.element[i];
                value += coefficient * elementTerms.values[elementTerms.termArray[i]][element];
                if (column >= 0) {
                    double[][] derivatives = elementTerms.derivatives[elementTerms.termArray[i]];
                    int[] localIndex = elementTerms.localIndex[i];
                    int[] destination = elementTerms.destination[i];
                    for (int k = 0; k < destination.length; k++) {
                        jacobianValues[destination[k]] += coefficient * derivatives[localIndex[k]][element];
                    }
                }
            }
        }
        return value;
    }

    private boolean isInSystem(int[] slots) {
        for (int slot : slots) {
            if (rowOfSlot[slot] < 0) {
                return false;
            }
        }
        return true;
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
        if (eventType == EquationEventType.EQUATION_ACTIVATED || eventType == EquationEventType.EQUATION_DEACTIVATED) {
            changed(equation.getType(), equation.getElementNum());
        } else {
            firstPassValid = false;
        }
        invalidate(!kept);
    }

    @Override
    public void onEquationArrayChange(EquationArray<V, E> equationArray, int elementNum, EquationEventType eventType) {
        if (eventType == EquationEventType.EQUATION_ACTIVATED || eventType == EquationEventType.EQUATION_DEACTIVATED) {
            changed(equationArray.getType(), elementNum);
        } else {
            firstPassValid = false;
        }
        invalidate(eventType == EquationEventType.EQUATION_CREATED || eventType == EquationEventType.EQUATION_REMOVED);
    }

    @Override
    public void onEquationTermChange(SingleEquationTerm<V, E> term, EquationTermEventType eventType) {
        // a term changes what its equation depends on
        Equation<V, E> equation = term.getEquation();
        if (equation != null) {
            changed(equation.getType(), equation.getElementNum());
        } else {
            firstPassValid = false;
        }
        invalidate(eventType == EquationTermEventType.EQUATION_TERM_ADDED
                && !(structure != null && termsChanged(structure.getNumber(term.getEquation()))));
    }

    /** Notes an equation whose activity, or the activity of one of its terms, changed: its slot is resolved again. */
    private void changed(E type, int elementNum) {
        if (structure != null && firstPassValid) {
            int equation = structure.getNumber(type, elementNum);
            if (equation >= 0) {
                changedEquations.add(equation);
            } else {
                firstPassValid = false;
            }
        }
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
        changed(equationTermArray.getEquationArray().getType(), equationTermArray.getEquationElementNum(termNum));
        invalidate(eventType == EquationTermEventType.EQUATION_TERM_ADDED);
    }

    /** The number of placements made: each activation that changed something calls for one. */
    public int getPlacementCount() {
        return placementCount;
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

    /**
     * For each slot, which equation it holds, as an index in {@link FixedStructure#getAlternatives(int)}: the GPU's row
     * mode, given by the placement of the CPU. {@link #MODE_NONE} when it holds none, {@link #MODE_OTHER} when it
     * holds an equation outside its alternatives. A switch of an outer loop, or a contingency, changes the modes of
     * the slots it touches and nothing else.
     */
    public int[] getModes() {
        update();
        if (modes == null || modes.length != equationOfSlot.length) {
            modes = new int[equationOfSlot.length];
            modesValid = false;
        }
        if (!modesValid) {
            for (int slot = 0; slot < modes.length; slot++) {
                int equation = equationOfSlot[slot];
                if (equation < 0) {
                    modes[slot] = MODE_NONE;
                } else {
                    int position = Arrays.binarySearch(structure.getAlternatives(slot), equation);
                    modes[slot] = position >= 0 ? position : MODE_OTHER;
                }
            }
            modesValid = true;
        }
        return modes;
    }

    /** Whether the columns kept from the last placement are those of OLF's index now. */
    private boolean columnsAreCurrent() {
        for (int equation = 0; equation < columnOfEquation.length; equation++) {
            if (columnOfEquation[equation] != structure.getColumnInEquationSystem(equation)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes, for each slot, the row of its variable in OLF's state vector and the column of the equation it holds in
     * OLF's index, -1 where it has none. They change only with a placement ({@link #getPlacementCount()}).
     */
    public void getRowsAndColumnsOfSlots(int[] rows, int[] columns) {
        update();
        for (int slot = 0; slot < equationOfSlot.length; slot++) {
            int equation = equationOfSlot[slot];
            rows[slot] = rowOfSlot[slot];
            columns[slot] = equation < 0 ? -1 : columnOfEquation[equation];
        }
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
