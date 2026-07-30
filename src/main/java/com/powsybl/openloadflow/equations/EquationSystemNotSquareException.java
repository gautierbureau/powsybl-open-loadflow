/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.commons.PowsyblException;

/**
 * Thrown when the equation system is not square, i.e. a variable is left without the equation determining it. This is
 * an invariant failure of the modeling, not of the data: it means some part of the equation system activated or
 * deactivated equations and variables inconsistently.
 *
 * <p>It has its own type so that a caller able to recover can catch precisely this failure instead of any
 * {@link PowsyblException}: a security analysis can re-run the offending contingency on the legacy modeling rather
 * than fail the whole analysis (see the alternative equations fallback in the security analysis).
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public class EquationSystemNotSquareException extends PowsyblException {

    public EquationSystemNotSquareException(String message) {
        super(message);
    }
}
