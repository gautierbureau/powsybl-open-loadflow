/**
 * Copyright (c) 2026, SuperGrid Institute (http://www.supergrid-institute.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfAcDcConverter;
import com.powsybl.openloadflow.network.LfDcBus;
import com.powsybl.openloadflow.network.LfDcComponentValidator;

import java.util.List;

/**
 * Outer loop that ensures AC-DC converters operate within their power limits, changing their control mode if needed.
 * No switch-back is implemented, which might become a blocking point for contingency analysis.
 *
 * @author Baptiste Perreyon {@literal <baptiste.perreyon at supergrid-institute.com>}
 */
public class AcDcConverterPowerLimitsOuterLoop implements AcOuterLoop {

    public static final String NAME = "AcDcConverterPowerLimits";
    private final double maxActivePowerMismatch;  // Maximum active power mismatch after Newton-Raphson

    public AcDcConverterPowerLimitsOuterLoop(double maxActivePowerMismatch) {
        this.maxActivePowerMismatch = maxActivePowerMismatch;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        List<Integer> numDccs = context.getNetwork().getDcBuses().stream().map(LfDcBus::getNumDCC).distinct().sorted().toList();
        // In each DC network, up to one converter control mode can be modified
        OuterLoopStatus globalStatus = OuterLoopStatus.STABLE;
        for (int numDcc : numDccs) {

            List<LfAcDcConverter> converters = LfDcComponentValidator.getAcDcConverters(context.getNetwork(), numDcc);

            // Get the converter to change in P_PCC mode, if any needs to.
            int count = converters.size();
            boolean[] controlsDcVoltage = new boolean[count];
            double[] pac = new double[count];
            double[] minP = new double[count];
            double[] maxP = new double[count];
            for (int i = 0; i < count; i++) {
                LfAcDcConverter converter = converters.get(i);
                AcDcConverter.ControlMode mode = converter.getControlMode();
                controlsDcVoltage[i] = mode == AcDcConverter.ControlMode.V_DC || mode == AcDcConverter.ControlMode.DC_DROOP;
                if (controlsDcVoltage[i]) {
                    pac[i] = converter.getPac();
                    minP[i] = converter.getMinP();
                    maxP[i] = converter.getMaxP();
                }
            }
            int selected = AcDcConverterPowerLimitsDecisions.selectConverter(count, controlsDcVoltage, pac, minP, maxP, maxActivePowerMismatch);

            if (selected < 0) {  // No converters violate its limit
                continue;
            }
            LfAcDcConverter selectedConverter = converters.get(selected);

            // However, in a first place, we must ensure that this control mode change does not prevent the control of DC buses voltage.
            if (!LfDcComponentValidator.isValidAfterConverterChangesToPpccMode(context.getNetwork(), numDcc, selectedConverter)) {
                return new OuterLoopResult(this, OuterLoopStatus.FAILED,
                        "Could not enforce AC-DC converter active power limit and ensure DC voltage control in DC component " + numDcc);
            }

            // Set the converter's targetP to the exceeded limit.
            double newTargetP = AcDcConverterPowerLimitsDecisions.newTargetP(pac[selected], minP[selected], maxP[selected]);
            selectedConverter.setTargetP(newTargetP);
            selectedConverter.setControlMode(AcDcConverter.ControlMode.P_PCC);
            globalStatus = OuterLoopStatus.UNSTABLE;
        }
        return new OuterLoopResult(this, globalStatus);
    }

    @Override
    public boolean isNeeded(AcLoadFlowContext context) {
        // Needed if the network contains an AC-DC converter.
        return !context.getNetwork().getVoltageSourceConverters().isEmpty();
    }
}
