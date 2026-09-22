/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.solver.AcSolverUtil;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AcNetworkVectorTest {

    @Test
    void valuesOnlyUpdateOfListedBranches() {
        LfNetwork network = Networks.load(EurostagTutorialExample1Factory.create(), new FirstSlackBusSelector()).getFirst();
        EquationSystem<AcVariableType, AcEquationType> equationSystem = new AcVectorizedEquationSystemCreator(network).create();
        AcSolverUtil.initStateVector(network, equationSystem, new UniformValueVoltageInitializer());
        StateVector stateVector = equationSystem.getStateVector();
        List<LfBranch> branches = network.getBranches();
        double[] before = branches.stream().mapToDouble(b -> b.getP1().eval()).toArray();

        double[] moved = stateVector.get().clone();
        equationSystem.getIndex().getSortedVariablesToFind().stream()
                .filter(v -> v.getType() == AcVariableType.BUS_PHI)
                .forEach(v -> moved[v.getRow()] += 0.01 * (v.getElementNum() + 1));
        LfBranch listed = branches.get(1);
        stateVector.setValuesOnly(moved.clone(), new int[] {listed.getNum()});
        double listedP1 = listed.getP1().eval();
        for (LfBranch b : branches) {
            if (b != listed) {                               // not listed: still the previous state's flow
                assertEquals(before[b.getNum()], b.getP1().eval(), 0, b.getId());
            }
        }

        stateVector.setValuesOnly(moved.clone());            // a full update of the same state
        assertEquals(listedP1, listed.getP1().eval(), 0, "a listed branch gets exactly the full update's value");
        for (LfBranch b : branches) {
            if (b != listed) {
                assertNotEquals(before[b.getNum()], b.getP1().eval(), b.getId() + " must move with the state");
            }
        }
    }
}
