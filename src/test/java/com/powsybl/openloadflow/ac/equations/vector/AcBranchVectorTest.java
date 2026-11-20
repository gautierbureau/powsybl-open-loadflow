/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreationParameters;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AcBranchVectorTest {

    /**
     * The arrays of the branches open at one side are as long as the network has branches. Those of a side are created
     * with the first branch that has terms for when it is open at that side, and not in a network that has none.
     */
    @Test
    void arraysOfBranchesOpenAtOneSideAreCreatedWithTheFirstOne() {
        Network network = EurostagTutorialExample1Factory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        int size = lfNetwork.getBranches().size();
        AcBranchVector branchVector = new AcBranchVector(lfNetwork.getBranches(), new AcEquationSystemCreationParameters());
        assertEquals(size, branchVector.getSize());
        assertNull(branchVector.openableSide1);
        assertNull(branchVector.openP2);
        assertNull(branchVector.openDp2dv2);
        assertNull(branchVector.openQ2);
        assertNull(branchVector.openDq2dv2);
        assertNull(branchVector.openableSide2);
        assertNull(branchVector.openP1);
        assertNull(branchVector.openDp1dv1);
        assertNull(branchVector.openQ1);
        assertNull(branchVector.openDq1dv1);
        assertNull(branchVector.openR1);

        // a branch has terms for when it is open at side 1: the arrays of that side, and only those
        branchVector.setOpenableSide1(1);
        assertArrayEquals(new boolean[] {false, true, false, false}, branchVector.openableSide1);
        assertArrayEquals(new int[] {1}, branchVector.openableSide1Nums.toArray());
        assertEquals(size, branchVector.openP2.length);
        assertEquals(size, branchVector.openDp2dv2.length);
        assertEquals(size, branchVector.openQ2.length);
        assertEquals(size, branchVector.openDq2dv2.length);
        assertNull(branchVector.openableSide2);
        assertNull(branchVector.openP1);

        // another one at side 2
        branchVector.setOpenableSide2(2);
        assertArrayEquals(new boolean[] {false, false, true, false}, branchVector.openableSide2);
        assertArrayEquals(new int[] {2}, branchVector.openableSide2Nums.toArray());
        assertEquals(size, branchVector.openP1.length);
        assertEquals(size, branchVector.openDp1dv1.length);
        assertEquals(size, branchVector.openQ1.length);
        assertEquals(size, branchVector.openDq1dv1.length);
        assertEquals(size, branchVector.openR1.length);

        // more branches, and the same one again: the arrays stay, and a branch is listed once
        double[] openP2 = branchVector.openP2;
        double[] openP1 = branchVector.openP1;
        branchVector.setOpenableSide1(0);
        branchVector.setOpenableSide1(1);
        branchVector.setOpenableSide2(2);
        assertSame(openP2, branchVector.openP2);
        assertSame(openP1, branchVector.openP1);
        assertArrayEquals(new boolean[] {true, true, false, false}, branchVector.openableSide1);
        assertArrayEquals(new int[] {1, 0}, branchVector.openableSide1Nums.toArray());
        assertArrayEquals(new int[] {2}, branchVector.openableSide2Nums.toArray());
    }
}
