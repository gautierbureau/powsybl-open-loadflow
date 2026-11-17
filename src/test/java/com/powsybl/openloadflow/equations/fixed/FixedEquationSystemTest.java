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
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.DistributedSlackNetworkFactory;
import com.powsybl.openloadflow.network.HvdcNetworkFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
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
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * A network, the parameters that switch its controls on, whether its switches are kept as branches, and what is
     * expected of the pattern when every equation is in it from the start.
     */
    private record Case(String name, Supplier<Network> network, Consumer<LoadFlowParameters> configuration, boolean breakers,
                        Pattern pattern) {

        Case(String name, Supplier<Network> network) {
            this(name, network, parameters -> { }, false, Pattern.NEVER_CHANGES);
        }

        Case(String name, Supplier<Network> network, Consumer<LoadFlowParameters> configuration) {
            this(name, network, configuration, false, Pattern.NEVER_CHANGES);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private enum Pattern {
        /** Built once: no activation changes it. */
        NEVER_CHANGES,
        /** Grows when a reactive balance moves to the slot of a tap or of a shunt it had not taken yet. */
        GROWS,
        /** OLF creates and removes equations as it runs: the arrays are compiled again. */
        RECOMPILED
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
                        parameters -> parameters.setTransformerVoltageControlOn(true), false, Pattern.GROWS),
                new Case("shared transformer voltage control", VoltageControlNetworkFactory::createWithTransformerSharedRemoteControl,
                        parameters -> parameters.setTransformerVoltageControlOn(true), false, Pattern.GROWS),
                new Case("shared shunt voltage control", VoltageControlNetworkFactory::createWithShuntSharedRemoteControl,
                        parameters -> parameters.setShuntCompensatorVoltageControlOn(true), false, Pattern.GROWS),
                new Case("shunt", ShuntNetworkFactory::create,
                        parameters -> parameters.setShuntCompensatorVoltageControlOn(true), false, Pattern.GROWS),
                new Case("phase control", FixedEquationSystemTest::phaseControl,
                        parameters -> parameters.setPhaseShifterRegulationOn(true)),
                new Case("generator remote reactive power control", ReactivePowerControlNetworkFactory::createWithGeneratorRemoteControl,
                        parameters -> OpenLoadFlowParameters.create(parameters).setGeneratorReactivePowerRemoteControl(true)),
                new Case("hvdc in ac emulation", HvdcNetworkFactory::createWithHvdcInAcEmulation),
                new Case("zero impedance branches", ZeroImpedanceNetworkFactory::createWithVoltageControl,
                        parameters -> { }, false, Pattern.RECOMPILED),
                new Case("node breaker", NodeBreakerNetworkFactory::create, parameters -> { }, true, Pattern.NEVER_CHANGES));
    }

    private static Stream<Arguments> arguments() {
        return cases().stream().flatMap(c -> Stream.of(
                Arguments.of(c, false, FixedEquationSystem.Scope.ALL),
                Arguments.of(c, true, FixedEquationSystem.Scope.ALL),
                Arguments.of(c, true, FixedEquationSystem.Scope.ACTIVE)));
    }

    @ParameterizedTest(name = "{0}, vectorized = {1}, scope = {2}")
    @MethodSource("arguments")
    void matchesTheEquationSystem(Case c, boolean vectorized, FixedEquationSystem.Scope scope) {
        Network network = c.network().get();
        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.create(parameters);
        c.configuration().accept(parameters);
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, parametersExt,
                        new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), c.breakers(), false)
                .setVectorized(vectorized);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
             FixedEquationSystem<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystem<>(context.getEquationSystem(), AcPlacement.INSTANCE, scope)) {
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
            if (c.pattern() != Pattern.RECOMPILED) {
                assertEquals(1, fixed.getStructureBuildCount());
                if (scope == FixedEquationSystem.Scope.ALL && c.pattern() == Pattern.NEVER_CHANGES) {
                    assertEquals(1, fixed.getPatternBuildCount());
                }
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
        LoadFlowParameters parameters = new LoadFlowParameters();
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters,
                        OpenLoadFlowParameters.create(parameters), new DenseMatrixFactory(),
                        new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false)
                .setVectorized(true);
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
    private static void checkNewtonStep(AcLoadFlowContext context, FixedEquationSystem<AcVariableType, AcEquationType> fixed) {
        double[] referenceStep = context.getEquationVector().getArray().clone();
        double[] targets = context.getTargetVector().getArray();
        for (int i = 0; i < referenceStep.length; i++) {
            referenceStep[i] -= targets[i];
        }
        double[] mismatch = referenceStep.clone();
        context.getJacobianMatrix().solveTransposed(referenceStep);

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
