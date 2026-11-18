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
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.List;
import java.util.Objects;

/**
 * The active power at one end of the HVDC lines in AC emulation, as a term array: values and derivatives are read from
 * {@link AcHvdcVector}, which {@link AcNetworkVector} computes at each state update with the formulas of the term
 * classes.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public abstract class AbstractHvdcAcEmulationEquationTermArrayEvaluator implements EquationTermArray.Evaluator<AcVariableType> {

    protected final AcHvdcVector hvdcVector;

    protected final VariableSet<AcVariableType> variableSet;

    protected AbstractHvdcAcEmulationEquationTermArrayEvaluator(AcHvdcVector hvdcVector, VariableSet<AcVariableType> variableSet) {
        this.hvdcVector = Objects.requireNonNull(hvdcVector);
        this.variableSet = Objects.requireNonNull(variableSet);
    }

    @Override
    public boolean isDisabled(int hvdcNum) {
        return hvdcVector.disabled[hvdcNum];
    }

    /** The angles of the two ends, in this order: the derivatives of {@link #evalDer()} are in the same one. */
    @Override
    public List<Derivative<AcVariableType>> getDerivatives(int hvdcNum) {
        return List.of(new Derivative<>(variableSet.getVariable(hvdcVector.bus1Num[hvdcNum], AcVariableType.BUS_PHI), 0),
                       new Derivative<>(variableSet.getVariable(hvdcVector.bus2Num[hvdcNum], AcVariableType.BUS_PHI), 1));
    }
}
