/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import com.powsybl.openloadflow.lf.util.ActivePowerDistribution;
import com.powsybl.openloadflow.util.PerUnit;

/**
 * The area-level rules of the area interchange control outer loops, as functions of numbers: an area's interchange
 * mismatch, with or without its share of the slack mismatch, whether a mismatch is small enough, an area's margin and
 * share when the remaining slack mismatch is spread over the areas. The distribution of an area's mismatch over its
 * buses is {@link ActivePowerDistribution}.
 *
 * <p>Every function is an expression over primitives. Nothing reads or writes a network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class AreaInterchangeControlDecisions {

    private AreaInterchangeControlDecisions() {
    }

    /** An area's interchange minus its target (pu). */
    public static double interchangeMismatch(double interchange, double interchangeTarget) {
        return interchange - interchangeTarget;
    }

    /** An area's interchange mismatch once its share of the slack bus mismatch is distributed (pu). */
    public static double interchangeMismatchWithSlack(double interchange, double interchangeTarget, double slackDistributionFactor,
                                                      double slackBusActivePowerMismatch) {
        return interchange - interchangeTarget + slackDistributionFactor * slackBusActivePowerMismatch;
    }

    /** Whether a mismatch (pu) is within the maximum mismatch (MW) or within the distribution's residue. */
    public static boolean isWithinMaxMismatch(double mismatch, double maxMismatchMw) {
        return Math.abs(mismatch) <= maxMismatchMw / PerUnit.SB || Math.abs(mismatch) <= ActivePowerDistribution.P_RESIDUE_EPS;
    }

    /**
     * The "margin" an area has to take a slack mismatch: the power it can distribute and still keep its interchange
     * within the maximum mismatch of its target.
     */
    public static double slackMargin(double mismatch, double interchangeMismatchWithSlack, double areaInterchangePMaxMismatchMw) {
        return Math.signum(mismatch) * interchangeMismatchWithSlack + areaInterchangePMaxMismatchMw / PerUnit.SB;
    }

    /**
     * The part of a slack mismatch an area takes, from its factor; when that part is below the distribution's residue,
     * a little more than the residue, so that the whole mismatch ends distributed by the areas first in the list.
     */
    public static double areaShare(double mismatch, double factor) {
        return Math.abs(mismatch * factor) > ActivePowerDistribution.P_RESIDUE_EPS
                ? mismatch * factor
                // Add this small 1.01 factor to make sure to be above epsilon and distribute some power
                : Math.signum(mismatch) * 1.01 * ActivePowerDistribution.P_RESIDUE_EPS;
    }

    /** Whether a mismatch is left to distribute, or an area could not distribute its whole share. */
    public static boolean isAboveResidue(double mismatch) {
        return Math.abs(mismatch) > ActivePowerDistribution.P_RESIDUE_EPS;
    }

    /** The slack mismatch left after an area took {@code areaShare} and left {@code areaRemaining} of it. */
    public static double remainingAfter(double remainingMismatch, double areaShare, double areaRemaining) {
        return remainingMismatch - areaShare + areaRemaining;
    }
}
