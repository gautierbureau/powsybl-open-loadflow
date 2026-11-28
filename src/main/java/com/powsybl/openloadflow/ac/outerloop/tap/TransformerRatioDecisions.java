/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop.tap;

/**
 * The ratio rules of {@link TransformerRatioManager}, the discrete transformer voltage control, as functions of
 * numbers: a transformer whose continuous ratio leaves its group's range is frozen at the group's limit; within the
 * range, a transformer of a group keeping its initial taps follows the group's ratio from its own initial ratio,
 * scaled to its own range; and the group's range and initial ratio from its members'.
 *
 * <p>Every function is an expression over primitives. Nothing reads or writes a network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class TransformerRatioDecisions {

    private TransformerRatioDecisions() {
    }

    /** Whether a continuous ratio is out of the group's range, so that the transformer is frozen at a limit. */
    public static boolean isOutOfRange(double r1, double minR1, double maxR1) {
        return r1 < minR1 || r1 > maxR1;
    }

    /** The limit an out of range ratio is frozen at. */
    public static double extremeR1(double r1, double minR1, double maxR1) {
        return r1 > maxR1 ? maxR1 : minR1;
    }

    /**
     * The ratio of a transformer of a group keeping its initial taps, from the ratio computed for the whole group: the
     * group's move from its initial ratio, as a fraction of the way to its limit, applied to the transformer's own way
     * from its initial ratio to its own limit.
     */
    public static double updatedR1(double computedR1, double individualInitialR1, double individualMinR1, double individualMaxR1,
                                   double minR1, double maxR1, double initialR1) {
        return computedR1 >= initialR1 ?
                individualInitialR1 + (computedR1 - initialR1) * (individualMaxR1 - individualInitialR1) / (maxR1 - initialR1)
                :
                individualInitialR1 - (initialR1 - computedR1) * (individualInitialR1 - individualMinR1) / (initialR1 - minR1);
    }

    /** A member's share of the way from its maximum ratio down to its ratio ({@code a}), 1 for a fixed ratio. */
    public static double shareFromMax(double r1, double minR1, double maxR1) {
        return maxR1 != minR1 ? (maxR1 - r1) / (maxR1 - minR1) : 1;
    }

    /** A member's share of the way from its minimum ratio up to its ratio ({@code b}), 1 for a fixed ratio. */
    public static double shareFromMin(double r1, double minR1, double maxR1) {
        return maxR1 != minR1 ? (r1 - minR1) / (maxR1 - minR1) : 1;
    }

    /** The group's initial ratio, from its average limits and the sums of its members' shares. */
    public static double groupInitialR1(double maxR1, double minR1, double sumShareFromMax, double sumShareFromMin, int count) {
        return (minR1 * sumShareFromMax + maxR1 * sumShareFromMin) / count;
    }
}
