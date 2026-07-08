/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.fastdc;

import com.powsybl.iidm.network.Network;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LoadFlowModel;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import com.powsybl.openloadflow.util.MatpowerCaseLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Manual performance benchmark for the fast LODF matrix computation on large MATPOWER Pégase cases.
 *
 * <p>Not run in CI: enabled only when the {@code pegase.dir} system property points at a directory
 * containing the {@code caseXXXXpegase.m} files. Reports, for each case, the timing of the LODF
 * matrix computation for all branches monitored and a growing number of outaged branches, so that
 * the cost of the single sparse solve (which scales with the number of outages) can be separated
 * from the dense arithmetic (which scales with monitored x outaged).
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
@EnabledIfSystemProperty(named = "pegase.dir", matches = ".+")
class LodfPerformanceBenchmark {

    private static final String[] CASES = {
        "case1354pegase.m",
        "case2869pegase.m",
        "case9241pegase.m",
        "case13659pegase.m",
    };

    private static Path caseDir() {
        return Paths.get(System.getProperty("pegase.dir"));
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
    void benchmarkLodf() {
        System.out.println("\n==================== FAST LODF ====================");
        System.out.printf("%-22s %10s %10s %10s %12s%n", "case", "monitored", "outaged", "cells", "lodf(ms)");
        String only = System.getProperty("pegase.case", "");
        int[] outageCounts = {100, 1000, Integer.MAX_VALUE}; // MAX_VALUE means all outageable branches
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
            DcLoadFlowParameters dcParameters = new DcLoadFlowParameters();
            LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst();
            try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters, false)) {
                List<LfBranch> monitoredBranches = lfNetwork.getBranches();
                List<LfBranch> outageableBranches = lfNetwork.getBranches().stream()
                        .filter(b -> b.getBus1() != null && b.getBus2() != null && !b.isZeroImpedance(LoadFlowModel.DC))
                        .toList();
                for (int requested : outageCounts) {
                    int n = Math.min(requested, outageableBranches.size());
                    List<LfBranch> outagedBranches = outageableBranches.subList(0, n);
                    long cells = (long) monitoredBranches.size() * n;
                    // one warmup (also triggers the Jacobian factorization), then median of 3 timed runs
                    double ms = timeMedian(1, 3, () -> {
                        DenseMatrix lodf = LodfCalculator.computeLodfMatrix(context, monitoredBranches, outagedBranches);
                        if (lodf.getRowCount() != monitoredBranches.size()) {
                            throw new IllegalStateException("unexpected matrix size");
                        }
                    });
                    System.out.printf("%-22s %10d %10d %10d %12.1f%n",
                            caseName, monitoredBranches.size(), n, cells, ms);
                    if (requested == Integer.MAX_VALUE) {
                        break; // 'all' already covered
                    }
                }
            }
        }
    }
}
