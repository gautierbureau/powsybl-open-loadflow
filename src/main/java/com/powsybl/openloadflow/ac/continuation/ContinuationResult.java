/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of a continuation power flow run: the traced P-V curve and the estimated maximum loadability
 * (voltage collapse) point.
 *
 * @author Claude
 */
public class ContinuationResult {

    public enum Status {
        /** The nose of the P-V curve was located (maximum loadability / voltage collapse point). */
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

    private final ContinuationPoint nosePoint;

    private final String criticalBusId;

    private final Map<String, Double> tangentParticipationByBus;

    /**
     * Constructor used when the nose is the last traced point (upper branch only, e.g. the stepped continuation).
     */
    public ContinuationResult(Status status, List<ContinuationPoint> points, double maxLoadFactor, String criticalBusId) {
        this(status, points, maxLoadFactor,
                points.isEmpty() ? null : points.get(points.size() - 1),
                criticalBusId, Map.of());
    }

    public ContinuationResult(Status status, List<ContinuationPoint> points, double maxLoadFactor,
                              ContinuationPoint nosePoint, String criticalBusId, Map<String, Double> tangentParticipationByBus) {
        this.status = Objects.requireNonNull(status);
        this.points = List.copyOf(points);
        this.maxLoadFactor = maxLoadFactor;
        this.nosePoint = nosePoint;
        this.criticalBusId = criticalBusId;
        this.tangentParticipationByBus = Map.copyOf(tangentParticipationByBus);
    }

    public Status getStatus() {
        return status;
    }

    /**
     * All the converged points, ordered as traced: the upper (stable) branch from the base case up to the nose,
     * then, for a predictor-corrector continuation, the lower (unstable) branch beyond the nose.
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
     * The estimated voltage collapse / maximum loadability point (the nose of the P-V curve).
     */
    public Optional<ContinuationPoint> getNosePoint() {
        return Optional.ofNullable(nosePoint);
    }

    /**
     * The id of the bus driving the collapse.
     */
    public Optional<String> getCriticalBusId() {
        return Optional.ofNullable(criticalBusId);
    }

    /**
     * Normalized voltage participation factors (per bus) derived from the tangent vector at the nose point.
     * A high value means the bus voltage is very sensitive to the load increase near collapse. Empty when the
     * continuation does not compute a tangent (e.g. the stepped continuation).
     */
    public Map<String, Double> getTangentParticipationByBus() {
        return tangentParticipationByBus;
    }
}
