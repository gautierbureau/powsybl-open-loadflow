/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Negative control for the multi-thread COPY mode IIDM free tests: proves that once armed, the
 * guard really trips when a {@link Ref} is dereferenced from another thread than the first one.
 *
 * @author Open Load Flow contributors
 */
class RefThreadGuardTest {

    @Test
    void testGuardTripsOnForeignThread() throws Exception {
        Ref<String> strongRef = Ref.create("identifiable", false);
        Ref<String> weakRef = Ref.create("identifiable", true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RefThreadGuardTestUtil.arm();
        try {
            // first dereferencing thread becomes the allowed one
            assertEquals("identifiable", strongRef.get());
            assertEquals("identifiable", weakRef.get());
            for (Ref<String> ref : java.util.List.of(strongRef, weakRef)) {
                Future<?> foreign = executor.submit(ref::get);
                ExecutionException e = assertThrows(ExecutionException.class, foreign::get);
                assertInstanceOf(IllegalStateException.class, e.getCause());
            }
        } finally {
            RefThreadGuardTestUtil.disarm();
            executor.shutdownNow();
        }
        // disarmed: foreign threads are free again
        assertEquals("identifiable", strongRef.get());
    }
}
