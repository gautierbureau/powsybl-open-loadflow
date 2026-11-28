/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import com.powsybl.openloadflow.network.LfHvdc;

/**
 * The decisions of the HVDC AC emulation limits outer loops (AC and DC), as functions of numbers: a line in linear
 * mode saturates when its flow passes the active power limit in its direction; a saturated line goes back to linear
 * mode when a flow is back within a limit, or saturates the other way when its flow reversed.
 *
 * <p>Every function is an expression over primitives. Nothing reads or writes a network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class HvdcAcEmulationDecisions {

    public static final int STATUS_LINEAR_MODE = 0;
    public static final int STATUS_SATURATION_MODE_FROM_CS1_TO_CS2 = 1;
    public static final int STATUS_SATURATION_MODE_FROM_CS2_TO_CS1 = 2;
    public static final int STATUS_FROZEN = 3;

    /** The status stays. */
    public static final int DECISION_NONE = 0;
    public static final int DECISION_TO_SATURATION_FROM_CS1_TO_CS2 = 1;
    public static final int DECISION_TO_SATURATION_FROM_CS2_TO_CS1 = 2;
    public static final int DECISION_TO_LINEAR_MODE = 3;

    private HvdcAcEmulationDecisions() {
    }

    public static int statusCode(LfHvdc.AcEmulationControl.AcEmulationStatus status) {
        return switch (status) {
            case LINEAR_MODE -> STATUS_LINEAR_MODE;
            case SATURATION_MODE_FROM_CS1_TO_CS2 -> STATUS_SATURATION_MODE_FROM_CS1_TO_CS2;
            case SATURATION_MODE_FROM_CS2_TO_CS1 -> STATUS_SATURATION_MODE_FROM_CS2_TO_CS1;
            case FROZEN -> STATUS_FROZEN;
        };
    }

    /** A line in linear mode: it saturates when the flow out of the station it leaves passes that direction's limit. */
    public static int linearModeDecision(double p1, double p2, double pMaxFromCS1toCS2, double pMaxFromCS2toCS1) {
        return p1 >= 0 ? (p1 > pMaxFromCS1toCS2 ? DECISION_TO_SATURATION_FROM_CS1_TO_CS2 : DECISION_NONE)
             : p2 > pMaxFromCS2toCS1 ? DECISION_TO_SATURATION_FROM_CS2_TO_CS1
             : DECISION_NONE;
    }

    /**
     * A saturated line: back to linear mode when a flow is positive and within its limit; otherwise saturated the other
     * way when the flow of the station it saturated from reversed.
     */
    public static int saturationModeDecision(int status, double p1, double p2, double pMaxFromCS1toCS2, double pMaxFromCS2toCS1) {
        return p1 > 0 && p1 < pMaxFromCS1toCS2 || p2 > 0 && p2 < pMaxFromCS2toCS1 ? DECISION_TO_LINEAR_MODE
             : p1 < 0 && status == STATUS_SATURATION_MODE_FROM_CS1_TO_CS2 ? DECISION_TO_SATURATION_FROM_CS2_TO_CS1
             : p2 < 0 && status == STATUS_SATURATION_MODE_FROM_CS2_TO_CS1 ? DECISION_TO_SATURATION_FROM_CS1_TO_CS2
             : DECISION_NONE;
    }

    /** The decision for a line in any status: a frozen line does not change. */
    public static int decision(int status, double p1, double p2, double pMaxFromCS1toCS2, double pMaxFromCS2toCS1) {
        return status == STATUS_LINEAR_MODE ? linearModeDecision(p1, p2, pMaxFromCS1toCS2, pMaxFromCS2toCS1)
             : status == STATUS_SATURATION_MODE_FROM_CS1_TO_CS2 || status == STATUS_SATURATION_MODE_FROM_CS2_TO_CS1
                ? saturationModeDecision(status, p1, p2, pMaxFromCS1toCS2, pMaxFromCS2toCS1)
             : DECISION_NONE;
    }
}
