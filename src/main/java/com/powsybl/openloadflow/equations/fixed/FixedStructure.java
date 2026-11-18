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
import com.powsybl.openloadflow.equations.EquationArray;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.equations.SingleEquation;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
 *     slots of the variables it depends on, and its own slot: the one its {@link Placement} pairs it with.</li>
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

    private final List<Variable<V>> variables;
    private final Map<Variable<V>, Integer> slots = new HashMap<>();

    private final List<SingleEquation<V, E>> singleEquations;
    private final List<EquationArray<V, E>> arrays;
    private final int[] arrayFirstEquation;
    private final int equationCount;
    private final int[][] rows;
    private final int[] ownSlot;
    private final boolean[] fixesVariable;

    private final List<ArrayTerms<V, E>> arrayTerms = new ArrayList<>();
    private final int[] arrayElementsWithSingleTerms;

    private int[] columnStart;
    private int[] rowIndices;
    private int[] diagonal;

    FixedStructure(EquationSystem<V, E> equationSystem, Placement<V, E> placement) {
        singleEquations = List.copyOf(equationSystem.getEquations());
        arrays = List.copyOf(equationSystem.getEquationArrays());
        arrayFirstEquation = new int[arrays.size()];
        int count = singleEquations.size();
        for (int a = 0; a < arrays.size(); a++) {
            arrayFirstEquation[a] = count;
            count += arrays.get(a).getElementCount();
        }
        equationCount = count;

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
            fixesVariable[equation] = placement.fixesVariable(type);
            if (equation >= singleEquations.size() && !getSingleTerms(equation).isEmpty()) {
                withSingleTerms.add(equation);
            }
        }
        arrayElementsWithSingleTerms = withSingleTerms.toArray();

        for (int a = 0; a < arrays.size(); a++) {
            for (EquationTermArray<V, E> termArray : arrays.get(a).getTermArrays()) {
                arrayTerms.add(compile(termArray, a));
            }
        }
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
        for (ArrayTerms<V, E> terms : arrayTerms) {
            for (int term = 0; term < terms.equation.length; term++) {
                for (int k = 0; k < terms.row[term].length; k++) {
                    terms.destination[term][k] = index(ownSlot[terms.equation[term]], terms.row[term][k]);
                }
            }
        }
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
        int a = arrays.size() - 1;
        while (arrayFirstEquation[a] > equation) {
            a--;
        }
        return a;
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
        return equation < singleEquations.size() ? singleEquations.get(equation).isActive()
                : getArray(equation).isElementActive(getElementNum(equation));
    }

    /** The column of the equation in OLF's own index: -1 when it is not active. */
    public int getColumnInEquationSystem(int equation) {
        return equation < singleEquations.size() ? singleEquations.get(equation).getColumn()
                : getArray(equation).getElementNumToColumn(getElementNum(equation));
    }

    /** The terms held as objects: all the terms of a single equation, the additional terms of an array element. */
    public List<SingleEquationTerm<V, E>> getSingleTerms(int equation) {
        return equation < singleEquations.size() ? singleEquations.get(equation).getTerms()
                : getArray(equation).getSingleEquationTerms(getElementNum(equation));
    }

    /** The slot the equation is paired with, or -1 when it has none of its own. */
    public int getOwnSlot(int equation) {
        return ownSlot[equation];
    }

    boolean fixesVariable(int equation) {
        return fixesVariable[equation];
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
