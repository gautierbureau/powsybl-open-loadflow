/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.util;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.violations.LimitViolationFilter;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.sa.OpenSecurityAnalysisParameters;
import com.powsybl.openloadflow.sa.OpenSecurityAnalysisProvider;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisReport;
import com.powsybl.security.SecurityAnalysisRunParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manual performance benchmark for DC load flow and DC security analysis on large MATPOWER cases
 * (e.g. the Pégase cases {@code case1354pegase.m ... case13659pegase.m}).
 *
 * <p>Not run in CI: enabled only when the {@code pegase.dir} system property points at a directory
 * containing the {@code .m} files (downloadable from the MATPOWER data set). Supported system properties:
 * <ul>
 *     <li>{@code pegase.dir} (required) - directory containing the {@code caseXXXXpegase.m} files;</li>
 *     <li>{@code pegase.case} - substring filter to run a single case (e.g. {@code 9241});</li>
 *     <li>{@code pegase.saContingencies} - number of N-1 branch contingencies to run (default 1000);</li>
 *     <li>{@code pegase.mode} - {@code standard}, {@code woodbury} or {@code both} (default) for the SA benchmark.</li>
 * </ul>
 *
 * <p>Because the pom's Surefire configuration does not interpolate {@code ${argLine}}, JVM flags
 * (heap, JFR, logback config) are not honoured through {@code surefire:test}. To profile, run the
 * {@link #main(String[])} entry point directly on the test classpath, for example:
 * <pre>
 * java -Xmx6g -Dlogback.configurationFile=quiet-logback.xml \
 *      -XX:StartFlightRecording=filename=woodbury.jfr,settings=profile,dumponexit=true \
 *      -Dpegase.dir=/path/to/pegase -Dpegase.case=9241 -Dpegase.mode=woodbury -Dpegase.saContingencies=6000 \
 *      -cp target/test-classes:target/classes:$(cat cp.txt) \
 *      com.powsybl.openloadflow.util.DcPerformanceBenchmark sa
 * </pre>
 *
 * @author Claude
 */
@EnabledIfSystemProperty(named = "pegase.dir", matches = ".+")
class DcPerformanceBenchmark {

    private static final String[] CASES = {
        "case1354pegase.m",
        "case2869pegase.m",
        "case9241pegase.m",
        "case13659pegase.m",
    };

    private final OpenLoadFlowProvider loadFlowProvider = new OpenLoadFlowProvider(new SparseMatrixFactory());
    private final OpenSecurityAnalysisProvider securityAnalysisProvider =
            new OpenSecurityAnalysisProvider(new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());

    /**
     * Entry point to run the benchmark directly under {@code java} (so JVM flags such as
     * {@code -XX:StartFlightRecording} and {@code -Dlogback.configurationFile} actually apply,
     * which is not the case through the Surefire fork). Usage:
     * {@code java ... DcPerformanceBenchmark <lf|sa>}.
     */
    public static void main(String[] args) {
        DcPerformanceBenchmark benchmark = new DcPerformanceBenchmark();
        String what = args.length > 0 ? args[0] : "sa";
        if ("lf".equals(what) || "both".equals(what)) {
            benchmark.benchmarkDcLoadFlow();
        }
        if ("sa".equals(what) || "both".equals(what)) {
            benchmark.benchmarkDcSecurityAnalysis();
        }
    }

    private static Path caseDir() {
        return Paths.get(System.getProperty("pegase.dir"));
    }

    private static int saContingencyLimit() {
        return Integer.getInteger("pegase.saContingencies", 1000);
    }

    private static LoadFlowParameters dcLoadFlowParameters() {
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDc(true);
        parameters.setDistributedSlack(true);
        return parameters;
    }

    /** Median of {@code runs} timings (ms) after {@code warmup} untimed iterations. */
    private static double timeMedian(int warmup, int runs, Runnable task) {
        for (int i = 0; i < warmup; i++) {
            task.run();
        }
        double[] times = new double[runs];
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            task.run();
            times[i] = (System.nanoTime() - t0) / 1_000_000.0;
        }
        java.util.Arrays.sort(times);
        return times[runs / 2];
    }

    @Test
    void benchmarkDcLoadFlow() {
        System.out.println("\n================ DC LOAD FLOW ================");
        System.out.printf("%-22s %10s %10s %12s%n", "case", "buses", "branches", "dcLf(ms)");
        String only = System.getProperty("pegase.case", "");
        for (String caseName : CASES) {
            if (!only.isEmpty() && !caseName.contains(only)) {
                continue;
            }
            Path file = caseDir().resolve(caseName);
            if (!Files.exists(file)) {
                System.out.printf("%-22s  (missing, skipped)%n", caseName);
                continue;
            }
            Network network = MatpowerCaseLoader.readDotM(file);
            int buses = network.getBusView().getBusStream().mapToInt(b -> 1).sum();
            int branches = network.getBranchCount();
            LoadFlowParameters parameters = dcLoadFlowParameters();
            LoadFlow.Runner runner = new LoadFlow.Runner(loadFlowProvider);

            double ms = timeMedian(3, 7, () -> {
                LoadFlowResult result = runner.run(network, parameters);
                if (!result.isFullyConverged()) {
                    throw new IllegalStateException("DC LF did not converge for " + caseName);
                }
            });
            System.out.printf("%-22s %10d %10d %12.1f%n", caseName, buses, branches, ms);
        }
    }

    @Test
    void benchmarkDcSecurityAnalysis() {
        System.out.println("\n============ DC SECURITY ANALYSIS ============");
        System.out.printf("%-22s %8s %8s %12s %12s %10s%n",
                "case", "N-1", "branches", "standard(ms)", "woodbury(ms)", "speedup");
        String only = System.getProperty("pegase.case", "");
        for (String caseName : CASES) {
            if (!only.isEmpty() && !caseName.contains(only)) {
                continue;
            }
            Path file = caseDir().resolve(caseName);
            if (!Files.exists(file)) {
                System.out.printf("%-22s  (missing, skipped)%n", caseName);
                continue;
            }
            Network network = MatpowerCaseLoader.readDotM(file);
            int branches = network.getBranchCount();

            List<Contingency> contingencies = network.getBranchStream()
                    .limit(saContingencyLimit())
                    .map(b -> new Contingency(b.getId(), new BranchContingency(b.getId())))
                    .collect(Collectors.toList());
            int nMinus1 = contingencies.size();

            String mode = System.getProperty("pegase.mode", "both");
            double standardMs = "woodbury".equals(mode) ? Double.NaN
                    : timeMedian(0, 1, runSecurityAnalysis(network, contingencies, false));
            double woodburyMs = "standard".equals(mode) ? Double.NaN
                    : timeMedian(1, 2, runSecurityAnalysis(network, contingencies, true));
            System.out.printf("%-22s %8d %8d %12.1f %12.1f %10.2f%n",
                    caseName, nMinus1, branches, standardMs, woodburyMs, standardMs / woodburyMs);
        }
    }

    private Runnable runSecurityAnalysis(Network network, List<Contingency> contingencies, boolean dcFastMode) {
        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.getLoadFlowParameters().setDc(true).setDistributedSlack(true);
        OpenSecurityAnalysisParameters openParams = new OpenSecurityAnalysisParameters();
        openParams.setDcFastMode(dcFastMode);
        saParameters.addExtension(OpenSecurityAnalysisParameters.class, openParams);

        ContingenciesProvider provider = n -> contingencies;
        return () -> {
            SecurityAnalysisRunParameters runParameters = new SecurityAnalysisRunParameters()
                    .setFilter(new LimitViolationFilter())
                    .setComputationManager(LocalComputationManager.getDefault())
                    .setSecurityAnalysisParameters(saParameters);
            SecurityAnalysisReport report = securityAnalysisProvider.run(network,
                    network.getVariantManager().getWorkingVariantId(),
                    provider,
                    runParameters).join();
            if (report.getResult() == null) {
                throw new IllegalStateException("SA returned no result");
            }
        };
    }
}
