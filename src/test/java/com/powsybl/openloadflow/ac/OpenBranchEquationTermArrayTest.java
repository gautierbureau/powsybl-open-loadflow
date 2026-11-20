/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.LineAdder;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.OpenBranchSide1ActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.OpenBranchSide1ReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.OpenBranchSide2ActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.OpenBranchSide2ReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.fastdecoupled.OpenBranchSide1ReactiveFlowFastDecoupledEquationTerm;
import com.powsybl.openloadflow.ac.equations.fastdecoupled.OpenBranchSide2ReactiveFlowFastDecoupledEquationTerm;
import com.powsybl.openloadflow.ac.equations.vector.OpenBranchSide1ReactiveFlowEquationTermArrayEvaluator;
import com.powsybl.openloadflow.ac.equations.vector.OpenBranchSide2ReactiveFlowEquationTermArrayEvaluator;
import com.powsybl.openloadflow.ac.solver.AcSolverUtil;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.VoltageControlNetworkFactory;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.EvaluableConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a vectorized equation system the flow left at the connected side of a branch open at the other one is an element
 * of a term array. It must be the term held as an object of the other system: same value, same derivative, same
 * activity, whether the branch is open in the network or opened and closed afterwards.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class OpenBranchEquationTermArrayTest {

    private static final String LINE = "LINE_12_BIS";
    private static final String TRANSFORMER = "T2wT1";
    private static final String OPEN_AT_SIDE_1 = "OPEN_AT_SIDE_1";
    private static final String OPEN_AT_SIDE_2 = "OPEN_AT_SIDE_2";

    /** A network as an equation system, with its open branch terms as objects or as arrays. */
    private record System(LfNetwork network, AcLoadFlowContext context) implements AutoCloseable {

        /**
         * Two buses linked by two lines, the second one feeding a third bus through two transformers, and two lines
         * connected at one end only. Every branch connected at both ends can be opened at each of them.
         */
        static System create(boolean vectorized) {
            Network network = VoltageControlNetworkFactory.createNetworkWith2T2wt();
            line(network, LINE).setBus1("BUS_1").setBus2("BUS_2").add();
            line(network, OPEN_AT_SIDE_1).setConnectableBus1("BUS_1").setBus2("BUS_2").add();
            line(network, OPEN_AT_SIDE_2).setBus1("BUS_1").setConnectableBus2("BUS_2").add();
            // a transformer that can change tap
            network.getTwoWindingsTransformer(TRANSFORMER).getRatioTapChanger().getVoltageRegulation().setTargetDeadband(0).setRegulating(true);
            LoadFlowParameters parameters = new LoadFlowParameters();
            AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters,
                            OpenLoadFlowParameters.create(parameters), new SparseMatrixFactory(),
                            new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false)
                    .setVectorized(vectorized);
            LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
            for (LfBranch branch : lfNetwork.getBranches()) {
                if (branch.getBus1() != null && branch.getBus2() != null) {
                    branch.setDisconnectionAllowedSide1(true);
                    branch.setDisconnectionAllowedSide2(true);
                }
            }
            System system = new System(lfNetwork, new AcLoadFlowContext(lfNetwork, acParameters));
            AcSolverUtil.initStateVector(lfNetwork, system.equations(), new UniformValueVoltageInitializer());
            return system;
        }

        private static LineAdder line(Network network, String id) {
            return network.newLine()
                    .setId(id)
                    .setR(1.5)
                    .setX(12.0)
                    .setG1(2e-5)
                    .setB1(3e-4)
                    .setG2(1e-5)
                    .setB2(2e-4);
        }

        EquationSystem<AcVariableType, AcEquationType> equations() {
            return context.getEquationSystem();
        }

        LfBranch branch(String id) {
            return network.getBranchById(id);
        }

        /** Moves every variable away from the flat start, by the same amount in both systems, whatever its row. */
        void move(double sign) {
            double[] x = equations().getStateVector().get().clone();
            for (Variable<AcVariableType> variable : equations().getIndex().getSortedVariablesToFind()) {
                x[variable.getRow()] += sign * 0.05 * Math.sin(1.0 + variable.getElementNum() + 10 * variable.getType().ordinal());
            }
            equations().getStateVector().set(x);
        }

        /** Sets the state again, as a solver does when it starts: the arrays are computed when the state is set. */
        void refresh() {
            move(0);
        }

        @SuppressWarnings("unchecked")
        static EquationTerm<AcVariableType, AcEquationType> term(Evaluable evaluable) {
            return (EquationTerm<AcVariableType, AcEquationType>) evaluable;
        }

        @Override
        public void close() {
            context.close();
        }
    }

    /** The term of a branch for when it is open at one side: an object in one system, an array element in the other. */
    private static void assertSameTerm(Class<?> termClass, Evaluable object, System arrays, Evaluable array) {
        if (object == EvaluableConstants.NAN) {
            // no such term, as the branch cannot be opened at this side
            assertSame(EvaluableConstants.NAN, array);
            return;
        }
        assertInstanceOf(termClass, object);
        assertInstanceOf(EquationTermArray.EquationTermArrayElementImpl.class, array);
        EquationTerm<AcVariableType, AcEquationType> objectTerm = System.term(object);
        EquationTerm<AcVariableType, AcEquationType> arrayTerm = System.term(array);
        assertEquals(objectTerm.isActive(), arrayTerm.isActive());
        assertEquals(objectTerm.eval(), arrayTerm.eval());
        assertNotEquals(0, arrayTerm.eval());
        assertEquals(1, arrayTerm.getVariables().size());
        Variable<AcVariableType> variable = objectTerm.getVariables().get(0);
        Variable<AcVariableType> sameVariable = arrays.equations().getVariable(variable.getElementNum(), variable.getType());
        assertSame(sameVariable, arrayTerm.getVariables().get(0));
        assertEquals(objectTerm.der(variable), arrayTerm.der(sameVariable));
        assertNotEquals(0, arrayTerm.der(sameVariable));
    }

    private static void assertSameAsTheObjectTerms(System objects, System arrays) {
        for (LfBranch branch : objects.network().getBranches()) {
            LfBranch sameBranch = arrays.network().getBranch(branch.getNum());
            assertSameTerm(OpenBranchSide2ActiveFlowEquationTerm.class, branch.getOpenP1(), arrays, sameBranch.getOpenP1());
            assertSameTerm(OpenBranchSide2ReactiveFlowEquationTerm.class, branch.getOpenQ1(), arrays, sameBranch.getOpenQ1());
            assertSameTerm(OpenBranchSide1ActiveFlowEquationTerm.class, branch.getOpenP2(), arrays, sameBranch.getOpenP2());
            assertSameTerm(OpenBranchSide1ReactiveFlowEquationTerm.class, branch.getOpenQ2(), arrays, sameBranch.getOpenQ2());
            // the flows the branch gives: those of its closed terms, of its open ones, or none
            assertEquals(branch.getP1().eval(), sameBranch.getP1().eval(), 1e-14);
            assertEquals(branch.getQ1().eval(), sameBranch.getQ1().eval(), 1e-14);
            assertEquals(branch.getP2().eval(), sameBranch.getP2().eval(), 1e-14);
            assertEquals(branch.getQ2().eval(), sameBranch.getQ2().eval(), 1e-14);
        }
        for (LfBus bus : objects.network().getBuses()) {
            LfBus sameBus = arrays.network().getBus(bus.getNum());
            assertEquals(bus.getP().eval(), sameBus.getP().eval(), 1e-14);
            assertEquals(bus.getQ().eval(), sameBus.getQ().eval(), 1e-14);
        }
    }

    private static boolean isActive(Evaluable term) {
        return System.term(term).isActive();
    }

    @Test
    void sameAsTheObjectTermsWhenOpenInTheNetwork() {
        try (System objects = System.create(false); System arrays = System.create(true)) {
            // the line with no bus at side 1 only has a flow at side 2, and the other way round
            LfBranch openAtSide1 = arrays.branch(OPEN_AT_SIDE_1);
            assertSame(EvaluableConstants.NAN, openAtSide1.getOpenP1());
            assertSame(openAtSide1.getOpenP2(), openAtSide1.getP2());
            assertSame(openAtSide1.getOpenQ2(), openAtSide1.getQ2());
            assertTrue(isActive(openAtSide1.getOpenP2()));
            assertTrue(isActive(openAtSide1.getOpenQ2()));
            LfBranch openAtSide2 = arrays.branch(OPEN_AT_SIDE_2);
            assertSame(EvaluableConstants.NAN, openAtSide2.getOpenP2());
            assertSame(openAtSide2.getOpenP1(), openAtSide2.getP1());
            assertSame(openAtSide2.getOpenQ1(), openAtSide2.getQ1());
            assertTrue(isActive(openAtSide2.getOpenP1()));
            assertTrue(isActive(openAtSide2.getOpenQ1()));

            assertSameAsTheObjectTerms(objects, arrays);
            objects.move(1);
            arrays.move(1);
            assertSameAsTheObjectTerms(objects, arrays);

            // the line is lost
            objects.branch(OPEN_AT_SIDE_1).setDisabled(true);
            openAtSide1.setDisabled(true);
            assertFalse(isActive(openAtSide1.getOpenP2()));
            assertFalse(isActive(openAtSide1.getOpenQ2()));
            assertSameAsTheObjectTerms(objects, arrays);

            // and back
            objects.branch(OPEN_AT_SIDE_1).setDisabled(false);
            openAtSide1.setDisabled(false);
            assertTrue(isActive(openAtSide1.getOpenP2()));
            assertSameAsTheObjectTerms(objects, arrays);
        }
    }

    @Test
    void sameAsTheObjectTermsWhenOpenedAndClosed() {
        try (System objects = System.create(false); System arrays = System.create(true)) {
            LfBranch line = arrays.branch(LINE);
            LfBranch transformer = arrays.branch(TRANSFORMER);

            // closed: the terms for when it is open are there, off, and have the flow that opening it would leave
            objects.move(1);
            arrays.move(1);
            assertFalse(isActive(line.getOpenP1()));
            assertFalse(isActive(line.getOpenP2()));
            assertTrue(isActive(line.getClosedP1()));
            assertSameAsTheObjectTerms(objects, arrays);

            // the line opens at side 1: its flow at side 2 is the one of the terms that were off
            double openP2 = line.getOpenP2().eval();
            objects.branch(LINE).setConnectedSide1(false);
            line.setConnectedSide1(false);
            assertTrue(isActive(line.getOpenP2()));
            assertTrue(isActive(line.getOpenQ2()));
            assertFalse(isActive(line.getOpenP1()));
            assertFalse(isActive(line.getClosedP1()));
            assertFalse(isActive(line.getClosedP2()));
            assertSame(line.getOpenP2(), line.getP2());
            assertEquals(openP2, line.getP2().eval());
            assertSameAsTheObjectTerms(objects, arrays);
            objects.move(-2);
            arrays.move(-2);
            assertNotEquals(openP2, line.getP2().eval());
            assertSameAsTheObjectTerms(objects, arrays);

            // it closes, and a transformer opens at side 2: its flow at side 1 depends on its ratio
            objects.branch(LINE).setConnectedSide1(true);
            line.setConnectedSide1(true);
            assertFalse(isActive(line.getOpenP2()));
            assertTrue(isActive(line.getClosedP2()));
            objects.refresh();
            arrays.refresh();
            objects.branch(TRANSFORMER).setConnectedSide2(false);
            transformer.setConnectedSide2(false);
            assertTrue(isActive(transformer.getOpenP1()));
            assertTrue(isActive(transformer.getOpenQ1()));
            assertFalse(isActive(transformer.getOpenP2()));
            assertFalse(isActive(transformer.getClosedP1()));
            assertSame(transformer.getOpenQ1(), transformer.getQ1());
            assertSameAsTheObjectTerms(objects, arrays);
            objects.move(1);
            arrays.move(1);
            assertSameAsTheObjectTerms(objects, arrays);

            // the ratio is read from the transformer, as the term held as an object does
            double openQ1 = transformer.getQ1().eval();
            double r1 = transformer.getPiModel().getR1();
            objects.branch(TRANSFORMER).getPiModel().setR1(r1 * 1.1);
            transformer.getPiModel().setR1(r1 * 1.1);
            objects.refresh();
            arrays.refresh();
            assertEquals(openQ1 * 1.1 * 1.1, transformer.getQ1().eval(), 1e-14);
            assertSameAsTheObjectTerms(objects, arrays);

            // open at both sides: nothing is left
            objects.branch(TRANSFORMER).setConnectedSide1(false);
            transformer.setConnectedSide1(false);
            assertFalse(isActive(transformer.getOpenP1()));
            assertFalse(isActive(transformer.getOpenP2()));
            assertFalse(isActive(transformer.getClosedP1()));
            assertSameAsTheObjectTerms(objects, arrays);

            // closed at side 2, still open at side 1
            objects.branch(TRANSFORMER).setConnectedSide2(true);
            transformer.setConnectedSide2(true);
            assertTrue(isActive(transformer.getOpenP2()));
            assertFalse(isActive(transformer.getOpenP1()));
            assertSameAsTheObjectTerms(objects, arrays);
            objects.move(-1);
            arrays.move(-1);
            assertSameAsTheObjectTerms(objects, arrays);

            // the transformer is lost while open at one side, and comes back
            objects.branch(TRANSFORMER).setDisabled(true);
            transformer.setDisabled(true);
            assertFalse(isActive(transformer.getOpenP2()));
            assertSameAsTheObjectTerms(objects, arrays);
            objects.branch(TRANSFORMER).setDisabled(false);
            transformer.setDisabled(false);
            assertTrue(isActive(transformer.getOpenP2()));
            assertFalse(isActive(transformer.getOpenP1()));
            assertFalse(isActive(transformer.getClosedP1()));
            assertSameAsTheObjectTerms(objects, arrays);
        }
    }

    /**
     * A term held as an object keeps the admittances its transformer had when it was created. An element of the arrays
     * follows the tap of the transformer, as the closed branch terms of the arrays do: it is the term that would be
     * created after the tap change.
     */
    @Test
    void followsTheTapOfATransformer() {
        try (System arrays = System.create(true)) {
            LfBranch transformer = arrays.branch(TRANSFORMER);
            transformer.setConnectedSide2(false);
            arrays.move(1);
            double p1 = transformer.getP1().eval();
            double q1 = transformer.getQ1().eval();

            transformer.getPiModel().setTapPosition(2);
            arrays.refresh();

            assertNotEquals(p1, transformer.getP1().eval(), 1e-6);
            assertNotEquals(q1, transformer.getQ1().eval(), 1e-6);
            assertSameAsATermCreatedNow(arrays, new OpenBranchSide2ActiveFlowEquationTerm(transformer, transformer.getBus1(),
                    arrays.equations().getVariableSet()), transformer.getP1());
            assertSameAsATermCreatedNow(arrays, new OpenBranchSide2ReactiveFlowEquationTerm(transformer, transformer.getBus1(),
                    arrays.equations().getVariableSet()), transformer.getQ1());

            // and at the other side
            transformer.setConnectedSide2(true);
            transformer.setConnectedSide1(false);
            arrays.refresh();
            assertSameAsATermCreatedNow(arrays, new OpenBranchSide1ActiveFlowEquationTerm(transformer, transformer.getBus2(),
                    arrays.equations().getVariableSet()), transformer.getP2());
            assertSameAsATermCreatedNow(arrays, new OpenBranchSide1ReactiveFlowEquationTerm(transformer, transformer.getBus2(),
                    arrays.equations().getVariableSet()), transformer.getQ2());
        }
    }

    /** The fast decoupled solver has its own derivative of the reactive power to the voltage: the same from an array. */
    @Test
    void sameFastDecoupledDerivativeAsTheObjectTerms() {
        try (System objects = System.create(false); System arrays = System.create(true)) {
            objects.move(1);
            arrays.move(1);
            int count = 0;
            for (LfBranch branch : objects.network().getBranches()) {
                int num = branch.getNum();
                LfBranch sameBranch = arrays.network().getBranch(num);
                if (branch.getOpenQ2() instanceof OpenBranchSide1ReactiveFlowEquationTerm term) {
                    var evaluator = assertInstanceOf(OpenBranchSide1ReactiveFlowEquationTermArrayEvaluator.class, evaluator(sameBranch.getOpenQ2()));
                    double derivative = new OpenBranchSide1ReactiveFlowFastDecoupledEquationTerm(term).derFastDecoupled(term.getV2Var());
                    assertNotEquals(0, derivative);
                    assertEquals(derivative, new OpenBranchSide1ReactiveFlowFastDecoupledEquationTerm(evaluator, num).derFastDecoupled(evaluator.getV2Var(num)));
                    count++;
                }
                if (branch.getOpenQ1() instanceof OpenBranchSide2ReactiveFlowEquationTerm term) {
                    var evaluator = assertInstanceOf(OpenBranchSide2ReactiveFlowEquationTermArrayEvaluator.class, evaluator(sameBranch.getOpenQ1()));
                    double derivative = new OpenBranchSide2ReactiveFlowFastDecoupledEquationTerm(term).derFastDecoupled(term.getV1Var());
                    assertNotEquals(0, derivative);
                    assertEquals(derivative, new OpenBranchSide2ReactiveFlowFastDecoupledEquationTerm(evaluator, num).derFastDecoupled(evaluator.getV1Var(num)));
                    count++;
                }
            }
            // the four branches that can be opened at each side, and the two lines connected at one end only
            assertEquals(10, count);
        }
    }

    private static EquationTermArray.Evaluator<AcVariableType> evaluator(Evaluable array) {
        return assertInstanceOf(EquationTermArray.EquationTermArrayElementImpl.class, array).getEvaluator();
    }

    private static void assertSameAsATermCreatedNow(System arrays, SingleEquationTerm<AcVariableType, AcEquationType> created, Evaluable array) {
        created.setStateVector(arrays.equations().getStateVector());
        EquationTerm<AcVariableType, AcEquationType> arrayTerm = System.term(array);
        assertEquals(created.eval(), arrayTerm.eval(), 1e-14);
        Variable<AcVariableType> variable = created.getVariables().get(0);
        assertEquals(created.der(variable), arrayTerm.der(variable), 1e-14);
    }
}
