/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.action;

import com.powsybl.action.PhaseTapChangerRegulationAction;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.LfLegBranch;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Activate or deactivate the regulation of a phase tap changer, optionally changing its regulation value.
 *
 * <p>The phase control must have been created as operable at network loading (see
 * {@link com.powsybl.openloadflow.network.LfTopoConfig#addBranchIdWithPtcRegulationToOperate(String)}), so that
 * equations needed by the regulation exist. Activating the regulation attaches the phase control to the controller
 * and controlled branches, phase control outer loop then takes care of the regulation. Deactivating the regulation
 * detaches it, the phase shift is then kept constant.</p>
 *
 * <p>Changing the regulation mode is not supported: the mode given in the action, if any, has to be the one of the
 * phase tap changer in the network.</p>
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public class LfPhaseTapChangerRegulationAction extends AbstractLfAction<PhaseTapChangerRegulationAction> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfPhaseTapChangerRegulationAction.class);

    private final LfBranch branch;

    private final TransformerPhaseControl phaseControl;

    public LfPhaseTapChangerRegulationAction(PhaseTapChangerRegulationAction action, LfNetwork network) {
        super(action);
        String branchId = action.getSide().map(side -> LfLegBranch.getId(side, action.getTransformerId())).orElseGet(action::getTransformerId);
        branch = network.getBranchById(branchId);
        phaseControl = branch == null ? null : branch.getOperablePhaseControl()
                .filter(pc -> pc.getControllerBranch() == branch)
                .orElse(null);
        if (phaseControl != null) {
            action.getRegulationMode().ifPresent(mode -> {
                if (toLfMode(mode) != phaseControl.getMode()) {
                    throw new UnsupportedOperationException("Phase tap changer regulation action '" + action.getId()
                            + "': change of regulation mode to " + mode + " is not supported on branch " + branchId);
                }
            });
        }
    }

    private static TransformerPhaseControl.Mode toLfMode(PhaseTapChanger.RegulationMode mode) {
        return switch (mode) {
            case ACTIVE_POWER_CONTROL -> TransformerPhaseControl.Mode.CONTROLLER;
            case CURRENT_LIMITER -> TransformerPhaseControl.Mode.LIMITER;
        };
    }

    @Override
    public boolean isValid() {
        return phaseControl != null;
    }

    @Override
    public boolean apply(LfNetwork network, LfContingency contingency, LfNetworkParameters networkParameters) {
        if (branch == null) {
            return false;
        }
        if (phaseControl == null) {
            LOGGER.warn("Phase tap changer regulation action '{}': no phase control available on branch '{}'", action.getId(), branch.getId());
            return false;
        }
        if (branch.isDisabled()) {
            return false;
        }
        if (action.isRegulating()) {
            return activate();
        } else {
            deactivate();
            return true;
        }
    }

    private boolean activate() {
        if (action.getRegulationValue().isPresent()) {
            phaseControl.setTargetValue(toPerUnit(action.getRegulationValue().getAsDouble()));
        }
        if (Double.isNaN(phaseControl.getTargetValue())) {
            LOGGER.warn("Phase tap changer regulation action '{}': no regulation value for branch '{}'", action.getId(), branch.getId());
            return false;
        }
        LfBranch controlledBranch = phaseControl.getControlledBranch();
        branch.setPhaseControl(phaseControl);
        controlledBranch.setPhaseControl(phaseControl);
        // phase control enabling (for active power control mode) is managed by phase control outer loop
        return true;
    }

    private void deactivate() {
        if (branch.getPhaseControl().filter(pc -> pc == phaseControl).isEmpty()) {
            return; // already deactivated
        }
        // switch to constant phase shift equation, then detach so that outer loops do not activate it again
        branch.setPhaseControlEnabled(false);
        LfBranch controlledBranch = phaseControl.getControlledBranch();
        branch.setPhaseControl(null);
        if (controlledBranch.getPhaseControl().filter(pc -> pc == phaseControl).isPresent()) {
            controlledBranch.setPhaseControl(null);
        }
    }

    private double toPerUnit(double regulationValue) {
        if (phaseControl.getUnit() == TransformerPhaseControl.Unit.A) {
            LfBus controlledBus = phaseControl.getControlledSide() == TwoSides.ONE
                    ? phaseControl.getControlledBranch().getBus1()
                    : phaseControl.getControlledBranch().getBus2();
            double nominalV = Optional.ofNullable(controlledBus).map(LfBus::getNominalV).orElse(Double.NaN);
            return regulationValue / PerUnit.ib(nominalV);
        }
        return regulationValue / PerUnit.SB;
    }
}
