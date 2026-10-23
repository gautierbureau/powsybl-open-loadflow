/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.se.equations;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.se.Measurement;
import com.powsybl.openloadflow.se.WlsResult;
import com.powsybl.openloadflow.se.WlsStateEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The vectorized measurement equations must give the same values and derivatives as the scalar ones, so that the
 * estimator can use either.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class VectorizedMeasurementEquationsTest {

    private static final double EPS = 1e-10;

    @Test
    void matchesTheScalarEquationsValueAndDerivatives() {
        LfNetwork network = Networks.load(IeeeCdfNetworkFactory.create118(), new FirstSlackBusSelector()).get(0);
        int busCount = network.getBuses().size();
        VariableSet<AcVariableType> variableSet = new VariableSet<>();
        for (LfBus bus : network.getBuses()) {
            variableSet.getVariable(bus.getNum(), AcVariableType.BUS_V).setRow(bus.getNum());
            variableSet.getVariable(bus.getNum(), AcVariableType.BUS_PHI).setRow(busCount + bus.getNum());
        }
        StateVector stateVector = new StateVector(new double[2 * busCount]);
        VectorizedMeasurementEquations vectorized = new VectorizedMeasurementEquations(network, variableSet, stateVector);

        List<MeasurementEquation> scalarEquations = new ArrayList<>();
        List<MeasurementEquation> vectorEquations = new ArrayList<>();
        for (LfBus bus : network.getBuses()) {
            scalarEquations.add(MeasurementEquations.busVoltage(bus, variableSet, stateVector));
            vectorEquations.add(vectorized.busVoltage(bus));
            for (boolean active : new boolean[] {true, false}) {
                scalarEquations.add(MeasurementEquations.busInjection(bus, active, variableSet, stateVector));
                vectorEquations.add(vectorized.busInjection(bus, active));
            }
        }
        for (LfBranch branch : network.getBranches()) {
            for (MeasuredQuantity quantity : List.of(MeasuredQuantity.BRANCH_P, MeasuredQuantity.BRANCH_Q, MeasuredQuantity.BRANCH_I)) {
                for (int side = 1; side <= 2; side++) {
                    scalarEquations.add(MeasurementEquations.branchFlow(branch, quantity, side, variableSet, stateVector));
                    vectorEquations.add(vectorized.branchFlow(branch, quantity, side));
                }
            }
        }

        // two states in turn, so the vector is checked to follow each state update
        for (double phase : new double[] {0, 0.8}) {
            double[] state = new double[2 * busCount];
            for (int i = 0; i < busCount; i++) {
                state[i] = 1 + 0.04 * Math.sin(0.7 * i + phase);
                state[busCount + i] = 0.2 * Math.cos(1.3 * i + phase);
            }
            stateVector.set(state);

            for (int k = 0; k < scalarEquations.size(); k++) {
                MeasurementEquation scalar = scalarEquations.get(k);
                MeasurementEquation vector = vectorEquations.get(k);
                assertEquals(scalar.eval(), vector.eval(), EPS * Math.max(1, Math.abs(scalar.eval())), "value of equation " + k);
                Set<Variable<AcVariableType>> variables = new LinkedHashSet<>(scalar.variables());
                assertEquals(variables, new LinkedHashSet<>(vector.variables()), "variables of equation " + k);
                for (Variable<AcVariableType> variable : variables) {
                    double expected = scalar.der(variable);
                    assertEquals(expected, vector.der(variable), EPS * Math.max(1, Math.abs(expected)),
                            "derivative of equation " + k + " by " + variable);
                }
                assertBulkDerivativesMatch(scalar, scalar, "scalar equation " + k);
                assertBulkDerivativesMatch(scalar, vector, "vectorized equation " + k);
            }
        }
    }

    /**
     * The bulk call must pass every variable exactly once, with the derivative {@code der} gives for it.
     */
    private static void assertBulkDerivativesMatch(MeasurementEquation reference, MeasurementEquation equation, String what) {
        Map<Variable<AcVariableType>, Double> bulk = new LinkedHashMap<>();
        equation.derivatives((variable, value) -> assertNull(bulk.put(variable, value), what + " passed " + variable + " twice"));
        assertEquals(new LinkedHashSet<>(reference.variables()), bulk.keySet(), "variables passed by " + what);
        bulk.forEach((variable, value) -> {
            double expected = reference.der(variable);
            assertEquals(expected, value, EPS * Math.max(1, Math.abs(expected)), "bulk derivative of " + what + " by " + variable);
        });
    }

    @Test
    void givesTheSameEstimateAsTheScalarEquations() {
        Network network = IeeeCdfNetworkFactory.create118();
        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        assertTrue(runner.run(network, new LoadFlowParameters()).isFullyConverged());
        LfNetwork lfNetwork = Networks.load(network, new FirstSlackBusSelector()).get(0);

        List<Measurement> measurements = noiseFreeRedundantMeterSet(lfNetwork);

        WlsStateEstimator scalarEstimator = new WlsStateEstimator(lfNetwork, measurements, false);
        scalarEstimator.initFlat();
        WlsResult scalar = scalarEstimator.estimate();
        WlsStateEstimator vectorEstimator = new WlsStateEstimator(lfNetwork, measurements, true);
        vectorEstimator.initFlat();
        WlsResult vector = vectorEstimator.estimate();

        assertTrue(scalar.isConverged());
        assertTrue(vector.isConverged());
        assertEquals(scalar.getIterationCount(), vector.getIterationCount());
        assertEquals(scalar.getObjectiveFunctionValue(), vector.getObjectiveFunctionValue(), 1e-12);
        for (int i = 0; i < scalar.getBusResults().size(); i++) {
            WlsResult.BusResult s = scalar.getBusResults().get(i);
            WlsResult.BusResult v = vector.getBusResults().get(i);
            assertEquals(s.busId(), v.busId());
            assertEquals(s.v(), v.v(), 1e-9, "voltage at " + s.busId());
            assertEquals(s.angle(), v.angle(), 1e-9, "angle at " + s.busId());
        }
    }

    /**
     * Voltage and injections at every bus, active and reactive flow at both sides of every branch, valued at the
     * load flow state.
     */
    private static List<Measurement> noiseFreeRedundantMeterSet(LfNetwork network) {
        List<Measurement> described = new ArrayList<>();
        for (LfBus bus : network.getBuses()) {
            described.add(Measurement.busVoltage(bus, 0, 0.004));
            described.add(Measurement.busInjection(bus, true, 0, 0.01));
            described.add(Measurement.busInjection(bus, false, 0, 0.01));
        }
        for (LfBranch branch : network.getBranches()) {
            if (branch.getBus1() != null && branch.getBus2() != null) {
                for (int side = 1; side <= 2; side++) {
                    described.add(Measurement.branchFlow(branch, MeasuredQuantity.BRANCH_P, side, 0, 0.01));
                    described.add(Measurement.branchFlow(branch, MeasuredQuantity.BRANCH_Q, side, 0, 0.01));
                }
            }
        }
        int busCount = network.getBuses().size();
        double[] v = new double[busCount];
        double[] angle = new double[busCount];
        for (LfBus bus : network.getBuses()) {
            v[bus.getNum()] = bus.getV();
            angle[bus.getNum()] = bus.getAngle();
        }
        WlsStateEstimator probe = new WlsStateEstimator(network, described, false);
        probe.init(v, angle);
        double[] exact = probe.evaluate();
        List<Measurement> measurements = new ArrayList<>(described.size());
        for (int i = 0; i < described.size(); i++) {
            Measurement m = described.get(i);
            measurements.add(new Measurement(m.quantity(), m.bus(), m.branch(), m.side(), exact[i], m.sigmaPu()));
        }
        return measurements;
    }
}
