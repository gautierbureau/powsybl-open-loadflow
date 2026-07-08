/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.fastdc;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowEngine;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.FourBusNetworkFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.PhaseControlFactory;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
class LodfCalculatorTest {

    private static final double DELTA_LODF = 1e-6;

    private DcLoadFlowParameters dcParameters;

    @BeforeEach
    void setUp() {
        dcParameters = new DcLoadFlowParameters();
    }

    private Map<String, Double> calculateFlows(Network network) {
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst();
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            new DcLoadFlowEngine(context).run();
            Map<String, Double> flows = new HashMap<>();
            for (LfBranch branch : lfNetwork.getBranches()) {
                // note that contrary to calculateSensi, eval takes the constant phase shift of PSTs into account
                flows.put(branch.getId(), branch.getP1().eval());
            }
            return flows;
        }
    }

    /**
     * Checks the LODF matrix of all the given branches for all of their single outages against the reference value
     * (post-outage flow - pre-outage flow) / pre-outage flow of the outaged branch, with pre and post-outage flows
     * computed by DC load flows.
     */
    private void checkLodfMatrixAgainstPostOutageFlows(Supplier<Network> networkSupplier, List<String> branchIds) {
        Map<String, Double> preFlows = calculateFlows(networkSupplier.get());

        LfNetwork lfNetwork = LfNetwork.load(networkSupplier.get(), new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst();
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            List<LfBranch> branches = branchIds.stream().map(lfNetwork::getBranchById).toList();
            DenseMatrix lodfMatrix = LodfCalculator.computeLodfMatrix(context, branches, branches);
            for (int column = 0; column < branchIds.size(); column++) {
                String outagedBranchId = branchIds.get(column);
                Network postOutageNetwork = networkSupplier.get();
                Branch<?> outagedBranch = postOutageNetwork.getBranch(outagedBranchId);
                outagedBranch.getTerminal1().disconnect();
                outagedBranch.getTerminal2().disconnect();
                Map<String, Double> postFlows = calculateFlows(postOutageNetwork);
                for (int row = 0; row < branchIds.size(); row++) {
                    String monitoredBranchId = branchIds.get(row);
                    double expectedLodf = row == column ? -1d
                            : (postFlows.get(monitoredBranchId) - preFlows.get(monitoredBranchId)) / preFlows.get(outagedBranchId);
                    assertEquals(expectedLodf, lodfMatrix.get(row, column), DELTA_LODF,
                            "LODF of '" + monitoredBranchId + "' for outage of '" + outagedBranchId + "'");
                }
            }
        }
    }

    @ParameterizedTest(name = "vectorized={0}")
    @ValueSource(booleans = {false, true})
    void testAgainstPostOutageFlows(boolean vectorized) {
        dcParameters.setVectorized(vectorized);
        checkLodfMatrixAgainstPostOutageFlows(FourBusNetworkFactory::create, List.of("l14", "l12", "l23", "l34", "l13"));
    }

    @ParameterizedTest(name = "vectorized={0}")
    @ValueSource(booleans = {false, true})
    void testWithPhaseShift(boolean vectorized) {
        dcParameters.setVectorized(vectorized);
        // set the PST to a non-neutral tap so that its phase shift is non-zero
        Supplier<Network> networkSupplier = () -> {
            Network network = PhaseControlFactory.createWithOneT2wtTwoLines();
            network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(2);
            return network;
        };
        checkLodfMatrixAgainstPostOutageFlows(networkSupplier, List.of("L1", "L2", "PS1"));
    }

    @ParameterizedTest(name = "vectorized={0}")
    @ValueSource(booleans = {false, true})
    void testParallelLinesAndConnectivityBreak(boolean vectorized) {
        dcParameters.setVectorized(vectorized);
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst();
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            List<LfBranch> branches = List.of("NHV1_NHV2_1", "NHV1_NHV2_2", "NGEN_NHV1", "NHV2_NLOAD").stream()
                    .map(lfNetwork::getBranchById)
                    .toList();
            // no load flow has been run: LODF factors only depend on the network topology and impedances
            DenseMatrix lodfMatrix = LodfCalculator.computeLodfMatrix(context, branches, branches);

            // the outage of one of the two identical parallel lines transfers all of its flow to the other one
            assertEquals(1d, lodfMatrix.get(1, 0), DELTA_LODF);
            assertEquals(1d, lodfMatrix.get(0, 1), DELTA_LODF);
            // and does not change the flow of the transformers in antenna
            assertEquals(0d, lodfMatrix.get(2, 0), DELTA_LODF);
            assertEquals(0d, lodfMatrix.get(3, 0), DELTA_LODF);
            // an outaged branch loses all of its flow
            assertEquals(-1d, lodfMatrix.get(0, 0));
            assertEquals(-1d, lodfMatrix.get(1, 1));
            // the outages of the transformers break the network connectivity: LODF factors are undefined
            for (int row = 0; row < branches.size(); row++) {
                assertTrue(Double.isNaN(lodfMatrix.get(row, 2)));
                assertTrue(Double.isNaN(lodfMatrix.get(row, 3)));
            }
        }
    }

    @ParameterizedTest(name = "vectorized={0}")
    @ValueSource(booleans = {false, true})
    void testOpenAndZeroImpedanceBranches(boolean vectorized) {
        dcParameters.setVectorized(vectorized);
        Network network = FourBusNetworkFactory.create();
        network.getLine("l23").getTerminal1().disconnect();
        network.getLine("l13").setR(0).setX(0);
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst();
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            LfBranch l12 = lfNetwork.getBranchById("l12");
            LfBranch l14 = lfNetwork.getBranchById("l14");
            LfBranch l23 = lfNetwork.getBranchById("l23");
            LfBranch l13 = lfNetwork.getBranchById("l13");

            List<LfBranch> monitoredBranches = List.of(l12);
            PowsyblException e = assertThrows(PowsyblException.class,
                    () -> LodfCalculator.computeLodfMatrix(context, monitoredBranches, List.of(l23)));
            assertEquals("Branch 'l23' must be connected on both sides to compute LODF factors for its outage", e.getMessage());

            e = assertThrows(PowsyblException.class,
                    () -> LodfCalculator.computeLodfMatrix(context, monitoredBranches, List.of(l13)));
            assertEquals("LODF factors cannot be computed for the outage of zero impedance branch 'l13'", e.getMessage());

            // open and zero impedance branches can be monitored but have undefined LODF factors
            LfBranch l34 = lfNetwork.getBranchById("l34");
            DenseMatrix lodfMatrix = LodfCalculator.computeLodfMatrix(context, List.of(l34, l23, l13, l12), List.of(l14));
            // l34 is the only remaining path between the merged buses b1/b3 and b4: it recovers all the flow of l14
            assertEquals(1d, lodfMatrix.get(0, 0), DELTA_LODF);
            assertTrue(Double.isNaN(lodfMatrix.get(1, 0)));
            assertTrue(Double.isNaN(lodfMatrix.get(2, 0)));
            // l12 radially feeds b2, so its flow is not impacted by the outage of l14
            assertEquals(0d, lodfMatrix.get(3, 0), DELTA_LODF);
        }
    }

    @Test
    void testResultMatrixTooLarge() {
        // a monitored x outaged product exceeding the DenseMatrix element limit must fail with an explicit message,
        // not a cryptic matrix allocation error; use a stub list to reach the guard without allocating real branches
        Network network = FourBusNetworkFactory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst();
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            LfBranch l12 = lfNetwork.getBranchById("l12");
            List<LfBranch> monitored = Collections.nCopies(20000, l12);
            List<LfBranch> outaged = List.of(l12);
            // 20000 x 20000 = 400M > DenseMatrix.MAX_ELEMENT_COUNT (268435455)
            List<LfBranch> manyOutaged = Collections.nCopies(20000, l12);
            PowsyblException e = assertThrows(PowsyblException.class,
                    () -> LodfCalculator.computeLodfMatrix(context, monitored, manyOutaged));
            assertTrue(e.getMessage().contains("LODF matrix is too large"), e.getMessage());
            // a small enough matrix is fine
            assertEquals(20000, LodfCalculator.computeLodfMatrix(context, monitored, outaged).getRowCount());
        }
    }

    @ParameterizedTest(name = "vectorized={0}")
    @ValueSource(booleans = {false, true})
    void testOutageBatchingGivesSameResult(boolean vectorized) {
        // processing the outages in several small batches must give exactly the same result as a single batch
        dcParameters.setVectorized(vectorized);
        Network network = FourBusNetworkFactory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst();
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            List<LfBranch> branches = List.of("l14", "l12", "l23", "l34", "l13").stream()
                    .map(lfNetwork::getBranchById)
                    .toList();
            DenseMatrix reference = LodfCalculator.computeLodfMatrix(context, branches, branches);
            for (int batchSize : new int[] {1, 2, 3, branches.size()}) {
                DenseMatrix batched = new DenseMatrix(branches.size(), branches.size());
                LodfCalculator.computeLodf(context, branches, branches, batched::set, batchSize);
                for (int row = 0; row < reference.getRowCount(); row++) {
                    for (int column = 0; column < reference.getColumnCount(); column++) {
                        assertEquals(reference.get(row, column), batched.get(row, column), 0d,
                                "batchSize=" + batchSize + " row=" + row + " column=" + column);
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "vectorized={0}")
    @ValueSource(booleans = {false, true})
    void testStreamingGivesSameValuesAsMatrix(boolean vectorized) {
        // the streaming computeLodf must report exactly the same values as the materialized matrix, and must work
        // even when the (monitored x outaged) product would exceed the DenseMatrix element limit
        dcParameters.setVectorized(vectorized);
        Network network = FourBusNetworkFactory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst();
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            List<LfBranch> monitored = List.of("l14", "l12", "l23", "l34", "l13").stream().map(lfNetwork::getBranchById).toList();
            List<LfBranch> outaged = List.of("l12", "l34").stream().map(lfNetwork::getBranchById).toList();
            DenseMatrix reference = LodfCalculator.computeLodfMatrix(context, monitored, outaged);

            int[] writeCount = {0};
            LodfResultWriter writer = (monitoredIndex, outagedIndex, lodf) -> {
                assertEquals(reference.get(monitoredIndex, outagedIndex), lodf, 0d,
                        "row=" + monitoredIndex + " column=" + outagedIndex);
                writeCount[0]++;
            };
            LodfCalculator.computeLodf(context, monitored, outaged, writer);
            // every (monitored, outaged) pair must be reported exactly once
            assertEquals(monitored.size() * outaged.size(), writeCount[0]);
        }
    }
}
