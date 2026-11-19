/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.fixed.FixedEquationSystem;
import com.powsybl.openloadflow.equations.fixed.FixedStructure;
import com.powsybl.openloadflow.network.DisabledBranchStatus;
import com.powsybl.openloadflow.network.LfContingency;
import com.powsybl.openloadflow.network.LfElement;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * What a contingency changes, once OLF has applied it, in the terms of the fixed equation system: the data a device
 * installs for the scenario of the contingency, instead of working it out by replaying OLF's rules.
 *
 * <ul>
 * <li>the slots whose mode ({@link FixedEquationSystem#getModes()}) or target changed from the base state, with their
 *     new mode and target: the target of the equation a slot holds, as the solve reads it from the target vector. A
 *     bus the contingency islands has its slots holding nothing; a bus that loses its last voltage controlling
 *     generator has its voltage slot holding its reactive balance; a lost generator or load moves the target of the
 *     active and reactive balances of its bus;</li>
 * <li>the elements the contingency disables, by number: buses, branches open at both sides or at one, HVDC lines;</li>
 * <li>the active power the contingency takes out of the balance, which the slack distribution spreads before the
 *     solve.</li>
 * </ul>
 *
 * <p>The delta is taken between the state recorded in a {@link Baseline} before the contingency and the state after
 * {@link LfContingency#apply}, on the same structure. It is OLF's own result, not a replay of it.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public record AcContingencyDelta(String contingencyId, double activePowerLoss,
                                 int[] disabledBuses, int[] disabledBranches, int[] side1OpenBranches, int[] side2OpenBranches,
                                 int[] disabledHvdcs, int[] slots, int[] modes, double[] targets) {

    public AcContingencyDelta {
        Objects.requireNonNull(contingencyId);
        if (slots.length != modes.length || slots.length != targets.length) {
            throw new IllegalArgumentException("One mode and one target by slot");
        }
    }

    /** The mode and the target of each slot in the base state, on the structure the deltas are taken on. */
    public static final class Baseline {

        private final FixedStructure<AcVariableType, AcEquationType> structure;
        private final int[] modes;
        private final double[] targets;

        private Baseline(FixedStructure<AcVariableType, AcEquationType> structure, int[] modes, double[] targets) {
            this.structure = structure;
            this.modes = modes;
            this.targets = targets;
        }

        public int[] getModes() {
            return modes.clone();
        }

        public double[] getTargets() {
            return targets.clone();
        }
    }

    private static FixedEquationSystem<AcVariableType, AcEquationType> fixed(AcLoadFlowContext context) {
        return ((AcJacobianMatrix) context.getJacobianMatrix()).getFixedJacobian().getFixedEquationSystem();
    }

    /**
     * The target of each slot: the one of the equation it holds, at its column of the target vector; NaN for a slot
     * that holds none, whose variable keeps its value.
     */
    private static double[] targetsBySlot(FixedEquationSystem<AcVariableType, AcEquationType> fixed,
                                          FixedStructure<AcVariableType, AcEquationType> structure, double[] targetVector) {
        double[] targets = new double[structure.getSlotCount()];
        for (int slot = 0; slot < targets.length; slot++) {
            int equation = fixed.getEquationOfSlot(slot);
            targets[slot] = equation < 0 ? Double.NaN : targetVector[structure.getColumnInEquationSystem(equation)];
        }
        return targets;
    }

    /** Records the modes and the targets by slot of the current state: the base the deltas are taken against. */
    public static Baseline baseline(AcLoadFlowContext context) {
        FixedEquationSystem<AcVariableType, AcEquationType> fixed = fixed(context);
        double[] targetVector = context.getTargetVector().getArray();
        int[] modes = fixed.getModes().clone();
        FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
        return new Baseline(structure, modes, targetsBySlot(fixed, structure, targetVector));
    }

    /**
     * The delta of a contingency OLF has just applied, against the base state; null when it cannot be expressed on
     * the base structure: the contingency made OLF create an equation the structure did not hold, and compile it
     * again.
     */
    public static AcContingencyDelta record(LfContingency contingency, AcLoadFlowContext context, Baseline baseline) {
        Objects.requireNonNull(contingency);
        FixedEquationSystem<AcVariableType, AcEquationType> fixed = fixed(context);
        double[] targetVector = context.getTargetVector().getArray();
        int[] modes = fixed.getModes();
        FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
        if (structure != baseline.structure) {
            return null;
        }
        double[] targets = targetsBySlot(fixed, structure, targetVector);
        int count = 0;
        int[] changed = new int[modes.length];
        for (int slot = 0; slot < modes.length; slot++) {
            if (modes[slot] != baseline.modes[slot]
                    || Double.doubleToLongBits(targets[slot]) != Double.doubleToLongBits(baseline.targets[slot])) {
                changed[count++] = slot;
            }
        }
        int[] slots = Arrays.copyOf(changed, count);
        int[] deltaModes = new int[count];
        double[] deltaTargets = new double[count];
        for (int i = 0; i < count; i++) {
            deltaModes[i] = modes[slots[i]];
            deltaTargets[i] = targets[slots[i]];
        }
        var disabledNetwork = contingency.getDisabledNetwork();
        return new AcContingencyDelta(contingency.getId(), contingency.getActivePowerLoss(),
                nums(disabledNetwork.getBuses(), LfElement::getNum),
                branches(disabledNetwork.getBranchesStatus(), DisabledBranchStatus.BOTH_SIDES),
                branches(disabledNetwork.getBranchesStatus(), DisabledBranchStatus.SIDE_1),
                branches(disabledNetwork.getBranchesStatus(), DisabledBranchStatus.SIDE_2),
                nums(disabledNetwork.getHvdcs(), LfElement::getNum),
                slots, deltaModes, deltaTargets);
    }

    private static <T> int[] nums(Collection<T> elements, ToIntFunction<T> num) {
        return elements.stream().mapToInt(num).sorted().toArray();
    }

    private static int[] branches(java.util.Map<? extends LfElement, DisabledBranchStatus> status, DisabledBranchStatus kind) {
        return status.entrySet().stream().filter(e -> e.getValue() == kind).mapToInt(e -> e.getKey().getNum()).sorted().toArray();
    }

    /** Writes the delta over the modes and the targets of the base state: those of the contingency. */
    public void applyTo(int[] baseModes, double[] baseTargets) {
        for (int i = 0; i < slots.length; i++) {
            baseModes[slots[i]] = modes[i];
            baseTargets[slots[i]] = targets[i];
        }
    }
}
