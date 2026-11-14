/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc;

import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.PhaseShifterTestCaseFactory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.FourBusNetworkFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the vectorized DC equation system produces numerically identical results to the scalar
 * one (the DC system is linear, so the two paths must agree to solver precision).
 *
 * @author Claude
 */
class DcEquationSystemVectorizationTest {

    private LoadFlow.Runner loadFlowRunner;
    private LoadFlowParameters parameters;

    @BeforeEach
    void setUp() {
        parameters = new LoadFlowParameters()
                .setDc(true)
                .setDistributedSlack(true);
        OpenLoadFlowParameters.create(parameters);
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
    }

    static Stream<Arguments> networks() {
        return Stream.of(
                Arguments.of("eurostag", (Supplier<Network>) () -> EurostagFactory.fix(EurostagTutorialExample1Factory.create())),
                Arguments.of("fourBus", (Supplier<Network>) FourBusNetworkFactory::create),
                Arguments.of("fourBusWithPhaseShifter", (Supplier<Network>) FourBusNetworkFactory::createWithPhaseTapChanger),
                Arguments.of("phaseShifter", (Supplier<Network>) PhaseShifterTestCaseFactory::create)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("networks")
    void vectorizedMatchesScalar(String name, Supplier<Network> networkSupplier) {
        Map<String, double[]> scalar = runAndCollectFlows(networkSupplier.get(), false);
        Map<String, double[]> vectorized = runAndCollectFlows(networkSupplier.get(), true);

        assertEquals(scalar.keySet(), vectorized.keySet(), "branch set differs for " + name);
        for (Map.Entry<String, double[]> entry : scalar.entrySet()) {
            assertArrayEquals(entry.getValue(), vectorized.get(entry.getKey()), 1e-9,
                    "flow mismatch on branch " + entry.getKey() + " for " + name);
        }
    }

    private Map<String, double[]> runAndCollectFlows(Network network, boolean vectorized) {
        boolean previous = DcLoadFlowParameters.vectorizedDefaultValue;
        DcLoadFlowParameters.vectorizedDefaultValue = vectorized;
        try {
            LoadFlowResult result = loadFlowRunner.run(network, parameters);
            assertTrue(result.isFullyConverged());
            Map<String, double[]> flows = new LinkedHashMap<>();
            for (Branch<?> branch : network.getBranches()) {
                flows.put(branch.getId(), new double[] {
                    branch.getTerminal1().getP(),
                    branch.getTerminal2().getP()
                });
            }
            return flows;
        } finally {
            DcLoadFlowParameters.vectorizedDefaultValue = previous;
        }
    }
}
