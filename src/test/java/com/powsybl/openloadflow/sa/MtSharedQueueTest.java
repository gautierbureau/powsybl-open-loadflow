/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.action.Action;
import com.powsybl.action.SwitchAction;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.LoadContingency;
import com.powsybl.contingency.strategy.OperatorStrategy;
import com.powsybl.contingency.strategy.condition.TrueCondition;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.network.FourBusNetworkFactory;
import com.powsybl.openloadflow.network.NodeBreakerNetworkFactory;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.BusResult;
import com.powsybl.security.results.OperatorStrategyResult;
import com.powsybl.security.results.PostContingencyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.StringWriter;
import java.util.List;

import static com.powsybl.openloadflow.sa.OpenSecurityAnalysisParameters.ContingencyPartitioningMode.ROUND_ROBIN;
import static com.powsybl.openloadflow.sa.OpenSecurityAnalysisParameters.ContingencyPartitioningMode.SHARED_QUEUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The SHARED_QUEUE contingency partitioning mode distributes contingencies to threads dynamically: every
 * thread pulls its next contingency from a shared queue and reuses one load flow context (and its LU
 * factorization) across the contingencies it happens to process. Because every thread simulates a copy of
 * the very same network as the single-threaded analysis, the results and reports must be identical to
 * single-thread mode (and to the static partitioning modes) whatever the thread count. When several
 * components are simulated, a worker owns one context per component and simulates each contingency it pulls
 * on all of them. DC analyses fall back to ROUND_ROBIN.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class MtSharedQueueTest extends AbstractOpenSecurityAnalysisTest {

    MtSharedQueueTest(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    private SecurityAnalysisResult run(Network network, List<Contingency> contingencies, int threadCount, boolean dc,
                                       OpenSecurityAnalysisParameters.ContingencyPartitioningMode mode, ReportNode reportNode) {
        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.setLoadFlowParameters(new LoadFlowParameters().setDc(dc));
        OpenSecurityAnalysisParameters saExt = new OpenSecurityAnalysisParameters()
                .setThreadCount(threadCount)
                .setContingencyPartitioningMode(mode);
        saParameters.addExtension(OpenSecurityAnalysisParameters.class, saExt);
        return runSecurityAnalysis(network, contingencies, createNetworkMonitors(network), saParameters, reportNode);
    }

    private static void assertSameResults(SecurityAnalysisResult expected, SecurityAnalysisResult actual) {
        for (BranchResult branchResult : expected.getPreContingencyResult().getNetworkResult().getBranchResults()) {
            BranchResult actualBranchResult = actual.getPreContingencyResult().getNetworkResult().getBranchResult(branchResult.getBranchId());
            assertEquals(branchResult.getP1(), actualBranchResult.getP1(), 0, "pre-contingency P1 mismatch on " + branchResult.getBranchId());
        }
        assertEquals(expected.getPostContingencyResults().size(), actual.getPostContingencyResults().size());
        // results must come back in the contingency list order despite the dynamic distribution
        assertEquals(expected.getPostContingencyResults().stream().map(r -> r.getContingency().getId()).toList(),
                actual.getPostContingencyResults().stream().map(r -> r.getContingency().getId()).toList());
        for (PostContingencyResult expectedPcr : expected.getPostContingencyResults()) {
            PostContingencyResult actualPcr = actual.getPostContingencyResults().stream()
                    .filter(r -> r.getContingency().getId().equals(expectedPcr.getContingency().getId()))
                    .findFirst().orElseThrow();
            assertEquals(expectedPcr.getStatus(), actualPcr.getStatus());
            for (BranchResult branchResult : expectedPcr.getNetworkResult().getBranchResults()) {
                BranchResult actualBranchResult = actualPcr.getNetworkResult().getBranchResult(branchResult.getBranchId());
                assertEquals(branchResult.getP1(), actualBranchResult.getP1(), 0,
                        "post-contingency P1 mismatch on " + branchResult.getBranchId() + " for " + expectedPcr.getContingency().getId());
            }
            assertEquals(expectedPcr.getNetworkResult().getBusResults().stream().map(BusResult::getBusId).sorted().toList(),
                    actualPcr.getNetworkResult().getBusResults().stream().map(BusResult::getBusId).sorted().toList(),
                    "bus result ids mismatch for " + expectedPcr.getContingency().getId());
        }
    }

    @ParameterizedTest(name = "threads={0}")
    @CsvSource({"2", "3", "4"})
    void testSharedQueueResultsIdenticalToSingleThread(int threadCount) {
        Network network = createNodeBreakerNetwork();
        List<Contingency> contingencies = List.of(
                new Contingency("L1", new BranchContingency("L1")),
                new Contingency("L2", new BranchContingency("L2")),
                new Contingency("LD", new LoadContingency("LD")));

        SecurityAnalysisResult singleThread = run(network, contingencies, 1, false, SHARED_QUEUE, ReportNode.NO_OP);
        SecurityAnalysisResult queue = run(network, contingencies, threadCount, false, SHARED_QUEUE, ReportNode.NO_OP);
        assertSameResults(singleThread, queue);

        // and identical to the static round-robin partitioning as well
        SecurityAnalysisResult roundRobin = run(network, contingencies, threadCount, false, ROUND_ROBIN, ReportNode.NO_OP);
        assertSameResults(roundRobin, queue);
    }

    @Test
    void testSharedQueueReportIdenticalToSingleThread() throws java.io.IOException {
        Network network = createNodeBreakerNetwork();
        List<Contingency> contingencies = List.of(
                new Contingency("L1", new BranchContingency("L1")),
                new Contingency("L2", new BranchContingency("L2")),
                new Contingency("LD", new LoadContingency("LD")));

        ReportNode singleThreadReport = newRootReportNode();
        run(network, contingencies, 1, false, SHARED_QUEUE, singleThreadReport);
        ReportNode multiThreadReport = newRootReportNode();
        run(network, contingencies, 2, false, SHARED_QUEUE, multiThreadReport);

        StringWriter singleThreadWriter = new StringWriter();
        singleThreadReport.print(singleThreadWriter);
        StringWriter multiThreadWriter = new StringWriter();
        multiThreadReport.print(multiThreadWriter);
        assertEquals(singleThreadWriter.toString(), multiThreadWriter.toString(),
                "multi-thread shared-queue report should be identical to the single-thread one");
    }

    @Test
    void testSharedQueueOperatorStrategiesIdenticalToSingleThread() {
        Network network = NodeBreakerNetworkFactory.create3Bars();
        network.getSwitch("C1").setOpen(true);
        network.getSwitch("C2").setOpen(true);

        List<Contingency> contingencies = List.of(
                new Contingency("L1", new BranchContingency("L1")),
                new Contingency("L3", new BranchContingency("L3")),
                new Contingency("L2", new BranchContingency("L2")));
        List<Action> actions = List.of(new SwitchAction("action1", "C1", false), new SwitchAction("action3", "C2", false));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyL1", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("action1")),
                new OperatorStrategy("strategyL3", ContingencyContext.specificContingency("L3"), new TrueCondition(), List.of("action3")),
                new OperatorStrategy("strategyL2", ContingencyContext.specificContingency("L2"), new TrueCondition(), List.of("action1", "action3")));

        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(false);
        setSlackBusId(parameters, "VL2_0");

        SecurityAnalysisResult single = runStrategies(network, contingencies, operatorStrategies, actions, parameters, 1);
        SecurityAnalysisResult queue = runStrategies(network, contingencies, operatorStrategies, actions, parameters, 2);

        assertSameResults(single, queue);
        assertEquals(single.getOperatorStrategyResults().size(), queue.getOperatorStrategyResults().size());
        for (OperatorStrategyResult expected : single.getOperatorStrategyResults()) {
            OperatorStrategyResult actual = queue.getOperatorStrategyResults().stream()
                    .filter(r -> r.getOperatorStrategy().getId().equals(expected.getOperatorStrategy().getId()))
                    .findFirst().orElseThrow();
            for (BranchResult branchResult : expected.getNetworkResult().getBranchResults()) {
                assertEquals(branchResult.getI1(), actual.getNetworkResult().getBranchResult(branchResult.getBranchId()).getI1(), 0,
                        "operator strategy " + expected.getOperatorStrategy().getId() + " I1 mismatch on " + branchResult.getBranchId());
            }
        }
    }

    @Test
    void testDcFallsBackToRoundRobin() {
        // DC uses the Woodbury path; SHARED_QUEUE is AC-only, so a DC request must fall back and match
        // the single-threaded DC result
        Network network = createNodeBreakerNetwork();
        List<Contingency> contingencies = List.of(
                new Contingency("L1", new BranchContingency("L1")),
                new Contingency("L2", new BranchContingency("L2")),
                new Contingency("LD", new LoadContingency("LD")));

        SecurityAnalysisResult singleThread = run(network, contingencies, 1, true, SHARED_QUEUE, ReportNode.NO_OP);
        SecurityAnalysisResult multiThread = run(network, contingencies, 2, true, SHARED_QUEUE, ReportNode.NO_OP);
        assertSameResults(singleThread, multiThread);
    }

    @ParameterizedTest(name = "threads={0}")
    @CsvSource({"2", "3"})
    void testMultiComponentSharedQueue(int threadCount) throws java.io.IOException {
        // several simulated components: each worker owns one context per component and simulates every
        // contingency it pulls on all of them, merging the per-component results in component order. Results
        // and report must stay identical to the single-threaded run.
        Network network = FourBusNetworkFactory.createWithTwoScs();
        network.getBusBreakerView().getBus("c1").getVoltageLevel().newLoad()
                .setId("dummyLoad")
                .setBus("c1")
                .setConnectableBus("c1")
                .setP0(1)
                .setQ0(0)
                .add();
        List<Contingency> contingencies = List.of(
                new Contingency("l13", new BranchContingency("l13")),
                new Contingency("l14", new BranchContingency("l14")),
                new Contingency("dummyLoad", new LoadContingency("dummyLoad")));

        ReportNode singleThreadReport = newRootReportNode();
        SecurityAnalysisResult singleThread = runAllComponents(network, contingencies, 1, singleThreadReport);
        ReportNode multiThreadReport = newRootReportNode();
        SecurityAnalysisResult multiThread = runAllComponents(network, contingencies, threadCount, multiThreadReport);

        assertSameResults(singleThread, multiThread);
        // the violations of all the components must be there, merged per contingency
        assertEquals(singleThread.getPostContingencyResults().stream()
                        .map(r -> r.getLimitViolationsResult().getLimitViolations().size()).toList(),
                multiThread.getPostContingencyResults().stream()
                        .map(r -> r.getLimitViolationsResult().getLimitViolations().size()).toList());

        StringWriter singleThreadWriter = new StringWriter();
        singleThreadReport.print(singleThreadWriter);
        StringWriter multiThreadWriter = new StringWriter();
        multiThreadReport.print(multiThreadWriter);
        assertEquals(singleThreadWriter.toString(), multiThreadWriter.toString(),
                "multi-thread report should be identical to the single-thread one");
    }

    private SecurityAnalysisResult runAllComponents(Network network, List<Contingency> contingencies, int threadCount, ReportNode reportNode) {
        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.setLoadFlowParameters(new LoadFlowParameters().setComponentMode(LoadFlowParameters.ComponentMode.ALL_CONNECTED));
        saParameters.addExtension(OpenSecurityAnalysisParameters.class,
                new OpenSecurityAnalysisParameters().setThreadCount(threadCount).setContingencyPartitioningMode(SHARED_QUEUE));
        return runSecurityAnalysis(network, contingencies, createNetworkMonitors(network), saParameters, reportNode);
    }

    private SecurityAnalysisResult runStrategies(Network network, List<Contingency> contingencies, List<OperatorStrategy> operatorStrategies,
                                                 List<Action> actions, LoadFlowParameters parameters, int threadCount) {
        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.setLoadFlowParameters(parameters);
        saParameters.addExtension(OpenSecurityAnalysisParameters.class,
                new OpenSecurityAnalysisParameters().setThreadCount(threadCount).setContingencyPartitioningMode(SHARED_QUEUE));
        return runSecurityAnalysis(network, contingencies, createAllBranchesMonitors(network), saParameters,
                operatorStrategies, actions, ReportNode.NO_OP);
    }

    private static ReportNode newRootReportNode() {
        return ReportNode.newRootReportNode()
                .withAllResourceBundlesFromClasspath()
                .withMessageTemplate("olf.threadRoot")
                .build();
    }
}
