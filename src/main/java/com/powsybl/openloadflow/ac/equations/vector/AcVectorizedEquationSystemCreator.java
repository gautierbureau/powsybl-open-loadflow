/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcVectorizedEquationSystemCreator extends AcEquationSystemCreator {

    protected AcNetworkVector networkVector;

    private EquationTermArray<AcVariableType, AcEquationType> closedP1Array;

    private EquationTermArray<AcVariableType, AcEquationType> closedP2Array;

    private EquationTermArray<AcVariableType, AcEquationType> closedQ1Array;

    private EquationTermArray<AcVariableType, AcEquationType> closedQ2Array;

    private EquationTermArray<AcVariableType, AcEquationType> openP1Array;

    private EquationTermArray<AcVariableType, AcEquationType> openP2Array;

    private EquationTermArray<AcVariableType, AcEquationType> openQ1Array;

    private EquationTermArray<AcVariableType, AcEquationType> openQ2Array;

    public AcVectorizedEquationSystemCreator(LfNetwork network) {
        this(network, new AcEquationSystemCreationParameters());
    }

    public AcVectorizedEquationSystemCreator(LfNetwork network, AcEquationSystemCreationParameters creationParameters) {
        super(network, creationParameters);
    }

    @Override
    protected void create(EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        networkVector = new AcNetworkVector(network, equationSystem, creationParameters);

        EquationArray<AcVariableType, AcEquationType> pArray = equationSystem.createEquationArray(AcEquationType.BUS_TARGET_P);
        EquationArray<AcVariableType, AcEquationType> qArray = equationSystem.createEquationArray(AcEquationType.BUS_TARGET_Q);

        closedP1Array = new EquationTermArray<>(ElementType.BRANCH,
            new ClosedBranchSide1ActiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        pArray.addTermArray(closedP1Array);
        closedP2Array = new EquationTermArray<>(ElementType.BRANCH,
            new ClosedBranchSide2ActiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        pArray.addTermArray(closedP2Array);
        closedQ1Array = new EquationTermArray<>(ElementType.BRANCH,
            new ClosedBranchSide1ReactiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        qArray.addTermArray(closedQ1Array);
        closedQ2Array = new EquationTermArray<>(ElementType.BRANCH,
            new ClosedBranchSide2ReactiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        qArray.addTermArray(closedQ2Array);
        // the flow at side 1 of a branch open at side 2, and the flow at side 2 of a branch open at side 1
        openP1Array = new EquationTermArray<>(ElementType.BRANCH,
            new OpenBranchSide2ActiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), equationSystem.getVariableSet()));
        pArray.addTermArray(openP1Array);
        openP2Array = new EquationTermArray<>(ElementType.BRANCH,
            new OpenBranchSide1ActiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), equationSystem.getVariableSet()));
        pArray.addTermArray(openP2Array);
        openQ1Array = new EquationTermArray<>(ElementType.BRANCH,
            new OpenBranchSide2ReactiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), equationSystem.getVariableSet()));
        qArray.addTermArray(openQ1Array);
        openQ2Array = new EquationTermArray<>(ElementType.BRANCH,
            new OpenBranchSide1ReactiveFlowEquationTermArrayEvaluator(networkVector.getBranchVector(), equationSystem.getVariableSet()));
        qArray.addTermArray(openQ2Array);

        networkVector.startListening();

        super.create(equationSystem);

        closedP1Array.compress();
        closedP2Array.compress();
        closedQ1Array.compress();
        closedQ2Array.compress();
        openP1Array.compress();
        openP2Array.compress();
        openQ1Array.compress();
        openQ2Array.compress();
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ActiveFlowEquationTerm(LfBranch branch, LfBus bus2,
                                                                                                       EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        networkVector.getBranchVector().setOpenableSide1(branch.getNum());
        return openP2Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ReactiveFlowEquationTerm(LfBranch branch, LfBus bus2,
                                                                                                         EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        networkVector.getBranchVector().setOpenableSide1(branch.getNum());
        return openQ2Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1,
                                                                                                       EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        networkVector.getBranchVector().setOpenableSide2(branch.getNum());
        return openP1Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1,
                                                                                                         EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        networkVector.getBranchVector().setOpenableSide2(branch.getNum());
        return openQ1Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2,
                                                                                                         boolean deriveA1, boolean deriveR1,
                                                                                                         EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        return closedP1Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2,
                                                                                                           boolean deriveA1, boolean deriveR1,
                                                                                                           EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        return closedQ1Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2,
                                                                                                         boolean deriveA1, boolean deriveR1,
                                                                                                         EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        return closedP2Array.getElement(branch.getNum());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2,
                                                                                                           boolean deriveA1, boolean deriveR1,
                                                                                                           EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        return closedQ2Array.getElement(branch.getNum());
    }
}
