/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

/**
 * The members of the saved states of the elements that the tests of the other packages read.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class ElementStates {

    private ElementStates() {
    }

    public static String findDifference(ElementState<?> state) {
        return state.findDifference();
    }

    public static String findDifference(NetworkState state) {
        return state.findDifference();
    }

    public static <T extends LfElement> T element(ElementState<T> state) {
        return state.element;
    }
}
