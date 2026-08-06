/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * Copyright (c) 2022-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.action;

import com.powsybl.action.*;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.openloadflow.graph.GraphConnectivity;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.Reports;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.powsybl.openloadflow.network.action.AbstractLfBranchAction.updateBusesAndBranchStatus;

/**
 * @author Bertrand Rix {@literal <bertrand.rix at artelys.com>}
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 * @author Jean-Luc Bouchot {@literal <jlbouchot at gmail.com>}
 */
public final class LfActionUtils {

    private LfActionUtils() {
    }

    public static LfAction createLfAction(Action action, Network network, LfNetwork lfNetwork) {
        Objects.requireNonNull(action);
        return switch (action.getType()) {
            case SwitchAction.NAME -> new LfSwitchAction((SwitchAction) action, lfNetwork);
            case TerminalsConnectionAction.NAME ->
                new LfTerminalsConnectionAction((TerminalsConnectionAction) action, lfNetwork);
            case PhaseTapChangerTapPositionAction.NAME ->
                new LfPhaseTapChangerAction((PhaseTapChangerTapPositionAction) action, lfNetwork);
            case RatioTapChangerTapPositionAction.NAME ->
                new LfRatioTapChangerAction((RatioTapChangerTapPositionAction) action, lfNetwork);
            case LoadAction.NAME ->
                new LfLoadAction((LoadAction) action, Objects.requireNonNull(network, "the iidm network is needed to convert a load action"), lfNetwork);
            case GeneratorAction.NAME -> new LfGeneratorAction((GeneratorAction) action, lfNetwork);
            case HvdcAction.NAME -> new LfHvdcAction((HvdcAction) action, lfNetwork);
            case ShuntCompensatorPositionAction.NAME ->
                new LfShuntCompensatorPositionAction((ShuntCompensatorPositionAction) action, lfNetwork);
            case AreaInterchangeTargetAction.NAME ->
                new LfAreaInterchangeTargetAction((AreaInterchangeTargetAction) action, lfNetwork);
            default -> throw new UnsupportedOperationException("Unsupported action type: " + action.getType());
        };
    }

    public static Map<String, LfAction> createLfActions(LfNetwork lfNetwork, Set<Action> actions, Network network) {
        return actions.stream()
                .map(action -> LfActionUtils.createLfAction(action, network, lfNetwork))
                .collect(Collectors.toMap(LfAction::getId, Function.identity()));
    }

    /**
     * Precompute, from the iidm network, the data the action conversion needs: the load actions
     * power shifts (they read the load base P0/Q0 and the load detail extension). Called on the
     * thread that owns the network; the conversion itself
     * ({@link #createLfActions(LfNetwork, Set, Map)}) then never reads the iidm network, so the
     * multi thread copy mode can convert the actions on each partition copy from worker threads.
     */
    public static Map<String, PowerShift> precomputeLoadActionPowerShifts(List<Action> actions, Network network) {
        Map<String, PowerShift> powerShifts = new HashMap<>();
        for (Action action : actions) {
            if (action instanceof LoadAction loadAction) {
                Load load = network.getLoad(loadAction.getLoadId());
                if (load != null) {
                    powerShifts.put(action.getId(), LfLoadAction.createPowerShift(load, loadAction));
                }
            }
        }
        return powerShifts;
    }

    /**
     * Same as {@link #createLfActions(LfNetwork, Set, Network)} but without any read of the iidm
     * network: the load action power shifts have been precomputed by
     * {@link #precomputeLoadActionPowerShifts(List, Network)}.
     */
    public static Map<String, LfAction> createLfActions(LfNetwork lfNetwork, Set<Action> actions, Map<String, PowerShift> loadActionPowerShifts) {
        return actions.stream()
                .map(action -> action instanceof LoadAction loadAction
                    ? new LfLoadAction(loadAction, loadActionPowerShifts.get(action.getId()), lfNetwork)
                    : LfActionUtils.createLfAction(action, null, lfNetwork))
                .collect(Collectors.toMap(LfAction::getId, Function.identity()));
    }

    public static void applyListOfActions(List<LfAction> actions, LfNetwork network, LfContingency contingency, LfNetworkParameters networkParameters) {
        Objects.requireNonNull(actions);
        Objects.requireNonNull(network);

        // first apply action modifying connectivity
        List<LfAction> branchActions = actions.stream()
            .filter(action -> action instanceof AbstractLfBranchAction<?>)
            .toList();
        updateConnectivity(branchActions, network, contingency);

        // then process remaining changes of actions
        actions.stream()
            .filter(action -> !(action instanceof AbstractLfBranchAction<?>))
            .forEach(action -> {
                if (!action.apply(network, contingency, networkParameters)) {
                    Reports.reportActionApplicationFailure(action.getId(), contingency.getId(), network.getReportNode());
                }
            });
    }

    private static void updateConnectivity(List<LfAction> branchActions, LfNetwork network, LfContingency contingency) {
        GraphConnectivity<LfBus, LfBranch> connectivity = network.getConnectivity();

        // re-update connectivity according to post contingency state (revert after LfContingency apply)
        connectivity.startTemporaryChanges();
        contingency.getDisabledNetwork().getBranches().forEach(connectivity::removeEdge);

        // update connectivity according to post action state
        connectivity.startTemporaryChanges();

        branchActions.forEach(action -> {
            if (!((AbstractLfBranchAction<?>) action).applyOnConnectivity(connectivity)) {
                Reports.reportActionApplicationFailure(action.getId(), contingency.getId(), network.getReportNode());
            }
        });

        updateBusesAndBranchStatus(connectivity);

        // reset connectivity to discard post contingency connectivity and post action connectivity
        connectivity.undoTemporaryChanges();
        connectivity.undoTemporaryChanges();
    }

}
