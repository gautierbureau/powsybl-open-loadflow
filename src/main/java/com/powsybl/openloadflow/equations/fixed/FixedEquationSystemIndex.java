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
    private final Set<Long> paired = new HashSet<>();
    private boolean matrixStructureValid;
    private boolean pairingValid;
    /** The single equations that have other terms than when their rows were computed. */
    private final TIntHashSet changedTerms = new TIntHashSet();

    private int[] positionOfEquation;
    private int[] equationOfPosition;
    /** For the search of a pairing: the positions already tried for the equation being paired. */
    private int[] visited;
    private int stamp;
    /** For an equation that took a unpaired position: the position, kept as long as it is free, so that the matrix structure stops growing. */
    private int[] movedTo;

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
        changedTerms.clear();
        if (structure == null) {
            structure = new FixedStructure<>(equationSystem, pairing);
            structureBuildCount++;
            positionOfEquation = new int[structure.getEquationCount()];
            movedTo = new int[structure.getEquationCount()];
            Arrays.fill(movedTo, -1);
            equationOfPosition = new int[structure.getVariableCount()];
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
        if (!pairingValid) {
            place();
            pairingValid = true;
        }
        if (!matrixStructureValid) {
            structure.buildMatrixStructure(pairedEquations, pairedPositions);
            values = new double[structure.getValueCount()];
            matrixStructureBuildCount++;
            matrixStructureValid = true;
        }
        structure.updateMatrixElementIndices();
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
        equationSystem.getIndex().getSortedVariablesToFind();
        Arrays.fill(positionOfEquation, -1);
        Arrays.fill(equationOfPosition, -1);
        TIntArrayList displaced = new TIntArrayList();
        for (int equation = 0; equation < structure.getEquationCount(); equation++) {
            if (!structure.isActive(equation)) {
                continue;
            }
            int own = structure.getOwnPosition(equation);
            if (own < 0 || !isToFind(own) || !structure.dependsOn(equation, own)) {
                displaced.add(equation);
            } else if (equationOfPosition[own] < 0) {
                take(equation, own);
            } else if (structure.fixesVariable(equation) && !structure.fixesVariable(equationOfPosition[own])) {
                displaced.add(equationOfPosition[own]);
                positionOfEquation[equationOfPosition[own]] = -1;
                take(equation, own);
            } else {
                displaced.add(equation);
            }
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
        for (int equation = 0; equation < positionOfEquation.length; equation++) {
            int position = positionOfEquation[equation];
            if (position >= 0) {
                if (position != structure.getOwnPosition(equation)) {
                    movedTo[equation] = position;
                }
                structure.setPairedPosition(equation, position);
                addToMatrixStructure(equation, position);
            }
        }
    }

    /**
     * Gives the equation one of the positions it may take: its own, the one it had or a controller position. When the position is
     * held, its equation is moved the same way first: one that has no other position to go to keeps it. This is the
     * search of an augmenting path. With {@code dependent}, only positions whose variable the equations depend on are
     * taken: together the moves then leave no zero on the diagonal when such a pairing exists.
     */
    private boolean move(int equation, boolean dependent) {
        int own = structure.getOwnPosition(equation);
        if (own >= 0 && moveTo(equation, own, dependent)
                || movedTo[equation] >= 0 && moveTo(equation, movedTo[equation], dependent)) {
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
        return structure.getVariable(position).getRow() >= 0;
    }

    private boolean isUnpaired(int position) {
        return equationOfPosition[position] < 0 && isToFind(position);
    }

    private int findUnpairedPosition(int equation) {
        if (movedTo[equation] >= 0 && isUnpaired(movedTo[equation])) {
            return movedTo[equation];
        }
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
