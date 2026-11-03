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
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfElement;
import net.jafama.FastMath;

import java.util.List;
import java.util.Objects;

/**
 * One of the two smoothed complementarity equations of the split-slack Fischer&ndash;Burmeister form of a voltage
 * controller's limits. A controller holds its voltage while its control variable {@code x} is inside
 * {@code [xMin, xMax]}, and is held at a limit otherwise: the box complementarity {@code x in [xMin, xMax] perp
 * (V - Vset)}. With the voltage deviation split into two nonnegative slacks, {@code V + vPlus - vMinus = Vset}, it
 * reads
 * <pre>
 *     0 &le; (room to raise V) &perp; vPlus &ge; 0 ,      0 &le; (room to lower V) &perp; vMinus &ge; 0 ,
 * </pre>
 * each pair written as {@code phi_mu(a, b) = a + b - sqrt(a^2 + b^2 + 2 mu^2) = 0}, which holds for {@code a, b > 0}
 * with {@code a b = mu^2}; {@code b} is the slack times the voltage scale. For a generator {@code x} is its reactive
 * generation and the room to raise V is {@code qMax - Q}; for a tap changer {@code x} is the ratio {@code r1}, and the
 * room to raise V is {@code r1Max - r1} or {@code r1 - r1Min} depending on the side of the controlled bus.
 *
 * <p>Its partial derivatives weigh each controller by how far it is from the switch, on both sides of it: one far
 * from its limit has {@code dphi/da ~ 0} and holds its voltage, one far past it has {@code dphi/db ~ 0} and holds its
 * control variable, and one near the switch blends the two. When {@code mu} goes to 0 the rows become the exact
 * ones.</p>
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class LimitComplementarityEquationTerm<T extends LfElement> extends AbstractElementEquationTerm<T, AcVariableType, AcEquationType> {

    /**
     * Cap, per unit, of an unbounded reactive limit: with a headroom near Double.MAX_VALUE the kernel loses the
     * slack to cancellation. Far beyond any physical reactive need, it never binds for a real generator.
     */
    public static final double MAX_REACTIVE = 100.0;

    private final Variable<AcVariableType> controlVar;

    private final Variable<AcVariableType> slackVar;

    private final List<Variable<AcVariableType>> variables;

    private final double bound;

    /** da/dx: -1 when the headroom is {@code bound - x}, +1 when it is {@code x - bound}. */
    private final double dadx;

    private final double mu;

    private final double voltageScale;

    /**
     * The pair {@code dadx (x - bound) perp slack}.
     */
    public LimitComplementarityEquationTerm(T element, Variable<AcVariableType> controlVar, Variable<AcVariableType> slackVar,
                                            double bound, double dadx, LimitsSmoothing smoothing) {
        super(element);
        this.controlVar = Objects.requireNonNull(controlVar);
        this.slackVar = Objects.requireNonNull(slackVar);
        this.bound = bound;
        this.dadx = dadx;
        this.mu = smoothing.mu();
        this.voltageScale = smoothing.voltageScale();
        this.variables = List.of(controlVar, slackVar);
    }

    /**
     * A generator bus reactive limit: {@code (qMax - Q) perp vPlus} when {@code upper}, {@code (Q - qMin) perp vMinus}
     * otherwise, an unbounded limit being capped (see {@link #MAX_REACTIVE}).
     */
    public static LimitComplementarityEquationTerm<LfBus> reactive(LfBus bus, Variable<AcVariableType> q, Variable<AcVariableType> slack,
                                                                   boolean upper, double qMin, double qMax, LimitsSmoothing smoothing) {
        return upper ? new LimitComplementarityEquationTerm<>(bus, q, slack, Math.min(qMax, MAX_REACTIVE), -1, smoothing)
                     : new LimitComplementarityEquationTerm<>(bus, q, slack, Math.max(qMin, -MAX_REACTIVE), 1, smoothing);
    }

    /**
     * A tap changer ratio limit: the room to raise the controlled voltage against {@code vPlus} when {@code upper}, the
     * room to lower it against {@code vMinus} otherwise. {@code raisesVoltage} tells whether a higher {@code r1} raises
     * the controlled voltage.
     */
    public static LimitComplementarityEquationTerm<LfBranch> ratio(LfBranch branch, Variable<AcVariableType> r1, Variable<AcVariableType> slack,
                                                                   boolean upper, boolean raisesVoltage, double r1Min, double r1Max,
                                                                   LimitsSmoothing smoothing) {
        return upper == raisesVoltage ? new LimitComplementarityEquationTerm<>(branch, r1, slack, r1Max, -1, smoothing)
                                      : new LimitComplementarityEquationTerm<>(branch, r1, slack, r1Min, 1, smoothing);
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }

    private double a() {
        return dadx * (sv.get(controlVar.getRow()) - bound);
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
        if (variable.equals(controlVar)) {
            return dphida(a(), b(), mu) * dadx;
        } else if (variable.equals(slackVar)) {
            return dphidb(a(), b(), mu) * voltageScale;
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    public double calculateSensi(DenseMatrix dx, int column) {
        return der(controlVar) * dx.get(controlVar.getRow(), column) + der(slackVar) * dx.get(slackVar.getRow(), column);
    }

    @Override
    protected String getName() {
        return "ac_limit_complementarity";
    }
}
