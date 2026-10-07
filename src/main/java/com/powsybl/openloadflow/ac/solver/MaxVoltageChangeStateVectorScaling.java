/**
 * Copyright (c) 2022, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationVector;
import com.powsybl.openloadflow.equations.TargetVector;
import com.powsybl.openloadflow.equations.Vectors;
import com.powsybl.openloadflow.util.Reports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Limit voltage magnitude change and voltage angle change between NR iterations
 *
 * @author Damien Jeandemange {@literal <damien.jeandemange at artelys.com>}
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class MaxVoltageChangeStateVectorScaling implements StateVectorScaling {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaxVoltageChangeStateVectorScaling.class);

    public static final double DEFAULT_MAX_DV = 0.1;
    public static final double DEFAULT_MAX_DPHI = Math.toRadians(10);

    private static final boolean STEP_TRACE = System.getenv("OLF_STEP_TRACE") != null;

    private final double maxDv;
    private final double maxDphi;

    public MaxVoltageChangeStateVectorScaling(double maxDv, double maxDphi) {
        this.maxDv = maxDv;
        this.maxDphi = maxDphi;
    }

    @Override
    public StateVectorScalingMode getMode() {
        return StateVectorScalingMode.MAX_VOLTAGE_CHANGE;
    }

    @Override
    public void apply(double[] dx, EquationSystem<AcVariableType, AcEquationType> equationSystem, ReportNode reportNode) {
        int vCutCount = 0;
        int phiCutCount = 0;
        double stepSize = 1.0;
        for (var variable : equationSystem.getIndex().getSortedVariablesToFind()) {
            int row = variable.getRow();
            double absValueChange = Math.abs(dx[row]);
            switch (variable.getType()) {
                case BUS_V:
                    if (absValueChange > maxDv) {
                        stepSize = Math.min(stepSize, maxDv / absValueChange);
                        vCutCount++;
                    }
                    break;
                case BUS_PHI:
                    if (absValueChange > maxDphi) {
                        stepSize = Math.min(stepSize, maxDphi / absValueChange);
                        phiCutCount++;
                    }
                    break;
                default:
                    break;
            }
        }
        // OLF_STEP_TRACE=1 (+ OLF_TRACE_CTG=<id> to scope it): the step size OLF actually applies, with
        // the variable that binds it. The GPU side prints the same thing (OLF_GPU_STEP_TRACE), and the
        // comparison is what splits "the device's step is throttled where OLF's is not" from "both are
        // throttled and the device's iterates still differ" — the open question on the 108
        // non-converging batched scenarios (powsybl-open-loadflow-gpu docs/tasks.md #67). Diagnostic only.
        if (STEP_TRACE && com.powsybl.openloadflow.util.OlfTraceScope.wanted()) {
            double mdv = 0;
            double mdphi = 0;
            String av = "-";
            String ap = "-";
            for (var variable : equationSystem.getIndex().getSortedVariablesToFind()) {
                double a2 = Math.abs(dx[variable.getRow()]);
                if (variable.getType() == AcVariableType.BUS_V && a2 > mdv) {
                    mdv = a2;
                    av = "row" + variable.getRow() + "/elem" + variable.getElementNum();
                } else if (variable.getType() == AcVariableType.BUS_PHI && a2 > mdphi) {
                    mdphi = a2;
                    ap = "row" + variable.getRow() + "/elem" + variable.getElementNum();
                }
            }
            System.err.printf("OLF_STEP ctg=%s f=%.9g maxDv=%.6g at=%s maxDphi=%.6g at=%s vCut=%d phiCut=%d%n",
                    com.powsybl.openloadflow.util.OlfTraceScope.current(), stepSize, mdv, av, mdphi, ap,
                    vCutCount, phiCutCount);
        }
        if (vCutCount > 0 || phiCutCount > 0) {
            LOGGER.debug("Step size: {} ({} dv and {} dphi changes outside thresholds)", stepSize, vCutCount, phiCutCount);
            if (reportNode != null) {
                Reports.reportMaxVoltageChangeStateVectorScaling(reportNode, stepSize, vCutCount, phiCutCount);
            }
            Vectors.mult(dx, stepSize);
        }
    }

    @Override
    public NewtonRaphsonStoppingCriteria.TestResult applyAfter(EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                               EquationVector<AcVariableType, AcEquationType> equationVector,
                                                               TargetVector<AcVariableType, AcEquationType> targetVector,
                                                               NewtonRaphsonStoppingCriteria stoppingCriteria,
                                                               NewtonRaphsonStoppingCriteria.TestResult testResult,
                                                               ReportNode reportNode) {
        // nothing to do
        return testResult;
    }
}
