/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

/**
 * The pi model of each tap of a tap changer, as columns indexed by tap index (tap position minus the low tap
 * position): what a closed branch's equations read at that tap. {@link PiModelArray#getTapModels()} reads them from the
 * taps' models when called, so a later minimum impedance change is not in a table read before it.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public record TapPiModels(double[] r, double[] x, double[] y, double[] ksi, double[] g1, double[] b1, double[] g2, double[] b2,
                          double[] r1, double[] a1) {

    public int getTapCount() {
        return r1.length;
    }
}
