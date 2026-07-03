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
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stepped continuation power flow to estimate the maximum loadability / voltage collapse point of a network.
 *
 * <p>The load is increased along a caller-defined {@link LoadIncreaseDirection} by an adaptive scalar factor
 * {@code lambda}. Each step reuses the standard AC load flow engine ({@link AcloadFlowEngine}) warm-started from
 * the previous converged solution, so all the usual outer loops (distributed slack, reactive limits, ...) keep
 * shaping the collapse point. A step that fails to converge is treated as an overshoot of the nose of the P-V
 * curve: the step is halved and retried until it drops below the configured minimum, at which point the last
 * converged point is reported as the voltage collapse point.</p>
 *
 * <p>This is a "predictor-free" continuation: it traces the upper (stable) branch of the P-V curve and the
 * loadability margin, but does not pass through the nose to trace the lower (unstable) branch (which would
 * require a predictor-corrector scheme with a bordered Jacobian).</p>
 *
 * @author Claude
 */
public class ContinuationPowerFlow {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContinuationPowerFlow.class);

    private final ContinuationPowerFlowParameters parameters;

    public ContinuationPowerFlow(ContinuationPowerFlowParameters parameters) {
        this.parameters = Objects.requireNonNull(parameters);
    }

    private record ParticipatingLoad(LfLoad load, double weight, double baseTargetP, double baseTargetQ) {
    }

    /**
     * Runs the continuation on an already prepared AC load flow context. The context network is left at the last
     * converged (nose) point when the method returns.
     */
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

        AcLoadFlowParameters acParameters = context.getParameters();
        VoltageInitializer originalInitializer = acParameters.getVoltageInitializer();
        try {
            return runContinuation(context, participants);
        } finally {
            // restore the load flow context in a clean state
            acParameters.setVoltageInitializer(originalInitializer);
        }
    }

    private ContinuationResult runContinuation(AcLoadFlowContext context, List<ParticipatingLoad> participants) {
        LfNetwork network = context.getNetwork();
        AcLoadFlowParameters acParameters = context.getParameters();

        List<ContinuationPoint> points = new ArrayList<>();

        // base case (lambda = 0)
        AcLoadFlowResult baseResult = new AcloadFlowEngine(context).run();
        if (!baseResult.isSuccess()) {
            LOGGER.warn("Continuation aborted: base case did not converge ({})", baseResult.getSolverStatus());
            return new ContinuationResult(ContinuationResult.Status.BASE_CASE_NOT_CONVERGED, List.of(), 0.0, null);
        }
        points.add(buildPoint(network, participants, 0.0));

        // subsequent steps are warm-started from the previous converged solution
        acParameters.setVoltageInitializer(new PreviousValueVoltageInitializer());

        double lambda = 0.0;
        double step = parameters.getInitialStepSize();
        int consecutiveSuccesses = 0;
        ContinuationResult.Status status = ContinuationResult.Status.MAX_STEPS_REACHED;

        for (int stepCount = 0; stepCount < parameters.getMaxSteps(); stepCount++) {
            if (step < parameters.getMinStepSize()) {
                status = ContinuationResult.Status.NOSE_POINT_REACHED;
                break;
            }
            double trialLambda = lambda + step;
            applyLoadIncrease(participants, trialLambda);
            AcLoadFlowResult result = new AcloadFlowEngine(context).run();
            if (result.isSuccess()) {
                lambda = trialLambda;
                ContinuationPoint point = buildPoint(network, participants, lambda);
                points.add(point);
                LOGGER.debug("Continuation step converged: lambda={}, minV={} pu at bus '{}'",
                        lambda, point.minVoltage(), point.minVoltageBusId());
                if (++consecutiveSuccesses >= parameters.getStepIncreaseThreshold()) {
                    step = Math.min(step * parameters.getStepIncreaseFactor(), parameters.getMaxStepSize());
                    consecutiveSuccesses = 0;
                }
            } else {
                // trial point is beyond the reachable domain (most likely past the nose): restore the last
                // converged load level and refine the step
                applyLoadIncrease(participants, lambda);
                step *= parameters.getStepDecreaseFactor();
                consecutiveSuccesses = 0;
                LOGGER.debug("Continuation step did not converge at lambda={}, reducing step to {}", trialLambda, step);
            }
        }

        String criticalBusId = points.get(points.size() - 1).minVoltageBusId();
        double maxLoadP = points.get(points.size() - 1).participatingLoadTargetPMw();
        LOGGER.info("Continuation finished ({}): maximum load factor={}, participating load={} MW, critical bus='{}'",
                status, lambda, maxLoadP, criticalBusId);
        return new ContinuationResult(status, points, lambda, criticalBusId);
    }

    private void applyLoadIncrease(List<ParticipatingLoad> participants, double lambda) {
        for (ParticipatingLoad p : participants) {
            double scaling = 1.0 + lambda * p.weight();
            p.load().setTargetP(p.baseTargetP() * scaling);
            if (parameters.isScaleReactivePowerWithActivePower()) {
                p.load().setTargetQ(p.baseTargetQ() * scaling);
            }
        }
    }

    private static ContinuationPoint buildPoint(LfNetwork network, List<ParticipatingLoad> participants, double lambda) {
        double minVoltage = Double.MAX_VALUE;
        String minVoltageBusId = null;
        for (LfBus bus : network.getBuses()) {
            if (bus.isDisabled() || bus.isFictitious()) {
                continue;
            }
            double v = bus.getV();
            if (!Double.isNaN(v) && v < minVoltage) {
                minVoltage = v;
                minVoltageBusId = bus.getId();
            }
        }
        double participatingLoadTargetPMw = participants.stream()
                .mapToDouble(p -> p.load().getTargetP())
                .sum() * PerUnit.SB;
        // the stepped continuation only traces the upper, stable branch
        return new ContinuationPoint(lambda, participatingLoadTargetPMw, minVoltage, minVoltageBusId, true);
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
                .filter(n -> n.getValidity() == LfNetwork.Validity.VALID)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No valid LfNetwork to run continuation on"));

        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            return run(context, direction);
        }
    }
}
