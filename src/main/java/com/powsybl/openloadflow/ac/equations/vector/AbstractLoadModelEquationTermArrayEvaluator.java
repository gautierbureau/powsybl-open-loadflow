/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.Derivative;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfLoad;

import java.util.List;
import java.util.Objects;

/**
 * The power the loads of a bus that follow a model take, as a term array with one element for each bus that has such
 * loads: values and derivatives are read from {@link AcLoadVector}, which {@link AcNetworkVector} computes at each state
 * update with the formulas of the term classes. It only depends on the voltage magnitude of the bus.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public abstract class AbstractLoadModelEquationTermArrayEvaluator implements EquationTermArray.Evaluator<AcVariableType> {

    protected final AcLoadVector loadVector;

    protected final AcBusVector busVector;

    protected final VariableSet<AcVariableType> variableSet;

    protected AbstractLoadModelEquationTermArrayEvaluator(AcLoadVector loadVector, AcBusVector busVector, VariableSet<AcVariableType> variableSet) {
        this.loadVector = Objects.requireNonNull(loadVector);
        this.busVector = Objects.requireNonNull(busVector);
        this.variableSet = Objects.requireNonNull(variableSet);
    }

    @Override
    public boolean isDisabled(int busNum) {
        return busVector.disabled[busNum];
    }

    public Variable<AcVariableType> getVVar(int busNum) {
        return variableSet.getVariable(busNum, AcVariableType.BUS_V);
    }

    /** The loads of the bus that follow a model. */
    public List<LfLoad> getLoads(int busNum) {
        return loadVector.getLoads(busNum);
    }

    @Override
    public List<Derivative<AcVariableType>> getDerivatives(int busNum) {
        return List.of(new Derivative<>(getVVar(busNum), 0));
    }
}
