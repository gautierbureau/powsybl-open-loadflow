/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.util.Evaluable;

import net.jafama.FastMath;

import java.util.Objects;

/**
 * Reverse-mode (adjoint) building blocks for a {@code custom_vjp} over a converged <em>classic</em> AC
 * load flow, covering the full tertiary-voltage-control objective {@code f = f_V + f_I + f_J} and the
 * relaxed discrete levers (transformer ratio, shunt susceptance), as a complement to
 * {@link ClassicVoltageControlVjp} (which handles the generator voltage setpoints {@code targetV}).
 *
 * <p>The adjoint follows the implicit-function theorem on the active equation set at the solution: for an
 * objective {@code f(x*(θ), θ)} with power-flow residual {@code g(x, θ) = 0},
 * <pre>
 *     θ̄ = ∂f/∂θ − (∂g/∂θ)ᵀ λ ,      with   Jᵀ λ = x̄ ,   x̄ = (∂f/∂x)ᵀ .
 * </pre>
 * OLF stores {@code M = Jᵀ}, so the adjoint is a plain {@code solve} reusing the forward LU
 * ({@link #solveAdjoint}); a single solve serves all levers.
 *
 * <p>This class supplies the two halves that {@link ClassicVoltageControlVjp} does not:
 * <ul>
 *   <li><b>{@code ∂f/∂x} assembly</b> for the current ({@code f_I}) and Joule/active-power ({@code f_J})
 *       objective terms: {@link #accumulateCurrentMagnitudeCotangent} and
 *       {@link #accumulateActivePowerCotangent} scatter {@code cotangent · ∂(I or P)/∂x} of one branch
 *       end into {@code x̄}, reusing the branch's own registered flow/current equation terms (already wired
 *       to the converged state vector). {@code f_V} needs no term: its {@code x̄} is the dead-band hinge on
 *       the monitored {@code BUS_V} rows, filled directly by the caller.</li>
 *   <li><b>parameter cotangents {@code −(∂g/∂θ)ᵀλ}</b> for the relaxed levers, given {@code λ}:
 *       {@link #ratioCotangent} (transformer ratio {@code ρ}, via the analytic {@code ∂P/∂ρ},
 *       {@code ∂Q/∂ρ} of both branch ends assembled into the incident-bus {@code P}/{@code Q} balance rows)
 *       and {@link #shuntSusceptanceCotangent} (shunt {@code B}, the single reactive-balance entry
 *       {@code ∂g_Q/∂B = −V²}). For these levers {@code ∂f/∂θ = 0} unless the lever also appears in a
 *       monitored flow of {@code f_I}/{@code f_J}; that direct term is left to the caller (it is the same
 *       {@code dr1} partials evaluated against the output cotangents).</li>
 * </ul>
 *
 * @author Working note
 */
public final class LoadFlowAdjoint {

    private LoadFlowAdjoint() {
    }

    /**
     * Adjoint solve {@code Jᵀ λ = x̄} reusing the forward factorization (plain {@code solve} on the stored
     * {@code M = Jᵀ}). Does not mutate the argument; returns a fresh {@code λ} indexed by equation column.
     */
    public static double[] solveAdjoint(AcLoadFlowContext context, double[] stateCotangent) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(stateCotangent);
        double[] lambda = stateCotangent.clone();
        context.getJacobianMatrix().solve(lambda);
        return lambda;
    }

    // --------- ∂f/∂x assembly: f_J (branch active power) and f_I (branch current magnitude) ----------

    /**
     * Accumulate {@code pBar · ∂P_side/∂x} of one branch end into the state cotangent {@code x̄} (the
     * {@code f_J} / Joule contribution; Joule loss of a branch is {@code P1 + P2}, so call once per side).
     */
    public static void accumulateActivePowerCotangent(LfBranch branch, TwoSides side, double pBar, double[] xBar) {
        Objects.requireNonNull(branch);
        Objects.requireNonNull(xBar);
        if (pBar == 0.0) {
            return;
        }
        Evaluable flow = side == TwoSides.ONE ? branch.getP1() : branch.getP2();
        scatter(flow, pBar, xBar, false);
    }

    /**
     * Accumulate {@code iBar · ∂I_side/∂x} of one branch end into the state cotangent {@code x̄} (the
     * {@code f_I} / current contribution). The ratio variable is skipped: OLF does not implement the
     * current-magnitude derivative w.r.t. {@code r1} (it is anyway not a free variable in the
     * fixed-parameter lever mode used by the wrapper).
     */
    public static void accumulateCurrentMagnitudeCotangent(LfBranch branch, TwoSides side, double iBar, double[] xBar) {
        Objects.requireNonNull(branch);
        Objects.requireNonNull(xBar);
        if (iBar == 0.0) {
            return;
        }
        Evaluable current = side == TwoSides.ONE ? branch.getI1() : branch.getI2();
        scatter(current, iBar, xBar, true);
    }

    @SuppressWarnings("unchecked")
    private static void scatter(Evaluable evaluable, double cotangent, double[] xBar, boolean skipRatio) {
        if (!(evaluable instanceof EquationTerm)) {
            return; // open/disconnected end or non-differentiable evaluable: no state dependence here
        }
        EquationTerm<AcVariableType, AcEquationType> term = (EquationTerm<AcVariableType, AcEquationType>) evaluable;
        for (Variable<AcVariableType> variable : term.getVariables()) {
            if (skipRatio && variable.getType() == AcVariableType.BRANCH_RHO1) {
                continue;
            }
            int row = variable.getRow();
            if (row >= 0) {
                xBar[row] += cotangent * term.der(variable);
            }
        }
    }

    // --------------- parameter cotangents −(∂g/∂θ)ᵀλ for the relaxed discrete levers ----------------

    /**
     * Transformer ratio cotangent {@code ρ̄ = −(∂g/∂ρ)ᵀλ} for one (closed) controllable branch, run with
     * the ratio as a fixed continuous parameter (automatic tap outer loop disabled). The ratio enters the
     * four flow terms {@code P1, Q1, P2, Q2}, which feed the active/reactive balance of buses 1 and 2, so
     * <pre>
     *     ρ̄ = −( λ_{P1}·∂P1/∂ρ + λ_{Q1}·∂Q1/∂ρ + λ_{P2}·∂P2/∂ρ + λ_{Q2}·∂Q2/∂ρ ) ,
     * </pre>
     * with the {@code ∂·/∂ρ} taken from the same analytic {@code dr1} partials OLF uses for ratio
     * sensitivities, evaluated at the converged state. Returns {@code 0} for an open branch.
     */
    public static double ratioCotangent(AcLoadFlowContext context, LfBranch branch, double[] lambda) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(branch);
        Objects.requireNonNull(lambda);
        LfBus bus1 = branch.getBus1();
        LfBus bus2 = branch.getBus2();
        if (bus1 == null || bus2 == null
                || !(branch.getClosedP1() instanceof ClosedBranchSide1ActiveFlowEquationTerm t)) {
            return 0.0;
        }
        double y = t.y();
        double ksi = t.ksi();
        double g1 = t.g1();
        double b1 = t.b1();
        double v1 = t.v1();
        double v2 = t.v2();
        double ph1 = t.ph1();
        double ph2 = t.ph2();
        double a1 = t.a1();
        double r1 = t.r1();
        double theta1 = AbstractClosedBranchAcFlowEquationTerm.theta1(ksi, ph1, a1, ph2);
        double theta2 = AbstractClosedBranchAcFlowEquationTerm.theta2(ksi, ph1, a1, ph2);

        double dp1 = ClosedBranchSide1ActiveFlowEquationTerm.dp1dr1(y, FastMath.sin(ksi), g1, v1, r1, v2, FastMath.sin(theta1));
        double dq1 = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dr1(y, FastMath.cos(ksi), b1, v1, r1, v2, FastMath.cos(theta1));
        double dp2 = ClosedBranchSide2ActiveFlowEquationTerm.dp2dr1(y, v1, v2, FastMath.sin(theta2));
        double dq2 = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dr1(y, v1, v2, FastMath.cos(theta2));

        var equationSystem = context.getEquationSystem();
        double dgT = lambdaAt(equationSystem, lambda, bus1, AcEquationType.BUS_TARGET_P) * dp1
                + lambdaAt(equationSystem, lambda, bus1, AcEquationType.BUS_TARGET_Q) * dq1
                + lambdaAt(equationSystem, lambda, bus2, AcEquationType.BUS_TARGET_P) * dp2
                + lambdaAt(equationSystem, lambda, bus2, AcEquationType.BUS_TARGET_Q) * dq2;
        return -dgT;
    }

    /**
     * Shunt susceptance cotangent {@code B̄ = −(∂g/∂B)ᵀλ} for the shunt at {@code bus}, run with {@code B}
     * as a fixed continuous parameter (automatic shunt outer loop disabled). The shunt injects
     * {@code Q^sh = −B·V²} into the bus reactive balance only, so {@code ∂g_Q/∂B = −V²} and
     * {@code B̄ = λ_{Q,bus}·V²}. Returns {@code 0} if the bus reactive balance is not an active equation.
     */
    public static double shuntSusceptanceCotangent(AcLoadFlowContext context, LfBus bus, double[] lambda) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(bus);
        Objects.requireNonNull(lambda);
        double lambdaQ = lambdaAt(context.getEquationSystem(), lambda, bus, AcEquationType.BUS_TARGET_Q);
        double v = bus.getV();
        return lambdaQ * v * v; // = −λ_Q·(∂Q^sh/∂B) = −λ_Q·(−V²)
    }

    private static double lambdaAt(EquationSystem<AcVariableType, AcEquationType> equationSystem, double[] lambda,
                                   LfBus bus, AcEquationType type) {
        Equation<AcVariableType, AcEquationType> equation = equationSystem.getEquation(bus.getNum(), type).orElse(null);
        if (equation == null || !equation.isActive()) {
            return 0.0; // inactive balance row (slack P, PV bus Q, ...) is not part of the active set
        }
        int column = equation.getColumn();
        return column >= 0 ? lambda[column] : 0.0;
    }
}
