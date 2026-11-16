/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class StateVectorTest {

    @Test
    void valuesOnlyUpdatesAreFullUpdatesForAListenerThatDoesNotTellThemApart() {
        StateVector stateVector = new StateVector();
        List<String> updates = new ArrayList<>();
        stateVector.addListener(() -> updates.add("full"));

        double[] state1 = {1, 2};
        stateVector.setValuesOnly(state1);
        assertSame(state1, stateVector.get());
        double[] state2 = {3, 4};
        stateVector.setValuesOnly(state2, new int[] {0});
        assertSame(state2, stateVector.get());

        assertEquals(List.of("full", "full"), updates);
    }

    @Test
    void aListenerIsToldWhichUpdateItIs() {
        StateVector stateVector = new StateVector();
        List<String> updates = new ArrayList<>();
        List<int[]> listedBranches = new ArrayList<>();
        stateVector.addListener(new StateVectorListener() {
            @Override
            public void onStateUpdate() {
                updates.add("full");
            }

            @Override
            public void onStateUpdate(boolean valuesOnly) {
                updates.add(valuesOnly ? "values only" : "full");
            }

            @Override
            public void onStateUpdate(int[] closedBranchNums) {
                updates.add("listed branches");
                listedBranches.add(closedBranchNums);
            }
        });

        stateVector.set(new double[] {1, 2});
        stateVector.setValuesOnly(new double[] {3, 4});
        stateVector.setValuesOnly(new double[] {5, 6}, new int[] {1, 3});

        assertEquals(List.of("full", "values only", "listed branches"), updates);
        assertArrayEquals(new int[] {1, 3}, listedBranches.getFirst());
    }
}
