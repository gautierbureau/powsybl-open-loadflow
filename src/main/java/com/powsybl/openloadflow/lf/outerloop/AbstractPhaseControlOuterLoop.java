/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.lf.AbstractLoadFlowParameters;
import com.powsybl.openloadflow.lf.LoadFlowContext;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfElement;
import com.powsybl.openloadflow.network.LfNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractPhaseControlOuterLoop<V extends Enum<V> & Quantity,
                                                    E extends Enum<E> & Quantity,
                                                    P extends AbstractLoadFlowParameters,
                                                    C extends LoadFlowContext<V, E, P>,
                                                    O extends OuterLoopContext<V, E, P, C>> implements OuterLoop<V, E, P, C, O> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractPhaseControlOuterLoop.class);

    private static final String TYPE = "PhaseControl";

    protected AbstractPhaseControlOuterLoop() {
    }

    @Override
    public String getType() {
        return TYPE;
    }

    private static final String PHASE_CONTROLLER_BRANCHES_PROPERTY = AbstractPhaseControlOuterLoop.class.getName() + ".phaseControllerBranches";

    /**
     * The branches that are phase controllers, by number: a branch's phase control is set when the network is built,
     * so they are gathered once per network instead of testing every branch at each check. Whether one is disabled or
     * connected at both sides is read at each call.
     */
    static int[] getPhaseControllerBranchNums(LfNetwork network) {
        Object gathered = network.getProperty(PHASE_CONTROLLER_BRANCHES_PROPERTY);
        if (gathered == null) {
            gathered = network.getBranches().stream()
                    .filter(LfBranch::isPhaseController)
                    .mapToInt(LfBranch::getNum)
                    .toArray();
            network.setProperty(PHASE_CONTROLLER_BRANCHES_PROPERTY, gathered);
        }
        return (int[]) gathered;
    }

    /** The phase controller branches that are enabled and connected at both sides, in the order of the branches. */
    protected static List<LfBranch> getControllerBranches(LfNetwork network) {
        List<LfBranch> controllerBranches = new ArrayList<>();
        for (int num : getPhaseControllerBranchNums(network)) {
            LfBranch branch = network.getBranch(num);
            if (!branch.isDisabled() && branch.isConnectedAtBothSides()) {
                controllerBranches.add(branch);
            }
        }
        assert controllerBranches.equals(network.getBranches().stream()
                .filter(branch -> !branch.isDisabled() && branch.isPhaseController() && branch.isConnectedAtBothSides())
                .toList()) : "The phase controller branches gathered once are not those of the network";
        return controllerBranches;
    }

    protected static void fixPhaseShifterNecessaryForConnectivity(LfNetwork network, List<LfBranch> controllerBranches) {
        if (!controllerBranches.isEmpty()) {
            List<LfBranch> disabledBranches = network.getBranches().stream()
                    .filter(LfElement::isDisabled)
                    .collect(Collectors.toList());
            for (LfBranch controllerBranch : controllerBranches) {
                var phaseControl = controllerBranch.getPhaseControl().orElseThrow();
                var controlledBranch = phaseControl.getControlledBranch();
                var connectivity = network.getConnectivity();
                connectivity.startTemporaryChanges();

                // apply contingency (in case we are inside a security analysis)
                disabledBranches.stream()
                        .filter(b -> b.getBus1() != null && b.getBus2() != null)
                        .forEach(connectivity::removeEdge);
                int componentsCountBeforePhaseShifterLoss = connectivity.getNbConnectedComponents();

                // then the phase shifter controlled branch
                if (controlledBranch.getBus1() != null && controlledBranch.getBus2() != null) {
                    connectivity.removeEdge(controlledBranch);
                }

                if (connectivity.getNbConnectedComponents() != componentsCountBeforePhaseShifterLoss) {
                    // phase shifter controlled branch necessary for connectivity, we switch off control
                    LOGGER.warn("Phase shifter '{}' control branch '{}' phase but is necessary for connectivity: switch off phase control",
                            controllerBranch.getId(), controlledBranch.getId());
                    controllerBranch.setPhaseControlEnabled(false);
                }

                connectivity.undoTemporaryChanges();
            }
        }
    }
}
