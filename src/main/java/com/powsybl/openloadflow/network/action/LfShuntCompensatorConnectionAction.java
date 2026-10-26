/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.action;

import com.powsybl.action.TerminalsConnectionAction;
import com.powsybl.iidm.network.ShuntCompensator;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.LfShuntImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * A terminals connection action on a shunt compensator: connecting it sets its section count, disconnecting it sets
 * section 0, which is electrically the same as disconnected. A shunt compensator disconnected in the network is in the
 * load flow network, at section 0, when an action may connect it (see {@link LfTopoConfig#getShuntIdsToClose()}).
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class LfShuntCompensatorConnectionAction extends AbstractLfAction<TerminalsConnectionAction> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfShuntCompensatorConnectionAction.class);

    private final LfShunt shunt;

    private final int sectionCount;

    public LfShuntCompensatorConnectionAction(TerminalsConnectionAction action, ShuntCompensator shuntCompensator, LfNetwork network) {
        super(action);
        if (action.getSide().isPresent()) {
            throw new UnsupportedOperationException("Terminals connection action: a side is not supported for shunt compensator '"
                    + shuntCompensator.getId() + "'");
        }
        LfShunt aShunt = network.getShuntById(shuntCompensator.getId());
        this.shunt = aShunt instanceof LfShuntImpl ? aShunt : null; // no svc here
        this.sectionCount = shuntCompensator.getSectionCount();
    }

    private Optional<LfShunt.Controller> findController() {
        return shunt.getControllers().stream()
                .filter(controller -> controller.getId().equals(action.getElementId()))
                .findAny();
    }

    @Override
    public boolean isValid() {
        return shunt != null && findController().isPresent();
    }

    @Override
    public boolean apply(LfNetwork network, LfContingency contingency, LfNetworkParameters networkParameters) {
        if (!isValid()) {
            LOGGER.warn("Terminals connection action {}: shunt compensator {} cannot be connected or disconnected", action.getId(), action.getElementId());
            return false;
        }
        if (shunt.getVoltageControl().isPresent()) {
            LOGGER.warn("Terminals connection action {}: voltage control is present on the shunt, section could be overridden.", action.getId());
        }
        findController().orElseThrow().updateSectionB(action.isOpen() ? 0 : sectionCount);
        return true;
    }
}
