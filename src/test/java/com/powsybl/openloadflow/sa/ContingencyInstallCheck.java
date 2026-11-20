/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.*;
import com.powsybl.openloadflow.ac.AcFixedJacobianMatrix;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.fixed.FixedEquationSystemIndex;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.ElementStates;
import com.powsybl.openloadflow.network.impl.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Checks that installing a contingency's record ({@link AcContingencyDelta#install}) on the base state leaves the
 * network as OLF's rules leave it: {@link LfContingency#apply} then the spreading of the loss, as a security analysis
 * does. For each contingency: apply, save the state of every bus and branch, record, restore, install, compare.
 * Arguments: the network, the load flow parameters (.json), the contingencies (.json), how many (0: all).
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class ContingencyInstallCheck {

    private ContingencyInstallCheck() {
    }

    private static int[] modesOrNull(FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed) {
        try {
            return fixed.getModes().clone();
        } catch (com.powsybl.commons.PowsyblException e) {
            return null;
        }
    }

    /** The reactive target of each bus, which the bus states compare only when it is frozen. */
    private static double[] generationTargetQ(LfNetwork network) {
        return network.getBuses().stream().mapToDouble(b -> b.isDisabled() ? Double.NaN : b.getGenerationTargetQ()).toArray();
    }

    private static String category(String difference) {
        // "bus X: target P of generator Y" -> "bus: target P of generator"
        String kind = difference.substring(0, difference.indexOf(' '));
        String rest = difference.substring(difference.indexOf(": ") + 2);
        return kind + ": " + rest.replaceAll("(generator|load|shunt) \\S+", "$1");
    }

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("iidm.import.repair-invalid-reactive-curve-limits", "true");
        Network network = Network.read(Path.of(args[0]), com.powsybl.computation.local.LocalComputationManager.getDefault(),
                com.powsybl.iidm.network.ImportConfig.load(), properties);
        LoadFlowParameters parameters = com.powsybl.loadflow.json.JsonLoadFlowParameters.read(Path.of(args[1]));
        OpenLoadFlowParameters ext = OpenLoadFlowParameters.get(parameters);
        var mapper = com.powsybl.commons.json.JsonUtil.createObjectMapper().registerModule(new com.powsybl.contingency.json.ContingencyJsonModule());
        List<Contingency> all = mapper.readValue(Path.of(args[2]).toFile(), new com.fasterxml.jackson.core.type.TypeReference<List<Contingency>>() { });
        int count = Integer.parseInt(args[3]);
        List<Contingency> contingencies = count <= 0 ? all : all.subList(0, Math.min(count, all.size()));
        LfTopoConfig topoConfig = new LfTopoConfig();
        List<PropagatedContingency> propagated = PropagatedContingency.createList(network, contingencies, topoConfig,
                new PropagatedContingencyCreationParameters().setContingencyPropagation(true)
                        .setShuntCompensatorVoltageControlOn(parameters.isShuntCompensatorVoltageControlOn())
                        .setSlackDistributionOnConformLoad(parameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD)
                        .setHvdcAcEmulation(parameters.isHvdcAcEmulation()));
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, ext, new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), !topoConfig.getSwitchesToOpen().isEmpty(), false);
        try (LfNetworkList networks = Networks.loadWithReconnectableElements(network, topoConfig, acParameters.getNetworkParameters(), ReportNode.NO_OP)) {
            LfNetwork net = networks.getLargest().orElseThrow();
            try (AcLoadFlowContext context = new AcLoadFlowContext(net, acParameters)) {
                new AcloadFlowEngine(context).run();
                FixedEquationSystemIndex<AcVariableType, AcEquationType> fixed = ((AcFixedJacobianMatrix) context.getJacobianMatrix()).getFixedJacobian().getFixedEquationSystemIndex();
                AcContingencyDelta.Baseline base = AcContingencyDelta.baseline(context);
                NetworkState state = NetworkState.save(net);
                // what each contingency touches, as the security analysis collects it
                ModifiedElementsCollector touched = new ModifiedElementsCollector(net);
                net.addListener(touched);
                var saParameters = new com.powsybl.security.SecurityAnalysisParameters().setLoadFlowParameters(parameters);
                var lossDistribution = new com.powsybl.openloadflow.sa.DefaultContingencyActivePowerLossDistribution();
                Map<String, Integer> categories = new TreeMap<>();
                Map<String, String> examples = new TreeMap<>();
                int checked = 0;
                int exact = 0;
                int recompiled = 0;
                for (PropagatedContingency pc : propagated) {
                    Optional<LfContingency> c = pc.toLfContingency(net);
                    if (c.isEmpty()) {
                        continue;
                    }
                    // OLF's rules
                    touched.reset();
                    touched.setEnabled(true);
                    c.get().apply(parameters.getBalanceType());
                    touched.add(c.get());
                    double spread = lossDistribution.run(net, c.get(), pc.getContingency(), saParameters, null, ReportNode.NO_OP);
                    List<BusState> busStates = ElementState.save(net.getBuses(), BusState::save);
                    List<BranchState> branchStates = ElementState.save(net.getBranches(), BranchState::save);
                    double[] generationTargetQ = generationTargetQ(net);
                    double[] hvdc = net.getHvdcs().stream().flatMapToDouble(h -> Arrays.stream(new double[] {h.isDisabled() ? 1 : 0,
                        h.getConverterStation1().getTargetP(), h.getConverterStation2().getTargetP()})).toArray();
                    LfSynchronousNetwork main = net.getSynchronousNetworks().getFirst();
                    List<LfBus> slack = List.copyOf(main.getSlackBuses());
                    LfBus reference = main.getReferenceBus();
                    int[] modes = modesOrNull(fixed);
                    double[] targets = context.getTargetVector().getArray().clone();
                    AcContingencyDelta d = AcContingencyDelta.record(c.get(), context, base, spread, touched.getBuses(), touched.getBranches());
                    touched.setEnabled(false);
                    state.restore();
                    if (!d.onBaseStructure()) {
                        recompiled++;
                    }
                    // the record alone
                    d.install(net, base);
                    Set<String> found = new TreeSet<>();
                    for (BusState s : busStates) {
                        String diff = ElementStates.findDifference(s);
                        if (diff != null) {
                            found.add(category("bus " + ElementStates.element(s).getId() + ": " + diff));
                            if (pc.getContingency().getId().equals(System.getProperty("show"))) {
                                LfBus b = ElementStates.element(s);
                                System.out.printf("%s bus %s: %s; after install frozen %s targetQ %s, voltage control %s, generators %s%n", // NOSONAR
                                    pc.getContingency().getId(), b.getId(), diff, b.isGenerationTargetQFrozen(), b.getGenerationTargetQ(), b.isGeneratorVoltageControlEnabled(),
                                    b.getGenerators().stream().map(g -> g.getId() + ":" + g.getGeneratorControlType() + (g.isDisabled() ? ":disabled" : "")).toList());
                            }
                        }
                    }
                    for (BranchState s : branchStates) {
                        String diff = ElementStates.findDifference(s);
                        if (diff != null) {
                            found.add(category("branch " + ElementStates.element(s).getId() + ": " + diff));
                        }
                    }
                    double[] hvdcAfter = net.getHvdcs().stream().flatMapToDouble(h -> Arrays.stream(new double[] {h.isDisabled() ? 1 : 0,
                        h.getConverterStation1().getTargetP(), h.getConverterStation2().getTargetP()})).toArray();
                    if (!Arrays.equals(hvdc, hvdcAfter)) {
                        found.add("hvdc: disabled or converter target P");
                    }
                    if (!java.util.Arrays.equals(generationTargetQ, generationTargetQ(net))) {
                        found.add("bus: generation target Q");
                    }
                    if (!slack.equals(main.getSlackBuses()) || reference != main.getReferenceBus()) {
                        found.add("slack or reference bus");
                    }
                    if (!Arrays.equals(modes, modesOrNull(fixed))) {
                        found.add("positions: modes");
                    }
                    if (!Arrays.equals(targets, context.getTargetVector().getArray())) {
                        found.add("target vector");
                    }
                    checked++;
                    if (found.isEmpty()) {
                        exact++;
                    }
                    for (String f : found) {
                        categories.merge(f, 1, Integer::sum);
                        examples.putIfAbsent(f, pc.getContingency().getId());
                    }
                    state.restore();
                    String left = ElementStates.findDifference(state);
                    if (left != null) {
                        throw new IllegalStateException("not restored after " + pc.getContingency().getId() + ": " + left);
                    }
                }
                System.out.printf("%d contingencies checked, %d installed exactly, %d with positions off the base structure%n", checked, exact, recompiled); // NOSONAR
                categories.forEach((k, v) -> System.out.printf("  %5d  %s   (e.g. %s)%n", v, k, examples.get(k))); // NOSONAR
            }
        }
    }
}
