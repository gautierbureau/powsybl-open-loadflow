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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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

    /** The voltage in kV and the angle of each bus, by bus number: restored at once, without going through the buses. */
    private final double[] busVoltages;

    private final double[] busAngles;

    protected NetworkState(LfNetwork network, List<BusState> busStates, List<BranchState> branchStates, List<HvdcState> hvdcStates,
                           Set<LfBus> excludedSlackBuses, List<AreaState> areaStates) {
        this.network = Objects.requireNonNull(network);
        this.busStates = Objects.requireNonNull(busStates);
        this.branchStates = Objects.requireNonNull(branchStates);
        this.hvdcStates = Objects.requireNonNull(hvdcStates);
        this.excludedSlackBuses = Objects.requireNonNull(excludedSlackBuses);
        this.areaStates = Objects.requireNonNull(areaStates);
        busVoltages = Arrays.copyOf(network.getBusVoltages(), busStates.size());
        busAngles = Arrays.copyOf(network.getBusAngles(), busStates.size());
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
     * Sets the excluded slack buses of each synchronous network back. A contingency changes them only when it takes
     * the slack bus out, and the setter goes through the buses of the synchronous network before it compares: so the
     * comparison is made here first.
     */
    private void restoreExcludedSlackBuses() {
        for (LfSynchronousNetwork synchronousNetwork : network.getSynchronousNetworks()) {
            if (!synchronousNetwork.getExcludedSlackBuses().equals(excludedSlackBuses)) {
                synchronousNetwork.setExcludedSlackBuses(excludedSlackBuses);
            }
        }
    }

    /**
     * Restores ONLY the given buses and branches — plus every HVDC and area, the excluded slack buses, and the
     * voltage magnitude and angle of EVERY bus. For a caller that knows nothing else changed since the save: a
     * precomputed post-contingency state injected into the network moves every bus voltage, while the contingency
     * itself only touched its own elements. Elements are restored in the order {@link #restore()} uses; anything
     * else changed since the save stays changed.
     *
     * @param buses the buses to restore entirely
     * @param branches the branches to restore
     */
    public void restore(Collection<LfBus> buses, Collection<LfBranch> branches) {
        restore(buses, branches, true);
    }

    /**
     * As {@link #restore(Collection, Collection)}; with {@code voltages} false the voltage magnitude and angle of the
     * buses NOT listed are left as they are - for a caller that overwrites them before anything reads them, and that
     * restores the rest itself ({@link #restoreVoltages()}, {@link #restoreVoltages(Collection)}) otherwise.
     */
    public void restore(Collection<LfBus> buses, Collection<LfBranch> branches, boolean voltages) {
        Objects.requireNonNull(buses);
        Objects.requireNonNull(branches);
        LOGGER.trace("Restoring network state of {} buses and {} branches", buses.size(), branches.size());
        for (LfBus bus : buses) {
            checkSaved(busStates, bus);
        }
        if (voltages) {
            restoreVoltages();
        }
        int[] busNums = buses.stream().mapToInt(LfBus::getNum).distinct().sorted().toArray();
        for (int num : busNums) {
            busStates.get(num).restore();
        }
        int[] branchNums = branches.stream().mapToInt(LfBranch::getNum).distinct().sorted().toArray();
        for (LfBranch branch : branches) {
            checkSaved(branchStates, branch);
        }
        for (int num : branchNums) {
            branchStates.get(num).restore();
        }
        ElementState.restore(hvdcStates);
        ElementState.restore(areaStates);
        restoreExcludedSlackBuses();
    }

    private static <T extends LfElement> void checkSaved(List<? extends ElementState<T>> states, T element) {
        int num = element.getNum();
        if (num < 0 || num >= states.size() || states.get(num).element != element) {
            throw new IllegalArgumentException("Element " + element.getId() + " is not part of the saved network state");
        }
    }

    /**
     * What differs between a bus or a branch of the network and its saved state, or null when all of them are as they
     * were saved. A partial restore leaves no difference when it was given every element that changed: this is what
     * the assertion that follows it checks. HVDC lines and areas are not looked at: a partial restore writes them all.
     */
    public String findDifference() {
        for (BusState state : busStates) {
            String difference = state.findDifference();
            if (difference != null) {
                return "bus " + state.element.getId() + ": " + difference;
            }
        }
        for (BranchState state : branchStates) {
            String difference = state.findDifference();
            if (difference != null) {
                return "branch " + state.element.getId() + ": " + difference;
            }
        }
        return null;
    }

    /** Restores the saved voltage magnitude and angle of every bus, and nothing else. */
    public void restoreVoltages() {
        System.arraycopy(busVoltages, 0, network.getBusVoltages(), 0, busVoltages.length);
        System.arraycopy(busAngles, 0, network.getBusAngles(), 0, busAngles.length);
        network.onStateWrite();
    }

    /** Restores the saved voltage magnitude and angle of the given buses, and nothing else. */
    public void restoreVoltages(Collection<LfBus> buses) {
        for (LfBus bus : buses) {
            checkSaved(busStates, bus);
            busStates.get(bus.getNum()).restoreVoltage();
        }
    }
}
