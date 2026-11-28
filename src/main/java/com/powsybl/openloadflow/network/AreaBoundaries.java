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
 * The areas of a network and their boundaries, as arrays, in the order of the network: each area's interchange target
 * (pu, read when built) and boundaries (the branch and side whose active power counts in the interchange, in the order
 * {@link LfArea#getInterchange()} sums them), and the area of each bus. The boundaries of area {@code a} are
 * {@code firstBoundary[a]} to {@code firstBoundary[a + 1] - 1}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class AreaBoundaries {

    public final int count;
    public final int[] areaNum;
    public final double[] interchangeTarget;
    public final int[] firstBoundary;
    public final int[] boundaryBranchNum;
    /** 1 or 2. */
    public final int[] boundarySide;
    /** The index of each bus's area in these arrays, by bus number; -1 for a bus in no area. */
    public final int[] busArea;

    private AreaBoundaries(LfNetwork network, List<LfArea> areas) {
        count = areas.size();
        areaNum = new int[count];
        interchangeTarget = new double[count];
        firstBoundary = new int[count + 1];
        int boundaryCount = areas.stream().mapToInt(area -> area.getBoundaries().size()).sum();
        boundaryBranchNum = new int[boundaryCount];
        boundarySide = new int[boundaryCount];
        busArea = new int[network.getBuses().size()];
        java.util.Arrays.fill(busArea, -1);
        int k = 0;
        for (int a = 0; a < count; a++) {
            LfArea area = areas.get(a);
            areaNum[a] = area.getNum();
            interchangeTarget[a] = area.getInterchangeTarget();
            firstBoundary[a] = k;
            for (LfArea.Boundary boundary : area.getBoundaries()) {
                boundaryBranchNum[k] = boundary.getBranch().getNum();
                boundarySide[k] = boundary.getSide() == TwoSides.ONE ? 1 : 2;
                k++;
            }
            for (LfBus bus : area.getBuses()) {
                busArea[bus.getNum()] = a;
            }
        }
        firstBoundary[count] = k;
    }

    public static AreaBoundaries of(LfNetwork network) {
        return new AreaBoundaries(network, network.getAreas());
    }
}
