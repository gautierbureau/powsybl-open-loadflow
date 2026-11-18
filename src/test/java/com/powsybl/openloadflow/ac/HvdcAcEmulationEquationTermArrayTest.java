/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcStateVectorUtil;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.HvdcAcEmulationSide1ActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.HvdcAcEmulationSide2ActiveFlowEquationTerm;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfHvdc;
import com.powsybl.openloadflow.network.LfHvdc.AcEmulationControl.AcEmulationStatus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.test.networks.HvdcNetworkFactory;
import com.powsybl.openloadflow.util.Evaluable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a vectorized equation system the active power of an HVDC line in AC emulation is, at each end, an element of a
 * term array. It must be the term held as an object of the other system: same value, same derivatives, same activity.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class HvdcAcEmulationEquationTermArrayTest {

    /** A network as an equation system, with its HVDC terms as objects or as arrays. */
    private record System(LfNetwork network, AcLoadFlowContext context) implements AutoCloseable {

        static System create(boolean vectorized) {
            Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
            network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                    .withDroop(180)
                    .withP0(0f)
                    .withEnabled(true)
                    .add();
            LoadFlowParameters parameters = new LoadFlowParameters();
            AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters,
                            OpenLoadFlowParameters.create(parameters), new SparseMatrixFactory(),
                            new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false)
                    .setVectorized(vectorized);
            LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
            System system = new System(lfNetwork, new AcLoadFlowContext(lfNetwork, acParameters));
            AcStateVectorUtil.initStateVector(lfNetwork, system.equations(), new UniformValueVoltageInitializer());
            return system;
        }

        EquationSystem<AcVariableType, AcEquationType> equations() {
            return context.getEquationSystem();
        }

        /** Moves every variable away from the flat start, by the same amount in both systems, whatever its row. */
        void move(double sign) {
            double[] x = equations().getStateVector().get().clone();
            for (Variable<AcVariableType> variable : equations().getIndex().getSortedVariablesToFind()) {
                x[variable.getRow()] += sign * 0.05 * Math.sin(1.0 + variable.getElementNum() + 10 * variable.getType().ordinal());
            }
            equations().getStateVector().set(x);
        }

        @SuppressWarnings("unchecked")
        static EquationTerm<AcVariableType, AcEquationType> term(Evaluable evaluable) {
            return (EquationTerm<AcVariableType, AcEquationType>) evaluable;
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private static void assertSameTerm(EquationTerm<AcVariableType, AcEquationType> object,
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

    private static void assertSameAsTheObjectTerms(System objects, System arrays) {
        assertFalse(objects.network().getHvdcs().isEmpty());
        for (LfHvdc hvdc : objects.network().getHvdcs()) {
            assertTrue(hvdc.isAcEmulation());
            LfHvdc sameHvdc = arrays.network().getHvdcs().get(hvdc.getNum());
            assertInstanceOf(HvdcAcEmulationSide1ActiveFlowEquationTerm.class, hvdc.getP1());
            assertInstanceOf(HvdcAcEmulationSide2ActiveFlowEquationTerm.class, hvdc.getP2());
            assertSameTerm(System.term(hvdc.getP1()), arrays, System.term(sameHvdc.getP1()));
            assertSameTerm(System.term(hvdc.getP2()), arrays, System.term(sameHvdc.getP2()));
        }
        for (LfBus bus : objects.network().getBuses()) {
            assertEquals(bus.getP().eval(), arrays.network().getBus(bus.getNum()).getP().eval(), 1e-14);
        }
    }

    @Test
    void sameAsTheObjectTerms() {
        try (System objects = System.create(false); System arrays = System.create(true)) {
            LfHvdc hvdc = objects.network().getHvdcs().get(0);
            LfHvdc sameHvdc = arrays.network().getHvdcs().get(0);

            // the power flows one way, then the other: the end that pays the losses changes
            objects.move(1);
            arrays.move(1);
            assertSameAsTheObjectTerms(objects, arrays);
            double p1 = hvdc.getP1().eval();
            objects.move(-2);
            arrays.move(-2);
            assertSameAsTheObjectTerms(objects, arrays);
            assertTrue(p1 * hvdc.getP1().eval() < 0);

            // the line reaches a limit: its terms are off in both systems, and still give its power
            hvdc.updateAcEmulationStatus(AcEmulationStatus.SATURATION_MODE_FROM_CS1_TO_CS2);
            sameHvdc.updateAcEmulationStatus(AcEmulationStatus.SATURATION_MODE_FROM_CS1_TO_CS2);
            assertFalse(System.term(hvdc.getP1()).isActive());
            assertSameAsTheObjectTerms(objects, arrays);
            objects.move(1);
            arrays.move(1);
            assertSameAsTheObjectTerms(objects, arrays);

            // back in its range
            hvdc.updateAcEmulationStatus(AcEmulationStatus.LINEAR_MODE);
            sameHvdc.updateAcEmulationStatus(AcEmulationStatus.LINEAR_MODE);
            assertTrue(System.term(hvdc.getP1()).isActive());
            assertSameAsTheObjectTerms(objects, arrays);

            // the line is lost
            hvdc.setDisabled(true);
            sameHvdc.setDisabled(true);
            assertFalse(System.term(hvdc.getP1()).isActive());
            assertFalse(System.term(sameHvdc.getP1()).isActive());
            assertFalse(System.term(sameHvdc.getP2()).isActive());
        }
    }
}
