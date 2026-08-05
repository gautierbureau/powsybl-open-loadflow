/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.OperationalLimitsGroup;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.results.PostContingencyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual benchmark of the fast DC security analysis violation detection, on MATPOWER Pegase networks. Not run with the
 * default build (the class name does not match the surefire test patterns), run it with:
 * <pre>./mvnw test -Dtest=FastDcSecurityAnalysisBenchmark</pre>
 *
 * <p>The case files are the {@code .mat} the {@code AlternativeEquationsBenchmark} caches in
 * {@code target/matpower-cases}; the benchmark is skipped when they are not there.
 *
 * <p>Each case is measured in two limit configurations, because <b>they are different benchmarks and their figures are
 * not comparable</b>:
 * <ul>
 *     <li><b>as loaded</b> — whatever rating the case carries. `case9241pegase` rates 39% of its branches, and
 *     `case13659pegase` none at all, so on the latter there is simply no violation detection to speed up.</li>
 *     <li><b>all rated</b> — an active power limit added to every branch. This is the configuration the fast DC
 *     security analysis work was measured in, and it is the upper bound of what any detection change can be worth:
 *     every branch of every contingency has something to check.</li>
 * </ul>
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
class FastDcSecurityAnalysisBenchmark extends AbstractOpenSecurityAnalysisTest {

    private static final Path CACHE_DIR = Path.of("target", "matpower-cases");

    /** Active power limit added to every branch in the "all rated" configuration, in MW. */
    private static final double ADDED_LIMIT_MW = 800.0;

    FastDcSecurityAnalysisBenchmark(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    @BeforeEach
    void setUpBenchmark() {
        var matrixFactory = new SparseMatrixFactory();
        var connectivityFactory = new EvenShiloachGraphDecrementalConnectivityFactory<com.powsybl.openloadflow.network.LfBus, com.powsybl.openloadflow.network.LfBranch>();
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);
        loadFlowProvider = new OpenLoadFlowProvider(matrixFactory, connectivityFactory);
        ((Logger) LoggerFactory.getLogger("com.powsybl")).setLevel(Level.WARN);
    }

    private static Network loadCase(String caseName) {
        Path matFile = CACHE_DIR.resolve(caseName + ".mat");
        assumeTrue(Files.exists(matFile), matFile + " not found, run AlternativeEquationsBenchmark first to cache it");
        return Network.read(matFile);
    }

    private static final Predicate<OperationalLimitsGroup> HAS_ANY_LIMIT =
            group -> group.getCurrentLimits().isPresent() || group.getActivePowerLimits().isPresent() || group.getApparentPowerLimits().isPresent();

    private static long ratedBranchCount(Network network) {
        return network.getBranchStream()
                .filter(branch -> branch.getOperationalLimitsGroups1().stream().anyMatch(HAS_ANY_LIMIT)
                        || branch.getOperationalLimitsGroups2().stream().anyMatch(HAS_ANY_LIMIT))
                .count();
    }

    private static void rateEveryBranch(Network network) {
        for (Branch<?> branch : network.getBranches()) {
            branch.getOrCreateSelectedOperationalLimitsGroup1().newActivePowerLimits().setPermanentLimit(ADDED_LIMIT_MW).add();
        }
    }

    private static SecurityAnalysisParameters fastDcParameters() {
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();
        parameters.getLoadFlowParameters().setDc(true);
        parameters.addExtension(OpenSecurityAnalysisParameters.class,
                new OpenSecurityAnalysisParameters().setDcFastMode(true).setThreadCount(1));
        OpenLoadFlowParameters.create(parameters.getLoadFlowParameters());
        return parameters;
    }

    private void benchmark(String caseName, int contingencyCount, boolean rateEveryBranch) {
        Network network = loadCase(caseName);
        if (rateEveryBranch) {
            rateEveryBranch(network);
        }
        List<Contingency> contingencies = network.getLineStream()
                .limit(contingencyCount)
                .map(line -> Contingency.line(line.getId()))
                .collect(Collectors.toList());
        SecurityAnalysisParameters parameters = fastDcParameters();

        List<Long> times = new ArrayList<>();
        long violations = 0;
        for (int i = 0; i < 7; i++) {
            long t0 = System.nanoTime();
            SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, Collections.emptyList(), parameters);
            long dt = (System.nanoTime() - t0) / 1_000_000;
            if (i >= 2) { // warmup
                times.add(dt);
            }
            violations = result.getPostContingencyResults().stream()
                    .mapToLong(postContingencyResult -> postContingencyResult.getLimitViolationsResult().getLimitViolations().size())
                    .sum();
        }
        List<Long> sorted = times.stream().sorted().toList();
        System.out.printf("FASTDC %-16s rated=%s branches=%d ratedBranches=%d ctg=%d | min=%5d ms median=%5d ms violations=%d%n",
                caseName, rateEveryBranch ? "all  " : "asLoaded", network.getBranchCount(), ratedBranchCount(network),
                contingencies.size(), sorted.get(0), sorted.get(sorted.size() / 2), violations);
    }

    @Test
    void benchmarkFastDcSecurityAnalysis() {
        for (String caseName : new String[] {"case9241pegase", "case13659pegase"}) {
            benchmark(caseName, 1000, false);
            benchmark(caseName, 1000, true);
        }
    }

    /** Sanity output alongside the timings: how many contingencies actually converged. */
    @Test
    void reportConvergence() {
        Network network = loadCase("case9241pegase");
        rateEveryBranch(network);
        List<Contingency> contingencies = network.getLineStream().limit(1000).map(line -> Contingency.line(line.getId())).toList();
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, Collections.emptyList(), fastDcParameters());
        long converged = result.getPostContingencyResults().stream()
                .filter(postContingencyResult -> postContingencyResult.getStatus() == com.powsybl.security.PostContingencyComputationStatus.CONVERGED)
                .count();
        long violations = result.getPostContingencyResults().stream()
                .mapToLong(r -> r.getLimitViolationsResult().getLimitViolations().size()).sum();
        System.out.printf("FASTDC case9241pegase converged=%d/%d violations=%d%n", converged, contingencies.size(), violations);
        for (PostContingencyResult r : result.getPostContingencyResults().subList(0, 1)) {
            System.out.printf("FASTDC first contingency %s: %d violations%n", r.getContingency().getId(),
                    r.getLimitViolationsResult().getLimitViolations().size());
        }
    }
}
