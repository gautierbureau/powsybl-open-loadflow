/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.fastdecoupled;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.LoadModelReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.vector.LoadModelReactiveFlowEquationTermArrayEvaluator;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfLoadModel;

import java.util.Collection;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * @author Jeanne Archambault {@literal <jeanne.archambault at artelys.com>}
 */
public class LoadModelReactiveFlowFastDecoupledEquationTerm implements FastDecoupledEquationTerm {

    private final DoubleSupplier derivative;

    public LoadModelReactiveFlowFastDecoupledEquationTerm(LoadModelReactiveFlowEquationTerm loadModelReativeFlowEquationTerm) {
        derivative = () -> derFastDecoupled(loadModelReativeFlowEquationTerm.getTarget(), loadModelReativeFlowEquationTerm.getExpTerms());
    }

    /** The element of a bus is the sum of the loads of the bus that follow a model. */
    public LoadModelReactiveFlowFastDecoupledEquationTerm(LoadModelReactiveFlowEquationTermArrayEvaluator loadModelReactiveFlowEvaluator, int busNum) {
        derivative = () -> {
            List<LfLoad> loads = loadModelReactiveFlowEvaluator.getLoads(busNum);
            double value = derFastDecoupled(loads.get(0));
            for (LfLoad load : loads.subList(1, loads.size())) {
                value += derFastDecoupled(load);
            }
            return value;
        };
    }

    private static double derFastDecoupled(LfLoad load) {
        return derFastDecoupled(load.getTargetQ(), load.getLoadModel().orElseThrow().getExpTermsQ());
    }

    private static double derFastDecoupled(double target, Collection<LfLoadModel.ExpTerm> expTerms) {
        double value = 0;
        for (LfLoadModel.ExpTerm expTerm : expTerms) {
            if (expTerm.n() != 0) {
                value += expTerm.c() * expTerm.n();
            }
        }
        return value * target;
    }

    public double derFastDecoupled(Variable<AcVariableType> variable) {
        return derivative.getAsDouble();
    }
}
