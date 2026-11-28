/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

/**
 * The decisions of {@link AutomationSystemOuterLoop} on arrays: an overload management system whose monitored branch
 * is connected at both sides takes, among its trippings in order, the first one whose threshold the monitored current
 * exceeds and that would change its branch: open a branch in service, or close a branch out of service.
 *
 * <p>{@link #trippingDecision} is an expression over primitives; {@link #selectTripping} walks one system's trippings.
 * Nothing reads or writes a network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class AutomationSystemDecisions {

    /** The tripping does not apply. */
    public static final int DECISION_NONE = 0;
    /** Open the branch to operate at both sides. */
    public static final int DECISION_OPEN = 1;
    /** Close the branch to operate at both sides. */
    public static final int DECISION_CLOSE = 2;

    private AutomationSystemDecisions() {
    }

    /**
     * One tripping: it applies when the current is above its threshold and its branch is not already as the tripping
     * wants it (the disabled state is compared to the tripping's opening), then opens a branch in service or closes one
     * out of service. A branch neither in service nor disabled (open at one side) does not apply.
     *
     * @param inService the branch to operate is enabled and connected at both sides
     */
    public static int trippingDecision(double i, double threshold, boolean branchOpen, boolean disabled, boolean inService) {
        return !(i > threshold && branchOpen != disabled) ? DECISION_NONE
             : branchOpen && inService ? DECISION_OPEN
             : !branchOpen && !inService ? DECISION_CLOSE
             : DECISION_NONE;
    }

    /**
     * The tripping a system takes: the first of {@code from} to {@code to - 1} that applies.
     *
     * @return the index of the tripping, -1 when none applies
     */
    public static int selectTripping(double i, int from, int to, double[] threshold, boolean[] branchOpen, boolean[] disabled, boolean[] inService) {
        for (int t = from; t < to; t++) {
            if (trippingDecision(i, threshold[t], branchOpen[t], disabled[t], inService[t]) != DECISION_NONE) {
                return t;
            }
        }
        return -1;
    }
}
