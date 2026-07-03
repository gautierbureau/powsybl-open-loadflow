/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of a {@link ContinuationPowerFlow} run: the traced P-V curve and the estimated maximum loadability
 * (voltage collapse) point.
 *
 * @author Claude
 */
public class ContinuationResult {

    public enum Status {
        /** The nose of the P-V curve was located (the adaptive step converged below its minimum value). */
        NOSE_POINT_REACHED,
        /** The maximum number of steps was reached before the nose could be located. */
        MAX_STEPS_REACHED,
        /** The base case (lambda = 0) did not converge, no continuation could be performed. */
        BASE_CASE_NOT_CONVERGED,
        /** No load participates in the requested load increase direction. */
        NO_PARTICIPATING_LOAD
    }

    private final Status status;

    private final List<ContinuationPoint> points;

    private final double maxLoadFactor;

    private final String criticalBusId;

    public ContinuationResult(Status status, List<ContinuationPoint> points, double maxLoadFactor, String criticalBusId) {
        this.status = Objects.requireNonNull(status);
        this.points = List.copyOf(points);
        this.maxLoadFactor = maxLoadFactor;
        this.criticalBusId = criticalBusId;
    }

    public Status getStatus() {
        return status;
    }

    /**
     * All the converged points of the P-V curve, ordered by increasing load factor, starting with the base case.
     */
    public List<ContinuationPoint> getPoints() {
        return points;
    }

    /**
     * The maximum load increase factor (lambda) that could be reached, i.e. the loadability margin.
     * {@code 0} means the collapse was reached at the base case load level.
     */
    public double getMaxLoadFactor() {
        return maxLoadFactor;
    }

    /**
     * The last converged point, i.e. the estimated voltage collapse / maximum loadability point.
     */
    public Optional<ContinuationPoint> getNosePoint() {
        return points.isEmpty() ? Optional.empty() : Optional.of(points.get(points.size() - 1));
    }

    /**
     * The id of the weakest bus (lowest voltage) at the nose point, a proxy for the bus driving the collapse.
     */
    public Optional<String> getCriticalBusId() {
        return Optional.ofNullable(criticalBusId);
    }
}
