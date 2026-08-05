/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.violations.LimitViolation;
import com.powsybl.contingency.violations.LimitViolationType;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.results.PostContingencyResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.powsybl.openloadflow.sa.OpenSecurityAnalysisParameters.LimitViolationReporting.MOST_RESTRICTIVE;
import static com.powsybl.openloadflow.sa.OpenSecurityAnalysisParameters.LimitViolationReporting.PER_LIMITS_GROUP;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the {@code limitViolationReporting} parameter: a branch carrying two selected operational limits groups yields
 * one violation per group in {@link OpenSecurityAnalysisParameters.LimitViolationReporting#PER_LIMITS_GROUP} (default),
 * but only the most restrictive group in {@link OpenSecurityAnalysisParameters.LimitViolationReporting#MOST_RESTRICTIVE}.
 *
 * @author (design proposal)
 */
class OpenSecurityAnalysisLimitViolationReportingTest extends AbstractOpenSecurityAnalysisTest {

    OpenSecurityAnalysisLimitViolationReportingTest(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    /**
     * Adds two selected operational limits groups on side 1 of the given line, each with a single active power permanent
     * limit ({@code lowLimit} for group "LOW", {@code highLimit} for group "HIGH").
     */
    private static Network eurostagWithTwoSelectedGroups(String lineId, double lowLimit, double highLimit) {
        Network network = EurostagTutorialExample1Factory.create();
        Line line = network.getLine(lineId);
        OperationalLimitsGroup low = line.newOperationalLimitsGroup1("LOW");
        low.newActivePowerLimits().setPermanentLimit(lowLimit).add();
        OperationalLimitsGroup high = line.newOperationalLimitsGroup1("HIGH");
        high.newActivePowerLimits().setPermanentLimit(highLimit).add();
        line.addSelectedOperationalLimitsGroups(TwoSides.ONE, "LOW", "HIGH");
        return network;
    }

    private SecurityAnalysisParameters parameters(boolean dcFastMode, OpenSecurityAnalysisParameters.LimitViolationReporting reporting) {
        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.getLoadFlowParameters().setDc(dcFastMode);
        saParameters.addExtension(OpenSecurityAnalysisParameters.class,
                new OpenSecurityAnalysisParameters().setDcFastMode(dcFastMode).setLimitViolationReporting(reporting));
        return saParameters;
    }

    private static List<LimitViolation> activePowerViolations(List<LimitViolation> violations, String branchId) {
        return violations.stream()
                .filter(v -> v.getSubjectId().equals(branchId) && v.getLimitType() == LimitViolationType.ACTIVE_POWER)
                .toList();
    }

    @Test
    void preContingencyReportingMode() {
        // ~300 MW flows through NHV1_NHV2_1: both the 100 MW and 200 MW groups are exceeded
        String lineId = "NHV1_NHV2_1";
        Network network = eurostagWithTwoSelectedGroups(lineId, 100.0, 200.0);

        List<LimitViolation> perGroup = activePowerViolations(runSecurityAnalysis(network, List.of(), List.of(),
                parameters(false, PER_LIMITS_GROUP)).getPreContingencyResult().getLimitViolationsResult().getLimitViolations(), lineId);
        assertEquals(2, perGroup.size());

        List<LimitViolation> mostRestrictive = activePowerViolations(runSecurityAnalysis(network, List.of(), List.of(),
                parameters(false, MOST_RESTRICTIVE)).getPreContingencyResult().getLimitViolationsResult().getLimitViolations(), lineId);
        assertEquals(1, mostRestrictive.size());
        // the most restrictive group is the one with the lowest permanent limit
        assertEquals("LOW", mostRestrictive.get(0).getOperationalLimitsGroupId());
    }

    @Test
    void postContingencyReportingModeFastDc() {
        // after losing NHV1_NHV2_1, ~600 MW flows through NHV1_NHV2_2: both the 400 MW and 500 MW groups are exceeded
        // (and neither is exceeded pre-contingency at ~300 MW, so the fast-DC snapshot path is exercised cleanly)
        String lineId = "NHV1_NHV2_2";
        Network network = eurostagWithTwoSelectedGroups(lineId, 400.0, 500.0);
        List<Contingency> contingencies = List.of(new Contingency("NHV1_NHV2_1", new BranchContingency("NHV1_NHV2_1")));

        PostContingencyResult perGroup = runSecurityAnalysis(network, contingencies, List.of(),
                parameters(true, PER_LIMITS_GROUP)).getPostContingencyResults().get(0);
        assertEquals(2, activePowerViolations(perGroup.getLimitViolationsResult().getLimitViolations(), lineId).size());

        PostContingencyResult mostRestrictive = runSecurityAnalysis(network, contingencies, List.of(),
                parameters(true, MOST_RESTRICTIVE)).getPostContingencyResults().get(0);
        List<LimitViolation> mostRestrictiveViolations = activePowerViolations(mostRestrictive.getLimitViolationsResult().getLimitViolations(), lineId);
        assertEquals(1, mostRestrictiveViolations.size());
        assertEquals("LOW", mostRestrictiveViolations.get(0).getOperationalLimitsGroupId());
    }
}
