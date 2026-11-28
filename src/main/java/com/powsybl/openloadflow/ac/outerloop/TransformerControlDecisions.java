/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.openloadflow.lf.outerloop.IncrementalControlDecisions;
import com.powsybl.openloadflow.network.DiscretePositionDecisions;

/**
 * The decisions of the incremental transformer controls on a {@link TransformerControlVector}: which taps move to
 * bring each controlled element (a bus's voltage, a branch's reactive power) back into its deadband. An element with
 * one controller moves it at once to the tap whose ratio is the closest to the one the sensitivity asks for, within the
 * maximum tap shift. An element with several controllers moves them one tap at a time, in turn, while the remaining
 * difference is out of the deadband and a controller can still move, so that the change is spread among them.
 *
 * <p>Nothing reads or writes a network; the ratchet and the tap choice are {@link IncrementalControlDecisions} and
 * {@link DiscretePositionDecisions}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class TransformerControlDecisions {

    private TransformerControlDecisions() {
    }

    /**
     * Every controlled element's decisions, one after the other.
     *
     * @param minSensitivity below it a controller is insensitive and does not move; 0 to never stop on it
     */
    public static void decide(TransformerControlVector v, int maxTapShift, double minSensitivity) {
        for (int c = 0; c < v.controllerCount; c++) {
            v.tapIndex[c] = v.initialTapIndex[c];
            v.r1[c] = v.initialR1[c];
        }
        for (int b = 0; b < v.controlledCount; b++) {
            decideControlled(v, b, maxTapShift, minSensitivity);
        }
    }

    static void decideControlled(TransformerControlVector v, int b, int maxTapShift, double minSensitivity) {
        int first = v.firstController[b];
        int end = v.firstController[b + 1];
        if (end - first == 1) {
            // only one transformer controls the element
            double sensitivity = v.sensitivity[first];
            double deltaR1 = v.diff[b] / sensitivity;
            if (IncrementalControlDecisions.isInsensitive(sensitivity, minSensitivity)) {
                v.insensitive[first] = true;
                return;
            }
            if (moveToClosestTap(v, first, v.r1[first] + deltaR1, maxTapShift) && isAtTapLimit(v, first)) {
                v.allControllersAtLimit[b] = true;
            }
        } else if (end - first > 1) {
            // several transformers control the same bus, to give to chance to all controllers to adjust controlled bus
            // voltage and to help distributing tap changes among all controllers, we try to adjust voltage by allowing
            // one tap change at a time for each controller
            double remainingDiffV = v.diff[b];
            boolean hasChanged = true;
            while (hasChanged) {
                hasChanged = false;
                for (int c = first; c < end; c++) {
                    if (Math.abs(v.tapIndex[c] - v.initialTapIndex[c]) >= maxTapShift
                            || !IncrementalControlDecisions.isOutOfDeadband(remainingDiffV, v.halfTargetDeadband[b])) {
                        continue;
                    }
                    double sensitivity = v.sensitivity[c];
                    if (IncrementalControlDecisions.isInsensitive(sensitivity, minSensitivity)) {
                        v.insensitive[c] = true;
                        continue;
                    }
                    double previousR1 = v.r1[c];
                    double deltaR1 = remainingDiffV / sensitivity;
                    if (moveToClosestTap(v, c, previousR1 + deltaR1, 1)) {
                        remainingDiffV += -(v.r1[c] - previousR1) * sensitivity;
                        hasChanged = true;
                    }
                }
            }
            boolean allAtLimit = true;
            for (int c = first; c < end; c++) {
                allAtLimit &= isAtTapLimit(v, c);
            }
            v.allControllersAtLimit[b] = allAtLimit;
        }
    }

    public static boolean isAtTapLimit(TransformerControlVector v, int c) {
        return v.tapIndex[c] == 0 || v.tapIndex[c] == v.tapR1s[c].length - 1;
    }

    /**
     * Moves controller {@code c} to the allowed tap whose r1 is the closest to {@code newR1}, within {@code maxTapShift}
     * taps, records the move and steps the ratchet.
     *
     * @return whether the tap moved
     */
    static boolean moveToClosestTap(TransformerControlVector v, int c, double newR1, int maxTapShift) {
        double[] values = v.tapR1s[c];
        int index = v.tapIndex[c];
        int allowed = v.allowedDirection[c];
        int newIndex = DiscretePositionDecisions.closestPosition(values, index,
                DiscretePositionDecisions.allowedFrom(allowed, index, 0),
                DiscretePositionDecisions.allowedTo(allowed, index, values.length),
                maxTapShift, newR1, Math.abs(newR1 - values[index]));
        if (newIndex == index) {
            return false;
        }
        int direction = IncrementalControlDecisions.directionOf(index, newIndex);
        int count = v.directionChangeCount[c];
        int max = v.maxDirectionChange[c];
        v.directionChangeCount[c] = IncrementalControlDecisions.directionChangeCountAfter(count, max, v.currentDirection[c], direction);
        v.currentDirection[c] = IncrementalControlDecisions.currentDirectionAfter(count, max, v.currentDirection[c], direction);
        v.allowedDirection[c] = IncrementalControlDecisions.allowedDirectionAfter(count, max, allowed, direction);
        v.tapIndex[c] = newIndex;
        v.r1[c] = values[newIndex];
        v.addMove(c, newIndex);
        return true;
    }
}
