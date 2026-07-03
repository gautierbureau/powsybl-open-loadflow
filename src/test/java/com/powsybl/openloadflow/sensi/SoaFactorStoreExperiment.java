/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.contingency.ContingencyContext;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.sensi.AbstractSensitivityAnalysis.LfSensitivityFactor.Status;
import com.powsybl.sensitivity.SensitivityFunctionType;
import com.powsybl.sensitivity.SensitivityVariableType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Foundation spike for the Structure-of-Arrays sensitivity factor model: validates that
 * {@link LfSensitivityFactorStore} faithfully represents a single-variable factor's fields, and measures the retained
 * heap of N factors stored as SoA rows versus N per-factor objects.
 *
 * <p>The heap benchmark is gated behind the {@code soa} system property so it never runs in CI. Typical invocation:
 * <pre>
 *   mvn -q test -Dsoa=true -Dtest=SoaFactorStoreExperiment -Dsoa.count=2000000 -DargLine="-Xmx4g"
 * </pre>
 * The round-trip correctness test always runs.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class SoaFactorStoreExperiment {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoaFactorStoreExperiment.class);

    /**
     * The store must round-trip every scalar field, the enum ordinals, and — critically — a real {@code NaN} predefined
     * value must read back as {@code NaN}, not as "absent". A naive NaN-as-absent sentinel would fail this; the store
     * uses a separate presence flag.
     */
    @Test
    void roundTrip() {
        LfSensitivityFactorStore<AcVariableType, AcEquationType> store = new LfSensitivityFactorStore<>(4);

        int r0 = store.add("v0", "f0", null, SensitivityFunctionType.BRANCH_ACTIVE_POWER_1,
                null, null, SensitivityVariableType.INJECTION_ACTIVE_POWER, ContingencyContext.all(), Status.VALID);
        int r1 = store.add("v1", "f1", null, SensitivityFunctionType.BRANCH_CURRENT_2,
                null, null, SensitivityVariableType.BUS_TARGET_VOLTAGE, ContingencyContext.none(), Status.VALID_ONLY_FOR_FUNCTION);

        assertEquals(2, store.size());

        // element / equation references round-trip (null here: no Lf network in this pure-store test)
        assertNull(store.getFunctionElement(r0));
        assertNull(store.getVariableElement(r0));
        assertNull(store.getVariableEquation(r0));

        // write-once fields
        assertEquals("v0", store.getVariableId(r0));
        assertEquals("f0", store.getFunctionId(r0));
        assertEquals(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, store.getFunctionType(r0));
        assertEquals(SensitivityVariableType.INJECTION_ACTIVE_POWER, store.getVariableType(r0));
        assertEquals(ContingencyContext.all(), store.getContingencyContext(r0));
        assertEquals(SensitivityFunctionType.BRANCH_CURRENT_2, store.getFunctionType(r1));
        assertEquals(SensitivityVariableType.BUS_TARGET_VOLTAGE, store.getVariableType(r1));

        // status round-trip + mutation
        assertEquals(Status.VALID, store.getStatus(r0));
        assertEquals(Status.VALID_ONLY_FOR_FUNCTION, store.getStatus(r1));
        store.setStatus(r0, Status.ZERO);
        assertEquals(Status.ZERO, store.getStatus(r0));

        // group index
        assertEquals(-1, store.getGroupIndex(r0));
        store.setGroupIndex(r0, 7);
        assertEquals(7, store.getGroupIndex(r0));

        // function reference
        store.setFunctionReference(r0, 123.5);
        assertEquals(123.5, store.getFunctionReference(r0));

        // predefined results: absent -> null; a set value (incl. 0.0 and NaN) -> that value; reset -> null
        assertNull(store.getSensitivityValuePredefinedResult(r0));
        assertNull(store.getFunctionPredefinedResult(r0));
        store.setSensitivityValuePredefinedResult(r0, 0.0);
        assertEquals(0.0, store.getSensitivityValuePredefinedResult(r0));
        store.setFunctionPredefinedResult(r0, Double.NaN);
        assertTrue(Double.isNaN(store.getFunctionPredefinedResult(r0)));  // real NaN must not read back as null
        store.setSensitivityValuePredefinedResult(r0, null);
        assertNull(store.getSensitivityValuePredefinedResult(r0));

        // growth beyond initial capacity preserves earlier rows
        for (int i = 0; i < 100; i++) {
            store.add("v" + i, "f" + i, null, SensitivityFunctionType.BRANCH_ACTIVE_POWER_1,
                    null, null, SensitivityVariableType.INJECTION_ACTIVE_POWER, ContingencyContext.all(), Status.VALID);
        }
        assertEquals(102, store.size());
        assertEquals("v1", store.getVariableId(r1));
        assertEquals(SensitivityVariableType.BUS_TARGET_VOLTAGE, store.getVariableType(r1));
    }

    @Test
    @EnabledIfSystemProperty(named = "soa", matches = "true")
    void heapFootprint() {
        int count = Integer.parseInt(System.getProperty("soa.count", "2000000"));

        // Pre-build the distinct id strings once (as in the real pipeline, where ids come as shared references from the
        // input factors), so the measurement reflects the factor STRUCTURE, not per-factor string allocation.
        String[] variableIds = new String[100];
        for (int i = 0; i < variableIds.length; i++) {
            variableIds[i] = "variable-" + i;
        }
        String[] functionIds = new String[20000];
        for (int i = 0; i < functionIds.length; i++) {
            functionIds[i] = "function-" + i;
        }
        ContingencyContext all = ContingencyContext.all();

        long baseline = usedHeap();

        // N single-variable factors as per-factor objects, retained in a list (as the holder does). Timed to capture
        // the GC cost of progressively promoting a growing set of millions of live objects during ingestion.
        long tObj = System.nanoTime();
        List<AbstractSensitivityAnalysis.SingleVariableLfSensitivityFactor<AcVariableType, AcEquationType>> objects = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            objects.add(new AbstractSensitivityAnalysis.SingleVariableLfSensitivityFactor<>(
                    i, variableIds[i % 100], functionIds[i % 20000], null, SensitivityFunctionType.BRANCH_ACTIVE_POWER_1,
                    null, SensitivityVariableType.INJECTION_ACTIVE_POWER, all));
        }
        long objectsBuildMs = (System.nanoTime() - tObj) / 1_000_000;
        long objectsHeap = usedHeap() - baseline;
        int objectsSize = objects.size();
        objects = null;

        long baseline2 = usedHeap();
        long tStore = System.nanoTime();
        LfSensitivityFactorStore<AcVariableType, AcEquationType> store = new LfSensitivityFactorStore<>(count);
        for (int i = 0; i < count; i++) {
            store.add(variableIds[i % 100], functionIds[i % 20000], null, SensitivityFunctionType.BRANCH_ACTIVE_POWER_1,
                    null, null, SensitivityVariableType.INJECTION_ACTIVE_POWER, all, Status.VALID);
        }
        long storeBuildMs = (System.nanoTime() - tStore) / 1_000_000;
        long storeHeap = usedHeap() - baseline2;
        int storeSize = store.size();

        LOGGER.info("=== SoA heap footprint for {} single-variable factors (shared ids: 100 variables x 20000 functions) ===", count);
        LOGGER.info("  per-factor objects : {} MB ({} bytes/factor), build={} ms, size={}", objectsHeap / (1024 * 1024), objectsHeap / count, objectsBuildMs, objectsSize);
        LOGGER.info("  SoA store          : {} MB ({} bytes/factor), build={} ms, size={}", storeHeap / (1024 * 1024), storeHeap / count, storeBuildMs, storeSize);
        LOGGER.info("  reduction          : {} MB ({}% heap), build {} ms -> {} ms",
                (objectsHeap - storeHeap) / (1024 * 1024), objectsHeap > 0 ? (objectsHeap - storeHeap) * 100 / objectsHeap : 0,
                objectsBuildMs, storeBuildMs);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        long used = Long.MAX_VALUE;
        // a few GC cycles to settle; not guaranteed but adequate for a coarse footprint measurement
        for (int i = 0; i < 6; i++) {
            System.gc();
            long u = runtime.totalMemory() - runtime.freeMemory();
            if (u < used) {
                used = u;
            }
        }
        return used;
    }
}
