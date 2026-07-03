/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.solver.AcSolverUtil;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationVector;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.TargetVector;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Predictor-corrector continuation power flow (a "true" continuation power flow, CPF).
 *
 * <p>The network state {@code x} (bus voltage magnitudes and angles) and a scalar load factor {@code lambda} are
 * traced together along the P-V curve. Each step:</p>
 * <ol>
 *     <li><b>predictor</b>: computes the tangent to the curve by solving the bordered system
 *         {@code [F_x F_lambda; e_k^T 0] . t = e_{n+1}} and moves an arc length {@code sigma} along it;</li>
 *     <li><b>corrector</b>: a Newton method on the same bordered (augmented) system that projects the predicted
 *         point back onto {@code F(x, lambda) = 0} using a local parameterization (the tangent component of
 *         largest magnitude is frozen).</li>
 * </ol>
 *
 * <p>Because the augmented (n+1)x(n+1) system stays non-singular at the nose of the curve (where the plain
 * power-flow Jacobian {@code F_x} becomes singular), the continuation passes through the maximum loadability
 * point and traces the lower (unstable) branch. The tangent at the nose gives the voltage participation factors,
 * i.e. which buses drive the collapse.</p>
 *
 * <p>This is a smooth continuation: the load is increased along a {@link LoadIncreaseDirection} and the single
 * slack bus absorbs the active power imbalance. Discrete outer-loop controls (distributed slack, reactive
 * limits, ...) are intentionally not applied during the continuation, so the traced curve is smooth. For a
 * collapse point accounting for those controls, use the stepped {@link ContinuationPowerFlow}.</p>
 *
 * <p>The augmented linear system is solved with a dense factorization ({@code O(n^3)} per iteration), which is
 * fine for the small and medium networks this prototype targets; a production implementation would use a sparse
 * bordered solve reusing the existing {@link JacobianMatrix} factorization.</p>
 *
 * @author Claude
 */
public class PredictorCorrectorContinuationPowerFlow {

    private static final Logger LOGGER = LoggerFactory.getLogger(PredictorCorrectorContinuationPowerFlow.class);

    private static final int PARAM_LAMBDA = -1;

    private final PredictorCorrectorParameters parameters;

    public PredictorCorrectorContinuationPowerFlow(PredictorCorrectorParameters parameters) {
        this.parameters = Objects.requireNonNull(parameters);
    }

    private record ParticipatingLoad(LfLoad load, double weight, double baseTargetP, double baseTargetQ) {
    }

    /**
     * Mutable state shared by the predictor and corrector of a single run.
     */
    private final class Continuation {

        private final LfNetwork network;
        private final EquationSystem<AcVariableType, AcEquationType> equationSystem;
        private final JacobianMatrix<AcVariableType, AcEquationType> j;
        private final TargetVector<AcVariableType, AcEquationType> targetVector;
        private final EquationVector<AcVariableType, AcEquationType> equationVector;
        private final StateVector stateVector;
        private final List<ParticipatingLoad> participants;
        private final int n;
        private final double[] fLambda;

        private double lambda;

        private Continuation(AcLoadFlowContext context, List<ParticipatingLoad> participants) {
            this.network = context.getNetwork();
            this.equationSystem = context.getEquationSystem();
            this.j = context.getJacobianMatrix();
            this.targetVector = context.getTargetVector();
            this.equationVector = context.getEquationVector();
            this.stateVector = equationSystem.getStateVector();
            this.participants = participants;
            this.n = equationSystem.getIndex().getColumnCount();
            this.fLambda = computeFLambda();
        }

        /**
         * F_lambda = d F / d lambda, evaluated once (the target is linear in lambda). Since F = calc - target,
         * F_lambda = -(target(lambda=1) - target(lambda=0)).
         */
        private double[] computeFLambda() {
            applyLoadIncrease(0.0);
            double[] t0 = targetVector.getArray().clone();
            applyLoadIncrease(1.0);
            double[] t1 = targetVector.getArray().clone();
            applyLoadIncrease(0.0);
            double[] result = new double[n];
            for (int i = 0; i < n; i++) {
                result[i] = -(t1[i] - t0[i]);
            }
            return result;
        }

        private void applyLoadIncrease(double lambdaValue) {
            for (ParticipatingLoad p : participants) {
                double scaling = 1.0 + lambdaValue * p.weight();
                p.load().setTargetP(p.baseTargetP() * scaling);
                if (parameters.isScaleReactivePowerWithActivePower()) {
                    p.load().setTargetQ(p.baseTargetQ() * scaling);
                }
            }
        }

        /** Current power mismatch F = calc(x) - target(lambda), indexed by equation column. */
        private double[] computeMismatch() {
            double[] calc = equationVector.getArray();
            double[] target = targetVector.getArray();
            double[] f = new double[n];
            for (int i = 0; i < n; i++) {
                f[i] = calc[i] - target[i];
            }
            return f;
        }

        /**
         * Solves the augmented system {@code A_aug . z = rhs} where
         * {@code A_aug = [F_x F_lambda; c^T d]}, using a dense factorization. The parameterization row is
         * {@code c = e_k, d = 0} for a state-variable parameter, or {@code c = 0, d = 1} for the lambda parameter.
         */
        private double[] solveAugmented(int paramVariable, double[] rhs) {
            Matrix m = j.getMatrix(); // m = F_x^T (stored transposed), updated to the current state
            DenseMatrix aAug = new DenseMatrix(n + 1, n + 1);
            // A_aug[equation c][variable r] = F_x[c][r] = m[r][c]
            m.iterateNonZeroValue((row, col, value) -> aAug.set(col, row, value));
            // border column: d F / d lambda
            for (int c = 0; c < n; c++) {
                aAug.set(c, n, fLambda[c]);
            }
            // border row: parameterization equation
            if (paramVariable == PARAM_LAMBDA) {
                aAug.set(n, n, 1.0);
            } else {
                aAug.set(n, paramVariable, 1.0);
            }
            double[] z = rhs.clone();
            try (LUDecomposition lu = aAug.decomposeLU()) {
                lu.solve(z);
            }
            return z;
        }

        private void addToState(double[] deltaX) {
            // stateVector.minus subtracts, so negate to add
            double[] neg = new double[n];
            for (int r = 0; r < n; r++) {
                neg[r] = -deltaX[r];
            }
            stateVector.minus(neg);
        }

        /**
         * Newton corrector projecting the current (x, lambda) guess back onto the curve, with the given local
         * parameterization frozen to eta.
         */
        private boolean correct(int paramVariable, double eta) {
            for (int iteration = 0; iteration <= parameters.getMaxCorrectorIterations(); iteration++) {
                double[] f = computeMismatch();
                double fNorm = infinityNorm(f);
                double paramResidual = (paramVariable == PARAM_LAMBDA)
                        ? lambda - eta
                        : stateVector.get(paramVariable) - eta;
                if (fNorm < parameters.getConvergenceEpsilon() && Math.abs(paramResidual) < parameters.getConvergenceEpsilon()) {
                    return true;
                }
                if (iteration == parameters.getMaxCorrectorIterations()) {
                    break;
                }
                double[] rhs = new double[n + 1];
                for (int i = 0; i < n; i++) {
                    rhs[i] = -f[i];
                }
                rhs[n] = -paramResidual;
                double[] z = solveAugmented(paramVariable, rhs);
                double[] deltaX = new double[n];
                System.arraycopy(z, 0, deltaX, 0, n);
                addToState(deltaX);
                lambda += z[n];
                applyLoadIncrease(lambda);
                if (!Double.isFinite(lambda) || !Double.isFinite(infinityNorm(stateVector.get()))) {
                    return false;
                }
            }
            return false;
        }

        /** Unit tangent [dx; dlambda] to the curve at the current point, oriented to continue previous direction. */
        private double[] computeTangent(int paramVariable, double[] previousTangent) {
            double[] rhs = new double[n + 1];
            rhs[n] = 1.0;
            double[] t = solveAugmented(paramVariable, rhs);
            double norm = euclideanNorm(t);
            for (int i = 0; i <= n; i++) {
                t[i] /= norm;
            }
            if (previousTangent == null) {
                if (t[n] < 0) { // first step: move towards increasing load
                    negate(t);
                }
            } else if (dot(t, previousTangent) < 0) {
                negate(t);
            }
            return t;
        }

        private ContinuationPoint buildPoint(boolean stable) {
            double minVoltage = Double.MAX_VALUE;
            String minVoltageBusId = null;
            for (Variable<AcVariableType> variable : equationSystem.getIndex().getSortedVariablesToFind()) {
                if (variable.getType() == AcVariableType.BUS_V) {
                    LfBus bus = network.getBus(variable.getElementNum());
                    if (bus.isFictitious() || bus.isDisabled()) {
                        continue;
                    }
                    double v = stateVector.get(variable.getRow());
                    if (v < minVoltage) {
                        minVoltage = v;
                        minVoltageBusId = bus.getId();
                    }
                }
            }
            double participatingLoadTargetPMw = participants.stream()
                    .mapToDouble(p -> p.load().getTargetP())
                    .sum() * PerUnit.SB;
            return new ContinuationPoint(lambda, participatingLoadTargetPMw, minVoltage, minVoltageBusId, stable);
        }

        /**
         * Normalized voltage participation factors at the current point, from the tangent: for each bus, the
         * absolute value of its voltage magnitude component in the tangent, normalized so the maximum is 1.
         */
        private Map<String, Double> tangentVoltageParticipation(double[] tangent) {
            Map<String, Double> raw = new LinkedHashMap<>();
            double max = 0.0;
            for (Variable<AcVariableType> variable : equationSystem.getIndex().getSortedVariablesToFind()) {
                if (variable.getType() == AcVariableType.BUS_V) {
                    LfBus bus = network.getBus(variable.getElementNum());
                    if (bus.isFictitious() || bus.isDisabled()) {
                        continue;
                    }
                    double value = Math.abs(tangent[variable.getRow()]);
                    raw.put(bus.getId(), value);
                    max = Math.max(max, value);
                }
            }
            Map<String, Double> normalized = new LinkedHashMap<>();
            if (max > 0) {
                for (var e : raw.entrySet()) {
                    normalized.put(e.getKey(), e.getValue() / max);
                }
            }
            return normalized;
        }
    }

    public ContinuationResult run(AcLoadFlowContext context, LoadIncreaseDirection direction) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(direction);
        LfNetwork network = context.getNetwork();

        List<ParticipatingLoad> participants = new ArrayList<>();
        for (LfBus bus : network.getBuses()) {
            if (bus.isDisabled()) {
                continue;
            }
            for (LfLoad load : bus.getLoads()) {
                double weight = direction.getWeight(load);
                if (weight != 0.0) {
                    participants.add(new ParticipatingLoad(load, weight, load.getTargetP(), load.getTargetQ()));
                }
            }
        }
        if (participants.isEmpty()) {
            LOGGER.warn("No load participates in the requested load increase direction");
            return new ContinuationResult(ContinuationResult.Status.NO_PARTICIPATING_LOAD, List.of(), 0.0, null);
        }

        // initialize the state vector at a flat start
        VoltageInitializer voltageInitializer = new UniformValueVoltageInitializer();
        voltageInitializer.prepare(network, ReportNode.NO_OP);
        AcSolverUtil.initStateVector(network, context.getEquationSystem(), voltageInitializer);

        Continuation c = new Continuation(context, participants);

        // base case (lambda = 0), solved with lambda frozen
        c.applyLoadIncrease(0.0);
        c.lambda = 0.0;
        if (!c.correct(PARAM_LAMBDA, 0.0)) {
            LOGGER.warn("Continuation aborted: base case did not converge");
            return new ContinuationResult(ContinuationResult.Status.BASE_CASE_NOT_CONVERGED, List.of(), 0.0, null);
        }

        List<ContinuationPoint> points = new ArrayList<>();
        points.add(c.buildPoint(true));

        double[] previousTangent = null;
        int paramVariable = PARAM_LAMBDA;
        double step = parameters.getInitialStepSize();
        double previousLambda = 0.0;
        boolean turned = false;
        int consecutiveSuccesses = 0;

        double maxLambda = 0.0;
        ContinuationPoint nosePoint = points.get(0);
        double[] noseState = c.stateVector.get().clone();
        double noseLambda = 0.0;
        int noseParamVariable = PARAM_LAMBDA;

        ContinuationResult.Status status = ContinuationResult.Status.MAX_STEPS_REACHED;

        for (int stepCount = 0; stepCount < parameters.getMaxSteps(); stepCount++) {
            // predictor: tangent at the current converged point
            double[] tangent = c.computeTangent(paramVariable, previousTangent);

            // local parameterization: freeze the tangent component of largest magnitude
            int argMax = argMaxAbs(tangent);
            int stepParamVariable = argMax == c.n ? PARAM_LAMBDA : argMax;

            // save the current converged point to be able to reject the step
            double[] savedState = c.stateVector.get().clone();
            double savedLambda = c.lambda;

            // move along the tangent
            double[] predictorDx = new double[c.n];
            for (int r = 0; r < c.n; r++) {
                predictorDx[r] = step * tangent[r];
            }
            c.addToState(predictorDx);
            c.lambda = savedLambda + step * tangent[c.n];
            c.applyLoadIncrease(c.lambda);

            double eta = stepParamVariable == PARAM_LAMBDA ? c.lambda : c.stateVector.get(stepParamVariable);

            if (c.correct(stepParamVariable, eta)) {
                boolean nowTurned = c.lambda < previousLambda - 1e-9;
                if (nowTurned) {
                    turned = true;
                }
                boolean stable = !turned;
                ContinuationPoint point = c.buildPoint(stable);
                points.add(point);

                if (stable && c.lambda >= maxLambda) {
                    maxLambda = c.lambda;
                    nosePoint = point;
                    noseState = c.stateVector.get().clone();
                    noseLambda = c.lambda;
                    noseParamVariable = stepParamVariable;
                }

                LOGGER.debug("Continuation point: lambda={}, minV={} pu at '{}', {}",
                        point.loadFactor(), point.minVoltage(), point.minVoltageBusId(), stable ? "stable" : "unstable");

                previousLambda = c.lambda;
                previousTangent = tangent;
                paramVariable = stepParamVariable;

                if (++consecutiveSuccesses >= parameters.getStepIncreaseThreshold()) {
                    step = Math.min(step * parameters.getStepIncreaseFactor(), parameters.getMaxStepSize());
                    consecutiveSuccesses = 0;
                }

                if (turned && (!parameters.isTraceLowerBranch() || c.lambda < parameters.getMinLoadFactor())) {
                    status = ContinuationResult.Status.NOSE_POINT_REACHED;
                    break;
                }
            } else {
                // reject the step and refine
                c.stateVector.set(savedState);
                c.lambda = savedLambda;
                c.applyLoadIncrease(c.lambda);
                step *= parameters.getStepDecreaseFactor();
                consecutiveSuccesses = 0;
                if (step < parameters.getMinStepSize()) {
                    status = ContinuationResult.Status.NOSE_POINT_REACHED;
                    break;
                }
            }
        }

        // tangent-based voltage participation factors at the nose
        c.stateVector.set(noseState);
        c.lambda = noseLambda;
        c.applyLoadIncrease(noseLambda);
        double[] noseTangent = c.computeTangent(noseParamVariable, null);
        Map<String, Double> participation = c.tangentVoltageParticipation(noseTangent);
        String criticalBusId = participation.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(nosePoint.minVoltageBusId());

        LOGGER.info("Continuation finished ({}): nose at lambda={}, critical bus='{}', {} points",
                status, maxLambda, criticalBusId, points.size());

        return new ContinuationResult(status, points, maxLambda, nosePoint, criticalBusId, participation);
    }

    /**
     * Convenience entry point building the {@link LfNetwork} and AC load flow context from an IIDM network before
     * running the continuation on its main (first valid) connected component. The IIDM network state is not
     * updated.
     */
    public ContinuationResult run(Network network, LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt,
                                  MatrixFactory matrixFactory, LoadIncreaseDirection direction) {
        Objects.requireNonNull(network);
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, lfParameters, lfParametersExt,
                matrixFactory, new EvenShiloachGraphDecrementalConnectivityFactory<>());

        List<LfNetwork> lfNetworks = Networks.load(network, acParameters.getNetworkParameters(), ReportNode.NO_OP);
        LfNetwork lfNetwork = lfNetworks.stream()
                .filter(nw -> nw.getValidity() == LfNetwork.Validity.VALID)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No valid LfNetwork to run continuation on"));

        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            return run(context, direction);
        }
    }

    private static double infinityNorm(double[] v) {
        double norm = 0.0;
        for (double x : v) {
            norm = Math.max(norm, Math.abs(x));
        }
        return norm;
    }

    private static double euclideanNorm(double[] v) {
        double sum = 0.0;
        for (double x : v) {
            sum += x * x;
        }
        return Math.sqrt(sum);
    }

    private static void negate(double[] v) {
        for (int i = 0; i < v.length; i++) {
            v[i] = -v[i];
        }
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static int argMaxAbs(double[] v) {
        int argMax = 0;
        double max = -1.0;
        for (int i = 0; i < v.length; i++) {
            double abs = Math.abs(v[i]);
            if (abs > max) {
                max = abs;
                argMax = i;
            }
        }
        return argMax;
    }
}
