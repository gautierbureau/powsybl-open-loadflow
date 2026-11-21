/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfShunt;
import com.powsybl.openloadflow.network.PiModel;
import gnu.trove.list.array.TByteArrayList;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TIntArrayList;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The records of the contingencies of a security analysis ({@link AcContingencyDelta}) as flat arrays, the data a
 * device reads for its scenarios: each table holds the entries of every contingency, those of contingency {@code k}
 * between {@code offsets[k]} and {@code offsets[k + 1]} of its offsets.
 *
 * <ul>
 * <li>the slots, indexed by slot of the fixed structure: the mode and the target of the slots the contingency changes
 *     ({@link #getSlotOffsets()}), against the base modes and targets ({@link #getBaseModes()},
 *     {@link #getBaseTargets()});</li>
 * <li>the elements, by number: the buses, the branches and the HVDC lines it disables, the generators and the loads
 *     (numbered bus by bus, as in {@link AcContingencyDelta.Baseline}) whose targets or flags it changes, the buses
 *     whose controls change, the shunts, the taps, the reactive keys, the slack and the reference buses.</li>
 * </ul>
 *
 * <p>The disabled original loads of a load are kept by id, for the host alone: a device has no use for them.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class AcContingencyRecords {

    /** {@link #getGeneratorFlags()}: the generator is disabled. */
    public static final byte GENERATOR_DISABLED = 1;
    /** {@link #getGeneratorFlags()}: the generator takes part in the slack distribution. */
    public static final byte GENERATOR_PARTICIPATING = 2;

    /** {@link #getControlBusFlags()}: the bus controls the voltage. */
    public static final byte BUS_VOLTAGE_CONTROL = 1;
    /** {@link #getControlBusFlags()}: the bus controls the reactive power of a branch. */
    public static final byte BUS_REACTIVE_POWER_CONTROL = 2;
    /** {@link #getControlBusFlags()}: the reactive target of the bus is frozen. */
    public static final byte BUS_TARGET_Q_FROZEN = 4;

    private static final LfGenerator.GeneratorControlType[] CONTROL_TYPES = LfGenerator.GeneratorControlType.values();

    private final AcContingencyDelta.Baseline baseline;

    // by contingency
    private final String[] contingencyIds;
    private final double[] activePowerLoss;
    private final double[] preDistributedActivePower;
    private final boolean[] onBaseStructure;
    private final int[] referenceBus;

    private final Table slots;
    private final int[] slotModes;
    private final double[] slotTargets;

    private final Table disabledBuses;
    private final Table disabledBranches;
    private final Table side1OpenBranches;
    private final Table side2OpenBranches;
    private final Table disabledHvdcs;

    private final Table generators;
    private final String[] generatorIds;
    private final double[] generatorTargetP;
    private final double[] generatorInitialTargetP;
    private final byte[] generatorFlags;
    private final byte[] generatorControlTypes;

    private final Table loads;
    private final String[] loadIds;
    private final double[] loadTargetP;
    private final double[] loadTargetQ;
    private final double[] loadAbsVariableTargetP;
    private final String[][] loadDisabledOriginalIds;

    private final Table controlBuses;
    private final byte[] controlBusFlags;
    private final double[] busFrozenGenerationTargetQ;

    private final Table reactiveTargetBuses;
    private final Table excludedSlackBuses;
    private final Table slackBuses;

    private final Table reactivePercentBuses;
    private final double[] reactivePercents;

    private final Table shunts;
    private final double[] shuntG;
    private final double[] shuntB;

    private final Table tapBranches;
    private final int[] tapPositions;
    private final double[] tapR1;
    private final double[] tapA1;

    /** The entries of one kind for every contingency: the numbers, and the offsets of each contingency's. */
    private record Table(int[] offsets, int[] values) {

        int start(int k) {
            return offsets[k];
        }

        int end(int k) {
            return offsets[k + 1];
        }

        int[] of(int k) {
            return Arrays.copyOfRange(values, offsets[k], offsets[k + 1]);
        }
    }

    /** Builds the tables, one contingency after another. */
    private static final class TableBuilder {

        private final TIntArrayList offsets = new TIntArrayList(new int[] {0});
        private final TIntArrayList values = new TIntArrayList();

        void add(int[] contingencyValues) {
            values.add(contingencyValues);
            offsets.add(values.size());
        }

        Table build() {
            return new Table(offsets.toArray(), values.toArray());
        }
    }

    public static final class Builder {

        private final AcContingencyDelta.Baseline baseline;
        private final List<String> contingencyIds = new ArrayList<>();
        private final TDoubleArrayList activePowerLoss = new TDoubleArrayList();
        private final TDoubleArrayList preDistributedActivePower = new TDoubleArrayList();
        private final List<Boolean> onBaseStructure = new ArrayList<>();
        private final TIntArrayList referenceBus = new TIntArrayList();
        private final TableBuilder slots = new TableBuilder();
        private final TIntArrayList slotModes = new TIntArrayList();
        private final TDoubleArrayList slotTargets = new TDoubleArrayList();
        private final TableBuilder disabledBuses = new TableBuilder();
        private final TableBuilder disabledBranches = new TableBuilder();
        private final TableBuilder side1OpenBranches = new TableBuilder();
        private final TableBuilder side2OpenBranches = new TableBuilder();
        private final TableBuilder disabledHvdcs = new TableBuilder();
        private final TableBuilder generators = new TableBuilder();
        private final List<String> generatorIds = new ArrayList<>();
        private final TDoubleArrayList generatorTargetP = new TDoubleArrayList();
        private final TDoubleArrayList generatorInitialTargetP = new TDoubleArrayList();
        private final TByteArrayList generatorFlags = new TByteArrayList();
        private final TByteArrayList generatorControlTypes = new TByteArrayList();
        private final TableBuilder loads = new TableBuilder();
        private final List<String> loadIds = new ArrayList<>();
        private final TDoubleArrayList loadTargetP = new TDoubleArrayList();
        private final TDoubleArrayList loadTargetQ = new TDoubleArrayList();
        private final TDoubleArrayList loadAbsVariableTargetP = new TDoubleArrayList();
        private final List<String[]> loadDisabledOriginalIds = new ArrayList<>();
        private final TableBuilder controlBuses = new TableBuilder();
        private final TByteArrayList controlBusFlags = new TByteArrayList();
        private final TDoubleArrayList busFrozenGenerationTargetQ = new TDoubleArrayList();
        private final TableBuilder reactiveTargetBuses = new TableBuilder();
        private final TableBuilder excludedSlackBuses = new TableBuilder();
        private final TableBuilder slackBuses = new TableBuilder();
        private final TableBuilder reactivePercentBuses = new TableBuilder();
        private final TDoubleArrayList reactivePercents = new TDoubleArrayList();
        private final TableBuilder shunts = new TableBuilder();
        private final TDoubleArrayList shuntG = new TDoubleArrayList();
        private final TDoubleArrayList shuntB = new TDoubleArrayList();
        private final TableBuilder tapBranches = new TableBuilder();
        private final TIntArrayList tapPositions = new TIntArrayList();
        private final TDoubleArrayList tapR1 = new TDoubleArrayList();
        private final TDoubleArrayList tapA1 = new TDoubleArrayList();

        /** For the records taken against the given base state. */
        public Builder(AcContingencyDelta.Baseline baseline) {
            this.baseline = Objects.requireNonNull(baseline);
        }

        /** Appends a record; returns its index. */
        public int add(AcContingencyDelta d) {
            Objects.requireNonNull(d);
            contingencyIds.add(d.contingencyId());
            activePowerLoss.add(d.activePowerLoss());
            preDistributedActivePower.add(d.preDistributedActivePower());
            onBaseStructure.add(d.onBaseStructure());
            referenceBus.add(d.referenceBus());
            slots.add(d.slots());
            slotModes.add(d.modes());
            slotTargets.add(d.targets());
            disabledBuses.add(d.disabledBuses());
            disabledBranches.add(d.disabledBranches());
            side1OpenBranches.add(d.side1OpenBranches());
            side2OpenBranches.add(d.side2OpenBranches());
            disabledHvdcs.add(d.disabledHvdcs());
            generators.add(d.generators());
            generatorIds.addAll(Arrays.asList(d.generatorIds()));
            generatorTargetP.add(d.generatorTargetP());
            generatorInitialTargetP.add(d.generatorInitialTargetP());
            for (int i = 0; i < d.generators().length; i++) {
                generatorFlags.add((byte) ((d.generatorDisabled()[i] ? GENERATOR_DISABLED : 0)
                        | (d.generatorParticipating()[i] ? GENERATOR_PARTICIPATING : 0)));
                generatorControlTypes.add((byte) d.generatorControlTypes()[i].ordinal());
            }
            loads.add(d.loads());
            loadIds.addAll(Arrays.asList(d.loadIds()));
            loadTargetP.add(d.loadTargetP());
            loadTargetQ.add(d.loadTargetQ());
            loadAbsVariableTargetP.add(d.loadAbsVariableTargetP());
            loadDisabledOriginalIds.addAll(Arrays.asList(d.loadDisabledOriginalIds()));
            controlBuses.add(d.controlBuses());
            for (int i = 0; i < d.controlBuses().length; i++) {
                controlBusFlags.add((byte) ((d.busVoltageControlEnabled()[i] ? BUS_VOLTAGE_CONTROL : 0)
                        | (d.busReactivePowerControlEnabled()[i] ? BUS_REACTIVE_POWER_CONTROL : 0)
                        | (d.busGenerationTargetQFrozen()[i] ? BUS_TARGET_Q_FROZEN : 0)));
            }
            busFrozenGenerationTargetQ.add(d.busFrozenGenerationTargetQ());
            reactiveTargetBuses.add(d.reactiveTargetBuses());
            excludedSlackBuses.add(d.excludedSlackBuses());
            slackBuses.add(d.slackBuses());
            reactivePercentBuses.add(d.reactivePercentBuses());
            reactivePercents.add(d.reactivePercents());
            shunts.add(d.shunts());
            shuntG.add(d.shuntG());
            shuntB.add(d.shuntB());
            tapBranches.add(d.tapBranches());
            tapPositions.add(d.tapPositions());
            tapR1.add(d.tapR1());
            tapA1.add(d.tapA1());
            return contingencyIds.size() - 1;
        }

        public AcContingencyRecords build() {
            return new AcContingencyRecords(this);
        }
    }

    private AcContingencyRecords(Builder b) {
        baseline = b.baseline;
        contingencyIds = b.contingencyIds.toArray(new String[0]);
        activePowerLoss = b.activePowerLoss.toArray();
        preDistributedActivePower = b.preDistributedActivePower.toArray();
        onBaseStructure = new boolean[contingencyIds.length];
        for (int k = 0; k < onBaseStructure.length; k++) {
            onBaseStructure[k] = b.onBaseStructure.get(k);
        }
        referenceBus = b.referenceBus.toArray();
        slots = b.slots.build();
        slotModes = b.slotModes.toArray();
        slotTargets = b.slotTargets.toArray();
        disabledBuses = b.disabledBuses.build();
        disabledBranches = b.disabledBranches.build();
        side1OpenBranches = b.side1OpenBranches.build();
        side2OpenBranches = b.side2OpenBranches.build();
        disabledHvdcs = b.disabledHvdcs.build();
        generators = b.generators.build();
        generatorIds = b.generatorIds.toArray(new String[0]);
        generatorTargetP = b.generatorTargetP.toArray();
        generatorInitialTargetP = b.generatorInitialTargetP.toArray();
        generatorFlags = b.generatorFlags.toArray();
        generatorControlTypes = b.generatorControlTypes.toArray();
        loads = b.loads.build();
        loadIds = b.loadIds.toArray(new String[0]);
        loadTargetP = b.loadTargetP.toArray();
        loadTargetQ = b.loadTargetQ.toArray();
        loadAbsVariableTargetP = b.loadAbsVariableTargetP.toArray();
        loadDisabledOriginalIds = b.loadDisabledOriginalIds.toArray(new String[0][]);
        controlBuses = b.controlBuses.build();
        controlBusFlags = b.controlBusFlags.toArray();
        busFrozenGenerationTargetQ = b.busFrozenGenerationTargetQ.toArray();
        reactiveTargetBuses = b.reactiveTargetBuses.build();
        excludedSlackBuses = b.excludedSlackBuses.build();
        slackBuses = b.slackBuses.build();
        reactivePercentBuses = b.reactivePercentBuses.build();
        reactivePercents = b.reactivePercents.toArray();
        shunts = b.shunts.build();
        shuntG = b.shuntG.toArray();
        shuntB = b.shuntB.toArray();
        tapBranches = b.tapBranches.build();
        tapPositions = b.tapPositions.toArray();
        tapR1 = b.tapR1.toArray();
        tapA1 = b.tapA1.toArray();
    }

    /** How many contingencies. */
    public int size() {
        return contingencyIds.length;
    }

    /** The record of contingency {@code k}, read back from the tables. */
    public AcContingencyDelta get(int k) {
        int g0 = generators.start(k);
        int g1 = generators.end(k);
        boolean[] genDisabled = new boolean[g1 - g0];
        boolean[] genParticipating = new boolean[g1 - g0];
        LfGenerator.GeneratorControlType[] genControlTypes = new LfGenerator.GeneratorControlType[g1 - g0];
        for (int i = g0; i < g1; i++) {
            genDisabled[i - g0] = (generatorFlags[i] & GENERATOR_DISABLED) != 0;
            genParticipating[i - g0] = (generatorFlags[i] & GENERATOR_PARTICIPATING) != 0;
            genControlTypes[i - g0] = CONTROL_TYPES[generatorControlTypes[i]];
        }
        int c0 = controlBuses.start(k);
        int c1 = controlBuses.end(k);
        boolean[] voltageControl = new boolean[c1 - c0];
        boolean[] reactivePowerControl = new boolean[c1 - c0];
        boolean[] frozen = new boolean[c1 - c0];
        for (int i = c0; i < c1; i++) {
            voltageControl[i - c0] = (controlBusFlags[i] & BUS_VOLTAGE_CONTROL) != 0;
            reactivePowerControl[i - c0] = (controlBusFlags[i] & BUS_REACTIVE_POWER_CONTROL) != 0;
            frozen[i - c0] = (controlBusFlags[i] & BUS_TARGET_Q_FROZEN) != 0;
        }
        int l0 = loads.start(k);
        int l1 = loads.end(k);
        return new AcContingencyDelta(contingencyIds[k], activePowerLoss[k], preDistributedActivePower[k],
                disabledBuses.of(k), disabledBranches.of(k), side1OpenBranches.of(k), side2OpenBranches.of(k), disabledHvdcs.of(k),
                onBaseStructure[k], slots.of(k), range(slotModes, slots, k), range(slotTargets, slots, k),
                generators.of(k), Arrays.copyOfRange(generatorIds, g0, g1), range(generatorTargetP, generators, k),
                range(generatorInitialTargetP, generators, k), genDisabled, genParticipating, genControlTypes,
                loads.of(k), Arrays.copyOfRange(loadIds, l0, l1), range(loadTargetP, loads, k), range(loadTargetQ, loads, k),
                range(loadAbsVariableTargetP, loads, k), Arrays.copyOfRange(loadDisabledOriginalIds, l0, l1),
                controlBuses.of(k), voltageControl, reactivePowerControl, frozen, range(busFrozenGenerationTargetQ, controlBuses, k),
                reactiveTargetBuses.of(k), excludedSlackBuses.of(k), slackBuses.of(k), referenceBus[k],
                reactivePercentBuses.of(k), range(reactivePercents, reactivePercentBuses, k),
                shunts.of(k), range(shuntG, shunts, k), range(shuntB, shunts, k),
                tapBranches.of(k), range(tapPositions, tapBranches, k), range(tapR1, tapBranches, k), range(tapA1, tapBranches, k));
    }

    private static double[] range(double[] values, Table table, int k) {
        return Arrays.copyOfRange(values, table.start(k), table.end(k));
    }

    private static int[] range(int[] values, Table table, int k) {
        return Arrays.copyOfRange(values, table.start(k), table.end(k));
    }

    /**
     * Writes contingency {@code k} on a network in its base state, from the tables alone, without OLF's rules: the
     * network is then as {@code LfContingency.apply} and the spreading of the loss leave it. The generators and the
     * loads are numbered as in the baseline the records were taken against.
     */
    public void install(int k, LfNetwork network, AcContingencyDelta.Baseline baseline) {
        // the slack bus moves before the buses it was on are disabled, as PropagatedContingency.toLfContingency does it
        int[] excluded = excludedSlackBuses.of(k);
        if (!baseline.hasExcludedSlackBuses(excluded)) {
            Set<LfBus> buses = new HashSet<>();
            for (int num : excluded) {
                buses.add(network.getBus(num));
            }
            network.getSynchronousNetworks().forEach(synchronousNetwork -> synchronousNetwork.setExcludedSlackBuses(buses));
            // and is chosen at once, among buses none of which is disabled yet: the choice depends on the branches they have
            network.getSynchronousNetworks().getFirst().getSlackBuses();
        }
        // in the order of DisabledNetwork.apply
        int[] v = disabledBranches.values();
        for (int i = disabledBranches.start(k); i < disabledBranches.end(k); i++) {
            network.getBranch(v[i]).setDisabled(true);
        }
        v = side1OpenBranches.values();
        for (int i = side1OpenBranches.start(k); i < side1OpenBranches.end(k); i++) {
            network.getBranch(v[i]).setConnectedSide1(false);
        }
        v = side2OpenBranches.values();
        for (int i = side2OpenBranches.start(k); i < side2OpenBranches.end(k); i++) {
            network.getBranch(v[i]).setConnectedSide2(false);
        }
        v = disabledHvdcs.values();
        for (int i = disabledHvdcs.start(k); i < disabledHvdcs.end(k); i++) {
            network.getHvdc(v[i]).setDisabled(true);
        }
        v = disabledBuses.values();
        for (int i = disabledBuses.start(k); i < disabledBuses.end(k); i++) {
            network.getBus(v[i]).setDisabled(true);
        }
        v = shunts.values();
        for (int i = shunts.start(k); i < shunts.end(k); i++) {
            LfShunt shunt = network.getShunt(v[i]);
            // only what changed: the standby automaton shunt of a static var compensator has no conductance to set
            if (differs(shunt.getG(), shuntG[i])) {
                shunt.setG(shuntG[i]);
            }
            if (differs(shunt.getB(), shuntB[i])) {
                shunt.setB(shuntB[i]);
            }
        }
        v = loads.values();
        for (int i = loads.start(k); i < loads.end(k); i++) {
            LfLoad load = baseline.getLoad(v[i]);
            load.setTargetP(loadTargetP[i]);
            load.setTargetQ(loadTargetQ[i]);
            load.setAbsVariableTargetP(loadAbsVariableTargetP[i]);
            for (String originalId : loadDisabledOriginalIds[i]) {
                load.setOriginalLoadDisabled(originalId, true);
            }
        }
        v = generators.values();
        for (int i = generators.start(k); i < generators.end(k); i++) {
            LfGenerator generator = baseline.getGenerator(v[i]);
            generator.setTargetP(generatorTargetP[i]);
            generator.setInitialTargetP(generatorInitialTargetP[i]);
            generator.setParticipating((generatorFlags[i] & GENERATOR_PARTICIPATING) != 0);
            generator.setDisabled((generatorFlags[i] & GENERATOR_DISABLED) != 0);
            generator.setGeneratorControlType(CONTROL_TYPES[generatorControlTypes[i]]);
        }
        // as OLF's rules: the bus of a lost generator left with none controlling its voltage has its voltage control
        // set off, which marks its reactive target to be computed again without the lost generator
        v = reactiveTargetBuses.values();
        for (int i = reactiveTargetBuses.start(k); i < reactiveTargetBuses.end(k); i++) {
            network.getBus(v[i]).setGeneratorVoltageControlEnabledAndRecomputeTargetQ(false);
        }
        v = controlBuses.values();
        for (int i = controlBuses.start(k); i < controlBuses.end(k); i++) {
            LfBus bus = network.getBus(v[i]);
            boolean voltageControl = (controlBusFlags[i] & BUS_VOLTAGE_CONTROL) != 0;
            boolean reactivePowerControl = (controlBusFlags[i] & BUS_REACTIVE_POWER_CONTROL) != 0;
            boolean frozen = (controlBusFlags[i] & BUS_TARGET_Q_FROZEN) != 0;
            // setting the voltage control marks the reactive target of the bus to be computed again from its
            // generators, and frees it when it was frozen
            if (bus.isGeneratorVoltageControlEnabled() != voltageControl || bus.isGenerationTargetQFrozen() && !frozen) {
                bus.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(voltageControl);
            }
            if (frozen && (!bus.isGenerationTargetQFrozen() || differs(bus.getGenerationTargetQ(), busFrozenGenerationTargetQ[i]))) {
                bus.freezeGenerationTargetQAndDisableGeneratorVoltageControl(busFrozenGenerationTargetQ[i]);
            }
            if (bus.isGeneratorReactivePowerControlEnabled() != reactivePowerControl) {
                bus.setGeneratorReactivePowerControlEnabled(reactivePowerControl);
            }
        }
        v = reactivePercentBuses.values();
        for (int i = reactivePercentBuses.start(k); i < reactivePercentBuses.end(k); i++) {
            network.getBus(v[i]).setRemoteControlReactivePercent(reactivePercents[i]);
        }
        v = tapBranches.values();
        for (int i = tapBranches.start(k); i < tapBranches.end(k); i++) {
            PiModel piModel = network.getBranch(v[i]).getPiModel();
            piModel.setTapPosition(tapPositions[i]);
            piModel.setA1(tapA1[i]);
            piModel.setR1(tapR1[i]);
        }
    }

    private static boolean differs(double a, double b) {
        return Double.doubleToLongBits(a) != Double.doubleToLongBits(b);
    }

    /**
     * Writes the tables as text, one block by contingency, the numbers as Java writes them and the reals in
     * hexadecimal, so that two writings compare to the last bit.
     */
    public void write(Writer writer) {
        try {
            writer.append("BASE modes ").append(join(getBaseModes())).append('\n');
            writer.append("BASE targets ").append(join(getBaseTargets())).append('\n');
            for (int k = 0; k < size(); k++) {
                writer.append("C ").append(contingencyIds[k])
                        .append(" loss ").append(Double.toHexString(activePowerLoss[k]))
                        .append(" spread ").append(Double.toHexString(preDistributedActivePower[k]))
                        .append(" onBaseStructure ").append(Boolean.toString(onBaseStructure[k]))
                        .append(" reference ").append(Integer.toString(referenceBus[k])).append('\n');
                for (int i = slots.start(k); i < slots.end(k); i++) {
                    writer.append("  S ").append(Integer.toString(slots.values()[i])).append(' ').append(Integer.toString(slotModes[i]))
                            .append(' ').append(Double.toHexString(slotTargets[i])).append('\n');
                }
                line(writer, "DB", disabledBuses, k);
                line(writer, "DR", disabledBranches, k);
                line(writer, "O1", side1OpenBranches, k);
                line(writer, "O2", side2OpenBranches, k);
                line(writer, "DH", disabledHvdcs, k);
                for (int i = generators.start(k); i < generators.end(k); i++) {
                    writer.append("  G ").append(Integer.toString(generators.values()[i])).append(' ').append(Double.toHexString(generatorTargetP[i]))
                            .append(' ').append(Double.toHexString(generatorInitialTargetP[i])).append(' ').append(Integer.toString(generatorFlags[i]))
                            .append(' ').append(CONTROL_TYPES[generatorControlTypes[i]].name()).append('\n');
                }
                for (int i = loads.start(k); i < loads.end(k); i++) {
                    writer.append("  L ").append(Integer.toString(loads.values()[i])).append(' ').append(Double.toHexString(loadTargetP[i]))
                            .append(' ').append(Double.toHexString(loadTargetQ[i])).append(' ').append(Double.toHexString(loadAbsVariableTargetP[i]))
                            .append(' ').append(String.join(",", loadDisabledOriginalIds[i])).append('\n');
                }
                for (int i = controlBuses.start(k); i < controlBuses.end(k); i++) {
                    writer.append("  B ").append(Integer.toString(controlBuses.values()[i])).append(' ').append(Integer.toString(controlBusFlags[i]))
                            .append(' ').append(Double.toHexString(busFrozenGenerationTargetQ[i])).append('\n');
                }
                line(writer, "RQ", reactiveTargetBuses, k);
                line(writer, "XS", excludedSlackBuses, k);
                line(writer, "SL", slackBuses, k);
                for (int i = reactivePercentBuses.start(k); i < reactivePercentBuses.end(k); i++) {
                    writer.append("  K ").append(Integer.toString(reactivePercentBuses.values()[i])).append(' ')
                            .append(Double.toHexString(reactivePercents[i])).append('\n');
                }
                for (int i = shunts.start(k); i < shunts.end(k); i++) {
                    writer.append("  H ").append(Integer.toString(shunts.values()[i])).append(' ').append(Double.toHexString(shuntG[i]))
                            .append(' ').append(Double.toHexString(shuntB[i])).append('\n');
                }
                for (int i = tapBranches.start(k); i < tapBranches.end(k); i++) {
                    writer.append("  T ").append(Integer.toString(tapBranches.values()[i])).append(' ').append(Integer.toString(tapPositions[i]))
                            .append(' ').append(Double.toHexString(tapR1[i])).append(' ').append(Double.toHexString(tapA1[i])).append('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void line(Writer writer, String tag, Table table, int k) throws IOException {
        if (table.end(k) > table.start(k)) {
            writer.append("  ").append(tag).append(' ').append(join(table.of(k))).append('\n');
        }
    }

    private static String join(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static String join(double[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(Double.toHexString(values[i]));
        }
        return sb.toString();
    }

    // The tables, for a device: the offsets of each table, then its values.

    /** The mode of each slot in the base state the records were taken against. */
    public int[] getBaseModes() {
        return baseline.getModes();
    }

    /** The target of each slot in the base state the records were taken against. */
    public double[] getBaseTargets() {
        return baseline.getTargets();
    }

    public String getContingencyId(int k) {
        return contingencyIds[k];
    }

    public double[] getActivePowerLoss() {
        return activePowerLoss;
    }

    public double[] getPreDistributedActivePower() {
        return preDistributedActivePower;
    }

    public boolean[] getOnBaseStructure() {
        return onBaseStructure;
    }

    public int[] getReferenceBus() {
        return referenceBus;
    }

    public int[] getSlotOffsets() {
        return slots.offsets();
    }

    public int[] getSlots() {
        return slots.values();
    }

    public int[] getSlotModes() {
        return slotModes;
    }

    public double[] getSlotTargets() {
        return slotTargets;
    }

    public int[] getDisabledBusOffsets() {
        return disabledBuses.offsets();
    }

    public int[] getDisabledBuses() {
        return disabledBuses.values();
    }

    public int[] getDisabledBranchOffsets() {
        return disabledBranches.offsets();
    }

    public int[] getDisabledBranches() {
        return disabledBranches.values();
    }

    public int[] getSide1OpenBranchOffsets() {
        return side1OpenBranches.offsets();
    }

    public int[] getSide1OpenBranches() {
        return side1OpenBranches.values();
    }

    public int[] getSide2OpenBranchOffsets() {
        return side2OpenBranches.offsets();
    }

    public int[] getSide2OpenBranches() {
        return side2OpenBranches.values();
    }

    public int[] getDisabledHvdcOffsets() {
        return disabledHvdcs.offsets();
    }

    public int[] getDisabledHvdcs() {
        return disabledHvdcs.values();
    }

    public int[] getGeneratorOffsets() {
        return generators.offsets();
    }

    public int[] getGenerators() {
        return generators.values();
    }

    public double[] getGeneratorTargetP() {
        return generatorTargetP;
    }

    public double[] getGeneratorInitialTargetP() {
        return generatorInitialTargetP;
    }

    public byte[] getGeneratorFlags() {
        return generatorFlags;
    }

    /** The ordinal of each generator's {@link LfGenerator.GeneratorControlType}. */
    public byte[] getGeneratorControlTypes() {
        return generatorControlTypes;
    }

    public int[] getLoadOffsets() {
        return loads.offsets();
    }

    public int[] getLoads() {
        return loads.values();
    }

    public double[] getLoadTargetP() {
        return loadTargetP;
    }

    public double[] getLoadTargetQ() {
        return loadTargetQ;
    }

    public double[] getLoadAbsVariableTargetP() {
        return loadAbsVariableTargetP;
    }

    public int[] getControlBusOffsets() {
        return controlBuses.offsets();
    }

    public int[] getControlBuses() {
        return controlBuses.values();
    }

    public byte[] getControlBusFlags() {
        return controlBusFlags;
    }

    public double[] getBusFrozenGenerationTargetQ() {
        return busFrozenGenerationTargetQ;
    }

    public int[] getReactiveTargetBusOffsets() {
        return reactiveTargetBuses.offsets();
    }

    public int[] getReactiveTargetBuses() {
        return reactiveTargetBuses.values();
    }

    public int[] getExcludedSlackBusOffsets() {
        return excludedSlackBuses.offsets();
    }

    public int[] getExcludedSlackBuses() {
        return excludedSlackBuses.values();
    }

    public int[] getSlackBusOffsets() {
        return slackBuses.offsets();
    }

    public int[] getSlackBuses() {
        return slackBuses.values();
    }

    public int[] getReactivePercentBusOffsets() {
        return reactivePercentBuses.offsets();
    }

    public int[] getReactivePercentBuses() {
        return reactivePercentBuses.values();
    }

    public double[] getReactivePercents() {
        return reactivePercents;
    }

    public int[] getShuntOffsets() {
        return shunts.offsets();
    }

    public int[] getShunts() {
        return shunts.values();
    }

    public double[] getShuntG() {
        return shuntG;
    }

    public double[] getShuntB() {
        return shuntB;
    }

    public int[] getTapBranchOffsets() {
        return tapBranches.offsets();
    }

    public int[] getTapBranches() {
        return tapBranches.values();
    }

    public int[] getTapPositions() {
        return tapPositions;
    }

    public double[] getTapR1() {
        return tapR1;
    }

    public double[] getTapA1() {
        return tapA1;
    }
}
