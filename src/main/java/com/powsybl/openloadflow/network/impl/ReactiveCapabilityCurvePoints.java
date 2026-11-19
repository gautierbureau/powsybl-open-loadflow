/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.ReactiveCapabilityCurve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The points of a reactive capability curve as arrays sorted by active power, read once from the curve.
 *
 * <p>The limits at an active power are computed with the arithmetic of IIDM's {@code ReactiveCapabilityCurveImpl}
 * and {@code ReactiveCapabilityCurveUtil.extrapolateReactiveLimitsSlope}, step by step, so they are the same to the
 * last bit: a point of the curve gives its own limits, an active power between two points the interpolation of their
 * limits, and an active power outside the curve the limits of its first or last point, or, when extrapolating, the
 * slope of its first or last two points continued.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
final class ReactiveCapabilityCurvePoints {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveCapabilityCurvePoints.class);

    private final double[] p;

    private final double[] minQ;

    private final double[] maxQ;

    private ReactiveCapabilityCurvePoints(ReactiveCapabilityCurve curve) {
        Objects.requireNonNull(curve);
        int count = curve.getPointCount();
        if (count < 2) {
            throw new IllegalStateException("points size should be >= 2");
        }
        p = new double[count];
        minQ = new double[count];
        maxQ = new double[count];
        int i = 0;
        for (ReactiveCapabilityCurve.Point point : curve.getPoints()) { // sorted by active power
            p[i] = point.getP();
            minQ[i] = point.getMinQ();
            maxQ[i] = point.getMaxQ();
            i++;
        }
    }

    static ReactiveCapabilityCurvePoints of(ReactiveCapabilityCurve curve) {
        return new ReactiveCapabilityCurvePoints(curve);
    }

    /**
     * The comparator of the points of {@code ReactiveCapabilityCurveImpl}.
     */
    private static int compare(double p1, double p2) {
        return p1 - p2 == 0 ? 0 : Double.compare(p1, p2);
    }

    double getMinQ(double activePower, boolean extrapolate) {
        return getLimit(activePower, extrapolate, false);
    }

    double getMaxQ(double activePower, boolean extrapolate) {
        return getLimit(activePower, extrapolate, true);
    }

    private double getLimit(double activePower, boolean extrapolate, boolean max) {
        double[] q = max ? maxQ : minQ;
        int last = p.length - 1;

        // a point of the curve
        for (int i = 0; i <= last; i++) {
            if (compare(activePower, p[i]) == 0) {
                return q[i];
            }
        }

        // between the first and the last point: the points around it, which are not the active power
        if (activePower >= p[0] && activePower <= p[last]) {
            int floor = 0;
            while (compare(p[floor + 1], activePower) < 0) {
                floor++;
            }
            int ceiling = floor + 1;
            return q[floor] + (q[ceiling] - q[floor]) / (p[ceiling] - p[floor]) * (activePower - p[floor]);
        }

        // outside the curve
        if (extrapolate) {
            return extrapolate(activePower, max);
        } else if (activePower < p[0]) {
            return q[0];
        } else {
            return q[last];
        }
    }

    private double extrapolate(double activePower, boolean max) {
        int bound;
        int bis;
        if (activePower < p[0]) {
            bound = 0;
            bis = 1;
        } else if (activePower > p[p.length - 1]) {
            bound = p.length - 1;
            bis = p.length - 2;
        } else {
            throw new IllegalStateException();
        }
        double slopeMinQ = (minQ[bis] - minQ[bound]) / (p[bis] - p[bound]);
        double slopeMaxQ = (maxQ[bis] - maxQ[bound]) / (p[bis] - p[bound]);
        double extrapolatedMinQ = minQ[bound] + slopeMinQ * (activePower - p[bound]);
        double extrapolatedMaxQ = maxQ[bound] + slopeMaxQ * (activePower - p[bound]);
        if (extrapolatedMinQ <= extrapolatedMaxQ) {
            return max ? extrapolatedMaxQ : extrapolatedMinQ;
        } else { // intersecting limits when extrapolated: the mean as both limits
            LOGGER.warn("Extrapolation of reactive capability curve leads to minQ > maxQ, correcting to minQ = maxQ");
            return (extrapolatedMinQ + extrapolatedMaxQ) / 2;
        }
    }
}
