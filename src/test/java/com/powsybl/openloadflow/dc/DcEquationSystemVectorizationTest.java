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
import com.powsybl.openloadflow.util.MatpowerCaseLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /**
     * On the large MATPOWER Pégase cases, checks the vectorized and scalar DC load flows agree and
     * reports the wall-clock speedup. Enabled only when {@code pegase.dir} points at the {@code .m} files.
     */
    @Test
    @EnabledIfSystemProperty(named = "pegase.dir", matches = ".+")
    void benchmarkVectorizedOnPegase() {
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new com.powsybl.math.matrix.SparseMatrixFactory()));
        Path dir = Paths.get(System.getProperty("pegase.dir"));
        System.out.printf("%n%-22s %10s %12s %12s %9s%n", "case", "buses", "scalar(ms)", "vector(ms)", "speedup");
        for (String caseName : new String[] {"case1354pegase.m", "case2869pegase.m", "case9241pegase.m", "case13659pegase.m"}) {
            Path file = dir.resolve(caseName);
            if (!Files.exists(file)) {
                continue;
            }
            Network network = MatpowerCaseLoader.readDotM(file);
            Map<String, double[]> scalar = runAndCollectFlows(network, false);
            Map<String, double[]> vectorized = runAndCollectFlows(network, true);
            for (Map.Entry<String, double[]> e : scalar.entrySet()) {
                assertArrayEquals(e.getValue(), vectorized.get(e.getKey()), 1e-6, "flow mismatch on " + e.getKey());
            }
            double scalarMs = timeMedian(network, false);
            double vectorMs = timeMedian(network, true);
            System.out.printf("%-22s %10d %12.1f %12.1f %9.2f%n",
                    caseName, network.getBusView().getBusStream().mapToInt(b -> 1).sum(),
                    scalarMs, vectorMs, scalarMs / vectorMs);
        }
    }

    private double timeMedian(Network network, boolean vectorized) {
        boolean previous = DcLoadFlowParameters.vectorizedDefaultValue;
        DcLoadFlowParameters.vectorizedDefaultValue = vectorized;
        try {
            for (int i = 0; i < 3; i++) {
                loadFlowRunner.run(network, parameters);
            }
            double[] times = new double[9];
            for (int i = 0; i < times.length; i++) {
                long t0 = System.nanoTime();
                loadFlowRunner.run(network, parameters);
                times[i] = (System.nanoTime() - t0) / 1_000_000.0;
            }
            java.util.Arrays.sort(times);
            return times[times.length / 2];
        } finally {
            DcLoadFlowParameters.vectorizedDefaultValue = previous;
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
