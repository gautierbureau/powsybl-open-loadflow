/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa.benchmark;

import com.powsybl.action.Action;
import com.powsybl.action.GeneratorActionBuilder;
import com.powsybl.computation.ComputationManager;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.strategy.OperatorStrategy;
import com.powsybl.contingency.strategy.condition.TrueCondition;
import com.powsybl.contingency.violations.LimitViolationFilter;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.matpower.model.MatpowerModel;
import com.powsybl.matpower.model.MatpowerWriter;
import com.powsybl.openloadflow.sa.OpenSecurityAnalysisParameters;
import com.powsybl.openloadflow.sa.OpenSecurityAnalysisProvider;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisReport;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.SecurityAnalysisRunParameters;
import com.powsybl.security.monitor.StateMonitor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Benchmark comparing the wall-clock time of a security analysis run:
 * <ul>
 *     <li>single threaded,</li>
 *     <li>parallelized on contingencies only (the historical behaviour),</li>
 *     <li>parallelized on operator strategies (the {@code operatorStrategyParallelization} parameter).</li>
 * </ul>
 *
 * <p>The benchmark is built around a workload that is deliberately skewed towards operator strategies: a small number
 * of contingencies, each carrying many operator strategies. This is exactly the case where contingency-level
 * parallelization brings no speed-up (in the extreme, a single contingency carrying many operator strategies) and where
 * operator strategy parallelization is expected to help.</p>
 *
 * <p>It is meant to be run on a large network such as PEGASE 13659. That network carries no branch current limits, so
 * the benchmark adds a permanent current limit to every branch (derived from the base case flow) to give the limit
 * violation detection realistic work to do.</p>
 *
 * <p>The network is <b>not</b> bundled: it is passed with the {@code -Dbenchmark.network=...} system property so the
 * benchmark is skipped in CI. Both PowSyBl native formats and the MATPOWER {@code .m} text format (e.g.
 * {@code case13659pegase.m}, downloadable from the MATPOWER project) are accepted.</p>
 *
 * <p>Example:</p>
 * <pre>
 * mvn test -Dtest=SecurityAnalysisParallelizationBenchmark \
 *     -Dbenchmark.network=/path/to/case13659pegase.m \
 *     -Dbenchmark.contingencies=4 -Dbenchmark.strategiesPerContingency=32 -Dbenchmark.threads=4
 * </pre>
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
@Tag("benchmark")
class SecurityAnalysisParallelizationBenchmark {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityAnalysisParallelizationBenchmark.class);

    private static final String NETWORK_PROPERTY = "benchmark.network";
    private static final int CONTINGENCY_COUNT = Integer.getInteger("benchmark.contingencies", 4);
    private static final int STRATEGIES_PER_CONTINGENCY = Integer.getInteger("benchmark.strategiesPerContingency", 32);
    private static final int THREAD_COUNT = Integer.getInteger("benchmark.threads", Math.min(4, Runtime.getRuntime().availableProcessors()));
    // permanent current limit set on every branch, as a factor of the base case current (1.0 => limit at base loading)
    private static final double LIMIT_FACTOR = 1.0;
    private static final double MIN_LIMIT = 100.0; // A, floor for very lightly loaded branches
    private static final double GENERATOR_ACTION_DELTA_MW = 10.0;

    @Test
    void benchmark() throws IOException {
        String networkPath = System.getProperty(NETWORK_PROPERTY);
        assumeTrue(networkPath != null && Files.exists(Path.of(networkPath)),
                "Benchmark skipped: set -D" + NETWORK_PROPERTY + "=<path to network> to run it");

        // the test logging configuration is very verbose (DEBUG/TRACE); quiet it down so it does not pollute timings
        quietLogs();

        Network network = loadNetwork(networkPath);
        LOGGER.info("Loaded network '{}': {} buses, {} branches, {} generators", network.getId(),
                network.getBusView().getBusStream().count(), network.getBranchCount(), network.getGeneratorCount());

        addBranchCurrentLimits(network);

        List<Contingency> contingencies = buildContingencies(network, CONTINGENCY_COUNT);
        List<Action> actions = new ArrayList<>();
        List<OperatorStrategy> operatorStrategies = buildOperatorStrategies(network, contingencies, STRATEGIES_PER_CONTINGENCY, actions);
        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(),
                network.getBranchStream().map(Branch::getId).collect(Collectors.toSet()), Collections.emptySet(), Collections.emptySet()));

        LOGGER.info("Workload: {} contingencies, {} operator strategies ({} per contingency), {} threads",
                contingencies.size(), operatorStrategies.size(), STRATEGIES_PER_CONTINGENCY, THREAD_COUNT);

        // warmup (JIT) - discarded
        run(network, contingencies, operatorStrategies, actions, monitors, 1, false);

        Result singleThread = timedRun(network, contingencies, operatorStrategies, actions, monitors, 1, false, "single-threaded");
        Result contingencyParallel = timedRun(network, contingencies, operatorStrategies, actions, monitors, THREAD_COUNT, false, "contingency-parallel");
        Result operatorStrategyParallel = timedRun(network, contingencies, operatorStrategies, actions, monitors, THREAD_COUNT, true, "operator-strategy-parallel");

        // all three runs must agree on the results
        assertEquals(singleThread.operatorStrategyResultCount, contingencyParallel.operatorStrategyResultCount);
        assertEquals(singleThread.operatorStrategyResultCount, operatorStrategyParallel.operatorStrategyResultCount);
        assertEquals(singleThread.postContingencyResultCount, operatorStrategyParallel.postContingencyResultCount);

        LOGGER.info("==== Security analysis parallelization benchmark ====");
        LOGGER.info("network={} contingencies={} strategiesPerContingency={} threads={}",
                network.getId(), contingencies.size(), STRATEGIES_PER_CONTINGENCY, THREAD_COUNT);
        logResult(singleThread, singleThread);
        logResult(contingencyParallel, singleThread);
        logResult(operatorStrategyParallel, singleThread);
    }

    private Result timedRun(Network network, List<Contingency> contingencies, List<OperatorStrategy> operatorStrategies,
                            List<Action> actions, List<StateMonitor> monitors, int threadCount,
                            boolean operatorStrategyParallelization, String label) {
        long start = System.nanoTime();
        SecurityAnalysisResult result = run(network, contingencies, operatorStrategies, actions, monitors, threadCount, operatorStrategyParallelization);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new Result(label, threadCount, operatorStrategyParallelization, elapsedMs,
                result.getOperatorStrategyResults().size(), result.getPostContingencyResults().size());
    }

    private SecurityAnalysisResult run(Network network, List<Contingency> contingencies, List<OperatorStrategy> operatorStrategies,
                                       List<Action> actions, List<StateMonitor> monitors, int threadCount,
                                       boolean operatorStrategyParallelization) {
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, new OpenSecurityAnalysisParameters()
                .setThreadCount(threadCount)
                .setOperatorStrategyParallelization(operatorStrategyParallelization));

        ForkJoinPool pool = new ForkJoinPool(THREAD_COUNT);
        try {
            ComputationManager computationManager = Mockito.mock(ComputationManager.class);
            Mockito.when(computationManager.getExecutor()).thenReturn(pool);

            ContingenciesProvider contingenciesProvider = n -> contingencies;
            SecurityAnalysisRunParameters runParameters = new SecurityAnalysisRunParameters()
                    .setFilter(new LimitViolationFilter())
                    .setComputationManager(computationManager)
                    .setSecurityAnalysisParameters(securityAnalysisParameters)
                    .setOperatorStrategies(operatorStrategies)
                    .setActions(actions)
                    .setMonitors(monitors);

            SecurityAnalysisReport report = new OpenSecurityAnalysisProvider().run(network,
                    network.getVariantManager().getWorkingVariantId(), contingenciesProvider, runParameters).join();
            return report.getResult();
        } finally {
            pool.shutdown();
        }
    }

    private static Network loadNetwork(String networkPath) throws IOException {
        Path path = Path.of(networkPath);
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".m")) {
            // MATPOWER text case: parse it and round-trip through the official binary .mat importer
            MatpowerModel model = MatpowerCaseParser.parse(path, name.substring(0, name.length() - 2));
            Path matFile = Files.createTempFile("benchmark-", ".mat");
            try {
                MatpowerWriter.write(model, matFile, false);
                return Network.read(matFile);
            } finally {
                Files.deleteIfExists(matFile);
            }
        }
        return Network.read(path);
    }

    private static void addBranchCurrentLimits(Network network) {
        // run a base case load flow to know the branch loadings, then set a permanent current limit on every branch
        LoadFlow.run(network, new LoadFlowParameters());
        int count = 0;
        for (Branch<?> branch : network.getBranches()) {
            double i1 = branch.getTerminal1().getI();
            double i2 = branch.getTerminal2().getI();
            double base = Math.max(Double.isNaN(i1) ? 0.0 : i1, Double.isNaN(i2) ? 0.0 : i2);
            double limit = Math.max(base * LIMIT_FACTOR, MIN_LIMIT);
            branch.newCurrentLimits1().setPermanentLimit(limit).add();
            branch.newCurrentLimits2().setPermanentLimit(limit).add();
            count++;
        }
        LOGGER.info("Added permanent current limits on {} branches", count);
    }

    private static List<Contingency> buildContingencies(Network network, int count) {
        List<Contingency> contingencies = new ArrayList<>();
        for (Branch<?> branch : network.getBranches()) {
            if (contingencies.size() >= count) {
                break;
            }
            if (branch.getTerminal1().isConnected() && branch.getTerminal2().isConnected()) {
                contingencies.add(new Contingency(branch.getId(), new BranchContingency(branch.getId())));
            }
        }
        return contingencies;
    }

    private static List<OperatorStrategy> buildOperatorStrategies(Network network, List<Contingency> contingencies,
                                                                  int strategiesPerContingency, List<Action> actions) {
        List<Generator> generators = network.getGeneratorStream()
                .filter(g -> g.getTerminal().isConnected())
                .toList();
        if (generators.isEmpty()) {
            throw new IllegalStateException("The network has no connected generator to build operator strategy actions");
        }
        List<OperatorStrategy> operatorStrategies = new ArrayList<>();
        int actionIndex = 0;
        for (Contingency contingency : contingencies) {
            for (int j = 0; j < strategiesPerContingency; j++) {
                Generator generator = generators.get(actionIndex % generators.size());
                String actionId = "action_" + actionIndex;
                actions.add(new GeneratorActionBuilder()
                        .withId(actionId)
                        .withGeneratorId(generator.getId())
                        .withActivePowerRelativeValue(true)
                        .withActivePowerValue(GENERATOR_ACTION_DELTA_MW)
                        .build());
                operatorStrategies.add(new OperatorStrategy("strategy_" + contingency.getId() + "_" + j,
                        ContingencyContext.specificContingency(contingency.getId()), new TrueCondition(), List.of(actionId)));
                actionIndex++;
            }
        }
        return operatorStrategies;
    }

    private static void logResult(Result result, Result reference) {
        double speedUp = result.elapsedMs > 0 ? (double) reference.elapsedMs / result.elapsedMs : Double.NaN;
        LOGGER.info(String.format(Locale.ROOT, "%-28s threads=%d operatorStrategyParallelization=%-5s : %6d ms  (x%.2f)  [%d operator strategy results]",
                result.label, result.threadCount, result.operatorStrategyParallelization, result.elapsedMs, speedUp, result.operatorStrategyResultCount));
    }

    private record Result(String label, int threadCount, boolean operatorStrategyParallelization, long elapsedMs,
                          int operatorStrategyResultCount, int postContingencyResultCount) {
    }

    /**
     * Lowers the (very verbose) load flow logging to WARN so it does not dominate the measured wall-clock time. Done
     * reflectively to avoid a compile time dependency on the logging backend; a no-op if logback is not used.
     */
    private static void quietLogs() {
        try {
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
            Class<?> logbackLogger = Class.forName("ch.qos.logback.classic.Logger");
            Object warn = levelClass.getField("WARN").get(null);
            Object info = levelClass.getField("INFO").get(null);
            java.lang.reflect.Method setLevel = logbackLogger.getMethod("setLevel", levelClass);
            setLevel.invoke(LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME), warn);
            setLevel.invoke(LoggerFactory.getLogger("com.powsybl.openloadflow"), warn);
            setLevel.invoke(LoggerFactory.getLogger(SecurityAnalysisParallelizationBenchmark.class.getName()), info);
        } catch (ReflectiveOperationException e) {
            // logging backend is not logback, leave the logging configuration untouched
        }
    }
}
