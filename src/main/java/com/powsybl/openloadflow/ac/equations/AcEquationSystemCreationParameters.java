/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcEquationSystemCreationParameters {

    private final boolean forceA1Var;

    private final LimitsSmoothing reactiveLimitsSmoothing;

    private final LimitsSmoothing ratioLimitsSmoothing;

    public AcEquationSystemCreationParameters() {
        this(false);
    }

    public AcEquationSystemCreationParameters(boolean forceA1Var) {
        this(forceA1Var, null);
    }

    /**
     * @param reactiveLimitsSmoothing when not null, a bus controlling its own voltage gets smoothed reactive limits
     *                                (see {@link LimitComplementarityEquationTerm}) instead of the PV/PQ switch
     */
    public AcEquationSystemCreationParameters(boolean forceA1Var, LimitsSmoothing reactiveLimitsSmoothing) {
        this(forceA1Var, reactiveLimitsSmoothing, null);
    }

    /**
     * @param ratioLimitsSmoothing when not null, a tap changer whose voltage control is enabled gets smoothed ratio
     *                             limits (see {@link AcEquationSystemCreator#canSmoothRatioLimits}) instead of being
     *                             held at a limit by the outer loop
     */
    public AcEquationSystemCreationParameters(boolean forceA1Var, LimitsSmoothing reactiveLimitsSmoothing,
                                              LimitsSmoothing ratioLimitsSmoothing) {
        this.forceA1Var = forceA1Var;
        this.reactiveLimitsSmoothing = reactiveLimitsSmoothing;
        this.ratioLimitsSmoothing = ratioLimitsSmoothing;
    }

    public boolean isForceA1Var() {
        return forceA1Var;
    }

    public LimitsSmoothing getReactiveLimitsSmoothing() {
        return reactiveLimitsSmoothing;
    }

    public LimitsSmoothing getRatioLimitsSmoothing() {
        return ratioLimitsSmoothing;
    }

    @Override
    public String toString() {
        return "AcEquationSystemCreationParameters(" +
                "forceA1Var=" + forceA1Var +
                (reactiveLimitsSmoothing != null ? ", reactiveLimitsSmoothing=" + reactiveLimitsSmoothing : "") +
                (ratioLimitsSmoothing != null ? ", ratioLimitsSmoothing=" + ratioLimitsSmoothing : "") +
                ')';
    }
}
