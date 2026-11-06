/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.*;
import com.powsybl.math.graph.TraverseResult;

import java.util.*;

/**
 * @author Florian Dupuy {@literal <florian.dupuy at rte-france.com>}
 */
public class ContingencyTripping {

    private static final ContingencyTripping NO_OP_TRIPPING = new ContingencyTripping(Collections.emptyList(), (s, tt, nt, nbv) -> null);

    @FunctionalInterface
    private interface NodeBreakerTraverserFactory {
        VoltageLevel.NodeBreakerView.TopologyTraverser create(
                Set<Switch> stoppingSwitches, Set<Terminal> traversedTerminals, List<Terminal> neighbourTerminals,
                VoltageLevel.NodeBreakerView nodeBreakerView);
    }

    /**
     * Post-processing of the switches found by a traversal: it may replace the opening of a switch by the
     * disconnection of the terminals located behind it, when both are equivalent for the load flow.
     */
    @FunctionalInterface
    private interface SwitchesToOpenReducer {
        void reduce(Set<Switch> switchesToOpen, Set<Terminal> traversedTerminals);
    }

    private final List<? extends Terminal> terminals;
    private final NodeBreakerTraverserFactory nodeBreakerTraverserFactory;
    private final SwitchesToOpenReducer switchesToOpenReducer;

    public ContingencyTripping(List<? extends Terminal> terminals, NodeBreakerTraverserFactory nodeBreakerTraverserFactory) {
        this(terminals, nodeBreakerTraverserFactory, null);
    }

    private ContingencyTripping(List<? extends Terminal> terminals, NodeBreakerTraverserFactory nodeBreakerTraverserFactory,
                                SwitchesToOpenReducer switchesToOpenReducer) {
        this.terminals = terminals;
        this.nodeBreakerTraverserFactory = nodeBreakerTraverserFactory;
        this.switchesToOpenReducer = switchesToOpenReducer;
    }

    public ContingencyTripping(Terminal terminal, NodeBreakerTraverserFactory nodeBreakerTraverserFactory) {
        this(List.of(terminal), nodeBreakerTraverserFactory);
    }

    public static ContingencyTripping createBranchTripping(Network network, Branch<?> branch) {
        return createBranchTripping(network, branch, null);
    }

    public static ContingencyTripping createBranchTripping(Network network, Branch<?> branch, String voltageLevelId) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(branch);

        if (voltageLevelId != null) {
            if (voltageLevelId.equals(branch.getTerminal1().getVoltageLevel().getId())) {
                return new ContingencyTripping(branch.getTerminal1(), NodeBreakerTraverser::new);
            } else if (voltageLevelId.equals(branch.getTerminal2().getVoltageLevel().getId())) {
                return new ContingencyTripping(branch.getTerminal2(), NodeBreakerTraverser::new);
            } else {
                throw new PowsyblException("VoltageLevel '" + voltageLevelId + "' not connected to branch '" + branch.getId() + "'");
            }
        } else {
            return new ContingencyTripping(List.of(branch.getTerminal1(), branch.getTerminal2()), NodeBreakerTraverser::new);
        }
    }

    public static ContingencyTripping createInjectionTripping(Network network, Injection<?> injection) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(injection);

        return new ContingencyTripping(injection.getTerminal(), NodeBreakerTraverser::new);
    }

    public static ContingencyTripping createThreeWindingsTransformerTripping(Network network, ThreeWindingsTransformer twt) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(twt);

        return new ContingencyTripping(twt.getTerminals(), NodeBreakerTraverser::new);
    }

    public static ContingencyTripping createBusbarSectionMinimalTripping(Network network, BusbarSection bbs) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(bbs);

        NodeBreakerTraverserFactory minimalTraverserFactory = (stoppingSwitches, traversedTerminals, neighbourTerminals, nbv) ->
            // To have the minimal tripping ("no propagation") with a busbar section we still need to traverse the
            // voltage level starting from that busbar section, stopping at first switch encountered (which will be
            // marked as retained afterwards), in order to have the smallest lost bus in breaker view
            // Note that neighbourTerminals is not filled up: we don't want to propagate to neighbouring voltage levels as
            // this is a minimal tripping ("no propagation")
            (nodeBefore, sw, nodeAfter) -> {
                if (sw != null) {
                    if (!sw.isOpen()) {
                        stoppingSwitches.add(sw);
                    }
                    return TraverseResult.TERMINATE_PATH;
                } else {
                    nbv.getOptionalTerminal(nodeAfter).ifPresent(traversedTerminals::add);
                    return TraverseResult.CONTINUE;
                }
            };
        return new ContingencyTripping(List.of(bbs.getTerminal()), minimalTraverserFactory,
            (switchesToOpen, traversedTerminals) -> reduceBusbarSectionMinimalTripping(bbs, switchesToOpen, traversedTerminals));
    }

    private static int find(int[] parent, int node) {
        int root = node;
        while (parent[root] != root) {
            root = parent[root];
        }
        int current = node;
        while (parent[current] != root) {
            int next = parent[current];
            parent[current] = root;
            current = next;
        }
        return root;
    }

    private static void union(int[] parent, int node1, int node2) {
        int root1 = find(parent, node1);
        int root2 = find(parent, node2);
        if (root1 != root2) {
            parent[root1] = root2;
        }
    }

    private static boolean isBranchLike(IdentifiableType type) {
        return type == IdentifiableType.LINE
                || type == IdentifiableType.TWO_WINDINGS_TRANSFORMER
                || type == IdentifiableType.THREE_WINDINGS_TRANSFORMER
                || type == IdentifiableType.BOUNDARY_LINE;
    }

    private static boolean isSupportedByTerminalDisconnection(Terminal terminal) {
        Connectable<?> connectable = terminal.getConnectable();
        return switch (connectable.getType()) {
            case LINE, TWO_WINDINGS_TRANSFORMER, THREE_WINDINGS_TRANSFORMER, BOUNDARY_LINE,
                 GENERATOR, STATIC_VAR_COMPENSATOR, BATTERY, LOAD, HVDC_CONVERTER_STATION, BUSBAR_SECTION -> true;
            case SHUNT_COMPENSATOR -> !((ShuntCompensator) connectable).isRegulating(); // regulating shunt loss is not supported
            default -> false;
        };
    }

    private record FarComponent(List<Terminal> terminals, boolean alive, boolean supported) {
    }

    private static FarComponent createFarComponent(List<Terminal> terminals) {
        boolean hasBusbarSection = terminals.stream().anyMatch(t -> t.getConnectable().getType() == IdentifiableType.BUSBAR_SECTION);
        long branchCount = terminals.stream().filter(t -> isBranchLike(t.getConnectable().getType())).count();
        boolean alive = hasBusbarSection || branchCount >= 2 || branchCount == 1 && terminals.size() >= 2;
        boolean supported = terminals.stream().allMatch(ContingencyTripping::isSupportedByTerminalDisconnection);
        return new FarComponent(terminals, alive, supported);
    }

    /**
     * Once the busbar section is lost, each part of the voltage level located behind a stopping switch is either:
     * <ul>
     *     <li>a dead end bay holding a single branch terminal: the branch is just open at that side,</li>
     *     <li>a dead end bay holding only injections: they are lost,</li>
     *     <li>a part still alive (another busbar section, several branches, or a branch feeding injections).</li>
     * </ul>
     * For the two first cases, the load flow result is the same if, instead of retaining and opening the switch, we
     * directly disconnect the terminals located behind it. If at most one part is still alive, it is just the
     * remaining of the bus the busbar section belonged to, so there is no need to retain the switch leading to it
     * either. Switches are only retained when at least two parts remain alive, as they have to become separate buses.
     * This avoids creating one additional bus (and one zero impedance branch) per feeder bay in the load flow network.
     */
    private static void reduceBusbarSectionMinimalTripping(BusbarSection bbs, Set<Switch> switchesToOpen, Set<Terminal> traversedTerminals) {
        if (switchesToOpen.isEmpty()) {
            return;
        }
        VoltageLevel.NodeBreakerView nbv = bbs.getTerminal().getVoltageLevel().getNodeBreakerView();

        // connected components of the voltage level once the stopping switches are open
        int[] parent = new int[nbv.getMaximumNodeIndex() + 1];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (VoltageLevel.NodeBreakerView.InternalConnection ic : nbv.getInternalConnections()) {
            union(parent, ic.getNode1(), ic.getNode2());
        }
        for (Switch sw : nbv.getSwitches()) {
            if (!sw.isOpen() && !switchesToOpen.contains(sw)) {
                union(parent, nbv.getNode1(sw.getId()), nbv.getNode2(sw.getId()));
            }
        }
        Map<Integer, List<Terminal>> terminalsByComponent = new HashMap<>();
        for (int node : nbv.getNodes()) {
            nbv.getOptionalTerminal(node)
                    .ifPresent(t -> terminalsByComponent.computeIfAbsent(find(parent, node), k -> new ArrayList<>()).add(t));
        }

        int bbsComponent = find(parent, bbs.getTerminal().getNodeBreakerView().getNode());
        Map<Switch, Integer> farComponentBySwitch = new LinkedHashMap<>();
        Map<Integer, FarComponent> farComponents = new HashMap<>();
        for (Switch sw : switchesToOpen) {
            int component1 = find(parent, nbv.getNode1(sw.getId()));
            int component2 = find(parent, nbv.getNode2(sw.getId()));
            int farComponent;
            if (component1 == bbsComponent && component2 != bbsComponent) {
                farComponent = component2;
            } else if (component2 == bbsComponent && component1 != bbsComponent) {
                farComponent = component1;
            } else {
                continue; // not a cut between the busbar section and something else: keep the switch
            }
            farComponentBySwitch.put(sw, farComponent);
            farComponents.computeIfAbsent(farComponent, k -> createFarComponent(terminalsByComponent.getOrDefault(k, Collections.emptyList())));
        }
        long aliveCount = farComponents.values().stream().filter(c -> c.alive() || !c.supported()).count();
        for (var e : farComponentBySwitch.entrySet()) {
            FarComponent farComponent = farComponents.get(e.getValue());
            if (!farComponent.supported()) {
                continue; // keep the switch: what is behind cannot be disconnected by terminal
            }
            if (farComponent.alive()) {
                if (aliveCount <= 1) {
                    switchesToOpen.remove(e.getKey()); // this part is just what remains of the bus: nothing to split
                }
            } else {
                // opening the switch is equivalent to disconnecting what is behind it
                switchesToOpen.remove(e.getKey());
                traversedTerminals.addAll(farComponent.terminals());
            }
        }
    }

    public static ContingencyTripping createContingencyTripping(Network network, Identifiable<?> identifiable) {
        switch (identifiable.getType()) {
            case LINE,
                 TWO_WINDINGS_TRANSFORMER,
                 TIE_LINE:
                return ContingencyTripping.createBranchTripping(network, (Branch<?>) identifiable);
            case BOUNDARY_LINE,
                 GENERATOR,
                 LOAD,
                 SHUNT_COMPENSATOR,
                 STATIC_VAR_COMPENSATOR,
                 BUSBAR_SECTION,
                 BATTERY:
                return ContingencyTripping.createInjectionTripping(network, (Injection<?>) identifiable);
            case THREE_WINDINGS_TRANSFORMER:
                return ContingencyTripping.createThreeWindingsTransformerTripping(network, (ThreeWindingsTransformer) identifiable);
            case HVDC_LINE,
                 SWITCH:
                return ContingencyTripping.NO_OP_TRIPPING;
            default:
                throw new UnsupportedOperationException("Unsupported contingency element type: " + identifiable.getType());
        }
    }

    public void traverse(Set<Switch> switchesToOpen, Set<Terminal> terminalsToDisconnect) {
        Set<Terminal> traversedTerminals = new HashSet<>();
        if (switchesToOpenReducer == null) {
            terminals.forEach(t -> traverseFromTerminal(t, switchesToOpen, traversedTerminals));
        } else {
            Set<Switch> localSwitchesToOpen = new LinkedHashSet<>();
            terminals.forEach(t -> traverseFromTerminal(t, localSwitchesToOpen, traversedTerminals));
            switchesToOpenReducer.reduce(localSwitchesToOpen, traversedTerminals);
            switchesToOpen.addAll(localSwitchesToOpen);
        }
        terminalsToDisconnect.addAll(traversedTerminals);
    }

    /**
     * Recursive method to calculate the switches to open and the traversed terminals from a terminal
     * @param terminal starting terminal
     * @param switchesToOpen set of switches which would be opened by the contingency propagation from terminal
     * @param traversedTerminals set of terminals traversed by the contingency propagation
     */
    private void traverseFromTerminal(Terminal terminal, Set<Switch> switchesToOpen, Set<Terminal> traversedTerminals) {
        Objects.requireNonNull(terminal);
        Objects.requireNonNull(switchesToOpen);
        Objects.requireNonNull(traversedTerminals);

        if (traversedTerminals.contains(terminal)) {
            return;
        }

        if (terminal.getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER) {
            traversedTerminals.add(terminal);
            List<Terminal> neighbourTerminals = traverseNodeBreakerVoltageLevelsFromTerminal(terminal, switchesToOpen, traversedTerminals);

            // Recursive call to continue the traverser in affected neighbouring voltage levels
            neighbourTerminals.forEach(t -> traverseFromTerminal(t, switchesToOpen, traversedTerminals));
        } else {
            // In bus breaker view we have no idea what kind of switch it was in the initial node/breaker topology
            // so to keep things simple we do not propagate the fault
            if (terminal.isConnected()) {
                traversedTerminals.add(terminal);
            }
        }
    }

    private List<Terminal> traverseNodeBreakerVoltageLevelsFromTerminal(Terminal terminal, Set<Switch> switchesToOpen,
                                                                        Set<Terminal> traversedTerminals) {
        int initNode = terminal.getNodeBreakerView().getNode();
        VoltageLevel.NodeBreakerView nodeBreakerView = terminal.getVoltageLevel().getNodeBreakerView();

        List<Terminal> neighbourTerminals = new ArrayList<>();
        VoltageLevel.NodeBreakerView.TopologyTraverser traverser = nodeBreakerTraverserFactory.create(
                switchesToOpen, traversedTerminals, neighbourTerminals, nodeBreakerView);
        nodeBreakerView.traverse(initNode, traverser);

        return neighbourTerminals;
    }

}
