/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.iidm.network.LimitType;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * The branch limit checks of a network laid out as parallel arrays, so that a security analysis can screen every
 * branch of every contingency with one flat pass instead of walking the branch, side, limit type and limits group
 * object graph again on each contingency.
 *
 * <p>A check is one (branch, side, limit type) carrying at least one limit. Its threshold is the lowest reduced limit
 * over the groups of that side and type: a flow that does not exceed it cannot violate any of them, so a single array
 * comparison rules the check out (see {@link LfBranch.LfLimitsGroup#getMinReducedValue()}). Only when the threshold is
 * exceeded are the limits groups visited, which reproduces the original detection exactly.
 *
 * <p>Checks are stored in the order the original detection visited them - by branch, then side one before side two,
 * then current, active power and apparent power - so the detected violations keep their insertion order. Branches,
 * sides and limit types carrying no limit simply have no entry, so limitless branches cost nothing at all.
 *
 * <p>Limits and their reductions do not change between contingencies, so a screen is built once per security analysis.
 * The disabling status does change, hence the branch predicate kept at detection time.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public final class BranchLimitScreen {

    static final byte SIDE_1_CURRENT = 0;
    static final byte SIDE_1_ACTIVE_POWER = 1;
    static final byte SIDE_1_APPARENT_POWER = 2;
    static final byte SIDE_2_CURRENT = 3;
    static final byte SIDE_2_ACTIVE_POWER = 4;
    static final byte SIDE_2_APPARENT_POWER = 5;

    private static final LimitType[] SCREENED_LIMIT_TYPES = {LimitType.CURRENT, LimitType.ACTIVE_POWER, LimitType.APPARENT_POWER};

    private final LfBranch[] branches;

    private final LfBus[] buses;

    private final double[] thresholds;

    private final byte[] kinds;

    private final List<LfBranch.LfLimitsGroup>[] groups;

    private BranchLimitScreen(LfBranch[] branches, LfBus[] buses, double[] thresholds, byte[] kinds,
                              List<LfBranch.LfLimitsGroup>[] groups) {
        this.branches = branches;
        this.buses = buses;
        this.thresholds = thresholds;
        this.kinds = kinds;
        this.groups = groups;
    }

    public int size() {
        return branches.length;
    }

    LfBranch[] getBranches() {
        return branches;
    }

    LfBus[] getBuses() {
        return buses;
    }

    double[] getThresholds() {
        return thresholds;
    }

    byte[] getKinds() {
        return kinds;
    }

    List<LfBranch.LfLimitsGroup>[] getGroups() {
        return groups;
    }

    /**
     * Build the screen of a network: resolve, once, the limits of every branch side and limit type, and keep the ones
     * carrying at least one limit with the lowest reduced limit of the side and type as screening threshold.
     */
    @SuppressWarnings("unchecked")
    public static BranchLimitScreen build(LfNetwork network, LimitReductionManager limitReductionManager) {
        List<LfBranch> branches = new ArrayList<>();
        List<LfBus> buses = new ArrayList<>();
        List<Double> thresholds = new ArrayList<>();
        List<Byte> kinds = new ArrayList<>();
        List<List<LfBranch.LfLimitsGroup>> groups = new ArrayList<>();
        for (LfBranch branch : network.getBranches()) {
            for (TwoSides side : TwoSides.values()) {
                LfBus bus = side == TwoSides.ONE ? branch.getBus1() : branch.getBus2();
                if (bus == null) {
                    // the original detection only checks a side that is connected to a bus
                    continue;
                }
                for (int typeIndex = 0; typeIndex < SCREENED_LIMIT_TYPES.length; typeIndex++) {
                    LimitType type = SCREENED_LIMIT_TYPES[typeIndex];
                    List<LfBranch.LfLimitsGroup> limitsGroups = side == TwoSides.ONE
                            ? branch.getLimits1(type, limitReductionManager)
                            : branch.getLimits2(type, limitReductionManager);
                    double threshold = Double.POSITIVE_INFINITY;
                    for (LfBranch.LfLimitsGroup limitsGroup : limitsGroups) {
                        threshold = Math.min(threshold, limitsGroup.getMinReducedValue());
                    }
                    if (!Double.isInfinite(threshold)) {
                        branches.add(branch);
                        buses.add(bus);
                        thresholds.add(threshold);
                        kinds.add((byte) ((side == TwoSides.ONE ? 0 : SCREENED_LIMIT_TYPES.length) + typeIndex));
                        groups.add(limitsGroups);
                    }
                }
            }
        }
        int size = branches.size();
        double[] thresholdArray = new double[size];
        byte[] kindArray = new byte[size];
        for (int k = 0; k < size; k++) {
            thresholdArray[k] = thresholds.get(k);
            kindArray[k] = kinds.get(k);
        }
        return new BranchLimitScreen(branches.toArray(new LfBranch[0]), buses.toArray(new LfBus[0]),
                thresholdArray, kindArray, groups.toArray(new List[0]));
    }
}
