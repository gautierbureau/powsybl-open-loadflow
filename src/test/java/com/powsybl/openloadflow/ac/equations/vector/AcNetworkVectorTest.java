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
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AcNetworkVectorTest {

    @Test
    @SuppressWarnings("unchecked")
    void valuesOnlyUpdateRefreshesTheFlowsAndNotTheirDerivatives() {
        LfNetwork network = Networks.load(EurostagTutorialExample1Factory.create(), new FirstSlackBusSelector()).getFirst();
        EquationSystem<AcVariableType, AcEquationType> equationSystem = new AcVectorizedEquationSystemCreator(network).create();
        AcSolverUtil.initStateVector(network, equationSystem, new UniformValueVoltageInitializer());
        StateVector stateVector = equationSystem.getStateVector();
        LfBranch branch = network.getBranchById("NHV1_NHV2_1");
        EquationTerm<AcVariableType, AcEquationType> p1 = (EquationTerm<AcVariableType, AcEquationType>) branch.getP1();
        Variable<AcVariableType> ph2 = p1.getVariables().stream()
                .filter(v -> v.getType() == AcVariableType.BUS_PHI && v.getElementNum() == branch.getBus2().getNum())
                .findFirst().orElseThrow();
        double derivativeBefore = p1.der(ph2);

        double[] moved = stateVector.get().clone();
        equationSystem.getIndex().getSortedVariablesToFind().stream()
                .filter(v -> v.getType() == AcVariableType.BUS_PHI)
                .forEach(v -> moved[v.getRow()] += 0.01 * (v.getElementNum() + 1));

        stateVector.setValuesOnly(moved.clone());
        double p1ValuesOnly = p1.eval();
        assertEquals(derivativeBefore, p1.der(ph2), 0, "a values-only update leaves the previous state's derivatives");

        stateVector.set(moved.clone());
        assertEquals(p1.eval(), p1ValuesOnly, 0, "a values-only update gives the flow a full update gives");
        assertNotEquals(derivativeBefore, p1.der(ph2), "a full update refreshes the derivatives");
    }
}
