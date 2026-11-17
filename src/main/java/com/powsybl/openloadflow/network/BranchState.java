/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

/**
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
 */
public class BranchState extends ElementState<LfBranch> {

    private final double a1;
    private final double r1;
    private final TransformerPhaseControl phaseControl;
    private Double operablePhaseControlTargetValue;
    private final boolean phaseControlEnabled;
    private final boolean voltageControlEnabled;
    private Integer tapPosition;
    private Boolean connectedSide1;
    private Boolean connectedSide2;

    public BranchState(LfBranch branch) {
        super(branch);
        PiModel piModel = branch.getPiModel();
        if (piModel instanceof PiModelArray piModelArray) {
            tapPosition = piModel.getTapPosition();
            // also save modified a1 and r1 and not directly a1 and r1 to avoid restoring
            // with same values as current tap position
            a1 = piModelArray.getModifiedA1();
            r1 = piModelArray.getModifiedR1();
        } else {
            a1 = piModel.getA1();
            r1 = piModel.getR1();
        }
        phaseControl = branch.getPhaseControl().orElse(null);
        branch.getOperablePhaseControl()
                .filter(pc -> pc.getControllerBranch() == branch)
                .ifPresent(pc -> operablePhaseControlTargetValue = pc.getTargetValue());
        phaseControlEnabled = branch.isPhaseControlEnabled();
        voltageControlEnabled = branch.isVoltageControlEnabled();
        if (branch.isDisconnectionAllowedSide1()) {
            connectedSide1 = branch.isConnectedSide1();
        }
        if (branch.isDisconnectionAllowedSide2()) {
            connectedSide2 = branch.isConnectedSide2();
        }
    }

    @Override
    public void restore() {
        super.restore();
        PiModel piModel = element.getPiModel();
        if (piModel instanceof PiModelArray) {
            piModel.setTapPosition(tapPosition);
        }
        piModel.setA1(a1);
        piModel.setR1(r1);
        // phase control might have been attached or detached by an action, restore it before enabling flag
        element.setPhaseControl(phaseControl);
        if (operablePhaseControlTargetValue != null) {
            element.getOperablePhaseControl().orElseThrow().setTargetValue(operablePhaseControlTargetValue);
        }
        element.setPhaseControlEnabled(phaseControlEnabled);
        element.setVoltageControlEnabled(voltageControlEnabled);
        if (connectedSide1 != null) {
            element.setConnectedSide1(connectedSide1);
        }
        if (connectedSide2 != null) {
            element.setConnectedSide2(connectedSide2);
        }
    }

    public static BranchState save(LfBranch branch) {
        return new BranchState(branch);
    }
}
