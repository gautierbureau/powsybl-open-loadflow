/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.GeneratorContingency;
import com.powsybl.contingency.LoadContingency;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.fixed.FixedEquationSystemIndex;
import com.powsybl.openloadflow.equations.fixed.FixedStructure;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfContingency;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfTopoConfig;
import com.powsybl.openloadflow.network.NetworkState;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.impl.PropagatedContingencyCreationParameters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The delta of a contingency, recorded once OLF has applied it, against the base state of IEEE 14.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AcContingencyDeltaTest {

    /**
     * Solves the base state, then applies the contingency as a security analysis does, records its delta and checks
     * it: written over the base modes and targets, it gives those of the contingency; once the network is restored, a
     * delta taken again is empty.
     */
    private static void check(Contingency contingency, Consumer<Recorded> assertions) {
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters parameters = new LoadFlowParameters();
        PropagatedContingency propagated = PropagatedContingency.createList(network, List.of(contingency), new LfTopoConfig(),
                new PropagatedContingencyCreationParameters()).get(0);
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, OpenLoadFlowParameters.create(parameters),
                new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            new AcloadFlowEngine(context).run();
            NetworkState state = NetworkState.save(lfNetwork);
            AcContingencyDelta.Baseline base = AcContingencyDelta.baseline(context);

            LfContingency lfContingency = propagated.toLfContingency(lfNetwork).orElseThrow();
            lfContingency.apply(parameters.getBalanceType());
            AcContingencyDelta delta = AcContingencyDelta.record(lfContingency, context, base);
            assertNotNull(delta);

            int[] modes = base.getModes();
            double[] targets = base.getTargets();
            delta.applyTo(modes, targets);
            AcContingencyDelta.Baseline after = AcContingencyDelta.baseline(context);
            assertArrayEquals(after.getModes(), modes);
            assertArrayEquals(after.getTargets(), targets);
            assertions.accept(new Recorded(lfNetwork, context, delta));

            state.restore();
            AcContingencyDelta restored = AcContingencyDelta.record(lfContingency, context, base);
            assertEquals(0, restored.positions().length, "the base state again");
        }
    }

    private record Recorded(LfNetwork network, AcLoadFlowContext context, AcContingencyDelta delta) {

        FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed() {
            return ((AcFixedJacobianMatrix) context.getJacobianMatrix()).getFixedJacobian().getFixedEquationSystemIndex();
        }

        int position(LfBus bus, AcVariableType type) {
            return fixed().getStructure().getPosition(context.getEquationSystem().getVariable(bus.getNum(), type));
        }

        /** The position of the position in the delta, -1 when it did not change. */
        int indexOf(int position) {
            for (int i = 0; i < delta.positions().length; i++) {
                if (delta.positions()[i] == position) {
                    return i;
                }
            }
            return -1;
        }

        AcEquationType typeHeld(int i) {
            FixedStructure<AcVariableType, AcEquationType> structure = fixed().getStructure();
            int position = delta.positions()[i];
            return structure.getType(structure.getAlternatives(position)[delta.modes()[i]]);
        }
    }

    /** A line whose loss islands nothing changes no position: its branch is masked, that is all. */
    @Test
    void aLineOutageChangesNoPosition() {
        check(new Contingency("L1-2-1", new BranchContingency("L1-2-1")), r -> {
            assertEquals(0, r.delta().positions().length);
            assertArrayEquals(new int[] {r.network().getBranchById("L1-2-1").getNum()}, r.delta().disabledBranches());
            assertEquals(0, r.delta().disabledBuses().length);
            assertEquals(0, r.delta().activePowerLoss());
        });
    }

    /** The line to bus 8 is its only link: the bus is islanded and its two positions hold nothing. */
    @Test
    void anIslandedBusHoldsNothing() {
        check(new Contingency("L7-8-1", new BranchContingency("L7-8-1")), r -> {
            LfBus bus8 = r.network().getGeneratorById("B8-G").getBus();
            assertArrayEquals(new int[] {bus8.getNum()}, r.delta().disabledBuses());
            for (AcVariableType type : List.of(AcVariableType.BUS_V, AcVariableType.BUS_PHI)) {
                int i = r.indexOf(r.position(bus8, type));
                assertTrue(i >= 0, type + " of the islanded bus");
                assertEquals(FixedEquationSystemIndex.MODE_NONE, r.delta().modes()[i]);
                assertTrue(Double.isNaN(r.delta().targets()[i]));
            }
        });
    }

    /**
     * The generator of bus 2 is its only one: its active power leaves the balance of the bus, and the bus stops
     * holding its voltage, so its voltage position holds its reactive balance, at the target Q of the bus.
     */
    @Test
    void aLostGeneratorMovesItsBusToItsReactiveBalance() {
        check(new Contingency("B2-G", new GeneratorContingency("B2-G")), r -> {
            LfBus bus2 = r.network().getGeneratorById("B2-G").getBus();
            int v = r.indexOf(r.position(bus2, AcVariableType.BUS_V));
            assertTrue(v >= 0);
            assertEquals(AcEquationType.BUS_TARGET_Q, r.typeHeld(v));
            assertEquals(bus2.getTargetQ(), r.delta().targets()[v]);
            int p = r.indexOf(r.position(bus2, AcVariableType.BUS_PHI));
            assertTrue(p >= 0);
            assertEquals(AcEquationType.BUS_TARGET_P, r.typeHeld(p));
            assertEquals(bus2.getTargetP(), r.delta().targets()[p]);
            assertEquals(0.4, r.delta().activePowerLoss(), 1e-12);
        });
    }

    /**
     * A lost load moves the target of the active balance of its bus, and nothing else: bus 3 holds its voltage, so its
     * voltage position holds its voltage target, which the reactive power of the load does not move.
     */
    @Test
    void aLostLoadMovesTheActiveTargetOfItsBus() {
        check(new Contingency("B3-L", new LoadContingency("B3-L")), r -> {
            LfBus bus3 = r.network().getGeneratorById("B3-G").getBus();
            int phi = r.position(bus3, AcVariableType.BUS_PHI);
            assertArrayEquals(new int[] {phi}, r.delta().positions());
            assertEquals(AcEquationType.BUS_TARGET_P, r.typeHeld(0));
            assertEquals(bus3.getTargetP(), r.delta().targets()[0]);
            assertTrue(r.delta().activePowerLoss() < 0, "a load lost is power gained");
        });
    }

    /**
     * Every branch and every generator of IEEE 57 in turn, on one context, as a security analysis runs them: each delta
     * written over the base state gives the modes and the targets OLF has after the contingency, and the restore
     * gives the base state again. The contingencies that make OLF compile its structure again are counted.
     */
    @Test
    void everyBranchAndGeneratorOfIeee57() {
        Network network = IeeeCdfNetworkFactory.create57();
        List<Contingency> contingencies = new java.util.ArrayList<>();
        network.getBranches().forEach(b -> contingencies.add(new Contingency(b.getId(), new BranchContingency(b.getId()))));
        network.getGenerators().forEach(g -> contingencies.add(new Contingency(g.getId(), new GeneratorContingency(g.getId()))));
        LoadFlowParameters parameters = new LoadFlowParameters();
        List<PropagatedContingency> propagated = PropagatedContingency.createList(network, contingencies, new LfTopoConfig(),
                new PropagatedContingencyCreationParameters());
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, OpenLoadFlowParameters.create(parameters),
                new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        int recorded = 0;
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            new AcloadFlowEngine(context).run();
            NetworkState state = NetworkState.save(lfNetwork);
            AcContingencyDelta.Baseline base = AcContingencyDelta.baseline(context);
            for (PropagatedContingency contingency : propagated) {
                var lfContingency = contingency.toLfContingency(lfNetwork);
                if (lfContingency.isEmpty()) {
                    continue;
                }
                lfContingency.get().apply(parameters.getBalanceType());
                AcContingencyDelta delta = AcContingencyDelta.record(lfContingency.get(), context, base);
                assertNotNull(delta, contingency.getContingency().getId());
                int[] modes = base.getModes();
                double[] targets = base.getTargets();
                delta.applyTo(modes, targets);
                AcContingencyDelta.Baseline after = AcContingencyDelta.baseline(context);
                assertArrayEquals(after.getModes(), modes, contingency.getContingency().getId());
                assertArrayEquals(after.getTargets(), targets, contingency.getContingency().getId());
                state.restore();
                assertEquals(0, AcContingencyDelta.record(lfContingency.get(), context, base).positions().length, contingency.getContingency().getId());
                recorded++;
            }
        }
        assertTrue(recorded > 80, recorded + " contingencies recorded");
    }

    /**
     * With the slack distributed, the loss of the generator of bus 2 is spread over the other generators before the
     * solve, as a security analysis does. The record taken after the spreading carries the lost generator, at a target
     * and an anchor of 0, and every participant it moved, at its new target; they make up the spread power.
     */
    @Test
    void theSpreadLossMovesTheParticipants() {
        Network network = IeeeCdfNetworkFactory.create14();
        Contingency contingency = new Contingency("B2-G", new GeneratorContingency("B2-G"));
        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(true);
        PropagatedContingency propagated = PropagatedContingency.createList(network, List.of(contingency), new LfTopoConfig(),
                new PropagatedContingencyCreationParameters()).get(0);
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, OpenLoadFlowParameters.create(parameters),
                new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            new AcloadFlowEngine(context).run();
            AcContingencyDelta.Baseline base = AcContingencyDelta.baseline(context);
            java.util.Map<String, Double> before = new java.util.HashMap<>();
            lfNetwork.getBuses().forEach(b -> b.getGenerators().forEach(g -> before.put(g.getId(), g.getTargetP())));
            LfContingency lfContingency = propagated.toLfContingency(lfNetwork).orElseThrow();
            lfContingency.apply(parameters.getBalanceType());
            double spread = new com.powsybl.openloadflow.sa.DefaultContingencyActivePowerLossDistribution().run(lfNetwork, lfContingency, contingency,
                    new com.powsybl.security.SecurityAnalysisParameters().setLoadFlowParameters(parameters), null, com.powsybl.commons.report.ReportNode.NO_OP);
            AcContingencyDelta delta = AcContingencyDelta.record(lfContingency, context, base, spread);
            assertEquals(spread, delta.preDistributedActivePower());
            assertTrue(spread > 0);
            List<String> ids = List.of(delta.generatorIds());
            int lost = ids.indexOf("B2-G");
            assertTrue(lost >= 0);
            assertEquals(0, delta.generatorTargetP()[lost]);
            assertEquals(0, delta.generatorInitialTargetP()[lost]);
            double moved = 0;
            for (int g = 0; g < ids.size(); g++) {
                if (g != lost) {
                    moved += delta.generatorTargetP()[g] - before.get(ids.get(g));
                }
            }
            assertEquals(spread, moved, 1e-9);
        }
    }
}
