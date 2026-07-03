/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

/**
 * A reactive-limit breakpoint on the P-V curve: the load factor at which a voltage-controlled generator bus hit
 * its reactive power limit and was switched from PV (voltage-controlled) to PQ (reactive power held at the limit).
 *
 * @param loadFactor the load increase factor (lambda) at which the switch occurred
 * @param busId      the id of the bus that switched PV to PQ
 * @param qLimitMvar the reactive power limit the generation was frozen at, in MVar
 * @param maxLimit   {@code true} if the maximum reactive limit was hit, {@code false} for the minimum
 *
 * @author Claude
 */
public record ReactiveLimitBreakpoint(double loadFactor, String busId, double qLimitMvar, boolean maxLimit) {
}
