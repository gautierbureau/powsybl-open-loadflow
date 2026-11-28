/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.ac.solver.AcSolverStatus;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ModifiedElementsCollectorTest {

    /** What changes is collected once, as its bus or as a branch, unless the collector is off; a reset forgets it. */
    @Test
    void collectsTheBusesAndTheBranchesThatChange() {
        LfNetwork lfNetwork = Networks.load(EurostagTutorialExample1Factory.create(), new MostMeshedSlackBusSelector()).getFirst();
        LfBus loadBus = lfNetwork.getBusById("VLLOAD_0");
        LfLoad load = loadBus.getLoads().getFirst();
        LfBus generatorBus = lfNetwork.getBusById("VLGEN_0");
        LfGenerator generator = generatorBus.getGenerators().getFirst();
        LfBranch line = lfNetwork.getBranchById("NHV1_NHV2_1");
        ModifiedElementsCollector collector = new ModifiedElementsCollector(lfNetwork);
        lfNetwork.addListener(collector);
        assertTrue(collector.getBuses().isEmpty());
        assertTrue(collector.getBranches().isEmpty());

        load.setTargetP(load.getTargetP() + 1);
        generator.setTargetP(generator.getTargetP() + 1);
        line.setDisabled(true);
        load.setTargetQ(load.getTargetQ() + 1);
        assertEquals(List.of(loadBus, generatorBus), collector.getBuses());
        assertEquals(List.of(line), collector.getBranches());

        collector.reset();
        assertTrue(collector.getBuses().isEmpty());
        assertTrue(collector.getBranches().isEmpty());

        // off, as it is while the collected elements are restored
        collector.setEnabled(false);
        load.setTargetP(load.getTargetP() - 1);
        line.setDisabled(false);
        assertTrue(collector.getBuses().isEmpty());
        assertTrue(collector.getBranches().isEmpty());
        collector.setEnabled(true);
        generatorBus.setDisabled(true);
        assertEquals(List.of(generatorBus), collector.getBuses());
    }

    /**
     * What a security analysis does: a line and a generator are lost, and the load flow that follows moves the other
     * generators, the voltages and the buses that reach a reactive limit. Restoring the collected elements, and the
     * voltage of every bus, leaves the network as it was saved. Restoring the voltages alone does not.
     */
    @Test
    void restoringWhatWasCollectedRestoresTheNetwork() {
        Network network = IeeeCdfNetworkFactory.create118();
        LoadFlowParameters parameters = new LoadFlowParameters();
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters,
                OpenLoadFlowParameters.create(parameters), new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            assertEquals(AcSolverStatus.CONVERGED, new AcloadFlowEngine(context).run().getSolverStatus());
            NetworkState state = NetworkState.save(lfNetwork);
            assertNull(state.findDifference());
            ModifiedElementsCollector collector = new ModifiedElementsCollector(lfNetwork);
            lfNetwork.addListener(collector);

            LfBranch line = lfNetwork.getBranches().get(0);
            LfGenerator generator = lfNetwork.getBuses().stream()
                    .filter(bus -> !bus.isSlack())
                    .flatMap(bus -> bus.getGenerators().stream())
                    .filter(g -> g.getTargetP() > 0)
                    .findFirst().orElseThrow();
            LfContingency contingency = new LfContingency("contingency", 0, 0, new DisabledNetwork(Set.of(), Set.of(line)),
                    Map.of(), Map.of(), Set.of(generator), Set.of());
            contingency.apply(parameters.getBalanceType());
            collector.add(contingency);
            assertEquals(AcSolverStatus.CONVERGED, new AcloadFlowEngine(context).run().getSolverStatus());
            assertNotNull(state.findDifference());

            // the voltages alone: the line is still lost, the generators have moved
            state.restoreVoltages();
            assertNotNull(state.findDifference());

            collector.setEnabled(false);
            state.restore(collector.getBuses(), collector.getBranches());
            collector.setEnabled(true);
            assertNull(state.findDifference());
            assertTrue(collector.getBuses().contains(generator.getBus()));
            assertEquals(List.of(line), collector.getBranches());
            // and it was a part of the network only
            assertTrue(collector.getBuses().size() < lfNetwork.getBuses().size());
        }
    }
}
