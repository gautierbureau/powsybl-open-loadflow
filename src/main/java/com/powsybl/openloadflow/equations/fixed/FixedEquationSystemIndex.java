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
 * OLF's equation system seen as a matrix whose shape does not change: one position per variable, and each active equation
 * written in the column of the variable it is paired with. A position no equation holds keeps its variable where it is
 * ({@code x = x}). What OLF activates or deactivates changes the values and which equation a position holds, not the
 * matrix structure.
 *
 * <p>Nothing of the model is re-derived: the equations, the terms, the formulas and the activations are OLF's, read
 * through its own API and followed through its events. The only model knowledge is the {@link VariablePairing}.
 *
 * <p>A fill computes three arrays:
 * <ul>
 * <li>the Jacobian values, on the matrix structure of the {@link FixedStructure};</li>
 * <li>the value of the equation each position holds, 0 for a position that holds none;</li>
 * <li>the <b>balance</b> of each position: the value of the equation paired with it that does not fix its variable (a
 *     bus's active or reactive balance), whether or not that equation is active. The reactive balance of a bus that
 *     holds its voltage and the active balance of the slack bus are entries of it: what the outer loops read. It is
 *     NaN where the equation cannot be evaluated: a disabled bus's.</li>
 * </ul>
 *
 * <p>The matrix structure only grows, and only when an active equation needs entries it does not hold. With
 * {@link Scope#ALL} it holds from the start every equation on its own position and on the controller positions its
 * {@link VariablePairing} gives: it then grows only when an equation takes a position that is neither. With
 * {@link Scope#ACTIVE} it also grows the first time an equation becomes active.
 *
 * <p>When OLF creates an equation the structure has not seen, the arrays are compiled again, and the matrix structure is
 * kept if the new arrays fit in it.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class FixedEquationSystemIndex<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>
        implements EquationSystemListener<V, E>, AutoCloseable {

    /** The mode of a position that holds no equation: its variable keeps its value. */
    public static final int MODE_NONE = -1;

    /** The mode of a position that holds an equation outside its alternatives: one the pairing moved to a unpaired position. */
    public static final int MODE_OTHER = -2;

    /** Which equations have their entries in the matrix structure from the start. */
    public enum Scope {
        /** Every equation OLF created, on the position it is paired with and on its controller positions. */
        ALL,
        /** The active equations only. */
        ACTIVE
    }

    private final EquationSystem<V, E> equationSystem;
    private final VariablePairing<V, E> pairing;
    private final Scope scope;

    private FixedStructure<V, E> structure;
    /** The structure that was discarded, until the arrays are compiled again: the new one takes its matrix structure if it can. */
    private FixedStructure<V, E> discarded;
    private final TIntArrayList pairedEquations = new TIntArrayList();
    private final TIntArrayList pairedPositions = new TIntArrayList();
    /** The pairings the matrix structure holds: the equations on their own position as a bit set, the others as pairs. */
    private final BitSet inStructureAtOwnPosition = new BitSet();
    private final TLongHashSet paired = new TLongHashSet();
    private boolean matrixStructureValid;
    private boolean pairingValid;
    /** The single equations that have other terms than when their rows were computed. */
    private final TIntHashSet changedTerms = new TIntHashSet();

    private int[] positionOfEquation;
    private int[] equationOfPosition;
    /** The row of the variable of each position in OLF's index, as of the last pairing: negative when it is not in the system. */
    private int[] rowOfPosition;
    /** For each equation, its column in OLF's index, during an evaluation of the equations. */
    private int[] columnOfEquation;
    /** OLF's state vector, by row, during a fill. */
    private double[] state;
    /** The linear terms of the structure, with their activity as of the last pairing. */
    private FixedStructure.LinearTerms<V, E> linearTerms;
    /** Its terms that refer to an element of a term array, with their activity as of the last pairing. */
    private FixedStructure.ElementTerms<V, E> elementTerms;
    /** For the search of a pairing: the positions already tried for the equation being paired. */
    private int[] visited;
    private int stamp;

    /*
     * The first pass of the pairing, kept from one pairing to the next: each equation on its own position when it
     * depends on its live variable. It is local to the equations of one position, so after an activation only the positions
     * of the equations that changed, and the positions whose variable entered or left the system, are resolved again.
     */
    /** For each position, the equations whose own position it is, in their order. */
    private int[][] ownersOfPosition;
    /** The equations that have no own position. */
    private int[] withoutOwnPosition;
    /** For each position, the equation the first pass puts on it, or -1. */
    private int[] firstPassEquationOfPosition;
    /**
     * The equations the first pass leaves without a position, each at the index the full pass would list it at: its own
     * number, or the number of the equation that took its position from it.
     */
    private int[] displacedAt;
    private final BitSet displacedListIndices = new BitSet();
    /** False when the first pass has to be computed for every equation: a new structure, an equation created or removed. */
    private boolean firstPassValid;
    /** The equations whose activity, or the activity of a term, changed since the last pairing. */
    private final TIntHashSet changedEquations = new TIntHashSet();
    /**
     * The equations whose activity, or the activity of a term, changed, as the last pairing found them: the linear
     * terms read again the activity of theirs only. Null when the activity of every term is read again.
     */
    private TIntArrayList activityChangedEquations;
    /** The position of each equation at the last pairing, to visit only the equations that moved. */
    private int[] lastPositionOfEquation;
    /** False when every equation has to be visited after the pairing: a new structure. */
    private boolean lastPairingValid;
    private int pairingCount;

    /** For each position, the index of the equation it holds among its alternatives, as of the last pairing. */
    private int[] modes;
    private boolean modesValid;

    private double[] values;
    private double[] balance;
    private double[] equationValues;

    private int structureBuildCount;
    private int matrixStructureBuildCount;

    public FixedEquationSystemIndex(EquationSystem<V, E> equationSystem, VariablePairing<V, E> pairing, Scope scope) {
        this.equationSystem = equationSystem;
        this.pairing = pairing;
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
            structure = new FixedStructure<>(equationSystem, pairing);
            structureBuildCount++;
            positionOfEquation = new int[structure.getEquationCount()];
            equationOfPosition = new int[structure.getVariableCount()];
            rowOfPosition = new int[structure.getVariableCount()];
            visited = new int[structure.getVariableCount()];
            stamp = 0;
            balance = new double[structure.getVariableCount()];
            equationValues = new double[structure.getVariableCount()];
            paired.clear();
            inStructureAtOwnPosition.clear();
            pairedEquations.resetQuick();
            pairedPositions.resetQuick();
            matrixStructureValid = discarded != null && structure.adoptMatrixStructure(discarded);
            discarded = null;
            pairingValid = false;
            initFirstPass();
            if (scope == Scope.ALL) {
                for (int equation = 0; equation < structure.getEquationCount(); equation++) {
                    if (structure.getOwnPosition(equation) >= 0) {
                        addToMatrixStructure(equation, structure.getOwnPosition(equation));
                    }
                    for (int position : structure.getControllerPositions(equation)) {
                        addToMatrixStructure(equation, position);
                    }
                }
            }
        }
        boolean pairedAgain = !pairingValid;
        if (!pairingValid) {
            place();
            pairingValid = true;
            modesValid = false;
        }
        if (pairedAgain || columnOfEquation == null || columnOfEquation.length != structure.getEquationCount()) {
            // the columns of OLF's index change only with an activation, which calls for a pairing: they are read
            // once by pairing, not at each evaluation
            if (columnOfEquation == null || columnOfEquation.length != structure.getEquationCount()) {
                columnOfEquation = new int[structure.getEquationCount()];
            }
            structure.getColumnsInEquationSystem(columnOfEquation);
        }
        if (!matrixStructureValid) {
            structure.buildMatrixStructure(pairedEquations, pairedPositions);
            values = new double[structure.getValueCount()];
            matrixStructureBuildCount++;
            matrixStructureValid = true;
        }
        structure.updateMatrixElementIndices();
        // an activation always calls for a pairing: the activity of the terms evaluated from arrays is read again with it
        FixedStructure.LinearTerms<V, E> current = structure.getLinearTerms();
        if (pairedAgain && current == linearTerms) {
            if (activityChangedEquations == null) {
                current.updateActivity();
            } else {
                for (int i = 0; i < activityChangedEquations.size(); i++) {
                    current.updateActivity(activityChangedEquations.getQuick(i));
                }
            }
            assert current.isActivityCurrent() : "The activity of a linear term changed without an event on its equation";
        }
        activityChangedEquations = null;
        linearTerms = current;
        FixedStructure.ElementTerms<V, E> currentElementTerms = structure.getElementTerms();
        if (pairedAgain && currentElementTerms == elementTerms) {
            currentElementTerms.updateActivity();
        }
        elementTerms = currentElementTerms;
    }

    /**
     * The equations whose terms changed take the rows of their new terms; the matrix structure stands when the columns they
     * are paired in already hold them. Returns false when a new term depends on a variable that has no position.
     */
    private boolean addRows() {
        for (TIntIterator it = changedTerms.iterator(); it.hasNext();) {
            if (!structure.addRows(it.next())) {
                return false;
            }
        }
        for (int i = 0; i < pairedEquations.size() && matrixStructureValid; i++) {
            if (changedTerms.contains(pairedEquations.getQuick(i))) {
                matrixStructureValid = structure.holds(pairedPositions.getQuick(i), pairedEquations.getQuick(i));
            }
        }
        return true;
    }

    private void addToMatrixStructure(int equation, int position) {
        if (position == structure.getOwnPosition(equation)) {
            if (inStructureAtOwnPosition.get(equation)) {
                return;
            }
            inStructureAtOwnPosition.set(equation);
        } else if (!paired.add(((long) equation << 32) | position)) {
            return;
        }
        pairedEquations.add(equation);
        pairedPositions.add(position);
        // the matrix structure stands when the column already holds the rows of the equation
        matrixStructureValid = matrixStructureValid && structure.holds(position, equation);
    }

    /**
     * Gives each active equation a position. A position is live when an active term depends on its variable: OLF's index keeps
     * a row for exactly those variables. Each equation takes the position it is paired with, when it depends on its
     * variable; when two claim one, the one that fixes the variable keeps it. The others are paired one by one on a
     * position they may take and whose variable they depend on: the one they had before or a controller position, which are in the
     * matrix structure already. An equation in the way moves the same way. So no position holds an equation that does not depend on
     * its variable, a zero on the diagonal, as long as the controller positions allow it. Any pairing gives the same system,
     * with the equations in another order.
     */
    private void place() {
        pairingCount++;
        equationSystem.getIndex().getSortedVariablesToFind();
        TIntHashSet changedPositions = new TIntHashSet();
        for (int position = 0; position < rowOfPosition.length; position++) {
            int row = structure.getVariable(position).getRow();
            if ((row >= 0) != (rowOfPosition[position] >= 0)) {
                changedPositions.add(position);
            }
            rowOfPosition[position] = row;
        }
        // what the equations of the chains depend on is kept from the last pairing, for those whose activity did not change
        if (firstPassValid) {
            activityChangedEquations = new TIntArrayList(changedEquations.size());
            for (TIntIterator it = changedEquations.iterator(); it.hasNext();) {
                int equation = it.next();
                structure.refreshDependedOnNow(equation);
                activityChangedEquations.add(equation);
            }
        } else {
            structure.refreshDependedOnNow();
            activityChangedEquations = null;
        }
        updateFirstPass(changedPositions);
        assert firstPassIsTheFullOne() : "The first pass kept from the last pairing is not the one computed again";
        System.arraycopy(firstPassEquationOfPosition, 0, equationOfPosition, 0, equationOfPosition.length);
        Arrays.fill(positionOfEquation, -1);
        for (int position = 0; position < equationOfPosition.length; position++) {
            if (equationOfPosition[position] >= 0) {
                positionOfEquation[equationOfPosition[position]] = position;
            }
        }
        TIntArrayList displaced = new TIntArrayList();
        for (int listIndex = displacedListIndices.nextSetBit(0); listIndex >= 0; listIndex = displacedListIndices.nextSetBit(listIndex + 1)) {
            displaced.add(displacedAt[listIndex]);
        }
        // a displaced equation takes a position it may take and depends on; an equation in its way moves the same way
        TIntArrayList left = new TIntArrayList();
        for (int i = 0; i < displaced.size(); i++) {
            int equation = displaced.getQuick(i);
            stamp++;
            if (!move(equation, true)) {
                left.add(equation);
            }
        }
        // what is left has no such position. It takes one it may take all the same, which leaves a zero on the diagonal
        // and keeps the matrix structure; only then any free one
        for (int i = 0; i < left.size(); i++) {
            int equation = left.getQuick(i);
            stamp++;
            if (!move(equation, false)) {
                int position = findUnpairedPosition(equation);
                if (position < 0) {
                    throw new PowsyblException("More active equations than variables: no position left for "
                            + structure.getType(equation) + " of element " + structure.getElementNum(equation));
                }
                take(equation, position);
            }
        }
        for (int position = 0; position < equationOfPosition.length; position++) {
            if (isUnpaired(position)) {
                throw new PowsyblException("More variables than active equations: no equation for " + structure.getVariable(position));
            }
        }
        // an equation on the position it had at the last pairing already has its matrix element indices and its entries in the matrix structure
        for (int equation = 0; equation < positionOfEquation.length; equation++) {
            int position = positionOfEquation[equation];
            if (position >= 0 && (!lastPairingValid || position != lastPositionOfEquation[equation])) {
                structure.setPairedPosition(equation, position);
                addToMatrixStructure(equation, position);
            }
        }
        System.arraycopy(positionOfEquation, 0, lastPositionOfEquation, 0, positionOfEquation.length);
        lastPairingValid = true;
    }

    private void initFirstPass() {
        int equationCount = structure.getEquationCount();
        int variableCount = structure.getVariableCount();
        TIntArrayList[] owners = new TIntArrayList[variableCount];
        TIntArrayList without = new TIntArrayList();
        for (int equation = 0; equation < equationCount; equation++) {
            int own = structure.getOwnPosition(equation);
            if (own < 0) {
                without.add(equation);
            } else {
                if (owners[own] == null) {
                    owners[own] = new TIntArrayList(2);
                }
                owners[own].add(equation);
            }
        }
        ownersOfPosition = new int[variableCount][];
        for (int position = 0; position < variableCount; position++) {
            ownersOfPosition[position] = owners[position] == null ? new int[0] : owners[position].toArray();
        }
        withoutOwnPosition = without.toArray();
        firstPassEquationOfPosition = new int[variableCount];
        displacedAt = new int[equationCount];
        lastPositionOfEquation = new int[equationCount];
        Arrays.fill(rowOfPosition, -1);
        firstPassValid = false;
        lastPairingValid = false;
        changedEquations.clear();
    }

    /** Resolves the first pass again for every position, or for the positions that may have changed only. */
    private void updateFirstPass(TIntHashSet changedPositions) {
        if (!firstPassValid) {
            Arrays.fill(firstPassEquationOfPosition, -1);
            Arrays.fill(displacedAt, -1);
            displacedListIndices.clear();
            for (int position = 0; position < ownersOfPosition.length; position++) {
                resolvePosition(position);
            }
            for (int equation : withoutOwnPosition) {
                resolveWithoutOwnPosition(equation);
            }
            firstPassValid = true;
        } else {
            for (TIntIterator it = changedEquations.iterator(); it.hasNext();) {
                int equation = it.next();
                int own = structure.getOwnPosition(equation);
                if (own >= 0) {
                    changedPositions.add(own);
                } else {
                    resolveWithoutOwnPosition(equation);
                }
            }
            for (TIntIterator it = changedPositions.iterator(); it.hasNext();) {
                resolvePosition(it.next());
            }
        }
        changedEquations.clear();
    }

    /** The first pass on one position: what the full pass does for the equations whose own position it is, in their order. */
    private void resolvePosition(int position) {
        int[] owners = ownersOfPosition[position];
        for (int equation : owners) {
            setDisplaced(equation, -1);
        }
        int holder = -1;
        for (int equation : owners) {
            if (!structure.isActive(equation)) {
                continue;
            }
            if (!isToFind(position) || !structure.dependsOn(equation, position)) {
                setDisplaced(equation, equation);
            } else if (holder < 0) {
                holder = equation;
            } else if (structure.fixesVariable(equation) && !structure.fixesVariable(holder)) {
                // the equation that held the position is listed where the full pass meets the one that takes it
                setDisplaced(equation, holder);
                holder = equation;
            } else {
                setDisplaced(equation, equation);
            }
        }
        firstPassEquationOfPosition[position] = holder;
    }

    private void resolveWithoutOwnPosition(int equation) {
        setDisplaced(equation, structure.isActive(equation) ? equation : -1);
    }

    private void setDisplaced(int listIndex, int equation) {
        displacedAt[listIndex] = equation;
        displacedListIndices.set(listIndex, equation >= 0);
    }

    /** For the assertion of {@link #place()}: the first pass computed for every position gives the one kept. */
    private boolean firstPassIsTheFullOne() {
        int[] keptEquationOfPosition = firstPassEquationOfPosition.clone();
        int[] keptDisplacedAt = displacedAt.clone();
        BitSet keptListIndices = (BitSet) displacedListIndices.clone();
        firstPassValid = false;
        updateFirstPass(new TIntHashSet());
        return Arrays.equals(keptEquationOfPosition, firstPassEquationOfPosition) && Arrays.equals(keptDisplacedAt, displacedAt)
                && keptListIndices.equals(displacedListIndices);
    }

    /**
     * Gives the equation one of the positions it may take: its own, the one it had or a controller position. When the position is
     * held, its equation is moved the same way first: one that has no other position to go to keeps it. This is the
     * search of an augmenting path. With {@code dependent}, only positions whose variable the equations depend on are
     * taken: together the moves then leave no zero on the diagonal when such a pairing exists.
     */
    private boolean move(int equation, boolean dependent) {
        int own = structure.getOwnPosition(equation);
        if (own >= 0 && moveTo(equation, own, dependent)) {
            return true;
        }
        for (int position : structure.getControllerPositions(equation)) {
            if (moveTo(equation, position, dependent)) {
                return true;
            }
        }
        return false;
    }

    private boolean moveTo(int equation, int position, boolean dependent) {
        if (visited[position] == stamp || !isToFind(position) || dependent && !structure.dependsOn(equation, position)) {
            return false;
        }
        visited[position] = stamp;
        int other = equationOfPosition[position];
        if (other >= 0 && !move(other, dependent)) {
            return false;
        }
        take(equation, position);
        return true;
    }

    private void take(int equation, int position) {
        positionOfEquation[equation] = position;
        equationOfPosition[position] = equation;
    }

    private boolean isToFind(int position) {
        return rowOfPosition[position] >= 0;
    }

    private boolean isUnpaired(int position) {
        return equationOfPosition[position] < 0 && isToFind(position);
    }

    private int findUnpairedPosition(int equation) {
        for (int position : structure.getControllerPositions(equation)) {
            if (isUnpaired(position)) {
                return position;
            }
        }
        for (int row : structure.getRows(equation)) {
            if (isUnpaired(row)) {
                return row;
            }
        }
        for (int position = 0; position < equationOfPosition.length; position++) {
            if (isUnpaired(position)) {
                return position;
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
        assert columnsAreCurrent() : "The columns of the equations kept from the last pairing are not those of OLF's index";
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
        double value = sum;
        if (linearTerms.linearOnly[equation]) {
            // the same sum as below, without reading the kinds of the terms from the objects
            for (int linear = linearTerms.start[equation]; linear < linearTerms.start[equation + 1]; linear++) {
                if (linearTerms.active[linear]) {
                    double coefficient = linearTerms.coefficient[linear];
                    if (Double.isNaN(coefficient)) {
                        coefficient = linearTerms.terms.get(linear).der(linearTerms.variables.get(linear));
                    }
                    value += coefficient * state[rowOfPosition[linearTerms.position[linear]]];
                }
            }
            return value;
        }
        FixedStructure.ObjectTerms<V, E> compiled = structure.getObjectTerms(equation);
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
                    value += coefficient * state[rowOfPosition[linearTerms.position[linear]]];
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
    public void updateDer() {
        update();
        updateDer(values);
    }

    /**
     * The same, with the Jacobian values written at the start of the given array: the value array of a sparse matrix
     * built on the matrix structure of {@link #getStructure()}, so that a solver fills its matrix in place.
     */
    public void updateDer(double[] jacobianValues) {
        update();
        if (jacobianValues.length < structure.getValueCount()) {
            throw new IllegalArgumentException("The matrix structure has " + structure.getValueCount() + " values, the array " + jacobianValues.length);
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
                    int own = structure.getOwnPosition(equation);
                    int position = positionOfEquation[equation];
                    int[] localIndex = terms.localIndex[term];
                    balance[own] += termValues[element];
                    if (position >= 0) {
                        // the matrix element indices are for the position the equation holds, its own or another
                        int[] matrixElementIndex = terms.matrixElementIndex[term];
                        for (int k = 0; k < matrixElementIndex.length; k++) {
                            jacobianValues[matrixElementIndex[k]] += termDerivatives[localIndex[k]][element];
                        }
                    }
                }
            }
        }
        elementTerms.readTermArrays();
        for (int equation : structure.getArrayElementsWithSingleTerms()) {
            balance[structure.getOwnPosition(equation)] += addSingleTerms(jacobianValues, equation, positionOfEquation[equation]);
        }
        // the single equations: derivatives where one is paired; its value there, and as the balance of its own position
        for (int equation = 0; equation < structure.getSingleEquationCount(); equation++) {
            int position = positionOfEquation[equation];
            boolean isBalance = structure.getOwnPosition(equation) >= 0 && !structure.fixesVariable(equation);
            if (position >= 0 || isBalance) {
                double value = addSingleTerms(jacobianValues, equation, position);
                if (position >= 0) {
                    equationValues[position] = value;
                }
                if (isBalance) {
                    balance[structure.getOwnPosition(equation)] = value;
                }
            }
        }
        int[] diagonal = structure.getDiagonal();
        for (int position = 0; position < equationOfPosition.length; position++) {
            int equation = equationOfPosition[position];
            if (equation < 0) {
                jacobianValues[diagonal[position]] = 1;
                equationValues[position] = 0;
            } else if (structure.isArrayElement(equation)) {
                equationValues[position] = balance[structure.getOwnPosition(equation)];
            }
        }
    }

    /**
     * Adds the derivatives of the active object terms of an equation in a column (none when negative) and returns their
     * value. An equation that holds no position can have an active term on a variable that left the system, a disabled
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
                        jacobianValues[compiled.matrixElementIndex[t][k]] += term.der(variables.get(k));
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
                int position = linearTerms.position[i];
                if (column < 0 && rowOfPosition[position] < 0) {
                    return Double.NaN;
                }
                double coefficient = linearTerms.coefficient[i];
                if (Double.isNaN(coefficient)) {
                    coefficient = linearTerms.terms.get(i).der(linearTerms.variables.get(i));
                }
                value += coefficient * state[rowOfPosition[position]];
                if (column >= 0) {
                    jacobianValues[linearTerms.matrixElementIndex[i]] += coefficient;
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
                int[] position = elementTerms.position[i];
                if (column < 0 && !isInSystem(position)) {
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
                    int[] matrixElementIndex = elementTerms.matrixElementIndex[i];
                    for (int k = 0; k < matrixElementIndex.length; k++) {
                        jacobianValues[matrixElementIndex[k]] += coefficient * derivatives[localIndex[k]][element];
                    }
                }
            }
        }
        return value;
    }

    private boolean isInSystem(int[] positions) {
        for (int position : positions) {
            if (rowOfPosition[position] < 0) {
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
     * it keeps its number and its position. OLF does so with the distribution equations of a voltage control when a zero
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

    /** Notes an equation whose activity, or the activity of one of its terms, changed: its position is resolved again. */
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

    /** The number of pairings made: each activation that changed something calls for one. */
    public int getPairingCount() {
        return pairingCount;
    }

    @Override
    public void onEquationTermConstantsChange() {
        // values only
    }

    /** An activation moves equations between positions; an equation or a term the structure cannot keep changes the compiled arrays. */
    private void invalidate(boolean compiledArrays) {
        pairingValid = false;
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
     * How many positions hold an equation that does not depend, in the current state, on their variable: the zeros on
     * the diagonal. A factorisation without pivoting needs none.
     */
    public int getZeroDiagonalCount() {
        update();
        int count = 0;
        for (int position = 0; position < equationOfPosition.length; position++) {
            if (equationOfPosition[position] >= 0 && !structure.dependsOnNow(equationOfPosition[position], position)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Writes the structure as text, one line per position: its variable, the equation it holds now, and the variables of
     * the rows of its column. Variables and equations are named by type and element number, so the text does not
     * depend on any numbering: the same text written from another structure of the same network compares the two.
     */
    public void write(Writer writer) throws IOException {
        update();
        int[] columnStart = structure.getColumnStart();
        for (int position = 0; position < structure.getVariableCount(); position++) {
            int equation = equationOfPosition[position];
            writer.append(name(structure.getVariable(position))).append(" <- ")
                    .append(equation < 0 ? "none" : structure.getType(equation) + ":" + structure.getElementNum(equation))
                    .append(" |");
            for (int i = columnStart[position]; i < columnStart[position + 1]; i++) {
                writer.append(' ').append(name(structure.getVariable(structure.getRowIndices()[i])));
            }
            writer.append('\n');
        }
    }

    private String name(Variable<V> variable) {
        return variable.getType() + ":" + variable.getElementNum();
    }

    /** The position an equation holds, -1 when it is not active. */
    public int getPositionOfEquation(int equation) {
        update();
        return positionOfEquation[equation];
    }

    /**
     * For each position, which equation it holds, as an index in {@link FixedStructure#getAlternatives(int)}: the GPU's row
     * mode, given by the pairing of the CPU. {@link #MODE_NONE} when it holds none, {@link #MODE_OTHER} when it
     * holds an equation outside its alternatives. A switch of an outer loop, or a contingency, changes the modes of
     * the positions it touches and nothing else.
     */
    public int[] getModes() {
        update();
        if (modes == null || modes.length != equationOfPosition.length) {
            modes = new int[equationOfPosition.length];
            modesValid = false;
        }
        if (!modesValid) {
            for (int position = 0; position < modes.length; position++) {
                int equation = equationOfPosition[position];
                if (equation < 0) {
                    modes[position] = MODE_NONE;
                } else {
                    int alternativeIndex = Arrays.binarySearch(structure.getAlternatives(position), equation);
                    modes[position] = alternativeIndex >= 0 ? alternativeIndex : MODE_OTHER;
                }
            }
            modesValid = true;
        }
        return modes;
    }

    /** Whether the columns kept from the last pairing are those of OLF's index now. */
    private boolean columnsAreCurrent() {
        for (int equation = 0; equation < columnOfEquation.length; equation++) {
            if (columnOfEquation[equation] != structure.getColumnInEquationSystem(equation)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes, for each position, the row of its variable in OLF's state vector and the column of the equation it holds in
     * OLF's index, -1 where it has none. They change only with a pairing ({@link #getPairingCount()}).
     */
    public void getRowsAndColumnsOfPositions(int[] rows, int[] columns) {
        update();
        for (int position = 0; position < equationOfPosition.length; position++) {
            int equation = equationOfPosition[position];
            rows[position] = rowOfPosition[position];
            columns[position] = equation < 0 ? -1 : columnOfEquation[equation];
        }
    }

    /** The equation a position holds, -1 when it holds none: its variable is then fixed. */
    public int getEquationOfPosition(int position) {
        update();
        return equationOfPosition[position];
    }

    /** The Jacobian values of the last {@link #updateDer()}. */
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

    /** How many times the matrix structure was built: once, plus once each time an equation needed entries it did not hold. */
    public int getMatrixStructureBuildCount() {
        return matrixStructureBuildCount;
    }
}
