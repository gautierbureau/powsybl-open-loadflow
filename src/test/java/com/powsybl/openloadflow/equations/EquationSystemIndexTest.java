/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.openloadflow.equations.EquationSystemIndexListener.ChangeType;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.network.LfNetwork;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class EquationSystemIndexTest {

    public enum TestEquationType implements Quantity {
        X("x_", ElementType.BUS),
        Y("y_", ElementType.BUS);

        private final String symbol;

        private final ElementType elementType;

        TestEquationType(String symbol, ElementType elementType) {
            this.symbol = symbol;
            this.elementType = elementType;
        }

        @Override
        public String getSymbol() {
            return symbol;
        }

        @Override
        public ElementType getElementType() {
            return elementType;
        }
    }

    public enum TestVariableType implements Quantity {
        A("a_", ElementType.BUS),
        B("b_", ElementType.BUS),
        C("c_", ElementType.BUS);

        private final String symbol;

        private final ElementType elementType;

        TestVariableType(String symbol, ElementType elementType) {
            this.symbol = symbol;
            this.elementType = elementType;
        }

        @Override
        public String getSymbol() {
            return symbol;
        }

        @Override
        public ElementType getElementType() {
            return elementType;
        }
    }

    private final List<Pair<Quantity, ChangeType>> quantityAdded = new ArrayList<>();

    @Test
    void test() {
        LfNetwork network = Mockito.mock(LfNetwork.class);
        EquationSystem<TestVariableType, TestEquationType> equationSystem = new EquationSystem<>(TestEquationType.class, network);
        equationSystem.getIndex().addListener(new EquationSystemIndexListener<>() {
            @Override
            public void onVariableChange(Variable<TestVariableType> variable, ChangeType changeType) {
                quantityAdded.add(Pair.of(variable.getType(), changeType));
            }

            @Override
            public void onEquationChange(SingleEquation<TestVariableType, TestEquationType> equation, ChangeType changeType) {
                quantityAdded.add(Pair.of(equation.getType(), changeType));
            }

            @Override
            public void onEquationTermChange(SingleEquationTerm<TestVariableType, TestEquationType> term) {
                // nothing to do
            }

            @Override
            public void onEquationArrayChange(EquationArray<TestVariableType, TestEquationType> equationArray, ChangeType changeType) {
                // nothing to do
            }

            @Override
            public void onEquationTermArrayChange(EquationTermArray<TestVariableType, TestEquationType> equationTermArray, int termNum, ChangeType changeType) {
                // nothing to do
            }

            @Override
            public void onEquationIndexOrderChanged() {
                // nothing to do
            }
        });

        // x = a + b
        // y = a + c
        var a = equationSystem.getVariableSet().getVariable(0, TestVariableType.A);
        var b = equationSystem.getVariableSet().getVariable(0, TestVariableType.B);
        var c = equationSystem.getVariableSet().getVariable(0, TestVariableType.C);
        SingleEquationTerm<TestVariableType, TestEquationType> aTerm = a.createTerm();
        SingleEquationTerm<TestVariableType, TestEquationType> bTerm = b.createTerm();
        var x = equationSystem.createEquation(0, TestEquationType.X)
                .addTerm(aTerm)
                .addTerm(bTerm);
        SingleEquationTerm<TestVariableType, TestEquationType> aTerm2 = a.createTerm();
        SingleEquationTerm<TestVariableType, TestEquationType> cTerm = c.createTerm();
        var y = equationSystem.createEquation(0, TestEquationType.Y)
                .addTerm(aTerm2)
                .addTerm(cTerm);
        assertEquals(List.of(Pair.of(TestEquationType.X, ChangeType.ADDED),
                             Pair.of(TestVariableType.A, ChangeType.ADDED),
                             Pair.of(TestVariableType.B, ChangeType.ADDED),
                             Pair.of(TestEquationType.Y, ChangeType.ADDED),
                             Pair.of(TestVariableType.C, ChangeType.ADDED)),
                      quantityAdded);
        quantityAdded.clear();
        assertEquals(List.of(x, y), equationSystem.getIndex().getSortedSingleEquationsToSolve());
        assertEquals(List.of(a, b, c), equationSystem.getIndex().getSortedVariablesToFind());

        // deactivate y
        // x = a + b
        y.setActive(false);
        assertEquals(List.of(Pair.of(TestVariableType.C, ChangeType.REMOVED),
                             Pair.of(TestEquationType.Y, ChangeType.REMOVED)),
                     quantityAdded);
        quantityAdded.clear();
        assertEquals(List.of(x), equationSystem.getIndex().getSortedSingleEquationsToSolve());
        assertEquals(List.of(a, b), equationSystem.getIndex().getSortedVariablesToFind());

        // reactivate y
        // x = a + b
        // y = a + c
        y.setActive(true);
        assertEquals(List.of(Pair.of(TestVariableType.C, ChangeType.ADDED),
                             Pair.of(TestEquationType.Y, ChangeType.ADDED)),
                     quantityAdded);
        quantityAdded.clear();
        assertEquals(List.of(x, y), equationSystem.getIndex().getSortedSingleEquationsToSolve());
        assertEquals(List.of(a, b, c), equationSystem.getIndex().getSortedVariablesToFind());

        // deactivate c term
        // x = a + b
        // y = a
        cTerm.setActive(false);
        assertEquals(List.of(Pair.of(TestVariableType.C, ChangeType.REMOVED)),
                     quantityAdded);
        quantityAdded.clear();
        assertEquals(List.of(x, y), equationSystem.getIndex().getSortedSingleEquationsToSolve());
        assertEquals(List.of(a, b), equationSystem.getIndex().getSortedVariablesToFind());

        // reactivate c term
        // x = a + b
        // y = a + c
        cTerm.setActive(true);
        assertEquals(List.of(Pair.of(TestVariableType.C, ChangeType.ADDED)),
                     quantityAdded);
        quantityAdded.clear();
        assertEquals(List.of(x, y), equationSystem.getIndex().getSortedSingleEquationsToSolve());
        assertEquals(List.of(a, b, c), equationSystem.getIndex().getSortedVariablesToFind());

        // deactivate all a term
        // x = b
        // y = c
        aTerm.setActive(false);
        assertTrue(quantityAdded.isEmpty());
        aTerm2.setActive(false);
        assertEquals(List.of(Pair.of(TestVariableType.A, ChangeType.REMOVED)),
                     quantityAdded);
        quantityAdded.clear();
        assertEquals(List.of(x, y), equationSystem.getIndex().getSortedSingleEquationsToSolve());
        assertEquals(List.of(b, c), equationSystem.getIndex().getSortedVariablesToFind());

        // reactivate one 'a' term
        // x = a + b
        // y = c
        aTerm.setActive(true);
        assertEquals(List.of(Pair.of(TestVariableType.A, ChangeType.ADDED)),
                     quantityAdded);
        quantityAdded.clear();
        assertEquals(List.of(x, y), equationSystem.getIndex().getSortedSingleEquationsToSolve());
        assertEquals(List.of(a, b, c), equationSystem.getIndex().getSortedVariablesToFind());

        // reactovate other 'a' term
        // x = a + b
        // y = a + c
        aTerm2.setActive(true);
        assertTrue(quantityAdded.isEmpty());
        assertEquals(List.of(x, y), equationSystem.getIndex().getSortedSingleEquationsToSolve());
        assertEquals(List.of(a, b, c), equationSystem.getIndex().getSortedVariablesToFind());
    }

    @Test
    void variablesOnly() {
        LfNetwork network = Mockito.mock(LfNetwork.class);
        EquationSystem<TestVariableType, TestEquationType> equationSystem = new EquationSystem<>(TestEquationType.class, network);
        // x = a + b
        // y = a + c
        var a = equationSystem.getVariableSet().getVariable(0, TestVariableType.A);
        var b = equationSystem.getVariableSet().getVariable(0, TestVariableType.B);
        var c = equationSystem.getVariableSet().getVariable(0, TestVariableType.C);
        var x = equationSystem.createEquation(0, TestEquationType.X)
                .addTerm(a.createTerm())
                .addTerm(b.createTerm());
        var y = equationSystem.createEquation(0, TestEquationType.Y)
                .addTerm(a.createTerm())
                .addTerm(c.createTerm());
        EquationSystemIndex<TestVariableType, TestEquationType> index = equationSystem.getIndex();

        // the variables get their rows, the equations are left without a column
        assertEquals(List.of(a, b, c), index.getSortedVariablesToFindOnly());
        assertEquals(List.of(0, 1, 2), List.of(a.getRow(), b.getRow(), c.getRow()));
        assertEquals(List.of(-1, -1), List.of(x.getColumn(), y.getColumn()));

        // the next caller that needs the equations brings them up to date
        assertEquals(List.of(x, y), index.getSortedSingleEquationsToSolve());
        assertEquals(List.of(0, 1), List.of(x.getColumn(), y.getColumn()));

        // a variable leaving the index is gone from the next variables-only update
        y.setActive(false);
        assertEquals(List.of(a, b), index.getSortedVariablesToFindOnly());
        assertEquals(-1, c.getRow());
        assertEquals(List.of(x), index.getSortedSingleEquationsToSolve());
        assertEquals(List.of(a, b), index.getSortedVariablesToFind());
    }
}
