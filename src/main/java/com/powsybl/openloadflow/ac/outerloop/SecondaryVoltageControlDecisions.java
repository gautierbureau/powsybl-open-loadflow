/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import java.util.Arrays;

/**
 * The decisions of {@link SecondaryVoltageControlOuterLoop} around its zone system, as functions of numbers: which
 * zones are adjusted, which are given up as stalled, and how the target voltage changes the zone system asks for are
 * clipped. The zone system itself (a dense linear solve over every adjusted zone) stays a matrix computation.
 *
 * <p>A zone's state is its pilot point voltage difference and the reactive power position {@code k} of each of its
 * controller buses: -1 at the minimum reactive power, 1 at the maximum. A zone is adjusted while its pilot point is
 * away from its target and its controller buses can still move that way, or, once the target is reached, while their
 * {@code k} are not aligned and the zone is not saturated. It is given up after {@link #MAX_NO_PROGRESS_COUNT}
 * adjustments in a row without progress.
 *
 * <p>The rules ({@link #qToK}, {@link #shouldAdjust}, {@link #hasProgressed}, {@link #noProgressCountAfter}, the
 * clipping) are expressions over primitives. Nothing reads or writes a network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class SecondaryVoltageControlDecisions {

    public static final double DV_EPS = 1E-4; // 0.1 kV
    public static final double DK_DIFF_MAX_EPS = 1E-3; // 1 MVar
    public static final double K_SATURATION_EPS = 5E-2;
    public static final double MAX_TARGET_VOLTAGE_STEP_KV = 2;

    /**
     * A zone error (pilot point voltage difference and controller buses k difference) has to decrease by at least this
     * ratio from one adjustment to the next one to be considered as progressing.
     */
    public static final double MIN_PROGRESS_RATIO = 1E-2;

    /**
     * Number of consecutive adjustments without progress after which a zone is considered as stalled.
     */
    public static final int MAX_NO_PROGRESS_COUNT = 2;

    private SecondaryVoltageControlDecisions() {
    }

    /** The reactive power position of a controller bus: -1 at its minimum, 1 at its maximum. */
    public static double qToK(double q, double minQ, double maxQ) {
        return (2 * q - maxQ - minQ) / (maxQ - minQ);
    }

    /** The spread of the controller buses' {@code k}: the largest minus the smallest. */
    public static double dkDiffMax(double[] ks) {
        return Arrays.stream(ks).max().orElseThrow() - Arrays.stream(ks).min().orElseThrow();
    }

    public static double kAverage(double[] ks) {
        return Arrays.stream(ks).average().orElseThrow();
    }

    /** Whether every controller bus is beyond its maximum reactive power, or every one beyond its minimum. */
    public static boolean allAtLimits(double[] ks) {
        return Arrays.stream(ks).allMatch(k -> k > 1) || Arrays.stream(ks).allMatch(k -> k < -1);
    }

    /**
     * Whether a zone is adjusted: away from its pilot point target and not saturated in the direction it should move,
     * or at its target with controller buses not aligned, not all at a limit and not globally saturated.
     */
    public static boolean shouldAdjust(double pilotDv, double kAverage, double dkDiffMax, boolean allAtLimits) {
        if (Math.abs(pilotDv) > DV_EPS) {
            return pilotDv > 0 && kAverage < 1 - K_SATURATION_EPS
                    || pilotDv < 0 && kAverage > -1 + K_SATURATION_EPS;
        }
        if (allAtLimits) {
            return false;
        }
        // Once the pilot target is reached, do not keep adjusting a globally saturated zone just to improve reactive
        // power sharing.
        if (kAverage <= -1 + K_SATURATION_EPS || kAverage >= 1 - K_SATURATION_EPS) {
            return false;
        }
        return Math.abs(dkDiffMax) > DK_DIFF_MAX_EPS;
    }

    /** Whether a zone's error decreased enough since its previous adjustment, on either of its two parts. */
    public static boolean hasProgressed(double pilotDvAbs, double dkDiffMax, double previousPilotDvAbs, double previousDkDiffMax) {
        return pilotDvAbs < previousPilotDvAbs * (1 - MIN_PROGRESS_RATIO)
                || dkDiffMax < previousDkDiffMax * (1 - MIN_PROGRESS_RATIO);
    }

    /** The adjustments in a row without progress, after this one. Without a previous adjustment, it is progress. */
    public static int noProgressCountAfter(boolean hasPrevious, double pilotDvAbs, double dkDiffMax,
                                           double previousPilotDvAbs, double previousDkDiffMax, int noProgressCount) {
        return !hasPrevious || hasProgressed(pilotDvAbs, dkDiffMax, previousPilotDvAbs, previousDkDiffMax) ? 0 : noProgressCount + 1;
    }

    /** Whether a zone is given up: too many adjustments in a row without progress. */
    public static boolean isStalled(int noProgressCount) {
        return noProgressCount >= MAX_NO_PROGRESS_COUNT;
    }

    /** Whether a target voltage change (pu) is beyond the largest step at the bus's nominal voltage. */
    public static boolean isStepClipped(double dv, double nominalV) {
        double stepKv = dv * nominalV;
        return stepKv > MAX_TARGET_VOLTAGE_STEP_KV || stepKv < -MAX_TARGET_VOLTAGE_STEP_KV;
    }

    /** A target voltage change (pu) bounded by the largest step at the bus's nominal voltage. */
    public static double clippedStep(double dv, double nominalV) {
        double stepKv = dv * nominalV;
        return stepKv > MAX_TARGET_VOLTAGE_STEP_KV ? MAX_TARGET_VOLTAGE_STEP_KV / nominalV
             : stepKv < -MAX_TARGET_VOLTAGE_STEP_KV ? -MAX_TARGET_VOLTAGE_STEP_KV / nominalV
             : dv;
    }

    public static boolean isOutOfPlausibleRange(double targetV, double minPlausibleTargetVoltage, double maxPlausibleTargetVoltage) {
        return targetV < minPlausibleTargetVoltage || targetV > maxPlausibleTargetVoltage;
    }

    /** A target voltage bounded to the plausible range. */
    public static double clipToPlausibleRange(double targetV, double minPlausibleTargetVoltage, double maxPlausibleTargetVoltage) {
        return targetV < minPlausibleTargetVoltage ? minPlausibleTargetVoltage
             : targetV > maxPlausibleTargetVoltage ? maxPlausibleTargetVoltage
             : targetV;
    }
}
