/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AbstractLoadModelEquationTerm;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.LoadModelActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.LoadModelReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.fastdecoupled.LoadModelReactiveFlowFastDecoupledEquationTerm;
import com.powsybl.openloadflow.ac.equations.vector.AbstractLoadModelEquationTermArrayEvaluator;
import com.powsybl.openloadflow.ac.equations.vector.LoadModelActiveFlowEquationTermArrayEvaluator;
import com.powsybl.openloadflow.ac.equations.vector.LoadModelReactiveFlowEquationTermArrayEvaluator;
import com.powsybl.openloadflow.ac.solver.AcSolverUtil;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a vectorized equation system the power of the loads of a bus that follow a model is, in each balance of the bus,
 * one element of a term array: their sum. It must be the sum of the terms the other system holds as objects, one for
 * each load: same value, same derivative, same activity. And each load must read its own power as its term gives it.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class LoadModelEquationTermArrayTest {

    /** A network as an equation system, with its load model terms as objects or as arrays. */
    private record System(LfNetwork network, AcLoadFlowContext context) implements AutoCloseable {

        /**
         * At one bus a load that does not follow the voltage, one that follows a polynomial of it and one that follows
         * a power of it; at another bus a load that follows a polynomial.
         */
        static System create(boolean vectorized) {
            Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
            VoltageLevel loadVoltageLevel = network.getVoltageLevel("VLLOAD");
            loadVoltageLevel.newLoad()
                    .setId("ZIPLOAD")
                    .setBus("NLOAD")
                    .setP0(50)
                    .setQ0(30)
                    .newZipModel()
                        .setC0p(0.5)
                        .setC0q(0.55)
                        .setC1p(0.3)
                        .setC1q(0.35)
                        .setC2p(0.2)
                        .setC2q(0.1)
                        .add()
                    .add();
            loadVoltageLevel.newLoad()
                    .setId("EXPLOAD")
                    .setBus("NLOAD")
                    .setP0(40)
                    .setQ0(20)
                    .newExponentialModel()
                        .setNp(0.8)
                        .setNq(0.9)
                        .add()
                    .add();
            network.getVoltageLevel("VLHV2").newLoad()
                    .setId("ZIPLOAD2")
                    .setBus("NHV2")
                    .setP0(10)
                    .setQ0(5)
                    .newZipModel()
                        .setC0p(0.1)
                        .setC0q(0.2)
                        .setC1p(0.4)
                        .setC1q(0.3)
                        .setC2p(0.5)
                        .setC2q(0.5)
                        .add()
                    .add();
            LoadFlowParameters parameters = new LoadFlowParameters();
            AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters,
                            OpenLoadFlowParameters.create(parameters).setUseLoadModel(true), new SparseMatrixFactory(),
                            new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false)
                    .setVectorized(vectorized);
            LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
            System system = new System(lfNetwork, new AcLoadFlowContext(lfNetwork, acParameters));
            AcSolverUtil.initStateVector(lfNetwork, system.equations(), new UniformValueVoltageInitializer());
            return system;
        }

        EquationSystem<AcVariableType, AcEquationType> equations() {
            return context.getEquationSystem();
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

        /** The terms a system that is not vectorized holds for the loads of a bus, one for each load. */
        <T extends AbstractLoadModelEquationTerm> List<T> objectTerms(LfBus bus, Class<T> termClass) {
            return equations().getEquationTerms(ElementType.BUS, bus.getNum()).stream()
                    .filter(termClass::isInstance)
                    .map(termClass::cast)
                    .toList();
        }

        /** The element a vectorized system holds for the loads of a bus in one of its balances: null when it has none. */
        EquationTerm<AcVariableType, AcEquationType> arrayTerm(LfBus bus, AcEquationType balance, Class<? extends AbstractLoadModelEquationTermArrayEvaluator> evaluatorClass) {
            List<EquationTerm<AcVariableType, AcEquationType>> terms = equations().getEquation(bus.getNum(), balance).orElseThrow().getTerms();
            List<EquationTerm<AcVariableType, AcEquationType>> found = terms.stream()
                    .filter(term -> term instanceof EquationTermArray.EquationTermArrayElementImpl<AcVariableType, AcEquationType> element
                            && evaluatorClass.isInstance(element.getEvaluator()))
                    .toList();
            assertTrue(found.size() <= 1);
            return found.isEmpty() ? null : found.get(0);
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private static void assertSameAsTheSumOfTheObjectTerms(List<? extends AbstractLoadModelEquationTerm> objectTerms, System arrays,
                                                         EquationTerm<AcVariableType, AcEquationType> arrayTerm, LfBus bus) {
        if (objectTerms.isEmpty()) {
            assertSame(null, arrayTerm);
            return;
        }
        Variable<AcVariableType> v = arrays.equations().getVariable(bus.getNum(), AcVariableType.BUS_V);
        assertEquals(List.of(v), arrayTerm.getVariables());
        double value = objectTerms.get(0).eval();
        double derivative = objectTerms.get(0).der(objectTerms.get(0).getVariables().get(0));
        for (AbstractLoadModelEquationTerm objectTerm : objectTerms.subList(1, objectTerms.size())) {
            value += objectTerm.eval();
            derivative += objectTerm.der(objectTerm.getVariables().get(0));
        }
        for (AbstractLoadModelEquationTerm objectTerm : objectTerms) {
            assertEquals(objectTerm.isActive(), arrayTerm.isActive());
        }
        assertEquals(value, arrayTerm.eval());
        assertEquals(derivative, arrayTerm.der(v));
        assertNotEquals(0, arrayTerm.eval());
        assertNotEquals(0, arrayTerm.der(v));
    }

    private static void assertSameAsTheObjectTerms(System objects, System arrays) {
        int loadCount = 0;
        int busCount = 0;
        for (LfBus bus : objects.network().getBuses()) {
            LfBus sameBus = arrays.network().getBus(bus.getNum());
            List<LoadModelActiveFlowEquationTerm> pTerms = objects.objectTerms(bus, LoadModelActiveFlowEquationTerm.class);
            List<LoadModelReactiveFlowEquationTerm> qTerms = objects.objectTerms(bus, LoadModelReactiveFlowEquationTerm.class);
            assertTrue(arrays.objectTerms(sameBus, AbstractLoadModelEquationTerm.class).isEmpty());
            assertSameAsTheSumOfTheObjectTerms(pTerms, arrays,
                    arrays.arrayTerm(sameBus, AcEquationType.BUS_TARGET_P, LoadModelActiveFlowEquationTermArrayEvaluator.class), sameBus);
            assertSameAsTheSumOfTheObjectTerms(qTerms, arrays,
                    arrays.arrayTerm(sameBus, AcEquationType.BUS_TARGET_Q, LoadModelReactiveFlowEquationTermArrayEvaluator.class), sameBus);
            busCount += pTerms.isEmpty() ? 0 : 1;
            // each load reads its own power
            for (int i = 0; i < bus.getLoads().size(); i++) {
                LfLoad load = bus.getLoads().get(i);
                LfLoad sameLoad = sameBus.getLoads().get(i);
                assertEquals(load.getLoadModel().isPresent(), sameLoad.getLoadModel().isPresent());
                if (load.getLoadModel().isPresent()) {
                    assertEquals(load.getP().eval(), sameLoad.getP().eval());
                    assertEquals(load.getQ().eval(), sameLoad.getQ().eval());
                    assertNotEquals(0, sameLoad.getP().eval());
                    loadCount++;
                }
            }
            assertEquals(bus.getP().eval(), sameBus.getP().eval(), 1e-14);
            assertEquals(bus.getQ().eval(), sameBus.getQ().eval(), 1e-14);
        }
        assertEquals(3, loadCount);
        assertEquals(2, busCount);
    }

    @Test
    void sameAsTheObjectTerms() {
        try (System objects = System.create(false); System arrays = System.create(true)) {
            assertSameAsTheObjectTerms(objects, arrays);
            objects.move(1);
            arrays.move(1);
            assertSameAsTheObjectTerms(objects, arrays);
            objects.move(-2);
            arrays.move(-2);
            assertSameAsTheObjectTerms(objects, arrays);

            // the target of a load changes, as when the loads take their share of the slack
            LfBus bus = objects.network().getBusById("VLLOAD_0");
            LfBus sameBus = arrays.network().getBus(bus.getNum());
            for (int i = 0; i < bus.getLoads().size(); i++) {
                LfLoad load = bus.getLoads().get(i);
                if (load.getLoadModel().isPresent()) {
                    double p = sameBus.getLoads().get(i).getP().eval();
                    load.setTargetP(load.getTargetP() * 1.1);
                    sameBus.getLoads().get(i).setTargetP(sameBus.getLoads().get(i).getTargetP() * 1.1);
                    arrays.refresh();
                    assertEquals(p * 1.1, sameBus.getLoads().get(i).getP().eval(), 1e-14);
                }
            }
            objects.refresh();
            assertSameAsTheObjectTerms(objects, arrays);

            // the bus is lost: its terms are off in both systems
            bus.setDisabled(true);
            sameBus.setDisabled(true);
            EquationTerm<AcVariableType, AcEquationType> arrayTerm = arrays.arrayTerm(sameBus, AcEquationType.BUS_TARGET_P, LoadModelActiveFlowEquationTermArrayEvaluator.class);
            assertFalse(arrayTerm.isActive());
            assertFalse(objects.objectTerms(bus, LoadModelActiveFlowEquationTerm.class).get(0).isActive());
            bus.setDisabled(false);
            sameBus.setDisabled(false);
            assertTrue(arrayTerm.isActive());
            objects.refresh();
            arrays.refresh();
            assertSameAsTheObjectTerms(objects, arrays);
        }
    }

    /** The fast decoupled solver has its own derivative of the reactive power to the voltage: the same from an array. */
    @Test
    void sameFastDecoupledDerivativeAsTheObjectTerms() {
        try (System objects = System.create(false); System arrays = System.create(true)) {
            objects.move(1);
            arrays.move(1);
            int busCount = 0;
            for (LfBus bus : objects.network().getBuses()) {
                List<LoadModelReactiveFlowEquationTerm> qTerms = objects.objectTerms(bus, LoadModelReactiveFlowEquationTerm.class);
                if (!qTerms.isEmpty()) {
                    LfBus sameBus = arrays.network().getBus(bus.getNum());
                    var element = assertInstanceOf(EquationTermArray.EquationTermArrayElementImpl.class,
                            arrays.arrayTerm(sameBus, AcEquationType.BUS_TARGET_Q, LoadModelReactiveFlowEquationTermArrayEvaluator.class));
                    var evaluator = assertInstanceOf(LoadModelReactiveFlowEquationTermArrayEvaluator.class, element.getEvaluator());
                    assertEquals(qTerms.size(), evaluator.getLoads(bus.getNum()).size());
                    Variable<AcVariableType> v = qTerms.get(0).getVariables().get(0);
                    double derivative = new LoadModelReactiveFlowFastDecoupledEquationTerm(qTerms.get(0)).derFastDecoupled(v);
                    for (LoadModelReactiveFlowEquationTerm qTerm : qTerms.subList(1, qTerms.size())) {
                        derivative += new LoadModelReactiveFlowFastDecoupledEquationTerm(qTerm).derFastDecoupled(v);
                    }
                    assertNotEquals(0, derivative);
                    assertEquals(derivative, new LoadModelReactiveFlowFastDecoupledEquationTerm(evaluator, bus.getNum()).derFastDecoupled(evaluator.getVVar(bus.getNum())));
                    busCount++;
                }
            }
            assertEquals(2, busCount);
        }
    }
}
