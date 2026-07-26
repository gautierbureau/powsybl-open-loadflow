/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

/**
 * Test access to the {@link RefThreadGuard}: once armed, the first thread that dereferences an
 * IIDM element through a {@link Ref} (the analysis thread building the LfNetwork) becomes the only
 * allowed one, and any dereference from another thread throws. Used by the multi-thread COPY mode
 * tests to prove that partition worker threads never touch the IIDM network.
 *
 * @author Open Load Flow contributors
 */
public final class RefThreadGuardTestUtil {

    private RefThreadGuardTestUtil() {
    }

    public static void arm() {
        RefThreadGuard.arm();
    }

    public static void disarm() {
        RefThreadGuard.disarm();
    }
}
