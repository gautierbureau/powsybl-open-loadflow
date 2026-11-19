/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.util;

/**
 * An evaluable whose value is stored in an array: {@code eval() == getValues()[getValueIndex()]}. The array stays the
 * storage of the value as long as the evaluable is used, so that callers reading many values can keep the array and
 * the index instead of calling {@link #eval()} each time.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface ArrayEvaluable extends Evaluable {

    double[] getValues();

    int getValueIndex();
}
