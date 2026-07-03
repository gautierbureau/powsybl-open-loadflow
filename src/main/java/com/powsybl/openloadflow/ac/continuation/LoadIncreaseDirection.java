/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

import com.powsybl.openloadflow.network.LfLoad;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Defines the direction in which the load is increased along the continuation, i.e. which loads participate
 * and with which relative weight. This is what allows stressing only a subset or a zone of the network.
 *
 * <p>At a load increase factor {@code lambda}, a participating load's target is scaled to
 * {@code target0 * (1 + lambda * weight)}, where {@code target0} is the base case target and {@code weight}
 * the value returned by {@link #getWeight(LfLoad)}. A weight of {@code 0} means the load does not participate.</p>
 *
 * @author Claude
 */
@FunctionalInterface
public interface LoadIncreaseDirection {

    /**
     * Participation weight of a load in the load increase direction. {@code 0} means the load does not participate.
     */
    double getWeight(LfLoad load);

    /**
     * All loads participate uniformly (each scaled proportionally to its own base value).
     */
    static LoadIncreaseDirection allLoads() {
        return load -> 1.0;
    }

    /**
     * Only the loads whose id belongs to {@code loadIds} participate, uniformly.
     */
    static LoadIncreaseDirection ofLoadIds(Set<String> loadIds) {
        Objects.requireNonNull(loadIds);
        return load -> loadIds.contains(load.getId()) ? 1.0 : 0.0;
    }

    /**
     * Only the loads connected to a bus whose id belongs to {@code busIds} participate, uniformly.
     * Useful to stress a given zone of the network.
     */
    static LoadIncreaseDirection ofBusIds(Set<String> busIds) {
        Objects.requireNonNull(busIds);
        return load -> busIds.contains(load.getBus().getId()) ? 1.0 : 0.0;
    }

    /**
     * Each load participates with a caller-provided weight (defaulting to {@code 0}, i.e. not participating,
     * when the load id is absent from the map).
     */
    static LoadIncreaseDirection weighted(Map<String, Double> weightByLoadId) {
        Objects.requireNonNull(weightByLoadId);
        return load -> weightByLoadId.getOrDefault(load.getId(), 0.0);
    }
}
