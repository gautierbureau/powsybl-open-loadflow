/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.lf.AbstractLoadFlowParameters;
import com.powsybl.openloadflow.lf.LoadFlowContext;
import com.powsybl.openloadflow.lf.util.LfReports;
import com.powsybl.openloadflow.network.LfHvdc;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;


/**
 * @author Sylvestre Prabakaran {@literal <sylvestre.prabakaran at rte-france.com>}
 * @author Hadrien Godard {@literal <hadrien.godard at artelys.com>}
 */
public abstract class AbstractHvdcAcEmulationLimitsOuterLoop<V extends Enum<V> & Quantity,
        E extends Enum<E> & Quantity,
        P extends AbstractLoadFlowParameters<P>,
        C extends LoadFlowContext<V, E, P>,
        O extends OuterLoopContext<V, E, P, C>> implements OuterLoop<V, E, P, C, O> {

    public static final String NAME = "HvdcAcEmulationLimits";

    protected AbstractHvdcAcEmulationLimitsOuterLoop() { }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Reads the line's status and flows, decides on them ({@link HvdcAcEmulationDecisions#decision}) and switches the
     * line's mode, with the same reports as before.
     *
     * @return whether the line's mode changed
     */
    protected static boolean checkAcEmulationMode(LfHvdc hvdc, boolean computeLoss, Logger logger, ReportNode reportNode) {
        LfHvdc.AcEmulationControl acEmulationControl = hvdc.getAcEmulationControl();
        int status = HvdcAcEmulationDecisions.statusCode(acEmulationControl.getAcEmulationStatus());
        if (status == HvdcAcEmulationDecisions.STATUS_FROZEN) {
            return false;
        }
        double pMax12 = acEmulationControl.getPMaxFromCS1toCS2();
        double pMax21 = acEmulationControl.getPMaxFromCS2toCS1();
        double p1 = hvdc.getP1().eval();
        double p2 = hvdc.getP2().eval();
        int decision = HvdcAcEmulationDecisions.decision(status, p1, p2, pMax12, pMax21);
        switch (decision) {
            case HvdcAcEmulationDecisions.DECISION_TO_SATURATION_FROM_CS1_TO_CS2 -> {
                acEmulationControl.switchToSaturationFromCS1toCS2(computeLoss);
                if (status == HvdcAcEmulationDecisions.STATUS_LINEAR_MODE) {
                    // If the HVDC was in linear mode but overpasses P_max -> switching to saturated mode
                    LfReports.reportAcEmulationFromLinearToSaturated(reportNode, hvdc.getId(), hvdc.getConverterStation1().getId(),
                            hvdc.getConverterStation2().getId(), pMax12 * PerUnit.SB, logger);
                } else {
                    // If the HVDC was in saturation mode CS2toCS1 but goes over opposite Pmax -> switching to saturation mode CS1toCS2
                    LfReports.reportAcEmulationSaturationSideSwitch(reportNode, hvdc.getId(), hvdc.getConverterStation1().getId(),
                            hvdc.getConverterStation2().getId(), pMax12 * PerUnit.SB, logger);
                }
                return true;
            }
            case HvdcAcEmulationDecisions.DECISION_TO_SATURATION_FROM_CS2_TO_CS1 -> {
                acEmulationControl.switchToSaturationFromCS2toCS1(computeLoss);
                if (status == HvdcAcEmulationDecisions.STATUS_LINEAR_MODE) {
                    LfReports.reportAcEmulationFromLinearToSaturated(reportNode, hvdc.getId(), hvdc.getConverterStation2().getId(),
                            hvdc.getConverterStation1().getId(), pMax21 * PerUnit.SB, logger);
                } else {
                    // If the HVDC was in saturation mode CS1toCS2 but goes over opposite Pmax -> switching to saturation mode CS2toCS1
                    LfReports.reportAcEmulationSaturationSideSwitch(reportNode, hvdc.getId(), hvdc.getConverterStation2().getId(),
                            hvdc.getConverterStation1().getId(), pMax21 * PerUnit.SB, logger);
                }
                return true;
            }
            case HvdcAcEmulationDecisions.DECISION_TO_LINEAR_MODE -> {
                // If the HVDC was in saturation mode but goes back within active power limits -> switching to linear mode
                acEmulationControl.switchToLinearMode();
                LfReports.reportAcEmulationBackToLinear(reportNode, hvdc.getId(), logger);
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
