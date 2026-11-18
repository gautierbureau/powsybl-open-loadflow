/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.ShuntCompensatorActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.ShuntCompensatorReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.solver.AcSolverUtil;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfShunt;
import com.powsybl.openloadflow.network.ShuntNetworkFactory;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.util.Evaluable;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a vectorized equation system the shunt terms are elements of term arrays. They must be the terms held as
 * objects of the other system: same values, same derivatives, same sensitivities, same activity.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ShuntCompensatorEquationTermArrayTest {

    /** A network as an equation system, with its shunt terms as objects or as arrays, away from the flat start. */
    private record System(LfNetwork network, AcLoadFlowContext context) implements AutoCloseable {

        static System create(Supplier<Network> networkSupplier, Consumer<LoadFlowParameters> configuration, boolean vectorized) {
            Network network = networkSupplier.get();
            LoadFlowParameters parameters = new LoadFlowParameters();
            configuration.accept(parameters);
            AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters,
                            OpenLoadFlowParameters.create(parameters), new SparseMatrixFactory(),
                            new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false)
                    .setVectorized(vectorized);
            LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
            System system = new System(lfNetwork, new AcLoadFlowContext(lfNetwork, acParameters));
            AcSolverUtil.initStateVector(lfNetwork, system.equations(), new UniformValueVoltageInitializer());
            double[] x = system.equations().getStateVector().get().clone();
            for (Variable<AcVariableType> variable : system.equations().getIndex().getSortedVariablesToFind()) {
                x[variable.getRow()] += offset(variable);
            }
            system.equations().getStateVector().set(x);
            return system;
        }

        /** The same for a variable of both systems, whatever its row. */
        static double offset(Variable<AcVariableType> variable) {
            return 0.05 * Math.sin(1.0 + variable.getElementNum() + 10 * variable.getType().ordinal());
        }

        EquationSystem<AcVariableType, AcEquationType> equations() {
            return context.getEquationSystem();
        }

        @SuppressWarnings("unchecked")
        static EquationTerm<AcVariableType, AcEquationType> term(Evaluable evaluable) {
            return (EquationTerm<AcVariableType, AcEquationType>) evaluable;
        }

        /** The sensitivity of a term to a move of every variable by its offset. */
        double sensitivity(EquationTerm<AcVariableType, AcEquationType> term) {
            DenseMatrix dx = new DenseMatrix(equations().getIndex().getRowCount(), 1);
            for (Variable<AcVariableType> variable : equations().getIndex().getSortedVariablesToFind()) {
                dx.set(variable.getRow(), 0, offset(variable));
            }
            return term.calculateSensi(dx, 0);
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private static void assertSameTerm(System objects, EquationTerm<AcVariableType, AcEquationType> object,
                                       System arrays, EquationTerm<AcVariableType, AcEquationType> array) {
        assertInstanceOf(EquationTermArray.EquationTermArrayElementImpl.class, array);
        assertEquals(object.isActive(), array.isActive());
        assertEquals(object.eval(), array.eval());
        assertEquals(object.getVariables().size(), array.getVariables().size());
        for (Variable<AcVariableType> variable : object.getVariables()) {
            Variable<AcVariableType> sameVariable = arrays.equations().getVariable(variable.getElementNum(), variable.getType());
            assertTrue(array.getVariables().contains(sameVariable));
            assertEquals(object.der(variable), array.der(sameVariable));
        }
    }

    private static void assertSameAsTheObjectTerms(Supplier<Network> network, Consumer<LoadFlowParameters> configuration,
                                                   boolean aSusceptanceIsAVariable) {
        try (System objects = System.create(network, configuration, false);
             System arrays = System.create(network, configuration, true)) {
            assertFalse(objects.network().getShunts().isEmpty());
            boolean susceptanceVariable = false;
            for (LfShunt shunt : objects.network().getShunts()) {
                LfShunt sameShunt = arrays.network().getShunt(shunt.getNum());
                EquationTerm<AcVariableType, AcEquationType> q = System.term(shunt.getQ());
                assertInstanceOf(ShuntCompensatorReactiveFlowEquationTerm.class, q);
                assertSameTerm(objects, q, arrays, System.term(sameShunt.getQ()));
                assertEquals(objects.sensitivity(q), arrays.sensitivity(System.term(sameShunt.getQ())), 1e-15);
                EquationTerm<AcVariableType, AcEquationType> p = System.term(shunt.getP());
                assertInstanceOf(ShuntCompensatorActiveFlowEquationTerm.class, p);
                assertSameTerm(objects, p, arrays, System.term(sameShunt.getP()));
                susceptanceVariable |= q.getVariables().size() == 2;
            }
            assertEquals(aSusceptanceIsAVariable, susceptanceVariable);
            for (LfBus bus : objects.network().getBuses()) {
                LfBus sameBus = arrays.network().getBus(bus.getNum());
                assertEquals(bus.getP().eval(), sameBus.getP().eval(), 1e-14);
                assertEquals(bus.getQ().eval(), sameBus.getQ().eval(), 1e-14);
            }

            // a susceptance changes, as an outer loop or a contingency does: the arrays follow at once
            LfShunt shunt = objects.network().getShunts().get(0);
            LfShunt sameShunt = arrays.network().getShunt(shunt.getNum());
            shunt.setB(shunt.getB() + 0.01);
            sameShunt.setB(sameShunt.getB() + 0.01);
            assertSameTerm(objects, System.term(shunt.getQ()), arrays, System.term(sameShunt.getQ()));

            // a shunt is lost: its terms are off in both systems
            shunt.setDisabled(true);
            sameShunt.setDisabled(true);
            assertFalse(System.term(shunt.getQ()).isActive());
            assertFalse(System.term(shunt.getP()).isActive());
            assertFalse(System.term(sameShunt.getQ()).isActive());
            assertFalse(System.term(sameShunt.getP()).isActive());
        }
    }

    @Test
    void fixedSusceptances() {
        assertSameAsTheObjectTerms(IeeeCdfNetworkFactory::create14, parameters -> { }, false);
        assertSameAsTheObjectTerms(ShuntNetworkFactory::create, parameters -> { }, false);
    }

    @Test
    void aSusceptanceThatIsAVariable() {
        assertSameAsTheObjectTerms(ShuntNetworkFactory::create, parameters -> parameters.setShuntCompensatorVoltageControlOn(true), true);
        assertSameAsTheObjectTerms(ShuntNetworkFactory::createWithTwoShuntCompensators,
                parameters -> parameters.setShuntCompensatorVoltageControlOn(true), true);
    }
}
