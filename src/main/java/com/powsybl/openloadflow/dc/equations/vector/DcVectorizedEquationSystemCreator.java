/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.equations.vector;

import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreator;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.EquationArray;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;

/**
 * Vectorized (structure-of-arrays) creator for the DC equation system, mirroring
 * {@code AcVectorizedEquationSystemCreator}. The bulk {@code BUS_TARGET_P} equation is backed by two
 * {@link EquationTermArray}s (closed-branch side 1 and side 2 flows) over a {@link DcNetworkVector};
 * all other DC equations and terms (zero-impedance dummy terms, phase-shift alpha equations, HVDC
 * AC-emulation) remain single equations/terms in the hybrid equation array.
 *
 * @author Claude
 */
public class DcVectorizedEquationSystemCreator extends DcEquationSystemCreator {

    private DcNetworkVector networkVector;

    private EquationTermArray<DcVariableType, DcEquationType> closedP1Array;

    private EquationTermArray<DcVariableType, DcEquationType> closedP2Array;

    public DcVectorizedEquationSystemCreator(LfNetwork network) {
        super(network);
    }

    public DcVectorizedEquationSystemCreator(LfNetwork network, DcEquationSystemCreationParameters creationParameters) {
        super(network, creationParameters);
    }

    @Override
    protected void create(EquationSystem<DcVariableType, DcEquationType> equationSystem, boolean withListener) {
        networkVector = new DcNetworkVector(getNetwork(), equationSystem, getCreationParameters());

        EquationArray<DcVariableType, DcEquationType> pArray = equationSystem.createEquationArray(DcEquationType.BUS_TARGET_P);

        closedP1Array = new EquationTermArray<>(ElementType.BRANCH,
                new ClosedBranchSide1DcFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), equationSystem.getVariableSet()));
        pArray.addTermArray(closedP1Array);
        closedP2Array = new EquationTermArray<>(ElementType.BRANCH,
                new ClosedBranchSide2DcFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), equationSystem.getVariableSet()));
        pArray.addTermArray(closedP2Array);

        networkVector.startListening();

        super.create(equationSystem, withListener);

        closedP1Array.compress();
        closedP2Array.compress();
    }

    @Override
    protected EquationTerm<DcVariableType, DcEquationType> createClosedBranchSide1DcFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2,
                                                                                                    boolean deriveA1, EquationSystem<DcVariableType, DcEquationType> equationSystem) {
        return closedP1Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<DcVariableType, DcEquationType> createClosedBranchSide2DcFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2,
                                                                                                    boolean deriveA1, EquationSystem<DcVariableType, DcEquationType> equationSystem) {
        return closedP2Array.getElement(branch.getNum());
    }
}
