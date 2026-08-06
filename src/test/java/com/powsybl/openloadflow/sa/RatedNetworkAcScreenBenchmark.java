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
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.MostMeshedSlackBusSelector;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two AC branch limit screening paths - reading the flows from the equation system arrays versus walking the
 * branch evaluables ({@link LimitViolationManager#bulkFlowScreen}) - on networks that carry a realistic set of
 * operational limits.
 *
 * <p>This is the benchmark that decides whether the bulk screen is worth anything. Measured on the MATPOWER cases it
 * is not: they rate 39% of their branches with one limit per group, so there are 12590 checks and the per-group limit
 * walk is a single comparison. A rated network has every branch side rated, several operational limits groups per side
 * and a permanent limit with temporary tiers above it, which is four to five times as many checks and a real walk
 * inside each - i.e. the regime the screen was designed for.
 *
 * <p>Not run with the default build (the class name does not match the surefire test patterns). Run it with:
 * <pre>./mvnw test -Dtest=RatedNetworkAcScreenBenchmark -Dbench.networks=/path/to/networks</pre>
 * Optional: {@code -Dbench.contingencies} (default 500), {@code -Dbench.altEq} (default false).
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
class RatedNetworkAcScreenBenchmark extends AbstractOpenSecurityAnalysisTest {

    RatedNetworkAcScreenBenchmark(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    @BeforeEach
    void setUpBenchmark() {
        var matrixFactory = new SparseMatrixFactory();
        EvenShiloachGraphDecrementalConnectivityFactory<LfBus, LfBranch> connectivityFactory = new EvenShiloachGraphDecrementalConnectivityFactory<>();
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);
        loadFlowProvider = new OpenLoadFlowProvider(matrixFactory, connectivityFactory);
        ((Logger) LoggerFactory.getLogger("com.powsybl")).setLevel(Level.WARN);
    }

    private static final Predicate<OperationalLimitsGroup> HAS_ANY_LIMIT =
            group -> group.getCurrentLimits().isPresent() || group.getActivePowerLimits().isPresent() || group.getApparentPowerLimits().isPresent();

    private static List<Path> networkFiles() {
        String dir = System.getProperty("bench.networks");
        assumeTrue(dir != null, "set -Dbench.networks to a directory of .xiidm/.xiidm.gz networks");
        Path path = Path.of(dir);
        assumeTrue(Files.isDirectory(path), path + " is not a directory");
        try (Stream<Path> files = Files.list(path)) {
            List<Path> networks = files.filter(f -> f.getFileName().toString().endsWith(".xiidm")
                            || f.getFileName().toString().endsWith(".xiidm.gz"))
                    .sorted().toList();
            assumeTrue(!networks.isEmpty(), "no .xiidm network in " + path);
            return networks;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String describeLimits(Network network) {
        long rated = network.getBranchStream()
                .filter(branch -> branch.getOperationalLimitsGroups1().stream().anyMatch(HAS_ANY_LIMIT)
                        || branch.getOperationalLimitsGroups2().stream().anyMatch(HAS_ANY_LIMIT))
                .count();
        long groups = 0;
        for (Branch<?> branch : network.getBranches()) {
            groups += branch.getOperationalLimitsGroups1().size() + branch.getOperationalLimitsGroups2().size();
        }
        LfNetwork lfNetwork = Networks.load(network, new MostMeshedSlackBusSelector()).get(0);
        BranchLimitScreen screen = BranchLimitScreen.build(lfNetwork, LimitReductionManager.create(Collections.emptyList()));
        return String.format("branches=%d rated=%d (%.1f%%) groups=%d screenedChecks=%d",
                network.getBranchCount(), rated, 100d * rated / network.getBranchCount(), groups, screen.size());
    }

    @Test
    void benchmarkRatedNetworkScreens() {
        int contingencyCount = Integer.getInteger("bench.contingencies", 500);
        boolean alternativeEquations = Boolean.getBoolean("bench.altEq");
        for (Path file : networkFiles()) {
            Network network = Network.read(file);
            List<Contingency> contingencies = network.getLineStream()
                    .limit(contingencyCount)
                    .map(line -> Contingency.line(line.getId()))
                    .collect(Collectors.toList());
            System.out.printf("RATEDAC %-28s %s contingencies=%d alternativeEquations=%s%n",
                    file.getFileName(), describeLimits(network), contingencies.size(), alternativeEquations);

            SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();
            LoadFlowParameters loadFlowParameters = new LoadFlowParameters();
            // these networks do not converge from a flat start; the DC start is what the tool that built them uses too
            loadFlowParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
            OpenLoadFlowParameters.create(loadFlowParameters).setAlternativeEquations(alternativeEquations);
            parameters.setLoadFlowParameters(loadFlowParameters);
            parameters.addExtension(OpenSecurityAnalysisParameters.class, new OpenSecurityAnalysisParameters().setThreadCount(1));

            // the run-to-run spread on these networks is wider than the difference being measured, so take enough
            // pairs to report a distribution rather than a single best time
            int runs = Integer.getInteger("bench.runs", 8);
            List<List<Long>> times = new ArrayList<>(List.of(new ArrayList<>(), new ArrayList<>()));
            List<List<String>> violations = new ArrayList<>(List.of(new ArrayList<>(), new ArrayList<>()));
            try {
                // interleaved, one warmup pair then measured pairs
                for (int i = 0; i < runs; i++) {
                    int mode = i % 2; // 0: evaluable walk, 1: bulk arrays
                    LimitViolationManager.bulkFlowScreen = mode == 1;
                    long t0 = System.nanoTime();
                    SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, Collections.emptyList(), parameters);
                    long dt = (System.nanoTime() - t0) / 1_000_000;
                    System.out.printf("RATEDAC %-28s bulk=%-5s run %d: %d ms%n", file.getFileName(), mode == 1, i / 2, dt);
                    // a non-converged pre-contingency load flow aborts the analysis, which then looks fast and
                    // reports nothing: fail loudly rather than time a run that did no work
                    assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED,
                            result.getPreContingencyResult().getStatus() == LoadFlowResult.ComponentResult.Status.CONVERGED
                                    ? LoadFlowResult.ComponentResult.Status.CONVERGED : result.getPreContingencyResult().getStatus(),
                            "pre-contingency load flow did not converge, the timing would be meaningless");
                    assertEquals(contingencies.size(), result.getPostContingencyResults().size(),
                            "not every contingency was simulated, the timing would be meaningless");
                    if (i >= 2) { // warmup
                        times.get(mode).add(dt);
                        violations.set(mode, describeViolations(result));
                    }
                }
            } finally {
                LimitViolationManager.bulkFlowScreen = true;
            }
            for (int mode = 0; mode < 2; mode++) {
                List<Long> sorted = times.get(mode).stream().sorted().toList();
                System.out.printf("RATEDAC %-28s %-9s n=%d min=%6d median=%6d mean=%6d max=%6d samples=%s%n",
                        file.getFileName(), mode == 1 ? "bulk" : "evaluable", sorted.size(), sorted.get(0),
                        sorted.get(sorted.size() / 2), (long) sorted.stream().mapToLong(Long::longValue).average().orElseThrow(),
                        sorted.get(sorted.size() - 1), times.get(mode));
            }
            double evaluableMedian = median(times.get(0));
            double bulkMedian = median(times.get(1));
            System.out.printf("RATEDAC %-28s speedup(median)=x%.3f violations=%d%n",
                    file.getFileName(), evaluableMedian / bulkMedian, violations.get(0).size());
            assertEquals(violations.get(0), violations.get(1), "the two screening paths must report the same violations");
        }
    }

    private static double median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static List<String> describeViolations(SecurityAnalysisResult result) {
        List<String> described = new ArrayList<>();
        result.getPostContingencyResults().forEach(postContingencyResult ->
                postContingencyResult.getLimitViolationsResult().getLimitViolations().forEach(violation ->
                        described.add(postContingencyResult.getContingency().getId() + "|" + violation.getSubjectId() + "|"
                                + violation.getSide() + "|" + violation.getLimitType() + "|" + violation.getOperationalLimitsGroupId()
                                + "|" + violation.getLimitName() + "|" + violation.getAcceptableDuration() + "|"
                                + violation.getLimit() + "|" + violation.getValue())));
        return described;
    }
}
