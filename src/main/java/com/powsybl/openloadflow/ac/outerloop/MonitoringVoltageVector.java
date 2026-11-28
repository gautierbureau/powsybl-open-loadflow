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
 * What one check of {@link MonitoringVoltageOuterLoop} reads and decides, as arrays: one entry per bus the check
 * looks at (a monitored bus, controller element of the generator voltage controls, with its voltage control off and a
 * stand-by automaton), in the order it looks at them. The loop fills the inputs from the network, {@link
 * MonitoringVoltageDecisions} fills the outputs from the inputs alone, and the loop applies the outputs.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class MonitoringVoltageVector {

    /** Number of entries of the current check. */
    public int count;

    // ---- inputs ----

    public int[] busNum = new int[0];
    /** The voltage of the bus the bus's voltage control controls (pu), NaN without one. */
    public double[] controlledV = new double[0];
    /** The automaton's thresholds (pu). */
    public double[] lowThreshold = new double[0];
    public double[] highThreshold = new double[0];
    /** The automaton's targets (pu): the target voltage a switch at the low or the high threshold sets. */
    public double[] lowTargetV = new double[0];
    public double[] highTargetV = new double[0];

    // ---- outputs ----

    /** A {@code MonitoringVoltageDecisions.DECISION_*}. */
    public int[] decision = new int[0];

    /** Starts a check of {@code newCount} entries. */
    public void reset(int newCount) {
        if (newCount > busNum.length) {
            busNum = new int[newCount];
            controlledV = new double[newCount];
            lowThreshold = new double[newCount];
            highThreshold = new double[newCount];
            lowTargetV = new double[newCount];
            highTargetV = new double[newCount];
            decision = new int[newCount];
        }
        count = newCount;
        Arrays.fill(decision, 0, newCount, MonitoringVoltageDecisions.DECISION_NONE);
    }
}
