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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // cotangent-map key for a BRANCH_ACTIVE_POWER_1 monitored function
    private static String powerKey(String branchId) {
        return AcSensitivityAnalysis.functionCotangentKey(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, branchId);
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
                network.getVariantManager().getWorkingVariantId(), List.of(), factors, Map.of(AcSensitivityAnalysis.functionCotangentKey(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, branch), 1.0));

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
                factors, Map.of(AcSensitivityAnalysis.functionCotangentKey(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, branch), 1.0));

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
                    network.getVariantManager().getWorkingVariantId(), List.of(), factors, Map.of(AcSensitivityAnalysis.functionCotangentKey(SensitivityFunctionType.BUS_VOLTAGE, f0), 1.0));
            double theta = thetaBar.get(zone); // UNSCALED (kV/kV), the dual of get_sensitivity_matrix

            double sKv = fwd.getBusVoltageSensitivityValue(zone, f0, SensitivityVariableType.SVC_PILOT_POINT_TARGET_VOLTAGE);
            assertEquals(sKv, theta, 1e-4 * (Math.abs(sKv) + 1e-3),
                    "runAdjoint vs forward closed-loop S for " + f0);

            double fdKv = (busVoltage(nAfter, f0) - busVoltage(nBefore, f0)) / (2 * dV);
            assertEquals(fdKv, theta, 5e-3 * (Math.abs(fdKv) + 1e-2),
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
                factors, Map.of(powerKey(branchA), wA, powerKey(branchB), wB));

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

    // y -> y + dY at constant ksi: scale R and X so the series admittance modulus shifts by dY; re-solve on a
    // fresh network and return [P1(variableLine), P1(crossBranch)] in MW.
    private static double[] admittancePerturbedFlows(LoadFlowParameters lfp, String variableLine, String crossBranch,
                                                     double rBase, double xBase, double yBase, double dY) {
        Network n = IeeeCdfNetworkFactory.create14();
        double scale = yBase / (yBase + dY);
        n.getLine(variableLine).setR(rBase * scale).setX(xBase * scale);
        LoadFlow.find("OpenLoadFlow").run(n, lfp);
        return new double[] {n.getBranch(variableLine).getTerminal1().getP(),
            n.getBranch(crossBranch).getTerminal1().getP()};
    }

    @Test
    void runAdjointHandlesLineAdmittanceLeverOnIeee14() {
        // TVC's line lever: BRANCH_ADMITTANCE = the series admittance modulus y = 1/hypot(R,X) at constant ksi.
        // The KEY case is a SELF-sensitivity (the monitored function is on the very branch whose admittance is
        // the variable): then AcSensitivityAnalysis.computeParameterDirectPartial contributes an explicit
        // direct term on top of the through-Jacobian term. analyseAdjoint adds yBar*computeParameterDirectPartial
        // exactly as the forward's calculateSensitivityValues adds sensi += computeParameterDirectPartial, so
        // this gate validates the adjoint direct-term handling (and its sign). A CROSS factor (function on a
        // different branch) has a zero direct term and checks the indirect term only.
        //
        // runAdjoint returns the UNSCALED sensitivity (funcBase(f)/varBase(v) applied inside analyseAdjoint),
        // i.e. the dual of get_sensitivity_matrix in physical units, so θ̄ equals the forward S and the
        // physical re-solve FD (MW per physical siemens) directly — no scaling correction.
        String variableLine = "L2-3-1"; // the controllable line -> its series admittance is the lever
        String crossBranch = "L1-5-1";  // a different monitored branch (direct term = 0)
        LoadFlowParameters lfp = cacheEnabledParameters();

        Network network = IeeeCdfNetworkFactory.create14();
        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged());

        List<SensitivityFactor> factors = List.of(
                new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, variableLine,
                        SensitivityVariableType.BRANCH_ADMITTANCE, variableLine, false, ContingencyContext.all()),
                new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, crossBranch,
                        SensitivityVariableType.BRANCH_ADMITTANCE, variableLine, false, ContingencyContext.all()));

        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(lfp);
        AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);

        String variantId = network.getVariantManager().getWorkingVariantId();
        double thetaSelf = analysis.runAdjoint(network, variantId, List.of(), factors, Map.of(powerKey(variableLine), 1.0)).get(variableLine);
        double thetaCross = analysis.runAdjoint(network, variantId, List.of(), factors, Map.of(powerKey(crossBranch), 1.0)).get(variableLine);

        // forward S (unscaled: physical MW per physical siemens)
        SensitivityAnalysisResult fwd = SensitivityAnalysis.find().run(network, factors,
                new SensitivityAnalysisRunParameters().setParameters(sensiParams));
        double sSelf = fwd.getBranchFlow1SensitivityValue(variableLine, variableLine, SensitivityVariableType.BRANCH_ADMITTANCE);
        double sCross = fwd.getBranchFlow1SensitivityValue(variableLine, crossBranch, SensitivityVariableType.BRANCH_ADMITTANCE);

        assertEquals(sSelf, thetaSelf, 1e-4 * (Math.abs(sSelf) + 1e-6),
                "runAdjoint vs forward S, self (direct term)");
        assertEquals(sCross, thetaCross, 1e-4 * (Math.abs(sCross) + 1e-6),
                "runAdjoint vs forward S, cross");

        // physical re-solve central finite difference on the admittance modulus
        double rBase = network.getLine(variableLine).getR();
        double xBase = network.getLine(variableLine).getX();
        double yBase = 1.0 / Math.hypot(rBase, xBase);
        double dY = 1e-4 * yBase;
        double[] pPlus = admittancePerturbedFlows(lfp, variableLine, crossBranch, rBase, xBase, yBase, dY);
        double[] pMinus = admittancePerturbedFlows(lfp, variableLine, crossBranch, rBase, xBase, yBase, -dY);
        double fdSelf = (pPlus[0] - pMinus[0]) / (2 * dY);
        double fdCross = (pPlus[1] - pMinus[1]) / (2 * dY);

        assertEquals(fdSelf, thetaSelf, 2e-2 * (Math.abs(fdSelf) + 1e-6),
                "runAdjoint vs re-solve FD, self (direct term)");
        assertEquals(fdCross, thetaCross, 2e-2 * (Math.abs(fdCross) + 1e-6),
                "runAdjoint vs re-solve FD, cross");

        // the direct term makes the self-sensitivity substantial (and it must have the right sign to match S/FD)
        assertTrue(Math.abs(thetaSelf) > 1e-3, "self-sensitivity (with direct term) must be non-trivial, got " + thetaSelf);
    }

    @Test
    void runAdjointKeepsFunctionTypesDistinctOnSharedBranchId() {
        // A branch monitored by SEVERAL function types (here active power on side 1 AND side 2) shares one
        // functionId, so the cotangents must be keyed by (functionType, id), not the id alone — otherwise the
        // two function types' cotangents collide/sum. Distinct weights w1 != w2 expose the bug: θ̄ must be
        // w1·S[P1,g] + w2·S[P2,g], not (w1+w2)·(S[P1,g] + S[P2,g]).
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters lfp = cacheEnabledParameters();
        String branch = "L1-2-1";
        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged());

        List<SensitivityFactor> factors = new ArrayList<>();
        for (String g : GENS) {
            factors.add(new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, branch,
                    SensitivityVariableType.INJECTION_ACTIVE_POWER, g, false, ContingencyContext.all()));
            factors.add(new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_2, branch,
                    SensitivityVariableType.INJECTION_ACTIVE_POWER, g, false, ContingencyContext.all()));
        }
        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(lfp);
        AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);

        double w1 = 0.7;
        double w2 = -1.3;
        Map<String, Double> thetaBar = analysis.runAdjoint(network,
                network.getVariantManager().getWorkingVariantId(), List.of(), factors,
                Map.of(AcSensitivityAnalysis.functionCotangentKey(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, branch), w1,
                       AcSensitivityAnalysis.functionCotangentKey(SensitivityFunctionType.BRANCH_ACTIVE_POWER_2, branch), w2));

        SensitivityAnalysisResult fwd = SensitivityAnalysis.find().run(network, factors,
                new SensitivityAnalysisRunParameters().setParameters(sensiParams));
        for (String g : GENS) {
            double s1 = fwd.getBranchFlow1SensitivityValue(g, branch, SensitivityVariableType.INJECTION_ACTIVE_POWER);
            double s2 = fwd.getBranchFlow2SensitivityValue(g, branch, SensitivityVariableType.INJECTION_ACTIVE_POWER);
            double expected = w1 * s1 + w2 * s2;
            assertEquals(expected, thetaBar.get(g), 1e-5 * (Math.abs(expected) + 1e-3),
                    "runAdjoint must keep the two function types distinct on the shared branch id for " + g);
        }
    }

    // Builds the MINIMAL O(F+V) factor set that reverse mode actually needs: one factor per function (feeds
    // x̄ through the cotangent map), plus one factor per variable (creates its θ̄ group) — using the self
    // function-on-the-variable's-element when the variable is itself monitored (so the direct term is
    // present), else a filler function. This is what the pypowsybl runAdjoint should emit instead of the
    // functions×variables cross product.
    private static List<SensitivityFactor> minimalAdjointFactors(SensitivityFunctionType ft, List<String> functions,
                                                                 SensitivityVariableType vt, List<String> variables) {
        List<SensitivityFactor> factors = new ArrayList<>();
        Set<String> added = new HashSet<>(); // "functionId|variableId" pairs already emitted (avoid dup direct term)
        for (String f : functions) { // x̄: each function once (paired with the first variable)
            if (added.add(f + '|' + variables.get(0))) {
                factors.add(new SensitivityFactor(ft, f, vt, variables.get(0), false, ContingencyContext.all()));
            }
        }
        Set<String> functionSet = new HashSet<>(functions);
        for (String v : variables) { // θ̄ group + direct term: self-pair if v is monitored, else a filler
            String fn = functionSet.contains(v) ? v : functions.get(0);
            if (added.add(fn + '|' + v)) { // skip if already emitted (e.g. variables[0]'s self, from x̄)
                factors.add(new SensitivityFactor(ft, fn, vt, v, false, ContingencyContext.all()));
            }
        }
        return factors;
    }

    @Test
    void runAdjointMinimalFactorSetMatchesFullCrossProduct() {
        // The full adjoint declares functions×variables factors; reverse mode only needs O(F+V). This gate
        // proves the minimal set reproduces the full θ̄ to machine precision on branch admittance — the case
        // with a non-zero direct term (self) AND cross sensitivities (which must come from the single solve,
        // not from per-pair factors). Distinct cotangent weights make every cross term matter.
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters lfp = cacheEnabledParameters();
        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged());

        SensitivityFunctionType ft = SensitivityFunctionType.BRANCH_ACTIVE_POWER_1;
        SensitivityVariableType vt = SensitivityVariableType.BRANCH_ADMITTANCE;
        List<String> functions = List.of("L1-2-1", "L2-3-1", "L1-5-1", "L2-4-1", "L3-4-1");
        List<String> variables = List.of("L2-3-1", "L1-5-1"); // both monitored -> self direct term exercised
        double[] w = {0.7, -1.3, 0.4, 1.1, -0.6};
        Map<String, Double> cot = new HashMap<>();
        for (int i = 0; i < functions.size(); i++) {
            cot.put(AcSensitivityAnalysis.functionCotangentKey(ft, functions.get(i)), w[i]);
        }

        List<SensitivityFactor> full = new ArrayList<>();
        for (String v : variables) {
            for (String f : functions) {
                full.add(new SensitivityFactor(ft, f, vt, v, false, ContingencyContext.all()));
            }
        }
        List<SensitivityFactor> minimal = minimalAdjointFactors(ft, functions, vt, variables);
        assertTrue(minimal.size() < full.size(), "minimal set must be smaller than the cross product");

        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(lfp);
        AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);
        String variantId = network.getVariantManager().getWorkingVariantId();

        Map<String, Double> thetaFull = analysis.runAdjoint(network, variantId, List.of(), full, cot);
        Map<String, Double> thetaMin = analysis.runAdjoint(network, variantId, List.of(), minimal, cot);

        for (String v : variables) {
            assertEquals(thetaFull.get(v), thetaMin.get(v), 1e-9 * (Math.abs(thetaFull.get(v)) + 1e-9),
                    "minimal O(F+V) factor set must match the full cross product for " + v);
        }
    }

    // Multi-function-type generalisation (what pypowsybl emits over its v/i1/i2/p1/p2 matrices): x̄ per type,
    // self-pairs in each type where the variable is monitored, and a group-guarantee (a fixed function paired
    // with every variable) so a variable that is NEVER a monitored function still gets a θ̄ group. The
    // group-guarantee is safe: its direct term is 0 (function not on the variable's element) or the deduped self.
    private static List<SensitivityFactor> minimalAdjointFactorsMulti(List<SensitivityFunctionType> fts,
            List<List<String>> functionsPerType, SensitivityVariableType vt, List<String> variables) {
        List<SensitivityFactor> factors = new ArrayList<>();
        Set<String> added = new HashSet<>();
        String v0 = variables.get(0);
        for (int t = 0; t < fts.size(); t++) {
            SensitivityFunctionType ft = fts.get(t);
            for (String f : functionsPerType.get(t)) { // x̄: each function once, paired with v0
                if (added.add(ft.name() + '|' + f + '|' + v0)) {
                    factors.add(new SensitivityFactor(ft, f, vt, v0, false, ContingencyContext.all()));
                }
            }
            Set<String> fset = new HashSet<>(functionsPerType.get(t));
            for (String v : variables) { // self-pair (direct term) where the variable is a monitored function
                if (fset.contains(v) && added.add(ft.name() + '|' + v + '|' + v)) {
                    factors.add(new SensitivityFactor(ft, v, vt, v, false, ContingencyContext.all()));
                }
            }
        }
        SensitivityFunctionType ft0 = fts.get(0); // group guarantee for every variable
        String f0 = functionsPerType.get(0).get(0);
        for (String v : variables) {
            if (added.add(ft0.name() + '|' + f0 + '|' + v)) {
                factors.add(new SensitivityFactor(ft0, f0, vt, v, false, ContingencyContext.all()));
            }
        }
        return factors;
    }

    @Test
    void runAdjointMinimalFactorSetMatchesFullMultiFunctionType() {
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters lfp = cacheEnabledParameters();
        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged());

        List<SensitivityFunctionType> fts = List.of(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1,
                SensitivityFunctionType.BRANCH_CURRENT_1);
        List<String> branches = List.of("L1-2-1", "L2-3-1", "L1-5-1");
        List<List<String>> functionsPerType = List.of(branches, branches);
        SensitivityVariableType vt = SensitivityVariableType.BRANCH_ADMITTANCE;
        List<String> variables = List.of("L2-3-1", "L2-4-1"); // L2-3-1 monitored (self), L2-4-1 not (group-guarantee)

        Map<String, Double> cot = new HashMap<>();
        double[] wp = {0.7, -1.3, 0.4};
        double[] wi = {0.5, 0.9, -0.2};
        for (int i = 0; i < branches.size(); i++) {
            cot.put(AcSensitivityAnalysis.functionCotangentKey(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, branches.get(i)), wp[i]);
            cot.put(AcSensitivityAnalysis.functionCotangentKey(SensitivityFunctionType.BRANCH_CURRENT_1, branches.get(i)), wi[i]);
        }

        List<SensitivityFactor> full = new ArrayList<>();
        for (String v : variables) {
            for (int t = 0; t < fts.size(); t++) {
                for (String f : functionsPerType.get(t)) {
                    full.add(new SensitivityFactor(fts.get(t), f, vt, v, false, ContingencyContext.all()));
                }
            }
        }
        List<SensitivityFactor> minimal = minimalAdjointFactorsMulti(fts, functionsPerType, vt, variables);
        assertTrue(minimal.size() < full.size(), "minimal set must be smaller than the cross product");

        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(lfp);
        AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);
        String variantId = network.getVariantManager().getWorkingVariantId();
        Map<String, Double> thetaFull = analysis.runAdjoint(network, variantId, List.of(), full, cot);
        Map<String, Double> thetaMin = analysis.runAdjoint(network, variantId, List.of(), minimal, cot);
        for (String v : variables) {
            assertEquals(thetaFull.get(v), thetaMin.get(v), 1e-9 * (Math.abs(thetaFull.get(v)) + 1e-9),
                    "minimal multi-type factor set must match the full cross product for " + v);
        }
    }
}
