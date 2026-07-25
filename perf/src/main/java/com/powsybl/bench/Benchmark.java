/*
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.bench;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.sa.OpenSecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysis;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisRunParameters;
import com.powsybl.sensitivity.SensitivityAnalysis;
import com.powsybl.sensitivity.SensitivityAnalysisParameters;
import com.powsybl.sensitivity.SensitivityFactor;
import com.powsybl.sensitivity.SensitivityFunctionType;
import com.powsybl.sensitivity.SensitivityVariableType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Branch-independent performance harness for powsybl-open-loadflow.
 *
 * <p>Runs a chosen computation (load flow, security analysis, sensitivity analysis) on a network, with a warmup phase
 * and N timed repetitions, and prints min / median / mean / p90 wall-clock times. It uses only the public powsybl API,
 * so the same jar benchmarks any OLF build. See README.md for the before/after methodology.
 */
public final class Benchmark {

    private Benchmark() {
    }

    private enum Compute { LF, SA, SENSI }

    private static final class Config {
        String network = "ieee300";
        Compute compute = Compute.SA;
        boolean dc = true;
        boolean fastDc = true;
        int threads = 1;
        int contingencies = -1;   // -1 = all N-1 branch contingencies
        int factors = 20;         // sensi: monitored branches x injections cap
        int warmup = 3;
        int measure = 10;
        boolean rateBranches = false;  // add an active power limit on every branch (reproduces a "rated" network)
    }

    public static void main(String[] args) {
        Config c = parseArgs(args);

        Network network = loadNetwork(c.network);
        if (c.rateBranches) {
            rateBranches(network);
        }
        String vid = network.getVariantManager().getWorkingVariantId();
        List<Contingency> contingencies = buildBranchContingencies(network, c.contingencies);

        System.out.printf("network=%s buses=%d branches=%d contingencies=%d compute=%s dc=%s fastDc=%s threads=%d%n",
                c.network, network.getBusView().getBusStream().count(), network.getBranchCount(), contingencies.size(),
                c.compute, c.dc, c.fastDc, c.threads);

        Supplier<Object> task = buildTask(c, network, vid, contingencies);

        // warmup
        for (int i = 0; i < c.warmup; i++) {
            task.get();
        }
        // measure
        long[] nanos = new long[c.measure];
        for (int i = 0; i < c.measure; i++) {
            long t0 = System.nanoTime();
            task.get();
            nanos[i] = System.nanoTime() - t0;
        }

        Arrays.sort(nanos);
        double min = nanos[0] / 1e6;
        double median = nanos[nanos.length / 2] / 1e6;
        double p90 = nanos[(int) Math.min(nanos.length - 1L, Math.round(0.9 * nanos.length))] / 1e6;
        double mean = Arrays.stream(nanos).average().orElse(0) / 1e6;
        System.out.printf("RESULT compute=%s dc=%s fastDc=%s threads=%d contingencies=%d warmup=%d measure=%d "
                        + "min=%.1fms median=%.1fms mean=%.1fms p90=%.1fms%n",
                c.compute, c.dc, c.fastDc, c.threads, contingencies.size(), c.warmup, c.measure, min, median, mean, p90);
    }

    private static Supplier<Object> buildTask(Config c, Network network, String vid, List<Contingency> contingencies) {
        return switch (c.compute) {
            case LF -> {
                LoadFlowParameters lfp = new LoadFlowParameters().setDc(c.dc);
                yield () -> {
                    // run each iteration on a fresh clone of the base variant so the load flow always starts cold
                    String tmp = "bench-" + System.identityHashCode(network) + "-lf";
                    network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, tmp, true);
                    network.getVariantManager().setWorkingVariant(tmp);
                    Object r = LoadFlow.run(network, tmp, LocalComputationManager.getDefault(), lfp);
                    network.getVariantManager().setWorkingVariant(vid);
                    network.getVariantManager().removeVariant(tmp);
                    return r;
                };
            }
            case SA -> {
                SecurityAnalysisParameters sap = new SecurityAnalysisParameters();
                sap.getLoadFlowParameters().setDc(c.dc);
                sap.addExtension(OpenSecurityAnalysisParameters.class,
                        new OpenSecurityAnalysisParameters().setDcFastMode(c.fastDc).setThreadCount(c.threads));
                yield () -> SecurityAnalysis.run(network, contingencies,
                        new SecurityAnalysisRunParameters().setSecurityAnalysisParameters(sap));
            }
            case SENSI -> {
                List<SensitivityFactor> factors = buildSensitivityFactors(network, c.factors);
                SensitivityAnalysisParameters sp = new SensitivityAnalysisParameters();
                sp.getLoadFlowParameters().setDc(c.dc);
                yield () -> SensitivityAnalysis.run(network, factors, contingencies, List.of(), sp);
            }
        };
    }

    /**
     * Add an active power limit on both sides of every branch, so the per-branch limit lookup / resolution path of the
     * fast-DC security analysis is exercised (the fast-DC violation-detection PRs profile "rated" networks). The limit
     * value is high enough not to be violated, isolating the limit-lookup cost from violation reporting.
     */
    private static void rateBranches(Network network) {
        for (Branch<?> branch : network.getBranches()) {
            branch.newOperationalLimitsGroup1("bench").newActivePowerLimits().setPermanentLimit(1e5).add();
            branch.setSelectedOperationalLimitsGroup1("bench");
            branch.newOperationalLimitsGroup2("bench").newActivePowerLimits().setPermanentLimit(1e5).add();
            branch.setSelectedOperationalLimitsGroup2("bench");
        }
    }

    private static List<Contingency> buildBranchContingencies(Network network, int max) {
        List<Contingency> contingencies = new ArrayList<>();
        for (Branch<?> branch : network.getBranches()) {
            contingencies.add(Contingency.branch(branch.getId()));
            if (max >= 0 && contingencies.size() >= max) {
                break;
            }
        }
        return contingencies;
    }

    private static List<SensitivityFactor> buildSensitivityFactors(Network network, int cap) {
        List<String> branchIds = network.getBranchStream().limit((long) Math.ceil(Math.sqrt(cap))).map(Branch::getId).toList();
        List<String> generatorIds = network.getGeneratorStream().limit((long) Math.ceil(Math.sqrt(cap))).map(Generator::getId).toList();
        List<SensitivityFactor> factors = new ArrayList<>();
        for (String branchId : branchIds) {
            for (String generatorId : generatorIds) {
                factors.add(new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, branchId,
                        SensitivityVariableType.INJECTION_ACTIVE_POWER, generatorId, false, ContingencyContext.all()));
                if (factors.size() >= cap) {
                    return factors;
                }
            }
        }
        return factors;
    }

    private static Network loadNetwork(String spec) {
        Map<String, Supplier<Network>> builtIn = Map.of(
                "ieee14", IeeeCdfNetworkFactory::create14,
                "ieee57", IeeeCdfNetworkFactory::create57,
                "ieee118", IeeeCdfNetworkFactory::create118,
                "ieee300", IeeeCdfNetworkFactory::create300);
        Supplier<Network> factory = builtIn.get(spec);
        if (factory != null) {
            return factory.get();
        }
        // otherwise treat spec as a network file path (e.g. a pegase .xiidm / .mat / UCTE file)
        return Network.read(Path.of(spec));
    }

    private static Config parseArgs(String[] args) {
        Config c = new Config();
        for (int i = 0; i < args.length - 1; i += 2) {
            String value = args[i + 1];
            switch (args[i]) {
                case "--network" -> c.network = value;
                case "--compute" -> c.compute = Compute.valueOf(value.toUpperCase());
                case "--dc" -> c.dc = Boolean.parseBoolean(value);
                case "--fast-dc" -> c.fastDc = Boolean.parseBoolean(value);
                case "--threads" -> c.threads = Integer.parseInt(value);
                case "--contingencies" -> c.contingencies = "all".equalsIgnoreCase(value) ? -1 : Integer.parseInt(value);
                case "--factors" -> c.factors = Integer.parseInt(value);
                case "--warmup" -> c.warmup = Integer.parseInt(value);
                case "--measure" -> c.measure = Integer.parseInt(value);
                case "--rate-branches" -> c.rateBranches = Boolean.parseBoolean(value);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        return c;
    }
}
