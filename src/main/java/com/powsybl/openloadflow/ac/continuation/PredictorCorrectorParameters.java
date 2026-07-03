/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

/**
 * Parameters of the {@link PredictorCorrectorContinuationPowerFlow}.
 *
 * <p>The step size is an arc length in the combined (state, lambda) tangent space: the predictor moves this
 * distance along the unit tangent to the P-V curve, and the corrector projects back onto the curve using a
 * local parameterization. A step whose corrector does not converge is reduced and retried; when it falls below
 * {@link #getMinStepSize()} the continuation stops.</p>
 *
 * @author Claude
 */
public class PredictorCorrectorParameters {

    public static final double DEFAULT_INITIAL_STEP_SIZE = 0.1;
    public static final double DEFAULT_MIN_STEP_SIZE = 1e-3;
    public static final double DEFAULT_MAX_STEP_SIZE = 0.5;
    public static final double DEFAULT_STEP_INCREASE_FACTOR = 1.5;
    public static final double DEFAULT_STEP_DECREASE_FACTOR = 0.5;
    public static final int DEFAULT_STEP_INCREASE_THRESHOLD = 3;
    public static final int DEFAULT_MAX_STEPS = 400;
    public static final double DEFAULT_CONVERGENCE_EPSILON = 1e-4;
    public static final int DEFAULT_MAX_CORRECTOR_ITERATIONS = 20;
    public static final boolean DEFAULT_TRACE_LOWER_BRANCH = true;
    public static final double DEFAULT_MIN_LOAD_FACTOR = 0.0;
    public static final boolean DEFAULT_SCALE_REACTIVE_POWER_WITH_ACTIVE_POWER = true;
    public static final boolean DEFAULT_RECORD_BUS_VOLTAGES = true;
    public static final boolean DEFAULT_ENFORCE_REACTIVE_LIMITS = false;
    public static final double DEFAULT_REACTIVE_POWER_LIMIT_TOLERANCE = 1e-4;

    private double initialStepSize = DEFAULT_INITIAL_STEP_SIZE;
    private double minStepSize = DEFAULT_MIN_STEP_SIZE;
    private double maxStepSize = DEFAULT_MAX_STEP_SIZE;
    private double stepIncreaseFactor = DEFAULT_STEP_INCREASE_FACTOR;
    private double stepDecreaseFactor = DEFAULT_STEP_DECREASE_FACTOR;
    private int stepIncreaseThreshold = DEFAULT_STEP_INCREASE_THRESHOLD;
    private int maxSteps = DEFAULT_MAX_STEPS;
    private double convergenceEpsilon = DEFAULT_CONVERGENCE_EPSILON;
    private int maxCorrectorIterations = DEFAULT_MAX_CORRECTOR_ITERATIONS;
    private boolean traceLowerBranch = DEFAULT_TRACE_LOWER_BRANCH;
    private double minLoadFactor = DEFAULT_MIN_LOAD_FACTOR;
    private boolean scaleReactivePowerWithActivePower = DEFAULT_SCALE_REACTIVE_POWER_WITH_ACTIVE_POWER;
    private boolean recordBusVoltages = DEFAULT_RECORD_BUS_VOLTAGES;
    private boolean enforceReactiveLimits = DEFAULT_ENFORCE_REACTIVE_LIMITS;
    private double reactivePowerLimitTolerance = DEFAULT_REACTIVE_POWER_LIMIT_TOLERANCE;

    /** Arc length of the first predictor step, in the combined (state, lambda) tangent space. */
    public double getInitialStepSize() {
        return initialStepSize;
    }

    public PredictorCorrectorParameters setInitialStepSize(double initialStepSize) {
        this.initialStepSize = initialStepSize;
        return this;
    }

    /** Continuation stops when the adaptive step becomes smaller than this value. */
    public double getMinStepSize() {
        return minStepSize;
    }

    public PredictorCorrectorParameters setMinStepSize(double minStepSize) {
        this.minStepSize = minStepSize;
        return this;
    }

    public double getMaxStepSize() {
        return maxStepSize;
    }

    public PredictorCorrectorParameters setMaxStepSize(double maxStepSize) {
        this.maxStepSize = maxStepSize;
        return this;
    }

    public double getStepIncreaseFactor() {
        return stepIncreaseFactor;
    }

    public PredictorCorrectorParameters setStepIncreaseFactor(double stepIncreaseFactor) {
        this.stepIncreaseFactor = stepIncreaseFactor;
        return this;
    }

    public double getStepDecreaseFactor() {
        return stepDecreaseFactor;
    }

    public PredictorCorrectorParameters setStepDecreaseFactor(double stepDecreaseFactor) {
        this.stepDecreaseFactor = stepDecreaseFactor;
        return this;
    }

    public int getStepIncreaseThreshold() {
        return stepIncreaseThreshold;
    }

    public PredictorCorrectorParameters setStepIncreaseThreshold(int stepIncreaseThreshold) {
        this.stepIncreaseThreshold = stepIncreaseThreshold;
        return this;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public PredictorCorrectorParameters setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    /** Infinity-norm tolerance on the power mismatch (per unit) used as the corrector convergence criterion. */
    public double getConvergenceEpsilon() {
        return convergenceEpsilon;
    }

    public PredictorCorrectorParameters setConvergenceEpsilon(double convergenceEpsilon) {
        this.convergenceEpsilon = convergenceEpsilon;
        return this;
    }

    public int getMaxCorrectorIterations() {
        return maxCorrectorIterations;
    }

    public PredictorCorrectorParameters setMaxCorrectorIterations(int maxCorrectorIterations) {
        this.maxCorrectorIterations = maxCorrectorIterations;
        return this;
    }

    /** If true, the continuation keeps tracing the lower (unstable) branch after passing the nose. */
    public boolean isTraceLowerBranch() {
        return traceLowerBranch;
    }

    public PredictorCorrectorParameters setTraceLowerBranch(boolean traceLowerBranch) {
        this.traceLowerBranch = traceLowerBranch;
        return this;
    }

    /** Lower branch tracing stops when the load factor drops back below this value. */
    public double getMinLoadFactor() {
        return minLoadFactor;
    }

    public PredictorCorrectorParameters setMinLoadFactor(double minLoadFactor) {
        this.minLoadFactor = minLoadFactor;
        return this;
    }

    /** If true, reactive power targets are scaled together with active power (constant power factor). */
    public boolean isScaleReactivePowerWithActivePower() {
        return scaleReactivePowerWithActivePower;
    }

    public PredictorCorrectorParameters setScaleReactivePowerWithActivePower(boolean scaleReactivePowerWithActivePower) {
        this.scaleReactivePowerWithActivePower = scaleReactivePowerWithActivePower;
        return this;
    }

    /**
     * If true, every bus voltage magnitude is recorded at each point so per-bus P-V curves and dV/dlambda can be
     * built. Costs memory proportional to (buses x points); disable it on large networks when only the margin and
     * nose are needed.
     */
    public boolean isRecordBusVoltages() {
        return recordBusVoltages;
    }

    public PredictorCorrectorParameters setRecordBusVoltages(boolean recordBusVoltages) {
        this.recordBusVoltages = recordBusVoltages;
        return this;
    }

    /**
     * If true, voltage-controlled generator buses that hit a reactive power limit along the curve are switched
     * from PV to PQ (their reactive power frozen at the limit), introducing breakpoints on the P-V curve. The
     * network must be loaded so the reactive limits are available (i.e. with reactive limits enabled).
     */
    public boolean isEnforceReactiveLimits() {
        return enforceReactiveLimits;
    }

    public PredictorCorrectorParameters setEnforceReactiveLimits(boolean enforceReactiveLimits) {
        this.enforceReactiveLimits = enforceReactiveLimits;
        return this;
    }

    /** Reactive power tolerance (per unit) beyond a limit before a bus is switched PV to PQ. */
    public double getReactivePowerLimitTolerance() {
        return reactivePowerLimitTolerance;
    }

    public PredictorCorrectorParameters setReactivePowerLimitTolerance(double reactivePowerLimitTolerance) {
        this.reactivePowerLimitTolerance = reactivePowerLimitTolerance;
        return this;
    }
}
