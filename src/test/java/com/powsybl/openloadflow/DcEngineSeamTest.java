/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowResult;
import com.powsybl.openloadflow.dc.DcOuterLoopContext;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.MostMeshedSlackBusSelector;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an alternative DC engine plugs into: the provider's DC engine seam and the DC outer loop context.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class DcEngineSeamTest {

    @Test
    void dcLoadFlowRunsTheProviderDcEngine() {
        List<LoadFlowParameters> seenParameters = new ArrayList<>();
        OpenLoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory()) {
            @Override
            protected List<DcLoadFlowResult> runDcEngine(Network network, LoadFlowParameters parameters, DcLoadFlowParameters dcParameters,
                                                         ReportNode reportNode) {
                seenParameters.add(parameters);
                return super.runDcEngine(network, parameters, dcParameters, reportNode);
            }
        };
        LoadFlowParameters parameters = new LoadFlowParameters().setDc(true);

        Network network = EurostagTutorialExample1Factory.create();
        LoadFlowResult result = new LoadFlow.Runner(provider).run(network, parameters);
        Network reference = EurostagTutorialExample1Factory.create();
        new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory())).run(reference, parameters);

        // the seam runs once, with the run's own parameters, and gives the default engine's results
        assertTrue(result.isFullyConverged());
        assertEquals(1, seenParameters.size());
        assertSame(parameters, seenParameters.getFirst());
        assertEquals(reference.getLine("NHV1_NHV2_1").getTerminal1().getP(), network.getLine("NHV1_NHV2_1").getTerminal1().getP(), 0);
    }

    @Test
    void dcOuterLoopContextIsCreatedOutsideItsPackage() {
        LfNetwork lfNetwork = Networks.load(EurostagTutorialExample1Factory.create(), new MostMeshedSlackBusSelector()).getFirst();
        assertSame(lfNetwork, new DcOuterLoopContext(lfNetwork).getNetwork());
    }
}
