/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.FourBusNetworkFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
class LodfComputerTest {

    private static final double DELTA_LODF = 1e-6;

    @Test
    void testLodfMatrix() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        List<String> branchIds = List.of("NHV1_NHV2_1", "NHV1_NHV2_2", "NGEN_NHV1");
        DenseMatrix lodfMatrix = LodfComputer.computeLodfMatrix(network, branchIds, branchIds, new LoadFlowParameters());
        // the outage of one of the two identical parallel lines transfers all of its flow to the other one
        assertEquals(1d, lodfMatrix.get(1, 0), DELTA_LODF);
        assertEquals(1d, lodfMatrix.get(0, 1), DELTA_LODF);
        // and does not change the flow of the transformer in antenna
        assertEquals(0d, lodfMatrix.get(2, 0), DELTA_LODF);
        // an outaged branch loses all of its flow
        assertEquals(-1d, lodfMatrix.get(0, 0));
        // the outage of the transformer breaks the network connectivity: LODF factors are undefined
        assertTrue(Double.isNaN(lodfMatrix.get(0, 2)));
    }

    @Test
    void testZeroImpedanceBranch() {
        Network network = FourBusNetworkFactory.create();
        network.getLine("l13").setR(0).setX(0);
        DenseMatrix lodfMatrix = LodfComputer.computeLodfMatrix(network, List.of("l13", "l12"), List.of("l12", "l13"), new LoadFlowParameters());
        // thanks to the minimal impedance replacement, zero impedance branches can be monitored
        assertFalse(Double.isNaN(lodfMatrix.get(0, 0)));
        // but their outage transfers almost all of their flow like a connectivity break, and is reported as undefined
        assertTrue(Double.isNaN(lodfMatrix.get(0, 1)));
        assertTrue(Double.isNaN(lodfMatrix.get(1, 1)));
    }

    @Test
    void testUnknownBranch() {
        Network network = FourBusNetworkFactory.create();
        LoadFlowParameters parameters = new LoadFlowParameters();
        List<String> monitoredBranchIds = List.of("l12");
        List<String> outagedBranchIds = List.of("unknown");
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> LodfComputer.computeLodfMatrix(network, monitoredBranchIds, outagedBranchIds, parameters));
        assertEquals("Branch 'unknown' not found in the main connected component", e.getMessage());
    }
}
