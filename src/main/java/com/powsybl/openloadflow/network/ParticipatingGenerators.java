/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The generators of a set of buses that take part in the slack distribution, as arrays, bus by bus: what any
 * participation type reads (maximum power and droop, target, participation factor, remaining margin) and the limits
 * the distribution clamps at. The generators of bus {@code b} (the {@code b}-th bus given) are {@code firstGenerator[b]}
 * to {@code firstGenerator[b + 1] - 1}. Read when built.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class ParticipatingGenerators {

    public final int busCount;
    public final int[] busNum;
    public final int[] firstGenerator;
    public final int count;
    /** The generator's index among its bus's generators ({@link LfBus#getGenerators()}). */
    public final int[] indexInBus;
    public final double[] targetP;
    public final double[] initialTargetP;
    public final double[] minTargetP;
    public final double[] maxTargetP;
    public final double[] minP;
    public final double[] maxP;
    public final double[] droop;
    public final double[] participationFactor;

    private ParticipatingGenerators(List<LfBus> buses) {
        busCount = buses.size();
        busNum = new int[busCount];
        firstGenerator = new int[busCount + 1];
        List<LfGenerator> generators = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (int b = 0; b < busCount; b++) {
            LfBus bus = buses.get(b);
            busNum[b] = bus.getNum();
            firstGenerator[b] = generators.size();
            List<LfGenerator> busGenerators = bus.getGenerators();
            for (int g = 0; g < busGenerators.size(); g++) {
                if (busGenerators.get(g).isParticipating()) {
                    generators.add(busGenerators.get(g));
                    indices.add(g);
                }
            }
        }
        firstGenerator[busCount] = generators.size();
        count = generators.size();
        indexInBus = new int[count];
        targetP = new double[count];
        initialTargetP = new double[count];
        minTargetP = new double[count];
        maxTargetP = new double[count];
        minP = new double[count];
        maxP = new double[count];
        droop = new double[count];
        participationFactor = new double[count];
        for (int i = 0; i < count; i++) {
            LfGenerator generator = generators.get(i);
            indexInBus[i] = indices.get(i);
            targetP[i] = generator.getTargetP();
            initialTargetP[i] = generator.getInitialTargetP();
            minTargetP[i] = generator.getMinTargetP();
            maxTargetP[i] = generator.getMaxTargetP();
            minP[i] = generator.getMinP();
            maxP[i] = generator.getMaxP();
            droop[i] = generator.getDroop();
            participationFactor[i] = generator.getParticipationFactor();
        }
    }

    /** The participating generators of the given buses, in their order. */
    public static ParticipatingGenerators of(Collection<LfBus> buses) {
        return new ParticipatingGenerators(new ArrayList<>(buses));
    }
}
