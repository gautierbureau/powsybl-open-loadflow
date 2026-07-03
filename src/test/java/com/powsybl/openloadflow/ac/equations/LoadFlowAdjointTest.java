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
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.GeneratorVoltageControl;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfShunt;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finite-difference validation of {@link LoadFlowAdjoint} on IEEE 14, extending the classic-solution
 * active-set VJP to the full TVC objective {@code f = f_V + f_I + f_J} and the relaxed levers:
 * <ul>
 *   <li><b>f_J</b>: {@code dP_branch/dtargetV} via the active-power {@code x̄} assembly + the targetV adjoint
 *       vs. a central finite difference of the branch active flow;</li>
 *   <li><b>f_I</b>: {@code dI_branch/dtargetV} via the current-magnitude {@code x̄} assembly + the targetV
 *       adjoint vs. a central finite difference of the branch current;</li>
 *   <li><b>RTC ratio</b>: {@code dV_out/dρ} via {@link LoadFlowAdjoint#ratioCotangent} vs. a central
 *       finite difference perturbing the branch ratio;</li>
 *   <li><b>Shunt B</b>: {@code dV_out/dB} via {@link LoadFlowAdjoint#shuntSusceptanceCotangent} vs. a
 *       central finite difference perturbing the shunt susceptance.</li>
 * </ul>
 *
 * @author Working note
 */
class LoadFlowAdjointTest {

    private static AcLoadFlowParameters acParameters() {
        AcLoadFlowParameters parameters = OpenLoadFlowParameters.createAcParameters(
                new LoadFlowParameters(), // classic PV/PQ with reactive-limit outer loop, fixed taps/shunts
                new OpenLoadFlowParameters(),
                new DenseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(),
                false,
                false);
        parameters.setVectorized(false);
        return parameters;
    }

    private static LfBus controller(LfNetwork lf) {
        return lf.getBuses().stream()
                .filter(b -> b.isGeneratorVoltageControlled()
                        && b.getGeneratorVoltageControl().map(GeneratorVoltageControl::isLocalControl).orElse(false)
                        && !b.isSlack())
                .findFirst().orElseThrow();
    }

    private static LfBus output(LfNetwork lf) {
        return lf.getBuses().stream()
                .filter(b -> !b.isGeneratorVoltageControlled())
                .findFirst().orElseThrow();
    }

    @Test
    void activePowerAndCurrentCotangentsMatchFiniteDifferenceOnIeee14() {
        Network network = IeeeCdfNetworkFactory.create14();
        AcLoadFlowParameters parameters = acParameters();
        LfNetwork lf = LfNetwork.load(network, new LfNetworkLoaderImpl(), parameters.getNetworkParameters()).get(0);

        LfBus controller = controller(lf);
        // a fully connected branch whose flow depends on the controller setpoint
        LfBranch branch = lf.getBranches().stream()
                .filter(b -> b.getBus1() != null && b.getBus2() != null)
                .findFirst().orElseThrow();

        try (AcLoadFlowContext context = new AcLoadFlowContext(lf, parameters)) {
            AcloadFlowEngine engine = new AcloadFlowEngine(context);
            GeneratorVoltageControl vc = controller.getGeneratorVoltageControl().orElseThrow();
            double targetV0 = vc.getTargetValue();
            double eps = 1e-4;

            // central FD of the branch active flow and current magnitude w.r.t. the controller setpoint
            vc.setTargetValue(targetV0 + eps);
            assertTrue(engine.run().isSuccess());
            double pPlus = branch.getP1().eval();
            double iPlus = branch.getI1().eval();
            vc.setTargetValue(targetV0 - eps);
            assertTrue(engine.run().isSuccess());
            double pMinus = branch.getP1().eval();
            double iMinus = branch.getI1().eval();
            double dpdTargetVFd = (pPlus - pMinus) / (2 * eps);
            double didTargetVFd = (iPlus - iMinus) / (2 * eps);

            // back to base; leaves the factorized Jacobian at the solution
            vc.setTargetValue(targetV0);
            assertTrue(engine.run().isSuccess());

            int n = context.getEquationSystem().getIndex().getColumnCount();

            // f_J: x̄ = (∂P1/∂x)ᵀ, then targetV adjoint gives dP1/dtargetV
            double[] xBarP = new double[n];
            LoadFlowAdjoint.accumulateActivePowerCotangent(branch, TwoSides.ONE, 1.0, xBarP);
            double dpdTargetVAdjoint = ClassicVoltageControlVjp.computeTargetVoltageCotangents(context, xBarP).get(controller);

            // f_I: x̄ = (∂I1/∂x)ᵀ, then targetV adjoint gives dI1/dtargetV
            double[] xBarI = new double[n];
            LoadFlowAdjoint.accumulateCurrentMagnitudeCotangent(branch, TwoSides.ONE, 1.0, xBarI);
            double didTargetVAdjoint = ClassicVoltageControlVjp.computeTargetVoltageCotangents(context, xBarI).get(controller);

            assertEquals(dpdTargetVFd, dpdTargetVAdjoint, 1e-3 * (Math.abs(dpdTargetVFd) + 1e-2));
            assertEquals(didTargetVFd, didTargetVAdjoint, 1e-3 * (Math.abs(didTargetVFd) + 1e-2));
        }
    }

    @Test
    void ratioCotangentMatchesFiniteDifferenceOnIeee14() {
        Network network = IeeeCdfNetworkFactory.create14();
        AcLoadFlowParameters parameters = acParameters();
        LfNetwork lf = LfNetwork.load(network, new LfNetworkLoaderImpl(), parameters.getNetworkParameters()).get(0);

        LfBus out = output(lf);
        LfBranch branch = lf.getBranches().stream()
                .filter(b -> b.getBus1() != null && b.getBus2() != null)
                .findFirst().orElseThrow();

        try (AcLoadFlowContext context = new AcLoadFlowContext(lf, parameters)) {
            AcloadFlowEngine engine = new AcloadFlowEngine(context);
            assertTrue(engine.run().isSuccess());

            // adjoint at the base solution: x̄ = e_{V_out} -> ρ̄ = dV_out/dρ
            int n = context.getEquationSystem().getIndex().getColumnCount();
            int vRow = context.getEquationSystem().getVariable(out.getNum(), AcVariableType.BUS_V).getRow();
            double[] xBar = new double[n];
            xBar[vRow] = 1.0;
            double[] lambda = LoadFlowAdjoint.solveAdjoint(context, xBar);
            double dvdRhoAdjoint = LoadFlowAdjoint.ratioCotangent(context, branch, lambda);

            // central FD perturbing the (fixed) transformer ratio
            double r0 = branch.getPiModel().getR1();
            double eps = 1e-5;
            branch.getPiModel().setR1(r0 + eps);
            assertTrue(engine.run().isSuccess());
            double vPlus = out.getV();
            branch.getPiModel().setR1(r0 - eps);
            assertTrue(engine.run().isSuccess());
            double vMinus = out.getV();
            branch.getPiModel().setR1(r0);
            double dvdRhoFd = (vPlus - vMinus) / (2 * eps);

            assertEquals(dvdRhoFd, dvdRhoAdjoint, 1e-3 * (Math.abs(dvdRhoFd) + 1e-2));
        }
    }

    @Test
    void shuntSusceptanceCotangentMatchesFiniteDifferenceOnIeee14() {
        Network network = IeeeCdfNetworkFactory.create14();
        AcLoadFlowParameters parameters = acParameters();
        LfNetwork lf = LfNetwork.load(network, new LfNetworkLoaderImpl(), parameters.getNetworkParameters()).get(0);

        // the bus carrying the IEEE 14 shunt (a capacitor on a PQ bus)
        LfBus shuntBus = lf.getBuses().stream()
                .filter(b -> b.getShunt().map(s -> Math.abs(s.getB()) > 0).orElse(false))
                .findFirst().orElseThrow();
        LfShunt shunt = shuntBus.getShunt().orElseThrow();
        LfBus out = lf.getBuses().stream()
                .filter(b -> !b.isGeneratorVoltageControlled() && b != shuntBus)
                .findFirst().orElseThrow();

        try (AcLoadFlowContext context = new AcLoadFlowContext(lf, parameters)) {
            AcloadFlowEngine engine = new AcloadFlowEngine(context);
            assertTrue(engine.run().isSuccess());

            // adjoint at the base solution: x̄ = e_{V_out} -> B̄ = dV_out/dB
            int n = context.getEquationSystem().getIndex().getColumnCount();
            int vRow = context.getEquationSystem().getVariable(out.getNum(), AcVariableType.BUS_V).getRow();
            double[] xBar = new double[n];
            xBar[vRow] = 1.0;
            double[] lambda = LoadFlowAdjoint.solveAdjoint(context, xBar);
            double dvdBAdjoint = LoadFlowAdjoint.shuntSusceptanceCotangent(context, shuntBus, lambda);

            // central FD perturbing the (fixed) shunt susceptance
            double b0 = shunt.getB();
            double eps = 1e-4;
            shunt.setB(b0 + eps);
            assertTrue(engine.run().isSuccess());
            double vPlus = out.getV();
            shunt.setB(b0 - eps);
            assertTrue(engine.run().isSuccess());
            double vMinus = out.getV();
            shunt.setB(b0);
            double dvdBFd = (vPlus - vMinus) / (2 * eps);

            assertEquals(dvdBFd, dvdBAdjoint, 1e-3 * (Math.abs(dvdBFd) + 1e-2));
        }
    }
}
