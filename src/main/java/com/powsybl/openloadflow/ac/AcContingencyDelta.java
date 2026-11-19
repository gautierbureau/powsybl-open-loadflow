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
import com.powsybl.openloadflow.equations.fixed.FixedEquationSystemIndex;
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
 * <li>the positions whose mode ({@link FixedEquationSystemIndex#getModes()}) or target changed from the base state, with their
 *     new mode and target: the target of the equation a position holds, as the solve reads it from the target vector. A
 *     bus the contingency islands has its positions holding nothing; a bus that loses its last voltage controlling
 *     generator has its voltage position holding its reactive balance; a lost generator or load moves the target of the
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
                                 int[] disabledHvdcs, int[] positions, int[] modes, double[] targets) {

    public AcContingencyDelta {
        Objects.requireNonNull(contingencyId);
        if (positions.length != modes.length || positions.length != targets.length) {
            throw new IllegalArgumentException("One mode and one target by position");
        }
    }

    /** The mode and the target of each position in the base state, on the structure the deltas are taken on. */
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

    private static FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed(AcLoadFlowContext context) {
        return ((AcFixedJacobianMatrix) context.getJacobianMatrix()).getFixedJacobian().getFixedEquationSystemIndex();
    }

    /**
     * The target of each position: the one of the equation it holds, at its column of the target vector; NaN for a position
     * that holds none, whose variable keeps its value.
     */
    private static double[] targetsByPosition(FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed,
                                          FixedStructure<AcVariableType, AcEquationType> structure, double[] targetVector) {
        double[] targets = new double[structure.getVariableCount()];
        for (int position = 0; position < targets.length; position++) {
            int equation = fixed.getEquationOfPosition(position);
            targets[position] = equation < 0 ? Double.NaN : targetVector[structure.getColumnInEquationSystem(equation)];
        }
        return targets;
    }

    /** Records the modes and the targets by position of the current state: the base the deltas are taken against. */
    public static Baseline baseline(AcLoadFlowContext context) {
        FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed = fixed(context);
        double[] targetVector = context.getTargetVector().getArray();
        int[] modes = fixed.getModes().clone();
        FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
        return new Baseline(structure, modes, targetsByPosition(fixed, structure, targetVector));
    }

    /**
     * The delta of a contingency OLF has just applied, against the base state; null when it cannot be expressed on
     * the base structure: the contingency made OLF create an equation the structure did not hold, and compile it
     * again.
     */
    public static AcContingencyDelta record(LfContingency contingency, AcLoadFlowContext context, Baseline baseline) {
        Objects.requireNonNull(contingency);
        FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed = fixed(context);
        double[] targetVector = context.getTargetVector().getArray();
        int[] modes = fixed.getModes();
        FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
        if (structure != baseline.structure) {
            return null;
        }
        double[] targets = targetsByPosition(fixed, structure, targetVector);
        int count = 0;
        int[] changed = new int[modes.length];
        for (int position = 0; position < modes.length; position++) {
            if (modes[position] != baseline.modes[position]
                    || Double.doubleToLongBits(targets[position]) != Double.doubleToLongBits(baseline.targets[position])) {
                changed[count++] = position;
            }
        }
        int[] positions = Arrays.copyOf(changed, count);
        int[] deltaModes = new int[count];
        double[] deltaTargets = new double[count];
        for (int i = 0; i < count; i++) {
            deltaModes[i] = modes[positions[i]];
            deltaTargets[i] = targets[positions[i]];
        }
        var disabledNetwork = contingency.getDisabledNetwork();
        return new AcContingencyDelta(contingency.getId(), contingency.getActivePowerLoss(),
                nums(disabledNetwork.getBuses(), LfElement::getNum),
                branches(disabledNetwork.getBranchesStatus(), DisabledBranchStatus.BOTH_SIDES),
                branches(disabledNetwork.getBranchesStatus(), DisabledBranchStatus.SIDE_1),
                branches(disabledNetwork.getBranchesStatus(), DisabledBranchStatus.SIDE_2),
                nums(disabledNetwork.getHvdcs(), LfElement::getNum),
                positions, deltaModes, deltaTargets);
    }

    private static <T> int[] nums(Collection<T> elements, ToIntFunction<T> num) {
        return elements.stream().mapToInt(num).sorted().toArray();
    }

    private static int[] branches(java.util.Map<? extends LfElement, DisabledBranchStatus> status, DisabledBranchStatus kind) {
        return status.entrySet().stream().filter(e -> e.getValue() == kind).mapToInt(e -> e.getKey().getNum()).sorted().toArray();
    }

    /** Writes the delta over the modes and the targets of the base state: those of the contingency. */
    public void applyTo(int[] baseModes, double[] baseTargets) {
        for (int i = 0; i < positions.length; i++) {
            baseModes[positions[i]] = modes[i];
            baseTargets[positions[i]] = targets[i];
        }
    }
}
