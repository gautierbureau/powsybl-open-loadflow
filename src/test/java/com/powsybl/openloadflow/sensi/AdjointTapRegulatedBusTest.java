/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.graph.dtree.DTreeGraphConnectivityFactory;
import com.powsybl.sensitivity.SensitivityAnalysisParameters;
import com.powsybl.sensitivity.SensitivityFunctionType;
import com.powsybl.sensitivity.SensitivityVariableType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A small network with the shape of a split 225 kV substation where a reactor on one node is credited by the gradient
 * with raising another node that a tap changer holds.
 * <pre>
 *     GEN ---- line ---- A (reactor REAC)
 *      |                 |
 *      |               T_A (no tap)
 *     line               |
 *      |                 S (star point)
 *      W (load) -- line -- B --- T_B (tap regulating B)
 * </pre>
 * The tap of T_B (S to B) holds B; switching the reactor off raises A and S, and would raise B as much if the tap
 * were frozen.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AdjointTapRegulatedBusTest {

    private static Network network(double reactorB, double tapTargetV) {
        Network network = Network.create("grou6-like", "test");
        Substation substation = network.newSubstation().setId("SUB").add();
        for (String id : List.of("GEN", "A", "S", "B", "W")) {
            substation.newVoltageLevel().setId("VL_" + id).setNominalV(225).setLowVoltageLimit(200).setHighVoltageLimit(250)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId(id).add();
        }
        network.getVoltageLevel("VL_GEN").newGenerator().setId("G").setBus("GEN").setMinP(0).setMaxP(2000).setTargetP(400)
                .setLocalTargetV(232)
                .newVoltageRegulation().withRegulating(true).withMode(RegulationMode.VOLTAGE).add()
                .add();
        network.getVoltageLevel("VL_A").newLoad().setId("LOAD_A").setBus("A").setP0(50).setQ0(-60).add();
        network.getVoltageLevel("VL_B").newLoad().setId("LOAD_B").setBus("B").setP0(60).setQ0(20).add();
        network.getVoltageLevel("VL_W").newLoad().setId("LOAD_W").setBus("W").setP0(250).setQ0(60).add();
        network.getVoltageLevel("VL_A").newShuntCompensator().setId("REAC").setBus("A").setSectionCount(1)
                .newLinearModel().setMaximumSectionCount(1).setBPerSection(reactorB).setGPerSection(0).add()
                .add();
        network.newLine().setId("L_GEN_A").setBus1("GEN").setBus2("A").setR(1).setX(25).add();
        network.newLine().setId("L_GEN_W").setBus1("GEN").setBus2("W").setR(1).setX(12).add();
        network.newLine().setId("L_W_B").setBus1("W").setBus2("B").setR(1).setX(15).add();
        substation.newTwoWindingsTransformer().setId("T_A").setBus1("A").setBus2("S")
                .setRatedU1(225).setRatedU2(225).setR(0.5).setX(20).add();
        TwoWindingsTransformer tb = substation.newTwoWindingsTransformer().setId("T_B").setBus1("S").setBus2("B")
                .setRatedU1(225).setRatedU2(225).setR(0.5).setX(20).add();
        var adder = tb.newRatioTapChanger();
        for (int k = 0; k <= 400; k++) {
            adder.beginStep().setRho(0.9 + 0.0005 * k).setR(0).setX(0).setG(0).setB(0).endStep();
        }
        adder.setTapPosition(200).setLoadTapChangingCapabilities(true)
                .newVoltageRegulation().withTargetValue(tapTargetV).withRegulating(false).withTerminal(tb.getTerminal2())
                    .withMode(RegulationMode.VOLTAGE).add()
                .add();
        tb.getRatioTapChanger().getVoltageRegulation().setTargetDeadband(0).setRegulating(true);
        return network;
    }

    private static LoadFlowParameters parameters() {
        LoadFlowParameters lfp = new LoadFlowParameters().setDistributedSlack(false).setTransformerVoltageControlOn(true);
        OpenLoadFlowParameters.create(lfp).setNetworkCacheEnabled(true)
                .setTransformerVoltageControlMode(OpenLoadFlowParameters.TransformerVoltageControlMode.AFTER_GENERATOR_VOLTAGE_CONTROL);
        return lfp;
    }

    private static double v(Network network, String bus) {
        return network.getBusBreakerView().getBus(bus).getV();
    }

    private static Network solved(double reactorB, double tapTargetV) {
        Network network = network(reactorB, tapTargetV);
        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, parameters()).isFullyConverged());
        return network;
    }

    /** dV(bus)/db(REAC), kV per siemens, by the adjoint with the given sensitivity parameters. */
    private static double gradient(Network network, String bus, OpenSensitivityAnalysisParameters sensiExt) {
        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, parameters()).isFullyConverged());
        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(parameters());
        sensiParams.addExtension(OpenSensitivityAnalysisParameters.class, sensiExt);
        return new AcSensitivityAnalysis(new SparseMatrixFactory(), new DTreeGraphConnectivityFactory<>(), sensiParams)
                .runAdjoint(network, network.getVariantManager().getWorkingVariantId(), List.of(),
                        Map.of(new AcSensitivityAnalysis.FunctionRef(SensitivityFunctionType.BUS_VOLTAGE, bus), 1.0),
                        List.of(AcSensitivityAnalysis.AdjointVariable.of(SensitivityVariableType.SHUNT_COMPENSATOR_SUSCEPTANCE, "REAC")))
                .values().iterator().next();
    }

    private static final double REACTOR_B = -0.0016; // S, about 80 Mvar at 225 kV

    private static OpenSensitivityAnalysisParameters frozenTaps() {
        return new OpenSensitivityAnalysisParameters();
    }

    private static OpenSensitivityAnalysisParameters regulatingTaps() {
        return new OpenSensitivityAnalysisParameters().setAdjointTransformerVoltageControl(true);
    }

    private static OpenSensitivityAnalysisParameters smoothedTaps() {
        return regulatingTaps().setAdjointTransformerVoltageControlSmoothing(true);
    }

    /** The change of {@code bus} the gradient predicts for switching the reactor off, kV. */
    private static double predictedSwitchOff(Network reactorOn, String bus, OpenSensitivityAnalysisParameters sensiExt) {
        return gradient(reactorOn, bus, sensiExt) * -REACTOR_B;
    }

    /**
     * The tap has room: switching the reactor off raises A by 9 kV and leaves B where its tap holds it. With frozen
     * taps, the gradient credits the reactor with raising B by 2.6 kV, as at G.ROUP6.
     */
    @Test
    void frozenTapsCreditTheReactorWithTheBusTheTapHolds() {
        Network on = solved(REACTOR_B, 222);
        Network off = solved(0, 222);
        double realB = v(off, "B") - v(on, "B");
        double realA = v(off, "A") - v(on, "A");
        assertEquals(0, realB, 0.05);
        assertTrue(realA > 8);

        assertTrue(predictedSwitchOff(on, "B", frozenTaps()) > 2.5);
        assertEquals(0, predictedSwitchOff(on, "B", regulatingTaps()), 0.05);
        assertEquals(0, predictedSwitchOff(on, "B", smoothedTaps()), 0.5);
        // and A: the frozen taps miss what the held B adds to it
        assertTrue(Math.abs(predictedSwitchOff(on, "A", frozenTaps()) - realA) > 0.2 * realA);
        assertEquals(realA, predictedSwitchOff(on, "A", regulatingTaps()), 0.05 * realA);
        assertEquals(realA, predictedSwitchOff(on, "A", smoothedTaps()), 0.1 * realA);
    }

    /**
     * The tap is 32 steps from the end of its range: switching the reactor off takes it there, and B rises 1.4 kV.
     * The regulating taps see none of it, the frozen ones twice as much; the smoothed ones are the closest.
     */
    @Test
    void smoothedTapsSeeATapRunningOutOfRange() {
        Network on = solved(REACTOR_B, 219.46);
        Network off = solved(0, 219.46);
        assertEquals(32, on.getTwoWindingsTransformer("T_B").getRatioTapChanger().getSolvedTapPosition());
        assertEquals(0, off.getTwoWindingsTransformer("T_B").getRatioTapChanger().getSolvedTapPosition());
        double real = v(off, "B") - v(on, "B");
        double frozenError = Math.abs(predictedSwitchOff(on, "B", frozenTaps()) - real);
        double regulatingError = Math.abs(predictedSwitchOff(on, "B", regulatingTaps()) - real);
        double smoothedError = Math.abs(predictedSwitchOff(on, "B", smoothedTaps()) - real);
        assertTrue(real > 1);
        assertTrue(smoothedError < 0.5 * Math.min(frozenError, regulatingError),
                "smoothed " + smoothedError + ", frozen " + frozenError + ", regulating " + regulatingError);
    }

    /** Between a tap at the end of its range and one 8 steps from it, the gradient jumps unless smoothed. */
    @Test
    void smoothedTapsDoNotJumpWhenTheTapLeavesTheEndOfItsRange() {
        Network atEnd = solved(REACTOR_B, 218);
        Network inside = solved(REACTOR_B, 218.5);
        assertEquals(0, atEnd.getTwoWindingsTransformer("T_B").getRatioTapChanger().getSolvedTapPosition());
        assertEquals(8, inside.getTwoWindingsTransformer("T_B").getRatioTapChanger().getSolvedTapPosition());
        double regulatingJump = Math.abs(gradient(atEnd, "B", regulatingTaps()) - gradient(inside, "B", regulatingTaps()));
        double smoothedJump = Math.abs(gradient(atEnd, "B", smoothedTaps()) - gradient(inside, "B", smoothedTaps()));
        assertTrue(regulatingJump > 1000, "regulating " + regulatingJump);
        assertTrue(smoothedJump < 0.15 * regulatingJump, "smoothed " + smoothedJump + " against " + regulatingJump);
    }
}
