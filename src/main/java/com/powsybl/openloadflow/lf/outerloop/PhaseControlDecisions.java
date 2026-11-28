/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import com.powsybl.openloadflow.util.PerUnit;

/**
 * The decisions of the phase control outer loops, as functions of numbers, for the two modes of a phase shifter: a
 * current limiter acts when the controlled current is above its limit, an active power controller when the controlled
 * active power is out of its target deadband. The discrete loop ({@code PhaseControlOuterLoop}) moves a limiter one tap
 * against the current's sensitivity; the incremental loops move a tap by the phase shift the sensitivity asks for.
 *
 * <p>Every function is an expression over primitives. Nothing reads or writes a network; which tap a phase shift
 * reaches is {@link com.powsybl.openloadflow.network.DiscretePositionDecisions}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class PhaseControlDecisions {

    /** Nothing changes for the phase shifter. */
    public static final int DECISION_NONE = 0;
    /** One tap so that a1 increases (discrete limiter, negative or zero sensitivity of the current to a1). */
    public static final int DECISION_SHIFT_ONE_TAP_UP = 1;
    /** One tap so that a1 decreases (discrete limiter, positive sensitivity of the current to a1). */
    public static final int DECISION_SHIFT_ONE_TAP_DOWN = 2;
    /** To the tap whose a1 is the closest to a1 plus the phase shift (incremental active power controller). */
    public static final int DECISION_REACH_PHASE_SHIFT = 3;
    /** To the first tap at or beyond a1 plus the phase shift (incremental current limiter). */
    public static final int DECISION_EXCEED_PHASE_SHIFT = 4;
    /** Out of its target, but the sensitivity is too small to act on (incremental loops). */
    public static final int DECISION_INSENSITIVE = 5;

    /** The smallest target deadband of an active power control: 1 MW. */
    public static final double MIN_TARGET_DEADBAND = 1 / PerUnit.SB;

    /** Below this sensitivity of the controlled flow to the phase shift, no tap is moved. */
    public static final double SENSI_EPS = 1e-6;

    private PhaseControlDecisions() {
    }

    /** Whether a current limiter acts: the current is above its limit. */
    public static boolean isAboveLimit(double i, double limit) {
        return i > limit;
    }

    /** The discrete limiter: one tap against the sensitivity of the current to a1, when the current is above its limit. */
    public static int discreteLimiterDecision(double i, double limit, boolean sensitivityPositive) {
        return !isAboveLimit(i, limit) ? DECISION_NONE
             : sensitivityPositive ? DECISION_SHIFT_ONE_TAP_DOWN
             : DECISION_SHIFT_ONE_TAP_UP;
    }

    public static double halfTargetDeadband(double targetDeadband) {
        return Math.max(targetDeadband, MIN_TARGET_DEADBAND) / 2;
    }

    /** Whether an active power controller acts: the active power is out of its target deadband. */
    public static boolean isOutOfDeadband(double p, double targetP, double halfTargetDeadband) {
        return Math.abs(p - targetP) > halfTargetDeadband;
    }

    /** Whether an incremental loop needs the sensitivity of the controlled flow: the control acts. */
    public static boolean needsSensitivity(boolean limiter, double value, double target, double halfTargetDeadband) {
        return limiter ? isAboveLimit(value, target) : isOutOfDeadband(value, target, halfTargetDeadband);
    }

    public static boolean isSensitive(double sensitivity) {
        return Math.abs(sensitivity) > SENSI_EPS;
    }

    /**
     * The phase shift (radians) that brings the controlled flow to its target, from the sensitivity of the flow to a
     * one degree shift.
     */
    public static double phaseShift(double value, double target, double sensitivity) {
        return Math.toRadians((target - value) / sensitivity);
    }

    /** An incremental loop's decision, the sensitivity being read only when {@link #needsSensitivity} holds. */
    public static int incrementalDecision(boolean limiter, double value, double target, double halfTargetDeadband, double sensitivity) {
        return !needsSensitivity(limiter, value, target, halfTargetDeadband) ? DECISION_NONE
             : !isSensitive(sensitivity) ? DECISION_INSENSITIVE
             : limiter ? DECISION_EXCEED_PHASE_SHIFT
             : DECISION_REACH_PHASE_SHIFT;
    }

    /**
     * Whether a limiter's tap change shifts the current of another limiter above its limit significantly: by more than
     * {@code margin} of its excess. Only a warning.
     */
    public static boolean isSignificantCrossImpact(double phaseShift, double crossSensitivity, double otherI, double otherLimit, double margin) {
        return Math.toDegrees(phaseShift) * crossSensitivity > margin * (otherI - otherLimit);
    }

    /** Every entry's decision, from the gathered values and sensitivities. */
    public static void decideIncrementalEntries(PhaseControlVector v) {
        for (int k = 0; k < v.count; k++) {
            decideIncrementalEntry(v, k);
        }
    }

    /** The decision of entry {@code k} of an incremental loop, and its phase shift when it moves a tap. */
    public static void decideIncrementalEntry(PhaseControlVector v, int k) {
        int decision = incrementalDecision(v.limiter[k], v.value[k], v.target[k], v.halfTargetDeadband[k], v.sensitivity[k]);
        v.decision[k] = decision;
        v.phaseShift[k] = decision == DECISION_REACH_PHASE_SHIFT || decision == DECISION_EXCEED_PHASE_SHIFT
                ? phaseShift(v.value[k], v.target[k], v.sensitivity[k])
                : Double.NaN;
    }
}
