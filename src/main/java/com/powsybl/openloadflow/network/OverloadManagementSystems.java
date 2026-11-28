/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.TwoSides;

import java.util.List;

/**
 * The overload management systems of a network, as arrays, in the order of the network: each system's monitored
 * branch and side, and its trippings in order (the branch to operate, open or close, the current threshold, pu). The
 * trippings of system {@code s} are {@code firstTripping[s]} to {@code firstTripping[s + 1] - 1}. They do not change
 * once the network is built; whether a branch is in service is read at each check.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class OverloadManagementSystems {

    public final int count;
    public final int[] monitoredBranchNum;
    /** 1 or 2. */
    public final int[] monitoredSide;
    public final int[] firstTripping;
    public final int[] branchToOperateNum;
    public final boolean[] branchOpen;
    public final double[] threshold;

    private OverloadManagementSystems(List<LfOverloadManagementSystem> systems) {
        count = systems.size();
        monitoredBranchNum = new int[count];
        monitoredSide = new int[count];
        firstTripping = new int[count + 1];
        int trippingCount = systems.stream().mapToInt(system -> system.getBranchTrippingList().size()).sum();
        branchToOperateNum = new int[trippingCount];
        branchOpen = new boolean[trippingCount];
        threshold = new double[trippingCount];
        int t = 0;
        for (int s = 0; s < count; s++) {
            LfOverloadManagementSystem system = systems.get(s);
            monitoredBranchNum[s] = system.getMonitoredBranch().getNum();
            monitoredSide[s] = system.getMonitoredSide() == TwoSides.ONE ? 1 : 2;
            firstTripping[s] = t;
            for (LfOverloadManagementSystem.LfBranchTripping tripping : system.getBranchTrippingList()) {
                branchToOperateNum[t] = tripping.branchToOperate().getNum();
                branchOpen[t] = tripping.branchOpen();
                threshold[t] = tripping.threshold();
                t++;
            }
        }
        firstTripping[count] = t;
    }

    private static final String PROPERTY = OverloadManagementSystems.class.getName();

    /** The systems of the network, built once and kept on it. */
    public static OverloadManagementSystems of(LfNetwork network) {
        Object built = network.getProperty(PROPERTY);
        if (built == null) {
            built = new OverloadManagementSystems(network.getOverloadManagementSystems());
            network.setProperty(PROPERTY, built);
        }
        return (OverloadManagementSystems) built;
    }
}
