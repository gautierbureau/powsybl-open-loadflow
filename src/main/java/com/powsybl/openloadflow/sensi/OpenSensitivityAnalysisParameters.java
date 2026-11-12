/*
 * Copyright (c) 2020-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.commons.extensions.AbstractExtension;
import com.powsybl.openloadflow.ac.equations.LimitsSmoothing;
import com.powsybl.sensitivity.SensitivityAnalysisParameters;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class OpenSensitivityAnalysisParameters extends AbstractExtension<SensitivityAnalysisParameters> {

    private String debugDir;
    private boolean startWithFrozenACEmulation = START_WITH_FROZEN_AC_EMULATION_DEFAULT_VALUE;
    private int threadCount = THREAD_COUNT_DEFAULT_VALUE;
    private boolean adjointSlackDistribution = ADJOINT_SLACK_DISTRIBUTION_DEFAULT_VALUE;
    private boolean adjointReactiveLimitsSmoothing = ADJOINT_REACTIVE_LIMITS_SMOOTHING_DEFAULT_VALUE;
    private double adjointReactiveLimitsSmoothingMu = ADJOINT_REACTIVE_LIMITS_SMOOTHING_MU_DEFAULT_VALUE;
    private double adjointReactiveLimitsSmoothingVoltageScale = ADJOINT_REACTIVE_LIMITS_SMOOTHING_VOLTAGE_SCALE_DEFAULT_VALUE;
    private int adjointSmoothingPolishIterations = ADJOINT_SMOOTHING_POLISH_ITERATIONS_DEFAULT_VALUE;
    private boolean adjointTransformerVoltageControl = ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_DEFAULT_VALUE;
    private boolean adjointTransformerVoltageControlSmoothing = ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_DEFAULT_VALUE;
    private double adjointTransformerVoltageControlSmoothingMu = ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_MU_DEFAULT_VALUE;
    private double adjointTransformerVoltageControlSmoothingVoltageScale = ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_VOLTAGE_SCALE_DEFAULT_VALUE;

    public static final String DEBUG_DIR_PARAM_NAME = "debugDir";
    public static final String DEBUG_DIR_DEFAULT_VALUE = "";
    public static final String START_WITH_FROZEN_AC_EMULATION_PARAM_NAME = "startWithFrozenACEmulation";
    public static final boolean START_WITH_FROZEN_AC_EMULATION_DEFAULT_VALUE = true;
    public static final String THREAD_COUNT_PARAM_NAME = "threadCount";
    public static final int THREAD_COUNT_DEFAULT_VALUE = 1;
    public static final String ADJOINT_SLACK_DISTRIBUTION_PARAM_NAME = "adjointSlackDistribution";
    public static final boolean ADJOINT_SLACK_DISTRIBUTION_DEFAULT_VALUE = false;
    public static final String ADJOINT_REACTIVE_LIMITS_SMOOTHING_PARAM_NAME = "adjointReactiveLimitsSmoothing";
    public static final boolean ADJOINT_REACTIVE_LIMITS_SMOOTHING_DEFAULT_VALUE = false;
    public static final String ADJOINT_REACTIVE_LIMITS_SMOOTHING_MU_PARAM_NAME = "adjointReactiveLimitsSmoothingMu";
    public static final double ADJOINT_REACTIVE_LIMITS_SMOOTHING_MU_DEFAULT_VALUE = LimitsSmoothing.REACTIVE_DEFAULT_MU;
    public static final String ADJOINT_REACTIVE_LIMITS_SMOOTHING_VOLTAGE_SCALE_PARAM_NAME = "adjointReactiveLimitsSmoothingVoltageScale";
    public static final double ADJOINT_REACTIVE_LIMITS_SMOOTHING_VOLTAGE_SCALE_DEFAULT_VALUE = LimitsSmoothing.REACTIVE_DEFAULT_VOLTAGE_SCALE;
    public static final String ADJOINT_SMOOTHING_POLISH_ITERATIONS_PARAM_NAME = "adjointSmoothingPolishIterations";
    public static final int ADJOINT_SMOOTHING_POLISH_ITERATIONS_DEFAULT_VALUE = 0;
    public static final String ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_PARAM_NAME = "adjointTransformerVoltageControl";
    public static final boolean ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_DEFAULT_VALUE = false;
    public static final String ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_PARAM_NAME = "adjointTransformerVoltageControlSmoothing";
    public static final boolean ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_DEFAULT_VALUE = false;
    public static final String ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_MU_PARAM_NAME = "adjointTransformerVoltageControlSmoothingMu";
    public static final double ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_MU_DEFAULT_VALUE = LimitsSmoothing.RATIO_DEFAULT_MU;
    public static final String ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_VOLTAGE_SCALE_PARAM_NAME = "adjointTransformerVoltageControlSmoothingVoltageScale";
    public static final double ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_VOLTAGE_SCALE_DEFAULT_VALUE = LimitsSmoothing.RATIO_DEFAULT_VOLTAGE_SCALE;

    public static final List<String> SPECIFIC_PARAMETERS_NAMES = List.of(DEBUG_DIR_PARAM_NAME, START_WITH_FROZEN_AC_EMULATION_PARAM_NAME, THREAD_COUNT_PARAM_NAME,
            ADJOINT_SLACK_DISTRIBUTION_PARAM_NAME, ADJOINT_REACTIVE_LIMITS_SMOOTHING_PARAM_NAME, ADJOINT_REACTIVE_LIMITS_SMOOTHING_MU_PARAM_NAME,
            ADJOINT_REACTIVE_LIMITS_SMOOTHING_VOLTAGE_SCALE_PARAM_NAME, ADJOINT_SMOOTHING_POLISH_ITERATIONS_PARAM_NAME,
            ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_PARAM_NAME, ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_PARAM_NAME,
            ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_MU_PARAM_NAME, ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_VOLTAGE_SCALE_PARAM_NAME);

    @Override
    public String getName() {
        return "open-sensitivity-parameters";
    }

    public String getDebugDir() {
        return debugDir;
    }

    public OpenSensitivityAnalysisParameters setDebugDir(String debugDir) {
        this.debugDir = debugDir;
        return this;
    }

    public boolean isStartWithFrozenACEmulation() {
        return startWithFrozenACEmulation;
    }

    public OpenSensitivityAnalysisParameters setStartWithFrozenACEmulation(boolean startWithFrozenACEmulation) {
        this.startWithFrozenACEmulation = startWithFrozenACEmulation;
        return this;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public OpenSensitivityAnalysisParameters setThreadCount(int threadCount) {
        this.threadCount = threadCount;
        return this;
    }

    public boolean isAdjointSlackDistribution() {
        return adjointSlackDistribution;
    }

    /**
     * With a distributed slack, makes the AC adjoint differentiate through the slack distribution: the active
     * power imbalance a variable creates, losses included, is spread over the participating elements as the
     * load flow distributes it, instead of being absorbed by the slack bus. The gradient then matches a load
     * flow re-run. Off by default, which keeps the adjoint equal to the forward sensitivity.
     */
    public OpenSensitivityAnalysisParameters setAdjointSlackDistribution(boolean adjointSlackDistribution) {
        this.adjointSlackDistribution = adjointSlackDistribution;
        return this;
    }

    public boolean isAdjointReactiveLimitsSmoothing() {
        return adjointReactiveLimitsSmoothing;
    }

    /**
     * Makes the AC adjoint differentiate a load flow whose generator reactive limits are smoothed, at the solution of
     * the exact load flow: a bus controlling its own voltage then counts for how far it is from its reactive limit,
     * instead of all (PV) or nothing (switched PQ). It costs one more factorization, on a copy of the solved network.
     * Off by default: the gradient follows the PV/PQ switch, and jumps across it.
     */
    public OpenSensitivityAnalysisParameters setAdjointReactiveLimitsSmoothing(boolean adjointReactiveLimitsSmoothing) {
        this.adjointReactiveLimitsSmoothing = adjointReactiveLimitsSmoothing;
        return this;
    }

    public double getAdjointReactiveLimitsSmoothingMu() {
        return adjointReactiveLimitsSmoothingMu;
    }

    /** The smoothing of the reactive limits, per unit on the base power: the smaller, the closer to the switch. */
    public OpenSensitivityAnalysisParameters setAdjointReactiveLimitsSmoothingMu(double adjointReactiveLimitsSmoothingMu) {
        this.adjointReactiveLimitsSmoothingMu = adjointReactiveLimitsSmoothingMu;
        return this;
    }

    public double getAdjointReactiveLimitsSmoothingVoltageScale() {
        return adjointReactiveLimitsSmoothingVoltageScale;
    }

    /**
     * The weight of a per unit voltage deviation against a per unit reactive headroom in the smoothed reactive
     * limits, of the order of the reactive power that moves a bus voltage by 1 per unit.
     */
    public OpenSensitivityAnalysisParameters setAdjointReactiveLimitsSmoothingVoltageScale(double adjointReactiveLimitsSmoothingVoltageScale) {
        this.adjointReactiveLimitsSmoothingVoltageScale = adjointReactiveLimitsSmoothingVoltageScale;
        return this;
    }

    public int getAdjointSmoothingPolishIterations() {
        return adjointSmoothingPolishIterations;
    }

    /**
     * Newton iterations on the smoothed load flow, from the exact solution, before the adjoint: 0 (the default)
     * linearizes the smoothed equations at the exact solution, more moves to the smoothed load flow's solution.
     */
    public OpenSensitivityAnalysisParameters setAdjointSmoothingPolishIterations(int adjointSmoothingPolishIterations) {
        if (adjointSmoothingPolishIterations < 0) {
            throw new IllegalArgumentException("Polish iterations must be >= 0: " + adjointSmoothingPolishIterations);
        }
        this.adjointSmoothingPolishIterations = adjointSmoothingPolishIterations;
        return this;
    }

    public boolean isAdjointTransformerVoltageControl() {
        return adjointTransformerVoltageControl;
    }

    /**
     * Makes the AC adjoint differentiate the ratio tap changers' voltage control: the load flow ends with every tap
     * rounded and held, so the adjoint otherwise sees a bus a tap regulates as free. With this on, the adjoint works on
     * a copy of the solved network where every regulating tap that is not at a tap limit regulates again, its ratio
     * continuous, so that the bus it holds stays held. It costs one more factorization. Off by default.
     */
    public OpenSensitivityAnalysisParameters setAdjointTransformerVoltageControl(boolean adjointTransformerVoltageControl) {
        this.adjointTransformerVoltageControl = adjointTransformerVoltageControl;
        return this;
    }

    public boolean isAdjointTransformerVoltageControlSmoothing() {
        return adjointTransformerVoltageControlSmoothing;
    }

    /**
     * With {@link #setAdjointTransformerVoltageControl adjointTransformerVoltageControl}, smooths the ratio limits of
     * the tap changers: a tap near or at the end of its range counts for how far it is from it, instead of regulating
     * fully up to the last step and not at all at the end. Applies to a single transformer controlling the voltage of
     * one of its ends; the others keep the exact rows. Off by default.
     */
    public OpenSensitivityAnalysisParameters setAdjointTransformerVoltageControlSmoothing(boolean adjointTransformerVoltageControlSmoothing) {
        this.adjointTransformerVoltageControlSmoothing = adjointTransformerVoltageControlSmoothing;
        return this;
    }

    public double getAdjointTransformerVoltageControlSmoothingMu() {
        return adjointTransformerVoltageControlSmoothingMu;
    }

    /** The smoothing of the ratio limits, in ratio units: about one tap step. */
    public OpenSensitivityAnalysisParameters setAdjointTransformerVoltageControlSmoothingMu(double adjointTransformerVoltageControlSmoothingMu) {
        this.adjointTransformerVoltageControlSmoothingMu = adjointTransformerVoltageControlSmoothingMu;
        return this;
    }

    public double getAdjointTransformerVoltageControlSmoothingVoltageScale() {
        return adjointTransformerVoltageControlSmoothingVoltageScale;
    }

    /** The weight of a per unit voltage deviation against a ratio headroom: about 1, a ratio change moving the voltage as much. */
    public OpenSensitivityAnalysisParameters setAdjointTransformerVoltageControlSmoothingVoltageScale(double adjointTransformerVoltageControlSmoothingVoltageScale) {
        this.adjointTransformerVoltageControlSmoothingVoltageScale = adjointTransformerVoltageControlSmoothingVoltageScale;
        return this;
    }

    public static OpenSensitivityAnalysisParameters getOrDefault(SensitivityAnalysisParameters sensitivityAnalysisParameters) {
        OpenSensitivityAnalysisParameters sensiParametersExt = sensitivityAnalysisParameters.getExtension(OpenSensitivityAnalysisParameters.class);
        if (sensiParametersExt == null) {
            sensiParametersExt = new OpenSensitivityAnalysisParameters();
        }
        return sensiParametersExt;
    }

    public static OpenSensitivityAnalysisParameters load() {
        return load(PlatformConfig.defaultConfig());
    }

    public static OpenSensitivityAnalysisParameters load(PlatformConfig platformConfig) {
        OpenSensitivityAnalysisParameters parameters = new OpenSensitivityAnalysisParameters();
        platformConfig.getOptionalModuleConfig("open-sensitivityanalysis-default-parameters")
                .ifPresent(config -> parameters
                        .setDebugDir(config.getStringProperty(DEBUG_DIR_PARAM_NAME, DEBUG_DIR_DEFAULT_VALUE))
                        .setStartWithFrozenACEmulation(config.getBooleanProperty(START_WITH_FROZEN_AC_EMULATION_PARAM_NAME, START_WITH_FROZEN_AC_EMULATION_DEFAULT_VALUE))
                        .setThreadCount(config.getIntProperty(THREAD_COUNT_PARAM_NAME, THREAD_COUNT_DEFAULT_VALUE))
                        .setAdjointSlackDistribution(config.getBooleanProperty(ADJOINT_SLACK_DISTRIBUTION_PARAM_NAME, ADJOINT_SLACK_DISTRIBUTION_DEFAULT_VALUE))
                        .setAdjointReactiveLimitsSmoothing(config.getBooleanProperty(ADJOINT_REACTIVE_LIMITS_SMOOTHING_PARAM_NAME, ADJOINT_REACTIVE_LIMITS_SMOOTHING_DEFAULT_VALUE))
                        .setAdjointReactiveLimitsSmoothingMu(config.getDoubleProperty(ADJOINT_REACTIVE_LIMITS_SMOOTHING_MU_PARAM_NAME, ADJOINT_REACTIVE_LIMITS_SMOOTHING_MU_DEFAULT_VALUE))
                        .setAdjointReactiveLimitsSmoothingVoltageScale(config.getDoubleProperty(ADJOINT_REACTIVE_LIMITS_SMOOTHING_VOLTAGE_SCALE_PARAM_NAME,
                                ADJOINT_REACTIVE_LIMITS_SMOOTHING_VOLTAGE_SCALE_DEFAULT_VALUE))
                        .setAdjointSmoothingPolishIterations(config.getIntProperty(ADJOINT_SMOOTHING_POLISH_ITERATIONS_PARAM_NAME,
                                ADJOINT_SMOOTHING_POLISH_ITERATIONS_DEFAULT_VALUE))
                        .setAdjointTransformerVoltageControl(config.getBooleanProperty(ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_PARAM_NAME,
                                ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_DEFAULT_VALUE))
                        .setAdjointTransformerVoltageControlSmoothing(config.getBooleanProperty(ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_PARAM_NAME,
                                ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_DEFAULT_VALUE))
                        .setAdjointTransformerVoltageControlSmoothingMu(config.getDoubleProperty(ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_MU_PARAM_NAME,
                                ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_MU_DEFAULT_VALUE))
                        .setAdjointTransformerVoltageControlSmoothingVoltageScale(config.getDoubleProperty(ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_VOLTAGE_SCALE_PARAM_NAME,
                                ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_VOLTAGE_SCALE_DEFAULT_VALUE)));
        return parameters;
    }

    public static OpenSensitivityAnalysisParameters load(Map<String, String> properties) {
        return new OpenSensitivityAnalysisParameters().update(properties);
    }

    /** Sets the parameters present in {@code properties}, leaving the others as they are. */
    public OpenSensitivityAnalysisParameters update(Map<String, String> properties) {
        OpenSensitivityAnalysisParameters parameters = this;
        Optional.ofNullable(properties.get(DEBUG_DIR_PARAM_NAME)).ifPresent(parameters::setDebugDir);
        Optional.ofNullable(properties.get(START_WITH_FROZEN_AC_EMULATION_PARAM_NAME))
                .ifPresent(value -> parameters.setStartWithFrozenACEmulation(Boolean.parseBoolean(value)));
        Optional.ofNullable(properties.get(THREAD_COUNT_PARAM_NAME))
                .ifPresent(value -> parameters.setThreadCount(Integer.parseInt(value)));
        Optional.ofNullable(properties.get(ADJOINT_SLACK_DISTRIBUTION_PARAM_NAME))
                .ifPresent(value -> parameters.setAdjointSlackDistribution(Boolean.parseBoolean(value)));
        Optional.ofNullable(properties.get(ADJOINT_REACTIVE_LIMITS_SMOOTHING_PARAM_NAME))
                .ifPresent(value -> parameters.setAdjointReactiveLimitsSmoothing(Boolean.parseBoolean(value)));
        Optional.ofNullable(properties.get(ADJOINT_REACTIVE_LIMITS_SMOOTHING_MU_PARAM_NAME))
                .ifPresent(value -> parameters.setAdjointReactiveLimitsSmoothingMu(Double.parseDouble(value)));
        Optional.ofNullable(properties.get(ADJOINT_REACTIVE_LIMITS_SMOOTHING_VOLTAGE_SCALE_PARAM_NAME))
                .ifPresent(value -> parameters.setAdjointReactiveLimitsSmoothingVoltageScale(Double.parseDouble(value)));
        Optional.ofNullable(properties.get(ADJOINT_SMOOTHING_POLISH_ITERATIONS_PARAM_NAME))
                .ifPresent(value -> parameters.setAdjointSmoothingPolishIterations(Integer.parseInt(value)));
        Optional.ofNullable(properties.get(ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_PARAM_NAME))
                .ifPresent(value -> parameters.setAdjointTransformerVoltageControl(Boolean.parseBoolean(value)));
        Optional.ofNullable(properties.get(ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_PARAM_NAME))
                .ifPresent(value -> parameters.setAdjointTransformerVoltageControlSmoothing(Boolean.parseBoolean(value)));
        Optional.ofNullable(properties.get(ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_MU_PARAM_NAME))
                .ifPresent(value -> parameters.setAdjointTransformerVoltageControlSmoothingMu(Double.parseDouble(value)));
        Optional.ofNullable(properties.get(ADJOINT_TRANSFORMER_VOLTAGE_CONTROL_SMOOTHING_VOLTAGE_SCALE_PARAM_NAME))
                .ifPresent(value -> parameters.setAdjointTransformerVoltageControlSmoothingVoltageScale(Double.parseDouble(value)));
        return parameters;
    }
}
