/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.lf.outerloop.AbstractHvdcAcEmulationLimitsOuterLoop;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.HvdcAcEmulationVector;
import com.powsybl.openloadflow.network.LfHvdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Sylvestre Prabakaran {@literal <sylvestre.prabakaran at rte-france.com>}
 * @author Hadrien Godard {@literal <hadrien.godard at artelys.com>}
 */
public class AcHvdcAcEmulationLimitsOuterLoop
        extends AbstractHvdcAcEmulationLimitsOuterLoop<AcVariableType, AcEquationType, AcLoadFlowParameters, AcLoadFlowContext, AcOuterLoopContext>
        implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcHvdcAcEmulationLimitsOuterLoop.class);

    /** The HVDC lines in AC emulation, gathered once when the outer loop is initialized, in the order of the network. */
    private record ContextData(HvdcAcEmulationVector acEmulationHvdcs) {
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        context.setData(new ContextData(HvdcAcEmulationVector.of(context.getNetwork())));
    }

    /**
     * Checks each HVDC line in AC emulation that is connected, as the device does one line per thread: its flows
     * against its limits switch it between linear mode and saturation.
     */
    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        OuterLoopStatus status = OuterLoopStatus.STABLE;

        HvdcAcEmulationVector hvdcs = ((ContextData) context.getData()).acEmulationHvdcs();
        for (int i = 0; i < hvdcs.count; i++) {
            LfHvdc hvdc = context.getNetwork().getHvdc(hvdcs.hvdcNum[i]);
            if (hvdc.getBus1().isDisabled() || hvdc.getBus2().isDisabled() || hvdc.isDisabled()) {
                continue;
            }
            if (checkAcEmulationMode(hvdc, true, LOGGER, reportNode)) {
                LOGGER.trace("Hvdc '{}' AC emulation state is changed to {}", hvdc.getId(), hvdc.getAcEmulationControl().getAcEmulationStatus());
                status = OuterLoopStatus.UNSTABLE;
            }
        }
        return new OuterLoopResult(this, status);
    }

    @Override
    public boolean isNeeded(AcLoadFlowContext context) {
        // Needed if the network contains an lfHVDC in AC Emulation mode
        return context.getNetwork().getHvdcs().stream().anyMatch(LfHvdc::isAcEmulation);
    }
}
