/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.solver.AcSolverStatus;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ghost mode of the equation system index: a bus isolated and enabled again leaves the columns and the rows of the
 * index as they were, and the fixed structure solves the same system as without the ghost mode.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class GhostModeTest {

    private record Solution(double[] v, double[] angle, int columnCount, int rowCount) {
    }

    /** Solves the IEEE 14 case with bus 8 isolated (its only branch open), then with it enabled again. */
    private static Solution[] solveWithBus8Isolated(boolean ghostMode) {
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(false);
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters,
                OpenLoadFlowParameters.create(parameters), new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            assertEquals(AcSolverStatus.CONVERGED, new AcloadFlowEngine(context).run().getSolverStatus());
            EquationSystem<AcVariableType, AcEquationType> equationSystem = context.getEquationSystem();
            EquationSystemIndex<AcVariableType, AcEquationType> index = equationSystem.getIndex();
            int columnCount = index.getColumnCount();
            int rowCount = index.getRowCount();
            List<Variable<AcVariableType>> variables = List.copyOf(index.getSortedVariablesToFind());
            index.setGhostMode(ghostMode);
            assertEquals(ghostMode, index.isGhostMode());

            LfBus bus8 = lfNetwork.getBusById("VL8_0");
            LfBranch branch78 = lfNetwork.getBranchById("L7-8-1");
            branch78.setDisabled(true);
            bus8.setDisabled(true);
            Variable<AcVariableType> v8 = equationSystem.getVariable(bus8.getNum(), AcVariableType.BUS_V);
            Variable<AcVariableType> phi8 = equationSystem.getVariable(bus8.getNum(), AcVariableType.BUS_PHI);
            if (ghostMode) {
                // the columns and the rows are kept: the equations of bus 8 are ghosts, its variables orphans
                assertEquals(columnCount, index.getColumnCount());
                assertEquals(rowCount, index.getRowCount());
                assertEquals(variables, index.getSortedVariablesToFind());
                assertEquals(2, index.getGhostColumns().length);
                assertTrue(index.isOrphan(v8));
                assertTrue(index.isOrphan(phi8));
            } else {
                assertEquals(columnCount - 2, index.getColumnCount());
                assertEquals(0, index.getGhostColumns().length);
                assertFalse(index.isOrphan(v8));
            }
            AcLoadFlowResult isolated = new AcloadFlowEngine(context).run();
            assertEquals(AcSolverStatus.CONVERGED, isolated.getSolverStatus());
            Solution whileIsolated = solution(lfNetwork, bus8, index);

            branch78.setDisabled(false);
            bus8.setDisabled(false);
            // the ghosts are active again: nothing left of them, and the index as it was
            assertEquals(0, index.getGhostColumns().length);
            assertFalse(index.isOrphan(v8));
            assertFalse(index.isOrphan(phi8));
            assertEquals(columnCount, index.getColumnCount());
            assertEquals(variables, index.getSortedVariablesToFind());
            assertEquals(AcSolverStatus.CONVERGED, new AcloadFlowEngine(context).run().getSolverStatus());
            return new Solution[] {whileIsolated, solution(lfNetwork, null, index)};
        }
    }

    private static Solution solution(LfNetwork lfNetwork, LfBus excluded, EquationSystemIndex<?, ?> index) {
        List<LfBus> buses = lfNetwork.getBuses().stream().filter(b -> b != excluded).toList();
        return new Solution(buses.stream().mapToDouble(LfBus::getV).toArray(),
                buses.stream().mapToDouble(LfBus::getAngle).toArray(),
                index.getColumnCount(), index.getRowCount());
    }

    @Test
    void sameSolutionWithGhosts() {
        Solution[] ghosts = solveWithBus8Isolated(true);
        Solution[] noGhosts = solveWithBus8Isolated(false);
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(noGhosts[i].v(), ghosts[i].v(), 1e-9);
            assertArrayEquals(noGhosts[i].angle(), ghosts[i].angle(), 1e-9);
        }
        // with the ghosts the system keeps its size while the bus is isolated
        assertEquals(ghosts[1].columnCount(), ghosts[0].columnCount());
        assertEquals(noGhosts[1].columnCount() - 2, noGhosts[0].columnCount());
    }

    @Test
    void disablingRemovesTheGhosts() {
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(false);
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters,
                OpenLoadFlowParameters.create(parameters), new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            new AcloadFlowEngine(context).run();
            EquationSystemIndex<AcVariableType, AcEquationType> index = context.getEquationSystem().getIndex();
            int columnCount = index.getColumnCount();
            index.setGhostMode(true);
            lfNetwork.getBranchById("L7-8-1").setDisabled(true);
            lfNetwork.getBusById("VL8_0").setDisabled(true);
            assertEquals(columnCount, index.getColumnCount());
            // without the ghost mode the isolated bus has no column nor row anymore
            index.setGhostMode(false);
            assertEquals(columnCount - 2, index.getColumnCount());
            assertEquals(columnCount - 2, index.getRowCount());
            assertEquals(0, index.getGhostColumns().length);
            assertEquals(AcSolverStatus.CONVERGED, new AcloadFlowEngine(context).run().getSolverStatus());
        }
    }
}
