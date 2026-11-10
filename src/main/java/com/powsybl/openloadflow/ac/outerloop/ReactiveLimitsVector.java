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
 * What one check of {@link ReactiveLimitsOuterLoop} reads and decides, as arrays: one entry per controller bus the
 * check looks at, in the order it looks at them. The loop fills the inputs from the network, {@link
 * ReactiveLimitsDecisions} fills the outputs from the inputs alone, and the loop applies the outputs to the network.
 *
 * <p>Nothing here refers to a network object, so the decisions can be taken on these arrays by any engine holding the
 * same values.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class ReactiveLimitsVector {

    /** Number of entries of the current check. */
    public int count;

    // ---- inputs ----

    /** The controller bus. */
    public int[] busNum = new int[0];
    /** The bus's control is on: it is checked as a controlling bus. Off, it is checked as a bus switched to PQ. */
    public boolean[] controlEnabled = new boolean[0];
    /** The bus's generator voltage control controls another bus. */
    public boolean[] remoteController = new boolean[0];
    /** A bus switched to PQ may switch back (none of its generators has a slope). */
    public boolean[] canSwitchToPv = new boolean[0];
    /** The limit a bus switched to PQ stands at, a {@code ReactiveLimitsDecisions.LIMIT_*}. */
    public int[] qLimitType = new int[0];
    /** The switches from PV to PQ the bus already made in this run. */
    public int[] pvPqSwitchCount = new int[0];
    /** Reactive power generated at the bus (pu). */
    public double[] q = new double[0];
    public double[] minQ = new double[0];
    public double[] maxQ = new double[0];
    /** The bus's own voltage (pu). */
    public double[] v = new double[0];
    /** The voltage of the bus its voltage control controls (pu), NaN without one. */
    public double[] controlledV = new double[0];
    /** The target of its voltage control (pu), NaN without one. */
    public double[] targetV = new double[0];
    /** The target Q a realistic-voltage switch freezes: the sum of the generators' own target Q. */
    public double[] initialTargetQ = new double[0];
    /** The target Q a bus switched to PQ is frozen at. */
    public double[] frozenTargetQ = new double[0];

    // ---- inputs of the election of the bus that stays PV, filled for its candidates only ----

    /** Nominal voltage of the controlled bus (of the bus itself without a voltage control). */
    public double[] controlledNominalV = new double[0];
    public double[] targetP = new double[0];
    /** Rank of the bus id among the candidates' ids. */
    public int[] idRank = new int[0];

    // ---- outputs ----

    /** A {@code ReactiveLimitsDecisions.DECISION_*}. */
    public int[] decision = new int[0];
    /** The bus's voltage is set back to 1 pu for the next solve. */
    public boolean[] resetV = new boolean[0];
    /** The target Q the decision freezes, when it freezes one. */
    public double[] qLimit = new double[0];

    /** Starts a check of {@code newCount} entries. */
    public void reset(int newCount) {
        if (newCount > busNum.length) {
            busNum = new int[newCount];
            controlEnabled = new boolean[newCount];
            remoteController = new boolean[newCount];
            canSwitchToPv = new boolean[newCount];
            qLimitType = new int[newCount];
            pvPqSwitchCount = new int[newCount];
            q = new double[newCount];
            minQ = new double[newCount];
            maxQ = new double[newCount];
            v = new double[newCount];
            controlledV = new double[newCount];
            targetV = new double[newCount];
            initialTargetQ = new double[newCount];
            frozenTargetQ = new double[newCount];
            controlledNominalV = new double[newCount];
            targetP = new double[newCount];
            idRank = new int[newCount];
            decision = new int[newCount];
            resetV = new boolean[newCount];
            qLimit = new double[newCount];
        }
        count = newCount;
        Arrays.fill(decision, 0, newCount, ReactiveLimitsDecisions.DECISION_NONE);
        Arrays.fill(resetV, 0, newCount, false);
    }
}
