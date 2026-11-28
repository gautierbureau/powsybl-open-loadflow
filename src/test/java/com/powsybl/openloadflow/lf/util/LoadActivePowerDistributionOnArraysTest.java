/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.util;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.openloadflow.network.DistributedSlackNetworkFactory;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfNetworkParameters;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The load distribution on arrays ({@link LoadActivePowerDistributionStep#distribute}) against the distribution on the
 * participating elements it replaces (the default {@link ActivePowerDistribution.Step#distribute}): the same targets to
 * the last bit, the same iterations and remaining mismatch, distribution after distribution.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class LoadActivePowerDistributionOnArraysTest {

    /** The step's own methods, through the default distribution. */
    private static ActivePowerDistribution.Step onElements(LoadActivePowerDistributionStep step) {
        return new ActivePowerDistribution.Step() {
            @Override
            public String getElementType() {
                return step.getElementType();
            }

            @Override
            public ActivePowerDistribution.PreviousStateInfo resetToInitialState(Collection<LfBus> participatingBuses, LfGenerator referenceGenerator) {
                return step.resetToInitialState(participatingBuses, referenceGenerator);
            }

            @Override
            public List<ParticipatingElement> getParticipatingElements(Collection<LfBus> participatingBuses, double mismatch) {
                return step.getParticipatingElements(participatingBuses, mismatch);
            }

            @Override
            public double run(List<ParticipatingElement> participatingElements, int iteration, double remainingMismatch) {
                return step.run(participatingElements, iteration, remainingMismatch);
            }
        };
    }

    private static LfNetwork load(Network network) {
        return Networks.load(network, new LfNetworkParameters().setDistributedOnConformLoad(false)).getFirst();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameTargets(boolean loadPowerFactorConstant) {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();
        // a load with a negative active power next to another one: its power factor is kept load by load
        Bus bus = network.getLoad("l2").getTerminal().getBusBreakerView().getBus();
        Load negative = bus.getVoltageLevel().newLoad().setId("negative").setBus(bus.getId()).setConnectableBus(bus.getId())
                .setP0(-20).setQ0(5).add();
        assertNotNull(negative);

        LfNetwork onArrays = load(network);
        LfNetwork onElements = load(network);
        LoadActivePowerDistributionStep step = new LoadActivePowerDistributionStep(loadPowerFactorConstant);
        ActivePowerDistribution.Step reference = onElements(step);

        for (double mismatch : new double[] {0.3, -0.45, 1.2, 1e-6}) {
            LfGenerator generatorA = onArrays.getBuses().stream().flatMap(b -> b.getGenerators().stream()).findFirst().orElseThrow();
            LfGenerator generatorB = onElements.getBuses().stream().flatMap(b -> b.getGenerators().stream()).findFirst().orElseThrow();
            ActivePowerDistribution.Result a = step.distribute(generatorA, onArrays.getBuses(), mismatch);
            ActivePowerDistribution.Result b = reference.distribute(generatorB, onElements.getBuses(), mismatch);
            assertEquals(b.iteration(), a.iteration());
            assertEquals(Double.doubleToLongBits(b.remainingMismatch()), Double.doubleToLongBits(a.remainingMismatch()));
            assertEquals(b.movedBuses(), a.movedBuses());
            assertEquals(Double.doubleToLongBits(generatorB.getTargetP()), Double.doubleToLongBits(generatorA.getTargetP()));
            for (LfBus busA : onArrays.getBuses()) {
                List<LfLoad> loadsA = busA.getLoads();
                List<LfLoad> loadsB = onElements.getBusById(busA.getId()).getLoads();
                for (int i = 0; i < loadsA.size(); i++) {
                    assertEquals(Double.doubleToLongBits(loadsB.get(i).getTargetP()), Double.doubleToLongBits(loadsA.get(i).getTargetP()), loadsA.get(i).getId());
                    assertEquals(Double.doubleToLongBits(loadsB.get(i).getTargetQ()), Double.doubleToLongBits(loadsA.get(i).getTargetQ()), loadsA.get(i).getId());
                }
            }
        }
        assertTrue(onArrays.getBuses().stream().flatMap(b -> b.getLoads().stream()).anyMatch(LfLoad::ensurePowerFactorConstantByLoad));
        assertTrue(onArrays.getBuses().stream().flatMap(b -> b.getLoads().stream()).anyMatch(l -> l.getTargetP() != l.getInitialTargetP()));
        if (loadPowerFactorConstant) {
            assertTrue(onArrays.getBuses().stream().flatMap(b -> b.getLoads().stream())
                    .anyMatch(l -> l.ensurePowerFactorConstantByLoad() && l.getTargetP() != l.getInitialTargetP()));
        }
    }
}
