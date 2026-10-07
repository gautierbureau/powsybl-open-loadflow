/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
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
import com.powsybl.openloadflow.lf.outerloop.AbstractActivePowerDistributionOuterLoop;
import com.powsybl.openloadflow.lf.outerloop.DistributedSlackContextData;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfSynchronousNetwork;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class DistributedSlackOuterLoop
        extends AbstractActivePowerDistributionOuterLoop<AcVariableType, AcEquationType, AcLoadFlowParameters, AcLoadFlowContext, AcOuterLoopContext>
        implements AcOuterLoop, AcActivePowerDistributionOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedSlackOuterLoop.class);

    public static final String NAME = "DistributedSlack";

    private final double slackBusPMaxMismatch;

    private final ActivePowerDistribution activePowerDistribution;

    public DistributedSlackOuterLoop(ActivePowerDistribution activePowerDistribution, double slackBusPMaxMismatch) {
        this.activePowerDistribution = Objects.requireNonNull(activePowerDistribution);
        this.slackBusPMaxMismatch = slackBusPMaxMismatch;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /** {@code OLF_DS_TRACE}: print the mismatch this loop sees and the amount it distributes on
     *  every pass, to stderr. The stopping rule is a TOLERANCE (slackBusPMaxMismatch, 1 MW by
     *  default), so the converged operating point depends on the sequence of passes, not only on
     *  the network — and that sequence is exactly what another implementation has to reproduce.
     *  The test logging config attaches no appender to this logger. */
    private static final boolean DS_TRACE = System.getenv("OLF_DS_TRACE") != null;

    /** The contingency whose post-contingency solve is running on this thread, so the pass sequence
     *  printed below can be attributed. A full security analysis runs this loop for every
     *  contingency; without the tag the passes of 10540 of them are one undifferentiated stream.
     *  Set by AbstractSecurityAnalysis around the post-contingency run. */
    public static final ThreadLocal<String> CURRENT_CONTINGENCY = new ThreadLocal<>();

    /** {@code OLF_DS_TRACE_CTG=<id>[,<id>]}: restrict the trace to these contingencies (the base
     *  load flow, which has no id, prints as {@code base} and is always included). */
    private static final String DS_TRACE_CTG = System.getenv("OLF_DS_TRACE_CTG");

    private static boolean traced() {
        if (!DS_TRACE || !com.powsybl.openloadflow.util.OlfTraceScope.wanted()) {   // OLF_TRACE_CTG too
            return false;
        }
        String ctg = CURRENT_CONTINGENCY.get();
        if (DS_TRACE_CTG == null || ctg == null) {
            return true;
        }
        for (String want : DS_TRACE_CTG.split(",")) {
            if (want.trim().equals(ctg)) {
                return true;
            }
        }
        return false;
    }

    private static String ctgTag() {
        String ctg = CURRENT_CONTINGENCY.get();
        return ctg == null ? "base" : ctg;
    }

    /** The band this loop distributes outside of, in MW. Exposed so an alternative engine can drive
     *  the same loop with the run's own tolerance rather than a guessed one. */
    public ActivePowerDistribution getActivePowerDistribution() {
        return activePowerDistribution;
    }

    public double getSlackBusPMaxMismatch() {
        return slackBusPMaxMismatch;
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        context.setData(new DistributedSlackContextData());
    }

    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        Map<Integer, Double> slackMismatchPerSynchronousComponent = context.getLastSolverResult().getSlackBusActivePowerMismatch();

        // We need to create a root report node in oder to include it only if there are child reports
        ReportNode iterationReportNode = Reports.createRootOuterLoopIterationReporter(reportNode, context.getOuterLoopTotalIterations() + 1);
        boolean severalSc = context.getNetwork().getSynchronousNetworks().size() > 1;

        OuterLoopStatus globalStatus = OuterLoopStatus.STABLE;
        for (LfSynchronousNetwork lfScNetwork : context.getNetwork().getSynchronousNetworks()) {
            OuterLoopResult result = check(lfScNetwork, slackMismatchPerSynchronousComponent.get(lfScNetwork.getNumSC()), context, iterationReportNode, severalSc);
            if (result.status() == OuterLoopStatus.FAILED) {
                // Include iteration report node in the main report node if it has children reports
                if (!iterationReportNode.getChildren().isEmpty()) {
                    reportNode.include(iterationReportNode);
                }
                return result; // We do not wait for the outer loops on other synchronous components
            } else if (result.status() == OuterLoopStatus.UNSTABLE) {
                globalStatus = OuterLoopStatus.UNSTABLE;
            }
        }

        // Include iteration report node in the main report node if it has children reports
        if (!iterationReportNode.getChildren().isEmpty()) {
            reportNode.include(iterationReportNode);
        }
        return new OuterLoopResult(this, globalStatus);

    }

    /**
     * Check if slack buses active power mismatch must be distributed over generators or loads and update their target
     * active power to do so if such case.
     * @param lfScNetwork: The synchronous network on which slack bus mismatch must be checked and fixed.
     * @param slackBusActivePowerMismatch Active power mismatch in the synchronous network. In per unit.
     * @param context AC outer loop context.
     * @param reportNode Report node of the current outer loop iteration
     * @param createSubReport if true, create a sub report node with the synchronous network number. Otherwise, reports are directly added to the main report node.
     * @return Outer loop result with its status and error message in case of failure.
     */
    public OuterLoopResult check(LfSynchronousNetwork lfScNetwork, double slackBusActivePowerMismatch, AcOuterLoopContext context, ReportNode reportNode, boolean createSubReport) {
        double absMismatch = Math.abs(slackBusActivePowerMismatch);
        boolean shouldDistributeSlack = absMismatch > slackBusPMaxMismatch / PerUnit.SB && absMismatch > ActivePowerDistribution.P_RESIDUE_EPS;

        if (traced()) {
            // ...and the two halves of the mismatch per slack bus: the evaluated P and the target, so
            // a gate that reads differently on another implementation for the same state can be
            // split into "P differs" and "target differs".
            StringBuilder halves = new StringBuilder();
            for (var sb : lfScNetwork.getSlackBuses()) {
                halves.append(String.format(" slack=%s p=%.12f targetP=%.12f", sb.getId(), sb.getP().eval(), sb.getTargetP()));
            }
            System.err.printf("DS_PASS ctg=%s sc=%d mismatch=%.12f threshold=%.12f distribute=%b%s%n",
                    ctgTag(), lfScNetwork.getNumSC(), slackBusActivePowerMismatch,
                    slackBusPMaxMismatch / PerUnit.SB, shouldDistributeSlack, halves);
        }
        if (!shouldDistributeSlack) {
            LOGGER.debug("Already balanced");
            return new OuterLoopResult(this, OuterLoopStatus.STABLE);
        }
        ReportNode iterationReportNode = createSubReport ? Reports.createLfSynchronousNetworkReportNode(reportNode, lfScNetwork.getNumSC()) : reportNode;
        ActivePowerDistribution.Result result = activePowerDistribution.run(lfScNetwork, slackBusActivePowerMismatch);
        ActivePowerDistribution.ResultWithFailureBehaviorHandling resultWbh = ActivePowerDistribution.handleDistributionFailureBehavior(
                context.getLoadFlowContext().getParameters().getSlackDistributionFailureBehavior(),
                lfScNetwork.getReferenceGenerator(),
                slackBusActivePowerMismatch,
                result,
                "Failed to distribute slack bus active power mismatch, %.2f MW remains"
        );
        double remainingMismatch = resultWbh.remainingMismatch();
        double distributedActivePower = slackBusActivePowerMismatch - remainingMismatch;
        if (traced()) {
            System.err.printf("DS_DIST ctg=%s sc=%d distributed=%.12f remaining=%.12f movedBuses=%b iterations=%d%n",
                    ctgTag(), lfScNetwork.getNumSC(), distributedActivePower, remainingMismatch,
                    resultWbh.movedBuses(), result.iteration());
        }
        if (Math.abs(remainingMismatch) > slackBusPMaxMismatch / PerUnit.SB) {
            Reports.reportMismatchDistributionFailure(iterationReportNode, remainingMismatch * PerUnit.SB);
        } else {
            if (Math.abs(remainingMismatch) > ActivePowerDistribution.P_RESIDUE_EPS) {
                Reports.reportResidualDistributionMismatch(reportNode, remainingMismatch * PerUnit.SB);
            }
            ActivePowerDistribution.reportAndLogSuccess(iterationReportNode, slackBusActivePowerMismatch, resultWbh);
        }
        DistributedSlackContextData contextData = (DistributedSlackContextData) context.getData();
        contextData.addDistributedActivePower(lfScNetwork.getNumSC(), distributedActivePower);
        if (resultWbh.failed()) {
            contextData.addDistributedActivePower(lfScNetwork.getNumSC(), -resultWbh.failedDistributedActivePower());
            return new OuterLoopResult(this, OuterLoopStatus.FAILED, resultWbh.failedMessage());
        } else {
            return new OuterLoopResult(this, resultWbh.movedBuses() ? OuterLoopStatus.UNSTABLE : OuterLoopStatus.STABLE);
        }
    }

    @Override
    public double getSlackBusActivePowerMismatch(AcOuterLoopContext context) {
        return context.getLastSolverResult().getSlackBusActivePowerMismatch().values().stream().reduce(0., Double::sum);
    }

    @Override
    public double getDistributedActivePower(AcOuterLoopContext context, int numSC) {
        return ((DistributedSlackContextData) context.getData()).getDistributedActivePower(numSC);
    }
}
