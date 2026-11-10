/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.VoltageTargetChecker;
import com.powsybl.openloadflow.VoltageTargetCheckerParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.lf.util.LfReports;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ReactiveLimitsOuterLoop implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveLimitsOuterLoop.class);

    public static final String NAME = "ReactiveLimits";

    private static final double REALISTIC_VOLTAGE_MARGIN = 1.02;

    public static final int MAX_SWITCH_PQ_PV_DEFAULT_VALUE = 3;

    private final int maxPqPvSwitch;
    private final double maxReactivePowerMismatch;
    private final double minRealisticVoltage;
    private final double maxRealisticVoltage;
    private final boolean robustMode;

    public ReactiveLimitsOuterLoop(int maxPqPvSwitch, double maxReactivePowerMismatch, boolean robustMode, double minRealisticVoltage, double maxRealisticVoltage) {
        this.maxPqPvSwitch = maxPqPvSwitch;
        this.maxReactivePowerMismatch = maxReactivePowerMismatch;
        this.minRealisticVoltage = minRealisticVoltage;
        this.maxRealisticVoltage = maxRealisticVoltage;
        this.robustMode = robustMode;

    }

    /** What the loop keeps from one check to the next, and the arrays its checks work on. */
    private static final class ContextData {

        /** Per bus, the switches from PV to PQ made so far. */
        private final int[] pvPqSwitchCount;

        /** The controller buses of the generator voltage controls. */
        private final ReactiveLimitsVector voltageControllers = new ReactiveLimitsVector();

        /** The controller buses of the generator remote reactive power controls. */
        private final ReactiveLimitsVector reactivePowerControllers = new ReactiveLimitsVector();

        private ContextData(int busCount) {
            pvPqSwitchCount = new int[busCount];
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    private static LfBus.QLimitType toQLimitType(int limitType) {
        return switch (limitType) {
            case ReactiveLimitsDecisions.LIMIT_MIN_Q -> LfBus.QLimitType.MIN_Q;
            case ReactiveLimitsDecisions.LIMIT_MAX_Q -> LfBus.QLimitType.MAX_Q;
            case ReactiveLimitsDecisions.LIMIT_MIN_REALISTIC_V -> LfBus.QLimitType.MIN_REALISTIC_V;
            case ReactiveLimitsDecisions.LIMIT_MAX_REALISTIC_V -> LfBus.QLimitType.MAX_REALISTIC_V;
            default -> throw new IllegalStateException("Not a reactive limit type: " + limitType);
        };
    }

    private static int toLimitType(LfBus.QLimitType qLimitType) {
        return switch (qLimitType) {
            case MIN_Q -> ReactiveLimitsDecisions.LIMIT_MIN_Q;
            case MAX_Q -> ReactiveLimitsDecisions.LIMIT_MAX_Q;
            case MIN_REALISTIC_V -> ReactiveLimitsDecisions.LIMIT_MIN_REALISTIC_V;
            case MAX_REALISTIC_V -> ReactiveLimitsDecisions.LIMIT_MAX_REALISTIC_V;
        };
    }

    /**
     * The inputs of a bus checked as a controlling bus.
     * A controller bus can be a controller bus with voltage control (1) or with remote reactive control (2).
     * (1) A bus PV bus can be switched to PQ in 3 cases:
     *  - if Q equals to Qmax
     *  - if Q equals to Qmin
     *  - if Q equals targetQ, if the robust mode is activated, in the case of remote voltage control
     *                         and the bus exceeds realistic limits
     *                         without exceeding reactive limits when in voltage control.
     *  (2) A remote reactive controller can reach its Q limits: the control is switch off.
     */
    private void gatherControllerBus(ReactiveLimitsVector r, int i, LfBus controllerBus) {
        r.busNum[i] = controllerBus.getNum();
        r.controlEnabled[i] = true;
        r.minQ[i] = controllerBus.getMinQ();
        r.maxQ[i] = controllerBus.getMaxQ();
        r.q[i] = controllerBus.getQ().eval() + controllerBus.getLoadTargetQ();
        r.v[i] = controllerBus.getV();
        r.remoteController[i] = isGeneratorRemoteController(controllerBus);
        // the target Q a realistic-voltage switch freezes
        r.initialTargetQ[i] = robustMode && r.remoteController[i] ? getInitialGenerationTargetQ(controllerBus) : Double.NaN;
        r.qLimitType[i] = ReactiveLimitsDecisions.LIMIT_NONE;
    }

    /**
     * The inputs of a bus checked as a bus switched to PQ.
     * A PQ bus can be switched to PV in 2 cases:
     *  - if Q is equal to Qmin and V is less than targetV: it means that the PQ bus can be unlocked in order to increase the reactive power and reach its targetV.
     *  - if Q is equal to Qmax and V is greater than targetV: it means that the PQ bus can be unlocked in order to decrease the reactive power and reach its targetV.
     * A PQ bus can have its Qmin or Qmax limit updated after a change in targetP of the generator or a change of the voltage magnitude of the bus.
     */
    private void gatherPqBus(ReactiveLimitsVector r, int i, LfBus controllerCapableBus) {
        r.busNum[i] = controllerCapableBus.getNum();
        r.controlEnabled[i] = false;
        // we don't support switching PQ to PV for bus with one controller with slope.
        r.canSwitchToPv[i] = !controllerCapableBus.hasGeneratorsWithSlope();
        r.minQ[i] = controllerCapableBus.getMinQ(); // the actual minQ.
        r.maxQ[i] = controllerCapableBus.getMaxQ(); // the actual maxQ.
        r.frozenTargetQ[i] = controllerCapableBus.getGenerationTargetQ();
        r.qLimitType[i] = controllerCapableBus.getQLimitType().map(ReactiveLimitsOuterLoop::toLimitType).orElse(ReactiveLimitsDecisions.LIMIT_NONE);
        if (r.qLimitType[i] != ReactiveLimitsDecisions.LIMIT_NONE) {
            r.controlledV[i] = getBusV(controllerCapableBus);
            r.targetV[i] = getBusTargetV(controllerCapableBus);
        }
    }

    /**
     * What a decision does to its bus before any bus switches: the voltage of a remote controller leaving PV out of
     * the realistic band is set to a safe 1 p.u. for next computation, and a bus blocked at a reactive limit that
     * moved is frozen at the new limit.
     *
     * @return whether a bus was frozen at a new limit
     */
    private static boolean applyBeforeSwitches(ReactiveLimitsVector r, int i, LfBus bus) {
        if (r.resetV[i]) {
            bus.setV(1);
        }
        if (r.decision[i] != ReactiveLimitsDecisions.DECISION_REFREEZE_AT_LIMIT) {
            return false;
        }
        boolean atMin = ReactiveLimitsDecisions.isMinLimit(r.qLimitType[i]);
        if (atMin) {
            LOGGER.trace("PQ bus {} with updated Q limits, previous minQ {} new minQ {}", bus.getId(), r.frozenTargetQ[i], r.qLimit[i]);
        } else {
            LOGGER.trace("PQ bus {} with updated Q limits, previous maxQ {} new maxQ {}", bus.getId(), r.frozenTargetQ[i], r.qLimit[i]);
        }
        bus.freezeGenerationTargetQAndDisableGeneratorVoltageControl(r.qLimit[i]);
        return true;
    }

    /** The inputs the election of the bus that stays PV reads, for the buses that would switch to PQ. */
    private static void gatherStrongestPvKeys(ReactiveLimitsVector r, LfNetwork network) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < r.count; i++) {
            if (ReactiveLimitsDecisions.isSwitchToPq(r.decision[i])) {
                LfBus controllerBus = network.getBus(r.busNum[i]);
                // the strongest one is the one at the highest nominal level and highest active power target
                r.controlledNominalV[i] = controllerBus.getGeneratorVoltageControl()
                        .map(vc -> vc.getControlledBus().getNominalV())
                        .orElse(controllerBus.getNominalV());
                r.targetP[i] = controllerBus.getTargetP();
                candidates.add(i);
            }
        }
        candidates.sort(Comparator.comparing(i -> network.getBus(r.busNum[i]).getId())); // for stability of the sort
        for (int rank = 0; rank < candidates.size(); rank++) {
            r.idRank[candidates.get(rank)] = rank;
        }
    }

    private boolean switchPvPq(ReactiveLimitsVector r, LfNetwork network, int remainingPvBusCount, int strongestKeptPv,
                               ContextData contextData, ReportNode reportNode) {
        boolean done = false;

        int modifiedRemainingPvBusCount = remainingPvBusCount;
        if (strongestKeptPv >= 0) {
            // keep one bus PV, the strongest one which is one at the highest nominal level and highest active power
            // target
            LfBus strongestBus = network.getBus(r.busNum[strongestKeptPv]);
            modifiedRemainingPvBusCount++;
            LOGGER.warn("All PV buses should switch PQ, strongest one '{}' will stay PV", strongestBus.getId());
            LfReports.reportBusForcedToBePv(reportNode, strongestBus.getId());
        }

        int pvToPqBusCount = ReactiveLimitsDecisions.countSwitchesToPq(r);
        if (pvToPqBusCount > 0) {
            done = true;

            ReportNode summary = LfReports.reportPvToPqBuses(reportNode, pvToPqBusCount, modifiedRemainingPvBusCount);

            boolean log = LOGGER.isTraceEnabled();

            for (int i = 0; i < r.count; i++) {
                if (!ReactiveLimitsDecisions.isSwitchToPq(r.decision[i])) {
                    continue;
                }
                LfBus controllerBus = network.getBus(r.busNum[i]);
                LfBus.QLimitType limitType = toQLimitType(ReactiveLimitsDecisions.limitTypeOf(r.decision[i]));

                // switch PV -> PQ
                controllerBus.freezeGenerationTargetQAndDisableGeneratorVoltageControl(r.qLimit[i]);
                controllerBus.setQLimitType(limitType);
                // increment PV -> PQ switch counter
                contextData.pvPqSwitchCount[r.busNum[i]]++;

                switch (limitType) {
                    case MAX_Q:
                        LfReports.reportPvToPqMaxQ(summary, controllerBus, r.q[i], r.qLimit[i], log, LOGGER);
                        break;
                    case MIN_Q:
                        LfReports.reportPvToPqMinQ(summary, controllerBus, r.q[i], r.qLimit[i], log, LOGGER);
                        break;
                    case MIN_REALISTIC_V:
                        LfReports.reportPvToPqMinRealisticV(summary, controllerBus, r.qLimit[i], minRealisticVoltage, log, LOGGER);
                        break;
                    case MAX_REALISTIC_V:
                        LfReports.reportPvToPqMaxRealisticV(summary, controllerBus, r.qLimit[i], maxRealisticVoltage, log, LOGGER);
                        break;
                }
            }

        }

        LOGGER.info("{} buses switched PV -> PQ ({} bus remains PV)", pvToPqBusCount, modifiedRemainingPvBusCount);

        return done;
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        AcLoadFlowContext loadFlowContext = context.getLoadFlowContext();
        if (loadFlowContext.getParameters().isFixVoltageTargets()) {
            new VoltageTargetChecker(context.getNetwork())
                    .fix(new VoltageTargetCheckerParameters(loadFlowContext.getParameters().getMatrixFactory()));
        }
        context.setData(new ContextData(context.getNetwork().getBuses().size()));
    }

    private static boolean switchPqPv(ReactiveLimitsVector r, LfNetwork network, ReportNode reportNode) {
        int pqToPvBusCount = 0;
        for (int i = 0; i < r.count; i++) {
            int decision = r.decision[i];
            if (decision == ReactiveLimitsDecisions.DECISION_TO_PV || decision == ReactiveLimitsDecisions.DECISION_TO_PV_BLOCKED) {
                pqToPvBusCount++;
            }
        }
        int pqPvSwitchCount = 0;

        boolean log = LOGGER.isTraceEnabled();

        List<ReportNode> pqPvNodes = new ArrayList<>();

        for (int i = 0; i < r.count; i++) {
            int decision = r.decision[i];
            if (decision != ReactiveLimitsDecisions.DECISION_TO_PV && decision != ReactiveLimitsDecisions.DECISION_TO_PV_BLOCKED) {
                continue;
            }
            LfBus controllerBus = network.getBus(r.busNum[i]);

            if (decision == ReactiveLimitsDecisions.DECISION_TO_PV_BLOCKED) {
                pqPvNodes.add(LfReports.createRootReportPvPqSwitchLimit(reportNode, controllerBus, r.pvPqSwitchCount[i], log, LOGGER));
            } else {
                controllerBus.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(true);
                controllerBus.setQLimitType(null);
                pqPvSwitchCount++;

                if (!ReactiveLimitsDecisions.isMinLimit(r.qLimitType[i])) {
                    pqPvNodes.add(LfReports.createRootReportPqToPvBusMaxLimit(
                            reportNode,
                            controllerBus,
                            controllerBus.getGeneratorVoltageControl().map(VoltageControl::getControlledBus).orElseThrow(),
                            getBusTargetV(controllerBus),
                            log,
                            LOGGER));
                } else {
                    pqPvNodes.add(LfReports.createRootReportPqToPvBusMinLimit(
                            reportNode,
                            controllerBus,
                            controllerBus.getGeneratorVoltageControl().map(VoltageControl::getControlledBus).orElseThrow(),
                            getBusTargetV(controllerBus),
                            log,
                            LOGGER));
                }
            }

        }

        if (!pqPvNodes.isEmpty()) {
            ReportNode summary = LfReports.reportPqToPvBuses(reportNode, pqPvSwitchCount, pqToPvBusCount - pqPvSwitchCount);
            pqPvNodes.forEach(summary::include);
        }

        LOGGER.info("{} buses switched PQ -> PV ({} buses blocked PQ because have reach max number of switch)",
                pqPvSwitchCount, pqToPvBusCount - pqPvSwitchCount);

        return pqPvSwitchCount > 0;
    }

    private double getInitialGenerationTargetQ(LfBus controllerBus) {
        return controllerBus.getGenerators().stream().mapToDouble(LfGenerator::getTargetQ).sum();
    }

    private boolean isGeneratorRemoteController(LfBus controllerBus) {
        return controllerBus.getGeneratorVoltageControl().map(c -> c.getControlledBus() != controllerBus).orElse(false);
    }

    private boolean switchReactiveControllerBusPq(ReactiveLimitsVector r, LfNetwork network, ReportNode reportNode) {
        int switchCount = 0;

        List<ReportNode> switchedNodes = new ArrayList<>();

        boolean log = LOGGER.isTraceEnabled();

        for (int i = 0; i < r.count; i++) {
            if (!ReactiveLimitsDecisions.isSwitchToPq(r.decision[i])) {
                continue;
            }
            LfBus controllerBus = network.getBus(r.busNum[i]);

            controllerBus.setGeneratorReactivePowerControlEnabled(false);
            controllerBus.freezeGenerationTargetQAndDisableGeneratorVoltageControl(r.qLimit[i]);
            switchCount++;

            switch (toQLimitType(ReactiveLimitsDecisions.limitTypeOf(r.decision[i]))) {
                case MAX_Q:
                    switchedNodes.add(LfReports.createRootReportReactiveControllerBusesToPqMaxQ(reportNode, controllerBus, r.q[i], r.qLimit[i], log, LOGGER));
                    break;
                case MIN_Q:
                    switchedNodes.add(LfReports.createRootReportReactiveControllerBusesToPqMinQ(reportNode, controllerBus, r.q[i], r.qLimit[i], log, LOGGER));
                    break;
                case MIN_REALISTIC_V, MAX_REALISTIC_V:
                    // Note: never happens for now. Robust mode applies only to remote voltage control generators
                    LOGGER.trace("Switch bus '{}' PV -> PQ, q set to {} = targetQ - v is outside realistic voltage limits [{}pu,{}pu] when remote voltage is maintained",
                            controllerBus.getId(), r.qLimit[i] * PerUnit.SB, minRealisticVoltage, maxRealisticVoltage);

                    break;
            }

        }

        if (!switchedNodes.isEmpty()) {
            ReportNode node = LfReports.reportReactiveControllerBusesToPqBuses(reportNode, switchCount);
            switchedNodes.forEach(node::include);
        }

        LOGGER.info("{} remote reactive power controller buses switched PQ", switchCount);

        return switchCount > 0;
    }

    private static double getBusTargetV(LfBus bus) {
        return bus.getGeneratorVoltageControl().map(GeneratorVoltageControl::getTargetValue).orElse(Double.NaN);
    }

    private static double getBusV(LfBus bus) {
        return bus.getGeneratorVoltageControl().map(vc -> vc.getControlledBus().getV()).orElse(Double.NaN);
    }

    public static List<LfBus> getReactivePowerControllerElements(LfNetwork network) {
        return network.getBuses().stream()
                .filter(LfBus::hasGeneratorReactivePowerControl)
                .flatMap(bus -> bus.getGeneratorReactivePowerControl().orElseThrow().getControllerBuses().stream())
                .filter(Predicate.not(LfBus::isDisabled))
                .toList();
    }

    /**
     * One check: the inputs of each bus are read from the network into the context's arrays, the decisions are taken
     * on the arrays ({@link ReactiveLimitsDecisions}), and they are applied to the network.
     *
     * <p>A bus is read, decided and given what its decision does before any switch (see {@link #applyBeforeSwitches})
     * before the next bus is read, so that a bus reads the voltages the buses before it left. The switches follow,
     * once every bus is decided.
     */
    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        OuterLoopStatus status = OuterLoopStatus.STABLE;

        LfNetwork network = context.getNetwork();
        var contextData = (ContextData) context.getData();
        double minRealisticV = this.minRealisticVoltage * REALISTIC_VOLTAGE_MARGIN;
        double maxRealisticV = this.maxRealisticVoltage / REALISTIC_VOLTAGE_MARGIN;

        ReactiveLimitsVector voltageControllers = contextData.voltageControllers;
        List<LfBus> voltageControllerBuses = network.<LfBus>getControllerElements(VoltageControl.Type.GENERATOR);
        voltageControllers.reset(voltageControllerBuses.size());
        int busesWithUpdatedQLimitsCount = 0;
        for (int i = 0; i < voltageControllers.count; i++) {
            LfBus bus = voltageControllerBuses.get(i);
            if (bus.isGeneratorVoltageControlEnabled()) {
                gatherControllerBus(voltageControllers, i, bus);
            } else {
                gatherPqBus(voltageControllers, i, bus);
            }
            voltageControllers.pvPqSwitchCount[i] = contextData.pvPqSwitchCount[bus.getNum()];
            ReactiveLimitsDecisions.decideEntry(voltageControllers, i, maxReactivePowerMismatch, robustMode, minRealisticV, maxRealisticV);
            if (applyBeforeSwitches(voltageControllers, i, bus)) {
                busesWithUpdatedQLimitsCount++;
            }
        }
        int pvToPqBusCount = ReactiveLimitsDecisions.countSwitchesToPq(voltageControllers);
        int remainingPvBusCount = ReactiveLimitsDecisions.countRemainingPv(voltageControllers);
        int pqToPvBusCount = ReactiveLimitsDecisions.count(voltageControllers, ReactiveLimitsDecisions.DECISION_TO_PV);

        // a bus that has a remote reactive generator power control, if its reactive limits are not respected,
        // will become a classical PQ bus at reactive limits.
        ReactiveLimitsVector reactivePowerControllers = contextData.reactivePowerControllers;
        List<LfBus> reactivePowerControllerBuses = getReactivePowerControllerElements(network).stream()
                .filter(LfBus::isGeneratorReactivePowerControlEnabled)
                .toList();
        reactivePowerControllers.reset(reactivePowerControllerBuses.size());
        for (int i = 0; i < reactivePowerControllers.count; i++) {
            LfBus bus = reactivePowerControllerBuses.get(i);
            gatherControllerBus(reactivePowerControllers, i, bus);
            ReactiveLimitsDecisions.decideEntry(reactivePowerControllers, i, maxReactivePowerMismatch, robustMode, minRealisticV, maxRealisticV);
            applyBeforeSwitches(reactivePowerControllers, i, bus);
        }
        int reactiveControllerBusToPqCount = ReactiveLimitsDecisions.countSwitchesToPq(reactivePowerControllers);

        ReportNode iterationReportNode = reportNode;
        if (pvToPqBusCount > 0 || pqToPvBusCount > 0 || busesWithUpdatedQLimitsCount > 0 || reactiveControllerBusToPqCount > 0) {
            iterationReportNode = LfReports.createOuterLoopIterationReporter(reportNode, context.getOuterLoopTotalIterations() + 1);
        }

        if (pvToPqBusCount > 0) {
            int strongestKeptPv = -1;
            if (remainingPvBusCount == 0) {
                gatherStrongestPvKeys(voltageControllers, network);
                strongestKeptPv = ReactiveLimitsDecisions.keepStrongestPv(voltageControllers);
            }
            if (switchPvPq(voltageControllers, network, remainingPvBusCount, strongestKeptPv, contextData, iterationReportNode)) {
                status = OuterLoopStatus.UNSTABLE;
            }
        }
        if (pqToPvBusCount > 0) {
            ReactiveLimitsDecisions.blockSwitchesToPv(voltageControllers, maxPqPvSwitch);
            if (switchPqPv(voltageControllers, network, iterationReportNode)) {
                status = OuterLoopStatus.UNSTABLE;
            }
        }
        if (busesWithUpdatedQLimitsCount > 0) {
            LOGGER.info("{} buses blocked at a reactive limit have been adjusted because the reactive limit changed", busesWithUpdatedQLimitsCount);
            LfReports.reportBusesWithUpdatedQLimits(iterationReportNode, busesWithUpdatedQLimitsCount);
            status = OuterLoopStatus.UNSTABLE;
        }
        if (reactiveControllerBusToPqCount > 0 && switchReactiveControllerBusPq(reactivePowerControllers, network, iterationReportNode)) {
            status = OuterLoopStatus.UNSTABLE;
        }
        return new OuterLoopResult(this, status);
    }

    @Override
    public boolean canFixUnrealisticState() {
        return true;
    }
}
