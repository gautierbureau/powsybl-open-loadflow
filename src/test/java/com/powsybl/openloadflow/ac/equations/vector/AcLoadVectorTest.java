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
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AcLoadVectorTest {

    /**
     * The arrays by bus are as long as the network has buses. They are created with the first load that follows a
     * model, and not in a network that has none: no bus has such a load there.
     */
    @Test
    void arraysByBusAreCreatedWithTheFirstLoad() {
        Network network = EurostagTutorialExample1Factory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        int busCount = lfNetwork.getBuses().size();
        AcLoadVector loadVector = new AcLoadVector(busCount);
        assertNull(loadVector.firstLoad);
        assertNull(loadVector.loadCount);
        assertNull(loadVector.busP);
        assertNull(loadVector.busDpdv);
        assertNull(loadVector.busQ);
        assertNull(loadVector.busDqdv);
        for (LfBus bus : lfNetwork.getBuses()) {
            assertTrue(loadVector.getLoads(bus.getNum()).isEmpty());
        }

        LfBus loadBus = lfNetwork.getBusById("VLLOAD_0");
        LfLoad load = loadBus.getLoads().get(0);
        assertEquals(0, loadVector.add(loadBus, load));
        assertEquals(busCount, loadVector.firstLoad.length);
        assertEquals(busCount, loadVector.loadCount.length);
        assertEquals(busCount, loadVector.busP.length);
        assertEquals(busCount, loadVector.busDpdv.length);
        assertEquals(busCount, loadVector.busQ.length);
        assertEquals(busCount, loadVector.busDqdv.length);
        assertArrayEquals(new int[] {loadBus.getNum()}, loadVector.busNums.toArray());
        for (LfBus bus : lfNetwork.getBuses()) {
            assertEquals(bus == loadBus ? List.of(load) : List.of(), loadVector.getLoads(bus.getNum()));
        }
    }
}
