/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcStateVectorUtil;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.fixed.FixedEquationSystemIndex;
import com.powsybl.openloadflow.equations.fixed.FixedStructure;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;

import java.util.List;
import java.util.Objects;

/**
 * The voltages and angles of the buses by position of the fixed equation system, kept at the end of a solve that wrote
 * them to the network, so that the next solve of the same load flow starts from them, as the GPU keeps its state
 * between outer loop iterations.
 *
 * <p>A solve that starts from the previous values reads them from the network. They are the ones kept, as long as
 * nothing else wrote a bus voltage or angle since ({@link LfNetwork#getStateWriteCount()}): restoring the network
 * after a contingency, or an outer loop that resets a voltage. Otherwise the network is read. A voltage is kept as the
 * network gives it back, which multiplies it by the nominal voltage when it is written and divides it when it is
 * read; the GPU keeps the voltage itself. The other variables are read from the network as before.
 *
 * <p>With assertions enabled, each start from the kept state is compared with the network read.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
final class AcKeptState {

    private static final byte OTHER = 0;
    private static final byte VOLTAGE = 1;
    private static final byte ANGLE = 2;

    private final LfNetwork network;

    /** The structure the positions are of; the tables below are for it. */
    private FixedStructure<AcVariableType, AcEquationType> structure;
    /** For each position: whether its variable is a bus voltage, a bus angle, or another one. */
    private byte[] kind;
    /** For each position of a bus voltage: the nominal voltage of the bus. */
    private double[] nominalV;
    /** For each position: the value kept, where {@link #kept} is set. */
    private double[] value;
    private boolean[] kept;
    private boolean valid;
    private long stateWriteCount;

    private int startCount;

    AcKeptState(LfNetwork network) {
        this.network = Objects.requireNonNull(network);
    }

    private void bind(FixedStructure<AcVariableType, AcEquationType> structure) {
        if (structure == this.structure) {
            return;
        }
        this.structure = structure;
        int variableCount = structure.getVariableCount();
        kind = new byte[variableCount];
        nominalV = new double[variableCount];
        value = new double[variableCount];
        kept = new boolean[variableCount];
        for (int position = 0; position < variableCount; position++) {
            Variable<AcVariableType> variable = structure.getVariable(position);
            if (variable.getType() == AcVariableType.BUS_V) {
                kind[position] = VOLTAGE;
                nominalV[position] = network.getBus(variable.getElementNum()).getNominalV();
            } else if (variable.getType() == AcVariableType.BUS_PHI) {
                kind[position] = ANGLE;
            }
        }
    }

    /** Keeps the state the solve just wrote to the network. */
    void keep(EquationSystem<AcVariableType, AcEquationType> equationSystem, FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed) {
        FixedStructure<AcVariableType, AcEquationType> current = fixed.getStructure();
        if (current == null) {
            valid = false;
            return;
        }
        bind(current);
        double[] state = equationSystem.getStateVector().get();
        for (int position = 0; position < kind.length; position++) {
            int row = structure.getVariable(position).getRow();
            kept[position] = row >= 0 && kind[position] != OTHER;
            if (kept[position]) {
                // as the network gives it back: AbstractLfBus.setV and getV
                value[position] = kind[position] == VOLTAGE ? state[row] * nominalV[position] / nominalV[position] : state[row];
            }
        }
        valid = true;
        stateWriteCount = network.getStateWriteCount();
    }

    void forget() {
        valid = false;
    }

    /**
     * Writes the initial state of a solve that starts from the previous values, from the kept state, and returns
     * true; returns false when it cannot: the state is then read from the network.
     */
    boolean initStateVector(EquationSystem<AcVariableType, AcEquationType> equationSystem,
                            FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed, VoltageInitializer initializer) {
        if (!valid || !(initializer instanceof PreviousValueVoltageInitializer)
                || stateWriteCount != network.getStateWriteCount()) {
            return false;
        }
        List<Variable<AcVariableType>> variables = equationSystem.getIndex().getSortedVariablesToFind();
        if (fixed.getStructure() != structure) {
            return false;
        }
        double[] x = new double[variables.size()];
        boolean[] written = new boolean[x.length];
        for (int position = 0; position < kind.length; position++) {
            int row = structure.getVariable(position).getRow();
            if (row >= 0 && kept[position]) {
                x[row] = value[position];
                written[row] = true;
            }
        }
        for (int row = 0; row < x.length; row++) {
            if (!written[row]) {
                x[row] = AcStateVectorUtil.initValue(network, variables.get(row), initializer);
            }
        }
        assert sameAsNetworkRead(x, variables, initializer);
        startCount++;
        equationSystem.getStateVector().set(x);
        return true;
    }

    private boolean sameAsNetworkRead(double[] x, List<Variable<AcVariableType>> variables, VoltageInitializer initializer) {
        for (int row = 0; row < x.length; row++) {
            double read = AcStateVectorUtil.initValue(network, variables.get(row), initializer);
            if (Double.doubleToLongBits(read) != Double.doubleToLongBits(x[row])) {
                throw new AssertionError("Kept state of " + variables.get(row) + " is " + x[row] + ", read from the network " + read);
            }
        }
        return true;
    }

    /** How many solves started from the kept state. */
    int getStartCount() {
        return startCount;
    }
}
