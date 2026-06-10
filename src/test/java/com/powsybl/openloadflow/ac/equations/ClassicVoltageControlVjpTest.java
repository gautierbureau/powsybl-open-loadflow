/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.GeneratorVoltageControl;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the robust classic-solution active-set VJP ({@link ClassicVoltageControlVjp}) on IEEE 14 —
 * the case where the smooth in-solve formulations fail to converge. The classic forward solve converges,
 * and the adjoint gradient of a downstream output (a load-bus voltage) w.r.t. a generator setpoint matches
 * a central finite difference.
 *
 * @author Working note
 */
class ClassicVoltageControlVjpTest {

    private static AcLoadFlowParameters acParameters() {
        AcLoadFlowParameters parameters = OpenLoadFlowParameters.createAcParameters(
                new LoadFlowParameters(), // classic PV/PQ with reactive-limit outer loop
                new OpenLoadFlowParameters(),
                new DenseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(),
                false,
                false);
        parameters.setVectorized(false);
        return parameters;
    }

    @Test
    void adjointTargetVoltageGradientMatchesFiniteDifferenceOnIeee14() {
        Network network = IeeeCdfNetworkFactory.create14();
        AcLoadFlowParameters parameters = acParameters();
        LfNetwork lf = LfNetwork.load(network, new LfNetworkLoaderImpl(), parameters.getNetworkParameters()).get(0);

        // a voltage-controlling bus (the setpoint we differentiate) and a load (PQ) output bus
        LfBus controller = lf.getBuses().stream()
                .filter(b -> b.isGeneratorVoltageControlled()
                        && b.getGeneratorVoltageControl().map(GeneratorVoltageControl::isLocalControl).orElse(false)
                        && !b.isSlack())
                .findFirst().orElseThrow();
        LfBus output = lf.getBuses().stream()
                .filter(b -> !b.isGeneratorVoltageControlled())
                .findFirst().orElseThrow();

        try (AcLoadFlowContext context = new AcLoadFlowContext(lf, parameters)) {
            AcloadFlowEngine engine = new AcloadFlowEngine(context);
            GeneratorVoltageControl vc = controller.getGeneratorVoltageControl().orElseThrow();
            double targetV0 = vc.getTargetValue();
            double eps = 1e-4;

            // central finite difference of the output-bus voltage w.r.t. the controller setpoint (cold solves)
            vc.setTargetValue(targetV0 + eps);
            assertTrue(engine.run().isSuccess());
            double vPlus = output.getV();
            vc.setTargetValue(targetV0 - eps);
            assertTrue(engine.run().isSuccess());
            double vMinus = output.getV();
            double dVdTargetVFd = (vPlus - vMinus) / (2 * eps);

            // back to base; leaves the factorized Jacobian at the solution
            vc.setTargetValue(targetV0);
            assertTrue(engine.run().isSuccess());

            // reverse mode: scalar output = output-bus voltage -> x̄ = e_{V_output}
            var equationSystem = context.getEquationSystem();
            int n = equationSystem.getIndex().getColumnCount();
            int vRow = equationSystem.getVariable(output.getNum(), AcVariableType.BUS_V).getRow();
            double[] xBar = new double[n];
            xBar[vRow] = 1.0;

            double dVdTargetVAdjoint = ClassicVoltageControlVjp.computeTargetVoltageCotangents(context, xBar).get(controller);

            // non-trivial cross-sensitivity (network coupling), matches FD
            assertEquals(dVdTargetVFd, dVdTargetVAdjoint, 1e-3 * (Math.abs(dVdTargetVFd) + 1e-2));
        }
    }
}
