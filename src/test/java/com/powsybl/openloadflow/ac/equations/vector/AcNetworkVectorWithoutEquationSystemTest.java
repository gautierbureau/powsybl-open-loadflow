/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives an {@link AcNetworkVector} from a {@link VariableSet} and a {@link StateVector} owned by the caller,
 * with no equation system, the way a state estimator building its own rectangular system would.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AcNetworkVectorWithoutEquationSystemTest {

    private static final double EPS = 1e-12;

    private LfNetwork network;
    private VariableSet<AcVariableType> variableSet;
    private double[] state;
    private StateVector stateVector;
    private AcNetworkVector networkVector;

    @BeforeEach
    void setUp() {
        network = Networks.load(IeeeCdfNetworkFactory.create14(), new FirstSlackBusSelector()).get(0);

        // rows laid out by the caller: all magnitudes first, then all angles
        int busCount = network.getBuses().size();
        variableSet = new VariableSet<>();
        for (LfBus bus : network.getBuses()) {
            variableSet.getVariable(bus.getNum(), AcVariableType.BUS_V).setRow(bus.getNum());
            variableSet.getVariable(bus.getNum(), AcVariableType.BUS_PHI).setRow(busCount + bus.getNum());
        }
        state = new double[2 * busCount];
        stateVector = new StateVector(state);

        networkVector = new AcNetworkVector(network, AcVariableIndex.of(variableSet.getVariables(), stateVector),
                new AcEquationSystemCreationParameters());
        networkVector.startListening();
    }

    private void setState(double phase) {
        int busCount = network.getBuses().size();
        double[] newState = new double[2 * busCount];
        for (int i = 0; i < busCount; i++) {
            newState[i] = 1 + 0.03 * Math.sin(i + phase);
            newState[busCount + i] = 0.1 * Math.cos(1.7 * i + phase);
        }
        stateVector.set(newState); // notifies the network vector, which recomputes all flows
    }

    @Test
    void matchesTheScalarFlowTermsAfterEveryStateUpdate() {
        // two different states, to check the state vector listener keeps the arrays current
        for (double phase : new double[] {0, 0.6}) {
            setState(phase);
            assertMatchesScalarTerms();
        }
    }

    private void assertMatchesScalarTerms() {
        AcBranchVector branchVector = networkVector.getBranchVector();
        int closedCount = 0;
        for (LfBranch branch : network.getBranches()) {
            int num = branch.getNum();
            if (!branchVector.isClosed(num)) {
                continue;
            }
            closedCount++;
            LfBus bus1 = branch.getBus1();
            LfBus bus2 = branch.getBus2();
            assertFlow(new ClosedBranchSide1ActiveFlowEquationTerm(branch, bus1, bus2, variableSet, false, false),
                    branchVector.p1[num], branchVector.dp1dv1[num], branchVector.dp1dv2[num],
                    branchVector.dp1dph1[num], branchVector.dp1dph2[num], bus1, bus2);
            assertFlow(new ClosedBranchSide1ReactiveFlowEquationTerm(branch, bus1, bus2, variableSet, false, false),
                    branchVector.q1[num], branchVector.dq1dv1[num], branchVector.dq1dv2[num],
                    branchVector.dq1dph1[num], branchVector.dq1dph2[num], bus1, bus2);
            assertFlow(new ClosedBranchSide2ActiveFlowEquationTerm(branch, bus1, bus2, variableSet, false, false),
                    branchVector.p2[num], branchVector.dp2dv1[num], branchVector.dp2dv2[num],
                    branchVector.dp2dph1[num], branchVector.dp2dph2[num], bus1, bus2);
            assertFlow(new ClosedBranchSide2ReactiveFlowEquationTerm(branch, bus1, bus2, variableSet, false, false),
                    branchVector.q2[num], branchVector.dq2dv1[num], branchVector.dq2dv2[num],
                    branchVector.dq2dph1[num], branchVector.dq2dph2[num], bus1, bus2);
            assertFlow(new ClosedBranchSide1CurrentMagnitudeEquationTerm(branch, bus1, bus2, variableSet, false, false),
                    branchVector.i1[num], networkVector.di1dv1(num), networkVector.di1dv2(num),
                    networkVector.di1dph1(num), networkVector.di1dph2(num), bus1, bus2);
            assertFlow(new ClosedBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, bus2, variableSet, false, false),
                    branchVector.i2[num], networkVector.di2dv1(num), networkVector.di2dv2(num),
                    networkVector.di2dph1(num), networkVector.di2dph2(num), bus1, bus2);
        }
        assertEquals(network.getBranches().size(), closedCount, "every IEEE 14 branch is closed");
    }

    private void assertFlow(SingleEquationTerm<AcVariableType, AcEquationType> term, double value,
                            double dv1, double dv2, double dph1, double dph2, LfBus bus1, LfBus bus2) {
        term.setStateVector(stateVector);
        assertEquals(term.eval(), value, EPS);
        assertEquals(term.der(variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_V)), dv1, EPS);
        assertEquals(term.der(variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_V)), dv2, EPS);
        assertEquals(term.der(variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_PHI)), dph1, EPS);
        assertEquals(term.der(variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_PHI)), dph2, EPS);
    }

    @Test
    void followsBranchDisabling() {
        setState(0);
        LfBranch branch = network.getBranches().get(0);
        assertTrue(networkVector.getBranchVector().isClosed(branch.getNum()));
        branch.setDisabled(true); // the network listener survives the decoupling from the equation system
        assertFalse(networkVector.getBranchVector().isClosed(branch.getNum()));
    }

    @Test
    void rejectsAVariableWithoutARow() {
        Variable<AcVariableType> unplaced = new VariableSet<AcVariableType>().getVariable(0, AcVariableType.BUS_V);
        List<Variable<AcVariableType>> variables = List.of(unplaced);
        StateVector sv = new StateVector(new double[1]);
        assertThrows(IllegalArgumentException.class, () -> AcVariableIndex.of(variables, sv));
    }
}
