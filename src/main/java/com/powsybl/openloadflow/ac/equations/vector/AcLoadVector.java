/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.util.Evaluable;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The loads of a network whose power follows the voltage of their bus, as arrays. A bus can have several of them, one
 * for each load model: the equation system needs their sum, by bus number, and each load its own power.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class AcLoadVector {

    /** The loads that follow a model, in the order their terms are created: those of a bus one after the other. */
    final List<LfLoad> loads = new ArrayList<>();
    /** For each of these loads: its active and its reactive power at the current state. */
    final TDoubleArrayList p = new TDoubleArrayList();
    final TDoubleArrayList q = new TDoubleArrayList();

    /** The buses that have such loads. */
    final TIntArrayList busNums = new TIntArrayList();

    // The arrays by bus number are created with the first load that follows a model: they are null in a network that
    // has no such load.

    /** By bus number: the first of the loads of the bus in {@link #loads}, -1 when it has none, and their count. */
    int[] firstLoad;
    int[] loadCount;

    /** By bus number: the power the loads of the bus take at the current state, and its derivative to the voltage. */
    double[] busP;
    double[] busDpdv;
    double[] busQ;
    double[] busDqdv;

    private final int busCount;

    public AcLoadVector(int busCount) {
        this.busCount = busCount;
    }

    /**
     * Adds a load that follows a model, when its terms are created, and returns its number. The loads of a bus are
     * added one after the other.
     */
    int add(LfBus bus, LfLoad load) {
        if (firstLoad == null) {
            firstLoad = new int[busCount];
            Arrays.fill(firstLoad, -1);
            loadCount = new int[busCount];
            busP = new double[busCount];
            busDpdv = new double[busCount];
            busQ = new double[busCount];
            busDqdv = new double[busCount];
        }
        int busNum = bus.getNum();
        int loadNum = loads.size();
        if (firstLoad[busNum] < 0) {
            firstLoad[busNum] = loadNum;
            busNums.add(busNum);
        } else if (firstLoad[busNum] + loadCount[busNum] != loadNum) {
            throw new IllegalStateException("The loads of bus " + bus.getId() + " are not added one after the other");
        }
        loadCount[busNum]++;
        loads.add(load);
        p.add(0);
        q.add(0);
        return loadNum;
    }

    /** The loads of a bus that follow a model. */
    public List<LfLoad> getLoads(int busNum) {
        return firstLoad == null || firstLoad[busNum] < 0 ? List.of() : loads.subList(firstLoad[busNum], firstLoad[busNum] + loadCount[busNum]);
    }

    public Evaluable getP(int loadNum) {
        return () -> p.getQuick(loadNum);
    }

    public Evaluable getQ(int loadNum) {
        return () -> q.getQuick(loadNum);
    }
}
