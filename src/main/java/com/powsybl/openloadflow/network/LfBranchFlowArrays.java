/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

/**
 * The branch flows of a network held as flat arrays indexed by {@link LfBranch#getNum()}, when the equation system
 * maintains them that way.
 *
 * <p>This is the bulk counterpart of {@link LfBranch#getP1()} and friends: reading a flow through the {@link
 * com.powsybl.openloadflow.util.Evaluable} of a branch is a pointer chase (branch, then its evaluable, then the value),
 * and the call site is megamorphic, so a consumer that reads one flow per branch of the whole network - the security
 * analysis limit violation detection - cannot be inlined or vectorised. Given these arrays it reads a {@code double[]}
 * with an {@code int} index instead.
 *
 * <p><b>Exactness.</b> {@link #p1()}, {@link #q1()}, {@link #p2()} and {@link #q2()} hold the very doubles that the
 * corresponding evaluables return, so a consumer can substitute one for the other freely. {@link #i1()} and {@link
 * #i2()} do <b>not</b>: the vectorised current is {@code hypot(p, q) / v} whereas the current magnitude equation term
 * evaluates {@code hypot(re(I), im(I))}. The two agree to rounding, not bit for bit, so the arrays must only be used
 * to <b>filter</b> - with a margin covering the disagreement - never to decide.
 *
 * <p><b>Coverage.</b> The arrays do not describe every branch: the equation system may model some of them outside the
 * vectorised terms (a zero impedance branch flows through its dummy variables, for instance), and those keep whatever
 * value last landed in their slots. {@link #describedBranches()} says which ones they do describe. On top of it a
 * consumer must still check that the branch is enabled and connected on both sides, since a disabled or half open
 * branch is not updated either and evaluates through different terms.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public interface LfBranchFlowArrays {

    /**
     * Whether the arrays describe a branch at all, indexed by branch num: true when its closed flows are the
     * vectorised terms, so that reading the arrays and evaluating its evaluables are the same computation. False for
     * the branches the equation system models otherwise; those must be read through their evaluables.
     */
    boolean[] describedBranches();

    /** Active power at side 1, per unit, indexed by branch num. */
    double[] p1();

    /** Reactive power at side 1, per unit, indexed by branch num. */
    double[] q1();

    /** Current magnitude at side 1, per unit, indexed by branch num. Filtering only, see the class javadoc. */
    double[] i1();

    /** Active power at side 2, per unit, indexed by branch num. */
    double[] p2();

    /** Reactive power at side 2, per unit, indexed by branch num. */
    double[] q2();

    /** Current magnitude at side 2, per unit, indexed by branch num. Filtering only, see the class javadoc. */
    double[] i2();
}
