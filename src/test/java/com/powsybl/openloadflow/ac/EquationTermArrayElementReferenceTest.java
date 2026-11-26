/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AbstractShuntCompensatorEquationTerm;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.solver.AcSolverUtil;
import com.powsybl.openloadflow.equations.AbstractElementEquationTerm;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.EquationTermArrayElementReference;
import com.powsybl.openloadflow.equations.SingleEquation;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableEquationTerm;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.FourBusNetworkFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LoadFlowModel;
import com.powsybl.openloadflow.network.PhaseControlFactory;
import com.powsybl.openloadflow.network.VoltageControlNetworkFactory;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Some equations sum flows the balances of the buses already hold: the equation that shares the reactive power between
 * the generators that hold one voltage, the target of a flow a phase shifter or a generator controls, the voltage
 * target of a bus with a slope. In a system that is not vectorized each of these flows is a second term, that computes
 * it again. In a vectorized one the equation refers to the element of the term array of the balance. Both must be the
 * same equations: same terms, same values, same derivatives, same activity.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class EquationTermArrayElementReferenceTest {

    /**
     * The flows of the term arrays compute the sine and the cosine of the two angles of a branch from those of ksi and of
     * their half difference: the same values as the objects to the last bits, not bit for bit.
     */
    private static final double FLOW_TOLERANCE = 1e-12;

    /** A network as an equation system, with the flows of its balances as objects or as arrays. */
    private record System(LfNetwork network, AcLoadFlowContext context) implements AutoCloseable {

        static System create(Supplier<Network> networkSupplier, Consumer<LoadFlowParameters> configuration, boolean vectorized) {
            Network network = networkSupplier.get();
            LoadFlowParameters parameters = new LoadFlowParameters();
            OpenLoadFlowParameters.create(parameters);
            configuration.accept(parameters);
            AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters,
                            OpenLoadFlowParameters.get(parameters), new SparseMatrixFactory(),
                            new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false)
                    .setVectorized(vectorized);
            LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
            // every branch can be opened at each side: the equations also hold the flows of the branches open at one side
            for (LfBranch branch : lfNetwork.getBranches()) {
                if (branch.getBus1() != null && branch.getBus2() != null && !branch.isZeroImpedance(LoadFlowModel.AC)) {
                    branch.setDisconnectionAllowedSide1(true);
                    branch.setDisconnectionAllowedSide2(true);
                }
            }
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

        @Override
        public void close() {
            context.close();
        }
    }

    /** What the single equations of a vectorized system hold. */
    private static final class Count {
        int references;
        int flowsHeldAsObjects;
    }

    private static SingleEquationTerm<AcVariableType, AcEquationType> multiplied(SingleEquationTerm<AcVariableType, AcEquationType> term) {
        return term instanceof SingleEquationTerm.MultiplyByScalarEquationTerm<AcVariableType, AcEquationType> ? term.getChildren().get(0) : term;
    }

    private static String describe(Variable<AcVariableType> variable) {
        return variable.getType() + " " + variable.getElementNum();
    }

    /** The single equations of the vectorized system against those of the other one, term by term. */
    private static Count assertSameEquations(System objects, System arrays, String state) {
        Count count = new Count();
        for (SingleEquation<AcVariableType, AcEquationType> equation : arrays.equations().getEquations()) {
            String name = state + ", " + equation.getType() + " " + equation.getElementNum();
            Equation<AcVariableType, AcEquationType> objectEquation = objects.equations().getEquation(equation.getElementNum(), equation.getType()).orElseThrow();
            assertEquals(objectEquation.isActive(), equation.isActive(), name);
            List<SingleEquationTerm<AcVariableType, AcEquationType>> terms = equation.getTerms();
            List<? extends EquationTerm<AcVariableType, AcEquationType>> objectTerms = objectEquation.getTerms();
            assertEquals(objectTerms.size(), terms.size(), name);
            for (int t = 0; t < terms.size(); t++) {
                SingleEquationTerm<AcVariableType, AcEquationType> term = terms.get(t);
                EquationTerm<AcVariableType, AcEquationType> objectTerm = objectTerms.get(t);
                SingleEquationTerm<AcVariableType, AcEquationType> multiplied = multiplied(term);
                if (multiplied instanceof EquationTermArrayElementReference) {
                    count.references++;
                } else if (!(multiplied instanceof VariableEquationTerm)) {
                    assertInstanceOf(AbstractElementEquationTerm.class, multiplied, name);
                    count.flowsHeldAsObjects++;
                }
                assertEquals(objectTerm.isActive(), term.isActive(), name);
                assertEquals(objectTerm.getElementType(), term.getElementType(), name);
                assertEquals(objectTerm.getElementNum(), term.getElementNum(), name);
                assertEquals(objectTerm.getVariables().stream().map(EquationTermArrayElementReferenceTest::describe).toList(),
                        term.getVariables().stream().map(EquationTermArrayElementReferenceTest::describe).toList(), name);
                if (equation.isActive() && term.isActive()) {
                    assertEquals(objectTerm.eval(), term.eval(), FLOW_TOLERANCE, name);
                    for (Variable<AcVariableType> variable : objectTerm.getVariables()) {
                        Variable<AcVariableType> sameVariable = arrays.equations().getVariable(variable.getElementNum(), variable.getType());
                        assertEquals(objectTerm.der(variable), term.der(sameVariable), FLOW_TOLERANCE, name + ", " + describe(variable));
                    }
                }
            }
        }
        return count;
    }

    /**
     * Compares the two systems at two states, then with each branch lost, open at side 1 and open at side 2 in turn.
     * Returns what the equations of the vectorized system hold.
     */
    private static Count compare(Supplier<Network> network, Consumer<LoadFlowParameters> configuration) {
        try (System objects = System.create(network, configuration, false); System arrays = System.create(network, configuration, true)) {
            objects.move(1);
            arrays.move(1);
            Count count = assertSameEquations(objects, arrays, "first state");
            objects.move(-2);
            arrays.move(-2);
            assertSameEquations(objects, arrays, "second state");
            for (LfBranch branch : arrays.network().getBranches()) {
                LfBranch objectBranch = objects.network().getBranch(branch.getNum());
                change(objects, arrays, () -> objectBranch.setDisabled(true), () -> branch.setDisabled(true), branch.getId() + " lost");
                change(objects, arrays, () -> objectBranch.setDisabled(false), () -> branch.setDisabled(false), branch.getId() + " back");
                if (branch.isDisconnectionAllowedSide1()) {
                    change(objects, arrays, () -> objectBranch.setConnectedSide1(false), () -> branch.setConnectedSide1(false), branch.getId() + " open at side 1");
                    change(objects, arrays, () -> objectBranch.setConnectedSide1(true), () -> branch.setConnectedSide1(true), branch.getId() + " closed at side 1");
                    change(objects, arrays, () -> objectBranch.setConnectedSide2(false), () -> branch.setConnectedSide2(false), branch.getId() + " open at side 2");
                    change(objects, arrays, () -> objectBranch.setConnectedSide2(true), () -> branch.setConnectedSide2(true), branch.getId() + " closed at side 2");
                }
            }
            return count;
        }
    }

    /** The same change in both systems; the state is set again, as a solver does when it starts, and the systems compared. */
    private static void change(System objects, System arrays, Runnable inObjects, Runnable inArrays, String state) {
        inObjects.run();
        inArrays.run();
        objects.move(0);
        arrays.move(0);
        assertSameEquations(objects, arrays, state);
    }

    private static Network remoteControlWithShunts(boolean controllerShunt) {
        Network network = VoltageControlNetworkFactory.createWithGeneratorRemoteControl();
        // a shunt at the bus of the first generator, and one that can hold a voltage at the bus of the second one
        network.getVoltageLevel("vl1").newShuntCompensator()
                .setId("SHUNT_1")
                .setBus("b1")
                .setSectionCount(1)
                .newLinearModel()
                    .setBPerSection(2e-3)
                    .setMaximumSectionCount(1)
                    .add()
                .add();
        if (controllerShunt) {
            network.getVoltageLevel("vl2").newShuntCompensator()
                    .setId("SHUNT_2")
                    .setBus("b2")
                    .setSectionCount(1)
                    .setLocalTargetV(21)
                    .newVoltageRegulation()
                        .withRegulating(true)
                        .withTargetDeadband(1)
                        .withMode(RegulationMode.VOLTAGE)
                        .add()
                    .newLinearModel()
                        .setBPerSection(1e-3)
                        .setMaximumSectionCount(3)
                        .add()
                    .add();
        }
        return network;
    }

    @Test
    void reactivePowerDistribution() {
        Count count = compare(() -> remoteControlWithShunts(false), parameters -> { });
        // for each of the three generators: the flow of each of the three transformers, closed and open at the other
        // side, and the flow of the shunt of the first generator
        assertEquals(3 * (3 * 2 + 1), count.references);
        assertEquals(0, count.flowsHeldAsObjects);
    }

    /**
     * The term the distribution equation holds for a shunt does not take its susceptance as a variable. The flow of the
     * balance does when the shunt can hold a voltage: the equation then keeps a term of its own.
     */
    @Test
    void reactivePowerDistributionWithAShuntThatHoldsAVoltage() {
        Count count = compare(() -> remoteControlWithShunts(true), parameters -> parameters.setShuntCompensatorVoltageControlOn(true));
        assertEquals(3 * (3 * 2 + 1), count.references);
        assertEquals(3, count.flowsHeldAsObjects);
        try (System arrays = System.create(() -> remoteControlWithShunts(true), parameters -> parameters.setShuntCompensatorVoltageControlOn(true), true)) {
            long shuntTerms = arrays.equations().getEquations().stream()
                    .filter(equation -> equation.getType() == AcEquationType.DISTR_Q)
                    .flatMap(equation -> equation.getTerms().stream())
                    .filter(term -> multiplied(term) instanceof AbstractShuntCompensatorEquationTerm)
                    .count();
            assertEquals(3, shuntTerms);
        }
    }

    @Test
    void reactivePowerDistributionAroundZeroImpedanceLines() {
        Count count = compare(VoltageControlNetworkFactory::createWithGeneratorRemoteControlAndSmallSeparatingImpedance, parameters -> { });
        assertTrue(count.references > 0);
        assertEquals(0, count.flowsHeldAsObjects);
    }

    @Test
    void flowAPhaseShifterControls() {
        Count count = compare(() -> {
            Network network = PhaseControlFactory.createNetworkWithT2wt();
            TwoWindingsTransformer transformer = network.getTwoWindingsTransformer("PS1");
            transformer.getPhaseTapChanger()
                    .setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                    .setTargetDeadband(1)
                    .setRegulating(true)
                    .setTapPosition(1)
                    .setRegulationTerminal(transformer.getTerminal1())
                    .setRegulationValue(83);
            return network;
        }, parameters -> parameters.setPhaseShifterRegulationOn(true));
        assertEquals(1, count.references);
        assertEquals(0, count.flowsHeldAsObjects);
    }

    @Test
    void flowAGeneratorControls() {
        Count count = compare(() -> {
            Network network = FourBusNetworkFactory.createBaseNetwork();
            network.getGenerator("g4").setLocalTargetQ(0).newVoltageRegulation()
                    .withMode(RegulationMode.REACTIVE_POWER)
                    .withTargetValue(1)
                    .withTerminal(network.getLine("l34").getTerminal(TwoSides.TWO))
                    .build();
            return network;
        }, parameters -> OpenLoadFlowParameters.get(parameters).setGeneratorReactivePowerRemoteControl(true));
        assertTrue(count.references > 0);
    }

    @Test
    void voltageTargetWithASlope() {
        Count count = compare(() -> {
            Network network = VoltageControlNetworkFactory.createWithStaticVarCompensator();
            StaticVarCompensator svc = network.getStaticVarCompensator("svc1");
            svc.setLocalTargetV(385);
            svc.newVoltageRegulation()
                    .withMode(RegulationMode.VOLTAGE_PER_REACTIVE_POWER)
                    .withSlope(0.03)
                    .withRegulating(true)
                    .build();
            return network;
        }, parameters -> OpenLoadFlowParameters.get(parameters).setVoltagePerReactivePowerControl(true));
        assertTrue(count.references > 0);
        assertEquals(0, count.flowsHeldAsObjects);
    }
}
