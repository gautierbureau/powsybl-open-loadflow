/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

/**
 * The decisions of {@link MonitoringVoltageOuterLoop}, as functions of numbers: a bus whose static var compensator
 * monitors a voltage, with its voltage control off, switches it on at the automaton's high target when the controlled
 * voltage is above the high threshold, at the low target when it is below the low threshold.
 *
 * <p>{@link #decision} is an expression over primitives; {@link #decideEntries} walks a {@link
 * MonitoringVoltageVector}. Nothing reads or writes a network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class MonitoringVoltageDecisions {

    /** The voltage is within the thresholds, or undefined. */
    public static final int DECISION_NONE = 0;
    /** Above the high threshold: voltage control on, at the high target. */
    public static final int DECISION_TO_PV_HIGH = 1;
    /** Below the low threshold: voltage control on, at the low target. */
    public static final int DECISION_TO_PV_LOW = 2;
    /**
     * Above the high threshold and below the low one (thresholds the wrong way round): switched at the high target,
     * then at the low one, which stays.
     */
    public static final int DECISION_TO_PV_HIGH_THEN_LOW = 3;

    private MonitoringVoltageDecisions() {
    }

    public static int decision(double controlledV, double lowThreshold, double highThreshold) {
        boolean above = controlledV > highThreshold;
        boolean below = controlledV < lowThreshold;
        return above && below ? DECISION_TO_PV_HIGH_THEN_LOW
             : above ? DECISION_TO_PV_HIGH
             : below ? DECISION_TO_PV_LOW
             : DECISION_NONE;
    }

    public static boolean switchesAtHigh(int decision) {
        return decision == DECISION_TO_PV_HIGH || decision == DECISION_TO_PV_HIGH_THEN_LOW;
    }

    public static boolean switchesAtLow(int decision) {
        return decision == DECISION_TO_PV_LOW || decision == DECISION_TO_PV_HIGH_THEN_LOW;
    }

    /** Every entry's decision. */
    public static void decideEntries(MonitoringVoltageVector m) {
        for (int i = 0; i < m.count; i++) {
            m.decision[i] = decision(m.controlledV[i], m.lowThreshold[i], m.highThreshold[i]);
        }
    }

    /** Whether a bus switches: the solver must run again. */
    public static boolean changesNetwork(MonitoringVoltageVector m) {
        for (int i = 0; i < m.count; i++) {
            if (m.decision[i] != DECISION_NONE) {
                return true;
            }
        }
        return false;
    }
}
