/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.util;

/**
 * Which contingency (or operator strategy) the debug traces below are currently inside.
 *
 * <p>Purely diagnostic, and the reason it exists: {@code DS_RUN}, {@code DS_SPLIT}, {@code DS_SAT},
 * {@code DS_PASS} and {@code RL_SWITCH} carry no contingency id, so in a security analysis of
 * thousands of contingencies none of them can be ATTRIBUTED. Debugging one contingency therefore
 * meant re-running it alone — and for a busbar-section contingency an alone-run builds a DIFFERENT
 * network, because which switches stay retained follows the contingency list. Two rte6515
 * contingencies whose distributed active power disagrees with the GPU arm (BUS-4467_BBS,
 * BUS-5250_BBS) both PASS when run alone, so the alone-run reproduces nothing and the per-generator
 * comparison that the disagreement needs could not be made at all.
 *
 * <p>{@link #current()} labels a trace line; {@link #wanted} lets a dump keep only one
 * contingency's section, so a per-generator dump over 6667 contingencies stays a few hundred lines
 * instead of millions. Set by {@code AbstractSecurityAnalysis} around each post-contingency
 * simulation; null in the base load flow.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class OlfTraceScope {

    /** Single-threaded by construction for the traced runs (the GPU comparison harness runs the CPU
     *  arm multi-threaded, so a thread-local keeps one worker's label off another's line). */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** {@code OLF_TRACE_CTG=<id>}: restrict the filtered dumps to that contingency. Null = all. */
    private static final String WANTED = System.getenv("OLF_TRACE_CTG");

    private OlfTraceScope() {
    }

    /** The contingency being simulated on this thread, or {@code "base"} outside one. */
    public static String current() {
        String c = CURRENT.get();
        return c == null ? "base" : c;
    }

    public static void enter(String contingencyId) {
        CURRENT.set(contingencyId);
    }

    public static void leave() {
        CURRENT.remove();
    }

    /** Should a FILTERED dump write for the current scope? True when no filter is set. */
    public static boolean wanted() {
        return WANTED == null || WANTED.equals(current());
    }
}
