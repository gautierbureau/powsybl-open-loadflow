/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.*;

import java.time.ZonedDateTime;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class AutomationSystemNetworkFactory extends AbstractLoadFlowNetworkFactory {

    private AutomationSystemNetworkFactory() {
    }

    /**
     * b1 and b2 are the HV network
     * b3 and b4 are the LV network
     * when breaker br1 is closed, the network is operated "coupled" and the LV line l34 have a very high intensity
     * opening the breaker br1 allow reducing the intensity of line l34 (even if network is in that case less robust
     * to any contingency)
     * g1
     * |      l12
     * b1 ====-------------==== b2
     * |                |
     * 8 tr1            8 tr2
     * |   b3p  l34    |
     * b3 ====*====--------==== b4
     * br1 |        |
     * ld3      ld4
     */
    public static Network create() {
        Network network = createCommonNetwork();
        Bus b3 = network.getBusBreakerView().getBus("b3");
        b3.getVoltageLevel().getBusBreakerView().newSwitch()
                .setId("br1")
                .setBus1("b3")
                .setBus2("b3p")
                .setOpen(false)
                .add();
        Substation s1 = network.getSubstation("s1");
        s1.newOverloadManagementSystem()
                .setId("l34_opens_br1")
                .setEnabled(true)
                .setMonitoredElementId("l34")
                .setMonitoredElementSide(ThreeSides.ONE)
                .newSwitchTripping()
                .setKey("br1 key")
                .setSwitchToOperateId("br1")
                .setCurrentLimit(300.)
                .add()
                .add();
        return network;
    }

    public static Network createWithSwitchToClose() {
        Network network = createCommonNetwork();
        Bus b3 = network.getBusBreakerView().getBus("b3");
        b3.getVoltageLevel().getBusBreakerView().newSwitch()
                .setId("br1")
                .setBus1("b3")
                .setBus2("b3p")
                .setOpen(true)
                .add();
        Substation s1 = network.getSubstation("s1");
        s1.newOverloadManagementSystem()
                .setId("l12_closes_br1")
                .setEnabled(true)
                .setMonitoredElementId("l12")
                .setMonitoredElementSide(ThreeSides.ONE)
                .newSwitchTripping()
                .setKey("br1 key")
                .setSwitchToOperateId("br1")
                .setCurrentLimit(250.0)
                .setOpenAction(false)
                .add()
                .add();
        return network;
    }

    /**
     * From previous test case, the switch b3p is replaced by line l33p.
     */
    public static Network createWithBranchTripping() {
        Network network = AutomationSystemNetworkFactory.createCommonNetwork();
        Bus b3 = network.getBusBreakerView().getBus("b3");
        Bus b3p = network.getBusBreakerView().getBus("b3p");
        createLine(network, b3, b3p, "l33p", 0.1, 3);
        Substation s1 = network.getSubstation("s1");
        s1.newOverloadManagementSystem()
                .setId("l34_opens_l12")
                .setEnabled(true)
                .setMonitoredElementId("l34")
                .setMonitoredElementSide(ThreeSides.ONE)
                .newBranchTripping()
                .setKey("l33p key")
                .setBranchToOperateId("l33p")
                .setSideToOperate(TwoSides.TWO)
                .setCurrentLimit(200.)
                .add()
                .add();
        return network;
    }

    public static Network createWithBranchTripping2() {
        Network network = AutomationSystemNetworkFactory.createCommonNetwork();
        Bus b3 = network.getBusBreakerView().getBus("b3");
        Bus b3p = network.getBusBreakerView().getBus("b3p");
        createLine(network, b3, b3p, "l33p", 0.1, 3);
        Substation s1 = network.getSubstation("s1");
        s1.newOverloadManagementSystem()
                .setId("l34_opens_l12")
                .setEnabled(true)
                .setMonitoredElementId("l12")
                .setMonitoredElementSide(ThreeSides.ONE)
                .newBranchTripping()
                .setKey("l33p key")
                .setBranchToOperateId("l33p")
                .setSideToOperate(TwoSides.TWO)
                .setOpenAction(false)
                .setCurrentLimit(200.)
                .add()
                .add();
        network.getLine("l33p").getTerminal1().disconnect();
        network.getLine("l33p").getTerminal2().disconnect();
        return network;
    }

    /**
     * From createWithBranchTripping, meshed so that no branch outage leaves an island, even with l33p open: a second line
     * beside l12 and beside l34, and a line from b3 to b4. Its system opens l33p above 150 A at l34's side 1: 106 A in the
     * base, 296 A without tr2, 174 A without l34b, 154 A without l3b4.
     */
    public static Network createMeshedWithBranchTripping() {
        Network network = AutomationSystemNetworkFactory.createCommonNetwork();
        Bus b1 = network.getBusBreakerView().getBus("b1");
        Bus b2 = network.getBusBreakerView().getBus("b2");
        Bus b3 = network.getBusBreakerView().getBus("b3");
        Bus b3p = network.getBusBreakerView().getBus("b3p");
        Bus b4 = network.getBusBreakerView().getBus("b4");
        createLine(network, b3, b3p, "l33p", 0.1, 3);
        createLine(network, b1, b2, "l12b", 0.1, 3);
        createLine(network, b3p, b4, "l34b", 0.05, 3.2);
        createLine(network, b3, b4, "l3b4", 0.05, 6);
        Substation s1 = network.getSubstation("s1");
        s1.newOverloadManagementSystem()
                .setId("l34_opens_l33p")
                .setEnabled(true)
                .setMonitoredElementId("l34")
                .setMonitoredElementSide(ThreeSides.ONE)
                .newBranchTripping()
                .setKey("l33p key")
                .setBranchToOperateId("l33p")
                .setSideToOperate(TwoSides.TWO)
                .setCurrentLimit(150.)
                .add()
                .add();
        return network;
    }

    /**
     * From createMeshedWithBranchTripping, with a 5 MW load at b3 and, instead of l34's system, one that opens l33p above
     * 260 A at tr2's side 1: 175 A in the base, 313 A without tr1 and l3b4, where b3 hangs on l33p alone.
     */
    public static Network createMeshedWithIslandingBranchTripping() {
        Network network = createMeshedWithBranchTripping();
        network.getOverloadManagementSystem("l34_opens_l33p").remove();
        createLoad(network.getBusBreakerView().getBus("b3"), "ld3b", 5, 2);
        network.getSubstation("s1").newOverloadManagementSystem()
                .setId("tr2_opens_l33p")
                .setEnabled(true)
                .setMonitoredElementId("tr2")
                .setMonitoredElementSide(ThreeSides.ONE)
                .newBranchTripping()
                .setKey("l33p key")
                .setBranchToOperateId("l33p")
                .setSideToOperate(TwoSides.TWO)
                .setCurrentLimit(260.)
                .add()
                .add();
        return network;
    }

    /**
     * From createMeshedWithBranchTripping, with l34's system closing l34b, closed in the base, above 150 A instead of
     * opening l33p: 106 A in the base, 174 A without l34b.
     */
    public static Network createMeshedWithBranchToClose() {
        Network network = createMeshedWithBranchTripping();
        network.getOverloadManagementSystem("l34_opens_l33p").remove();
        network.getSubstation("s1").newOverloadManagementSystem()
                .setId("l34_closes_l34b")
                .setEnabled(true)
                .setMonitoredElementId("l34")
                .setMonitoredElementSide(ThreeSides.ONE)
                .newBranchTripping()
                .setKey("l34b key")
                .setBranchToOperateId("l34b")
                .setSideToOperate(TwoSides.TWO)
                .setOpenAction(false)
                .setCurrentLimit(150.)
                .add()
                .add();
        return network;
    }

    public static Network createWithBadAutomationSystems() {
        Network network = AutomationSystemNetworkFactory.createCommonNetwork();
        Bus b3 = network.getBusBreakerView().getBus("b3");
        Bus b3p = network.getBusBreakerView().getBus("b3p");
        createLine(network, b3, b3p, "l33p", 0.1, 3);
        // we create another component.
        Bus b5 = createBus(network, "s3", "b5", 225);
        Bus b6 = createBus(network, "s4", "b6", 225);
        createLine(network, b5, b6, "l56", 0.5, 3);
        return network;
    }

    private static Network createCommonNetwork() {
        Network network = Network.create("OverloadManagementSystemTestCase", "code");
        network.setCaseDate(ZonedDateTime.parse("2020-04-05T14:11:00.000+01:00"));
        Bus b1 = createBus(network, "s1", "b1", 225);
        Bus b2 = createBus(network, "s2", "b2", 225);
        Bus b3 = createBus(network, "s1", "b3", 63);
        Bus b3p = b3.getVoltageLevel().getBusBreakerView().newBus()
                .setId("b3p")
                .add();
        Bus b4 = createBus(network, "s2", "b4", 63);
        createGenerator(b1, "g1", 100, 230);
        createLoad(b3p, "ld3", 3, 2);
        createLoad(b4, "ld4", 90, 60);
        createLine(network, b1, b2, "l12", 0.1, 3);
        createLine(network, b3p, b4, "l34", 0.05, 3.2);
        createTransformer(network, "s1", b1, b3, "tr1", 0.2, 2, 1);
        createTransformer(network, "s2", b2, b4, "tr2", 0.3, 3, 1);
        return network;
    }
}
