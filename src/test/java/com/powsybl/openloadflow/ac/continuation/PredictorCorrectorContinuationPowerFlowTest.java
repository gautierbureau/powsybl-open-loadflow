/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ServiceParameterResolver;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.SlackBusSelectionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Claude
 */
@ExtendWith(ServiceParameterResolver.class)
class PredictorCorrectorContinuationPowerFlowTest {

    private final CommonTestConfig commonTestConfig;

    PredictorCorrectorContinuationPowerFlowTest(CommonTestConfig commonTestConfig) {
        this.commonTestConfig = commonTestConfig;
    }

    @Test
    void eurostagTraceThroughNoseTest() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.FIRST);

        PredictorCorrectorParameters cpfParameters = new PredictorCorrectorParameters();
        ContinuationResult result = new PredictorCorrectorContinuationPowerFlow(cpfParameters)
                .run(network, parameters, parametersExt, commonTestConfig.matrixFactory(), LoadIncreaseDirection.allLoads());

        // the nose was located
        assertSame(ContinuationResult.Status.NOSE_POINT_REACHED, result.getStatus());
        assertTrue(result.getMaxLoadFactor() > 0.5, "Expected a loadability margin > 0.5, got " + result.getMaxLoadFactor());

        List<ContinuationPoint> points = result.getPoints();
        assertEquals(0.0, points.get(0).loadFactor());

        // the nose is the point of maximum load factor
        double maxLambda = points.stream().mapToDouble(ContinuationPoint::loadFactor).max().orElseThrow();
        assertEquals(maxLambda, result.getMaxLoadFactor(), 1e-9);
        ContinuationPoint nose = result.getNosePoint().orElseThrow();
        assertEquals(maxLambda, nose.loadFactor(), 1e-9);

        // the continuation passed through the nose: there are points on the lower (unstable) branch
        long stableCount = points.stream().filter(ContinuationPoint::stable).count();
        long unstableCount = points.stream().filter(p -> !p.stable()).count();
        assertTrue(stableCount > 1, "Expected several stable points, got " + stableCount);
        assertTrue(unstableCount > 0, "Expected the lower branch to be traced, got " + unstableCount + " unstable points");

        // for a load factor reachable on both branches, the unstable point has a lower voltage than the stable one
        double probeLambda = 0.5 * maxLambda;
        double upperV = branchVoltageAt(points, probeLambda, true);
        double lowerV = branchVoltageAt(points, probeLambda, false);
        assertTrue(lowerV < upperV, "Lower branch voltage " + lowerV + " should be below upper branch voltage " + upperV);

        // the collapse is driven by the load bus, with the highest tangent voltage participation
        assertTrue(result.getCriticalBusId().orElseThrow().contains("VLLOAD"));
        assertEquals(1.0, result.getTangentParticipationByBus().get(result.getCriticalBusId().orElseThrow()), 1e-9);
    }

    private static double branchVoltageAt(List<ContinuationPoint> points, double lambda, boolean stable) {
        return points.stream()
                .filter(p -> p.stable() == stable)
                .min((a, b) -> Double.compare(Math.abs(a.loadFactor() - lambda), Math.abs(b.loadFactor() - lambda)))
                .orElseThrow()
                .minVoltage();
    }

    @Test
    void perBusCurveAndSensitivityTest() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.FIRST);

        ContinuationResult result = new PredictorCorrectorContinuationPowerFlow(new PredictorCorrectorParameters())
                .run(network, parameters, parametersExt, commonTestConfig.matrixFactory(), LoadIncreaseDirection.allLoads());

        // per-bus voltages recorded for every bus at every point
        assertTrue(result.getMonitoredBusIds().contains("VLLOAD_0"));

        // the load bus P-V curve has one entry per continuation point
        List<PvCurvePoint> curve = result.getPvCurve("VLLOAD_0");
        assertEquals(result.getPoints().size(), curve.size());
        assertEquals(0.0, curve.get(0).loadFactor());

        // voltage decreases monotonically along the traced curve (upper branch then lower branch)
        for (int i = 1; i < curve.size(); i++) {
            assertTrue(curve.get(i).voltage() < curve.get(i - 1).voltage());
        }

        // dV/dlambda magnitude blows up near the nose: a voltage collapse proximity indicator
        double maxAbsSlope = curve.stream()
                .map(PvCurvePoint::dvDlambda)
                .filter(Double::isFinite)
                .mapToDouble(Math::abs)
                .max().orElseThrow();
        assertTrue(maxAbsSlope > 10.0, "dV/dlambda should diverge near the nose, got " + maxAbsSlope);
    }

    @Test
    void generationParticipationChangesNoseTest() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        // a local generator at the load bus, whose ramp-up can offset the local load increase
        network.getVoltageLevel("VLLOAD").newGenerator()
                .setId("GLOAD").setBus("NLOAD").setConnectableBus("NLOAD")
                .setMinP(0).setMaxP(2000).setTargetP(200).setTargetQ(0)
                .setVoltageRegulatorOn(false)
                .add();

        LoadFlowParameters parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.FIRST);

        PredictorCorrectorContinuationPowerFlow cpf =
                new PredictorCorrectorContinuationPowerFlow(new PredictorCorrectorParameters());

        // slack-only: the single slack bus absorbs the whole load increase
        ContinuationResult slackOnly = cpf.run(network, parameters, parametersExt, commonTestConfig.matrixFactory(),
                LoadIncreaseDirection.allLoads(), GenerationParticipation.none());

        // the local generator ramps up alongside the load, relieving the stressed corridor
        ContinuationResult withGeneration = cpf.run(network, parameters, parametersExt, commonTestConfig.matrixFactory(),
                LoadIncreaseDirection.allLoads(), GenerationParticipation.ofGeneratorIds(Set.of("GLOAD")));

        assertSame(ContinuationResult.Status.NOSE_POINT_REACHED, slackOnly.getStatus());
        assertSame(ContinuationResult.Status.NOSE_POINT_REACHED, withGeneration.getStatus());

        // local generation participation pushes the collapse point to a higher load factor
        assertTrue(withGeneration.getMaxLoadFactor() > slackOnly.getMaxLoadFactor() + 0.1,
                "Expected the nose to move up with local generation: slackOnly=" + slackOnly.getMaxLoadFactor()
                        + ", withGeneration=" + withGeneration.getMaxLoadFactor());
    }

    @Test
    void noParticipatingLoadTest() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.FIRST);

        ContinuationResult result = new PredictorCorrectorContinuationPowerFlow(new PredictorCorrectorParameters())
                .run(network, parameters, parametersExt, commonTestConfig.matrixFactory(),
                        LoadIncreaseDirection.ofLoadIds(java.util.Set.of("UNKNOWN")));

        assertSame(ContinuationResult.Status.NO_PARTICIPATING_LOAD, result.getStatus());
    }
}
