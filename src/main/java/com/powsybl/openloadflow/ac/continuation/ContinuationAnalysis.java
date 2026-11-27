/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;

import java.util.Objects;

/**
 * Single entry point to run a continuation power flow and estimate the voltage collapse / maximum loadability
 * point of a network, hiding the choice between the two engines behind {@link ContinuationAnalysisParameters}.
 *
 * <p>Example:</p>
 * <pre>
 *     ContinuationAnalysisParameters parameters = new ContinuationAnalysisParameters()
 *             .setEngine(ContinuationAnalysisParameters.Engine.PREDICTOR_CORRECTOR)
 *             .setLoadIncreaseDirection(LoadIncreaseDirection.allLoads());
 *     ContinuationResult result = ContinuationAnalysis.run(network, parameters);
 *     double margin = result.getMaxLoadFactor();
 *     result.getNosePoint().ifPresent(nose -&gt; System.out.println("collapse at " + nose.loadFactor()));
 * </pre>
 *
 * @author Claude
 */
public final class ContinuationAnalysis {

    private ContinuationAnalysis() {
    }

    /**
     * Runs a continuation with default load flow configuration (a sparse matrix factory and default
     * OpenLoadFlow parameters).
     */
    public static ContinuationResult run(Network network, ContinuationAnalysisParameters parameters) {
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        return run(network, lfParameters, OpenLoadFlowParameters.create(lfParameters), new SparseMatrixFactory(), parameters);
    }

    /**
     * Runs a continuation on the main connected component of {@code network}, using the given load flow
     * configuration for the underlying AC solves. The network state is not modified.
     */
    public static ContinuationResult run(Network network, LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt,
                                         MatrixFactory matrixFactory, ContinuationAnalysisParameters parameters) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(parameters);
        return switch (parameters.getEngine()) {
            case STEPPED -> new ContinuationPowerFlow(parameters.getSteppedParameters())
                    .run(network, lfParameters, lfParametersExt, matrixFactory, parameters.getLoadIncreaseDirection());
            case PREDICTOR_CORRECTOR -> new PredictorCorrectorContinuationPowerFlow(parameters.getPredictorCorrectorParameters())
                    .run(network, lfParameters, lfParametersExt, matrixFactory,
                            parameters.getLoadIncreaseDirection(), parameters.getGenerationParticipation());
        };
    }
}
