/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.contingency.violations.LimitViolation;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ServiceParameterResolver;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBranchFlowArrays;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LoadFlowModel;
import com.powsybl.openloadflow.network.MostMeshedSlackBusSelector;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The branch limit screen reading the flows from the branch-num indexed arrays published by the vectorised equation
 * system ({@link LfBranchFlowArrays}) instead of walking the branch evaluables.
 *
 * <p>Two things have to hold for that substitution to be legitimate, and they are what this class checks: the arrays
 * hold the same values as the evaluables (exactly for the powers, to rounding for the currents, which is why the
 * current screen keeps a margin), and the two screening paths report exactly the same violations.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
@ExtendWith(ServiceParameterResolver.class)
class BranchLimitScreenBulkFlowsTest {

    private final CommonTestConfig commonTestConfig;

    BranchLimitScreenBulkFlowsTest(CommonTestConfig commonTestConfig) {
        this.commonTestConfig = commonTestConfig;
    }

    private LoadFlowParameters parameters;

    private OpenLoadFlowParameters parametersExt;

    @BeforeEach
    void setUp() {
        parameters = new LoadFlowParameters();
        parametersExt = OpenLoadFlowParameters.create(parameters);
    }

    /**
     * A converged AC network with current limits on both lines, plus an active power and an apparent power limit so
     * that all three screened limit types are exercised.
     */
    private static Network limitedNetwork() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.createWithFixedCurrentLimits());
        network.getLine("NHV1_NHV2_1").getOrCreateSelectedOperationalLimitsGroup1()
                .newActivePowerLimits().setPermanentLimit(280.0).add();
        network.getLine("NHV1_NHV2_2").getOrCreateSelectedOperationalLimitsGroup2()
                .newApparentPowerLimits().setPermanentLimit(300.0).add();
        return network;
    }

    private LfNetwork solvedNetwork() {
        return solvedNetwork(limitedNetwork());
    }

    private LfNetwork solvedNetwork(Network network) {
        LfNetwork lfNetwork = Networks.load(network, new MostMeshedSlackBusSelector()).get(0);
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, parametersExt,
                commonTestConfig.matrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
        try (var context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            new AcloadFlowEngine(context).run();
        }
        return lfNetwork;
    }

    /**
     * The powers held in the arrays are the very doubles the evaluables return, so the screen can compare them
     * directly. The currents are not: the arrays hold {@code hypot(p, q) / v} while the evaluable computes
     * {@code hypot(re(I), im(I))}. This pins the disagreement well below the margin the current screen leaves, which
     * is what makes the bulk screen unable to miss a violation.
     */
    @Test
    void arraysHoldTheEvaluableFlows() {
        LfNetwork lfNetwork = solvedNetwork();
        LfBranchFlowArrays flows = lfNetwork.getBranchFlowArrays();
        assertNotNull(flows, "the vectorised equation system is expected to publish its branch flow arrays");

        double maxCurrentDeviation = 0;
        int compared = 0;
        for (LfBranch branch : lfNetwork.getBranches()) {
            if (branch.isDisabled() || branch.getBus1() == null || branch.getBus2() == null) {
                continue;
            }
            int num = branch.getNum();
            // powers: bit for bit, the evaluable reads the very same array slot
            assertEquals(branch.getP1().eval(), flows.p1()[num]);
            assertEquals(branch.getQ1().eval(), flows.q1()[num]);
            assertEquals(branch.getP2().eval(), flows.p2()[num]);
            assertEquals(branch.getQ2().eval(), flows.q2()[num]);
            // apparent power: the screen recomputes it from the power arrays with the same expression as the branch
            assertEquals(branch.computeApparentPower1(), Math.sqrt(flows.p1()[num] * flows.p1()[num] + flows.q1()[num] * flows.q1()[num]));
            assertEquals(branch.computeApparentPower2(), Math.sqrt(flows.p2()[num] * flows.p2()[num] + flows.q2()[num] * flows.q2()[num]));
            // currents: a different expression of the same quantity, so equal only to rounding
            maxCurrentDeviation = Math.max(maxCurrentDeviation, relativeDeviation(branch.getI1().eval(), flows.i1()[num]));
            maxCurrentDeviation = Math.max(maxCurrentDeviation, relativeDeviation(branch.getI2().eval(), flows.i2()[num]));
            compared++;
        }
        assertTrue(compared > 0, "no branch compared");
        // the screen leaves a 1e-9 relative margin on current thresholds: the disagreement must stay far below it
        assertTrue(maxCurrentDeviation < 1e-12,
                "bulk and evaluable currents disagree by " + maxCurrentDeviation + " relative, too close to the screen margin");
    }

    private static double relativeDeviation(double expected, double actual) {
        if (expected == 0) {
            return Math.abs(actual);
        }
        return Math.abs(actual - expected) / Math.abs(expected);
    }

    /**
     * The two screening paths are interchangeable: detecting on a network that publishes its flow arrays and on the
     * same network with the arrays withheld must report the same violations, in the same order.
     */
    @Test
    void bulkAndEvaluableScreensReportTheSameViolations() {
        LfNetwork lfNetwork = solvedNetwork();
        assertTrue(!screensAgreeOn(lfNetwork).isEmpty(), "the case is expected to violate, otherwise it proves nothing");
    }

    /**
     * A branch open on one side is not maintained in the arrays (only the closed branches are) and evaluates through
     * its open-branch terms, so the bulk screen has to fall back to its evaluables for it. Reading its stale array
     * slot instead would screen it on a flow that is not its own.
     */
    @Test
    void branchOpenOnOneSideIsScreenedThroughItsEvaluables() {
        Network network = limitedNetwork();
        network.getLine("NHV1_NHV2_1").getTerminal2().disconnect();
        LfNetwork lfNetwork = solvedNetwork(network);

        LfBranch openBranch = lfNetwork.getBranchById("NHV1_NHV2_1");
        assertTrue(!openBranch.isDisabled() && !openBranch.isConnectedSide2(),
                "the branch is expected enabled and open on side 2, otherwise the fallback is not exercised");
        screensAgreeOn(lfNetwork);
    }

    /**
     * A zero impedance branch is modelled by the equation system outside the vectorised terms (its flows come from
     * dummy variables), so the arrays hold values that are not its flows at all. It must be reported as not described
     * and screened through its evaluables.
     */
    @Test
    void zeroImpedanceBranchIsScreenedThroughItsEvaluables() {
        Network network = limitedNetwork();
        network.getLine("NHV1_NHV2_2").setR(0.0).setX(0.0);
        LfNetwork lfNetwork = solvedNetwork(network);

        LfBranch zeroImpedanceBranch = lfNetwork.getBranchById("NHV1_NHV2_2");
        assertTrue(zeroImpedanceBranch.isZeroImpedance(LoadFlowModel.AC), "the branch is expected to be zero impedance");
        LfBranchFlowArrays flows = lfNetwork.getBranchFlowArrays();
        assertNotNull(flows);
        assertFalse(flows.describedBranches()[zeroImpedanceBranch.getNum()],
                "a zero impedance branch must not be reported as described by the flow arrays");
        screensAgreeOn(lfNetwork);
    }

    /**
     * Detect on the given solved network through both screening paths - with the flow arrays published, and with them
     * withheld so the detection walks the branch evaluables - and check they report the same violations. Returns them.
     */
    private static List<LimitViolation> screensAgreeOn(LfNetwork lfNetwork) {
        LfBranchFlowArrays flows = lfNetwork.getBranchFlowArrays();
        assertNotNull(flows, "the vectorised equation system is expected to publish its branch flow arrays");

        LimitViolationManager bulk = new LimitViolationManager(List.of());
        bulk.detectViolations(lfNetwork);

        lfNetwork.setBranchFlowArrays(null);
        assertNull(lfNetwork.getBranchFlowArrays());
        LimitViolationManager evaluable = new LimitViolationManager(List.of());
        evaluable.detectViolations(lfNetwork);
        lfNetwork.setBranchFlowArrays(flows);

        assertViolationsEqual(evaluable.getLimitViolations(), bulk.getLimitViolations());
        return bulk.getLimitViolations();
    }

    private static void assertViolationsEqual(List<LimitViolation> expected, List<LimitViolation> actual) {
        assertEquals(expected.size(), actual.size(), "violation count");
        for (int i = 0; i < expected.size(); i++) {
            LimitViolation e = expected.get(i);
            LimitViolation a = actual.get(i);
            assertEquals(e.getSubjectId(), a.getSubjectId());
            assertEquals(e.getSide(), a.getSide());
            assertEquals(e.getLimitType(), a.getLimitType());
            assertEquals(e.getOperationalLimitsGroupId(), a.getOperationalLimitsGroupId());
            assertEquals(e.getLimitName(), a.getLimitName());
            assertEquals(e.getAcceptableDuration(), a.getAcceptableDuration());
            assertEquals(e.getLimit(), a.getLimit());
            assertEquals(e.getValue(), a.getValue());
        }
    }
}
