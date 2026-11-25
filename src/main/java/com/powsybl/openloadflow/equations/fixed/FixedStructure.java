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
 * <li><b>Slots.</b> One per variable that a term of the system can depend on, in the order of the variables. A slot is
 *     a row (the variable) and a column (the equation that determines it).</li>
 * <li><b>Equations.</b> Numbered once: the single equations, then the elements of each equation array. Each knows the
 *     slots of the variables it depends on, and its own slot: the one its {@link Placement} pairs it with. A single
 *     equation that is removed and created again keeps its number: it is the same one, with other terms.</li>
 * <li><b>Pattern.</b> The transposed Jacobian by columns, which is the Jacobian by rows: for each slot, the sorted rows
 *     of every equation placed there, plus the diagonal. It is built from a list of placements and only ever
 *     grows.</li>
 * <li><b>Destinations.</b> For each term of a term array and each of its derivatives, the index of its value in the
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
        /** For each derivative of each term: the slot of its variable. */
        final int[][] row;
        /** For each derivative of each term: its value index in the own column of the equation, -1 when the pattern lacks it. */
        final int[][] destination;

        ArrayTerms(EquationTermArray<V, E> termArray, int termCount) {
            this.termArray = termArray;
            equation = new int[termCount];
            element = new int[termCount];
            localIndex = new int[termCount][];
            row = new int[termCount][];
            destination = new int[termCount][];
        }
    }

    /** The terms an equation holds as objects: for each variable of each one, its slot and where its derivative goes. */
    static final class ObjectTerms<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        final List<SingleEquationTerm<V, E>> terms;
        /** For each term, its variables. */
        final List<List<Variable<V>>> variables = new ArrayList<>();
        /** For each variable of each term: its slot. */
        final int[][] row;
        /** For each variable of each term: the value index in the column of the slot of the equation, -1 when the pattern lacks it. */
        final int[][] destination;
        /** For each term: {@link #LINEAR}, {@link #ELEMENT} or {@link #OTHER}. */
        final byte[] kind;

        ObjectTerms(List<SingleEquationTerm<V, E>> terms, byte[] kind) {
            this.terms = terms;
            this.kind = kind;
            row = new int[terms.size()][];
            destination = new int[terms.size()][];
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
        /** For each term: the slot of its variable. */
        final int[] slot;
        /** For each term: its coefficient, or NaN when the term computes it at each evaluation. */
        final double[] coefficient;
        /** For each term: the value index in the own column of its equation, -1 when the pattern lacks it. */
        final int[] destination;
        /** For each term: whether it is active, as of the last call to {@link #updateActivity()}. */
        final boolean[] active;
        final List<SingleEquationTerm<V, E>> terms;
        final List<Variable<V>> variables;
        /**
         * For each equation: true when all the terms it holds as objects are linear, which are then its terms of this
         * table in the order of their creation. Most single equations: those of the zero impedance branches.
         */
        final boolean[] linearOnly;

        LinearTerms(int[] start, TIntArrayList slot, TDoubleArrayList coefficient, TIntArrayList destination,
                    List<SingleEquationTerm<V, E>> terms, List<Variable<V>> variables, boolean[] linearOnly) {
            this.start = start;
            this.linearOnly = linearOnly;
            this.slot = slot.toArray();
            this.coefficient = coefficient.toArray();
            this.destination = destination.toArray();
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

        /** The same for the terms of one equation only: one whose activity, or the activity of a term, changed. */
        void updateActivity(int equation) {
            for (int i = start[equation]; i < start[equation + 1]; i++) {
                active[i] = terms.get(i).isActive();
            }
        }

        /** For an assertion: the activity kept is the one of the terms now. */
        boolean isActivityCurrent() {
            for (int i = 0; i < active.length; i++) {
                if (active[i] != terms.get(i).isActive()) {
                    return false;
                }
            }
            return true;
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
        /** For each variable of each term: its slot. */
        final int[][] slot;
        /** For each variable of each term: the value index in the own column of its equation, -1 when the pattern lacks it. */
        final int[][] destination;
        /** For each term: whether it is active, as of the last call to {@link #updateActivity()}. */
        final boolean[] active;
        final List<SingleEquationTerm<V, E>> terms;
        /** For each term array: its values and its derivatives, as of the last call to {@link #readTermArrays()}. */
        final double[][] values;
        final double[][][] derivatives;

        ElementTerms(int[] start, List<EquationTermArray<V, E>> termArrays, TIntArrayList termArray, TIntArrayList element,
                     TDoubleArrayList coefficient, List<int[]> localIndex, List<int[]> slot, List<int[]> destination,
                     List<SingleEquationTerm<V, E>> terms) {
            this.start = start;
            this.termArrays = termArrays;
            this.termArray = termArray.toArray();
            this.element = element.toArray();
            this.coefficient = coefficient.toArray();
            this.localIndex = localIndex.toArray(new int[0][]);
            this.slot = slot.toArray(new int[0][]);
            this.destination = destination.toArray(new int[0][]);
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
    private final Map<Variable<V>, Integer> slots = new HashMap<>();

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
    private final int[] ownSlot;
    /** For each equation, the slot its destinations are for: its own at first, then the one the placement gives it. */
    private final int[] destinationSlot;
    /** The equations that changed slot since their destinations were computed. */
    private final TIntArrayList staleDestinations = new TIntArrayList();
    /** True for an equation with a term that depends on the variable it is paired with. */
    private final boolean[] dependsOnOwnSlot;
    /** True for an equation that takes part in a chain of moves: what it depends on is then asked of its active terms. */
    private final boolean[] movesInChain;
    private final boolean[] fixesVariable;
    /** For each equation, the slots it may take besides its own, sorted: empty for most. */
    private final int[][] candidates;
    /**
     * For an equation that takes part in a chain of moves, whether an active term depends on the variable of its own
     * slot, and of each of its candidates: what {@link #dependsOnNow} answers, kept from one placement to the next and
     * refreshed for the equations whose terms changed activity. Only the placement asks, for these slots, thousands of
     * times per placement around the zero impedance branches.
     */
    private final boolean[] ownSlotDependedOnNow;
    private final boolean[][] candidatesDependedOnNow;
    /** For each slot, the equations that can hold it, built when first asked for. */
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

    FixedStructure(EquationSystem<V, E> equationSystem, Placement<V, E> placement) {
        // numbered by type and element, not in the order of OLF's hash map, which changes from one run to the next:
        // where several placements are possible the choice follows the numbers, and must be the same each time
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
        Map<Long, Integer> slotByTypeAndElement = new HashMap<>();
        for (int slot = 0; slot < variables.size(); slot++) {
            Variable<V> variable = variables.get(slot);
            slots.put(variable, slot);
            slotByTypeAndElement.put(key(variable.getType(), variable.getElementNum()), slot);
        }

        rows = new int[equationCount][];
        ownSlot = new int[equationCount];
        dependsOnOwnSlot = new boolean[equationCount];
        movesInChain = new boolean[equationCount];
        fixesVariable = new boolean[equationCount];
        TIntArrayList withSingleTerms = new TIntArrayList();
        TIntArrayList buffer = new TIntArrayList();
        for (int equation = 0; equation < equationCount; equation++) {
            buffer.resetQuick();
            forEachVariable(equation, variable -> buffer.add(slots.get(variable)));
            rows[equation] = sortedDistinct(buffer);
            E type = getType(equation);
            V variableType = placement.variableType(type);
            ownSlot[equation] = variableType == null ? -1
                    : slotByTypeAndElement.getOrDefault(key(variableType, getElementNum(equation)), -1);
            dependsOnOwnSlot[equation] = ownSlot[equation] >= 0 && Arrays.binarySearch(rows[equation], ownSlot[equation]) >= 0;
            fixesVariable[equation] = placement.fixesVariable(type);
            if (equation >= singleEquations.size() && !getSingleTerms(equation).isEmpty()) {
                withSingleTerms.add(equation);
            }
        }
        arrayElementsWithSingleTerms = withSingleTerms.toArray();
        destinationSlot = ownSlot.clone();
        objectTerms = new ArrayList<>(equationCount);
        mixed = new boolean[equationCount];
        for (int equation = 0; equation < equationCount; equation++) {
            objectTerms.add(compileObjectTerms(equation));
        }

        candidates = new int[equationCount][];
        Arrays.fill(candidates, NO_SLOT);
        Map<Integer, TIntArrayList> candidateSlots = new HashMap<>();
        placement.forEachCandidate((equationType, equationElementNum, variableType, variableElementNum) -> {
            int equation = getNumber(equationType, equationElementNum);
            Integer slot = slotByTypeAndElement.get(key(variableType, variableElementNum));
            if (equation >= 0 && slot != null && slot != ownSlot[equation]) {
                candidateSlots.computeIfAbsent(equation, e -> new TIntArrayList()).add(slot);
            }
        });
        addCandidatesAgainstZeroDiagonals(candidateSlots, placement);
        candidateSlots.forEach((equation, slotList) -> candidates[equation] = sortedDistinct(slotList));
        ownSlotDependedOnNow = new boolean[equationCount];
        candidatesDependedOnNow = new boolean[equationCount][];
        for (int equation = 0; equation < equationCount; equation++) {
            if (movesInChain[equation]) {
                candidatesDependedOnNow[equation] = new boolean[candidates[equation].length];
            }
        }

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
     * An equation on a slot whose variable it does not depend on leaves a zero on the diagonal, around which a
     * factorisation has to pivot. Two kinds of equations would:
     * <ul>
     * <li>one that does not depend on the variable it is paired with: the two equations that tie the buses of a zero
     * impedance branch together, paired with the flows of the branch, or the balance of a bus that only such
     * branches connect;</li>
     * <li>one that a control sends to the slot of a variable it does not depend on: the reactive balance of a bus
     * whose voltage is held by a shunt of another bus, sent to the susceptance of that shunt.</li>
     * </ul>
     * Such an equation takes the slot of a variable it depends on instead, and the equation paired with that slot the
     * slot the first one leaves: the equation of the branch goes to the angle of a bus and the balance of that bus to
     * the flow of the branch; the balance of the controlled bus goes to the voltage of the bus of the shunt, and the
     * balance of that bus to the susceptance. An equation only moves to a variable of the kind of its own. These moves
     * are added to the candidates, so that the pattern holds them from the start. The placement then finds, in each
     * state, which ones to make.
     */
    private void addCandidatesAgainstZeroDiagonals(Map<Integer, TIntArrayList> candidateSlots, Placement<V, E> placement) {
        ChainSearch search = new ChainSearch(candidateSlots, placement);
        List<int[]> independent = new ArrayList<>();
        candidateSlots.forEach((equation, slotList) -> {
            for (int i = 0; i < slotList.size(); i++) {
                if (Arrays.binarySearch(rows[equation], slotList.getQuick(i)) < 0) {
                    independent.add(new int[] {equation, slotList.getQuick(i)});
                }
            }
        });
        independent.sort(Comparator.<int[]>comparingInt(pair -> pair[0]).thenComparingInt(pair -> pair[1]));
        for (int[] pair : independent) {
            search.addChains(pair[0], pair[1]);
        }
        for (int equation = 0; equation < equationCount; equation++) {
            if (ownSlot[equation] >= 0 && rows[equation].length > 0 && !dependsOnOwnSlot[equation]) {
                search.addChains(equation, ownSlot[equation]);
            }
        }
    }

    /**
     * The search of the moves that bring to a slot an equation which depends on its variable, when the equation that
     * should take the slot does not: this one takes the slot of a variable it depends on, the equation paired with
     * that slot the slot of a variable it depends on in turn, and so on until one depends on the variable of the first
     * slot. Most often the first equation paired with a slot is enough: a chain of one move each way, an exchange.
     */
    private final class ChainSearch {

        /** How many equations a search visits at most: it gives up on a slot that nothing close depends on. */
        private static final int MAX_VISITED_COUNT = 256;

        private final Map<Integer, TIntArrayList> candidateSlots;
        private final int[] kind = new int[variables.size()];
        private final TIntArrayList[] pairedWith;
        private final int[] visited = new int[equationCount];
        private final int[] previous = new int[equationCount];
        private final int[] slotTaken = new int[equationCount];
        private final TIntArrayList queue = new TIntArrayList();
        private int stamp;

        private ChainSearch(Map<Integer, TIntArrayList> candidateSlots, Placement<V, E> placement) {
            this.candidateSlots = candidateSlots;
            for (int slot = 0; slot < kind.length; slot++) {
                kind[slot] = placement.kind(variables.get(slot).getType());
            }
            pairedWith = new TIntArrayList[variables.size()];
            for (int equation = 0; equation < equationCount; equation++) {
                int own = ownSlot[equation];
                if (own >= 0) {
                    if (pairedWith[own] == null) {
                        pairedWith[own] = new TIntArrayList(2);
                    }
                    pairedWith[own].add(equation);
                }
            }
        }

        /** Adds to the candidates the moves of the shortest chains from the equation to the slot. */
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
            int ownKind = kind[ownSlot[equation] >= 0 ? ownSlot[equation] : target];
            for (int slot : rows[equation]) {
                if (slot == target || slot == ownSlot[equation] || kind[slot] != ownKind || pairedWith[slot] == null) {
                    continue;
                }
                for (int i = 0; i < pairedWith[slot].size(); i++) {
                    int other = pairedWith[slot].getQuick(i);
                    if (kind[target] == kind[slot] && Arrays.binarySearch(rows[other], target) >= 0) {
                        found = true;
                        addCandidate(other, target);
                        addCandidate(equation, slot);
                        for (int e = equation; previous[e] >= 0; e = previous[e]) {
                            addCandidate(previous[e], slotTaken[e]);
                        }
                    } else if (visited[other] != stamp) {
                        visited[other] = stamp;
                        previous[other] = equation;
                        slotTaken[other] = slot;
                        queue.add(other);
                    }
                }
            }
            return found;
        }

        private void addCandidate(int equation, int slot) {
            movesInChain[equation] = true;
            if (slot != ownSlot[equation]) {
                candidateSlots.computeIfAbsent(equation, e -> new TIntArrayList()).add(slot);
            }
        }
    }

    /**
     * True when the equation depends on the variable of the slot. For an equation that takes part in a chain of moves
     * this is asked of its active terms: around zero impedance branches the answer changes with the state.
     */
    boolean dependsOn(int equation, int slot) {
        if (movesInChain[equation]) {
            boolean dependsNow;
            if (slot == ownSlot[equation]) {
                dependsNow = ownSlotDependedOnNow[equation];
            } else {
                int i = Arrays.binarySearch(candidates[equation], slot);
                dependsNow = i >= 0 ? candidatesDependedOnNow[equation][i] : dependsOnNow(equation, slot);
            }
            assert dependsNow == dependsOnNow(equation, slot) : "What the equation depends on was not refreshed after a change of activity";
            return dependsNow;
        }
        return slot == ownSlot[equation] ? dependsOnOwnSlot[equation] : Arrays.binarySearch(rows[equation], slot) >= 0;
    }

    /**
     * Refreshes what an equation that takes part in a chain of moves depends on now, for its own slot and its
     * candidates: after a change of the activity of the equation or of one of its terms.
     */
    void refreshDependedOnNow(int equation) {
        if (movesInChain[equation]) {
            ownSlotDependedOnNow[equation] = ownSlot[equation] >= 0 && dependsOnNow(equation, ownSlot[equation]);
            int[] slotsOfCandidates = candidates[equation];
            for (int i = 0; i < slotsOfCandidates.length; i++) {
                candidatesDependedOnNow[equation][i] = dependsOnNow(equation, slotsOfCandidates[i]);
            }
        }
    }

    /** The same for every equation: for a new structure, or after a change the events do not locate. */
    void refreshDependedOnNow() {
        for (int equation = 0; equation < equationCount; equation++) {
            refreshDependedOnNow(equation);
        }
    }

    /** True when a term of the equation that is active now depends on the variable of the slot. */
    boolean dependsOnNow(int equation, int slot) {
        ObjectTerms<V, E> compiled = objectTerms.get(equation);
        if (compiled != null) {
            for (int t = 0; t < compiled.row.length; t++) {
                if (contains(compiled.row[t], slot) && compiled.terms.get(t).isActive()) {
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
                    if (contains(terms.row[term], slot) && terms.termArray.isTermActive(term)) {
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

    /** The object terms of an equation with the slots of their variables; null when it has none or when a variable has no slot. */
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
            compiled.destination[t] = new int[termVariables.size()];
            for (int k = 0; k < termVariables.size(); k++) {
                Integer slot = slots.get(termVariables.get(k));
                if (slot == null) {
                    return null;
                }
                compiled.row[t][k] = slot;
                compiled.destination[t][k] = rowIndices != null && destinationSlot[equation] >= 0 ? index(destinationSlot[equation], slot) : -1;
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
            TIntArrayList slot = new TIntArrayList();
            TDoubleArrayList coefficient = new TDoubleArrayList();
            TIntArrayList destination = new TIntArrayList();
            List<SingleEquationTerm<V, E>> terms = new ArrayList<>();
            List<Variable<V>> termVariables = new ArrayList<>();
            boolean[] linearOnly = new boolean[equationCount];
            for (int equation = 0; equation < equationCount; equation++) {
                start[equation] = terms.size();
                ObjectTerms<V, E> compiled = objectTerms.get(equation);
                if (compiled != null && !mixed[equation]) {
                    linearOnly[equation] = true;
                    for (byte kind : compiled.kind) {
                        linearOnly[equation] &= kind == LINEAR;
                    }
                    for (int t = 0; t < compiled.row.length; t++) {
                        if (compiled.kind[t] == LINEAR) {
                            slot.add(compiled.row[t][0]);
                            coefficient.add(coefficient(compiled.terms.get(t)));
                            destination.add(compiled.destination[t][0]);
                            terms.add(compiled.terms.get(t));
                            termVariables.add(compiled.variables.get(t).get(0));
                        }
                    }
                }
            }
            start[equationCount] = terms.size();
            linearTerms = new LinearTerms<>(start, slot, coefficient, destination, terms, termVariables, linearOnly);
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
            List<int[]> slot = new ArrayList<>();
            List<int[]> destination = new ArrayList<>();
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
                            // the arrays of the compiled term itself: its destinations follow the slot of the equation
                            slot.add(compiled.row[t]);
                            destination.add(compiled.destination[t]);
                            terms.add(compiled.terms.get(t));
                        }
                    }
                }
            }
            start[equationCount] = terms.size();
            elementTerms = new ElementTerms<>(start, termArrays, termArray, element, coefficient, localIndex, slot, destination, terms);
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
            if (!termNums.isEmpty() && ownSlot[equation] < 0) {
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
                terms.destination[term] = new int[derivatives.size()];
                for (int k = 0; k < derivatives.size(); k++) {
                    terms.localIndex[term][k] = derivatives.get(k).getLocalIndex();
                    terms.row[term][k] = slots.get(derivatives.get(k).getVariable());
                }
            }
        }
        return terms;
    }

    /**
     * Builds the pattern that holds the given placements: each equation's rows in the column of its slot, and the
     * diagonal of every slot. Then the destinations of the array terms.
     */
    void buildPattern(TIntArrayList placedEquations, TIntArrayList placedSlots) {
        int slotCount = variables.size();
        int[] start = new int[slotCount + 1];
        for (int slot = 0; slot < slotCount; slot++) {
            start[slot + 1] = 1;
        }
        for (int i = 0; i < placedEquations.size(); i++) {
            start[placedSlots.getQuick(i) + 1] += rows[placedEquations.getQuick(i)].length;
        }
        for (int slot = 0; slot < slotCount; slot++) {
            start[slot + 1] += start[slot];
        }
        int[] raw = new int[start[slotCount]];
        int[] next = Arrays.copyOf(start, slotCount);
        for (int slot = 0; slot < slotCount; slot++) {
            raw[next[slot]++] = slot;
        }
        for (int i = 0; i < placedEquations.size(); i++) {
            int slot = placedSlots.getQuick(i);
            for (int row : rows[placedEquations.getQuick(i)]) {
                raw[next[slot]++] = row;
            }
        }
        columnStart = new int[slotCount + 1];
        int size = 0;
        for (int slot = 0; slot < slotCount; slot++) {
            Arrays.sort(raw, start[slot], start[slot + 1]);
            columnStart[slot] = size;
            for (int i = start[slot]; i < start[slot + 1]; i++) {
                if (size == columnStart[slot] || raw[size - 1] != raw[i]) {
                    raw[size++] = raw[i];
                }
            }
        }
        columnStart[slotCount] = size;
        rowIndices = Arrays.copyOf(raw, size);
        diagonal = new int[slotCount];
        for (int slot = 0; slot < slotCount; slot++) {
            diagonal[slot] = index(slot, slot);
        }
        setDestinations();
    }

    /**
     * Takes the pattern of the structure this one replaces, when both have the same slots: the system was compiled
     * again, for an equation it had not seen, and its matrix can stay as long as the pattern holds what is placed.
     */
    boolean adoptPattern(FixedStructure<V, E> previous) {
        if (previous.rowIndices == null || !variables.equals(previous.variables)) {
            return false;
        }
        columnStart = previous.columnStart;
        rowIndices = previous.rowIndices;
        diagonal = previous.diagonal;
        setDestinations();
        return true;
    }

    private void setDestinations() {
        staleDestinations.resetQuick();
        linearTerms = null;
        for (int equation = 0; equation < equationCount; equation++) {
            setObjectTermDestinations(equation);
        }
        for (ArrayTerms<V, E> terms : arrayTerms) {
            for (int term = 0; term < terms.equation.length; term++) {
                setDestinations(terms, term);
            }
        }
    }

    private void setObjectTermDestinations(int equation) {
        ObjectTerms<V, E> compiled = objectTerms.get(equation);
        if (compiled != null) {
            // the table of the linear terms holds a copy of their destinations; the one of the element terms the arrays themselves
            int linear = linearTerms != null && !mixed[equation] ? linearTerms.start[equation] : -1;
            for (int t = 0; t < compiled.row.length; t++) {
                for (int k = 0; k < compiled.row[t].length; k++) {
                    compiled.destination[t][k] = destinationSlot[equation] >= 0 ? index(destinationSlot[equation], compiled.row[t][k]) : -1;
                }
                if (linear >= 0 && compiled.kind[t] == LINEAR) {
                    linearTerms.destination[linear++] = compiled.destination[t][0];
                }
            }
        }
    }

    private void setDestinations(ArrayTerms<V, E> terms, int term) {
        for (int k = 0; k < terms.row[term].length; k++) {
            terms.destination[term][k] = index(destinationSlot[terms.equation[term]], terms.row[term][k]);
        }
    }

    /**
     * The slot an equation holds now. Its destinations are for that slot, so that a fill writes each derivative
     * without looking for its entry, wherever the equation is: {@link #updateDestinations()} computes them again.
     */
    void setDestinationSlot(int equation, int slot) {
        if (destinationSlot[equation] != slot) {
            destinationSlot[equation] = slot;
            staleDestinations.add(equation);
        }
    }

    /** Computes again the destinations of the equations that changed slot. The pattern must hold their entries. */
    void updateDestinations() {
        for (int i = 0; i < staleDestinations.size(); i++) {
            int equation = staleDestinations.getQuick(i);
            setObjectTermDestinations(equation);
            if (equation >= singleEquations.size()) {
                int a = getArrayIndex(equation);
                for (ArrayTerms<V, E> terms : arrayTermsOfArray.get(a)) {
                    TIntArrayList termNums = terms.termArray.getTermNumsForEquationElementNum(equation - arrayFirstEquation[a]);
                    for (int j = 0; j < termNums.size(); j++) {
                        setDestinations(terms, termNums.getQuick(j));
                    }
                }
            }
        }
        staleDestinations.resetQuick();
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
     * element, and so the same slot. Returns that number, or -1 when there is none.
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
     * Adds the variables of the terms a single equation has now to its rows, which only grow, like the pattern.
     * Returns false when one of these variables has no slot.
     */
    boolean addRows(int equation) {
        TIntArrayList all = new TIntArrayList(rows[equation]);
        for (SingleEquationTerm<V, E> term : getSingleTerms(equation)) {
            for (Variable<V> variable : term.getVariables()) {
                Integer slot = slots.get(variable);
                if (slot == null) {
                    return false;
                }
                all.add(slot);
            }
        }
        rows[equation] = sortedDistinct(all);
        dependsOnOwnSlot[equation] = ownSlot[equation] >= 0 && Arrays.binarySearch(rows[equation], ownSlot[equation]) >= 0;
        objectTerms.set(equation, compileObjectTerms(equation));
        return true;
    }

    /** The index of the value at (row, column), or -1 when the pattern does not hold it. */
    public int index(int column, int row) {
        int index = Arrays.binarySearch(rowIndices, columnStart[column], columnStart[column + 1], row);
        return index >= 0 ? index : -1;
    }

    /** True when a column of the pattern already holds the rows of an equation. */
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

    public int getSlotCount() {
        return variables.size();
    }

    public Variable<V> getVariable(int slot) {
        return variables.get(slot);
    }

    /** The slot of a variable, or -1 when no term of the system depends on it. */
    public int getSlot(Variable<V> variable) {
        return slots.getOrDefault(variable, -1);
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

    /**
     * The column of every equation in OLF's index, as {@link #getColumnInEquationSystem(int)} gives it: the elements of
     * an equation array are consecutive equations, whose columns are copied from the array at once. The columns of the
     * single equations are read only with {@code singles}: they are left as they are otherwise.
     */
    public void getColumnsInEquationSystem(int[] columns, boolean singles) {
        if (singles) {
            for (int equation = 0; equation < singleEquations.size(); equation++) {
                columns[equation] = bound[equation] ? singleEquations.get(equation).getColumn() : -1;
            }
        }
        for (int a = 0; a < arrays.size(); a++) {
            int first = arrayFirstEquation[a];
            int length = (a + 1 < arrays.size() ? arrayFirstEquation[a + 1] : equationCount) - first;
            System.arraycopy(arrays.get(a).getElementNumToColumn(), 0, columns, first, length);
        }
    }

    /** The terms held as objects: all the terms of a single equation, the additional terms of an array element. */
    public List<SingleEquationTerm<V, E>> getSingleTerms(int equation) {
        if (equation < singleEquations.size()) {
            return bound[equation] ? singleEquations.get(equation).getTerms() : List.of();
        }
        return getArray(equation).getSingleEquationTerms(getElementNum(equation));
    }

    /** The slot the equation is paired with, or -1 when it has none of its own. */
    public int getOwnSlot(int equation) {
        return ownSlot[equation];
    }

    boolean fixesVariable(int equation) {
        return fixesVariable[equation];
    }

    /** The terms the equation holds as objects, compiled; null when it has none. */
    ObjectTerms<V, E> getObjectTerms(int equation) {
        return objectTerms.get(equation);
    }

    /**
     * The slots the equation may take besides its own: those its {@link Placement} gives, and those of the moves that
     * keep zeros off the diagonal.
     */
    int[] getCandidates(int equation) {
        return candidates[equation];
    }

    /**
     * For each slot, the equations that can hold it, in the order of their numbers: those whose own slot it is and
     * those that have it among their candidates. Built once with the structure: it is the table a mode of a slot
     * indexes ({@link FixedEquationSystem#getModes()}).
     */
    public int[] getAlternatives(int slot) {
        if (alternatives == null) {
            TIntArrayList[] lists = new TIntArrayList[getSlotCount()];
            for (int equation = 0; equation < getEquationCount(); equation++) {
                if (ownSlot[equation] >= 0) {
                    alternativesOf(lists, ownSlot[equation]).add(equation);
                }
                for (int candidate : candidates[equation]) {
                    alternativesOf(lists, candidate).add(equation);
                }
            }
            int[][] built = new int[lists.length][];
            for (int s = 0; s < lists.length; s++) {
                built[s] = lists[s] == null ? new int[0] : sortedDistinct(lists[s]);
            }
            alternatives = built;
        }
        return alternatives[slot];
    }

    private static TIntArrayList alternativesOf(TIntArrayList[] lists, int slot) {
        if (lists[slot] == null) {
            lists[slot] = new TIntArrayList(2);
        }
        return lists[slot];
    }

    /** The slots of the variables the equation depends on, sorted. */
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
