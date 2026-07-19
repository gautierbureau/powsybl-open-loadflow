/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.action;

import com.powsybl.action.TerminalsConnectionAction;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Bertrand Rix {@literal <bertrand.rix at artelys.com>}
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 * @author Jean-Luc Bouchot {@literal <jlbouchot at gmail.com>}
 */
public class LfTerminalsConnectionAction extends AbstractLfBranchAction<TerminalsConnectionAction> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfTerminalsConnectionAction.class);

    public LfTerminalsConnectionAction(TerminalsConnectionAction action, LfNetwork lfNetwork) {
        super(action, lfNetwork);
        if (action.getSide().isPresent()) {
            throw new UnsupportedOperationException("Terminals connection action: only open or close branch at both sides is supported yet.");
        }
    }

    private com.powsybl.openloadflow.network.LfShunt shuntToOperate;   // assigned from the super-constructor call — no initializer

    @Override
    void findEnabledDisabledBranches(LfNetwork lfNetwork) {
        List<LfBranch> branches = lfNetwork.getBranchesByOriginalId(action.getElementId());
        if (branches != null) {
            branches.forEach(b -> applyEnabledDisabled(b, action));
        } else {
            // Not a branch: a shunt compensator's terminals may also be operated — the aggregate
            // carries a per-compensator section controller when the shunt was retained as closable
            // (LfTopoConfig.shuntIdsToClose) or operated. Connection = restore its section count,
            // disconnection = section 0.
            com.powsybl.openloadflow.network.LfShunt shunt = lfNetwork.getShuntById(action.getElementId());
            if (shunt != null) {
                shuntToOperate = shunt;
            } else {
                LOGGER.warn("TerminalsConnectionAction action {}: branch, three windings transformer or shunt matching element id {} not found", action.getId(), action.getElementId());
            }
        }
    }

    @Override
    public boolean isValid() {
        return shuntToOperate != null || super.isValid();
    }

    @Override
    public boolean apply(com.powsybl.openloadflow.network.LfNetwork network, com.powsybl.openloadflow.network.LfContingency contingency,
                         com.powsybl.openloadflow.network.LfNetworkParameters networkParameters) {
        if (shuntToOperate != null) {
            return applyShunt();
        }
        return super.apply(network, contingency, networkParameters);
    }

    @Override
    public boolean applyOnConnectivity(com.powsybl.openloadflow.graph.GraphConnectivity<com.powsybl.openloadflow.network.LfBus, LfBranch> connectivity) {
        // The SA replay routes AbstractLfBranchAction subclasses through the BULK connectivity
        // path, never through apply() — the shunt flavor must hook here too. A shunt
        // (dis)connection changes no connectivity: apply the aggregate update and report success.
        if (shuntToOperate != null) {
            return applyShunt();
        }
        return super.applyOnConnectivity(connectivity);
    }

    private boolean applyShunt() {
        return shuntToOperate.setCompensatorConnected(action.getElementId(), !action.isOpen());
    }

    void applyEnabledDisabled(LfBranch branch, TerminalsConnectionAction action) {
        if (branch.getBus1() != null && branch.getBus2() != null) {
            if (action.isOpen()) {
                addDisabledBranch(branch);
            } else {
                addEnabledBranch(branch);
            }
        } else {
            LOGGER.warn("TerminalsConnectionAction action {}: branch matching element id {} has one missing bus", action.getId(), action.getElementId());
        }
    }
}
