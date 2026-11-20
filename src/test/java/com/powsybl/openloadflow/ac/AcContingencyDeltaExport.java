/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.fixed.FixedEquationSystem;
import com.powsybl.openloadflow.equations.fixed.FixedStructure;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.*;

import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;

/**
 * Writes the record of each contingency of a security analysis, by element id, for olf-gpu's
 * CpuContingencyDeltaReferenceTest, which compares it with the device's batch plan: the two OLF lines do not share an
 * API. Arguments: the network, the load flow parameters (.json), the contingencies (.json), how many, the file to write.
 * Each contingency is applied, its loss spread as a security analysis does, recorded and restored.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class AcContingencyDeltaExport {

    private AcContingencyDeltaExport() {
    }

    static String name(LfNetwork net, AcVariableType type, int num) {
        String id = switch (type.getElementType()) {
            case BUS -> net.getBus(num).getId();
            case BRANCH -> net.getBranch(num).getId();
            case SHUNT_COMPENSATOR -> net.getShunt(num).getId();
            default -> String.valueOf(num);
        };
        return type + ":" + id;
    }

    static String name(LfNetwork net, AcEquationType type, int num) {
        String id = switch (type.getElementType()) {
            case BUS -> net.getBus(num).getId();
            case BRANCH -> net.getBranch(num).getId();
            case SHUNT_COMPENSATOR -> net.getShunt(num).getId();
            default -> String.valueOf(num);
        };
        return type + ":" + id;
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
        List<Contingency> contingencies = all.subList(0, Math.min(Integer.parseInt(args[3]), all.size()));
        LfTopoConfig topoConfig = new LfTopoConfig();
        List<PropagatedContingency> propagated = PropagatedContingency.createList(network, contingencies, topoConfig,
                new PropagatedContingencyCreationParameters().setContingencyPropagation(true)
                        .setShuntCompensatorVoltageControlOn(parameters.isShuntCompensatorVoltageControlOn())
                        .setSlackDistributionOnConformLoad(parameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD)
                        .setHvdcAcEmulation(parameters.isHvdcAcEmulation()));
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, ext, new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), !topoConfig.getSwitchesToOpen().isEmpty(), false);
        try (LfNetworkList networks = Networks.loadWithReconnectableElements(network, topoConfig, acParameters.getNetworkParameters(), ReportNode.NO_OP);
             PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[4])))) {
            LfNetwork net = networks.getLargest().orElseThrow();
            try (AcLoadFlowContext context = new AcLoadFlowContext(net, acParameters)) {
                new AcloadFlowEngine(context).run();
                FixedEquationSystem<AcVariableType, AcEquationType> fixed = ((AcJacobianMatrix) context.getJacobianMatrix()).getFixedJacobian().getFixedEquationSystem();
                AcContingencyDelta.Baseline base = AcContingencyDelta.baseline(context);
                FixedStructure<AcVariableType, AcEquationType> structure = fixed.getStructure();
                NetworkState state = NetworkState.save(net);
                for (LfBus bus : net.getBuses()) {
                    for (LfGenerator g : bus.getGenerators()) {
                        out.println("BG\t" + g.getId() + "\t" + Double.toHexString(g.getTargetP()));
                    }
                }
                for (LfBus bus : net.getBuses()) {
                    out.println("BRP\t" + bus.getId() + "\t" + Double.toHexString(bus.getRemoteControlReactivePercent()));
                }
                out.println("BSL\t" + net.getSynchronousNetworks().getFirst().getSlackBuses().getFirst().getId());
                var saParameters = new com.powsybl.security.SecurityAnalysisParameters().setLoadFlowParameters(parameters);
                var lossDistribution = new com.powsybl.openloadflow.sa.DefaultContingencyActivePowerLossDistribution();
                // the base target of the equation each slot holds
                double[] baseTargets = base.getTargets();
                for (int slot = 0; slot < baseTargets.length; slot++) {
                    int e = fixed.getEquationOfSlot(slot);
                    if (e >= 0) {
                        out.println("B\t" + name(net, structure.getType(e), structure.getElementNum(e)) + "\t" + Double.toHexString(baseTargets[slot]));
                    }
                }
                int written = 0;
                for (PropagatedContingency pc : propagated) {
                    Optional<LfContingency> c = pc.toLfContingency(net);
                    if (c.isEmpty()) {
                        continue;
                    }
                    c.get().apply(parameters.getBalanceType());
                    double spread = lossDistribution.run(net, c.get(), pc.getContingency(), saParameters, null, ReportNode.NO_OP);
                    AcContingencyDelta d = AcContingencyDelta.record(c.get(), context, base, spread);
                    if (!d.onBaseStructure()) {
                        out.println("C\t" + pc.getContingency().getId() + "\tRECOMPILED");
                    } else {
                        out.println("C\t" + pc.getContingency().getId() + "\t" + Double.toHexString(d.activePowerLoss()));
                        out.println("P\t" + Double.toHexString(d.preDistributedActivePower()));
                        out.println("SL\t" + net.getBus(d.slackBuses()[0]).getId());
                        for (int k = 0; k < d.reactivePercentBuses().length; k++) {
                            out.println("RP\t" + net.getBus(d.reactivePercentBuses()[k]).getId() + "\t" + Double.toHexString(d.reactivePercents()[k]));
                        }
                        for (int k = 0; k < d.shunts().length; k++) {
                            out.println("SH\t" + net.getShunt(d.shunts()[k]).getId() + "\t" + Double.toHexString(d.shuntG()[k]) + "\t" + Double.toHexString(d.shuntB()[k]));
                        }
                        for (int k = 0; k < d.tapBranches().length; k++) {
                            out.println("TP\t" + net.getBranch(d.tapBranches()[k]).getId() + "\t" + d.tapPositions()[k]
                                    + "\t" + Double.toHexString(d.tapR1()[k]) + "\t" + Double.toHexString(d.tapA1()[k]));
                        }
                        for (int g = 0; g < d.generatorIds().length; g++) {
                            out.println("G\t" + d.generatorIds()[g] + "\t" + Double.toHexString(d.generatorTargetP()[g])
                                    + "\t" + Double.toHexString(d.generatorInitialTargetP()[g]));
                        }
                        for (int k = 0; k < d.slots().length; k++) {
                            Variable<AcVariableType> v = structure.getVariable(d.slots()[k]);
                            String eq = "NONE";
                            if (d.modes()[k] >= 0) {
                                int e = structure.getAlternatives(d.slots()[k])[d.modes()[k]];
                                eq = name(net, structure.getType(e), structure.getElementNum(e));
                            } else if (d.modes()[k] == FixedEquationSystem.MODE_OTHER) {
                                eq = "OTHER";
                            }
                            out.println("S\t" + name(net, v.getType(), v.getElementNum()) + "\t" + eq + "\t" + Double.toHexString(d.targets()[k]));
                        }
                        written++;
                    }
                    state.restore();
                }
                System.out.println("written " + written + " of " + propagated.size()); // NOSONAR
            }
        }
    }
}
