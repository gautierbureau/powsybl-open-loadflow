/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

/**
 * Parameters of the {@link ContinuationPowerFlow} stepped continuation algorithm.
 *
 * <p>The continuation increases a scalar load factor {@code lambda} from {@code 0} by adaptive steps.
 * A step that does not converge is interpreted as an overshoot of the maximum loadability point (the nose of
 * the P-V curve): the step is then reduced and retried. When the step falls below {@link #getMinStepSize()},
 * the last converged point is reported as the voltage collapse / maximum loadability point.</p>
 *
 * @author Claude
 */
public class ContinuationPowerFlowParameters {

    public static final double DEFAULT_INITIAL_STEP_SIZE = 0.25;
    public static final double DEFAULT_MIN_STEP_SIZE = 0.005;
    public static final double DEFAULT_MAX_STEP_SIZE = 0.5;
    public static final double DEFAULT_STEP_INCREASE_FACTOR = 2.0;
    public static final double DEFAULT_STEP_DECREASE_FACTOR = 0.5;
    public static final int DEFAULT_STEP_INCREASE_THRESHOLD = 2;
    public static final int DEFAULT_MAX_STEPS = 100;
    public static final boolean DEFAULT_SCALE_REACTIVE_POWER_WITH_ACTIVE_POWER = true;

    private double initialStepSize = DEFAULT_INITIAL_STEP_SIZE;
    private double minStepSize = DEFAULT_MIN_STEP_SIZE;
    private double maxStepSize = DEFAULT_MAX_STEP_SIZE;
    private double stepIncreaseFactor = DEFAULT_STEP_INCREASE_FACTOR;
    private double stepDecreaseFactor = DEFAULT_STEP_DECREASE_FACTOR;
    private int stepIncreaseThreshold = DEFAULT_STEP_INCREASE_THRESHOLD;
    private int maxSteps = DEFAULT_MAX_STEPS;
    private boolean scaleReactivePowerWithActivePower = DEFAULT_SCALE_REACTIVE_POWER_WITH_ACTIVE_POWER;

    /** Load factor increment used for the first step. */
    public double getInitialStepSize() {
        return initialStepSize;
    }

    public ContinuationPowerFlowParameters setInitialStepSize(double initialStepSize) {
        this.initialStepSize = initialStepSize;
        return this;
    }

    /** Continuation stops when the adaptive step becomes smaller than this value: the nose is then considered reached. */
    public double getMinStepSize() {
        return minStepSize;
    }

    public ContinuationPowerFlowParameters setMinStepSize(double minStepSize) {
        this.minStepSize = minStepSize;
        return this;
    }

    /** Upper bound of the adaptive step size. */
    public double getMaxStepSize() {
        return maxStepSize;
    }

    public ContinuationPowerFlowParameters setMaxStepSize(double maxStepSize) {
        this.maxStepSize = maxStepSize;
        return this;
    }

    /** Multiplicative factor applied to the step after {@link #getStepIncreaseThreshold()} consecutive successes. */
    public double getStepIncreaseFactor() {
        return stepIncreaseFactor;
    }

    public ContinuationPowerFlowParameters setStepIncreaseFactor(double stepIncreaseFactor) {
        this.stepIncreaseFactor = stepIncreaseFactor;
        return this;
    }

    /** Multiplicative factor applied to the step after a non converging step (overshoot of the nose). */
    public double getStepDecreaseFactor() {
        return stepDecreaseFactor;
    }

    public ContinuationPowerFlowParameters setStepDecreaseFactor(double stepDecreaseFactor) {
        this.stepDecreaseFactor = stepDecreaseFactor;
        return this;
    }

    /** Number of consecutive converging steps before the step size is increased. */
    public int getStepIncreaseThreshold() {
        return stepIncreaseThreshold;
    }

    public ContinuationPowerFlowParameters setStepIncreaseThreshold(int stepIncreaseThreshold) {
        this.stepIncreaseThreshold = stepIncreaseThreshold;
        return this;
    }

    /** Safety bound on the total number of load flow solves. */
    public int getMaxSteps() {
        return maxSteps;
    }

    public ContinuationPowerFlowParameters setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    /** If true, reactive power targets are scaled together with active power (constant power factor). */
    public boolean isScaleReactivePowerWithActivePower() {
        return scaleReactivePowerWithActivePower;
    }

    public ContinuationPowerFlowParameters setScaleReactivePowerWithActivePower(boolean scaleReactivePowerWithActivePower) {
        this.scaleReactivePowerWithActivePower = scaleReactivePowerWithActivePower;
        return this;
    }
}
