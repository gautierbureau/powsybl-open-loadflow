/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.MostMeshedSlackBusSelector;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A solver factory decides the engine that runs a load flow with it.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AcSolverFactoryEngineTest {

    @Test
    void loadFlowRunsTheEngineOfTheSolverFactory() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        List<LfNetwork> lfNetworks = Networks.load(network, new MostMeshedSlackBusSelector());
        LoadFlowParameters parameters = new LoadFlowParameters();
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters,
                OpenLoadFlowParameters.create(parameters), new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
        AtomicInteger enginesRun = new AtomicInteger();
        acParameters.setSolverFactory(new NewtonRaphsonFactory() {
            @Override
            public AcloadFlowEngine createLoadFlowEngine(AcLoadFlowContext context) {
                return new AcloadFlowEngine(context) {
                    @Override
                    public AcLoadFlowResult run() {
                        enginesRun.incrementAndGet();
                        return super.run();
                    }
                };
            }
        }, parameters);

        List<AcLoadFlowResult> results = AcloadFlowEngine.run(lfNetworks, acParameters);

        assertEquals(1, enginesRun.get());
        assertTrue(results.get(0).isSuccess());
    }
}
