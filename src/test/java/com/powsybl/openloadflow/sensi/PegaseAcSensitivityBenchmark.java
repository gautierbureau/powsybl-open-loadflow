/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.matpower.model.MBranch;
import com.powsybl.matpower.model.MBus;
import com.powsybl.matpower.model.MGen;
import com.powsybl.matpower.model.MatpowerFormatVersion;
import com.powsybl.matpower.model.MatpowerModel;
import com.powsybl.matpower.model.MatpowerWriter;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.sensitivity.SensitivityAnalysis;
import com.powsybl.sensitivity.SensitivityAnalysisParameters;
import com.powsybl.sensitivity.SensitivityAnalysisResult;
import com.powsybl.sensitivity.SensitivityFactor;
import com.powsybl.sensitivity.SensitivityFactorModelReader;
import com.powsybl.sensitivity.SensitivityFunctionType;
import com.powsybl.sensitivity.SensitivityVariableType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual benchmark of AC sensitivity analysis on large PEGASE cases (up to 13 659 buses) with a very large number of
 * sensitivity factors. It is <b>not</b> a unit test: it is disabled unless the {@code bench} system property is set,
 * and it measures wall-clock time rather than asserting anything.
 *
 * <p>Because the powsybl MATPOWER importer reads the binary {@code .mat} format while the upstream PEGASE cases are
 * distributed as text {@code .m} M-files, the benchmark first parses the {@code .m} file into a
 * {@link MatpowerModel} and writes it back as {@code .mat}, then imports it as a {@link Network}.
 *
 * <p>Typical invocation (from the project root):
 * <pre>
 *   mvn -q test-compile
 *   mvn -q test -Dbench=true -Dtest=PegaseAcSensitivityBenchmark \
 *       -Dbench.case=/path/to/case13659pegase.m \
 *       -Dbench.functions=2000 -Dbench.variables=200 -Dbench.contingencies=0
 * </pre>
 * The number of factors created is {@code functions x variables}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
class PegaseAcSensitivityBenchmark {

    private static final Logger LOGGER = LoggerFactory.getLogger(PegaseAcSensitivityBenchmark.class);

    private static int intProp(String name, int defaultValue) {
        String v = System.getProperty(name);
        return v == null ? defaultValue : Integer.parseInt(v);
    }

    @Test
    void benchmark() throws IOException {
        String casePath = System.getProperty("bench.case");
        if (casePath == null) {
            throw new IllegalStateException("Set -Dbench.case=/path/to/caseXXXXpegase.m");
        }
        int nbFunctions = intProp("bench.functions", 2000);
        int nbVariables = intProp("bench.variables", 200);
        int nbContingencies = intProp("bench.contingencies", 0);
        int warmup = intProp("bench.warmup", 1);
        int runs = intProp("bench.runs", 3);

        // 1. Parse the .m case and import it as a powsybl Network (via a temporary .mat file).
        Network network = importMatpowerCase(Path.of(casePath));
        LOGGER.info("Imported network '{}': {} buses, {} branches, {} generators",
                network.getId(),
                network.getBusView().getBusStream().count(),
                network.getBranchCount(),
                network.getGeneratorCount());

        // 2. Bare AC load flow, to isolate the solve+extraction overhead of sensitivity analysis from the base LF.
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDistributedSlack(true);
        long t0 = System.nanoTime();
        LoadFlowResult lfResult = LoadFlow.find("OpenLoadFlow").run(network, lfParameters);
        long lfMs = (System.nanoTime() - t0) / 1_000_000;
        LOGGER.info("Bare AC load flow: status={}, {} ms", lfResult.isFullyConverged(), lfMs);

        // 3. Build the factors: (branch active power, side 1) w.r.t. (injection active power) for a grid of
        //    functions x variables. Variables are generators (unambiguous injections in the main component).
        List<Branch> functionBranches = network.getBranchStream().limit(nbFunctions).toList();
        List<Generator> variableGenerators = network.getGeneratorStream().limit(nbVariables).toList();
        List<SensitivityFactor> factors = new ArrayList<>(functionBranches.size() * variableGenerators.size());
        for (Generator g : variableGenerators) {
            for (Branch<?> b : functionBranches) {
                factors.add(new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, b.getId(),
                        SensitivityVariableType.INJECTION_ACTIVE_POWER, g.getId(),
                        false, ContingencyContext.all()));
            }
        }

        // 4. Optional branch contingencies.
        List<Contingency> contingencies = new ArrayList<>();
        List<Branch> contingencyBranches = network.getBranchStream().limit(nbContingencies).toList();
        for (Branch<?> b : contingencyBranches) {
            contingencies.add(Contingency.branch(b.getId()));
        }

        LOGGER.info("Benchmark configuration: {} factors ({} functions x {} variables), {} contingencies",
                factors.size(), functionBranches.size(), variableGenerators.size(), contingencies.size());

        SensitivityAnalysisParameters sensiParameters = new SensitivityAnalysisParameters();
        sensiParameters.getLoadFlowParameters()
                .setDistributedSlack(true)
                .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES);

        SensitivityAnalysis.Runner runner = new SensitivityAnalysis.Runner(
                new OpenSensitivityAnalysisProvider(new SparseMatrixFactory(),
                        new EvenShiloachGraphDecrementalConnectivityFactory<>()));

        // 5. Warmup + measured runs.
        for (int i = 0; i < warmup; i++) {
            runOnce(runner, network, factors, contingencies, sensiParameters, "warmup " + (i + 1));
        }
        long[] timingsMs = new long[runs];
        for (int i = 0; i < runs; i++) {
            timingsMs[i] = runOnce(runner, network, factors, contingencies, sensiParameters, "run " + (i + 1));
        }

        long min = Long.MAX_VALUE;
        long sum = 0;
        for (long t : timingsMs) {
            min = Math.min(min, t);
            sum += t;
        }
        LOGGER.info("=== RESULT case={} factors={} contingencies={} : bareLf={} ms, sensi min={} ms, avg={} ms ===",
                network.getId(), factors.size(), contingencies.size(), lfMs, min, sum / runs);
    }

    private static long runOnce(SensitivityAnalysis.Runner runner, Network network, List<SensitivityFactor> factors,
                                List<Contingency> contingencies, SensitivityAnalysisParameters sensiParameters, String label) {
        // Two writer modes:
        //  - default ("model"): the standard runner overload, which accumulates every value in a
        //    SensitivityResultModelWriter and assembles a SensitivityAnalysisResult (result egress included).
        //  - "count": a counting SensitivityResultWriter that discards values, to isolate the analysis proper
        //    (factor ingestion + load flow + solve + value computation) from the result-model egress.
        boolean countOnly = "count".equals(System.getProperty("bench.writer"));
        long t0 = System.nanoTime();
        long count;
        if (countOnly) {
            SensitivityFactorModelReader reader = new SensitivityFactorModelReader(factors, network);
            CountingResultWriter writer = new CountingResultWriter();
            runner.run(network, network.getVariantManager().getWorkingVariantId(), reader, writer,
                    contingencies, List.of(), sensiParameters, LocalComputationManager.getDefault(), ReportNode.NO_OP);
            count = writer.count;
        } else {
            SensitivityAnalysisResult result = runner.run(network, factors, contingencies, sensiParameters);
            count = result.getValues().size();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOGGER.info("  [{}] {} sensitivity values in {} ms (writer={})", label, count, ms, countOnly ? "count" : "model");
        return ms;
    }

    /** Discards sensitivity values, only counting them, to isolate result-egress cost from the analysis. */
    private static final class CountingResultWriter implements com.powsybl.sensitivity.SensitivityResultWriter {
        private long count;

        @Override
        public void writeSensitivityValue(int factorContext, int contingencyIndex, int variableIndex, double value, double functionReference) {
            count++;
        }

        @Override
        public void writeStateStatus(int contingencyIndex, int variableIndex, com.powsybl.sensitivity.SensitivityAnalysisResult.Status status) {
            // no-op
        }
    }

    private static Network importMatpowerCase(Path mFile) throws IOException {
        MatpowerModel model = parseMatpowerM(mFile);
        Path matFile = Files.createTempFile("pegase-bench", ".mat");
        MatpowerWriter.write(model, matFile, true);
        return Network.read(matFile);
    }

    /**
     * Minimal parser for the MATPOWER {@code .m} text format (baseMVA, bus, gen and branch sections only), building a
     * {@link MatpowerModel}. Column layouts follow the MATPOWER CASEFORMAT specification.
     */
    private static MatpowerModel parseMatpowerM(Path mFile) throws IOException {
        List<String> lines = Files.readAllLines(mFile);
        MatpowerModel model = new MatpowerModel(mFile.getFileName().toString().replace(".m", ""));
        model.setVersion(MatpowerFormatVersion.V2);
        model.setBaseMva(100);
        String section = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("mpc.baseMVA")) {
                String value = line.substring(line.indexOf('=') + 1).replace(";", "").trim();
                model.setBaseMva(Double.parseDouble(value));
                continue;
            }
            if (line.startsWith("mpc.bus ")) {
                section = "bus";
                continue;
            } else if (line.startsWith("mpc.gen ") && !line.startsWith("mpc.gencost")) {
                section = "gen";
                continue;
            } else if (line.startsWith("mpc.branch")) {
                section = "branch";
                continue;
            } else if (line.startsWith("mpc.gencost") || line.startsWith("mpc.bus_name") || line.startsWith("mpc.areas")) {
                section = null;
                continue;
            }
            if (section == null || line.isEmpty() || line.startsWith("%") || line.startsWith("]")) {
                if (line.startsWith("]")) {
                    section = null;
                }
                continue;
            }
            String data = line.endsWith(";") ? line.substring(0, line.length() - 1) : line;
            String[] tok = data.trim().split("\\s+");
            switch (section) {
                case "bus" -> model.addBus(parseBus(tok));
                case "gen" -> model.addGenerator(parseGen(tok));
                case "branch" -> model.addBranch(parseBranch(tok));
                default -> { }
            }
        }
        return model;
    }

    private static MBus parseBus(String[] t) {
        MBus bus = new MBus();
        bus.setNumber(Integer.parseInt(t[0]));
        bus.setType(MBus.Type.fromInt(Integer.parseInt(t[1])));
        bus.setName("BUS-" + t[0]);
        bus.setRealPowerDemand(Double.parseDouble(t[2]));
        bus.setReactivePowerDemand(Double.parseDouble(t[3]));
        bus.setShuntConductance(Double.parseDouble(t[4]));
        bus.setShuntSusceptance(Double.parseDouble(t[5]));
        bus.setAreaNumber(Integer.parseInt(t[6]));
        bus.setVoltageMagnitude(Double.parseDouble(t[7]));
        bus.setVoltageAngle(Double.parseDouble(t[8]));
        bus.setBaseVoltage(Double.parseDouble(t[9]));
        bus.setLossZone(Integer.parseInt(t[10]));
        bus.setMaximumVoltageMagnitude(Double.parseDouble(t[11]));
        bus.setMinimumVoltageMagnitude(Double.parseDouble(t[12]));
        return bus;
    }

    private static MGen parseGen(String[] t) {
        MGen gen = new MGen();
        gen.setNumber(Integer.parseInt(t[0]));
        gen.setRealPowerOutput(Double.parseDouble(t[1]));
        gen.setReactivePowerOutput(Double.parseDouble(t[2]));
        gen.setMaximumReactivePowerOutput(Double.parseDouble(t[3]));
        gen.setMinimumReactivePowerOutput(Double.parseDouble(t[4]));
        gen.setVoltageMagnitudeSetpoint(Double.parseDouble(t[5]));
        gen.setTotalMbase(Double.parseDouble(t[6]));
        gen.setStatus(Integer.parseInt(t[7]));
        gen.setMaximumRealPowerOutput(Double.parseDouble(t[8]));
        gen.setMinimumRealPowerOutput(Double.parseDouble(t[9]));
        return gen;
    }

    private static MBranch parseBranch(String[] t) {
        MBranch branch = new MBranch();
        branch.setFrom(Integer.parseInt(t[0]));
        branch.setTo(Integer.parseInt(t[1]));
        branch.setR(Double.parseDouble(t[2]));
        branch.setX(Double.parseDouble(t[3]));
        branch.setB(Double.parseDouble(t[4]));
        branch.setRateA(Double.parseDouble(t[5]));
        branch.setRateB(Double.parseDouble(t[6]));
        branch.setRateC(Double.parseDouble(t[7]));
        branch.setRatio(Double.parseDouble(t[8]));
        branch.setPhaseShiftAngle(Double.parseDouble(t[9]));
        branch.setStatus(Integer.parseInt(t[10]));
        branch.setAngMin(Double.parseDouble(t[11]));
        branch.setAngMax(Double.parseDouble(t[12]));
        return branch;
    }
}
