/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.equations.AbstractElementEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.network.LfBus;
import net.jafama.FastMath;

import java.util.List;
import java.util.Objects;

/**
 * One of the two smoothed complementarity equations of the split-slack Fischer&ndash;Burmeister form of a generator
 * bus reactive limits. The PV/PQ logic is the box complementarity {@code Q in [qMin, qMax] perp (V - Vset)}; with
 * the voltage deviation split into two nonnegative slacks, {@code V + vPlus - vMinus = Vset}, it reads
 * <pre>
 *     0 &le; (qMax - Q) &perp; vPlus  &ge; 0 ,      0 &le; (Q - qMin) &perp; vMinus &ge; 0 ,
 * </pre>
 * each pair written as {@code phi_mu(a, b) = a + b - sqrt(a^2 + b^2 + 2 mu^2) = 0}, which holds for
 * {@code a, b > 0} with {@code a b = mu^2}. {@code Q} is the bus reactive generation
 * ({@link AcVariableType#GENERATOR_Q}) and {@code b} the slack times the voltage scale.
 *
 * <p>Its partial derivatives weigh each generator by how far it is from the switch, on both sides of it: a bus far
 * from its limit has {@code dphi/da ~ 0} and holds its voltage (PV), a bus far past it has {@code dphi/db ~ 0} and
 * holds its reactive generation (PQ), and one near the switch blends the two. When {@code mu} goes to 0 the rows
 * become the PV or PQ rows of the classic equations.</p>
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class GeneratorReactiveSplitFbEquationTerm extends AbstractElementEquationTerm<LfBus, AcVariableType, AcEquationType> {

    /**
     * Cap, per unit, of an unbounded reactive limit: with a headroom near Double.MAX_VALUE the kernel loses the
     * slack to cancellation. Far beyond any physical reactive need, it never binds for a real generator.
     */
    public static final double MAX_REACTIVE = 100.0;

    private final Variable<AcVariableType> qVar;

    private final Variable<AcVariableType> slackVar;

    private final List<Variable<AcVariableType>> variables;

    private final double bound;

    /** da/dQ: -1 for the upper pair (a = qMax - Q), +1 for the lower one (a = Q - qMin). */
    private final double dadq;

    private final double mu;

    private final double voltageScale;

    /**
     * @param upper {@code true} for the pair {@code (qMax - Q) perp vPlus}, {@code false} for
     *              {@code (Q - qMin) perp vMinus}
     */
    public GeneratorReactiveSplitFbEquationTerm(LfBus controllerBus, Variable<AcVariableType> qVar,
                                                Variable<AcVariableType> slackVar, boolean upper,
                                                double qMin, double qMax, ReactiveLimitsSmoothing smoothing) {
        super(controllerBus);
        this.qVar = Objects.requireNonNull(qVar);
        this.slackVar = Objects.requireNonNull(slackVar);
        this.bound = upper ? Math.min(qMax, MAX_REACTIVE) : Math.max(qMin, -MAX_REACTIVE);
        this.dadq = upper ? -1.0 : 1.0;
        this.mu = smoothing.mu();
        this.voltageScale = smoothing.voltageScale();
        this.variables = List.of(qVar, slackVar);
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }

    private double a() {
        return dadq * (sv.get(qVar.getRow()) - bound);
    }

    private double b() {
        return voltageScale * sv.get(slackVar.getRow());
    }

    /** sqrt(a^2 + b^2 + 2 mu^2), scaled so that it does not overflow. */
    static double root(double a, double b, double mu) {
        double s = Math.max(Math.abs(a), Math.max(Math.abs(b), mu));
        double ar = a / s;
        double br = b / s;
        double mr = mu / s;
        return s * FastMath.sqrt(ar * ar + br * br + 2.0 * mr * mr);
    }

    public static double phi(double a, double b, double mu) {
        return a + b - root(a, b, mu);
    }

    public static double dphida(double a, double b, double mu) {
        return 1.0 - a / root(a, b, mu);
    }

    public static double dphidb(double a, double b, double mu) {
        return 1.0 - b / root(a, b, mu);
    }

    @Override
    public double eval() {
        return phi(a(), b(), mu);
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(qVar)) {
            return dphida(a(), b(), mu) * dadq;
        } else if (variable.equals(slackVar)) {
            return dphidb(a(), b(), mu) * voltageScale;
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    public double calculateSensi(DenseMatrix dx, int column) {
        return der(qVar) * dx.get(qVar.getRow(), column) + der(slackVar) * dx.get(slackVar.getRow(), column);
    }

    @Override
    protected String getName() {
        return "ac_generator_reactive_split_fb";
    }
}
