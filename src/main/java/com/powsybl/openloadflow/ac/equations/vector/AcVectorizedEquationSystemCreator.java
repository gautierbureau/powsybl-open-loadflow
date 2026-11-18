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

    private EquationTermArray<AcVariableType, AcEquationType> loadPArray;

    private EquationTermArray<AcVariableType, AcEquationType> loadQArray;

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
        loadPArray = new EquationTermArray<>(ElementType.BUS,
            new LoadModelActiveFlowEquationTermArrayEvaluator(networkVector.getLoadVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        pArray.addTermArray(loadPArray);
        loadQArray = new EquationTermArray<>(ElementType.BUS,
            new LoadModelReactiveFlowEquationTermArrayEvaluator(networkVector.getLoadVector(), networkVector.getBusVector(), equationSystem.getVariableSet()));
        qArray.addTermArray(loadQArray);

        networkVector.startListening();

        super.create(equationSystem);

        closedP1Array.compress();
        closedP2Array.compress();
        closedQ1Array.compress();
        closedQ2Array.compress();
        loadPArray.compress();
        loadQArray.compress();
    }

    /**
     * The loads of a bus that follow a model give one term to each balance of the bus, their sum, and each load reads
     * its own power in the load vector.
     */
    @Override
    protected void createLoadEquations(LfBus bus, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        AcLoadVector loadVector = networkVector.getLoadVector();
        boolean hasTerms = false;
        for (LfLoad load : bus.getLoads()) {
            if (load.getLoadModel().isPresent()) {
                int loadNum = loadVector.add(bus, load);
                load.setP(loadVector.getP(loadNum));
                load.setQ(loadVector.getQ(loadNum));
                if (!hasTerms) {
                    equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_P).addTerm(loadPArray.getElement(bus.getNum()));
                    equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_Q).addTerm(loadQArray.getElement(bus.getNum()));
                    hasTerms = true;
                }
            }
        }
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
