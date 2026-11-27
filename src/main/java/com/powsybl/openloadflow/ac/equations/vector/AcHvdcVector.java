/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.network.LfHvdc;

import java.util.Arrays;
import java.util.List;

/**
 * The HVDC lines in AC emulation of a network as arrays, indexed by HVDC line number: the two buses of each one, the
 * constants of its control, and its active power at both ends with their derivatives at the current state.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class AcHvdcVector {

    /** The buses of each HVDC line that has terms in the equation system, -1 for the others. */
    public final int[] bus1Num;
    public final int[] bus2Num;
    public final boolean[] disabled;

    public final double[] k;
    public final double[] p0;
    public final double[] r;
    public final double[] lossFactor1;
    public final double[] lossFactor2;

    public final double[] p1;
    public final double[] dp1dph1;
    public final double[] dp1dph2;
    public final double[] p2;
    public final double[] dp2dph1;
    public final double[] dp2dph2;

    public AcHvdcVector(List<LfHvdc> hvdcs) {
        int size = hvdcs.size();
        bus1Num = new int[size];
        bus2Num = new int[size];
        Arrays.fill(bus1Num, -1);
        Arrays.fill(bus2Num, -1);
        disabled = new boolean[size];
        for (int hvdcNum = 0; hvdcNum < size; hvdcNum++) {
            disabled[hvdcNum] = hvdcs.get(hvdcNum).isDisabled();
        }
        k = new double[size];
        p0 = new double[size];
        r = new double[size];
        lossFactor1 = new double[size];
        lossFactor2 = new double[size];
        p1 = new double[size];
        dp1dph1 = new double[size];
        dp1dph2 = new double[size];
        p2 = new double[size];
        dp2dph1 = new double[size];
        dp2dph2 = new double[size];
    }

    public int getSize() {
        return bus1Num.length;
    }

    /**
     * Says where an HVDC line in AC emulation is and reads the constants of its control, when its terms are created:
     * as a term held as an object reads them when it is constructed.
     */
    void set(LfHvdc hvdc) {
        int hvdcNum = hvdc.getNum();
        bus1Num[hvdcNum] = hvdc.getBus1().getNum();
        bus2Num[hvdcNum] = hvdc.getBus2().getNum();
        k[hvdcNum] = hvdc.getAcEmulationControl().getDroop() * 180 / Math.PI;
        p0[hvdcNum] = hvdc.getAcEmulationControl().getP0();
        r[hvdcNum] = hvdc.getR();
        lossFactor1[hvdcNum] = hvdc.getConverterStation1().getLossFactor() / 100;
        lossFactor2[hvdcNum] = hvdc.getConverterStation2().getLossFactor() / 100;
    }
}
