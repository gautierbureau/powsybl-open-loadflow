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

    private final ReactiveLimitsSmoothing reactiveLimitsSmoothing;

    public AcEquationSystemCreationParameters() {
        this(false);
    }

    public AcEquationSystemCreationParameters(boolean forceA1Var) {
        this(forceA1Var, null);
    }

    /**
     * @param reactiveLimitsSmoothing when not null, a bus controlling its own voltage gets smoothed reactive limits
     *                                (see {@link GeneratorReactiveSplitFbEquationTerm}) instead of the PV/PQ switch
     */
    public AcEquationSystemCreationParameters(boolean forceA1Var, ReactiveLimitsSmoothing reactiveLimitsSmoothing) {
        this.forceA1Var = forceA1Var;
        this.reactiveLimitsSmoothing = reactiveLimitsSmoothing;
    }

    public boolean isForceA1Var() {
        return forceA1Var;
    }

    public ReactiveLimitsSmoothing getReactiveLimitsSmoothing() {
        return reactiveLimitsSmoothing;
    }

    @Override
    public String toString() {
        return "AcEquationSystemCreationParameters(" +
                "forceA1Var=" + forceA1Var +
                (reactiveLimitsSmoothing != null ? ", reactiveLimitsSmoothing=" + reactiveLimitsSmoothing : "") +
                ')';
    }
}
