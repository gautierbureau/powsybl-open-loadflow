/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.fixed.FixedEquationSystem;
import com.powsybl.openloadflow.equations.fixed.FixedStructure;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The voltages and angles of the buses by slot of the fixed equation system, kept at the end of a solve that wrote
 * them to the network, so that the next solve of the same load flow starts from them, as the GPU keeps its state
 * between outer loop iterations.
 *
 * <p>A solve that starts from the previous values reads them from the network. They are the ones kept, as long as
 * nothing else wrote a bus voltage or angle since ({@link LfNetwork#getStateWriteCount()}): restoring the network
 * after a contingency, or an outer loop that resets a voltage. Otherwise the network is read. The voltage kept is the
 * one the solve converged to, as the GPU keeps it: the network multiplies it by the nominal voltage when it is
 * written and divides it when it is read, which can change its last bit. The flows of the zero impedance branches
 * are kept too, where OLF starts them from 0 at each solve. The taps, phase shifts and susceptances are read from the
 * network as before: an outer loop may have changed them there.
 *
 * <p>With assertions enabled, each start from the kept state is compared with the network read: the network holds
 * each kept voltage, and each kept angle, as it was written.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
final class AcKeptState {

    private static final byte OTHER = 0;
    private static final byte VOLTAGE = 1;
    private static final byte ANGLE = 2;
    /** A flow of a zero impedance branch: OLF starts it from 0 at each solve, the GPU from where it converged. */
    private static final byte DUMMY = 3;

    private final LfNetwork network;

    /** The structure the slots are of; the tables below are for it. */
    private FixedStructure<AcVariableType, AcEquationType> structure;
    /** For each slot: whether its variable is a bus voltage, a bus angle, or another one. */
    private byte[] kind;
    /** For each slot: the value kept, where {@link #kept} is set. */
    private double[] value;
    private boolean[] kept;
    private boolean valid;
    private long stateWriteCount;

    /** The rows written from the kept state by the last initialization, kept to be reused. */
    private boolean[] written;

    private int startCount;

    AcKeptState(LfNetwork network) {
        this.network = Objects.requireNonNull(network);
    }

    private void bind(FixedStructure<AcVariableType, AcEquationType> structure) {
        if (structure == this.structure) {
            return;
        }
        this.structure = structure;
        int slotCount = structure.getSlotCount();
        kind = new byte[slotCount];
        value = new double[slotCount];
        kept = new boolean[slotCount];
        for (int slot = 0; slot < slotCount; slot++) {
            Variable<AcVariableType> variable = structure.getVariable(slot);
            if (variable.getType() == AcVariableType.BUS_V) {
                kind[slot] = VOLTAGE;
            } else if (variable.getType() == AcVariableType.BUS_PHI) {
                kind[slot] = ANGLE;
            } else if (variable.getType() == AcVariableType.DUMMY_P || variable.getType() == AcVariableType.DUMMY_Q) {
                kind[slot] = DUMMY;
            }
        }
    }

    /** Keeps the state the solve just wrote to the network. */
    void keep(EquationSystem<AcVariableType, AcEquationType> equationSystem, FixedEquationSystem<AcVariableType, AcEquationType> fixed) {
        FixedStructure<AcVariableType, AcEquationType> current = fixed.getStructure();
        if (current == null) {
            valid = false;
            return;
        }
        bind(current);
        double[] state = equationSystem.getStateVector().get();
        // the rows of the variables of the slots, as the placement keeps them
        int[] rows = fixed.getRowOfSlots();
        for (int slot = 0; slot < kind.length; slot++) {
            int row = rows[slot];
            kept[slot] = row >= 0 && kind[slot] != OTHER;
            if (kept[slot]) {
                value[slot] = state[row];
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
                            FixedEquationSystem<AcVariableType, AcEquationType> fixed, VoltageInitializer initializer) {
        if (!valid || !(initializer instanceof PreviousValueVoltageInitializer)
                || stateWriteCount != network.getStateWriteCount()) {
            return false;
        }
        List<Variable<AcVariableType>> variables = equationSystem.getIndex().getSortedVariablesToFind();
        if (fixed.getStructure() != structure) {
            return false;
        }
        // the array of the state vector, which this initial state replaces, when it has the size: no allocation
        double[] x = AcSolverUtil.stateArray(equationSystem, variables.size());
        if (written == null || written.length != x.length) {
            written = new boolean[x.length];
        } else {
            Arrays.fill(written, false);
        }
        int[] rows = fixed.getRowOfSlots();
        for (int slot = 0; slot < kind.length; slot++) {
            int row = rows[slot];
            if (row >= 0 && kept[slot]) {
                x[row] = value[slot];
                written[row] = true;
            }
        }
        for (int row = 0; row < x.length; row++) {
            if (!written[row]) {
                x[row] = AcSolverUtil.initValue(network, variables.get(row), initializer);
            }
        }
        assert sameAsNetworkRead(x, variables, initializer, written);
        startCount++;
        equationSystem.getStateVector().set(x);
        return true;
    }

    private boolean sameAsNetworkRead(double[] x, List<Variable<AcVariableType>> variables, VoltageInitializer initializer, boolean[] written) {
        for (int row = 0; row < x.length; row++) {
            Variable<AcVariableType> variable = variables.get(row);
            double read = AcSolverUtil.initValue(network, variable, initializer);
            double expected = x[row];
            if (written[row] && (variable.getType() == AcVariableType.DUMMY_P || variable.getType() == AcVariableType.DUMMY_Q)) {
                continue; // the network holds no value for them
            }
            if (written[row] && variable.getType() == AcVariableType.BUS_V) {
                // the network holds the voltage multiplied by the nominal voltage: AbstractLfBus.setV and getV
                double nominal = network.getBus(variable.getElementNum()).getNominalV();
                expected = x[row] * nominal / nominal;
            }
            if (Double.doubleToLongBits(read) != Double.doubleToLongBits(expected)) {
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
