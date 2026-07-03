/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class NetworkState {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkState.class);

    private final LfNetwork network;

    private final List<BusState> busStates;

    private final List<BranchState> branchStates;

    private final List<HvdcState> hvdcStates;

    private final Set<LfBus> excludedSlackBuses;

    private final List<AreaState> areaStates;

    // per-element indexes, used to restore only the elements impacted by a contingency (see restore with arguments)
    private final Map<LfBus, BusState> busStateByBus;

    private final Map<LfBranch, BranchState> branchStateByBranch;

    private final Map<LfHvdc, HvdcState> hvdcStateByHvdc;

    protected NetworkState(LfNetwork network, List<BusState> busStates, List<BranchState> branchStates, List<HvdcState> hvdcStates,
                           Set<LfBus> excludedSlackBuses, List<AreaState> areaStates) {
        this.network = Objects.requireNonNull(network);
        this.busStates = Objects.requireNonNull(busStates);
        this.branchStates = Objects.requireNonNull(branchStates);
        this.hvdcStates = Objects.requireNonNull(hvdcStates);
        this.excludedSlackBuses = Objects.requireNonNull(excludedSlackBuses);
        this.areaStates = Objects.requireNonNull(areaStates);
        this.busStateByBus = indexByElement(busStates);
        this.branchStateByBranch = indexByElement(branchStates);
        this.hvdcStateByHvdc = indexByElement(hvdcStates);
    }

    private static <T extends LfElement, U extends ElementState<T>> Map<T, U> indexByElement(List<U> states) {
        Map<T, U> index = new HashMap<>(states.size());
        for (U state : states) {
            index.put(state.getElement(), state);
        }
        return index;
    }

    public static NetworkState save(LfNetwork network) {
        Objects.requireNonNull(network);
        LOGGER.trace("Saving network state");
        network.setGeneratorsInitialTargetPToTargetP();
        List<BusState> busStates = ElementState.save(network.getBuses(), BusState::save);
        List<BranchState> branchStates = ElementState.save(network.getBranches(), BranchState::save);
        List<HvdcState> hvdcStates = ElementState.save(network.getHvdcs(), HvdcState::save);
        List<AreaState> areaStates = ElementState.save(network.getAreas(), AreaState::save);
        Set<LfBus> excludedSlackBuses = network.getSynchronousNetworks().stream()
            .flatMap(lfScNetwork -> lfScNetwork.getExcludedSlackBuses().stream())
            .collect(Collectors.toSet());
        return new NetworkState(network, busStates, branchStates, hvdcStates, excludedSlackBuses, areaStates);
    }

    public void restore() {
        LOGGER.trace("Restoring network state");
        ElementState.restore(busStates);
        ElementState.restore(branchStates);
        ElementState.restore(hvdcStates);
        ElementState.restore(areaStates);
        restoreExcludedSlackBuses();
    }

    /**
     * Reset the excluded slack buses of each synchronous network to the saved value. A contingency only changes them
     * when it isolates the slack bus, which is rare, so the value is compared first: {@code setExcludedSlackBuses}
     * scans the synchronous network buses on each call, which is not worth doing when nothing changed.
     */
    private void restoreExcludedSlackBuses() {
        network.getSynchronousNetworks().forEach(scLfNetwork -> {
            if (!scLfNetwork.getExcludedSlackBuses().equals(excludedSlackBuses)) {
                scLfNetwork.setExcludedSlackBuses(excludedSlackBuses);
            }
        });
    }

    /**
     * Restore the saved state of the given buses, branches and HVDC links only, instead of the whole network. This is
     * used by security analyses that touch only a bounded set of elements per contingency: restoring just those
     * elements avoids the network-wide cost of {@link #restore()} after each contingency. Areas and excluded slack
     * buses are always restored as their number is negligible.
     */
    public void restore(Collection<LfBus> buses, Collection<LfBranch> branches, Collection<LfHvdc> hvdcs) {
        LOGGER.trace("Restoring network state of {} buses, {} branches and {} hvdcs", buses.size(), branches.size(), hvdcs.size());
        for (LfBus bus : buses) {
            busStateByBus.get(bus).restore();
        }
        for (LfBranch branch : branches) {
            branchStateByBranch.get(branch).restore();
        }
        for (LfHvdc hvdc : hvdcs) {
            hvdcStateByHvdc.get(hvdc).restore();
        }
        ElementState.restore(areaStates);
        restoreExcludedSlackBuses();
    }
}
