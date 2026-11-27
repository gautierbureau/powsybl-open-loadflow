/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

import com.powsybl.openloadflow.network.LfGenerator;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Defines how generation is increased along the continuation to pick up (part of) the load increase, i.e. which
 * generators participate and with which relative weight.
 *
 * <p>At a load increase factor {@code lambda}, a participating generator's active power target is scaled to
 * {@code target0 * (1 + lambda * weight)}. Whatever the participating generation does not cover is absorbed by
 * the slack bus, so the continuation stays well-posed. Participation typically moves the collapse point compared
 * to the default {@link #none()} (slack-only) behaviour, since local generation relieves the stressed corridors.</p>
 *
 * @author Claude
 */
@FunctionalInterface
public interface GenerationParticipation {

    /**
     * Participation weight of a generator. {@code 0} means the generator does not participate (its target is
     * held constant and, if it is the slack, it still absorbs the residual).
     */
    double getWeight(LfGenerator generator);

    /**
     * No generator participates: the single slack bus absorbs the whole load increase.
     */
    static GenerationParticipation none() {
        return generator -> 0.0;
    }

    /**
     * All generators participate uniformly (each scaled proportionally to its own base target).
     */
    static GenerationParticipation allGenerators() {
        return generator -> 1.0;
    }

    /**
     * Only the generators whose id belongs to {@code generatorIds} participate, uniformly.
     */
    static GenerationParticipation ofGeneratorIds(Set<String> generatorIds) {
        Objects.requireNonNull(generatorIds);
        return generator -> generatorIds.contains(generator.getId()) ? 1.0 : 0.0;
    }

    /**
     * Each generator participates with a caller-provided weight (defaulting to {@code 0} when its id is absent).
     */
    static GenerationParticipation weighted(Map<String, Double> weightByGeneratorId) {
        Objects.requireNonNull(weightByGeneratorId);
        return generator -> weightByGeneratorId.getOrDefault(generator.getId(), 0.0);
    }
}
