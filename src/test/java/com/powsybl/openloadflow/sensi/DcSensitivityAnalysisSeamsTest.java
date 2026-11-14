/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.dc.fastdc.ComputedContingencyElement;
import com.powsybl.openloadflow.dc.fastdc.ComputedElement;
import com.powsybl.openloadflow.dc.fastdc.WoodburyEngine;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.FourBusNetworkFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import com.powsybl.sensitivity.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an alternative DC sensitivity engine plugs into: the provider's DC analysis factory and the analysis's Woodbury
 * engine factory.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class DcSensitivityAnalysisSeamsTest extends AbstractSensitivityAnalysisTest {

    DcSensitivityAnalysisSeamsTest(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    @Test
    void dcSensitivityAnalysisRunsThroughItsFactories() {
        AtomicInteger analysesCreated = new AtomicInteger();
        AtomicInteger enginesCreated = new AtomicInteger();
        OpenSensitivityAnalysisProvider provider = new OpenSensitivityAnalysisProvider(commonTestConfig.matrixFactory()) {
            @Override
            protected AbstractSensitivityAnalysis<?, ?> createDcSensitivityAnalysis(GraphConnectivityFactory<LfBus, LfBranch> selectedConnectivityFactory,
                                                                                    SensitivityAnalysisParameters sensitivityAnalysisParameters) {
                analysesCreated.incrementAndGet();
                return new DcSensitivityAnalysis(commonTestConfig.matrixFactory(), selectedConnectivityFactory, sensitivityAnalysisParameters) {
                    @Override
                    protected WoodburyEngine createWoodburyEngine(DcEquationSystemCreationParameters creationParameters,
                                                                  List<ComputedContingencyElement> contingencyElements,
                                                                  DenseMatrix contingenciesStates,
                                                                  List<ComputedElement> actionElements,
                                                                  DenseMatrix actionsStates) {
                        enginesCreated.incrementAndGet();
                        return super.createWoodburyEngine(creationParameters, contingencyElements, contingenciesStates, actionElements, actionsStates);
                    }
                };
            }
        };

        Network network = FourBusNetworkFactory.create();
        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        List<SensitivityFactor> factors = createFactorMatrix(List.of(network.getGenerator("g2")),
                                                             network.getBranchStream().collect(Collectors.toList()));
        SensitivityAnalysisRunParameters runParameters = new SensitivityAnalysisRunParameters()
                .setContingencies(List.of(new Contingency("l23", new BranchContingency("l23"))))
                .setParameters(sensiParameters);
        SensitivityAnalysisResult result = new SensitivityAnalysis.Runner(provider).run(network, factors, runParameters);
        SensitivityAnalysisResult reference = sensiRunner.run(network, factors, runParameters);

        // the provider's factory gives the analysis, whose factory gives the engine of the post-contingency states
        assertEquals(1, analysesCreated.get());
        assertTrue(enginesCreated.get() > 0);
        for (String branchId : List.of("l12", "l13", "l14", "l23", "l34")) {
            assertEquals(reference.getBranchFlow1SensitivityValue("g2", branchId, SensitivityVariableType.INJECTION_ACTIVE_POWER),
                    result.getBranchFlow1SensitivityValue("g2", branchId, SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
            assertEquals(reference.getBranchFlow1SensitivityValue("l23", "g2", branchId, SensitivityVariableType.INJECTION_ACTIVE_POWER),
                    result.getBranchFlow1SensitivityValue("l23", "g2", branchId, SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        }
    }
}
