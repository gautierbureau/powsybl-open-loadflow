/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

import java.util.Objects;

/**
 * Parameters of a {@link ContinuationAnalysis}: which continuation engine to run, how to increase load and
 * generation, and the engine-specific stepping parameters.
 *
 * @author Claude
 */
public class ContinuationAnalysisParameters {

    /**
     * The continuation engine to use.
     */
    public enum Engine {
        /**
         * Stepped continuation ({@link ContinuationPowerFlow}): reuses the full AC engine and its outer loops
         * (distributed slack, reactive limits, ...). Traces the upper branch and the loadability margin; cannot
         * pass the nose.
         */
        STEPPED,
        /**
         * Predictor-corrector continuation ({@link PredictorCorrectorContinuationPowerFlow}): a true CPF that
         * passes through the nose and traces the lower (unstable) branch, on a smooth single-slack model.
         */
        PREDICTOR_CORRECTOR
    }

    private Engine engine = Engine.PREDICTOR_CORRECTOR;

    private LoadIncreaseDirection loadIncreaseDirection = LoadIncreaseDirection.allLoads();

    private GenerationParticipation generationParticipation = GenerationParticipation.none();

    private ContinuationPowerFlowParameters steppedParameters = new ContinuationPowerFlowParameters();

    private PredictorCorrectorParameters predictorCorrectorParameters = new PredictorCorrectorParameters();

    public Engine getEngine() {
        return engine;
    }

    public ContinuationAnalysisParameters setEngine(Engine engine) {
        this.engine = Objects.requireNonNull(engine);
        return this;
    }

    /** Which loads participate in the increase and with which weight (defaults to all loads). */
    public LoadIncreaseDirection getLoadIncreaseDirection() {
        return loadIncreaseDirection;
    }

    public ContinuationAnalysisParameters setLoadIncreaseDirection(LoadIncreaseDirection loadIncreaseDirection) {
        this.loadIncreaseDirection = Objects.requireNonNull(loadIncreaseDirection);
        return this;
    }

    /**
     * Which generators pick up the load increase (defaults to none, i.e. the slack absorbs it). Only used by the
     * {@link Engine#PREDICTOR_CORRECTOR} engine; the stepped engine relies on its active power distribution
     * outer loop instead.
     */
    public GenerationParticipation getGenerationParticipation() {
        return generationParticipation;
    }

    public ContinuationAnalysisParameters setGenerationParticipation(GenerationParticipation generationParticipation) {
        this.generationParticipation = Objects.requireNonNull(generationParticipation);
        return this;
    }

    /** Stepping parameters used by the {@link Engine#STEPPED} engine. */
    public ContinuationPowerFlowParameters getSteppedParameters() {
        return steppedParameters;
    }

    public ContinuationAnalysisParameters setSteppedParameters(ContinuationPowerFlowParameters steppedParameters) {
        this.steppedParameters = Objects.requireNonNull(steppedParameters);
        return this;
    }

    /** Stepping parameters used by the {@link Engine#PREDICTOR_CORRECTOR} engine. */
    public PredictorCorrectorParameters getPredictorCorrectorParameters() {
        return predictorCorrectorParameters;
    }

    public ContinuationAnalysisParameters setPredictorCorrectorParameters(PredictorCorrectorParameters predictorCorrectorParameters) {
        this.predictorCorrectorParameters = Objects.requireNonNull(predictorCorrectorParameters);
        return this;
    }
}
