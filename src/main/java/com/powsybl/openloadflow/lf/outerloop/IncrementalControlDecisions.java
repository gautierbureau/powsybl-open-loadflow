/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import com.powsybl.openloadflow.network.DiscretePositionDecisions;

/**
 * The rules the incremental voltage control outer loops share, as functions of numbers: the target deadband of a
 * controlled bus, whether its voltage is out of it, whether a controller is too insensitive to act, and the ratchet
 * that stops a controller from going back and forth ({@link IncrementalContextData.ControllerContext}).
 *
 * <p>The ratchet: a controller may change direction {@code maxDirectionChange} times; once it has, each move fixes its
 * allowed direction to the direction of that move. Its state is the number of direction changes so far, the direction
 * of the last move ({@link #DIRECTION_NONE} before the first one) and the allowed direction (a {@code
 * DiscretePositionDecisions.ALLOWED_*}).
 *
 * <p>Every function is an expression over primitives. Nothing reads or writes a network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class IncrementalControlDecisions {

    /** No move yet. */
    public static final int DIRECTION_NONE = 0;
    public static final int DIRECTION_INCREASE = 1;
    public static final int DIRECTION_DECREASE = -1;

    private IncrementalControlDecisions() {
    }

    /** The direction of a move from position {@code from} to position {@code to}, which differ. */
    public static int directionOf(int from, int to) {
        return to - from > 0 ? DIRECTION_INCREASE : DIRECTION_DECREASE;
    }

    /** The number of direction changes after a move in {@code direction}. */
    public static int directionChangeCountAfter(int directionChangeCount, int maxDirectionChange, int currentDirection, int direction) {
        return directionChangeCount < maxDirectionChange && currentDirection != DIRECTION_NONE && currentDirection != direction
                ? directionChangeCount + 1
                : directionChangeCount;
    }

    /** The direction of the last move after a move in {@code direction}: not recorded any more once the ratchet holds. */
    public static int currentDirectionAfter(int directionChangeCount, int maxDirectionChange, int currentDirection, int direction) {
        return directionChangeCount < maxDirectionChange ? direction : currentDirection;
    }

    /** The allowed direction after a move in {@code direction}: the move's own once the ratchet holds. */
    public static int allowedDirectionAfter(int directionChangeCount, int maxDirectionChange, int allowedDirection, int direction) {
        return directionChangeCount < maxDirectionChange ? allowedDirection
             : direction == DIRECTION_INCREASE ? DiscretePositionDecisions.ALLOWED_INCREASE
             : DiscretePositionDecisions.ALLOWED_DECREASE;
    }

    /**
     * Half the target deadband of a voltage control (pu): its own deadband, or {@code minTargetDeadbandKv} at the
     * controlled bus's nominal voltage when it has none.
     *
     * @param targetDeadband the control's deadband (pu), NaN when it has none
     */
    public static double halfVoltageTargetDeadband(double targetDeadband, double nominalV, double minTargetDeadbandKv) {
        return (Double.isNaN(targetDeadband) ? minTargetDeadbandKv / nominalV : targetDeadband) / 2;
    }

    /** Whether a voltage {@code diffV = targetV - v} away from its target is out of the deadband. */
    public static boolean isOutOfDeadband(double diffV, double halfTargetDeadband) {
        return Math.abs(diffV) > halfTargetDeadband;
    }

    /** Whether a controller's sensitivity is too small to act on. */
    public static boolean isInsensitive(double sensitivity, double minSensitivity) {
        return Math.abs(sensitivity) < minSensitivity;
    }
}
