/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import java.util.List;

/**
 * The secondary voltage control zones of a network, as arrays, in the order of the network: each zone's pilot bus,
 * pilot target voltage (pu) and whether it is enabled, and its controller buses with the bus each controls. The
 * controller buses of zone {@code z} are {@code firstController[z]} to {@code firstController[z + 1] - 1}. Read when
 * built: actions and contingencies change the participating units and the targets.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class SecondaryVoltageControlZones {

    public final int count;
    public final int[] pilotBusNum;
    public final double[] targetV;
    public final boolean[] enabled;
    public final int[] firstController;
    public final int[] controllerBusNum;
    public final int[] controlledBusNum;

    private SecondaryVoltageControlZones(LfNetwork network, List<LfSecondaryVoltageControl> zones) {
        count = zones.size();
        pilotBusNum = new int[count];
        targetV = new double[count];
        enabled = new boolean[count];
        firstController = new int[count + 1];
        List<LfSecondaryVoltageControl> enabledZones = network.getEnabledSecondaryVoltageControls();
        int controllerCount = zones.stream().mapToInt(zone -> zone.getControllerBuses().size()).sum();
        controllerBusNum = new int[controllerCount];
        controlledBusNum = new int[controllerCount];
        int c = 0;
        for (int z = 0; z < count; z++) {
            LfSecondaryVoltageControl zone = zones.get(z);
            pilotBusNum[z] = zone.getPilotBus().getNum();
            targetV[z] = zone.getTargetValue();
            enabled[z] = enabledZones.contains(zone);
            firstController[z] = c;
            for (LfBus controllerBus : zone.getControllerBuses()) {
                controllerBusNum[c] = controllerBus.getNum();
                controlledBusNum[c] = controllerBus.getGeneratorVoltageControl().map(vc -> vc.getControlledBus().getNum()).orElse(-1);
                c++;
            }
        }
        firstController[count] = c;
    }

    public static SecondaryVoltageControlZones of(LfNetwork network) {
        return new SecondaryVoltageControlZones(network, network.getSecondaryVoltageControls());
    }
}
