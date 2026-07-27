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
 * <p>Checks are stored grouped by kind (side and limit type), so the screening pass is one tight loop per kind over
 * contiguous thresholds, with the flow accessor fixed for the whole loop and no per-element dispatch. That order is
 * not the order in which the original detection reported violations, so each check also carries its rank in that
 * original order (by branch, then side one before side two, then current, active power and apparent power), which the
 * detection uses to report the - rare - violations it finds in the expected order.
 *
 * <p>Branches, sides and limit types carrying no limit simply have no entry, so limitless branches cost nothing.
 * Limits and their reductions do not change between contingencies, so a screen is built once per security analysis.
 * The disabling status does change, hence the branch predicate kept at detection time.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public final class BranchLimitScreen {

    static final int SIDE_1_CURRENT = 0;
    static final int SIDE_1_ACTIVE_POWER = 1;
    static final int SIDE_1_APPARENT_POWER = 2;
    static final int SIDE_2_CURRENT = 3;
    static final int SIDE_2_ACTIVE_POWER = 4;
    static final int SIDE_2_APPARENT_POWER = 5;

    static final int KIND_COUNT = 6;

    private static final LimitType[] SCREENED_LIMIT_TYPES = {LimitType.CURRENT, LimitType.ACTIVE_POWER, LimitType.APPARENT_POWER};

    private final LfBranch[] branches;

    private final LfBus[] buses;

    private final double[] thresholds;

    private final int[] ranks;

    private final List<LfBranch.LfLimitsGroup>[] groups;

    /** Index of the first check of each kind, plus the total count as last element. */
    private final int[] kindStart;

    private BranchLimitScreen(LfBranch[] branches, LfBus[] buses, double[] thresholds, int[] ranks,
                              List<LfBranch.LfLimitsGroup>[] groups, int[] kindStart) {
        this.branches = branches;
        this.buses = buses;
        this.thresholds = thresholds;
        this.ranks = ranks;
        this.groups = groups;
        this.kindStart = kindStart;
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

    int[] getRanks() {
        return ranks;
    }

    List<LfBranch.LfLimitsGroup>[] getGroups() {
        return groups;
    }

    int[] getKindStart() {
        return kindStart;
    }

    /**
     * Build the screen of a network: resolve, once, the limits of every branch side and limit type, and keep the ones
     * carrying at least one limit with the lowest reduced limit of the side and type as screening threshold.
     */
    @SuppressWarnings("unchecked")
    public static BranchLimitScreen build(LfNetwork network, LimitReductionManager limitReductionManager) {
        // collect the checks per kind, so that they end up grouped by kind in the arrays
        List<List<Check>> checksByKind = new ArrayList<>(KIND_COUNT);
        for (int kind = 0; kind < KIND_COUNT; kind++) {
            checksByKind.add(new ArrayList<>());
        }
        List<LfBranch> networkBranches = network.getBranches();
        for (int branchIndex = 0; branchIndex < networkBranches.size(); branchIndex++) {
            LfBranch branch = networkBranches.get(branchIndex);
            for (TwoSides side : TwoSides.values()) {
                boolean side1 = side == TwoSides.ONE;
                LfBus bus = side1 ? branch.getBus1() : branch.getBus2();
                if (bus == null) {
                    // the original detection only checks a side that is connected to a bus
                    continue;
                }
                for (int typeIndex = 0; typeIndex < SCREENED_LIMIT_TYPES.length; typeIndex++) {
                    LimitType type = SCREENED_LIMIT_TYPES[typeIndex];
                    List<LfBranch.LfLimitsGroup> limitsGroups = side1
                            ? branch.getLimits1(type, limitReductionManager)
                            : branch.getLimits2(type, limitReductionManager);
                    double threshold = Double.POSITIVE_INFINITY;
                    for (LfBranch.LfLimitsGroup limitsGroup : limitsGroups) {
                        threshold = Math.min(threshold, limitsGroup.getMinReducedValue());
                    }
                    if (!Double.isInfinite(threshold)) {
                        int kind = (side1 ? 0 : SCREENED_LIMIT_TYPES.length) + typeIndex;
                        // rank in the order the original detection visited the checks
                        int rank = branchIndex * KIND_COUNT + kind;
                        checksByKind.get(kind).add(new Check(branch, bus, threshold, rank, limitsGroups));
                    }
                }
            }
        }

        int size = 0;
        for (List<Check> checks : checksByKind) {
            size += checks.size();
        }
        LfBranch[] branches = new LfBranch[size];
        LfBus[] buses = new LfBus[size];
        double[] thresholds = new double[size];
        int[] ranks = new int[size];
        List<LfBranch.LfLimitsGroup>[] groups = new List[size];
        int[] kindStart = new int[KIND_COUNT + 1];
        int k = 0;
        for (int kind = 0; kind < KIND_COUNT; kind++) {
            kindStart[kind] = k;
            for (Check check : checksByKind.get(kind)) {
                branches[k] = check.branch();
                buses[k] = check.bus();
                thresholds[k] = check.threshold();
                ranks[k] = check.rank();
                groups[k] = check.groups();
                k++;
            }
        }
        kindStart[KIND_COUNT] = k;
        return new BranchLimitScreen(branches, buses, thresholds, ranks, groups, kindStart);
    }

    private record Check(LfBranch branch, LfBus bus, double threshold, int rank, List<LfBranch.LfLimitsGroup> groups) {
    }
}
