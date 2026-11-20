/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.*;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcContingencyDelta;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.impl.LfNetworkList;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.impl.PropagatedContingencyCreationParameters;
import com.powsybl.openloadflow.sa.DefaultContingencyActivePowerLossDistribution;
import com.powsybl.security.SecurityAnalysisParameters;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Installing a contingency's record ({@link AcContingencyDelta#install}) on the base state leaves every bus and every
 * branch as OLF's rules leave them: {@link LfContingency#apply}, then the spreading of the loss, as a security analysis
 * does. This is what lets a security analysis, and a device, install a contingency from its record alone.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ContingencyInstallTest {

    /** The reactive target of each bus, which the bus states compare only when it is frozen. */
    private static double[] generationTargetQ(LfNetwork network) {
        return network.getBuses().stream().mapToDouble(b -> b.isDisabled() ? Double.NaN : b.getGenerationTargetQ()).toArray();
    }

    private record Checked(int count, int onBaseStructure, int slackMoved) {
    }

    /** Checks every contingency in turn, on one context. */
    private static Checked checkInstall(Network network, LoadFlowParameters parameters, List<Contingency> contingencies) {
        LfTopoConfig topoConfig = new LfTopoConfig();
        List<PropagatedContingency> propagated = PropagatedContingency.createList(network, contingencies, topoConfig,
                new PropagatedContingencyCreationParameters().setContingencyPropagation(true));
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, OpenLoadFlowParameters.get(parameters),
                new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), topoConfig.isBreaker(), false);
        LfNetworkList networks = Networks.loadWithReconnectableElements(network, topoConfig, acParameters.getNetworkParameters(), ReportNode.NO_OP);
        LfNetwork lfNetwork = networks.getLargest().orElseThrow();
        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters().setLoadFlowParameters(parameters);
        int onBaseStructure = 0;
        int checked = 0;
        int slackMoved = 0;
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            new AcloadFlowEngine(context).run();
            NetworkState state = NetworkState.save(lfNetwork);
            ModifiedElementsCollector touched = new ModifiedElementsCollector(lfNetwork);
            lfNetwork.addListener(touched);
            List<LfBus> baseSlackBuses = List.copyOf(lfNetwork.getSynchronousNetworks().getFirst().getSlackBuses());
            AcContingencyDelta.Baseline base = AcContingencyDelta.baseline(context);
            for (PropagatedContingency contingency : propagated) {
                var lfContingency = contingency.toLfContingency(lfNetwork);
                if (lfContingency.isEmpty()) {
                    continue;
                }
                String id = contingency.getContingency().getId();
                touched.reset();
                touched.setEnabled(true);
                lfContingency.get().apply(parameters.getBalanceType());
                touched.add(lfContingency.get());
                double spread = new DefaultContingencyActivePowerLossDistribution().run(lfNetwork, lfContingency.get(),
                        contingency.getContingency(), saParameters, null, ReportNode.NO_OP);
                List<BusState> buses = ElementState.save(lfNetwork.getBuses(), BusState::save);
                List<BranchState> branches = ElementState.save(lfNetwork.getBranches(), BranchState::save);
                double[] generationTargetQ = generationTargetQ(lfNetwork);
                List<LfBus> slackBuses = List.copyOf(lfNetwork.getSynchronousNetworks().getFirst().getSlackBuses());
                // recorded from what the contingency touched, as the security analysis does
                AcContingencyDelta delta = AcContingencyDelta.record(lfContingency.get(), context, base, spread,
                        touched.getBuses(), touched.getBranches());
                touched.setEnabled(false);
                state.restore();
                delta.install(lfNetwork, base);
                for (BusState bus : buses) {
                    assertNull(bus.findDifference(), id + ", bus " + bus.element.getId());
                }
                for (BranchState branch : branches) {
                    assertNull(branch.findDifference(), id + ", branch " + branch.element.getId());
                }
                assertArrayEquals(generationTargetQ, generationTargetQ(lfNetwork), id + ", generation target Q");
                assertEquals(slackBuses, lfNetwork.getSynchronousNetworks().getFirst().getSlackBuses(), id);
                state.restore();
                assertNull(state.findDifference(), id);
                checked++;
                if (delta.onBaseStructure()) {
                    onBaseStructure++;
                }
                if (!slackBuses.equals(baseSlackBuses)) {
                    slackMoved++;
                }
            }
        }
        networks.close();
        return new Checked(checked, onBaseStructure, slackMoved);
    }

    /** Lost branches and generators, with the loss spread over the other generators. */
    @Test
    void everyBranchAndGeneratorOfIeee57() {
        Network network = IeeeCdfNetworkFactory.create57();
        List<Contingency> contingencies = new ArrayList<>();
        network.getBranches().forEach(b -> contingencies.add(new Contingency(b.getId(), new BranchContingency(b.getId()))));
        network.getGenerators().forEach(g -> contingencies.add(new Contingency(g.getId(), new GeneratorContingency(g.getId()))));
        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(true);
        OpenLoadFlowParameters.create(parameters);
        Checked checked = checkInstall(network, parameters, contingencies);
        assertEquals(contingencies.size(), checked.count());
        assertEquals(checked.count(), checked.onBaseStructure());
    }

    /** Lost loads, with the loss spread over the loads: their targets, variable targets and disabled original loads. */
    @Test
    void everyLoadOfIeee14() {
        Network network = IeeeCdfNetworkFactory.create14();
        List<Contingency> contingencies = new ArrayList<>();
        network.getLoads().forEach(l -> contingencies.add(new Contingency(l.getId(), new LoadContingency(l.getId()))));
        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(true)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        OpenLoadFlowParameters.create(parameters);
        Checked checked = checkInstall(network, parameters, contingencies);
        assertEquals(contingencies.size(), checked.count());
        assertEquals(checked.count(), checked.onBaseStructure());
    }

    /**
     * Every bus of the Eurostag example, the slack bus NGEN among them: the record moves the slack bus off the buses the
     * contingency cuts off, before it disables them.
     */
    @Test
    void everyBusOfEurostagWithItsSlackBus() {
        Network network = EurostagTutorialExample1Factory.create();
        List<Contingency> contingencies = network.getBusBreakerView().getBusStream()
                .map(bus -> new Contingency(bus.getId(), new BusContingency(bus.getId())))
                .toList();
        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(com.powsybl.openloadflow.network.SlackBusSelectionMode.NAME)
                .setSlackBusesIds(List.of("NGEN"));
        Checked checked = checkInstall(network, parameters, contingencies);
        assertTrue(checked.count() > 0);
        assertTrue(checked.slackMoved() > 0);
    }
}
