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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Claude
 */
@ExtendWith(ServiceParameterResolver.class)
class ContinuationAnalysisTest {

    private final CommonTestConfig commonTestConfig;

    ContinuationAnalysisTest(CommonTestConfig commonTestConfig) {
        this.commonTestConfig = commonTestConfig;
    }

    @Test
    void bothEnginesThroughFacadeTest() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(true);
        OpenLoadFlowParameters lfParametersExt = OpenLoadFlowParameters.create(lfParameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.FIRST);

        ContinuationResult stepped = ContinuationAnalysis.run(network, lfParameters, lfParametersExt,
                commonTestConfig.matrixFactory(),
                new ContinuationAnalysisParameters().setEngine(ContinuationAnalysisParameters.Engine.STEPPED));
        assertSame(ContinuationResult.Status.NOSE_POINT_REACHED, stepped.getStatus());
        assertTrue(stepped.getMaxLoadFactor() > 0.5);

        ContinuationResult predictorCorrector = ContinuationAnalysis.run(network, lfParameters, lfParametersExt,
                commonTestConfig.matrixFactory(),
                new ContinuationAnalysisParameters().setEngine(ContinuationAnalysisParameters.Engine.PREDICTOR_CORRECTOR));
        assertSame(ContinuationResult.Status.NOSE_POINT_REACHED, predictorCorrector.getStatus());

        // the predictor-corrector traces the lower branch, the stepped engine does not
        assertTrue(predictorCorrector.getPoints().stream().anyMatch(p -> !p.stable()));
        assertTrue(stepped.getPoints().stream().allMatch(ContinuationPoint::stable));

        // both engines find a comparable loadability margin on this network
        assertTrue(Math.abs(stepped.getMaxLoadFactor() - predictorCorrector.getMaxLoadFactor()) < 0.2,
                "stepped=" + stepped.getMaxLoadFactor() + ", predictorCorrector=" + predictorCorrector.getMaxLoadFactor());
    }

    @Test
    void defaultConfigurationEntryPointTest() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        ContinuationResult result = ContinuationAnalysis.run(network, new ContinuationAnalysisParameters());
        assertSame(ContinuationResult.Status.NOSE_POINT_REACHED, result.getStatus());
        assertTrue(result.getNosePoint().isPresent());
    }
}
