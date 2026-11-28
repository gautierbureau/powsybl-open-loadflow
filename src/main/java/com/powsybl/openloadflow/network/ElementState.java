/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ElementState<T extends LfElement> {

    protected final T element;

    protected final boolean disabled;

    public ElementState(T element) {
        this.element = Objects.requireNonNull(element);
        disabled = element.isDisabled();
    }

    public void restore() {
        element.setDisabled(disabled);
    }

    /**
     * What differs between the element and this saved state, or null when {@link #restore()} would change nothing.
     * For the assertion that follows a partial restore of the network.
     */
    String findDifference() {
        return element.isDisabled() == disabled ? null : "disabled";
    }

    static boolean same(double value, double saved) {
        return value == saved || Double.isNaN(value) && Double.isNaN(saved);
    }

    public static <T extends LfElement, U extends ElementState<T>> List<U> save(Collection<T> elements, Function<T, U> save) {
        Objects.requireNonNull(elements);
        Objects.requireNonNull(save);
        List<U> states = new ArrayList<>(elements.size());
        for (T element : elements) {
            states.add(save.apply(element));
        }
        return states;
    }

    public static <T extends LfElement, U extends ElementState<T>> void restore(Collection<U> states) {
        Objects.requireNonNull(states);
        states.forEach(ElementState::restore);
    }
}
