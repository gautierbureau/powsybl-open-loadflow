/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

import com.powsybl.commons.PowsyblException;
import com.powsybl.openloadflow.equations.Derivative;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationArray;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.EquationTermArrayElementReference;
import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.equations.SingleEquation;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableEquationTerm;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * An equation system compiled into arrays. Nothing here depends on what is active.
 *
 * <ul>
 * <li><b>Positions.</b> One per variable that a term of the system can depend on, in the order of the variables. A position is
 *     a row (the variable) and a column (the equation that determines it).</li>
 * <li><b>Equations.</b> Numbered once: the single equations, then the elements of each equation array. Each knows the
 *     positions of the variables it depends on, and its own position: the one its {@link VariablePairing} pairs it with. A single
 *     equation that is removed and created again keeps its number: it is the same one, with other terms.</li>
 * <li><b>Matrix structure.</b> The transposed Jacobian by columns, which is the Jacobian by rows: for each position, the sorted rows
 *     of every equation paired there, plus the diagonal. It is built from a list of pairings and only ever
 *     grows.</li>
 * <li><b>Matrix element indices.</b> For each term of a term array and each of its derivatives, the index of its value in the
 *     own column of its equation.</li>
 * </ul>
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class FixedStructure<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

    /** The terms of one term array, indexed by term number, and where each of their outputs goes. */
    static final class ArrayTerms<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        final EquationTermArray<V, E> termArray;
        /** The equation of each term. */
        final int[] equation;
        /** The element of each term: its index in {@code eval()} and in each array of {@code evalDer()}. */
        final int[] element;
        /** For each derivative of each term: its array in {@code evalDer()}. */
        final int[][] localIndex;
        /** For each derivative of each term: the position of its variable. */
        final int[][] row;
        /** For each derivative of each term: its value index in the own column of the equation, -1 when the matrix structure lacks it. */
        final int[][] matrixElementIndex;

        ArrayTerms(EquationTermArray<V, E> termArray, int termCount) {
            this.termArray = termArray;
            equation = new int[termCount];
            element = new int[termCount];
            localIndex = new int[termCount][];
            row = new int[termCount][];
            matrixElementIndex = new int[termCount][];
        }
    }

    /** The terms an equation holds as objects: for each variable of each one, its position and where its derivative goes. */
    static final class ObjectTerms<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        final List<SingleEquationTerm<V, E>> terms;
        /** For each term, its variables. */
        final List<List<Variable<V>>> variables = new ArrayList<>();
        /** For each variable of each term: its position. */
        final int[][] row;
        /** For each variable of each term: the value index in the column of the position of the equation, -1 when the matrix structure lacks it. */
        final int[][] matrixElementIndex;
        /** For each term: {@link #LINEAR}, {@link #ELEMENT} or {@link #OTHER}. */
        final byte[] kind;

        ObjectTerms(List<SingleEquationTerm<V, E>> terms, byte[] kind) {
            this.terms = terms;
            this.kind = kind;
            row = new int[terms.size()][];
            matrixElementIndex = new int[terms.size()][];
        }
    }

    /**
     * The terms that are a coefficient times one variable, of the equations whose terms held as objects are all of
     * this kind or of the kind of {@link ElementTerms}, one equation after the other. The equations that tie the buses
     * of a zero impedance branch together, the flows of such a branch in the balances of its buses, the targets of the
     * voltages, the taps and the susceptances are of this kind. They are evaluated in one loop over these arrays,
     * without a call to an object.
     */
    static final class LinearTerms<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        /** For each equation, the index of its first term; a last entry closes the last equation. */
        final int[] start;
        /** For each term: the position of its variable. */
        final int[] position;
        /** For each term: its coefficient, or NaN when the term computes it at each evaluation. */
        final double[] coefficient;
        /** For each term: the value index in the own column of its equation, -1 when the matrix structure lacks it. */
        final int[] matrixElementIndex;
        /** For each term: whether it is active, as of the last call to {@link #updateActivity()}. */
        final boolean[] active;
        final List<SingleEquationTerm<V, E>> terms;
        final List<Variable<V>> variables;

        LinearTerms(int[] start, TIntArrayList position, TDoubleArrayList coefficient, TIntArrayList matrixElementIndex,
                    List<SingleEquationTerm<V, E>> terms, List<Variable<V>> variables) {
            this.start = start;
            this.position = position.toArray();
            this.coefficient = coefficient.toArray();
            this.matrixElementIndex = matrixElementIndex.toArray();
            this.active = new boolean[terms.size()];
            this.terms = terms;
            this.variables = variables;
            updateActivity();
        }

        /** Reads again which terms are active: to call when an activation changed, before the terms are evaluated. */
        void updateActivity() {
            for (int i = 0; i < active.length; i++) {
                active[i] = terms.get(i).isActive();
            }
        }
    }

    /**
     * The terms that are a coefficient times an element of a term array, of the same equations, one equation after the
     * other: the flows of the branches and of the shunts of the controller buses in a reactive power distribution
     * equation, or the flow a phase shifter controls, which the balances of the buses already hold in term arrays.
     * They are evaluated in one loop over these arrays, from the values and the derivatives the term arrays computed,
     * without a call to an object.
     */
    static final class ElementTerms<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        /** For each equation, the index of its first term; a last entry closes the last equation. */
        final int[] start;
        /** The term arrays the terms refer to. */
        final List<EquationTermArray<V, E>> termArrays;
        /** For each term: the index of its term array in {@link #termArrays}. */
        final int[] termArray;
        /** For each term: its element, the index of its value and of its derivatives in the arrays of its term array. */
        final int[] element;
        /** For each term: its coefficient, or NaN when the term computes it at each evaluation. */
        final double[] coefficient;
        /** For each variable of each term: the array of its derivatives in {@code evalDer()}. */
        final int[][] localIndex;
        /** For each variable of each term: its position. */
        final int[][] position;
        /** For each variable of each term: the value index in the own column of its equation, -1 when the matrix structure lacks it. */
        final int[][] matrixElementIndex;
        /** For each term: whether it is active, as of the last call to {@link #updateActivity()}. */
        final boolean[] active;
        final List<SingleEquationTerm<V, E>> terms;
        /** For each term array: its values and its derivatives, as of the last call to {@link #readTermArrays()}. */
        final double[][] values;
        final double[][][] derivatives;

        ElementTerms(int[] start, List<EquationTermArray<V, E>> termArrays, TIntArrayList termArray, TIntArrayList element,
                     TDoubleArrayList coefficient, List<int[]> localIndex, List<int[]> position, List<int[]> matrixElementIndex,
                     List<SingleEquationTerm<V, E>> terms) {
            this.start = start;
            this.termArrays = termArrays;
            this.termArray = termArray.toArray();
            this.element = element.toArray();
            this.coefficient = coefficient.toArray();
            this.localIndex = localIndex.toArray(new int[0][]);
            this.position = position.toArray(new int[0][]);
            this.matrixElementIndex = matrixElementIndex.toArray(new int[0][]);
            this.active = new boolean[terms.size()];
            this.terms = terms;
            values = new double[termArrays.size()][];
            derivatives = new double[termArrays.size()][][];
            updateActivity();
        }

        /** Reads again which terms are active: to call when an activation changed, before the terms are evaluated. */
        void updateActivity() {
            for (int i = 0; i < active.length; i++) {
                active[i] = terms.get(i).isActive();
            }
        }

        /** Takes the values and the derivatives the term arrays hold at the current state. */
        void readTermArrays() {
            for (int a = 0; a < values.length; a++) {
                values[a] = termArrays.get(a).eval();
                derivatives[a] = termArrays.get(a).evalDer();
            }
        }

        /** The coefficient of a term that computes it at each evaluation. */
        double computeCoefficient(int term) {
            return ((SingleEquationTerm.MultiplyByScalarEquationTerm<V, E>) terms.get(term)).getScalar();
        }
    }

    /** A term held as an object that is a coefficient times a variable. */
    static final byte LINEAR = 0;
    /** A term held as an object that is a coefficient times an element of a term array. */
    static final byte ELEMENT = 1;
    /** Any other term held as an object: it is evaluated by a call to it. */
    static final byte OTHER = 2;

    private static final int[] NO_SLOT = new int[0];

    private final List<Variable<V>> variables;
    private final Map<Variable<V>, Integer> positions = new HashMap<>();

    private final List<SingleEquation<V, E>> singleEquations;
    private final Map<Long, Integer> singleEquationNumbers = new HashMap<>();
    /** False for a single equation that was removed from the system and not created again since: it holds nothing. */
    private final boolean[] bound;
    private final List<EquationArray<V, E>> arrays;
    private final int[] arrayFirstEquation;
    /** For each equation that is an element of an equation array: the index of its array; -1 for a single equation. */
    private final int[] arrayIndexOfEquation;
    /** For each equation that is an element of an equation array: its element number; -1 for a single equation. */
    private final int[] elementNumOfEquation;
    private final int equationCount;
    private final int[][] rows;
    private final int[] ownPosition;
    /** For each equation, the position its matrix element indices are for: its own at first, then the one the pairing gives it. */
    private final int[] pairedPosition;
    /** The equations that changed position since their matrix element indices were computed. */
    private final TIntArrayList staleMatrixElementIndices = new TIntArrayList();
    /** True for an equation with a term that depends on the variable it is paired with. */
    private final boolean[] dependsOnOwnVariable;
    /** True for an equation that takes part in a chain of moves: what it depends on is then asked of its active terms. */
    private final boolean[] movesInChain;
    private final boolean[] fixesVariable;
    /** For each equation, the positions it may take besides its own, sorted: empty for most. */
    private final int[][] controllerPositions;
    /** For each position, the equations that can hold it, built when first asked for. */
    private int[][] alternatives;
    /** For each equation, the terms it holds as objects: null when it has none. */
    private final List<ObjectTerms<V, E>> objectTerms;
    /**
     * True for an equation that holds as an object a term that is neither a coefficient times a variable nor a
     * coefficient times an element of a term array: its object terms are evaluated one by one.
     */
    private final boolean[] mixed;
    /** The linear terms of the other equations: built when asked for, and again when an equation is compiled again. */
    private LinearTerms<V, E> linearTerms;
    /** Their terms that refer to an element of a term array: built when asked for, and again when an equation is compiled again. */
    private ElementTerms<V, E> elementTerms;

    private final List<ArrayTerms<V, E>> arrayTerms = new ArrayList<>();
    /** For each equation array, the terms of its term arrays. */
    private final List<List<ArrayTerms<V, E>>> arrayTermsOfArray = new ArrayList<>();
    private final int[] arrayElementsWithSingleTerms;

    private int[] columnStart;
    private int[] rowIndices;
    private int[] diagonal;

    FixedStructure(EquationSystem<V, E> equationSystem, VariablePairing<V, E> pairing) {
        // numbered by type and element, not in the order of OLF's hash map, which changes from one run to the next:
        // where several pairings are possible the choice follows the numbers, and must be the same each time
        singleEquations = new ArrayList<>(equationSystem.getEquations());
        singleEquations.sort(Comparator.<SingleEquation<V, E>>comparingInt(equation -> equation.getType().ordinal())
                .thenComparingInt(SingleEquation::getElementNum));
        bound = new boolean[singleEquations.size()];
        Arrays.fill(bound, true);
        for (int equation = 0; equation < singleEquations.size(); equation++) {
            singleEquationNumbers.put(key(singleEquations.get(equation).getType(), singleEquations.get(equation).getElementNum()), equation);
        }
        arrays = List.copyOf(equationSystem.getEquationArrays());
        arrayFirstEquation = new int[arrays.size()];
        int count = singleEquations.size();
        for (int a = 0; a < arrays.size(); a++) {
            arrayFirstEquation[a] = count;
            count += arrays.get(a).getElementCount();
        }
        equationCount = count;
        arrayIndexOfEquation = new int[equationCount];
        elementNumOfEquation = new int[equationCount];
        Arrays.fill(arrayIndexOfEquation, 0, singleEquations.size(), -1);
        Arrays.fill(elementNumOfEquation, 0, singleEquations.size(), -1);
        for (int a = 0; a < arrays.size(); a++) {
            for (int element = 0; element < arrays.get(a).getElementCount(); element++) {
                arrayIndexOfEquation[arrayFirstEquation[a] + element] = a;
                elementNumOfEquation[arrayFirstEquation[a] + element] = element;
            }
        }

        SortedSet<Variable<V>> sorted = new TreeSet<>();
        for (int equation = 0; equation < equationCount; equation++) {
            forEachVariable(equation, sorted::add);
        }
        variables = List.copyOf(sorted);
        Map<Long, Integer> positionByTypeAndElement = new HashMap<>();
        for (int position = 0; position < variables.size(); position++) {
            Variable<V> variable = variables.get(position);
            positions.put(variable, position);
            positionByTypeAndElement.put(key(variable.getType(), variable.getElementNum()), position);
        }

        rows = new int[equationCount][];
        ownPosition = new int[equationCount];
        dependsOnOwnVariable = new boolean[equationCount];
        movesInChain = new boolean[equationCount];
        fixesVariable = new boolean[equationCount];
        TIntArrayList withSingleTerms = new TIntArrayList();
        TIntArrayList buffer = new TIntArrayList();
        for (int equation = 0; equation < equationCount; equation++) {
            buffer.resetQuick();
            forEachVariable(equation, variable -> buffer.add(positions.get(variable)));
            rows[equation] = sortedDistinct(buffer);
            E type = getType(equation);
            V variableType = pairing.variableType(type);
            ownPosition[equation] = variableType == null ? -1
                    : positionByTypeAndElement.getOrDefault(key(variableType, getElementNum(equation)), -1);
            dependsOnOwnVariable[equation] = ownPosition[equation] >= 0 && Arrays.binarySearch(rows[equation], ownPosition[equation]) >= 0;
            fixesVariable[equation] = pairing.fixesVariable(type);
            if (equation >= singleEquations.size() && !getSingleTerms(equation).isEmpty()) {
                withSingleTerms.add(equation);
            }
        }
        arrayElementsWithSingleTerms = withSingleTerms.toArray();
        pairedPosition = ownPosition.clone();
        objectTerms = new ArrayList<>(equationCount);
        mixed = new boolean[equationCount];
        for (int equation = 0; equation < equationCount; equation++) {
            objectTerms.add(compileObjectTerms(equation));
        }

        controllerPositions = new int[equationCount][];
        Arrays.fill(controllerPositions, NO_SLOT);
        Map<Integer, TIntArrayList> controllerPositionLists = new HashMap<>();
        pairing.forEachControllerVariable((equationType, equationElementNum, variableType, variableElementNum) -> {
            int equation = getNumber(equationType, equationElementNum);
            Integer position = positionByTypeAndElement.get(key(variableType, variableElementNum));
            if (equation >= 0 && position != null && position != ownPosition[equation]) {
                controllerPositionLists.computeIfAbsent(equation, e -> new TIntArrayList()).add(position);
            }
        });
        addExchangesAgainstZeroDiagonals(controllerPositionLists, pairing);
        controllerPositionLists.forEach((equation, positionList) -> controllerPositions[equation] = sortedDistinct(positionList));

        for (int a = 0; a < arrays.size(); a++) {
            List<ArrayTerms<V, E>> termsOfArray = new ArrayList<>();
            for (EquationTermArray<V, E> termArray : arrays.get(a).getTermArrays()) {
                termsOfArray.add(compile(termArray, a));
            }
            arrayTerms.addAll(termsOfArray);
            arrayTermsOfArray.add(termsOfArray);
        }
    }

    /**
     * An equation on a position whose variable it does not depend on leaves a zero on the diagonal, around which a
     * factorisation has to pivot. Two kinds of equations would:
     * <ul>
     * <li>one that does not depend on the variable it is paired with: the two equations that tie the buses of a zero
     * impedance branch together, paired with the flows of the branch, or the balance of a bus that only such
     * branches connect;</li>
     * <li>one that a control sends to the position of a variable it does not depend on: the reactive balance of a bus
     * whose voltage is held by a shunt of another bus, sent to the susceptance of that shunt.</li>
     * </ul>
     * Such an equation takes the position of a variable it depends on instead, and the equation paired with that position the
     * position the first one leaves: the equation of the branch goes to the angle of a bus and the balance of that bus to
     * the flow of the branch; the balance of the controlled bus goes to the voltage of the bus of the shunt, and the
     * balance of that bus to the susceptance. An equation only moves to a variable of the kind of its own. These moves
     * are added to the controller positions, so that the matrix structure holds them from the start. The pairing then finds, in each
     * state, which ones to make.
     */
    private void addExchangesAgainstZeroDiagonals(Map<Integer, TIntArrayList> controllerPositionLists, VariablePairing<V, E> pairing) {
        ChainSearch search = new ChainSearch(controllerPositionLists, pairing);
        List<int[]> independent = new ArrayList<>();
        controllerPositionLists.forEach((equation, positionList) -> {
            for (int i = 0; i < positionList.size(); i++) {
                if (Arrays.binarySearch(rows[equation], positionList.getQuick(i)) < 0) {
                    independent.add(new int[] {equation, positionList.getQuick(i)});
                }
            }
        });
        independent.sort(Comparator.<int[]>comparingInt(pair -> pair[0]).thenComparingInt(pair -> pair[1]));
        for (int[] pair : independent) {
            search.addChains(pair[0], pair[1]);
        }
        for (int equation = 0; equation < equationCount; equation++) {
            if (ownPosition[equation] >= 0 && rows[equation].length > 0 && !dependsOnOwnVariable[equation]) {
                search.addChains(equation, ownPosition[equation]);
            }
        }
    }

    /**
     * The search of the moves that bring to a position an equation which depends on its variable, when the equation that
     * should take the position does not: this one takes the position of a variable it depends on, the equation paired with
     * that position the position of a variable it depends on in turn, and so on until one depends on the variable of the first
     * position. Most often the first equation paired with a position is enough: a chain of one move each way, an exchange.
     */
    private final class ChainSearch {

        /** How many equations a search visits at most: it gives up on a position that nothing close depends on. */
        private static final int MAX_VISITED_COUNT = 256;

        private final Map<Integer, TIntArrayList> controllerPositionLists;
        private final int[] kind = new int[variables.size()];
        private final TIntArrayList[] pairedWith;
        private final int[] visited = new int[equationCount];
        private final int[] previous = new int[equationCount];
        private final int[] positionTaken = new int[equationCount];
        private final TIntArrayList queue = new TIntArrayList();
        private int stamp;

        private ChainSearch(Map<Integer, TIntArrayList> controllerPositionLists, VariablePairing<V, E> pairing) {
            this.controllerPositionLists = controllerPositionLists;
            for (int position = 0; position < kind.length; position++) {
                kind[position] = pairing.kind(variables.get(position).getType());
            }
            pairedWith = new TIntArrayList[variables.size()];
            for (int equation = 0; equation < equationCount; equation++) {
                int own = ownPosition[equation];
                if (own >= 0) {
                    if (pairedWith[own] == null) {
                        pairedWith[own] = new TIntArrayList(2);
                    }
                    pairedWith[own].add(equation);
                }
            }
        }

        /** Adds to the controller positions the moves of the shortest chains from the equation to the position. */
        private void addChains(int first, int target) {
            stamp++;
            queue.resetQuick();
            queue.add(first);
            visited[first] = stamp;
            previous[first] = -1;
            boolean found = false;
            int levelEnd = 1;
            for (int head = 0; head < queue.size() && queue.size() <= MAX_VISITED_COUNT; head++) {
                if (head == levelEnd) {
                    if (found) {
                        return;
                    }
                    levelEnd = queue.size();
                }
                found |= visit(queue.getQuick(head), target);
            }
        }

        /**
         * Looks at the equations paired with the variables an equation depends on, among the variables of the kind of
         * its own. True when one of these equations ends a chain.
         */
        private boolean visit(int equation, int target) {
            boolean found = false;
            int ownKind = kind[ownPosition[equation] >= 0 ? ownPosition[equation] : target];
            for (int position : rows[equation]) {
                if (position == target || position == ownPosition[equation] || kind[position] != ownKind || pairedWith[position] == null) {
                    continue;
                }
                for (int i = 0; i < pairedWith[position].size(); i++) {
                    int other = pairedWith[position].getQuick(i);
                    if (kind[target] == kind[position] && Arrays.binarySearch(rows[other], target) >= 0) {
                        found = true;
                        addControllerPosition(other, target);
                        addControllerPosition(equation, position);
                        for (int e = equation; previous[e] >= 0; e = previous[e]) {
                            addControllerPosition(previous[e], positionTaken[e]);
                        }
                    } else if (visited[other] != stamp) {
                        visited[other] = stamp;
                        previous[other] = equation;
                        positionTaken[other] = position;
                        queue.add(other);
                    }
                }
            }
            return found;
        }

        private void addControllerPosition(int equation, int position) {
            movesInChain[equation] = true;
            if (position != ownPosition[equation]) {
                controllerPositionLists.computeIfAbsent(equation, e -> new TIntArrayList()).add(position);
            }
        }
    }

    /**
     * True when the equation depends on the variable of the position. For an equation that takes part in a chain of moves
     * this is asked of its active terms: around zero impedance branches the answer changes with the state.
     */
    boolean dependsOn(int equation, int position) {
        if (movesInChain[equation]) {
            return dependsOnNow(equation, position);
        }
        return position == ownPosition[equation] ? dependsOnOwnVariable[equation] : Arrays.binarySearch(rows[equation], position) >= 0;
    }

    /** True when a term of the equation that is active now depends on the variable of the position. */
    boolean dependsOnNow(int equation, int position) {
        ObjectTerms<V, E> compiled = objectTerms.get(equation);
        if (compiled != null) {
            for (int t = 0; t < compiled.row.length; t++) {
                if (contains(compiled.row[t], position) && compiled.terms.get(t).isActive()) {
                    return true;
                }
            }
        }
        if (equation >= singleEquations.size()) {
            int a = getArrayIndex(equation);
            int element = equation - arrayFirstEquation[a];
            for (ArrayTerms<V, E> terms : arrayTermsOfArray.get(a)) {
                TIntArrayList termNums = terms.termArray.getTermNumsForEquationElementNum(element);
                for (int i = 0; i < termNums.size(); i++) {
                    int term = termNums.getQuick(i);
                    if (contains(terms.row[term], position) && terms.termArray.isTermActive(term)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /** The object terms of an equation with the positions of their variables; null when it has none or when a variable has no position. */
    private ObjectTerms<V, E> compileObjectTerms(int equation) {
        List<SingleEquationTerm<V, E>> terms = List.copyOf(getSingleTerms(equation));
        linearTerms = null;
        elementTerms = null;
        mixed[equation] = false;
        byte[] kind = new byte[terms.size()];
        for (int t = 0; t < kind.length; t++) {
            kind[t] = kind(terms.get(t));
            mixed[equation] |= kind[t] == OTHER;
        }
        if (terms.isEmpty()) {
            return null;
        }
        ObjectTerms<V, E> compiled = new ObjectTerms<>(terms, kind);
        for (int t = 0; t < terms.size(); t++) {
            List<Variable<V>> termVariables = terms.get(t).getVariables();
            compiled.variables.add(termVariables);
            compiled.row[t] = new int[termVariables.size()];
            compiled.matrixElementIndex[t] = new int[termVariables.size()];
            for (int k = 0; k < termVariables.size(); k++) {
                Integer position = positions.get(termVariables.get(k));
                if (position == null) {
                    return null;
                }
                compiled.row[t][k] = position;
                compiled.matrixElementIndex[t][k] = rowIndices != null && pairedPosition[equation] >= 0 ? index(pairedPosition[equation], position) : -1;
            }
        }
        return compiled;
    }

    /** A variable or an element of a term array, alone or multiplied by a scalar, is evaluated from arrays. */
    private static byte kind(SingleEquationTerm<?, ?> term) {
        SingleEquationTerm<?, ?> multiplied = multiplied(term);
        if (multiplied instanceof VariableEquationTerm) {
            return LINEAR;
        }
        // one derivative for each variable, so that the derivatives of the term are those of its variables, in their order
        if (multiplied instanceof EquationTermArrayElementReference<?, ?> reference
                && reference.getDerivatives().size() == reference.getVariables().size()) {
            return ELEMENT;
        }
        return OTHER;
    }

    /** The term a scalar multiplies, or the term itself. */
    private static SingleEquationTerm<?, ?> multiplied(SingleEquationTerm<?, ?> term) {
        return term instanceof SingleEquationTerm.MultiplyByScalarEquationTerm<?, ?> multiple ? multiple.getChildren().get(0) : term;
    }

    /** The coefficient of a term evaluated from arrays: NaN when its scalar is not a constant. */
    private static double coefficient(SingleEquationTerm<?, ?> term) {
        if (term instanceof SingleEquationTerm.MultiplyByScalarEquationTerm<?, ?> multiple) {
            return multiple.isScalarConstant() ? multiple.getScalar() : Double.NaN;
        }
        return 1;
    }

    /** True for an equation whose object terms are evaluated one by one: one of them is not evaluated from arrays. */
    boolean isMixed(int equation) {
        return mixed[equation];
    }

    /** The linear terms of the equations that are not mixed. */
    LinearTerms<V, E> getLinearTerms() {
        if (linearTerms == null) {
            int[] start = new int[equationCount + 1];
            TIntArrayList position = new TIntArrayList();
            TDoubleArrayList coefficient = new TDoubleArrayList();
            TIntArrayList matrixElementIndex = new TIntArrayList();
            List<SingleEquationTerm<V, E>> terms = new ArrayList<>();
            List<Variable<V>> termVariables = new ArrayList<>();
            for (int equation = 0; equation < equationCount; equation++) {
                start[equation] = terms.size();
                ObjectTerms<V, E> compiled = objectTerms.get(equation);
                if (compiled != null && !mixed[equation]) {
                    for (int t = 0; t < compiled.row.length; t++) {
                        if (compiled.kind[t] == LINEAR) {
                            position.add(compiled.row[t][0]);
                            coefficient.add(coefficient(compiled.terms.get(t)));
                            matrixElementIndex.add(compiled.matrixElementIndex[t][0]);
                            terms.add(compiled.terms.get(t));
                            termVariables.add(compiled.variables.get(t).get(0));
                        }
                    }
                }
            }
            start[equationCount] = terms.size();
            linearTerms = new LinearTerms<>(start, position, coefficient, matrixElementIndex, terms, termVariables);
        }
        return linearTerms;
    }

    /** The terms of the equations that are not mixed that refer to an element of a term array. */
    ElementTerms<V, E> getElementTerms() {
        if (elementTerms == null) {
            int[] start = new int[equationCount + 1];
            List<EquationTermArray<V, E>> termArrays = new ArrayList<>();
            Map<EquationTermArray<V, E>, Integer> termArrayIndices = new IdentityHashMap<>();
            TIntArrayList termArray = new TIntArrayList();
            TIntArrayList element = new TIntArrayList();
            TDoubleArrayList coefficient = new TDoubleArrayList();
            List<int[]> localIndex = new ArrayList<>();
            List<int[]> position = new ArrayList<>();
            List<int[]> matrixElementIndex = new ArrayList<>();
            List<SingleEquationTerm<V, E>> terms = new ArrayList<>();
            for (int equation = 0; equation < equationCount; equation++) {
                start[equation] = terms.size();
                ObjectTerms<V, E> compiled = objectTerms.get(equation);
                if (compiled != null && !mixed[equation]) {
                    for (int t = 0; t < compiled.row.length; t++) {
                        if (compiled.kind[t] == ELEMENT) {
                            @SuppressWarnings("unchecked")
                            EquationTermArrayElementReference<V, E> reference = (EquationTermArrayElementReference<V, E>) multiplied(compiled.terms.get(t));
                            termArray.add(termArrayIndices.computeIfAbsent(reference.getTermArray(), array -> {
                                termArrays.add(array);
                                return termArrays.size() - 1;
                            }));
                            element.add(reference.getTermElementNum());
                            coefficient.add(coefficient(compiled.terms.get(t)));
                            localIndex.add(reference.getDerivatives().stream().mapToInt(Derivative::getLocalIndex).toArray());
                            // the arrays of the compiled term itself: its matrix element indices follow the position of the equation
                            position.add(compiled.row[t]);
                            matrixElementIndex.add(compiled.matrixElementIndex[t]);
                            terms.add(compiled.terms.get(t));
                        }
                    }
                }
            }
            start[equationCount] = terms.size();
            elementTerms = new ElementTerms<>(start, termArrays, termArray, element, coefficient, localIndex, position, matrixElementIndex, terms);
        }
        return elementTerms;
    }

    private ArrayTerms<V, E> compile(EquationTermArray<V, E> termArray, int a) {
        int elementCount = arrays.get(a).getElementCount();
        int termCount = 0;
        for (int element = 0; element < elementCount; element++) {
            termCount += termArray.getTermNumsForEquationElementNum(element).size();
        }
        ArrayTerms<V, E> terms = new ArrayTerms<>(termArray, termCount);
        for (int element = 0; element < elementCount; element++) {
            TIntArrayList termNums = termArray.getTermNumsForEquationElementNum(element);
            int equation = arrayFirstEquation[a] + element;
            if (!termNums.isEmpty() && ownPosition[equation] < 0) {
                throw new PowsyblException("No variable to pair the element " + element + " of the equation array "
                        + arrays.get(a).getType() + " with");
            }
            for (int i = 0; i < termNums.size(); i++) {
                int term = termNums.getQuick(i);
                List<Derivative<V>> derivatives = termArray.getTermDerivatives(term);
                terms.equation[term] = equation;
                terms.element[term] = termArray.getTermElementNum(term);
                terms.localIndex[term] = new int[derivatives.size()];
                terms.row[term] = new int[derivatives.size()];
                terms.matrixElementIndex[term] = new int[derivatives.size()];
                for (int k = 0; k < derivatives.size(); k++) {
                    terms.localIndex[term][k] = derivatives.get(k).getLocalIndex();
                    terms.row[term][k] = positions.get(derivatives.get(k).getVariable());
                }
            }
        }
        return terms;
    }

    /**
     * Builds the matrix structure that holds the given pairings: each equation's rows in the column of its position, and the
     * diagonal of every position. Then the matrix element indices of the array terms.
     */
    void buildMatrixStructure(TIntArrayList pairedEquations, TIntArrayList pairedPositions) {
        int variableCount = variables.size();
        int[] start = new int[variableCount + 1];
        for (int position = 0; position < variableCount; position++) {
            start[position + 1] = 1;
        }
        for (int i = 0; i < pairedEquations.size(); i++) {
            start[pairedPositions.getQuick(i) + 1] += rows[pairedEquations.getQuick(i)].length;
        }
        for (int position = 0; position < variableCount; position++) {
            start[position + 1] += start[position];
        }
        int[] raw = new int[start[variableCount]];
        int[] next = Arrays.copyOf(start, variableCount);
        for (int position = 0; position < variableCount; position++) {
            raw[next[position]++] = position;
        }
        for (int i = 0; i < pairedEquations.size(); i++) {
            int position = pairedPositions.getQuick(i);
            for (int row : rows[pairedEquations.getQuick(i)]) {
                raw[next[position]++] = row;
            }
        }
        columnStart = new int[variableCount + 1];
        int size = 0;
        for (int position = 0; position < variableCount; position++) {
            Arrays.sort(raw, start[position], start[position + 1]);
            columnStart[position] = size;
            for (int i = start[position]; i < start[position + 1]; i++) {
                if (size == columnStart[position] || raw[size - 1] != raw[i]) {
                    raw[size++] = raw[i];
                }
            }
        }
        columnStart[variableCount] = size;
        rowIndices = Arrays.copyOf(raw, size);
        diagonal = new int[variableCount];
        for (int position = 0; position < variableCount; position++) {
            diagonal[position] = index(position, position);
        }
        setMatrixElementIndices();
    }

    /**
     * Takes the matrix structure of the structure this one replaces, when both have the same positions: the system was compiled
     * again, for an equation it had not seen, and its matrix can stay as long as the matrix structure holds what is paired.
     */
    boolean adoptMatrixStructure(FixedStructure<V, E> previous) {
        if (previous.rowIndices == null || !variables.equals(previous.variables)) {
            return false;
        }
        columnStart = previous.columnStart;
        rowIndices = previous.rowIndices;
        diagonal = previous.diagonal;
        setMatrixElementIndices();
        return true;
    }

    private void setMatrixElementIndices() {
        staleMatrixElementIndices.resetQuick();
        linearTerms = null;
        for (int equation = 0; equation < equationCount; equation++) {
            setObjectTermMatrixElementIndices(equation);
        }
        for (ArrayTerms<V, E> terms : arrayTerms) {
            for (int term = 0; term < terms.equation.length; term++) {
                setMatrixElementIndices(terms, term);
            }
        }
    }

    private void setObjectTermMatrixElementIndices(int equation) {
        ObjectTerms<V, E> compiled = objectTerms.get(equation);
        if (compiled != null) {
            // the table of the linear terms holds a copy of their matrix element indices; the one of the element terms the arrays themselves
            int linear = linearTerms != null && !mixed[equation] ? linearTerms.start[equation] : -1;
            for (int t = 0; t < compiled.row.length; t++) {
                for (int k = 0; k < compiled.row[t].length; k++) {
                    compiled.matrixElementIndex[t][k] = pairedPosition[equation] >= 0 ? index(pairedPosition[equation], compiled.row[t][k]) : -1;
                }
                if (linear >= 0 && compiled.kind[t] == LINEAR) {
                    linearTerms.matrixElementIndex[linear++] = compiled.matrixElementIndex[t][0];
                }
            }
        }
    }

    private void setMatrixElementIndices(ArrayTerms<V, E> terms, int term) {
        for (int k = 0; k < terms.row[term].length; k++) {
            terms.matrixElementIndex[term][k] = index(pairedPosition[terms.equation[term]], terms.row[term][k]);
        }
    }

    /**
     * The position an equation holds now. Its matrix element indices are for that position, so that a fill writes each derivative
     * without looking for its entry, wherever the equation is: {@link #updateMatrixElementIndices()} computes them again.
     */
    void setPairedPosition(int equation, int position) {
        if (pairedPosition[equation] != position) {
            pairedPosition[equation] = position;
            staleMatrixElementIndices.add(equation);
        }
    }

    /** Computes again the matrix element indices of the equations that changed position. The matrix structure must hold their entries. */
    void updateMatrixElementIndices() {
        for (int i = 0; i < staleMatrixElementIndices.size(); i++) {
            int equation = staleMatrixElementIndices.getQuick(i);
            setObjectTermMatrixElementIndices(equation);
            if (equation >= singleEquations.size()) {
                int a = getArrayIndex(equation);
                for (ArrayTerms<V, E> terms : arrayTermsOfArray.get(a)) {
                    TIntArrayList termNums = terms.termArray.getTermNumsForEquationElementNum(equation - arrayFirstEquation[a]);
                    for (int j = 0; j < termNums.size(); j++) {
                        setMatrixElementIndices(terms, termNums.getQuick(j));
                    }
                }
            }
        }
        staleMatrixElementIndices.resetQuick();
    }

    /** The number of the equation of a type and an element, single or element of an array; -1 when there is none. */
    int getNumber(E type, int elementNum) {
        Integer number = singleEquationNumbers.get(key(type, elementNum));
        if (number != null) {
            return number;
        }
        for (int a = 0; a < arrays.size(); a++) {
            if (arrays.get(a).getType() == type && elementNum < arrays.get(a).getElementCount()) {
                return arrayFirstEquation[a] + elementNum;
            }
        }
        return -1;
    }

    /**
     * The number of a single equation of this structure. It is -1 for any other equation: one created since the
     * compilation and not bound, or the element of an equation array.
     */
    int getNumber(Equation<V, E> equation) {
        Integer number = singleEquationNumbers.get(key(equation.getType(), equation.getElementNum()));
        return number != null && bound[number] && singleEquations.get(number) == equation ? number : -1;
    }

    /** A single equation was removed from the system: it keeps its number and its rows, and holds nothing. */
    boolean unbind(SingleEquation<V, E> equation) {
        int number = getNumber(equation);
        if (number >= 0) {
            bound[number] = false;
            objectTerms.set(number, null);
            mixed[number] = false;
            linearTerms = null;
            elementTerms = null;
        }
        return number >= 0;
    }

    /**
     * A single equation was created in the system: it takes the number of the removed one of the same type and
     * element, and so the same position. Returns that number, or -1 when there is none.
     */
    int bind(SingleEquation<V, E> equation) {
        Integer number = singleEquationNumbers.get(key(equation.getType(), equation.getElementNum()));
        if (number == null || bound[number]) {
            return -1;
        }
        singleEquations.set(number, equation);
        bound[number] = true;
        return number;
    }

    /**
     * Adds the variables of the terms a single equation has now to its rows, which only grow, like the matrix structure.
     * Returns false when one of these variables has no position.
     */
    boolean addRows(int equation) {
        TIntArrayList all = new TIntArrayList(rows[equation]);
        for (SingleEquationTerm<V, E> term : getSingleTerms(equation)) {
            for (Variable<V> variable : term.getVariables()) {
                Integer position = positions.get(variable);
                if (position == null) {
                    return false;
                }
                all.add(position);
            }
        }
        rows[equation] = sortedDistinct(all);
        dependsOnOwnVariable[equation] = ownPosition[equation] >= 0 && Arrays.binarySearch(rows[equation], ownPosition[equation]) >= 0;
        objectTerms.set(equation, compileObjectTerms(equation));
        return true;
    }

    /** The index of the value at (row, column), or -1 when the matrix structure does not hold it. */
    public int index(int column, int row) {
        int index = Arrays.binarySearch(rowIndices, columnStart[column], columnStart[column + 1], row);
        return index >= 0 ? index : -1;
    }

    /** True when a column of the matrix structure already holds the rows of an equation. */
    boolean holds(int column, int equation) {
        for (int row : rows[equation]) {
            if (index(column, row) < 0) {
                return false;
            }
        }
        return true;
    }

    private void forEachVariable(int equation, Consumer<Variable<V>> consumer) {
        for (SingleEquationTerm<V, E> term : getSingleTerms(equation)) {
            term.getVariables().forEach(consumer);
        }
        if (equation >= singleEquations.size()) {
            int element = getElementNum(equation);
            for (EquationTermArray<V, E> termArray : getArray(equation).getTermArrays()) {
                TIntArrayList termNums = termArray.getTermNumsForEquationElementNum(element);
                for (int i = 0; i < termNums.size(); i++) {
                    for (Derivative<V> derivative : termArray.getTermDerivatives(termNums.getQuick(i))) {
                        consumer.accept(derivative.getVariable());
                    }
                }
            }
        }
    }

    private static int[] sortedDistinct(TIntArrayList values) {
        values.sort();
        int size = 0;
        for (int i = 0; i < values.size(); i++) {
            if (size == 0 || values.getQuick(size - 1) != values.getQuick(i)) {
                values.setQuick(size++, values.getQuick(i));
            }
        }
        return values.toArray(0, size);
    }

    private static long key(Enum<?> type, int elementNum) {
        return ((long) type.ordinal() << 32) | elementNum;
    }

    private int getArrayIndex(int equation) {
        return arrayIndexOfEquation[equation];
    }

    private EquationArray<V, E> getArray(int equation) {
        return arrays.get(getArrayIndex(equation));
    }

    public int getVariableCount() {
        return variables.size();
    }

    public Variable<V> getVariable(int position) {
        return variables.get(position);
    }

    /** The position of a variable, or -1 when no term of the system depends on it. */
    public int getPosition(Variable<V> variable) {
        return positions.getOrDefault(variable, -1);
    }

    public int getEquationCount() {
        return equationCount;
    }

    /** The single equations are the first equations; the elements of the equation arrays follow. */
    public int getSingleEquationCount() {
        return singleEquations.size();
    }

    public E getType(int equation) {
        return equation < singleEquations.size() ? singleEquations.get(equation).getType() : getArray(equation).getType();
    }

    public int getElementNum(int equation) {
        return equation < singleEquations.size() ? singleEquations.get(equation).getElementNum()
                : equation - arrayFirstEquation[getArrayIndex(equation)];
    }

    public boolean isArrayElement(int equation) {
        return equation >= singleEquations.size();
    }

    public boolean isActive(int equation) {
        return equation < singleEquations.size() ? bound[equation] && singleEquations.get(equation).isActive()
                : getArray(equation).isElementActive(getElementNum(equation));
    }

    /** The column of the equation in OLF's own index: -1 when it is not active. */
    public int getColumnInEquationSystem(int equation) {
        if (equation < singleEquations.size()) {
            return bound[equation] ? singleEquations.get(equation).getColumn() : -1;
        }
        return arrays.get(arrayIndexOfEquation[equation]).getElementNumToColumn(elementNumOfEquation[equation]);
    }

    /** The terms held as objects: all the terms of a single equation, the additional terms of an array element. */
    public List<SingleEquationTerm<V, E>> getSingleTerms(int equation) {
        if (equation < singleEquations.size()) {
            return bound[equation] ? singleEquations.get(equation).getTerms() : List.of();
        }
        return getArray(equation).getSingleEquationTerms(getElementNum(equation));
    }

    /** The position the equation is paired with, or -1 when it has none of its own. */
    public int getOwnPosition(int equation) {
        return ownPosition[equation];
    }

    boolean fixesVariable(int equation) {
        return fixesVariable[equation];
    }

    /** The terms the equation holds as objects, compiled; null when it has none. */
    ObjectTerms<V, E> getObjectTerms(int equation) {
        return objectTerms.get(equation);
    }

    /**
     * The positions the equation may take besides its own: those its {@link VariablePairing} gives, and those of the moves that
     * keep zeros off the diagonal.
     */
    int[] getControllerPositions(int equation) {
        return controllerPositions[equation];
    }

    /**
     * For each position, the equations that can hold it, in the order of their numbers: those whose own position it is and
     * those that have it among their controller positions. Built once with the structure: it is the table a mode of a position
     * indexes ({@link FixedEquationSystemIndex#getModes()}).
     */
    public int[] getAlternatives(int position) {
        if (alternatives == null) {
            TIntArrayList[] lists = new TIntArrayList[getVariableCount()];
            for (int equation = 0; equation < getEquationCount(); equation++) {
                if (ownPosition[equation] >= 0) {
                    alternativesOf(lists, ownPosition[equation]).add(equation);
                }
                for (int candidate : controllerPositions[equation]) {
                    alternativesOf(lists, candidate).add(equation);
                }
            }
            int[][] built = new int[lists.length][];
            for (int s = 0; s < lists.length; s++) {
                built[s] = lists[s] == null ? new int[0] : sortedDistinct(lists[s]);
            }
            alternatives = built;
        }
        return alternatives[position];
    }

    private static TIntArrayList alternativesOf(TIntArrayList[] lists, int position) {
        if (lists[position] == null) {
            lists[position] = new TIntArrayList(2);
        }
        return lists[position];
    }

    /** The positions of the variables the equation depends on, sorted. */
    int[] getRows(int equation) {
        return rows[equation];
    }

    List<ArrayTerms<V, E>> getArrayTerms() {
        return arrayTerms;
    }

    int[] getArrayElementsWithSingleTerms() {
        return arrayElementsWithSingleTerms;
    }

    public int[] getColumnStart() {
        return columnStart;
    }

    public int[] getRowIndices() {
        return rowIndices;
    }

    public int[] getDiagonal() {
        return diagonal;
    }

    public int getValueCount() {
        return rowIndices.length;
    }
}
