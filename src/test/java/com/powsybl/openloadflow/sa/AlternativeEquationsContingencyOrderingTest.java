/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.BusResult;
import com.powsybl.security.results.NetworkResult;
import com.powsybl.security.results.PostContingencyResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With alternative equations on, a security analysis reorders contingencies so that those preserving the matrix
 * structure are simulated first (sharing a single symbolic factorization) and those forcing a full structure rebuild
 * are simulated last, so the base structure construction is paid once (see {@code AbstractSecurityAnalysis}).
 *
 * <p>This test builds a network with both flavours: tripping the three windings transformer {@code 3wt} removes its
 * fictitious star bus, which stays on the legacy modeling and therefore forces a full rebuild (a "fallback"); tripping
 * the line {@code l14} islands the eligible generator bus {@code b4}, which the alternative modeling keeps with a
 * trivial disabled equation (structure-preserving). The fallback contingency is listed first on input so that the
 * internal reordering genuinely permutes the execution order, letting us check that (a) results still match the legacy
 * modeling and (b) the post-contingency results are handed back in the original input order, not the execution order.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
class AlternativeEquationsContingencyOrderingTest extends AbstractOpenSecurityAnalysisTest {

    AlternativeEquationsContingencyOrderingTest(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    /**
     * A slack bus b1 feeding, through a three windings transformer, two load buses b2 (225 kV) and b3 (20 kV), plus a
     * remote generator bus b4 connected to b1 by a single line l14. Tripping 3wt islands b2, b3 and the fictitious star
     * bus (fallback); tripping l14 islands b4 with its generator (structure-preserving).
     */
    private static Network createNetworkWithFallbackAndStructurePreservingContingencies() {
        Network network = Network.create("alt-eq-ordering", "test");

        Substation s = network.newSubstation()
                .setId("s")
                .add();

        VoltageLevel vl1 = s.newVoltageLevel()
                .setId("vl1")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl1.getBusBreakerView().newBus()
                .setId("b1")
                .add();
        vl1.newGenerator()
                .setId("g1")
                .setConnectableBus("b1")
                .setBus("b1")
                .setTargetP(100)
                .setTargetV(405)
                .setMinP(0)
                .setMaxP(500)
                .setVoltageRegulatorOn(true)
                .add();

        VoltageLevel vl2 = s.newVoltageLevel()
                .setId("vl2")
                .setNominalV(225)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl2.getBusBreakerView().newBus()
                .setId("b2")
                .add();
        vl2.newLoad()
                .setId("ld2")
                .setConnectableBus("b2")
                .setBus("b2")
                .setP0(120)
                .setQ0(60)
                .add();

        VoltageLevel vl3 = s.newVoltageLevel()
                .setId("vl3")
                .setNominalV(20)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl3.getBusBreakerView().newBus()
                .setId("b3")
                .add();
        vl3.newLoad()
                .setId("ld3")
                .setConnectableBus("b3")
                .setBus("b3")
                .setP0(20)
                .setQ0(8)
                .add();

        s.newThreeWindingsTransformer()
                .setId("3wt")
                .newLeg1()
                .setConnectableBus("b1")
                .setBus("b1")
                .setRatedU(380)
                .setR(0.08)
                .setX(47.3)
                .add()
                .newLeg2()
                .setConnectableBus("b2")
                .setBus("b2")
                .setRatedU(225)
                .setR(0.4)
                .setX(7.7)
                .add()
                .newLeg3()
                .setConnectableBus("b3")
                .setBus("b3")
                .setRatedU(20)
                .setR(4.98)
                .setX(133.5)
                .add()
                .add();

        VoltageLevel vl4 = s.newVoltageLevel()
                .setId("vl4")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl4.getBusBreakerView().newBus()
                .setId("b4")
                .add();
        vl4.newGenerator()
                .setId("g4")
                .setConnectableBus("b4")
                .setBus("b4")
                .setTargetP(80)
                .setTargetV(405)
                .setMinP(0)
                .setMaxP(500)
                .setVoltageRegulatorOn(true)
                .add();

        int zb400 = 400 * 400 / 100;
        network.newLine()
                .setId("l14")
                .setVoltageLevel1("vl1")
                .setConnectableBus1("b1")
                .setBus1("b1")
                .setVoltageLevel2("vl4")
                .setConnectableBus2("b4")
                .setBus2("b4")
                .setR(0.01 * zb400)
                .setX(0.1 * zb400)
                .setG1(0)
                .setB1(0)
                .setG2(0)
                .setB2(0)
                .add();

        return network;
    }

    private SecurityAnalysisResult run(boolean alternativeEquations, List<Contingency> contingencies) {
        Network network = createNetworkWithFallbackAndStructurePreservingContingencies();
        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(lfParameters).setAlternativeEquations(alternativeEquations);
        saParameters.setLoadFlowParameters(lfParameters);
        return runSecurityAnalysis(network, contingencies, createNetworkMonitors(network), saParameters);
    }

    @Test
    void alternativeEquationsReordersFallbackLastButKeepsInputResultOrder() {
        // the fallback contingency (3wt) is listed first, the structure-preserving one (l14) second, so the internal
        // "structure-preserving first, fallback last" reordering really permutes the execution order
        List<Contingency> contingencies = List.of(
                Contingency.threeWindingsTransformer("3wt"),
                Contingency.line("l14"));

        SecurityAnalysisResult legacy = run(false, contingencies);

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger saLogger = loggerContext.getLogger(AbstractSecurityAnalysis.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        saLogger.addAppender(logAppender);
        SecurityAnalysisResult alternative;
        try {
            alternative = run(true, contingencies);
        } finally {
            saLogger.detachAppender(logAppender);
        }

        // exactly one contingency (the 3wt with its fictitious star bus) falls back to a full structure build, and the
        // end-of-analysis summary confirms the "construction paid once" invariant: builds == fallbacks + 1
        long fallbackLogs = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("fell back to a full Jacobian structure rebuild"))
                .count();
        assertEquals(1, fallbackLogs);
        assertTrue(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("performed 2 full Jacobian structure build(s) for 1 fallback")),
                "expected the summary log to report 2 structure builds for 1 fallback contingency");

        // results are returned in the input contingency order, not the (reordered) execution order
        assertEquals(List.of("3wt", "l14"), legacy.getPostContingencyResults().stream()
                .map(r -> r.getContingency().getId()).toList());
        assertEquals(List.of("3wt", "l14"), alternative.getPostContingencyResults().stream()
                .map(r -> r.getContingency().getId()).toList());

        // and the alternative modeling matches the legacy modeling contingency by contingency
        assertSameResultsWithinTolerance(legacy, alternative);
    }

    private static void assertSameResultsWithinTolerance(SecurityAnalysisResult expected, SecurityAnalysisResult actual) {
        assertEquals(expected.getPreContingencyResult().getStatus(), actual.getPreContingencyResult().getStatus());
        compareNetworkResults(expected.getPreContingencyResult().getNetworkResult(), actual.getPreContingencyResult().getNetworkResult());
        assertEquals(expected.getPostContingencyResults().size(), actual.getPostContingencyResults().size());
        for (PostContingencyResult expectedPcr : expected.getPostContingencyResults()) {
            PostContingencyResult actualPcr = actual.getPostContingencyResults().stream()
                    .filter(r -> r.getContingency().getId().equals(expectedPcr.getContingency().getId()))
                    .findFirst().orElseThrow();
            assertEquals(expectedPcr.getStatus(), actualPcr.getStatus(), "status mismatch for " + expectedPcr.getContingency().getId());
            compareNetworkResults(expectedPcr.getNetworkResult(), actualPcr.getNetworkResult());
        }
    }

    private static void compareNetworkResults(NetworkResult legacyNetworkResult, NetworkResult networkResult) {
        assertEquals(legacyNetworkResult.getBranchResults().size(), networkResult.getBranchResults().size());
        for (BranchResult legacyBranchResult : legacyNetworkResult.getBranchResults()) {
            BranchResult branchResult = networkResult.getBranchResult(legacyBranchResult.getBranchId());
            assertEquals(legacyBranchResult.getP1(), branchResult.getP1(), 1e-2, "p1 mismatch on branch " + legacyBranchResult.getBranchId());
            assertEquals(legacyBranchResult.getQ1(), branchResult.getQ1(), 1e-2, "q1 mismatch on branch " + legacyBranchResult.getBranchId());
            assertEquals(legacyBranchResult.getP2(), branchResult.getP2(), 1e-2, "p2 mismatch on branch " + legacyBranchResult.getBranchId());
            assertEquals(legacyBranchResult.getQ2(), branchResult.getQ2(), 1e-2, "q2 mismatch on branch " + legacyBranchResult.getBranchId());
        }
        assertEquals(legacyNetworkResult.getBusResults().size(), networkResult.getBusResults().size());
        for (BusResult legacyBusResult : legacyNetworkResult.getBusResults()) {
            BusResult busResult = networkResult.getBusResults().stream()
                    .filter(b -> b.getBusId().equals(legacyBusResult.getBusId()))
                    .findFirst().orElseThrow();
            assertEquals(legacyBusResult.getV(), busResult.getV(), 1e-4, "v mismatch on bus " + legacyBusResult.getBusId());
            assertEquals(legacyBusResult.getAngle(), busResult.getAngle(), 1e-4, "angle mismatch on bus " + legacyBusResult.getBusId());
        }
    }
}
