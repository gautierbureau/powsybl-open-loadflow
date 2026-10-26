/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.outerloop.AcOuterLoop;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.MostMeshedSlackBusSelector;
import com.powsybl.openloadflow.network.impl.Networks;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What an engine extending {@link AcloadFlowEngine} can decide for its runs.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AcloadFlowEngineExtensionTest {

    private final Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

    private AcLoadFlowContext createContext() {
        LfNetwork lfNetwork = Networks.load(network, new MostMeshedSlackBusSelector()).getFirst();
        LoadFlowParameters parameters = new LoadFlowParameters();
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, OpenLoadFlowParameters.create(parameters),
                new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
        return new AcLoadFlowContext(lfNetwork, acParameters);
    }

    @Test
    void testEngineChoosesTheOuterLoopsItDrives() {
        try (AcLoadFlowContext context = createContext()) {
            AcLoadFlowResult result = new AcloadFlowEngine(context).run();
            assertTrue(result.isSuccess());
            assertNotEquals(0, result.getDistributedActivePower(), "the default engine distributes the slack");
        }
        try (AcLoadFlowContext context = createContext()) {
            AcLoadFlowResult result = new AcloadFlowEngine(context) {
                @Override
                protected List<Pair<AcOuterLoop, AcOuterLoopContext>> engineOuterLoopsAndContexts(AcLoadFlowContext context) {
                    return List.of();
                }
            }.run();
            assertTrue(result.isSuccess());
            assertEquals(0, result.getOuterLoopIterations());
            assertEquals(0, result.getDistributedActivePower());
        }
    }
}
