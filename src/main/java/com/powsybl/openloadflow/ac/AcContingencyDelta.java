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
import com.powsybl.openloadflow.network.GeneratorVoltageControl;
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
import java.util.Map;
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
 *     with their new tap, ratio and phase shift (OLF's rules change only the position of a table of taps).</li>
 * </ul>
 *
 * <p>The delta is taken between the state recorded in a {@link Baseline} before the contingency and the state after
 * {@link LfContingency#apply}, on the same structure. It is OLF's own result, not a replay of it.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public record AcContingencyDelta(String contingencyId, double activePowerLoss, double preDistributedActivePower,
                                 int[] disabledBuses, int[] disabledBranches, int[] side1OpenBranches, int[] side2OpenBranches,
                                 int[] disabledHvdcs, boolean onBaseStructure, int[] positions, int[] modes, double[] targets,
                                 int[] generators, String[] generatorIds, double[] generatorTargetP, double[] generatorInitialTargetP,
                                 boolean[] generatorDisabled, boolean[] generatorParticipating, LfGenerator.GeneratorControlType[] generatorControlTypes,
                                 int[] loads, String[] loadIds, double[] loadTargetP, double[] loadTargetQ, double[] loadAbsVariableTargetP,
                                 String[][] loadDisabledOriginalIds,
                                 int[] controlBuses, boolean[] busVoltageControlEnabled, boolean[] busReactivePowerControlEnabled,
                                 boolean[] busGenerationTargetQFrozen, double[] busFrozenGenerationTargetQ,
                                 int[] reactiveTargetBuses,
                                 int[] excludedSlackBuses, int[] slackBuses, int referenceBus,
                                 int[] reactivePercentBuses, double[] reactivePercents,
                                 int[] shunts, double[] shuntG, double[] shuntB,
                                 int[] tapBranches, int[] tapPositions, double[] tapR1, double[] tapA1) {

    public AcContingencyDelta {
        Objects.requireNonNull(contingencyId);
        if (positions.length != modes.length || positions.length != targets.length) {
            throw new IllegalArgumentException("One mode and one target by position");
        }
        int g = generators.length;
        if (generatorIds.length != g || generatorTargetP.length != g || generatorInitialTargetP.length != g
                || generatorDisabled.length != g || generatorParticipating.length != g || generatorControlTypes.length != g) {
            throw new IllegalArgumentException("One state by generator");
        }
        int l = loads.length;
        if (loadIds.length != l || loadTargetP.length != l || loadTargetQ.length != l || loadAbsVariableTargetP.length != l
                || loadDisabledOriginalIds.length != l) {
            throw new IllegalArgumentException("One state by load");
        }
        if (busVoltageControlEnabled.length != controlBuses.length || busReactivePowerControlEnabled.length != controlBuses.length
                || busGenerationTargetQFrozen.length != controlBuses.length || busFrozenGenerationTargetQ.length != controlBuses.length) {
            throw new IllegalArgumentException("One control state by bus");
        }
    }

    /** The mode and the target of each position in the base state, on the structure the deltas are taken on. */
    public static final class Baseline {

        private final FixedStructure<AcVariableType, AcEquationType> structure;
        private final int[] modes;
        private final double[] targets;
        private final List<LfGenerator> generators;
        /** For each bus, the number of its first generator and of its first load. */
        private final int[] firstGenerator;
        private final int[] firstLoad;
        private final double[] generatorTargetP;
        private final double[] generatorInitialTargetP;
        private final boolean[] generatorDisabled;
        private final boolean[] generatorParticipating;
        private final LfGenerator.GeneratorControlType[] generatorControlTypes;
        private final List<LfLoad> loads;
        private final double[] loadTargetP;
        private final double[] loadTargetQ;
        private final double[] loadAbsVariableTargetP;
        private final int[] loadDisabledOriginalCount;
        private final boolean[] busVoltageControlEnabled;
        private final boolean[] busReactivePowerControlEnabled;
        private final boolean[] busGenerationTargetQFrozen;
        private final double[] busFrozenGenerationTargetQ;
        private final double[] reactivePercent;
        private final int[] excludedSlackBuses;
        private final double[] shuntGBase;
        private final double[] shuntBBase;
        /** The branches whose ratio and phase shift are from a table of taps: OLF's rules change only their position. */
        private final List<LfBranch> tapBranches;
        private final int[] tapPositionBase;
        /** For each branch, its index among {@link #tapBranches}, -1 when it has no table of taps. */
        private final int[] tapIndex;

        private Baseline(FixedStructure<AcVariableType, AcEquationType> structure, int[] modes, double[] targets, LfNetwork network) {
            this.structure = structure;
            this.modes = modes;
            this.targets = targets;
            generators = new ArrayList<>();
            loads = new ArrayList<>();
            firstGenerator = new int[network.getBuses().size()];
            firstLoad = new int[network.getBuses().size()];
            for (LfBus bus : network.getBuses()) {
                firstGenerator[bus.getNum()] = generators.size();
                firstLoad[bus.getNum()] = loads.size();
                generators.addAll(bus.getGenerators());
                loads.addAll(bus.getLoads());
            }
            generatorTargetP = generators.stream().mapToDouble(LfGenerator::getTargetP).toArray();
            generatorInitialTargetP = generators.stream().mapToDouble(LfGenerator::getInitialTargetP).toArray();
            generatorDisabled = new boolean[generators.size()];
            generatorParticipating = new boolean[generators.size()];
            generatorControlTypes = new LfGenerator.GeneratorControlType[generators.size()];
            for (int g = 0; g < generators.size(); g++) {
                generatorDisabled[g] = generators.get(g).isDisabled();
                generatorParticipating[g] = generators.get(g).isParticipating();
                generatorControlTypes[g] = generators.get(g).getGeneratorControlType();
            }
            loadTargetP = loads.stream().mapToDouble(LfLoad::getTargetP).toArray();
            loadTargetQ = loads.stream().mapToDouble(LfLoad::getTargetQ).toArray();
            loadAbsVariableTargetP = loads.stream().mapToDouble(LfLoad::getAbsVariableTargetP).toArray();
            loadDisabledOriginalCount = loads.stream().mapToInt(AcContingencyDelta::disabledOriginalCount).toArray();
            busVoltageControlEnabled = new boolean[network.getBuses().size()];
            busReactivePowerControlEnabled = new boolean[network.getBuses().size()];
            busGenerationTargetQFrozen = new boolean[network.getBuses().size()];
            busFrozenGenerationTargetQ = new double[network.getBuses().size()];
            for (LfBus bus : network.getBuses()) {
                busVoltageControlEnabled[bus.getNum()] = bus.isGeneratorVoltageControlEnabled();
                busReactivePowerControlEnabled[bus.getNum()] = bus.isGeneratorReactivePowerControlEnabled();
                busGenerationTargetQFrozen[bus.getNum()] = bus.isGenerationTargetQFrozen();
                busFrozenGenerationTargetQ[bus.getNum()] = frozenGenerationTargetQ(bus);
            }
            reactivePercent = network.getBuses().stream().mapToDouble(LfBus::getRemoteControlReactivePercent).toArray();
            excludedSlackBuses = excludedSlackBuses(network);
            shuntGBase = network.getShunts().stream().mapToDouble(LfShunt::getG).toArray();
            shuntBBase = network.getShunts().stream().mapToDouble(LfShunt::getB).toArray();
            tapBranches = network.getBranches().stream().filter(b -> b.getPiModel() instanceof PiModelArray).toList();
            tapPositionBase = tapBranches.stream().mapToInt(b -> b.getPiModel().getTapPosition()).toArray();
            tapIndex = new int[network.getBranches().size()];
            Arrays.fill(tapIndex, -1);
            for (int b = 0; b < tapBranches.size(); b++) {
                tapIndex[tapBranches.get(b).getNum()] = b;
            }
        }

        public int[] getModes() {
            return modes.clone();
        }

        /** The generator of the given number: bus by bus, in the order of each bus. */
        LfGenerator getGenerator(int num) {
            return generators.get(num);
        }

        /** The load of the given number: bus by bus, in the order of each bus. */
        LfLoad getLoad(int num) {
            return loads.get(num);
        }

        boolean hasExcludedSlackBuses(int[] buses) {
            return Arrays.equals(buses, excludedSlackBuses);
        }

        public double[] getTargets() {
            return targets.clone();
        }
    }

    private static FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed(AcLoadFlowContext context) {
        if (!(context.getJacobianMatrix() instanceof AcFixedJacobianMatrix fixedMatrix)) {
            throw new IllegalStateException("A contingency is recorded in the terms of the fixed structure: the AC Jacobian structure must be "
                    + AcJacobianStructure.FIXED);
        }
        return fixedMatrix.getFixedJacobian().getFixedEquationSystemIndex();
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
        return new Baseline(structure, modes, targetsByPosition(fixed, structure, targetVector), context.getNetwork());
    }

    /**
     * The delta of a contingency OLF has just applied, against the base state. Its positions are left empty, and
     * {@code onBaseStructure} false, when they cannot be expressed on the base structure: the contingency made OLF create
     * an equation the structure did not hold, and compile it again, or its equations cannot be paired. The rest of the
     * record, which {@link #install} writes, does not depend on the structure.
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
        LfNetwork network = context.getNetwork();
        return record(contingency, context, baseline, preDistributedActivePower, network.getBuses(), network.getBranches());
    }

    /**
     * The same, looking only at what the contingency touched, as a device's planning does: the buses and the branches
     * OLF's rules changed ({@code ModifiedElementsCollector}: the buses of its lost generators and loads, those it
     * disabled, those whose targets or controls the rules or the spreading of the loss changed, and the branches it
     * opened or whose tap changed), and the controller buses of the voltage and reactive power controls of these
     * buses, whose reactive keys the loss of a controller changes.
     */
    public static AcContingencyDelta record(LfContingency contingency, AcLoadFlowContext context, Baseline baseline,
                                            double preDistributedActivePower, Collection<LfBus> touchedBuses,
                                            Collection<LfBranch> touchedBranches) {
        Objects.requireNonNull(contingency);
        LfNetwork network = context.getNetwork();
        int[] scope = scope(network, touchedBuses);
        FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed = fixed(context);
        double[] targetVector = context.getTargetVector().getArray();
        // the positions, when the contingency's equations are on the base structure
        int[] modes = null;
        FixedStructure<AcVariableType, AcEquationType> structure = null;
        try {
            modes = fixed.getModes();
            structure = fixed.getStructure();
        } catch (com.powsybl.commons.PowsyblException e) {
            // they cannot be paired (a bus left with no voltage control and nothing to hold its reactive balance): the
            // solve of the contingency fails as it does when OLF applies it
        }
        // a contingency that moves the slack bus makes OLF compile the structure again: its positions are not on the base one
        boolean onBaseStructure = modes != null && structure == baseline.structure;
        int count = 0;
        int[] positions = new int[0];
        int[] deltaModes = new int[0];
        double[] deltaTargets = new double[0];
        if (onBaseStructure) {
            double[] targets = targetsByPosition(fixed, structure, targetVector);
            int[] changed = new int[modes.length];
            for (int position = 0; position < modes.length; position++) {
                if (modes[position] != baseline.modes[position]
                        || Double.doubleToLongBits(targets[position]) != Double.doubleToLongBits(baseline.targets[position])) {
                    changed[count++] = position;
                }
            }
            positions = Arrays.copyOf(changed, count);
            deltaModes = new int[count];
            deltaTargets = new double[count];
            for (int i = 0; i < count; i++) {
                deltaModes[i] = modes[positions[i]];
                deltaTargets[i] = targets[positions[i]];
            }
        }
        List<Integer> generatorNums = new ArrayList<>();
        List<String> generatorIds = new ArrayList<>();
        List<Double> generatorTargets = new ArrayList<>();
        List<Double> generatorAnchors = new ArrayList<>();
        for (int busNum : scope) {
            List<LfGenerator> busGenerators = network.getBus(busNum).getGenerators();
            for (int k = 0; k < busGenerators.size(); k++) {
                int g = baseline.firstGenerator[busNum] + k;
                LfGenerator generator = busGenerators.get(k);
                if (differs(generator.getTargetP(), baseline.generatorTargetP[g]) || differs(generator.getInitialTargetP(), baseline.generatorInitialTargetP[g])
                        || generator.isDisabled() != baseline.generatorDisabled[g] || generator.isParticipating() != baseline.generatorParticipating[g]
                        || generator.getGeneratorControlType() != baseline.generatorControlTypes[g]) {
                    generatorNums.add(g);
                    generatorIds.add(generator.getId());
                    generatorTargets.add(generator.getTargetP());
                    generatorAnchors.add(generator.getInitialTargetP());
                }
            }
        }
        boolean[] generatorDisabled = new boolean[generatorNums.size()];
        boolean[] generatorParticipating = new boolean[generatorNums.size()];
        LfGenerator.GeneratorControlType[] generatorControlTypes = new LfGenerator.GeneratorControlType[generatorNums.size()];
        for (int i = 0; i < generatorNums.size(); i++) {
            LfGenerator generator = baseline.generators.get(generatorNums.get(i));
            generatorDisabled[i] = generator.isDisabled();
            generatorParticipating[i] = generator.isParticipating();
            generatorControlTypes[i] = generator.getGeneratorControlType();
        }
        List<Integer> loadNums = new ArrayList<>();
        List<String> loadIds = new ArrayList<>();
        List<Double> loadTargets = new ArrayList<>();
        for (int busNum : scope) {
            List<LfLoad> busLoads = network.getBus(busNum).getLoads();
            for (int k = 0; k < busLoads.size(); k++) {
                int l = baseline.firstLoad[busNum] + k;
                LfLoad load = busLoads.get(k);
                if (differs(load.getTargetP(), baseline.loadTargetP[l]) || differs(load.getTargetQ(), baseline.loadTargetQ[l])
                        || differs(load.getAbsVariableTargetP(), baseline.loadAbsVariableTargetP[l])
                        || contingency.getLostLoads().containsKey(load) && disabledOriginalCount(load) != baseline.loadDisabledOriginalCount[l]) {
                    loadNums.add(l);
                    loadIds.add(load.getId());
                    loadTargets.add(load.getTargetP());
                }
            }
        }
        double[] loadTargetQ = new double[loadNums.size()];
        double[] loadAbsVariableTargetP = new double[loadNums.size()];
        String[][] loadDisabledOriginalIds = new String[loadNums.size()][];
        for (int i = 0; i < loadNums.size(); i++) {
            LfLoad load = baseline.loads.get(loadNums.get(i));
            loadTargetQ[i] = load.getTargetQ();
            loadAbsVariableTargetP[i] = load.getAbsVariableTargetP();
            loadDisabledOriginalIds[i] = disabledOriginalIds(load).toArray(new String[0]);
        }
        LfSynchronousNetwork main = network.getSynchronousNetworks().getFirst();
        int[] slackBuses = main.getSlackBuses().stream().mapToInt(LfBus::getNum).toArray();
        int[] excludedSlackBuses = excludedSlackBuses(network);
        int referenceBus = main.getReferenceBus() != null ? main.getReferenceBus().getNum() : -1;
        List<Integer> controlBuses = new ArrayList<>();
        for (int busNum : scope) {
            LfBus bus = network.getBus(busNum);
            if (bus.isGeneratorVoltageControlEnabled() != baseline.busVoltageControlEnabled[bus.getNum()]
                    || bus.isGeneratorReactivePowerControlEnabled() != baseline.busReactivePowerControlEnabled[bus.getNum()]
                    || bus.isGenerationTargetQFrozen() != baseline.busGenerationTargetQFrozen[bus.getNum()]
                    || differs(frozenGenerationTargetQ(bus), baseline.busFrozenGenerationTargetQ[bus.getNum()])) {
                controlBuses.add(bus.getNum());
            }
        }
        boolean[] busVoltageControlEnabled = new boolean[controlBuses.size()];
        boolean[] busReactivePowerControlEnabled = new boolean[controlBuses.size()];
        boolean[] busGenerationTargetQFrozen = new boolean[controlBuses.size()];
        double[] busFrozenGenerationTargetQ = new double[controlBuses.size()];
        for (int i = 0; i < controlBuses.size(); i++) {
            busGenerationTargetQFrozen[i] = network.getBus(controlBuses.get(i)).isGenerationTargetQFrozen();
            busFrozenGenerationTargetQ[i] = frozenGenerationTargetQ(network.getBus(controlBuses.get(i)));
            busVoltageControlEnabled[i] = network.getBus(controlBuses.get(i)).isGeneratorVoltageControlEnabled();
            busReactivePowerControlEnabled[i] = network.getBus(controlBuses.get(i)).isGeneratorReactivePowerControlEnabled();
        }
        List<Integer> percentBuses = new ArrayList<>();
        List<Double> percents = new ArrayList<>();
        for (int busNum : scope) {
            LfBus bus = network.getBus(busNum);
            if (differs(bus.getRemoteControlReactivePercent(), baseline.reactivePercent[bus.getNum()])) {
                percentBuses.add(bus.getNum());
                percents.add(bus.getRemoteControlReactivePercent());
            }
        }
        List<Integer> shunts = new ArrayList<>();
        List<Double> shuntG = new ArrayList<>();
        List<Double> shuntB = new ArrayList<>();
        for (LfShunt shunt : shuntsOf(network, scope)) {
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
        int[] touchedTapBranches = touchedBranches.stream().mapToInt(LfBranch::getNum)
                .filter(num -> baseline.tapIndex[num] >= 0).sorted().distinct().toArray();
        for (int num : touchedTapBranches) {
            int b = baseline.tapIndex[num];
            LfBranch branch = baseline.tapBranches.get(b);
            PiModel piModel = branch.getPiModel();
            if (piModel.getTapPosition() != baseline.tapPositionBase[b]) {
                tapBranches.add(branch.getNum());
                taps.add(piModel.getTapPosition());
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
                onBaseStructure, positions, deltaModes, deltaTargets,
                ints(generatorNums), generatorIds.toArray(new String[0]), unbox(generatorTargets), unbox(generatorAnchors),
                generatorDisabled, generatorParticipating, generatorControlTypes,
                ints(loadNums), loadIds.toArray(new String[0]), unbox(loadTargets), loadTargetQ, loadAbsVariableTargetP, loadDisabledOriginalIds,
                ints(controlBuses), busVoltageControlEnabled, busReactivePowerControlEnabled, busGenerationTargetQFrozen, busFrozenGenerationTargetQ,
                reactiveTargetBuses(contingency),
                excludedSlackBuses, slackBuses, referenceBus,
                percentBuses.stream().mapToInt(Integer::intValue).toArray(), unbox(percents),
                shunts.stream().mapToInt(Integer::intValue).toArray(), unbox(shuntG), unbox(shuntB),
                tapBranches.stream().mapToInt(Integer::intValue).toArray(), taps.stream().mapToInt(Integer::intValue).toArray(),
                unbox(r1), unbox(a1));
    }

    /**
     * The buses a record looks at, in increasing order: the touched ones and the controller buses of their voltage and
     * reactive power controls.
     */
    private static int[] scope(LfNetwork network, Collection<LfBus> touchedBuses) {
        boolean[] in = new boolean[network.getBuses().size()];
        for (LfBus bus : touchedBuses) {
            in[bus.getNum()] = true;
            bus.getGeneratorVoltageControl().ifPresent(vc -> vc.<GeneratorVoltageControl>getMainVoltageControl()
                    .getMergedControllerElements().forEach(controller -> in[controller.getNum()] = true));
            bus.getGeneratorReactivePowerControl().ifPresent(rpc -> rpc.getControllerBuses()
                    .forEach(controller -> in[controller.getNum()] = true));
        }
        int count = 0;
        for (boolean b : in) {
            if (b) {
                count++;
            }
        }
        int[] scope = new int[count];
        int i = 0;
        for (int num = 0; num < in.length; num++) {
            if (in[num]) {
                scope[i++] = num;
            }
        }
        return scope;
    }

    /** The shunts of the given buses, in increasing number. */
    private static List<LfShunt> shuntsOf(LfNetwork network, int[] scope) {
        List<LfShunt> shunts = new ArrayList<>();
        for (int busNum : scope) {
            LfBus bus = network.getBus(busNum);
            bus.getShunt().ifPresent(shunts::add);
            bus.getControllerShunt().ifPresent(shunts::add);
            bus.getSvcShunt().ifPresent(shunts::add);
        }
        shunts.sort(java.util.Comparator.comparingInt(LfShunt::getNum));
        return shunts;
    }

    private static boolean differs(double a, double b) {
        return Double.doubleToLongBits(a) != Double.doubleToLongBits(b);
    }

    private static double[] unbox(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** The buses the slack bus may not be chosen among: those a contingency cuts off with the slack bus. */
    private static int[] excludedSlackBuses(LfNetwork network) {
        return network.getSynchronousNetworks().getFirst().getExcludedSlackBuses().stream().mapToInt(LfBus::getNum).sorted().toArray();
    }

    /**
     * The buses whose reactive target OLF computes again from their generators, once they are applied
     * ({@code LfContingency.processLostGenerators}): those of a lost generator that keep none controlling their voltage.
     * The target is computed when it is next read; a record does not read it, as OLF has not yet.
     */
    private static int[] reactiveTargetBuses(LfContingency contingency) {
        return contingency.getLostGenerators().stream().map(LfGenerator::getBus).distinct()
                .filter(bus -> bus.getGenerators().stream().noneMatch(g -> g.getGeneratorControlType() == LfGenerator.GeneratorControlType.VOLTAGE))
                .mapToInt(LfBus::getNum).sorted().toArray();
    }

    /** The reactive target a bus has frozen, NaN when it has none. */
    private static double frozenGenerationTargetQ(LfBus bus) {
        return bus.isGenerationTargetQFrozen() ? bus.getGenerationTargetQ() : Double.NaN;
    }

    /** The original loads of a load that are disabled, in the order of its original ids. */
    private static int disabledOriginalCount(LfLoad load) {
        int count = 0;
        for (boolean disabled : load.getOriginalLoadsDisablingStatus().values()) {
            if (disabled) {
                count++;
            }
        }
        return count;
    }

    private static List<String> disabledOriginalIds(LfLoad load) {
        return load.getOriginalLoadsDisablingStatus().entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
    }

    private static int[] ints(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static <T> int[] nums(Collection<T> elements, ToIntFunction<T> num) {
        return elements.stream().mapToInt(num).sorted().toArray();
    }

    private static int[] branches(java.util.Map<? extends LfElement, DisabledBranchStatus> status, DisabledBranchStatus kind) {
        return status.entrySet().stream().filter(e -> e.getValue() == kind).mapToInt(e -> e.getKey().getNum()).sorted().toArray();
    }

    /**
     * Writes the contingency on a network in its base state, from this record alone, without OLF's rules: the network
     * is then as {@link LfContingency#apply} and the spreading of the loss leave it. The generators and the loads are
     * numbered in the order of the baseline: bus by bus, in the order of each bus.
     */
    public void install(LfNetwork network, Baseline baseline) {
        AcContingencyRecords.Builder records = new AcContingencyRecords.Builder(baseline);
        records.add(this);
        records.build().install(0, network, baseline);
    }

    /** Writes the delta over the modes and the targets of the base state: those of the contingency. */
    public void applyTo(int[] baseModes, double[] baseTargets) {
        for (int i = 0; i < positions.length; i++) {
            baseModes[positions[i]] = modes[i];
            baseTargets[positions[i]] = targets[i];
        }
    }
}
