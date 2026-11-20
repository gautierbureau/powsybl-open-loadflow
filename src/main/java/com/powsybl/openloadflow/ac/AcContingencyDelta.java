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
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfContingency;
import com.powsybl.openloadflow.network.LfElement;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfShunt;
import com.powsybl.openloadflow.network.LfSynchronousNetwork;
import com.powsybl.openloadflow.network.PiModel;
import com.powsybl.openloadflow.network.PiModelArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
 *     solve, and the part of it that was spread ({@code preDistributedActivePower});</li>
 * <li>the generators and the loads whose target, or whose anchor ({@code initialTargetP}: the target the slack
 *     distribution starts from again when it resets), changed: the lost ones, and those the spreading of the loss
 *     moved. Taken after that spreading, they are the starting point of the post-contingency solve, so a device needs
 *     no distribution of its own before it;</li>
 * <li>the slack buses and the reference bus of the main synchronous component: OLF picks others when the contingency
 *     isolates them;</li>
 * <li>the buses whose share of a reactive power distribution ({@code remoteControlReactivePercent}, the key of their
 *     {@code DISTR_Q} terms) changed, with their new share: a controller lost, or cut off, leaves the others a larger
 *     one;</li>
 * <li>the shunts whose admittance changed, with their new one, and the branches whose ratio or phase shift changed,
 *     with their new tap (-1 for a branch without a table of taps), ratio and phase shift.</li>
 * </ul>
 *
 * <p>The delta is taken between the state recorded in a {@link Baseline} before the contingency and the state after
 * {@link LfContingency#apply}, on the same structure. It is OLF's own result, not a replay of it.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public record AcContingencyDelta(String contingencyId, double activePowerLoss, double preDistributedActivePower,
                                 int[] disabledBuses, int[] disabledBranches, int[] side1OpenBranches, int[] side2OpenBranches,
                                 int[] disabledHvdcs, int[] slots, int[] modes, double[] targets,
                                 String[] generatorIds, double[] generatorTargetP, double[] generatorInitialTargetP,
                                 String[] loadIds, double[] loadTargetP,
                                 int[] slackBuses, int referenceBus,
                                 int[] reactivePercentBuses, double[] reactivePercents,
                                 int[] shunts, double[] shuntG, double[] shuntB,
                                 int[] tapBranches, int[] tapPositions, double[] tapR1, double[] tapA1) {

    public AcContingencyDelta {
        Objects.requireNonNull(contingencyId);
        if (slots.length != modes.length || slots.length != targets.length) {
            throw new IllegalArgumentException("One mode and one target by slot");
        }
        if (generatorIds.length != generatorTargetP.length || generatorIds.length != generatorInitialTargetP.length
                || loadIds.length != loadTargetP.length) {
            throw new IllegalArgumentException("One target by generator and by load");
        }
    }

    /** The mode and the target of each slot in the base state, on the structure the deltas are taken on. */
    public static final class Baseline {

        private final FixedStructure<AcVariableType, AcEquationType> structure;
        private final int[] modes;
        private final double[] targets;
        private final List<LfGenerator> generators;
        private final double[] generatorTargetP;
        private final double[] generatorInitialTargetP;
        private final List<LfLoad> loads;
        private final double[] loadTargetP;
        private final double[] reactivePercent;
        private final double[] shuntGBase;
        private final double[] shuntBBase;
        private final double[] r1Base;
        private final double[] a1Base;

        private Baseline(FixedStructure<AcVariableType, AcEquationType> structure, int[] modes, double[] targets, LfNetwork network) {
            this.structure = structure;
            this.modes = modes;
            this.targets = targets;
            generators = new ArrayList<>();
            loads = new ArrayList<>();
            for (LfBus bus : network.getBuses()) {
                generators.addAll(bus.getGenerators());
                loads.addAll(bus.getLoads());
            }
            generatorTargetP = generators.stream().mapToDouble(LfGenerator::getTargetP).toArray();
            generatorInitialTargetP = generators.stream().mapToDouble(LfGenerator::getInitialTargetP).toArray();
            loadTargetP = loads.stream().mapToDouble(LfLoad::getTargetP).toArray();
            reactivePercent = network.getBuses().stream().mapToDouble(LfBus::getRemoteControlReactivePercent).toArray();
            shuntGBase = network.getShunts().stream().mapToDouble(LfShunt::getG).toArray();
            shuntBBase = network.getShunts().stream().mapToDouble(LfShunt::getB).toArray();
            r1Base = network.getBranches().stream().mapToDouble(b -> b.getPiModel().getR1()).toArray();
            a1Base = network.getBranches().stream().mapToDouble(b -> b.getPiModel().getA1()).toArray();
        }

        public int[] getModes() {
            return modes.clone();
        }

        public double[] getTargets() {
            return targets.clone();
        }
    }

    private static FixedEquationSystem<AcVariableType, AcEquationType> fixed(AcLoadFlowContext context) {
        AcJacobianMatrix j = (AcJacobianMatrix) context.getJacobianMatrix();
        if (!j.isFixed()) {
            throw new IllegalStateException("A contingency is recorded in the terms of the fixed structure: the AC Jacobian structure must be "
                    + AcJacobianStructure.FIXED);
        }
        return j.getFixedJacobian().getFixedEquationSystem();
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
        return new Baseline(structure, modes, targetsBySlot(fixed, structure, targetVector), context.getNetwork());
    }

    /**
     * The delta of a contingency OLF has just applied, against the base state; null when it cannot be expressed on
     * the base structure: the contingency made OLF create an equation the structure did not hold, and compile it
     * again.
     */
    public static AcContingencyDelta record(LfContingency contingency, AcLoadFlowContext context, Baseline baseline) {
        return record(contingency, context, baseline, 0);
    }

    /**
     * The same, once the loss has also been spread over the participants of the slack distribution
     * ({@code ContingencyActivePowerLossDistribution}), which spread {@code preDistributedActivePower} of it.
     */
    public static AcContingencyDelta record(LfContingency contingency, AcLoadFlowContext context, Baseline baseline,
                                            double preDistributedActivePower) {
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
        List<String> generatorIds = new ArrayList<>();
        List<Double> generatorTargets = new ArrayList<>();
        List<Double> generatorAnchors = new ArrayList<>();
        for (int g = 0; g < baseline.generators.size(); g++) {
            LfGenerator generator = baseline.generators.get(g);
            if (differs(generator.getTargetP(), baseline.generatorTargetP[g]) || differs(generator.getInitialTargetP(), baseline.generatorInitialTargetP[g])) {
                generatorIds.add(generator.getId());
                generatorTargets.add(generator.getTargetP());
                generatorAnchors.add(generator.getInitialTargetP());
            }
        }
        List<String> loadIds = new ArrayList<>();
        List<Double> loadTargets = new ArrayList<>();
        for (int l = 0; l < baseline.loads.size(); l++) {
            LfLoad load = baseline.loads.get(l);
            if (differs(load.getTargetP(), baseline.loadTargetP[l])) {
                loadIds.add(load.getId());
                loadTargets.add(load.getTargetP());
            }
        }
        LfNetwork network = context.getNetwork();
        LfSynchronousNetwork main = network.getSynchronousNetworks().getFirst();
        int[] slackBuses = main.getSlackBuses().stream().mapToInt(LfBus::getNum).toArray();
        int referenceBus = main.getReferenceBus() != null ? main.getReferenceBus().getNum() : -1;
        List<Integer> percentBuses = new ArrayList<>();
        List<Double> percents = new ArrayList<>();
        for (LfBus bus : network.getBuses()) {
            if (differs(bus.getRemoteControlReactivePercent(), baseline.reactivePercent[bus.getNum()])) {
                percentBuses.add(bus.getNum());
                percents.add(bus.getRemoteControlReactivePercent());
            }
        }
        List<Integer> shunts = new ArrayList<>();
        List<Double> shuntG = new ArrayList<>();
        List<Double> shuntB = new ArrayList<>();
        for (LfShunt shunt : network.getShunts()) {
            if (differs(shunt.getG(), baseline.shuntGBase[shunt.getNum()]) || differs(shunt.getB(), baseline.shuntBBase[shunt.getNum()])) {
                shunts.add(shunt.getNum());
                shuntG.add(shunt.getG());
                shuntB.add(shunt.getB());
            }
        }
        List<Integer> tapBranches = new ArrayList<>();
        List<Integer> taps = new ArrayList<>();
        List<Double> r1 = new ArrayList<>();
        List<Double> a1 = new ArrayList<>();
        for (LfBranch branch : network.getBranches()) {
            PiModel piModel = branch.getPiModel();
            if (differs(piModel.getR1(), baseline.r1Base[branch.getNum()]) || differs(piModel.getA1(), baseline.a1Base[branch.getNum()])) {
                tapBranches.add(branch.getNum());
                // a branch whose ratio is not a tap of a table has no position
                taps.add(piModel instanceof PiModelArray ? piModel.getTapPosition() : -1);
                r1.add(piModel.getR1());
                a1.add(piModel.getA1());
            }
        }
        var disabledNetwork = contingency.getDisabledNetwork();
        return new AcContingencyDelta(contingency.getId(), contingency.getActivePowerLoss(), preDistributedActivePower,
                nums(disabledNetwork.getBuses(), LfElement::getNum),
                branches(disabledNetwork.getBranchesStatus(), DisabledBranchStatus.BOTH_SIDES),
                branches(disabledNetwork.getBranchesStatus(), DisabledBranchStatus.SIDE_1),
                branches(disabledNetwork.getBranchesStatus(), DisabledBranchStatus.SIDE_2),
                nums(disabledNetwork.getHvdcs(), LfElement::getNum),
                slots, deltaModes, deltaTargets,
                generatorIds.toArray(new String[0]), unbox(generatorTargets), unbox(generatorAnchors),
                loadIds.toArray(new String[0]), unbox(loadTargets),
                slackBuses, referenceBus,
                percentBuses.stream().mapToInt(Integer::intValue).toArray(), unbox(percents),
                shunts.stream().mapToInt(Integer::intValue).toArray(), unbox(shuntG), unbox(shuntB),
                tapBranches.stream().mapToInt(Integer::intValue).toArray(), taps.stream().mapToInt(Integer::intValue).toArray(),
                unbox(r1), unbox(a1));
    }

    private static boolean differs(double a, double b) {
        return Double.doubleToLongBits(a) != Double.doubleToLongBits(b);
    }

    private static double[] unbox(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
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
