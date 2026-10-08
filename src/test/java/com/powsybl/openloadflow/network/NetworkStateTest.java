/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class NetworkStateTest {

    @Test
    void restoreDoesNotShareTheSavedLoadDisablingStatus() {
        // A security analysis saves the base state once and restores it after every contingency. Losing a
        // load marks its original load disabled; the restore must clear that EVERY time, not only the first.
        LfNetwork lfNetwork = Networks.load(EurostagTutorialExample1Factory.create(), new MostMeshedSlackBusSelector()).getFirst();
        LfLoad load = lfNetwork.getBuses().stream()
                .flatMap(b -> b.getLoads().stream())
                .filter(l -> l.getOriginalIds().contains("LOAD"))
                .findFirst().orElseThrow();
        NetworkState state = NetworkState.save(lfNetwork);
        for (int contingency = 1; contingency <= 2; contingency++) {
            load.setOriginalLoadDisabled("LOAD", true);
            state.restore();
            assertFalse(load.isOriginalLoadDisabled("LOAD"), "original load still disabled after restore " + contingency);
        }
    }

    @Test
    void partialRestore() {
        LfNetwork lfNetwork = Networks.load(EurostagTutorialExample1Factory.create(), new MostMeshedSlackBusSelector()).getFirst();
        LfBus loadBus = lfNetwork.getBusById("VLLOAD_0");
        LfLoad load = loadBus.getLoads().getFirst();
        LfGenerator gen = lfNetwork.getBusById("VLGEN_0").getGenerators().getFirst();
        LfBranch line = lfNetwork.getBranchById("NHV1_NHV2_1");
        double loadTargetP = load.getTargetP();
        double genTargetP = gen.getTargetP();
        double[] v = lfNetwork.getBuses().stream().mapToDouble(LfBus::getV).toArray();
        double[] angle = lfNetwork.getBuses().stream().mapToDouble(LfBus::getAngle).toArray();
        NetworkState state = NetworkState.save(lfNetwork);

        load.setTargetP(loadTargetP + 1);                    // on a listed bus: restored
        line.setDisabled(true);                              // a listed branch: restored
        gen.setTargetP(genTargetP + 1);                      // on an UNLISTED bus: left as is
        for (LfBus bus : lfNetwork.getBuses()) {             // every bus voltage: restored
            bus.setV(bus.getV() + 0.1);
            bus.setAngle(bus.getAngle() + 0.1);
        }
        state.restore(List.of(loadBus), List.of(line));

        assertEquals(loadTargetP, load.getTargetP(), 0);
        assertFalse(line.isDisabled());
        assertEquals(genTargetP + 1, gen.getTargetP(), 0);
        for (LfBus bus : lfNetwork.getBuses()) {
            assertEquals(v[bus.getNum()], bus.getV(), 0, bus.getId());
            assertEquals(angle[bus.getNum()], bus.getAngle(), 0, bus.getId());
        }

        LfNetwork other = Networks.load(EurostagTutorialExample1Factory.create(), new MostMeshedSlackBusSelector()).getFirst();
        List<LfBus> foreign = List.of(other.getBusById("VLLOAD_0"));
        List<LfBranch> none = List.of();
        assertThrows(IllegalArgumentException.class, () -> state.restore(foreign, none));
    }
}
