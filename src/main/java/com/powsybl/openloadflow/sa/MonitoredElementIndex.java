/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.security.monitor.StateMonitor;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The branches and buses a state monitor selects in a network, found once per network and monitor instead of once per
 * network result. A branch is selected when one of its original ids is monitored, a bus when its voltage level is; both
 * in the network's order. Which of them are out of service is a property of each result (a contingency, an operator
 * strategy) and is left to the caller.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class MonitoredElementIndex {

    private final Map<LfNetwork, Map<StateMonitor, List<LfBranch>>> branches = new IdentityHashMap<>();

    private final Map<LfNetwork, Map<StateMonitor, List<LfBus>>> buses = new IdentityHashMap<>();

    public synchronized List<LfBranch> getBranches(LfNetwork network, StateMonitor monitor) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(monitor);
        return branches.computeIfAbsent(network, n -> new IdentityHashMap<>())
                .computeIfAbsent(monitor, m -> findBranches(network, m));
    }

    public synchronized List<LfBus> getBuses(LfNetwork network, StateMonitor monitor) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(monitor);
        return buses.computeIfAbsent(network, n -> new IdentityHashMap<>())
                .computeIfAbsent(monitor, m -> findBuses(network, m));
    }

    private static List<LfBranch> findBranches(LfNetwork network, StateMonitor monitor) {
        List<LfBranch> found = new ArrayList<>();
        if (monitor.getBranchIds().isEmpty()) {
            return found;
        }
        for (LfBranch lfBranch : network.getBranches()) {
            for (String originalId : lfBranch.getOriginalIds()) {
                if (monitor.getBranchIds().contains(originalId)) {
                    found.add(lfBranch);
                    break; // only generate result at first original ID match
                }
            }
        }
        return found;
    }

    private static List<LfBus> findBuses(LfNetwork network, StateMonitor monitor) {
        List<LfBus> found = new ArrayList<>();
        if (monitor.getVoltageLevelIds().isEmpty()) {
            return found;
        }
        for (LfBus lfBus : network.getBuses()) {
            if (monitor.getVoltageLevelIds().contains(lfBus.getVoltageLevelId())) {
                found.add(lfBus);
            }
        }
        return found;
    }
}
