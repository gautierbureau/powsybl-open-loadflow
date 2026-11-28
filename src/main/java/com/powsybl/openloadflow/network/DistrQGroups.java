/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TIntArrayList;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The groups of the remote generator voltage controls of a network as flat tables: the controllers whose reactive
 * power a {@code DISTR_Q} equation shares, and their keys. The tables a device reads to share the reactive power the
 * way OLF does ({@link GeneratorVoltageControl#updateReactiveKeys}): a controller that leaves the voltage control (a
 * switch to PQ at a reactive limit) or is disabled gets no share, and the others share it in proportion to their keys.
 *
 * <ul>
 * <li>a group by main voltage control that is not local, with the buses it controls and its controllers in the order of
 *     the merged control ({@link VoltageControl#getMergedControllerElements()});</li>
 * <li>the raw key of each controller ({@link Control#createReactiveKeys}): the remote control reactive keys of its
 *     generators, or their reactive power range, or their count. A contingency that loses a generator changes them:
 *     take the tables again.</li>
 * </ul>
 *
 * <p>{@link #distribute} shares the reactive power over the tables for given active controllers, with the arithmetic of
 * OLF: the same zeroing, and the same compensated sum in member order.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class DistrQGroups {

    private final LfNetwork network;
    private final int[] controlledBuses;
    private final int[] groupStart;
    private final int[] members;
    private final double[] rawKeys;

    private DistrQGroups(LfNetwork network, int[] controlledBuses, int[] groupStart, int[] members, double[] rawKeys) {
        this.network = network;
        this.controlledBuses = controlledBuses;
        this.groupStart = groupStart;
        this.members = members;
        this.rawKeys = rawKeys;
    }

    /** The tables of the network in its current state. */
    public static DistrQGroups of(LfNetwork network) {
        Objects.requireNonNull(network);
        TIntArrayList controlled = new TIntArrayList();
        TIntArrayList start = new TIntArrayList(new int[] {0});
        TIntArrayList memberList = new TIntArrayList();
        TDoubleArrayList keys = new TDoubleArrayList();
        for (LfBus bus : network.getBuses()) {
            if (bus.isDisabled() || !bus.isGeneratorVoltageControlled()) {
                continue;
            }
            GeneratorVoltageControl voltageControl = bus.getGeneratorVoltageControl().orElse(null);
            if (voltageControl == null || voltageControl.getMergeStatus() != VoltageControl.MergeStatus.MAIN
                    || voltageControl.isLocalControl() || voltageControl.getControlledBus() != bus) {
                continue;
            }
            List<LfBus> controllers = voltageControl.getMergedControllerElements();
            controlled.add(bus.getNum());
            for (LfBus controller : controllers) {
                memberList.add(controller.getNum());
            }
            keys.add(Control.createReactiveKeys(controllers, LfGenerator.GeneratorControlType.VOLTAGE));
            start.add(memberList.size());
        }
        return new DistrQGroups(network, controlled.toArray(), start.toArray(), memberList.toArray(), keys.toArray());
    }

    /** How many groups. */
    public int getGroupCount() {
        return controlledBuses.length;
    }

    /** The bus each group controls. */
    public int[] getControlledBuses() {
        return controlledBuses;
    }

    /** The members of group {@code g} are at {@code [groupStart[g], groupStart[g + 1])} of {@link #getMembers()}. */
    public int[] getGroupStart() {
        return groupStart;
    }

    /** The controller buses of every group, one group after another. */
    public int[] getMembers() {
        return members;
    }

    /** The raw key of each member. */
    public double[] getRawKeys() {
        return rawKeys;
    }

    /**
     * Shares the reactive power of every group over its active members, as OLF does: an inactive member gets 0, the
     * others their key over the sum of the active keys.
     *
     * @param active whether each member takes part, indexed as {@link #getMembers()}
     * @return the share of each member, indexed as {@link #getMembers()}
     */
    public double[] distribute(boolean[] active) {
        double[] shares = new double[members.length];
        for (int g = 0; g < controlledBuses.length; g++) {
            int from = groupStart[g];
            int to = groupStart[g + 1];
            double[] keys = Arrays.copyOfRange(rawKeys, from, to);
            double[] groupShares = new double[to - from];
            Control.distribute(keys, Arrays.copyOfRange(active, from, to), groupShares);
            System.arraycopy(groupShares, 0, shares, from, to - from);
        }
        return shares;
    }

    /** Whether each member takes part in its group, from the state of the network: enabled, and controlling the voltage. */
    public boolean[] getActiveMembers() {
        boolean[] active = new boolean[members.length];
        for (int m = 0; m < members.length; m++) {
            LfBus bus = network.getBus(members[m]);
            active[m] = !bus.isDisabled() && bus.isGeneratorVoltageControlEnabled();
        }
        return active;
    }
}
