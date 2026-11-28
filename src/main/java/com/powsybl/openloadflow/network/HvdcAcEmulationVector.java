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
 * The HVDC lines in AC emulation of a network, as arrays, in the order of the network: what an engine needs to
 * compute their flows and to check their limits. The parameters are read when the vector is built; the status of each
 * line and its flows are read again at each check.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class HvdcAcEmulationVector {

    public final int count;

    public final int[] hvdcNum;
    public final int[] bus1Num;
    public final int[] bus2Num;
    /** The flow at zero angle difference (pu). */
    public final double[] p0;
    /** The droop (pu per radian of angle difference). */
    public final double[] droop;
    public final double[] pMaxFromCS1toCS2;
    public final double[] pMaxFromCS2toCS1;
    /** The converter stations' loss factors (%). */
    public final double[] lossFactor1;
    public final double[] lossFactor2;
    /** The DC line's resistance (pu). */
    public final double[] r;

    private HvdcAcEmulationVector(List<LfHvdc> hvdcs) {
        count = hvdcs.size();
        hvdcNum = new int[count];
        bus1Num = new int[count];
        bus2Num = new int[count];
        p0 = new double[count];
        droop = new double[count];
        pMaxFromCS1toCS2 = new double[count];
        pMaxFromCS2toCS1 = new double[count];
        lossFactor1 = new double[count];
        lossFactor2 = new double[count];
        r = new double[count];
        for (int i = 0; i < count; i++) {
            LfHvdc hvdc = hvdcs.get(i);
            LfHvdc.AcEmulationControl control = hvdc.getAcEmulationControl();
            hvdcNum[i] = hvdc.getNum();
            bus1Num[i] = hvdc.getBus1() != null ? hvdc.getBus1().getNum() : -1;
            bus2Num[i] = hvdc.getBus2() != null ? hvdc.getBus2().getNum() : -1;
            p0[i] = control.getP0();
            droop[i] = control.getDroop();
            pMaxFromCS1toCS2[i] = control.getPMaxFromCS1toCS2();
            pMaxFromCS2toCS1[i] = control.getPMaxFromCS2toCS1();
            lossFactor1[i] = hvdc.getConverterStation1().getLossFactor();
            lossFactor2[i] = hvdc.getConverterStation2().getLossFactor();
            r[i] = hvdc.getR();
        }
    }

    /** The HVDC lines of the network in AC emulation. */
    public static HvdcAcEmulationVector of(LfNetwork network) {
        return new HvdcAcEmulationVector(network.getHvdcs().stream().filter(LfHvdc::isAcEmulation).toList());
    }
}
