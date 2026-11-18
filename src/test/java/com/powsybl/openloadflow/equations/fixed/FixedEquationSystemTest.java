/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.math.matrix.SparseMatrix;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcPlacement;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.solver.AcSolverUtil;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.DistributedSlackNetworkFactory;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.network.HvdcNetworkFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfShunt;
import com.powsybl.openloadflow.network.LfSynchronousNetwork;
import com.powsybl.openloadflow.network.LoadFlowModel;
import com.powsybl.openloadflow.network.NodeBreakerNetworkFactory;
import com.powsybl.openloadflow.network.PhaseControlFactory;
import com.powsybl.openloadflow.network.ReactivePowerControlNetworkFactory;
import com.powsybl.openloadflow.network.ShuntNetworkFactory;
import com.powsybl.openloadflow.network.VoltageControlNetworkFactory;
import com.powsybl.openloadflow.network.ZeroImpedanceNetworkFactory;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The matrix and the equation values of a {@link FixedEquationSystem} must be OLF's own: the Jacobian of
 * {@code JacobianMatrix} and the values of {@code EquationVector}, with each active equation in the column of its slot
 * and an identity column for each slot that holds no equation. Checked on the base state of each network, where the
 * fixed system must also give OLF's Newton step, then after each step of a random walk through what OLF can activate
 * and deactivate: branches, buses, the voltage control of generators, transformers and shunts, phase control.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class FixedEquationSystemTest {

    private static final int STEPS = 60;

    /** A network, the parameters that switch its controls on, and whether its switches are kept as branches. */
    private record Case(String name, Supplier<Network> network, Consumer<LoadFlowParameters> configuration, boolean breakers) {

        Case(String name, Supplier<Network> network) {
            this(name, network, parameters -> { }, false);
        }

        Case(String name, Supplier<Network> network, Consumer<LoadFlowParameters> configuration) {
            this(name, network, configuration, false);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static Network transformerVoltageControl() {
        Network network = VoltageControlNetworkFactory.createNetworkWithT2wt();
        TwoWindingsTransformer transformer = network.getTwoWindingsTransformers().iterator().next();
        transformer.getRatioTapChanger()
                .setTapPosition(0)
                .getVoltageRegulation()
                    .setTerminal(transformer.getTerminal2(), 34.0)
                    .setTargetDeadband(0)
                    .setRegulating(true);
        return network;
    }

    private static Network phaseControl() {
        Network network = PhaseControlFactory.createNetworkWithT2wt();
        TwoWindingsTransformer transformer = network.getTwoWindingsTransformers().iterator().next();
        transformer.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(transformer.getTerminal1())
                .setRegulationValue(83);
        return network;
    }

    /** The HVDC line of the factory follows the angles of its two ends only with this extension. */
    private static Network hvdcInAcEmulation() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0f)
                .withEnabled(true)
                .add();
        return network;
    }

    private static List<Case> cases() {
        return List.of(
                new Case("ieee14", IeeeCdfNetworkFactory::create14),
                new Case("ieee57", IeeeCdfNetworkFactory::create57),
                new Case("ieee118", IeeeCdfNetworkFactory::create118),
                new Case("ieee300", IeeeCdfNetworkFactory::create300),
                new Case("eurostag", EurostagTutorialExample1Factory::create),
                new Case("distributed slack", DistributedSlackNetworkFactory::create),
                new Case("generator remote voltage control", VoltageControlNetworkFactory::createWithGeneratorRemoteControl),
                new Case("static var compensator", VoltageControlNetworkFactory::createWithStaticVarCompensator),
                new Case("transformer voltage control", FixedEquationSystemTest::transformerVoltageControl,
                        parameters -> parameters.setTransformerVoltageControlOn(true)),
                new Case("shared transformer voltage control", VoltageControlNetworkFactory::createWithTransformerSharedRemoteControl,
                        parameters -> parameters.setTransformerVoltageControlOn(true)),
                new Case("shared shunt voltage control", VoltageControlNetworkFactory::createWithShuntSharedRemoteControl,
                        parameters -> parameters.setShuntCompensatorVoltageControlOn(true)),
                new Case("shunt", ShuntNetworkFactory::create,
                        parameters -> parameters.setShuntCompensatorVoltageControlOn(true)),
                new Case("phase control", FixedEquationSystemTest::phaseControl,
                        parameters -> parameters.setPhaseShifterRegulationOn(true)),
                new Case("generator remote reactive power control", ReactivePowerControlNetworkFactory::createWithGeneratorRemoteControl,
                        parameters -> OpenLoadFlowParameters.create(parameters).setGeneratorReactivePowerRemoteControl(true)),
                new Case("hvdc in ac emulation", FixedEquationSystemTest::hvdcInAcEmulation),
                new Case("zero impedance branches", ZeroImpedanceNetworkFactory::createWithVoltageControl),
                new Case("node breaker", NodeBreakerNetworkFactory::create, parameters -> { }, true));
    }

    private static Stream<Arguments> arguments() {
        return cases().stream().flatMap(c -> Stream.of(
                Arguments.of(c, false, FixedEquationSystem.Scope.ALL),
                Arguments.of(c, true, FixedEquationSystem.Scope.ALL),
                Arguments.of(c, true, FixedEquationSystem.Scope.ACTIVE)));
    }

    private static AcLoadFlowParameters acParameters(Network network, LoadFlowParameters parameters, boolean breakers, boolean vectorized) {
        return OpenLoadFlowParameters.createAcParameters(network, parameters, OpenLoadFlowParameters.create(parameters),
                        new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), breakers, false)
                .setVectorized(vectorized);
    }

    @ParameterizedTest(name = "{0}, vectorized = {1}, scope = {2}")
    @MethodSource("arguments")
    void matchesTheEquationSystem(Case c, boolean vectorized, FixedEquationSystem.Scope scope) {
        Network network = c.network().get();
        LoadFlowParameters parameters = new LoadFlowParameters();
        c.configuration().accept(parameters);
        AcLoadFlowParameters acParameters = acParameters(network, parameters, c.breakers(), vectorized);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), new AcPlacement(lfNetwork), scope)) {
            check(lfNetwork, context, fixed, "base state");
            checkNewtonStep(context, fixed);

            List<Runnable> moves = moves(lfNetwork);
            Random random = new Random(0);
            int checked = 0;
            for (int step = 0; step < STEPS; step++) {
                Runnable move = moves.get(random.nextInt(moves.size()));
                move.run();
                var index = context.getEquationSystem().getIndex();
                if (index.getRowCount() == index.getColumnCount()) { // else a state OLF itself cannot solve
                    check(lfNetwork, context, fixed, "step " + step);
                    checked++;
                }
            }
            assertTrue(checked > STEPS / 2, "Only " + checked + " states checked");
            // compiled once, even where OLF removes equations and creates them again: on the zero impedance network
            assertEquals(1, fixed.getStructureBuildCount());
            // and with every equation and every candidate in it from the start, the pattern is built once: also when
            // a reactive balance first takes the slot of a tap, of a susceptance or of a remote generator bus
            if (scope == FixedEquationSystem.Scope.ALL) {
                assertEquals(1, fixed.getPatternBuildCount());
            }
        }
    }

    /**
     * The four buses of the Eurostag example: bus 0 holds its voltage, bus 1 is the slack. Their slots hold the voltage
     * and the angle target, on the pattern of the balance that can take their place. When bus 3 is lost, its two slots
     * hold nothing and keep their pattern.
     */
    @Test
    void writesItsStructure() throws IOException {
        Network network = EurostagTutorialExample1Factory.create();
        AcLoadFlowParameters acParameters = acParameters(network, new LoadFlowParameters(), false, true);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), AcPlacement.INSTANCE, FixedEquationSystem.Scope.ALL)) {
            String pattern01 = " | BUS_V:0 BUS_PHI:0 BUS_V:1 BUS_PHI:1\n";
            String pattern012 = " | BUS_V:0 BUS_PHI:0 BUS_V:1 BUS_PHI:1 BUS_V:2 BUS_PHI:2\n";
            String pattern123 = " | BUS_V:1 BUS_PHI:1 BUS_V:2 BUS_PHI:2 BUS_V:3 BUS_PHI:3\n";
            String pattern23 = " | BUS_V:2 BUS_PHI:2 BUS_V:3 BUS_PHI:3\n";
            String firstSlots = "BUS_V:0 <- BUS_TARGET_V:0" + pattern01
                    + "BUS_PHI:0 <- BUS_TARGET_P:0" + pattern01
                    + "BUS_V:1 <- BUS_TARGET_Q:1" + pattern012
                    + "BUS_PHI:1 <- BUS_TARGET_PHI:1" + pattern012
                    + "BUS_V:2 <- BUS_TARGET_Q:2" + pattern123
                    + "BUS_PHI:2 <- BUS_TARGET_P:2" + pattern123;
            assertEquals(firstSlots + "BUS_V:3 <- BUS_TARGET_Q:3" + pattern23 + "BUS_PHI:3 <- BUS_TARGET_P:3" + pattern23, write(fixed));

            LfBus lost = lfNetwork.getBusById("VLLOAD_0");
            lost.setDisabled(true);
            lost.getBranches().forEach(branch -> branch.setDisabled(true));
            assertEquals(firstSlots + "BUS_V:3 <- none" + pattern23 + "BUS_PHI:3 <- none" + pattern23, write(fixed));
            assertEquals(1, fixed.getPatternBuildCount());
        }
    }

    /**
     * A bus at an end of a zero impedance branch is off before the equation system is created, like the far side of an
     * open switch that is kept for an action. Its slots hold nothing, and those of the branch hold the two equations
     * that fix its flows to zero. The two equations that tie the buses together are off, and they cannot be evaluated:
     * one of their variables is not in the system. The slots they are paired with have no balance.
     */
    @Test
    void noBalanceWhereAVariableIsNotInTheSystem() {
        Network network = ZeroImpedanceNetworkFactory.createWithVoltageControl();
        AcLoadFlowParameters acParameters = acParameters(network, new LoadFlowParameters(), false, true);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        LfBranch branch = lfNetwork.getBranches().stream().filter(b -> b.isZeroImpedance(LoadFlowModel.AC)).findFirst().orElseThrow();
        LfBus off = branch.getBus1().isSlack() || branch.getBus1().isReference() ? branch.getBus2() : branch.getBus1();
        off.setDisabled(true);
        off.getBranches().forEach(b -> b.setDisabled(true));
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), AcPlacement.INSTANCE, FixedEquationSystem.Scope.ALL)) {
            check(lfNetwork, context, fixed, "bus off");
            FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
            for (AcVariableType flow : List.of(AcVariableType.DUMMY_P, AcVariableType.DUMMY_Q)) {
                int slot = structure.getSlot(context.getEquationSystem().getVariable(branch.getNum(), flow));
                assertTrue(AcPlacement.INSTANCE.fixesVariable(structure.getType(fixed.getEquationOfSlot(slot))));
                assertTrue(Double.isNaN(fixed.getBalance()[slot]));
            }
            for (AcVariableType voltage : List.of(AcVariableType.BUS_PHI, AcVariableType.BUS_V)) {
                assertEquals(-1, fixed.getEquationOfSlot(structure.getSlot(context.getEquationSystem().getVariable(off.getNum(), voltage))));
            }
        }
    }

    /**
     * The slack bus is excluded, as when a contingency takes it out: OLF picks another reference bus and creates its
     * angle equation, one the structure has not seen. The arrays are compiled again, and the pattern is kept: the new
     * equation fits in the column of its slot.
     */
    @Test
    void keepsThePatternWhenAnEquationIsCreated() {
        Network network = IeeeCdfNetworkFactory.create14();
        AcLoadFlowParameters acParameters = acParameters(network, new LoadFlowParameters(), false, true);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), new AcPlacement(lfNetwork), FixedEquationSystem.Scope.ALL)) {
            check(lfNetwork, context, fixed, "base state");
            int[] rowIndices = fixed.getStructure().getRowIndices();

            LfSynchronousNetwork synchronousNetwork = lfNetwork.getSynchronousNetworks().get(0);
            LfBus reference = synchronousNetwork.getReferenceBus();
            synchronousNetwork.setExcludedSlackBuses(Set.of(reference));
            assertNotSame(reference, synchronousNetwork.getReferenceBus());
            check(lfNetwork, context, fixed, "another reference bus");
            assertEquals(2, fixed.getStructureBuildCount());
            assertEquals(1, fixed.getPatternBuildCount());
            assertSame(rowIndices, fixed.getStructure().getRowIndices());
        }
    }

    /**
     * Buses 1, 2 and 3 are tied by two zero impedance lines, 1 and 3. Bus 1 holds its voltage and is the angle
     * reference; buses 1 and 3 have other lines, bus 2 has none. OLF pairs the two equations of a zero impedance line
     * with its flows, which they do not depend on, and the balances of bus 2 with its voltage, which they do not depend
     * on either. Here the equations of a line take the voltage slots of the bus at its end away from the reference, and
     * the balances of that bus the flow slots of the line. When a line is lost, the bus that is left with nothing but a
     * zero impedance line is the one that exchanges its slots. No slot ever holds an equation that does not depend on
     * its variable, and the pattern does not change.
     */
    @Test
    void theEquationsOfAZeroImpedanceLineExchangeTheirSlotsWithTheBalancesOfABus() {
        Network network = ZeroImpedanceNetworkFactory.createWithVoltageControl();
        AcLoadFlowParameters acParameters = acParameters(network, new LoadFlowParameters(), false, true);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), new AcPlacement(lfNetwork), FixedEquationSystem.Scope.ALL)) {
            check(lfNetwork, context, fixed, "base state");
            assertEquals("BUS_TARGET_PHI:1", equationOn(context, fixed, AcVariableType.BUS_PHI, 1));
            assertEquals("ZERO_PHI:1", equationOn(context, fixed, AcVariableType.BUS_PHI, 2));
            assertEquals("BUS_TARGET_P:2", equationOn(context, fixed, AcVariableType.DUMMY_P, 1));
            assertEquals("ZERO_PHI:3", equationOn(context, fixed, AcVariableType.BUS_PHI, 3));
            assertEquals("BUS_TARGET_P:3", equationOn(context, fixed, AcVariableType.DUMMY_P, 3));
            assertEquals("BUS_TARGET_V:1", equationOn(context, fixed, AcVariableType.BUS_V, 1));
            assertEquals("ZERO_V:1", equationOn(context, fixed, AcVariableType.BUS_V, 2));
            assertEquals("BUS_TARGET_Q:2", equationOn(context, fixed, AcVariableType.DUMMY_Q, 1));
            assertEquals("ZERO_V:3", equationOn(context, fixed, AcVariableType.BUS_V, 3));
            assertEquals("BUS_TARGET_Q:3", equationOn(context, fixed, AcVariableType.DUMMY_Q, 3));
            assertEquals(0, fixed.getZeroDiagonalCount());

            // line 1 is lost: its flows are fixed to zero, and bus 2 hangs from bus 3 by line 3
            lfNetwork.getBranchById("l12").setDisabled(true);
            check(lfNetwork, context, fixed, "line 1 lost");
            assertEquals("DUMMY_TARGET_P:1", equationOn(context, fixed, AcVariableType.DUMMY_P, 1));
            assertEquals("ZERO_PHI:3", equationOn(context, fixed, AcVariableType.BUS_PHI, 2));
            assertEquals("BUS_TARGET_P:2", equationOn(context, fixed, AcVariableType.DUMMY_P, 3));
            assertEquals("BUS_TARGET_P:3", equationOn(context, fixed, AcVariableType.BUS_PHI, 3));
            assertEquals("DUMMY_TARGET_Q:1", equationOn(context, fixed, AcVariableType.DUMMY_Q, 1));
            assertEquals("ZERO_V:3", equationOn(context, fixed, AcVariableType.BUS_V, 2));
            assertEquals("BUS_TARGET_Q:2", equationOn(context, fixed, AcVariableType.DUMMY_Q, 3));
            assertEquals("BUS_TARGET_Q:3", equationOn(context, fixed, AcVariableType.BUS_V, 3));
            assertEquals(0, fixed.getZeroDiagonalCount());
            assertEquals(1, fixed.getPatternBuildCount());
        }
    }

    /**
     * The shunts of buses 1 and 2 hold the voltage of bus 3, a bus they are not connected to: the reactive balance of
     * bus 3 does not depend on their susceptances. So it does not take the slot of the susceptance of a shunt, as it
     * does for a shunt of its own bus: it takes the voltage slot of the bus of the shunt, and the reactive balance of
     * that bus takes the slot of the susceptance.
     */
    @Test
    void aBalanceReachesARemoteShuntThroughTheBalanceOfItsBus() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();
        AcLoadFlowParameters acParameters = acParameters(network, new LoadFlowParameters().setShuntCompensatorVoltageControlOn(true), false, true);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        LfShunt shunt0 = lfNetwork.getBus(1).getControllerShunt().orElseThrow();
        LfShunt shunt1 = lfNetwork.getBus(2).getControllerShunt().orElseThrow();
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), new AcPlacement(lfNetwork), FixedEquationSystem.Scope.ALL)) {
            shunt0.setVoltageControlEnabled(true);
            shunt1.setVoltageControlEnabled(true);
            check(lfNetwork, context, fixed, "both shunts hold the voltage");
            assertEquals("BUS_TARGET_V:3", equationOn(context, fixed, AcVariableType.BUS_V, 3));
            assertEquals("BUS_TARGET_Q:3", equationOn(context, fixed, AcVariableType.BUS_V, 1));
            assertEquals("BUS_TARGET_Q:1", equationOn(context, fixed, AcVariableType.SHUNT_B, shunt0.getNum()));
            assertEquals("DISTR_SHUNT_B:" + shunt1.getNum(), equationOn(context, fixed, AcVariableType.SHUNT_B, shunt1.getNum()));
            assertEquals(0, fixed.getZeroDiagonalCount());

            shunt0.setVoltageControlEnabled(false);
            check(lfNetwork, context, fixed, "the second shunt holds the voltage");
            assertEquals("SHUNT_TARGET_B:" + shunt0.getNum(), equationOn(context, fixed, AcVariableType.SHUNT_B, shunt0.getNum()));
            assertEquals("BUS_TARGET_Q:1", equationOn(context, fixed, AcVariableType.BUS_V, 1));
            assertEquals("BUS_TARGET_Q:3", equationOn(context, fixed, AcVariableType.BUS_V, 2));
            assertEquals("BUS_TARGET_Q:2", equationOn(context, fixed, AcVariableType.SHUNT_B, shunt1.getNum()));
            assertEquals(0, fixed.getZeroDiagonalCount());
            assertEquals(1, fixed.getPatternBuildCount());
        }
    }

    /**
     * OLF holds its single equations in a hash map whose order changes from one run to the next. The structure numbers
     * them by type and element: where several placements are possible the choice follows the numbers, and two runs
     * must make the same one.
     */
    @Test
    void numbersTheSingleEquationsByTypeAndElement() {
        Network network = ZeroImpedanceNetworkFactory.createWithVoltageControl();
        AcLoadFlowParameters acParameters = acParameters(network, new LoadFlowParameters(), false, true);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), new AcPlacement(lfNetwork), FixedEquationSystem.Scope.ALL)) {
            FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
            assertTrue(structure.getSingleEquationCount() > 10);
            for (int equation = 1; equation < structure.getSingleEquationCount(); equation++) {
                int types = structure.getType(equation - 1).compareTo(structure.getType(equation));
                assertTrue(types < 0 || types == 0 && structure.getElementNum(equation - 1) < structure.getElementNum(equation));
            }
        }
    }

    /**
     * Around a zero impedance line every term held as an object is a coefficient times one variable: the two angles in
     * the equation of the line, its flow in the balances of its two buses. The structure holds them in one table, with
     * their slot and their coefficient, and evaluates them from it. In a system that is not vectorized, the equation
     * that shares the reactive power between the generators that hold one voltage holds terms that are not of this
     * kind, the reactive flows of the branches of these generators: its object terms are evaluated one by one.
     */
    @Test
    void linearTermsAreHeldInATable() {
        Network network = ZeroImpedanceNetworkFactory.createWithVoltageControl();
        AcLoadFlowParameters acParameters = acParameters(network, new LoadFlowParameters(), false, true);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), new AcPlacement(lfNetwork), FixedEquationSystem.Scope.ALL)) {
            EquationSystem<AcVariableType, AcEquationType> equationSystem = context.getEquationSystem();
            FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
            FixedStructure.LinearTerms<AcVariableType, AcEquationType> linearTerms = structure.getLinearTerms();
            // the equation of line 1: the angle of bus 1 minus the angle of bus 2
            int zeroPhi = structure.getNumber(AcEquationType.ZERO_PHI, 1);
            assertFalse(structure.isMixed(zeroPhi));
            int first = linearTerms.start[zeroPhi];
            assertEquals(2, linearTerms.start[zeroPhi + 1] - first);
            assertEquals(1, linearTerms.coefficient[first]);
            assertEquals(-1, linearTerms.coefficient[first + 1]);
            assertEquals(structure.getSlot(equationSystem.getVariable(1, AcVariableType.BUS_PHI)), linearTerms.slot[first]);
            assertEquals(structure.getSlot(equationSystem.getVariable(2, AcVariableType.BUS_PHI)), linearTerms.slot[first + 1]);
            // the active balance of bus 2: it receives the flow of line 1 and gives the flow of line 3
            int balance = structure.getNumber(AcEquationType.BUS_TARGET_P, 2);
            int flow1 = structure.getSlot(equationSystem.getVariable(1, AcVariableType.DUMMY_P));
            int flow3 = structure.getSlot(equationSystem.getVariable(3, AcVariableType.DUMMY_P));
            assertFalse(structure.isMixed(balance));
            assertEquals(Map.of(flow1, -1d, flow3, 1d), coefficients(linearTerms, balance, true));

            // the voltage target of bus 5: its voltage
            int target = structure.getNumber(AcEquationType.BUS_TARGET_V, 5);
            int voltage = structure.getSlot(equationSystem.getVariable(5, AcVariableType.BUS_V));
            assertEquals(Map.of(voltage, 1d), coefficients(linearTerms, target, true));

            // bus 5 is lost: OLF turns its terms off, and the table follows when the equations are placed again
            LfBus lost = lfNetwork.getBus(5);
            lost.setDisabled(true);
            lost.getBranches().forEach(branch -> branch.setDisabled(true));
            check(lfNetwork, context, fixed, "bus 5 lost");
            assertEquals(Map.of(voltage, 1d), coefficients(fixed.getStructure().getLinearTerms(), target, false));
            assertEquals(Map.of(flow1, -1d, flow3, 1d), coefficients(fixed.getStructure().getLinearTerms(), balance, true));
        }

        Network remoteControlNetwork = VoltageControlNetworkFactory.createWithGeneratorRemoteControl();
        AcLoadFlowParameters remoteControlParameters = acParameters(remoteControlNetwork, new LoadFlowParameters(), false, false);
        LfNetwork remoteControlLfNetwork = Networks.load(remoteControlNetwork, remoteControlParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(remoteControlLfNetwork, remoteControlParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), new AcPlacement(remoteControlLfNetwork), FixedEquationSystem.Scope.ALL)) {
            FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
            int distribution = structure.getNumber(AcEquationType.DISTR_Q, remoteControlLfNetwork.getGeneratorById("g1").getBus().getNum());
            assertTrue(distribution >= 0);
            assertTrue(structure.isMixed(distribution));
            assertTrue(coefficients(structure.getLinearTerms(), distribution, true).isEmpty());
            assertEquals(0, structure.getElementTerms().terms.size());
        }
    }

    /**
     * The equation that shares the reactive power between the generators that hold one voltage sums the reactive flows
     * of the branches of these generators, each one times the share of the generator of the equation. In a vectorized
     * system these flows are elements of the term arrays of the reactive balances, and the equation refers to them. The
     * structure holds these terms in one table: for each one its array, its element and where its derivatives go, and
     * evaluates them from the arrays.
     */
    @Test
    void termsThatReferToATermArrayAreHeldInATable() {
        Network network = VoltageControlNetworkFactory.createWithGeneratorRemoteControl();
        AcLoadFlowParameters acParameters = acParameters(network, new LoadFlowParameters(), false, true);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), new AcPlacement(lfNetwork), FixedEquationSystem.Scope.ALL)) {
            EquationSystem<AcVariableType, AcEquationType> equationSystem = context.getEquationSystem();
            FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
            FixedStructure.ElementTerms<AcVariableType, AcEquationType> elementTerms = structure.getElementTerms();
            // the three generators are behind three transformers: each equation holds the flow of each transformer
            assertEquals(9, elementTerms.terms.size());
            LfBranch transformer = lfNetwork.getBranchById("tr2");
            LfBus generatorBus = lfNetwork.getGeneratorById("g2").getBus();
            LfBus controlledBus = transformer.getBus1() == generatorBus ? transformer.getBus2() : transformer.getBus1();
            LfBus thirdGeneratorBus = lfNetwork.getGeneratorById("g3").getBus();
            int transformerTerm = -1;
            for (String generatorId : List.of("g1", "g2", "g3")) {
                LfBus bus = lfNetwork.getGeneratorById(generatorId).getBus();
                int distribution = structure.getNumber(AcEquationType.DISTR_Q, bus.getNum());
                assertFalse(structure.isMixed(distribution));
                assertEquals(0, structure.getLinearTerms().start[distribution + 1] - structure.getLinearTerms().start[distribution]);
                assertEquals(3, elementTerms.start[distribution + 1] - elementTerms.start[distribution]);
                for (int i = elementTerms.start[distribution]; i < elementTerms.start[distribution + 1]; i++) {
                    assertTrue(elementTerms.active[i]);
                    // the share of a generator is asked of the bus at each evaluation
                    assertTrue(Double.isNaN(elementTerms.coefficient[i]));
                    // the flow depends on the voltage and on the angle of the two buses of the transformer
                    assertEquals(4, elementTerms.slot[i].length);
                    assertEquals(ElementType.BRANCH, elementTerms.termArrays.get(elementTerms.termArray[i]).getElementType());
                    if (bus == thirdGeneratorBus && elementTerms.element[i] == transformer.getNum()) {
                        transformerTerm = i;
                    }
                }
            }
            // the flow of the second transformer in the equation of the third generator: at the side of its generator
            assertTrue(transformerTerm >= 0);
            assertEquals(Set.of(structure.getSlot(equationSystem.getVariable(generatorBus.getNum(), AcVariableType.BUS_V)),
                                structure.getSlot(equationSystem.getVariable(generatorBus.getNum(), AcVariableType.BUS_PHI)),
                                structure.getSlot(equationSystem.getVariable(controlledBus.getNum(), AcVariableType.BUS_V)),
                                structure.getSlot(equationSystem.getVariable(controlledBus.getNum(), AcVariableType.BUS_PHI))),
                    Arrays.stream(elementTerms.slot[transformerTerm]).boxed().collect(Collectors.toSet()));
            check(lfNetwork, context, fixed, "base");

            // its value is the one of the array, and its derivatives those of the array times the share of the third generator
            fixed.fill();
            double share = elementTerms.computeCoefficient(transformerTerm);
            assertEquals(thirdGeneratorBus.getRemoteControlReactivePercent(), share);
            EquationTermArray<AcVariableType, AcEquationType> flows = elementTerms.termArrays.get(elementTerms.termArray[transformerTerm]);
            assertEquals((transformer.getBus1() == generatorBus ? transformer.getQ1() : transformer.getQ2()).eval(), flows.eval()[transformer.getNum()]);
            int distribution = structure.getNumber(AcEquationType.DISTR_Q, thirdGeneratorBus.getNum());
            assertTrue(fixed.getSlotOfEquation(distribution) >= 0);
            int checked = 0;
            for (int k = 0; k < 4; k++) {
                // of the terms of the equation, only this one depends on the bus of the second generator
                int slot = elementTerms.slot[transformerTerm][k];
                if (structure.getVariable(slot).getElementNum() == generatorBus.getNum()) {
                    double derivative = flows.evalDer()[elementTerms.localIndex[transformerTerm][k]][transformer.getNum()];
                    assertTrue(derivative != 0);
                    assertEquals(share * derivative, fixed.getValues()[elementTerms.destination[transformerTerm][k]]);
                    checked++;
                }
            }
            assertEquals(2, checked);

            // the second transformer is lost: OLF turns its terms off, and the table follows when the equations are placed again
            transformer.setDisabled(true);
            generatorBus.setDisabled(true);
            check(lfNetwork, context, fixed, "second generator lost");
            assertSame(elementTerms, fixed.getStructure().getElementTerms());
            assertFalse(elementTerms.active[transformerTerm]);
            transformer.setDisabled(false);
            generatorBus.setDisabled(false);
            check(lfNetwork, context, fixed, "second generator back");
            assertTrue(elementTerms.active[transformerTerm]);
        }
    }

    /** The coefficient, by slot, of the linear terms of an equation that are active, or of those that are not. */
    private static Map<Integer, Double> coefficients(FixedStructure.LinearTerms<AcVariableType, AcEquationType> linearTerms, int equation, boolean active) {
        Map<Integer, Double> coefficients = new HashMap<>();
        for (int i = linearTerms.start[equation]; i < linearTerms.start[equation + 1]; i++) {
            if (linearTerms.active[i] == active) {
                coefficients.put(linearTerms.slot[i], linearTerms.coefficient[i]);
            }
        }
        return coefficients;
    }

    private static String equationOn(AcLoadFlowContext context, FixedEquationSystem<AcVariableType, AcEquationType> fixed,
                                     AcVariableType variableType, int elementNum) {
        FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
        int equation = fixed.getEquationOfSlot(structure.getSlot(context.getEquationSystem().getVariable(elementNum, variableType)));
        return equation < 0 ? "none" : structure.getType(equation) + ":" + structure.getElementNum(equation);
    }

    private static String write(FixedEquationSystem<AcVariableType, AcEquationType> fixed) throws IOException {
        StringWriter writer = new StringWriter();
        fixed.write(writer);
        return writer.toString();
    }

    /**
     * Everything a contingency, an action or an outer loop toggles. The moves keep what OLF keeps: a branch is on only
     * between buses that are on, and a bus that is on keeps a branch.
     */
    private static List<Runnable> moves(LfNetwork network) {
        List<Runnable> moves = new ArrayList<>();
        for (LfBranch branch : network.getBranches()) {
            moves.add(() -> {
                if (branch.isDisabled()) {
                    if (isOn(branch.getBus1()) && isOn(branch.getBus2())) {
                        branch.setDisabled(false);
                    }
                } else if (keepsABranchWithout(branch.getBus1(), branch) && keepsABranchWithout(branch.getBus2(), branch)) {
                    branch.setDisabled(true);
                }
            });
            if (branch.isVoltageController()) {
                moves.add(() -> branch.setVoltageControlEnabled(!branch.isVoltageControlEnabled()));
            }
            if (branch.isPhaseController()) {
                moves.add(() -> branch.setPhaseControlEnabled(!branch.isPhaseControlEnabled()));
            }
        }
        for (LfBus bus : network.getBuses()) {
            if (!bus.isSlack() && !bus.isReference()) { // OLF relocates the slack before it disables its bus
                moves.add(() -> toggle(bus));
            }
            if (bus.isGeneratorVoltageControlEnabled()) {
                moves.add(() -> bus.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(!bus.isGeneratorVoltageControlEnabled()));
            }
            bus.getControllerShunt().ifPresent(shunt ->
                    moves.add(() -> shunt.setVoltageControlEnabled(!shunt.isVoltageControlEnabled())));
        }
        return moves;
    }

    private static void toggle(LfBus bus) {
        if (bus.isDisabled()) {
            List<LfBranch> branches = bus.getBranches().stream().filter(branch -> isOn(other(branch, bus))).toList();
            if (!branches.isEmpty()) {
                bus.setDisabled(false);
                branches.forEach(branch -> branch.setDisabled(false));
            }
        } else if (bus.getBranches().stream().filter(branch -> !branch.isDisabled())
                .allMatch(branch -> keepsABranchWithout(other(branch, bus), bus))) {
            bus.setDisabled(true);
            bus.getBranches().forEach(branch -> branch.setDisabled(true));
        }
    }

    private static boolean isOn(LfBus bus) {
        return bus == null || !bus.isDisabled();
    }

    private static LfBus other(LfBranch branch, LfBus bus) {
        return branch.getBus1() == bus ? branch.getBus2() : branch.getBus1();
    }

    /** True when the bus, if on, keeps a branch that is on once the given branch is off. */
    private static boolean keepsABranchWithout(LfBus bus, LfBranch without) {
        return bus == null || bus.isDisabled()
                || bus.getBranches().stream().anyMatch(branch -> branch != without && !branch.isDisabled());
    }

    /** True when the bus, if on, keeps a branch that is on once the given neighbour and the branches to it are off. */
    private static boolean keepsABranchWithout(LfBus bus, LfBus without) {
        return bus == null || bus.isDisabled()
                || bus.getBranches().stream().anyMatch(branch -> !branch.isDisabled() && other(branch, bus) != without);
    }

    private static void check(LfNetwork lfNetwork, AcLoadFlowContext context,
                              FixedEquationSystem<AcVariableType, AcEquationType> fixed, String state) {
        EquationSystem<AcVariableType, AcEquationType> equationSystem = context.getEquationSystem();
        // a state away from the flat start, of the size of OLF's current variables
        AcSolverUtil.initStateVector(lfNetwork, equationSystem, new UniformValueVoltageInitializer());
        double[] x = equationSystem.getStateVector().get().clone();
        for (int i = 0; i < x.length; i++) {
            x[i] += 0.05 * Math.sin(i + 1.0);
        }
        equationSystem.getStateVector().set(x);

        DenseMatrix reference = (DenseMatrix) context.getJacobianMatrix().getMatrix();
        double[] referenceValues = context.getEquationVector().getArray();
        fixed.fill();
        FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
        int slotCount = structure.getSlotCount();
        double[][] column = new double[slotCount][slotCount];
        for (int slot = 0; slot < slotCount; slot++) {
            for (int i = structure.getColumnStart()[slot]; i < structure.getColumnStart()[slot + 1]; i++) {
                column[slot][structure.getRowIndices()[i]] = fixed.getValues()[i];
            }
        }

        int activeCount = 0;
        for (int equation = 0; equation < structure.getEquationCount(); equation++) {
            int slot = fixed.getSlotOfEquation(equation);
            String name = state + ", " + structure.getType(equation) + " " + structure.getElementNum(equation);
            assertEquals(structure.isActive(equation), slot >= 0, name);
            if (slot >= 0) {
                activeCount++;
                int referenceColumn = structure.getColumnInEquationSystem(equation);
                assertClose(referenceValues[referenceColumn], fixed.getEquationValues()[slot], name);
                for (int row = 0; row < slotCount; row++) {
                    int referenceRow = structure.getVariable(row).getRow();
                    double expected = referenceRow >= 0 ? reference.get(referenceRow, referenceColumn) : 0;
                    assertClose(expected, column[slot][row], name + ", " + structure.getVariable(row));
                }
            }
        }
        assertEquals(equationSystem.getIndex().getColumnCount(), activeCount, state);
        // no slot holds an equation that does not depend on its variable, wherever a placement can avoid it
        if (hasPlacementWithoutZeroDiagonal(structure)) {
            assertEquals(0, fixed.getZeroDiagonalCount(), state);
        }
        for (int slot = 0; slot < slotCount; slot++) {
            if (fixed.getEquationOfSlot(slot) < 0) {
                assertEquals(0, fixed.getEquationValues()[slot], state);
                for (int row = 0; row < slotCount; row++) {
                    assertEquals(row == slot ? 1 : 0, column[slot][row], state + ", free slot " + structure.getVariable(slot));
                }
            }
        }

        // the balances are the active and reactive balance of every bus, whatever its slots hold: a bus that holds its
        // voltage has no active reactive equation, and its reactive balance is what the reactive limits are checked on
        for (LfBus bus : lfNetwork.getBuses()) {
            int phi = structure.getSlot(equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_PHI));
            int v = structure.getSlot(equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_V));
            if (!bus.isDisabled() && phi >= 0 && v >= 0) {
                assertClose(bus.getP().eval(), fixed.getBalance()[phi], state + ", active balance of " + bus.getId());
                assertClose(bus.getQ().eval(), fixed.getBalance()[v], state + ", reactive balance of " + bus.getId());
            }
        }
    }

    /**
     * Solving the fixed system gives OLF's own Newton step: the slots only put the equations in another order, and a
     * slot without equation leaves its variable where it is.
     */
    /**
     * True when the active equations can be placed one per slot, each on a variable it depends on in the current
     * state: searched here among all the variables of each equation, whatever its candidates. It is not so in a state
     * OLF cannot solve either, like an island of zero impedance branches that no bus holds the voltage of.
     */
    private static boolean hasPlacementWithoutZeroDiagonal(FixedStructure<AcVariableType, AcEquationType> structure) {
        int[] equationOfSlot = new int[structure.getSlotCount()];
        Arrays.fill(equationOfSlot, -1);
        for (int equation = 0; equation < structure.getEquationCount(); equation++) {
            if (structure.isActive(equation) && !place(structure, equation, equationOfSlot, new boolean[structure.getSlotCount()])) {
                return false;
            }
        }
        return true;
    }

    private static boolean place(FixedStructure<AcVariableType, AcEquationType> structure, int equation, int[] equationOfSlot, boolean[] visited) {
        int own = structure.getOwnSlot(equation);
        if (own >= 0 && placeOn(structure, equation, own, equationOfSlot, visited)) {
            return true;
        }
        for (int slot : structure.getRows(equation)) {
            if (placeOn(structure, equation, slot, equationOfSlot, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean placeOn(FixedStructure<AcVariableType, AcEquationType> structure, int equation, int slot, int[] equationOfSlot, boolean[] visited) {
        if (visited[slot] || structure.getVariable(slot).getRow() < 0 || !structure.dependsOnNow(equation, slot)) {
            return false;
        }
        visited[slot] = true;
        if (equationOfSlot[slot] >= 0 && !place(structure, equationOfSlot[slot], equationOfSlot, visited)) {
            return false;
        }
        equationOfSlot[slot] = equation;
        return true;
    }

    private static void checkNewtonStep(AcLoadFlowContext context, FixedEquationSystem<AcVariableType, AcEquationType> fixed) {
        double[] referenceStep = context.getEquationVector().getArray().clone();
        double[] targets = context.getTargetVector().getArray();
        for (int i = 0; i < referenceStep.length; i++) {
            referenceStep[i] -= targets[i];
        }
        double[] mismatch = referenceStep.clone();
        // OLF's step on the matrix in the numbering of the index: the Jacobian of the context itself solves on a fixed matrix
        try (LUDecomposition lu = context.getJacobianMatrix().getMatrix().decomposeLU()) {
            lu.solveTransposed(referenceStep);
        }

        FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
        int slotCount = structure.getSlotCount();
        double[] step = new double[slotCount];
        for (int equation = 0; equation < structure.getEquationCount(); equation++) {
            if (fixed.getSlotOfEquation(equation) >= 0) {
                step[fixed.getSlotOfEquation(equation)] = mismatch[structure.getColumnInEquationSystem(equation)];
            }
        }
        // the matrix a solver would keep: built once on the pattern, then filled in place
        SparseMatrix matrix = new SparseMatrix(slotCount, slotCount, structure.getColumnStart(), structure.getRowIndices(),
                new double[structure.getValueCount()]);
        fixed.fill(matrix.getValues());
        try (LUDecomposition lu = matrix.decomposeLU()) {
            lu.solveTransposed(step);
        }
        for (int slot = 0; slot < slotCount; slot++) {
            int row = structure.getVariable(slot).getRow();
            double expected = row >= 0 ? referenceStep[row] : 0;
            assertEquals(expected, step[slot], 1e-8 * Math.max(1, Math.abs(expected)), "Newton step of " + structure.getVariable(slot));
        }
    }

    private static void assertClose(double expected, double actual, String message) {
        assertEquals(expected, actual, 1e-12 * Math.max(1, Math.abs(expected)), message);
    }
}
