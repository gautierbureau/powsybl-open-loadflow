/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-support guard asserting that the Lf model never dereferences the underlying IIDM network
 * from more than one thread: once armed, the first thread that dereferences a {@link Ref} (the
 * analysis thread building the LfNetwork) becomes the only allowed one, so any later dereference
 * from a multi-thread partition worker throws. Disabled (single non-volatile null check) unless a
 * test arms it; production code never does.
 *
 * @author Open Load Flow contributors
 */
final class RefThreadGuard {

    private static volatile AtomicReference<Thread> allowedThread;

    private RefThreadGuard() {
    }

    static void arm() {
        allowedThread = new AtomicReference<>();
    }

    static void disarm() {
        allowedThread = null;
    }

    static void check() {
        AtomicReference<Thread> allowed = allowedThread;
        if (allowed != null) {
            Thread current = Thread.currentThread();
            if (allowed.get() == null && allowed.compareAndSet(null, current)) {
                return;
            }
            if (allowed.get() != current) {
                throw new IllegalStateException("IIDM network dereferenced from thread '" + current.getName()
                        + "' while the Lf model was built by thread '" + allowed.get().getName() + "'");
            }
        }
    }
}
