/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.fastdc;

import com.powsybl.commons.PowsyblException;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.action.AbstractLfBranchAction;
import com.powsybl.openloadflow.network.action.LfAction;

import java.util.*;

/**
 * Parts of the network that are dead in the base case (behind an open switch) and that an action may energize by
 * closing a branch towards them, made computable by the fast DC engine, which reuses the base case's matrix.
 *
 * <p>Each such island is put in the base case's system: its buses and its closed internal branches enabled, attached
 * to the live network by one of the branches an action closes (the island's anchor), and its injections set to zero.
 * A radial part without injection carries no flow, so the base case and every state that does not energize the island
 * are exact; the island's rows are its buses' own active power balance equations. An action closing the anchor then
 * changes only the target vector (the island's injections come back), and one closing another branch towards the island
 * is that branch enabled and the anchor disabled: two ordinary Woodbury branch elements.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class EnergizableIslands {

    private record Injections(double[] loadTargetP, double[] loadInitialTargetP, double[] loadAbsVariableTargetP,
                              double[] generatorTargetP, double[] generatorInitialTargetP, boolean[] generatorParticipating) {
    }

    /** An island: its buses with their base-case injections, and the branch attaching it in the base case's system. */
    public record Island(List<LfBus> buses, LfBranch anchor, Map<LfBus, Injections> injections) {
    }

    private static final EnergizableIslands NONE = new EnergizableIslands(Map.of());

    private final Map<LfBranch, Island> islandByEntryBranch;

    private EnergizableIslands(Map<LfBranch, Island> islandByEntryBranch) {
        this.islandByEntryBranch = islandByEntryBranch;
    }

    public static EnergizableIslands none() {
        return NONE;
    }

    public boolean isEmpty() {
        return islandByEntryBranch.isEmpty();
    }

    /**
     * Finds the islands these actions may energize and puts them in the network's base case: to be called before the
     * load flow context (its equation system) is created.
     */
    public static EnergizableIslands prepare(LfNetwork network, Collection<LfAction> actions) {
        Set<LfBranch> removedFromConnectivity = network.getConnectivityRemovedBranches();
        Map<LfBus, Island> islandByBus = new HashMap<>();
        Map<LfBranch, Island> islandByEntryBranch = new LinkedHashMap<>();
        for (LfAction action : actions) {
            if (!(action instanceof AbstractLfBranchAction<?> branchAction)) {
                continue;
            }
            for (LfBranch branch : branchAction.getEnabledBranches()) {
                LfBus bus1 = branch.getBus1();
                LfBus bus2 = branch.getBus2();
                if (bus1 == null || bus2 == null) {
                    continue;
                }
                LfBus dead;
                if (islandByBus.containsKey(bus1) && !bus2.isDisabled() || bus1.isDisabled() && !bus2.isDisabled()) {
                    dead = bus1;
                } else if (islandByBus.containsKey(bus2) && !bus1.isDisabled() || bus2.isDisabled() && !bus1.isDisabled()) {
                    dead = bus2;
                } else {
                    continue; // between live buses (an ordinary Woodbury element), or between dead ones (not supported)
                }
                Island island = islandByBus.get(dead);
                if (island == null) {
                    island = energizeInBaseCase(network, dead, branch, removedFromConnectivity);
                    for (LfBus bus : island.buses()) {
                        islandByBus.put(bus, island);
                    }
                }
                islandByEntryBranch.put(branch, island);
            }
        }
        return islandByEntryBranch.isEmpty() ? NONE : new EnergizableIslands(islandByEntryBranch);
    }

    private static Island energizeInBaseCase(LfNetwork network, LfBus root, LfBranch anchor, Set<LfBranch> removedFromConnectivity) {
        // the island: the dead buses reachable from the root through branches closed in the base case's topology
        List<LfBus> buses = new ArrayList<>();
        Set<LfBranch> branches = new LinkedHashSet<>();
        Deque<LfBus> queue = new ArrayDeque<>(List.of(root));
        Set<LfBus> visited = new HashSet<>(List.of(root));
        while (!queue.isEmpty()) {
            LfBus bus = queue.poll();
            if (!bus.getHvdcs().isEmpty()) {
                throw new PowsyblException("Bus '" + bus.getId() + "', energized by closing '" + anchor.getId()
                        + "', holds an HVDC converter: not supported in fast DC security analysis");
            }
            buses.add(bus);
            for (LfBranch branch : bus.getBranches()) {
                if (branch.getBus1() == null || branch.getBus2() == null || removedFromConnectivity.contains(branch)) {
                    continue; // open at one side, or a branch that is open in the base case (another closable one)
                }
                LfBus other = branch.getBus1() == bus ? branch.getBus2() : branch.getBus1();
                if (!other.isDisabled()) {
                    throw new IllegalStateException("Dead bus '" + bus.getId() + "' closed onto live bus '" + other.getId() + "'");
                }
                branches.add(branch);
                if (visited.add(other)) {
                    queue.add(other);
                }
            }
        }

        // in the base case's system, with no injection
        Map<LfBus, Injections> injections = new LinkedHashMap<>();
        for (LfBus bus : buses) {
            bus.setDisabled(false);
            injections.put(bus, saveAndZero(bus));
        }
        branches.forEach(b -> b.setDisabled(false));
        anchor.setDisabled(false);
        network.getConnectivity().addEdge(anchor.getBus1(), anchor.getBus2(), anchor);
        return new Island(buses, anchor, injections);
    }

    private static Injections saveAndZero(LfBus bus) {
        List<LfLoad> loads = bus.getLoads();
        List<LfGenerator> generators = bus.getGenerators();
        Injections injections = new Injections(new double[loads.size()], new double[loads.size()], new double[loads.size()],
                new double[generators.size()], new double[generators.size()], new boolean[generators.size()]);
        for (int i = 0; i < loads.size(); i++) {
            LfLoad load = loads.get(i);
            injections.loadTargetP()[i] = load.getTargetP();
            injections.loadInitialTargetP()[i] = load.getInitialTargetP();
            injections.loadAbsVariableTargetP()[i] = load.getAbsVariableTargetP();
            load.setTargetP(0);
            load.setInitialTargetP(0);
            load.setAbsVariableTargetP(0);
        }
        for (int i = 0; i < generators.size(); i++) {
            LfGenerator generator = generators.get(i);
            injections.generatorTargetP()[i] = generator.getTargetP();
            injections.generatorInitialTargetP()[i] = generator.getInitialTargetP();
            injections.generatorParticipating()[i] = generator.isParticipating();
            generator.setTargetP(0);
            generator.setInitialTargetP(0);
            generator.setParticipating(false);
        }
        return injections;
    }

    /** Whether this branch is the anchor of an island: already closed in the base case's system, so no Woodbury element. */
    public boolean isAnchor(LfBranch branch) {
        Island island = islandByEntryBranch.get(branch);
        return island != null && island.anchor() == branch;
    }

    /** The anchor this branch replaces when an action closes it towards its island, or null. */
    public LfBranch replacedAnchor(LfBranch branch) {
        Island island = islandByEntryBranch.get(branch);
        return island != null && island.anchor() != branch ? island.anchor() : null;
    }

    /**
     * Energizes, on the network, the islands these actions close a branch towards: their injections back, and the anchor
     * disabled when another branch attaches the island. Undone by restoring the network state saved after
     * {@link #prepare}.
     */
    public void energize(List<LfAction> actions) {
        if (isEmpty()) {
            return;
        }
        Set<Island> energized = new HashSet<>();
        for (LfAction action : actions) {
            if (action instanceof AbstractLfBranchAction<?> branchAction) {
                for (LfBranch branch : branchAction.getEnabledBranches()) {
                    Island island = islandByEntryBranch.get(branch);
                    if (island != null && energized.add(island)) {
                        restoreInjections(island);
                    }
                    if (island != null && island.anchor() != branch) {
                        island.anchor().setDisabled(true);
                    }
                }
            }
        }
    }

    private static void restoreInjections(Island island) {
        island.injections().forEach((bus, injections) -> {
            List<LfLoad> loads = bus.getLoads();
            for (int i = 0; i < loads.size(); i++) {
                LfLoad load = loads.get(i);
                load.setTargetP(injections.loadTargetP()[i]);
                load.setInitialTargetP(injections.loadInitialTargetP()[i]);
                load.setAbsVariableTargetP(injections.loadAbsVariableTargetP()[i]);
            }
            List<LfGenerator> generators = bus.getGenerators();
            for (int i = 0; i < generators.size(); i++) {
                LfGenerator generator = generators.get(i);
                generator.setTargetP(injections.generatorTargetP()[i]);
                generator.setInitialTargetP(injections.generatorInitialTargetP()[i]);
                generator.setParticipating(injections.generatorParticipating()[i]);
            }
        });
    }
}
