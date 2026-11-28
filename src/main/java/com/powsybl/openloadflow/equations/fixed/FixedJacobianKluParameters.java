/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

import com.powsybl.math.matrix.KluParameters;

import java.util.Locale;

/**
 * The KLU parameters of the matrix of a {@link FixedJacobian}. Its pattern is analysed once and factorized many times,
 * which pays for the minimum-fill ordering: some 20% fewer operations than AMD on a power network's Jacobian, for a
 * longer analysis. The others are the defaults of the library.
 *
 * <p>For tests, the {@code olf.klu.*} system properties override them: ordering (amd, colamd, minimum_fill), btf
 * (true, false), scale (-1, 0, 1, 2), tol, check (rgrowth, rcond, none) and rcondThreshold.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class FixedJacobianKluParameters {

    public static final String PROPERTY_PREFIX = "olf.klu.";

    private FixedJacobianKluParameters() {
    }

    private static String property(String name) {
        return System.getProperty(PROPERTY_PREFIX + name);
    }

    /** The parameters, a new instance each time: those of the system properties read when it is created. */
    public static KluParameters create() {
        KluParameters parameters = new KluParameters().setOrdering(KluParameters.Ordering.MINIMUM_FILL);
        String value = property("ordering");
        if (value != null) {
            parameters.setOrdering(KluParameters.Ordering.valueOf(value.toUpperCase(Locale.ROOT)));
        }
        value = property("btf");
        if (value != null) {
            parameters.setBtf(Boolean.parseBoolean(value));
        }
        value = property("scale");
        if (value != null) {
            parameters.setScale(Integer.parseInt(value));
        }
        value = property("tol");
        if (value != null) {
            parameters.setTol(Double.parseDouble(value));
        }
        value = property("check");
        if (value != null) {
            parameters.setCheck(KluParameters.Check.valueOf(value.toUpperCase(Locale.ROOT)));
        }
        value = property("rcondThreshold");
        if (value != null) {
            parameters.setRcondThreshold(Double.parseDouble(value));
        }
        return parameters;
    }
}
