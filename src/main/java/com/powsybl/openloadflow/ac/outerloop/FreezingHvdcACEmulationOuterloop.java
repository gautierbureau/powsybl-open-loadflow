/*
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.ac.equations.AcStateVectorUtil;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.lf.util.LfReports;
import com.powsybl.openloadflow.network.LfHvdc;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Didier Vidal {@literal <didier.vidal_externe at rte-france.com>}
 */
public class FreezingHvdcACEmulationOuterloop implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(FreezingHvdcACEmulationOuterloop.class);

    public static final String NAME = "FreezingHvdcACEmulation";

    /**
     * The HVDC lines in AC emulation, gathered once when the outer loop is initialized: the ones frozen before the
     * first solve, and released at the first check.
     */
    private static final class ContextData {

        private final LfHvdc[] acEmulationHvdcs;

        private ContextData(LfNetwork network) {
            acEmulationHvdcs = network.getHvdcs().stream().filter(LfHvdc::isAcEmulation).toArray(LfHvdc[]::new);
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        ContextData contextData = new ContextData(context.getNetwork());
        context.setData(contextData);
        // Updating State Vector with previous values
        AcStateVectorUtil.initStateVector(context.getNetwork(), context.getLoadFlowContext().getEquationSystem(), new PreviousValueVoltageInitializer(true));
        for (LfHvdc lfHvdc : contextData.acEmulationHvdcs) {
            // If some angles are at NaN, it means that the HVDC has been reconnected by an action, AC emulation is not frozen
            if (!lfHvdc.isDisabled() && !lfHvdc.getBus1().isDisabled() && !lfHvdc.getBus2().isDisabled()) {
                if (!Double.isNaN(lfHvdc.getBus1().getAngle() - lfHvdc.getBus2().getAngle())) {
                    lfHvdc.getAcEmulationControl().switchToFrozenState(true);
                    double setPointHvdc;
                    String controllerId;
                    String nonControllerId;
                    if (lfHvdc.getConverterStation1().getTargetP() < 0) {
                        setPointHvdc = -lfHvdc.getConverterStation1().getTargetP() * PerUnit.SB;
                        controllerId = lfHvdc.getConverterStation1().getId();
                        nonControllerId = lfHvdc.getConverterStation2().getId();
                    } else {
                        setPointHvdc = -lfHvdc.getConverterStation2().getTargetP() * PerUnit.SB;
                        controllerId = lfHvdc.getConverterStation2().getId();
                        nonControllerId = lfHvdc.getConverterStation1().getId();
                    }
                    LfReports.reportFreezeHvdc(context.getNetwork().getReportNode(), lfHvdc.getId(), controllerId, nonControllerId, setPointHvdc, LOGGER);
                } else {
                    LfReports.reportNoFreezeBecauseHvdcAction(context.getNetwork().getReportNode(), lfHvdc.getId(), LOGGER);
                }
            }
        }
    }

    @Override
    public boolean isNeeded(AcLoadFlowContext context) {
        // Needed if the network contains an lfHVDC in AC Emulation mode
        return context.getNetwork().getHvdcs().stream().anyMatch(LfHvdc::isAcEmulation);
    }

    /** The HVDC lines frozen before the first solve are released at the first check; the next checks find none. */
    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        ContextData contextData = (ContextData) context.getData();
        boolean unfrozen = false;
        for (LfHvdc lfHvdc : contextData.acEmulationHvdcs) {
            if (lfHvdc.getAcEmulationControl().getAcEmulationStatus() == LfHvdc.AcEmulationControl.AcEmulationStatus.FROZEN) {
                LfReports.reportUnfreezeHvdc(reportNode, lfHvdc.getId(), LOGGER);
                lfHvdc.getAcEmulationControl().switchToLinearMode();
                unfrozen = true;
            }
        }
        return new OuterLoopResult(this, unfrozen ? OuterLoopStatus.UNSTABLE : OuterLoopStatus.STABLE);
    }

}
