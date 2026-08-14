/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.loadflow.LoadFlowRunParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several threads running cached load flows, each on its own variant of the same network: the
 * pattern used to explore many injection scenarios in parallel.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class LoadFlowCacheConcurrencyTest {

    private static final int THREAD_COUNT = 4;
    private static final int RUNS_PER_THREAD = 20;

    private Network network;
    private LoadFlowParameters parameters;
    private ExecutorService executor;

    private final LoadFlow.Runner runner = LoadFlow.find("OpenLoadFlow");

    @BeforeEach
    void setUp() {
        network = IeeeCdfNetworkFactory.create118();
        network.getVariantManager().allowVariantMultiThreadAccess(true);
        parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters)
                .setNetworkCacheEnabled(true);
        executor = Executors.newFixedThreadPool(THREAD_COUNT);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        NetworkCache.AC_LF_INSTANCE.clear();
    }

    @Test
    void oneVariantPerThread() throws Exception {
        List<String> variantIds = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            String variantId = "variant" + i;
            network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, variantId);
            variantIds.add(variantId);
        }

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            String variantId = variantIds.get(i);
            double targetPFactor = 1 + 0.01 * i;
            futures.add(executor.submit(() -> {
                network.getVariantManager().setWorkingVariant(variantId);
                Generator generator = network.getGeneratorStream().filter(g -> g.getTargetP() > 0).findFirst().orElseThrow();
                double targetP = generator.getTargetP();
                int converged = 0;
                for (int run = 0; run < RUNS_PER_THREAD; run++) {
                    // a small change, so that each run reuses the cached network of its own variant
                    generator.setTargetP(targetP * targetPFactor * (1 + 0.001 * run));
                    LoadFlowResult result = runner.run(network, variantId,
                            LoadFlowRunParameters.getDefault().setParameters(parameters));
                    if (result.isFullyConverged()) {
                        converged++;
                    }
                }
                return converged;
            }));
        }

        int converged = 0;
        for (Future<Integer> future : futures) {
            converged += future.get(5, TimeUnit.MINUTES);
        }
        assertEquals(THREAD_COUNT * RUNS_PER_THREAD, converged);
        // one cache entry per variant, and no entry lost or duplicated by concurrent lookups
        assertEquals(THREAD_COUNT, NetworkCache.AC_LF_INSTANCE.getEntryCount());
        for (String variantId : variantIds) {
            network.getVariantManager().setWorkingVariant(variantId);
            assertTrue(NetworkCache.AC_LF_INSTANCE.findEntry(network).isPresent());
        }
    }
}
