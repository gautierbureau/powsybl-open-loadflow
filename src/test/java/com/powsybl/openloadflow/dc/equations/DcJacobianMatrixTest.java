/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.equations;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.PhaseShifterTestCaseFactory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowEngine;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.graph.NaiveGraphConnectivityFactory;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfElement;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import com.powsybl.openloadflow.util.PerUnit;
import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.util.LoadFlowAssert.DELTA_POWER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class DcJacobianMatrixTest {

    /**
     * Counts the derivative recomputations a re-solve costs. The initial build goes through initDer, so a matrix that
     * is never asked to refresh its values leaves this at zero.
     */
    private static final class CountingDcJacobianMatrix extends DcJacobianMatrix {

        private int updateDerCount;

        private CountingDcJacobianMatrix(DcLoadFlowContext context, MatrixFactory matrixFactory) {
            super(context.getEquationSystem(), matrixFactory, context.getNetwork());
        }

        @Override
        protected void updateDer() {
            updateDerCount++;
            super.updateDer();
        }
    }

    private static final class CountingContext extends DcLoadFlowContext {

        private CountingDcJacobianMatrix countingJacobianMatrix;

        private CountingContext(LfNetwork network, DcLoadFlowParameters parameters) {
            super(network, parameters);
        }

        @Override
        public JacobianMatrix<DcVariableType, DcEquationType> getJacobianMatrix() {
            if (jacobianMatrix == null) {
                countingJacobianMatrix = new CountingDcJacobianMatrix(this, getParameters().getMatrixFactory());
                jacobianMatrix = countingJacobianMatrix;
            }
            return jacobianMatrix;
        }

        private int updateDerCount() {
            return countingJacobianMatrix.updateDerCount;
        }
    }

    private static DcLoadFlowParameters dcParameters() {
        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(false);
        return OpenLoadFlowParameters.createDcParameters(parameters, OpenLoadFlowParameters.create(parameters),
                new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfElement::getNum), false);
    }

    private static LfNetwork load(Network network, DcLoadFlowParameters dcParameters) {
        return LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).get(0);
    }

    private static double[] branchFlows(LfNetwork lfNetwork) {
        return lfNetwork.getBranches().stream()
                .mapToDouble(branch -> branch.getP1().eval() * PerUnit.SB)
                .toArray();
    }

    private static void assertSameFlows(LfNetwork expected, LfNetwork actual) {
        assertEquals(expected.getBranches().size(), actual.getBranches().size());
        double[] expectedFlows = branchFlows(expected);
        double[] actualFlows = branchFlows(actual);
        for (int i = 0; i < expectedFlows.length; i++) {
            LfBranch branch = actual.getBranches().get(i);
            assertEquals(expectedFlows[i], actualFlows[i], DELTA_POWER, branch.getId());
        }
    }

    private static Network phaseShifterNetworkWithARegulatingTap() {
        Network network = PhaseShifterTestCaseFactory.create();
        TwoWindingsTransformer ps1 = network.getTwoWindingsTransformer("PS1");
        ps1.getPhaseTapChanger().getStep(0).setAlpha(-5);
        ps1.getPhaseTapChanger().getStep(2).setAlpha(5);
        ps1.getPhaseTapChanger().setTargetDeadband(10);
        ps1.getPhaseTapChanger().setRegulationValue(-80);
        ps1.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL);
        ps1.getPhaseTapChanger().setRegulating(true);
        return network;
    }

    @Test
    void stateUpdatesAloneDoNotRecomputeTheValues() {
        // Eurostag carries a pi model array, its ratio tap changer being a regulating one, so this is also the case
        // of a branch that is watched and never moves -- which in DC is every ratio tap changer, there being no
        // transformer voltage control to move one
        DcLoadFlowParameters dcParameters = dcParameters();
        LfNetwork lfNetwork = load(EurostagFactory.fix(EurostagTutorialExample1Factory.create()), dcParameters);

        try (CountingContext context = new CountingContext(lfNetwork, dcParameters)) {
            // a first run builds the matrix through initDer, and writes the state vector twice on the way
            new DcLoadFlowEngine(context).run();
            assertEquals(0, context.updateDerCount());

            // a second run on the same context: the only thing that moved is the state left by the first one
            new DcLoadFlowEngine(context).run();
            assertEquals(0, context.updateDerCount());
        }
    }

    @Test
    void aMovedRatioTapDoesRecomputeTheValues() {
        DcLoadFlowParameters dcParameters = dcParameters();
        LfNetwork lfNetwork = load(EurostagFactory.fix(EurostagTutorialExample1Factory.create()), dcParameters);

        try (CountingContext context = new CountingContext(lfNetwork, dcParameters)) {
            new DcLoadFlowEngine(context).run();
            assertEquals(0, context.updateDerCount());

            // the steps of a ratio tap changer carry their own ratio, which the power is built from, so moving the
            // tap has to be picked up
            lfNetwork.getBranchById("NHV2_NLOAD").getPiModel().setTapPosition(0);
            new DcLoadFlowEngine(context).run();
            assertEquals(1, context.updateDerCount());

            // and nothing more is owed once it has been
            new DcLoadFlowEngine(context).run();
            assertEquals(1, context.updateDerCount());
        }
    }

    @Test
    void aMovedPhaseTapThatOnlyShiftsTheAngleDoesNotRecomputeTheValues() {
        DcLoadFlowParameters dcParameters = dcParameters();
        LfNetwork lfNetwork = load(phaseShifterNetworkWithARegulatingTap(), dcParameters);

        try (CountingContext context = new CountingContext(lfNetwork, dcParameters)) {
            new DcLoadFlowEngine(context).run();

            // the steps of this phase shifter differ by their angle alone, and an angle is not one of the three
            // quantities the power is built from: it lands in the right hand side, leaving the matrix as it was
            lfNetwork.getBranchById("PS1").getPiModel().setTapPosition(2);
            new DcLoadFlowEngine(context).run();
            assertEquals(0, context.updateDerCount());
        }
        // that the flows still follow the tap is reSolvingAfterATapChangeMatchesAnIndependentRun below
    }

    @Test
    void reSolvingAfterATargetChangeMatchesAnIndependentRun() {
        DcLoadFlowParameters dcParameters = dcParameters();

        // the reference: a network built with the new generation, solved once
        Network reference = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        reference.getGenerator("GEN").setTargetP(500);
        LfNetwork referenceLfNetwork = load(reference, dcParameters);
        try (DcLoadFlowContext context = new DcLoadFlowContext(referenceLfNetwork, dcParameters)) {
            new DcLoadFlowEngine(context).run();
        }

        // the same generation reached by moving the target on a context that has already solved once
        LfNetwork lfNetwork = load(EurostagFactory.fix(EurostagTutorialExample1Factory.create()), dcParameters);
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            new DcLoadFlowEngine(context).run();
            lfNetwork.getGeneratorById("GEN").setTargetP(500 / PerUnit.SB);
            new DcLoadFlowEngine(context).run();
        }

        assertSameFlows(referenceLfNetwork, lfNetwork);
    }

    @Test
    void reSolvingAfterARatioTapChangeMatchesAnIndependentRun() {
        DcLoadFlowParameters dcParameters = dcParameters();

        // the reference: a network built on the lowest ratio tap position, solved once
        Network reference = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        reference.getTwoWindingsTransformer("NHV2_NLOAD").getRatioTapChanger().setTapPosition(0);
        LfNetwork referenceLfNetwork = load(reference, dcParameters);
        try (DcLoadFlowContext context = new DcLoadFlowContext(referenceLfNetwork, dcParameters)) {
            new DcLoadFlowEngine(context).run();
        }

        // the same ratio reached in place, after a first solve: a ratio is one of the quantities the power is built
        // from, so this is the case a matrix that stopped watching its pi models would answer with stale derivatives
        LfNetwork lfNetwork = load(EurostagFactory.fix(EurostagTutorialExample1Factory.create()), dcParameters);
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            new DcLoadFlowEngine(context).run();
            lfNetwork.getBranchById("NHV2_NLOAD").getPiModel().setTapPosition(0);
            new DcLoadFlowEngine(context).run();
        }

        assertSameFlows(referenceLfNetwork, lfNetwork);
    }

    @Test
    void reSolvingAfterATapChangeMatchesAnIndependentRun() {
        DcLoadFlowParameters dcParameters = dcParameters();

        // the reference: a network built on tap position 2, solved once
        Network reference = phaseShifterNetworkWithARegulatingTap();
        reference.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(2);
        LfNetwork referenceLfNetwork = load(reference, dcParameters);
        try (DcLoadFlowContext context = new DcLoadFlowContext(referenceLfNetwork, dcParameters)) {
            new DcLoadFlowEngine(context).run();
        }

        // the same tap position reached in place, after a first solve: this is what a jacobian frozen on the state
        // alone would get wrong
        LfNetwork lfNetwork = load(phaseShifterNetworkWithARegulatingTap(), dcParameters);
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            new DcLoadFlowEngine(context).run();
            lfNetwork.getBranchById("PS1").getPiModel().setTapPosition(2);
            new DcLoadFlowEngine(context).run();
        }

        assertSameFlows(referenceLfNetwork, lfNetwork);
    }
}
