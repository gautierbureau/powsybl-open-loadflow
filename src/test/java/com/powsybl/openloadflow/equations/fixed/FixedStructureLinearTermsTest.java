/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreationParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreator;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcStateVectorUtil;
import com.powsybl.openloadflow.ac.equations.AcVariablePairing;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.vector.AcVectorizedEquationSystemCreator;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfNetworkParameters;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.test.networks.HvdcNetworkFactory;
import com.powsybl.openloadflow.test.networks.ZeroImpedanceNetworkFactory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The table of the linear terms of a {@link FixedStructure}: read from its arrays, in the package of the structure, on
 * an equation system created without the load flow.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class FixedStructureLinearTermsTest {

    private static LfNetwork load(Network network) {
        return Networks.load(network, new LfNetworkParameters()).get(0);
    }

    private static EquationSystem<AcVariableType, AcEquationType> create(LfNetwork lfNetwork, boolean vectorized) {
        AcEquationSystemCreationParameters parameters = new AcEquationSystemCreationParameters();
        return vectorized ? new AcVectorizedEquationSystemCreator(lfNetwork, parameters).create()
                          : new AcEquationSystemCreator(lfNetwork, parameters).create();
    }

    /**
     * Around a zero impedance line every term held as an object is a coefficient times one variable: the two angles in
     * the equation of the line, its flow in the balances of its two buses. The structure holds them in one table, with
     * their position and their coefficient, and evaluates them from it. The balance of a bus of an HVDC line in AC
     * emulation holds a term that is not of this kind: its object terms are evaluated one by one.
     */
    @Test
    void linearTermsAreHeldInATable() {
        Network network = ZeroImpedanceNetworkFactory.createWithVoltageControl();
        LfNetwork lfNetwork = load(network);
        EquationSystem<AcVariableType, AcEquationType> lfNetworkEquations = create(lfNetwork, true);
        try (FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystemIndex<>(lfNetworkEquations, new AcVariablePairing(lfNetwork), FixedEquationSystemIndex.Scope.ALL)) {
            EquationSystem<AcVariableType, AcEquationType> equationSystem = lfNetworkEquations;
            FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
            FixedStructure.LinearTerms<AcVariableType, AcEquationType> linearTerms = structure.getLinearTerms();
            // the equation of line 1: the angle of bus 1 minus the angle of bus 2
            int zeroPhi = structure.getNumber(AcEquationType.ZERO_PHI, 1);
            assertFalse(structure.isMixed(zeroPhi));
            int first = linearTerms.start[zeroPhi];
            assertEquals(2, linearTerms.start[zeroPhi + 1] - first);
            assertEquals(1, linearTerms.coefficient[first]);
            assertEquals(-1, linearTerms.coefficient[first + 1]);
            assertEquals(structure.getPosition(equationSystem.getVariable(1, AcVariableType.BUS_PHI)), linearTerms.position[first]);
            assertEquals(structure.getPosition(equationSystem.getVariable(2, AcVariableType.BUS_PHI)), linearTerms.position[first + 1]);
            // the active balance of bus 2: it receives the flow of line 1 and gives the flow of line 3
            int balance = structure.getNumber(AcEquationType.BUS_TARGET_P, 2);
            int flow1 = structure.getPosition(equationSystem.getVariable(1, AcVariableType.DUMMY_P));
            int flow3 = structure.getPosition(equationSystem.getVariable(3, AcVariableType.DUMMY_P));
            assertFalse(structure.isMixed(balance));
            assertEquals(Map.of(flow1, -1d, flow3, 1d), coefficients(linearTerms, balance, true));

            // the voltage target of bus 5: its voltage
            int target = structure.getNumber(AcEquationType.BUS_TARGET_V, 5);
            int voltage = structure.getPosition(equationSystem.getVariable(5, AcVariableType.BUS_V));
            assertEquals(Map.of(voltage, 1d), coefficients(linearTerms, target, true));

            // bus 5 is lost: OLF turns its terms off, and the table follows when the equations are paired again
            LfBus lost = lfNetwork.getBus(5);
            lost.setDisabled(true);
            lost.getBranches().forEach(branch -> branch.setDisabled(true));
            AcStateVectorUtil.initStateVector(lfNetwork, equationSystem, new UniformValueVoltageInitializer());
            fixed.updateDer();
            assertEquals(Map.of(voltage, 1d), coefficients(fixed.getStructure().getLinearTerms(), target, false));
            assertEquals(Map.of(flow1, -1d, flow3, 1d), coefficients(fixed.getStructure().getLinearTerms(), balance, true));
        }

        Network hvdcNetwork = hvdcInAcEmulation();
        LfNetwork hvdcLfNetwork = load(hvdcNetwork);
        EquationSystem<AcVariableType, AcEquationType> hvdcLfNetworkEquations = create(hvdcLfNetwork, true);
        try (FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed =
                     new FixedEquationSystemIndex<>(hvdcLfNetworkEquations, new AcVariablePairing(hvdcLfNetwork), FixedEquationSystemIndex.Scope.ALL)) {
            FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
            LfBus converterBus = hvdcLfNetwork.getHvdcs().get(0).getBus1();
            int balance = structure.getNumber(AcEquationType.BUS_TARGET_P, converterBus.getNum());
            assertTrue(structure.isMixed(balance));
            assertTrue(coefficients(structure.getLinearTerms(), balance, true).isEmpty());
        }
    }

    /** The coefficient, by position, of the linear terms of an equation that are active, or of those that are not. */
    private static Map<Integer, Double> coefficients(FixedStructure.LinearTerms<AcVariableType, AcEquationType> linearTerms, int equation, boolean active) {
        Map<Integer, Double> coefficients = new HashMap<>();
        for (int i = linearTerms.start[equation]; i < linearTerms.start[equation + 1]; i++) {
            if (linearTerms.active[i] == active) {
                coefficients.put(linearTerms.position[i], linearTerms.coefficient[i]);
            }
        }
        return coefficients;
    }

    /** The HVDC line of the factory follows the angles of its two ends only with this extension. */
    private static Network hvdcInAcEmulation() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0f)
                .withEnabled(true)
                .add();
        return network;
    }
}
