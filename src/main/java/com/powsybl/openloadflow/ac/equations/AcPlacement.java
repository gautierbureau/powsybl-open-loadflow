/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.fixed.FixedEquationSystem;
import com.powsybl.openloadflow.equations.fixed.Placement;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfElement;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.VoltageControl;

import java.util.function.Consumer;

/**
 * The pairing of the AC equations for a {@link FixedEquationSystem}: a balance with the angle or the magnitude of its bus, a control equation with the
 * variable it frees or fixes. The equations OLF toggles in pairs share a variable, so they share a slot.
 *
 * <p>The equations left without a pairing here find their slot by themselves: the reactive balance of a bus whose
 * voltage is held by a remote generator, a transformer or a shunt takes the slot that control frees, and so does
 * {@code BRANCH_TARGET_Q1} and {@code BRANCH_TARGET_Q2}.
 *
 * <p>With a network, the controls of the network say beforehand which slots these are: the candidates. The balance
 * of a controlled bus may go to the slot of any controller of its control, or of the controls merged with it; the
 * equation of a controlled branch to the slot of its controllers. The pattern then holds them from the start.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class AcPlacement implements Placement<AcVariableType, AcEquationType> {

    /** Without a network: no candidate is known, and the pattern grows as the placements are met. */
    public static final AcPlacement INSTANCE = new AcPlacement(null);

    private final LfNetwork network;

    public AcPlacement(LfNetwork network) {
        this.network = network;
    }

    @Override
    public AcVariableType variableType(AcEquationType equationType) {
        return switch (equationType) {
            case BUS_TARGET_P, BUS_TARGET_PHI, BUS_DISTR_SLACK_P -> AcVariableType.BUS_PHI;
            case BUS_TARGET_Q, BUS_TARGET_V, DISTR_Q -> AcVariableType.BUS_V;
            case SHUNT_TARGET_B, DISTR_SHUNT_B -> AcVariableType.SHUNT_B;
            case BRANCH_TARGET_ALPHA1, BRANCH_TARGET_P1, BRANCH_TARGET_P2 -> AcVariableType.BRANCH_ALPHA1;
            case BRANCH_TARGET_RHO1, DISTR_RHO -> AcVariableType.BRANCH_RHO1;
            case DUMMY_TARGET_P, ZERO_PHI -> AcVariableType.DUMMY_P;
            case DUMMY_TARGET_Q, ZERO_V -> AcVariableType.DUMMY_Q;
            default -> null;
        };
    }

    @Override
    public boolean fixesVariable(AcEquationType equationType) {
        return switch (equationType) {
            case BUS_TARGET_P, BUS_TARGET_Q, BRANCH_TARGET_P1, BRANCH_TARGET_P2, ZERO_PHI, ZERO_V -> false;
            default -> true;
        };
    }

    /**
     * Angles, phase shifts and active flows on one side, voltages, ratios, susceptances and reactive flows on the
     * other: an active power equation barely depends on a voltage, and not at all on a flat start without losses.
     */
    @Override
    public int kind(AcVariableType variableType) {
        return switch (variableType) {
            case BUS_PHI, BRANCH_ALPHA1, DUMMY_P -> 0;
            case BUS_V, BRANCH_RHO1, SHUNT_B, DUMMY_Q -> 1;
            default -> 2 + variableType.ordinal();
        };
    }

    @Override
    public void forEachCandidate(CandidateConsumer<AcVariableType, AcEquationType> consumer) {
        if (network == null) {
            return;
        }
        for (LfBus bus : network.getBuses()) {
            for (VoltageControl<?> voltageControl : bus.getVoltageControls()) {
                if (voltageControl.getControlledBus() == bus) {
                    forEachController(voltageControl, controller -> {
                        AcVariableType variableType = controlVariableType(controller);
                        if (variableType != null) {
                            consumer.accept(AcEquationType.BUS_TARGET_Q, bus.getNum(), variableType, controller.getNum());
                        }
                    });
                }
            }
        }
        for (LfBranch branch : network.getBranches()) {
            branch.getGeneratorReactivePowerControl().ifPresent(control -> control.getControllerBuses().forEach(controller ->
                    acceptBothSides(consumer, AcEquationType.BRANCH_TARGET_Q1, AcEquationType.BRANCH_TARGET_Q2,
                            control.getControlledBranch(), AcVariableType.BUS_V, controller)));
            branch.getTransformerReactivePowerControl().ifPresent(control ->
                    acceptBothSides(consumer, AcEquationType.BRANCH_TARGET_Q1, AcEquationType.BRANCH_TARGET_Q2,
                            control.getControlledBranch(), AcVariableType.BRANCH_RHO1, control.getControllerBranch()));
            branch.getPhaseControl().ifPresent(control ->
                    acceptBothSides(consumer, AcEquationType.BRANCH_TARGET_P1, AcEquationType.BRANCH_TARGET_P2,
                            control.getControlledBranch(), AcVariableType.BRANCH_ALPHA1, control.getControllerBranch()));
        }
    }

    /** The controllers of a voltage control, and those of the controls it is merged with through zero impedance branches. */
    private static <T extends LfElement> void forEachController(VoltageControl<T> voltageControl, Consumer<LfElement> consumer) {
        voltageControl.getControllerElements().forEach(consumer);
        VoltageControl<T> main = voltageControl.getMainVoltageControl();
        if (main != null) {
            main.getMergedControllerElements().forEach(consumer);
        }
    }

    /** The variable a controller frees when it controls: the voltage of a generator bus, a tap, a susceptance. */
    private static AcVariableType controlVariableType(LfElement controller) {
        return switch (controller.getType()) {
            case BUS -> AcVariableType.BUS_V;
            case BRANCH -> AcVariableType.BRANCH_RHO1;
            case SHUNT_COMPENSATOR -> AcVariableType.SHUNT_B;
            default -> null;
        };
    }

    private static void acceptBothSides(CandidateConsumer<AcVariableType, AcEquationType> consumer, AcEquationType side1,
                                        AcEquationType side2, LfBranch controlled, AcVariableType variableType, LfElement controller) {
        consumer.accept(side1, controlled.getNum(), variableType, controller.getNum());
        consumer.accept(side2, controlled.getNum(), variableType, controller.getNum());
    }
}
