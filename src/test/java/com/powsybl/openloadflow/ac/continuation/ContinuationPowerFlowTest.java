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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Claude
 */
@ExtendWith(ServiceParameterResolver.class)
class ContinuationPowerFlowTest {

    private final CommonTestConfig commonTestConfig;

    ContinuationPowerFlowTest(CommonTestConfig commonTestConfig) {
        this.commonTestConfig = commonTestConfig;
    }

    private OpenLoadFlowParameters createParametersExt(LoadFlowParameters parameters) {
        return OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.FIRST);
    }

    @Test
    void eurostagVoltageCollapseTest() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(true);
        OpenLoadFlowParameters parametersExt = createParametersExt(parameters);

        ContinuationPowerFlowParameters cpfParameters = new ContinuationPowerFlowParameters();
        ContinuationResult result = new ContinuationPowerFlow(cpfParameters)
                .run(network, parameters, parametersExt, commonTestConfig.matrixFactory(), LoadIncreaseDirection.allLoads());

        // the nose of the P-V curve is located
        assertSame(ContinuationResult.Status.NOSE_POINT_REACHED, result.getStatus());

        // there is a strictly positive loadability margin (we can add load beyond the base case)
        assertTrue(result.getMaxLoadFactor() > 0.5, "Expected a loadability margin > 0.5, got " + result.getMaxLoadFactor());

        // the curve has several points, all at increasing load factor
        List<ContinuationPoint> points = result.getPoints();
        assertTrue(points.size() > 3);
        assertEquals(0.0, points.get(0).loadFactor());
        for (int i = 1; i < points.size(); i++) {
            assertTrue(points.get(i).loadFactor() > points.get(i - 1).loadFactor());
        }

        // voltage drops as the load increases: the nose voltage is below the base case voltage
        double baseMinVoltage = points.get(0).minVoltage();
        ContinuationPoint nose = result.getNosePoint().orElseThrow();
        assertTrue(nose.minVoltage() < baseMinVoltage,
                "Expected nose voltage " + nose.minVoltage() + " below base voltage " + baseMinVoltage);

        // the weakest bus at collapse is the load bus (VLLOAD voltage level)
        assertTrue(result.getCriticalBusId().orElseThrow().contains("VLLOAD"));
    }

    @Test
    void noParticipatingLoadTest() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(true);
        OpenLoadFlowParameters parametersExt = createParametersExt(parameters);

        // select a subset that matches no load in the network
        ContinuationResult result = new ContinuationPowerFlow(new ContinuationPowerFlowParameters())
                .run(network, parameters, parametersExt, commonTestConfig.matrixFactory(),
                        LoadIncreaseDirection.ofLoadIds(Set.of("UNKNOWN")));

        assertSame(ContinuationResult.Status.NO_PARTICIPATING_LOAD, result.getStatus());
        assertEquals(0.0, result.getMaxLoadFactor());
        assertTrue(result.getPoints().isEmpty());
        assertFalse(result.getNosePoint().isPresent());
    }
}
