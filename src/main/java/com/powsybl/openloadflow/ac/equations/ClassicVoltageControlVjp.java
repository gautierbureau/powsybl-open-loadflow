/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.network.GeneratorVoltageControl;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.VoltageControl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reverse-mode (adjoint) gradient of a converged <em>classic</em> AC load flow with respect to the
 * generator voltage setpoints ({@code targetV}), using the active-set / frozen-binding-set approach.
 *
 * <p>This is the robust backward half of a {@code custom_vjp} over the power flow: the forward problem is
 * solved by the standard PV/PQ outer loop (which converges wherever a normal load flow does — including
 * the cases where the smooth in-solve reformulations fail), and the gradient is obtained by
 * differentiating the active equation set at the solution. Given an arbitrary state cotangent
 * {@code x̄ = (∂f/∂x)ᵀ} (indexed by variable row), the parameter cotangent for each voltage-regulating
 * bus is
 * <pre>
 *     θ̄_g = ∂f/∂targetV_g − (∂g/∂targetV_g)ᵀ λ ,      with   Jᵀ λ = x̄ .
 * </pre>
 * For a bus that is still PV at the solution, the active equation is {@code g_v = V − targetV} so
 * {@code ∂g_v/∂targetV = −1} and {@code θ̄_g = λ_v}. For a bus whose generator has saturated (switched to
 * PQ, so {@code BUS_TARGET_V} is inactive), the setpoint has no first-order effect within the frozen
 * binding set and {@code θ̄_g = 0} (it is omitted from the returned map). The objective is assumed not to
 * depend on {@code targetV} directly.
 *
 * <p>OLF stores {@code M = Jᵀ}; the adjoint is therefore a plain {@code solve} reusing the forward LU, and
 * a single solve yields the gradient for all regulating buses.</p>
 *
 * @author Working note
 */
public final class ClassicVoltageControlVjp {

    private ClassicVoltageControlVjp() {
    }

    public static Map<LfBus, Double> computeTargetVoltageCotangents(AcLoadFlowContext context, double[] stateCotangent) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(stateCotangent);

        // adjoint solve Jᵀ λ = x̄ (plain solve on the stored M = Jᵀ); solve overwrites its argument
        double[] lambda = stateCotangent.clone();
        context.getJacobianMatrix().solve(lambda); // lambda indexed by equation column

        Map<LfBus, Double> targetVoltageCotangents = new LinkedHashMap<>();
        var equationSystem = context.getEquationSystem();
        for (LfBus bus : context.getNetwork().getBuses()) {
            GeneratorVoltageControl voltageControl = bus.getGeneratorVoltageControl().orElse(null);
            if (voltageControl == null
                    || !bus.isGeneratorVoltageControlled()
                    || !voltageControl.isLocalControl()
                    || voltageControl.getMergeStatus() != VoltageControl.MergeStatus.MAIN) {
                continue;
            }
            // active-set: only buses still PV at the solution contribute (saturated/PQ -> frozen -> 0)
            var voltageTarget = equationSystem.getEquation(bus.getNum(), AcEquationType.BUS_TARGET_V).orElse(null);
            if (voltageTarget == null || !voltageTarget.isActive()) {
                continue;
            }
            targetVoltageCotangents.put(bus, lambda[voltageTarget.getColumn()]);
        }
        return targetVoltageCotangents;
    }
}
