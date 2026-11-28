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
 * The decisions of {@link IncrementalShuntVoltageControlOuterLoop} on a {@link ShuntVoltageControlVector}: which
 * sections move to bring each controlled bus back into its deadband. The section controllers of a bus move one
 * section at a time, in turn (shunts by decreasing susceptance magnitude, then their controllers), while the remaining
 * voltage difference is out of the deadband, each at most {@code maxSectionShift} moves in a check.
 *
 * <p>Nothing reads or writes a network; the ratchet and the section choice are {@link IncrementalControlDecisions} and
 * {@link DiscretePositionDecisions}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class ShuntVoltageControlDecisions {

    private ShuntVoltageControlDecisions() {
    }

    /** Every bus's decisions, bus after bus. */
    public static void decide(ShuntVoltageControlVector v, int maxSectionShift) {
        for (int k = 0; k < v.controllerCount; k++) {
            v.position[k] = v.initialPosition[k];
        }
        for (int b = 0; b < v.busCount; b++) {
            decideBus(v, b, maxSectionShift);
        }
    }

    static void decideBus(ShuntVoltageControlVector v, int b, int maxSectionShift) {
        // several shunts could control the same bus
        double remainingDiffV = v.diffV[b];
        boolean hasChanged = true;
        while (hasChanged) {
            hasChanged = false;
            for (int s = v.firstShunt[b]; s < v.firstShunt[b + 1]; s++) {
                double sensitivity = v.sensitivity[s];
                for (int k = v.firstController[s]; k < v.firstController[s + 1]; k++) {
                    if (!IncrementalControlDecisions.isOutOfDeadband(remainingDiffV, v.halfTargetDeadband[b])
                            || v.sectionShift[k] >= maxSectionShift) {
                        // in its deadband, or already changed by maximum allowed number of sections shift in this outerloop
                        continue;
                    }
                    double previousB = v.sectionBs[k][v.position[k]];
                    double deltaB = remainingDiffV / sensitivity;
                    if (moveToClosestSection(v, k, deltaB)) {
                        remainingDiffV -= (v.sectionBs[k][v.position[k]] - previousB) * sensitivity;
                        hasChanged = true;
                    }
                }
            }
        }
    }

    /**
     * Moves controller {@code k} by at most one section, to the allowed one whose susceptance is the closest to its
     * susceptance plus {@code deltaB}, if one is closer than {@code |deltaB|}; records the move and steps the ratchet.
     *
     * @return whether the section moved
     */
    static boolean moveToClosestSection(ShuntVoltageControlVector v, int k, double deltaB) {
        double[] values = v.sectionBs[k];
        int position = v.position[k];
        int allowed = v.allowedDirection[k];
        double newB = values[position] + deltaB;
        int newPosition = DiscretePositionDecisions.closestPosition(values, position,
                DiscretePositionDecisions.allowedFrom(allowed, position, v.minPosition[k]),
                DiscretePositionDecisions.allowedTo(allowed, position, values.length),
                1, newB, Math.abs(deltaB));
        if (newPosition == position) {
            return false;
        }
        int direction = IncrementalControlDecisions.directionOf(position, newPosition);
        int count = v.directionChangeCount[k];
        int max = v.maxDirectionChange[k];
        v.directionChangeCount[k] = IncrementalControlDecisions.directionChangeCountAfter(count, max, v.currentDirection[k], direction);
        v.currentDirection[k] = IncrementalControlDecisions.currentDirectionAfter(count, max, v.currentDirection[k], direction);
        v.allowedDirection[k] = IncrementalControlDecisions.allowedDirectionAfter(count, max, allowed, direction);
        v.position[k] = newPosition;
        v.sectionShift[k]++;
        v.addMove(k, newPosition);
        return true;
    }
}
