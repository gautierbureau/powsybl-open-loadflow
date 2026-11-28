/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import java.util.Arrays;

/**
 * What one check of a phase control outer loop reads and decides, as arrays: one entry per phase shifter the check
 * looks at, in the order it looks at them (the current limiters, then the active power controllers, for the
 * incremental loops). The loop fills the inputs from the network, {@link PhaseControlDecisions} fills the outputs from
 * the inputs alone, and the loop applies the outputs.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class PhaseControlVector {

    /** Number of entries of the current check. */
    public int count;

    // ---- inputs ----

    /** The controller branch. */
    public int[] branchNum = new int[0];
    /** The branch whose flow is controlled (the controller branch itself for a current limiter). */
    public int[] controlledBranchNum = new int[0];
    /** The side of the controlled branch whose flow is controlled, 1 or 2. */
    public int[] controlledSide = new int[0];
    /** A current limiter; otherwise an active power controller. */
    public boolean[] limiter = new boolean[0];
    /** The controlled current (limiter) or active power (controller), on the controlled side (pu). */
    public double[] value = new double[0];
    /** The current limit or the active power target (pu). */
    public double[] target = new double[0];
    /** Half the target deadband of an active power controller (pu), NaN for a limiter. */
    public double[] halfTargetDeadband = new double[0];
    /**
     * The sensitivity of the controlled flow to a one degree shift (incremental loops), NaN when the control does
     * not act and it is not read.
     */
    public double[] sensitivity = new double[0];

    // ---- outputs ----

    /** A {@code PhaseControlDecisions.DECISION_*}. */
    public int[] decision = new int[0];
    /** The phase shift a tap move aims at (radians), NaN when no tap moves. */
    public double[] phaseShift = new double[0];

    /** Starts a check of {@code newCount} entries. */
    public void reset(int newCount) {
        if (newCount > branchNum.length) {
            branchNum = new int[newCount];
            limiter = new boolean[newCount];
            controlledBranchNum = new int[newCount];
            controlledSide = new int[newCount];
            value = new double[newCount];
            target = new double[newCount];
            halfTargetDeadband = new double[newCount];
            sensitivity = new double[newCount];
            decision = new int[newCount];
            phaseShift = new double[newCount];
        }
        count = newCount;
        Arrays.fill(halfTargetDeadband, 0, newCount, Double.NaN);
        Arrays.fill(sensitivity, 0, newCount, Double.NaN);
        Arrays.fill(decision, 0, newCount, PhaseControlDecisions.DECISION_NONE);
        Arrays.fill(phaseShift, 0, newCount, Double.NaN);
    }
}
