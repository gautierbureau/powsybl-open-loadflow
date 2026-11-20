/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.impl.Networks;

import java.nio.file.Path;
import java.util.Properties;

/**
 * Checks on a real network that the shares of the remote voltage control groups computed on {@link DistrQGroups} are
 * OLF's reactive keys to the last bit, after a load flow. Arguments: the network, the load flow parameters (.json).
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class DistrQGroupsCheck {

    private DistrQGroupsCheck() {
    }

    public static void main(String[] args) {
        Properties properties = new Properties();
        properties.setProperty("iidm.import.repair-invalid-reactive-curve-limits", "true");
        Network network = Network.read(Path.of(args[0]), com.powsybl.computation.local.LocalComputationManager.getDefault(),
                com.powsybl.iidm.network.ImportConfig.load(), properties);
        LoadFlowParameters parameters = com.powsybl.loadflow.json.JsonLoadFlowParameters.read(Path.of(args[1]));
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, OpenLoadFlowParameters.get(parameters),
                new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            new AcloadFlowEngine(context).run();
            DistrQGroups groups = DistrQGroups.of(lfNetwork);
            boolean[] active = groups.getActiveMembers();
            double[] shares = groups.distribute(active);
            int inactive = 0;
            int same = 0;
            for (int m = 0; m < shares.length; m++) {
                inactive += active[m] ? 0 : 1;
                LfBus bus = lfNetwork.getBus(groups.getMembers()[m]);
                if (Double.doubleToLongBits(bus.getRemoteControlReactivePercent()) == Double.doubleToLongBits(shares[m])) {
                    same++;
                } else {
                    System.out.printf("  %s: OLF %s, tables %s%n", bus.getId(), bus.getRemoteControlReactivePercent(), shares[m]); // NOSONAR
                }
            }
            System.out.printf("%d groups, %d members (%d out of the control), %d shares equal to OLF's to the last bit%n", // NOSONAR
                    groups.getGroupCount(), shares.length, inactive, same);
        }
    }
}
