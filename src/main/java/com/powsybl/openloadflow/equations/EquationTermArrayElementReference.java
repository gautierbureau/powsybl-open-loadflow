/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.network.ElementType;

import java.util.List;
import java.util.Objects;

/**
 * A term of a single equation that refers to an element of a term array: its value and its derivatives are those the
 * array holds for this element, and are read there. Its activity is its own, as the equation is another one.
 * <p>
 * An equation that sums quantities the equation arrays already hold, as a reactive power distribution equation sums
 * the reactive flows of the balances of the controller buses, takes them through such terms: the flow is computed
 * once, and the term only says which element it is, of which array.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class EquationTermArrayElementReference<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractNamedEquationTerm<V, E> {

    private final EquationTermArray<V, E> termArray;

    private final int termElementNum;

    private final List<Derivative<V>> derivatives;

    private final List<Variable<V>> variables;

    public EquationTermArrayElementReference(EquationTermArray<V, E> termArray, int termElementNum) {
        super(!Objects.requireNonNull(termArray).getEvaluator().isDisabled(termElementNum));
        this.termArray = termArray;
        this.termElementNum = termElementNum;
        derivatives = List.copyOf(termArray.getEvaluator().getDerivatives(termElementNum));
        variables = derivatives.stream().map(Derivative::getVariable).distinct().toList();
    }

    public EquationTermArray<V, E> getTermArray() {
        return termArray;
    }

    /** The number of the element in the term array: its index in the arrays of values and of derivatives. */
    public int getTermElementNum() {
        return termElementNum;
    }

    /** The variables the element depends on, each with the index of its derivatives in {@link EquationTermArray#evalDer()}. */
    public List<Derivative<V>> getDerivatives() {
        return derivatives;
    }

    @Override
    public ElementType getElementType() {
        return termArray.getElementType();
    }

    @Override
    public int getElementNum() {
        return termElementNum;
    }

    @Override
    public List<Variable<V>> getVariables() {
        return variables;
    }

    @Override
    public double eval() {
        return termArray.eval(termElementNum);
    }

    @Override
    public double der(Variable<V> variable) {
        Objects.requireNonNull(variable);
        for (Derivative<V> derivative : derivatives) {
            if (derivative.getVariable().equals(variable)) {
                return termArray.evalDer()[derivative.getLocalIndex()][termElementNum];
            }
        }
        throw new IllegalStateException("Unknown variable: " + variable);
    }

    @Override
    public double calculateSensi(DenseMatrix dx, int column) {
        return termArray.getEvaluator().calculateSensi(termElementNum, dx, column);
    }

    @Override
    protected String getName() {
        return termArray.getEvaluator().getName();
    }
}
