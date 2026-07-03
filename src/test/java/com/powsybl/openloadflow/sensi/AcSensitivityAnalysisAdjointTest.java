/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.contingency.ContingencyContext;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControlAdder;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.sensitivity.SensitivityAnalysis;
import com.powsybl.sensitivity.SensitivityAnalysisParameters;
import com.powsybl.sensitivity.SensitivityAnalysisResult;
import com.powsybl.sensitivity.SensitivityAnalysisRunParameters;
import com.powsybl.sensitivity.SensitivityFactor;
import com.powsybl.sensitivity.SensitivityFunctionType;
import com.powsybl.sensitivity.SensitivityVariableType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finite-difference / forward cross-check of the reverse-mode entry point
 * {@link AcSensitivityAnalysis#runAdjoint} on IEEE-14 (the VJP validation gate). runAdjoint reuses the
 * AC load flow retained in the network cache ({@code networkCacheEnabled}) and returns
 * {@code θ̄ = Sᵀ·ȳ} without materialising the sensitivity matrix {@code S}.
 *
 * <p>The factors are the canonical DC-like sensitivity {@code d P_branch1 / d P_injection}: with an
 * active-power function and an active-power variable the SPI unscale is unity (both in MW, base cancels),
 * so runAdjoint's raw per-unit output equals both the forward (unscaled) sensitivity and a central finite
 * difference in MW/MW with no scaling correction. This makes it a clean, self-contained gate:
 * <ul>
 *   <li>{@code θ̄_g} for {@code ȳ = e_branch} must equal the forward {@code S[branch, g]} (transpose
 *       identity, tight) and a re-solve central difference {@code dP_branch/dP_g} (ground truth);</li>
 *   <li>for a weighted multi-branch cotangent, {@code θ̄_g = Σ_b ȳ_b · S[b, g]} (linearity + the
 *       per-function {@code x̄} de-duplication).</li>
 * </ul>
 */
class AcSensitivityAnalysisAdjointTest {

    private static final List<String> GENS = List.of("B2-G", "B3-G", "B6-G");

    private static LoadFlowParameters cacheEnabledParameters() {
        LoadFlowParameters lfp = new LoadFlowParameters().setDistributedSlack(false);
        OpenLoadFlowParameters.create(lfp).setNetworkCacheEnabled(true);
        return lfp;
    }

    private static LoadFlowParameters distributedSlackCacheEnabledParameters() {
        LoadFlowParameters lfp = new LoadFlowParameters().setDistributedSlack(true);
        OpenLoadFlowParameters.create(lfp).setNetworkCacheEnabled(true);
        return lfp;
    }

    private static SensitivityFactor injectionToBranchFlow(String branch, String gen) {
        return new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, branch,
                SensitivityVariableType.INJECTION_ACTIVE_POWER, gen, false, ContingencyContext.all());
    }

    private static double dBranchFlowPerGenFd(Network network, LoadFlowParameters lfp, String branch, String gen) {
        Generator g = network.getGenerator(gen);
        double p0 = g.getTargetP();
        double eps = 0.5; // MW
        g.setTargetP(p0 + eps);
        LoadFlow.find("OpenLoadFlow").run(network, lfp);
        double pPlus = network.getBranch(branch).getTerminal1().getP();
        g.setTargetP(p0 - eps);
        LoadFlow.find("OpenLoadFlow").run(network, lfp);
        double pMinus = network.getBranch(branch).getTerminal1().getP();
        g.setTargetP(p0);
        LoadFlow.find("OpenLoadFlow").run(network, lfp);
        return (pPlus - pMinus) / (2 * eps); // dP_branch_MW / dP_gen_MW == raw pu/pu
    }

    @Test
    void runAdjointMatchesForwardSensitivityAndFiniteDifferenceOnIeee14() {
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters lfp = cacheEnabledParameters();
        String branch = "L1-2-1";

        // populate the cache (runAdjoint reuses this converged, factorized context)
        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged());

        List<SensitivityFactor> factors = new ArrayList<>();
        for (String g : GENS) {
            factors.add(injectionToBranchFlow(branch, g));
        }

        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(lfp);

        // reverse mode: ȳ = e_branch -> θ̄_g = dP_branch1/dP_g
        AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);
        Map<String, Double> thetaBar = analysis.runAdjoint(network,
                network.getVariantManager().getWorkingVariantId(), List.of(), factors, Map.of(branch, 1.0));

        // forward sensitivity matrix S (its own no-cache load flow; unscaled == raw here)
        SensitivityAnalysisResult fwd = SensitivityAnalysis.find().run(network, factors,
                new SensitivityAnalysisRunParameters().setParameters(sensiParams));

        double maxAbs = 0;
        for (String g : GENS) {
            double s = fwd.getBranchFlow1SensitivityValue(g, branch, SensitivityVariableType.INJECTION_ACTIVE_POWER);
            double fd = dBranchFlowPerGenFd(network, lfp, branch, g); // last: mutates+restores the cache
            double theta = thetaBar.get(g);
            assertEquals(s, theta, 1e-5 * (Math.abs(s) + 1e-3), "runAdjoint vs forward S for " + g);
            assertEquals(fd, theta, 2e-3 * (Math.abs(fd) + 1e-2), "runAdjoint vs finite difference for " + g);
            maxAbs = Math.max(maxAbs, Math.abs(theta));
        }
        assertTrue(maxAbs > 0.1, "gradient must be non-trivial, got max |θ̄| = " + maxAbs);
    }

    @Test
    void runAdjointHandlesDistributedSlackOnIeee14() {
        // Distributed slack is an outer loop folded into the RHS via slackParticipationByBus
        // (getParticipatingElements) exactly as the forward does — no Schur/augmented Jacobian — so the
        // transpose picks it up on the shared λ. The gate is that runAdjoint reproduces the forward
        // sensitivity S with the slack DISTRIBUTED over the machines (S differs from the single-slack case
        // because the injection variable now carries the -participation columns).
        //
        // We compare against the forward S rather than a raw-targetP re-solve: perturbing a *participating*
        // generator's targetP has part of the change reabsorbed by the slack distribution, so the physical
        // FD's effective injection pattern is not the SPI INJECTION_ACTIVE_POWER variable (they disagree by
        // the participation, ~2x here). That is a property of the forward injection convention, which OLF's
        // own distributed-slack sensitivity tests validate against a convention-correct FD; here we assert
        // the adjoint inherits it exactly.
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters lfp = distributedSlackCacheEnabledParameters();
        String branch = "L1-2-1";

        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged());

        List<SensitivityFactor> factors = new ArrayList<>();
        for (String g : GENS) {
            factors.add(injectionToBranchFlow(branch, g));
        }

        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(lfp);

        AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);
        Map<String, Double> thetaBar = analysis.runAdjoint(network,
                network.getVariantManager().getWorkingVariantId(), List.of(),
                factors, Map.of(branch, 1.0));

        SensitivityAnalysisResult fwd = SensitivityAnalysis.find().run(network, factors,
                new SensitivityAnalysisRunParameters().setParameters(sensiParams));

        double maxAbs = 0;
        for (String g : GENS) {
            double s = fwd.getBranchFlow1SensitivityValue(g, branch, SensitivityVariableType.INJECTION_ACTIVE_POWER);
            double theta = thetaBar.get(g);
            assertEquals(s, theta, 1e-5 * (Math.abs(s) + 1e-3), "runAdjoint vs forward S (distributed slack) for " + g);
            maxAbs = Math.max(maxAbs, Math.abs(theta));
        }
        assertTrue(maxAbs > 0.1, "gradient must be non-trivial, got max |θ̄| = " + maxAbs);
    }

    private static Network ieee14WithZone(double pilotTargetV) {
        Network network = IeeeCdfNetworkFactory.create14();
        network.getGenerator("B8-G").newMinMaxReactiveLimits().setMinQ(-6).setMaxQ(200).add();
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone().withName("z1")
                    .newPilotPoint().withTargetV(pilotTargetV).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                    .newControlUnit().withId("B6-G").add()
                    .newControlUnit().withId("B8-G").add()
                    .add()
                .add();
        return network;
    }

    private static LoadFlowParameters svcCacheEnabledParameters() {
        LoadFlowParameters lfp = new LoadFlowParameters().setUseReactiveLimits(false);
        OpenLoadFlowParameters.create(lfp)
                .setSecondaryVoltageControl(true)
                .setMaxPlausibleTargetVoltage(1.6)
                .setNetworkCacheEnabled(true);
        return lfp;
    }

    private static double busVoltage(Network network, String busId) {
        return network.getBusBreakerView().getBus(busId).getV();
    }

    private static double nominalV(Network network, String busId) {
        return network.getBusBreakerView().getBus(busId).getVoltageLevel().getNominalV();
    }

    @Test
    void runAdjointHandlesSvcPilotLeverOnIeee14() {
        // TVC's RST lever: the SVC pilot-point target voltage (SVC_PILOT_POINT_TARGET_VOLTAGE, variableId =
        // the zone name). runAdjoint reuses fillSvcPilotFactorsRhs (the closed-loop coordination) on the
        // cached converged context. θ̄ is RAW per-unit dV_bus_pu / dV_pilot_pu, whereas the forward S and the
        // physical re-solve FD are kV/kV, so they relate by the nominal-voltage ratio Vnom(pilot)/Vnom(f0)
        // (which is 1 when the monitored bus shares the pilot's voltage level).
        String zone = "z1";
        String pilot = "B10";
        List<String> monitored = List.of("B10", "B6", "B4"); // pilot, a controller bus, a far bus on another VL
        double targetV = 13.0;
        double dV = 0.1; // kV central step on the pilot target

        LoadFlowParameters lfp = svcCacheEnabledParameters();
        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(lfp);

        Network network = ieee14WithZone(targetV);
        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged());

        List<SensitivityFactor> factors = new ArrayList<>();
        for (String bus : monitored) {
            factors.add(new SensitivityFactor(SensitivityFunctionType.BUS_VOLTAGE, bus,
                    SensitivityVariableType.SVC_PILOT_POINT_TARGET_VOLTAGE, zone, false, ContingencyContext.all()));
        }
        AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);

        // forward closed-loop sensitivity S (kV/kV) and a per-bus re-solve central FD (kV/kV)
        SensitivityAnalysisResult fwd = SensitivityAnalysis.find().run(network, factors,
                new SensitivityAnalysisRunParameters().setParameters(sensiParams));
        Network nBefore = ieee14WithZone(targetV - dV);
        LoadFlow.find("OpenLoadFlow").run(nBefore, lfp);
        Network nAfter = ieee14WithZone(targetV + dV);
        LoadFlow.find("OpenLoadFlow").run(nAfter, lfp);

        double pilotTheta = 0;
        for (String f0 : monitored) {
            // ȳ = e_{f0} -> θ̄[zone] = dV_f0 / dV_pilotTarget (closed loop), raw per-unit
            Map<String, Double> thetaBar = analysis.runAdjoint(network,
                    network.getVariantManager().getWorkingVariantId(), List.of(), factors, Map.of(f0, 1.0));
            double theta = thetaBar.get(zone);
            double ratio = nominalV(network, pilot) / nominalV(network, f0); // pu/pu = (kV/kV) * Vnom(pilot)/Vnom(f0)

            double sKv = fwd.getBusVoltageSensitivityValue(zone, f0, SensitivityVariableType.SVC_PILOT_POINT_TARGET_VOLTAGE);
            assertEquals(sKv * ratio, theta, 1e-4 * (Math.abs(sKv * ratio) + 1e-3),
                    "runAdjoint vs forward closed-loop S for " + f0);

            double fdKv = (busVoltage(nAfter, f0) - busVoltage(nBefore, f0)) / (2 * dV);
            assertEquals(fdKv * ratio, theta, 5e-3 * (Math.abs(fdKv * ratio) + 1e-2),
                    "runAdjoint vs re-solve FD for " + f0);

            if (f0.equals(pilot)) {
                pilotTheta = theta;
            }
        }
        // the closed loop makes the pilot bus voltage track its own target: a non-trivial, ≈1 gradient
        assertEquals(1.0, pilotTheta, 1e-3, "pilot bus voltage tracks its target");
    }

    @Test
    void runAdjointIsLinearInTheFunctionCotangents() {
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters lfp = cacheEnabledParameters();
        String branchA = "L1-2-1";
        String branchB = "L1-5-1";
        double wA = 0.75;
        double wB = -1.5;

        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged());

        List<SensitivityFactor> factors = new ArrayList<>();
        for (String g : GENS) {
            factors.add(injectionToBranchFlow(branchA, g));
            factors.add(injectionToBranchFlow(branchB, g));
        }

        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(lfp);

        AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);
        Map<String, Double> thetaBar = analysis.runAdjoint(network,
                network.getVariantManager().getWorkingVariantId(), List.of(),
                factors, Map.of(branchA, wA, branchB, wB));

        SensitivityAnalysisResult fwd = SensitivityAnalysis.find().run(network, factors,
                new SensitivityAnalysisRunParameters().setParameters(sensiParams));

        for (String g : GENS) {
            double sA = fwd.getBranchFlow1SensitivityValue(g, branchA, SensitivityVariableType.INJECTION_ACTIVE_POWER);
            double sB = fwd.getBranchFlow1SensitivityValue(g, branchB, SensitivityVariableType.INJECTION_ACTIVE_POWER);
            double expected = wA * sA + wB * sB;
            assertEquals(expected, thetaBar.get(g), 1e-5 * (Math.abs(expected) + 1e-3),
                    "weighted Sᵀȳ for " + g);
        }
    }
}
