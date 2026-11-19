/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfShunt;

import java.util.Arrays;
import java.util.List;

/**
 * The shunts of a network as arrays, indexed by shunt number: where each one is, and its active and reactive power
 * with their derivatives at the current state.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class AcShuntVector {

    final LfShunt[] shunts;

    /** The bus of each shunt that has terms in the equation system, -1 for the others. */
    public final int[] busNum;
    /** True for a shunt whose susceptance is a variable. */
    public final boolean[] deriveB;
    public final boolean[] disabled;
    /** The row of the susceptance of each shunt, -1 when it is not a variable of the system. */
    public final int[] bRow;

    /** The susceptance used at the current state: the variable, or the one of the shunt. */
    public final double[] b;
    public final double[] p;
    public final double[] dpdv;
    public final double[] q;
    public final double[] dqdv;
    public final double[] dqdb;

    public AcShuntVector(List<LfShunt> shunts) {
        int size = shunts.size();
        this.shunts = shunts.toArray(new LfShunt[0]);
        busNum = new int[size];
        Arrays.fill(busNum, -1);
        deriveB = new boolean[size];
        disabled = new boolean[size];
        for (int shuntNum = 0; shuntNum < size; shuntNum++) {
            disabled[shuntNum] = shunts.get(shuntNum).isDisabled();
        }
        bRow = new int[size];
        Arrays.fill(bRow, -1);
        b = new double[size];
        p = new double[size];
        dpdv = new double[size];
        q = new double[size];
        dqdv = new double[size];
        dqdb = new double[size];
    }

    public int getSize() {
        return shunts.length;
    }

    /** Says where a shunt is, and whether its susceptance is a variable, when its terms are created. */
    void set(LfShunt shunt, LfBus bus, boolean deriveB) {
        busNum[shunt.getNum()] = bus.getNum();
        this.deriveB[shunt.getNum()] = deriveB;
    }
}
