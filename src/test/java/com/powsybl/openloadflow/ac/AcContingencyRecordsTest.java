/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.GeneratorContingency;
import com.powsybl.contingency.LoadContingency;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfContingency;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfTopoConfig;
import com.powsybl.openloadflow.network.NetworkState;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.impl.PropagatedContingencyCreationParameters;
import com.powsybl.openloadflow.sa.DefaultContingencyActivePowerLossDistribution;
import com.powsybl.security.SecurityAnalysisParameters;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The records of a security analysis as flat tables ({@link AcContingencyRecords}): each record reads back from the
 * tables as it was added, component by component.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AcContingencyRecordsTest {

    private static Object box(Object value) {
        if (value instanceof Object[] objects) {
            return objects;
        }
        return value;
    }

    /** Two records with the same values, the arrays compared by content. */
    private static void assertSameRecord(AcContingencyDelta expected, AcContingencyDelta actual) throws ReflectiveOperationException {
        for (RecordComponent component : AcContingencyDelta.class.getRecordComponents()) {
            Object e = component.getAccessor().invoke(expected);
            Object a = component.getAccessor().invoke(actual);
            assertTrue(Objects.deepEquals(box(e), box(a)), expected.contingencyId() + ", " + component.getName());
        }
    }

    /** Records every given contingency of the network, with the loss spread, as a security analysis plans them. */
    private static List<AcContingencyDelta> record(Network network, LoadFlowParameters parameters, List<Contingency> contingencies,
                                                   AcContingencyRecords.Builder[] builder) {
        List<PropagatedContingency> propagated = PropagatedContingency.createList(network, contingencies, new LfTopoConfig(),
                new PropagatedContingencyCreationParameters());
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, OpenLoadFlowParameters.get(parameters),
                new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters().setLoadFlowParameters(parameters);
        List<AcContingencyDelta> deltas = new ArrayList<>();
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            new AcloadFlowEngine(context).run();
            NetworkState state = NetworkState.save(lfNetwork);
            AcContingencyDelta.Baseline base = AcContingencyDelta.baseline(context);
            builder[0] = new AcContingencyRecords.Builder(base);
            for (PropagatedContingency contingency : propagated) {
                LfContingency lfContingency = contingency.toLfContingency(lfNetwork).orElse(null);
                if (lfContingency == null) {
                    continue;
                }
                lfContingency.apply(parameters.getBalanceType());
                double spread = new DefaultContingencyActivePowerLossDistribution().run(lfNetwork, lfContingency,
                        contingency.getContingency(), saParameters, null, ReportNode.NO_OP);
                AcContingencyDelta delta = AcContingencyDelta.record(lfContingency, context, base, spread);
                assertEquals(deltas.size(), builder[0].add(delta));
                deltas.add(delta);
                state.restore();
            }
            assertArrayEquals(base.getModes(), builder[0].build().getBaseModes());
        }
        return deltas;
    }

    /** Every load of IEEE 14, its loss spread over the loads: the load tables. */
    @Test
    void loadsReadBackAsAdded() throws ReflectiveOperationException {
        Network network = IeeeCdfNetworkFactory.create14();
        List<Contingency> contingencies = new ArrayList<>();
        network.getLoads().forEach(l -> contingencies.add(new Contingency(l.getId(), new LoadContingency(l.getId()))));
        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(true)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        OpenLoadFlowParameters.create(parameters);
        AcContingencyRecords.Builder[] builder = new AcContingencyRecords.Builder[1];
        List<AcContingencyDelta> deltas = record(network, parameters, contingencies, builder);
        AcContingencyRecords records = builder[0].build();
        assertEquals(contingencies.size(), records.size());
        for (int k = 0; k < records.size(); k++) {
            assertSameRecord(deltas.get(k), records.get(k));
        }
        assertTrue(records.getLoads().length >= records.size());
        assertEquals(records.getLoads().length, records.getLoadTargetQ().length);
    }

    /**
     * Every branch and generator of IEEE 57: the slots and the other tables. The loss is not spread: spreading it then
     * restoring the whole network without a solve leaves a reactive target that AcTargetVector keeps stale, on #204
     * already; the spreading is read back on IEEE 14.
     */
    @Test
    void readBackAsAdded() throws ReflectiveOperationException {
        Network network = IeeeCdfNetworkFactory.create57();
        List<Contingency> contingencies = new ArrayList<>();
        network.getBranches().forEach(b -> contingencies.add(new Contingency(b.getId(), new BranchContingency(b.getId()))));
        network.getGenerators().forEach(g -> contingencies.add(new Contingency(g.getId(), new GeneratorContingency(g.getId()))));
        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(false);
        OpenLoadFlowParameters.create(parameters);
        AcContingencyRecords.Builder[] builder = new AcContingencyRecords.Builder[1];
        List<AcContingencyDelta> deltas = record(network, parameters, contingencies, builder);
        AcContingencyRecords records = builder[0].build();

        assertEquals(deltas.size(), records.size());
        assertTrue(records.size() > 80);
        for (int k = 0; k < records.size(); k++) {
            assertEquals(deltas.get(k).contingencyId(), records.getContingencyId(k));
            assertSameRecord(deltas.get(k), records.get(k));
        }
        // the tables: one offset more than contingencies, each table ending at its length
        assertEquals(records.size() + 1, records.getSlotOffsets().length);
        assertEquals(records.getSlots().length, records.getSlotOffsets()[records.size()]);
        assertEquals(records.getSlots().length, records.getSlotTargets().length);
        assertEquals(records.getGenerators().length, records.getGeneratorOffsets()[records.size()]);
        assertEquals(records.getGenerators().length, records.getGeneratorFlags().length);
        assertTrue(records.getGenerators().length > 0);

        // the text writing is the same from the same records
        StringWriter w1 = new StringWriter();
        records.write(w1);
        StringWriter w2 = new StringWriter();
        builder[0].build().write(w2);
        assertEquals(w1.toString(), w2.toString());
        assertTrue(w1.toString().startsWith("BASE modes "));
    }
}
