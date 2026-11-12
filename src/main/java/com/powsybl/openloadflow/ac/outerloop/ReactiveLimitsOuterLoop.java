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
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ReactiveLimitsOuterLoop implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveLimitsOuterLoop.class);

    /** {@code OLF_RL_TRACE}: print every PV -> PQ / PQ -> PV switch this loop makes, to stderr. The
     *  test logging config attaches no appender to this logger, so the decisions are otherwise
     *  invisible — and they are exactly what a GPU port has to reproduce. */
    private static final boolean RL_TRACE = System.getenv("OLF_RL_TRACE") != null;

    /** Every RL_* line carries the contingency whose solve is running (see
     *  DistributedSlackOuterLoop.CURRENT_CONTINGENCY), and {@code OLF_RL_TRACE_CTG=<id>[,<id>]}
     *  restricts the lines to those contingencies: a security analysis runs this loop for every
     *  contingency, and the switch SEQUENCE of one group under one contingency is what another
     *  implementation has to reproduce. */
    private static final String RL_TRACE_CTG = System.getenv("OLF_RL_TRACE_CTG");

    private static void rlPrintf(String fmt, Object... args) {
        if (!com.powsybl.openloadflow.util.OlfTraceScope.wanted()) {   // OLF_TRACE_CTG filters it too
            return;
        }
        String ctg = DistributedSlackOuterLoop.CURRENT_CONTINGENCY.get();
        if (RL_TRACE_CTG != null && ctg != null
                && java.util.Arrays.stream(RL_TRACE_CTG.split(",")).map(String::trim).noneMatch(ctg::equals)) {
            return;
        }
        System.err.printf("ctg=" + (ctg == null ? "base" : ctg) + " " + fmt, args);
    }

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

    /** The PV->PQ switch budget and the reactive-mismatch band this loop switches outside of.
     *  Exposed so an alternative engine can drive the same loop with the run's own settings. */
    public int getMaxPqPvSwitch() {
        return maxPqPvSwitch;
    }

    public double getMaxReactivePowerMismatch() {
        return maxReactivePowerMismatch;
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

    private static boolean isTraced(LfBus bus) {
        return RL_TRACE_BUS != null && Arrays.stream(RL_TRACE_BUS.split(",")).anyMatch(bus.getId()::startsWith);
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

        // OLF_RL_TRACE_BUS also covers the PV side: after an unpin, whether the bus pins AGAIN is
        // decided here, and the only way to compare that decision with another implementation is to
        // print the q and the limits it was taken at.
        if (isTraced(controllerBus)) {
            rlPrintf("RL_PVCHECK bus=%s q=%.12f minQ=%.12f maxQ=%.12f v=%.12f tol=%.3e pinMin=%b pinMax=%b%n",
                    controllerBus.getId(), r.q[i], r.minQ[i], r.maxQ[i], controllerBus.getV(), maxReactivePowerMismatch,
                    r.q[i] < r.minQ[i] - maxReactivePowerMismatch, r.q[i] > r.maxQ[i] + maxReactivePowerMismatch);
        }
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
            if (isTraced(controllerCapableBus)) {
                // every pinned-bus DECISION for the named buses - the comparison the switch-back turns on (controlled
                // voltage against the group target, and the MARGIN between them) plus the limits and the frozen Q
                rlPrintf("RL_DECIDE bus=%s type=%s q=%.12f minQ=%.12f maxQ=%.12f v=%.12f vt=%.12f margin=%.3e canSwitch=%b%n",
                        controllerCapableBus.getId(), toQLimitType(r.qLimitType[i]), r.frozenTargetQ[i], r.minQ[i], r.maxQ[i],
                        r.controlledV[i], r.targetV[i], r.controlledV[i] - r.targetV[i], r.canSwitchToPv[i]);
            }
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
        if (RL_TRACE) {
            // the pinned bus is RE-FROZEN at a limit that MOVED (a reactive-capability curve
            // follows the generator's targetP, which the distributed slack moves)
            rlPrintf("RL_REFREEZE bus=%s type=%s frozenQ=%.12f newLimit=%.12f drift=%.3e v=%.9f targetV=%.9f%n",
                    bus.getId(), atMin ? "MIN_Q" : "MAX_Q", r.frozenTargetQ[i], r.qLimit[i], r.qLimit[i] - r.frozenTargetQ[i],
                    r.controlledV[i], r.targetV[i]);
        }
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
            Reports.reportBusForcedToBePv(reportNode, strongestBus.getId());
        }

        int pvToPqBusCount = ReactiveLimitsDecisions.countSwitchesToPq(r);
        if (pvToPqBusCount > 0) {
            done = true;

            ReportNode summary = Reports.reportPvToPqBuses(reportNode, pvToPqBusCount, modifiedRemainingPvBusCount);

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
                        Reports.reportPvToPqMaxQ(summary, controllerBus, r.q[i], r.qLimit[i], log, LOGGER);
                        break;
                    case MIN_Q:
                        Reports.reportPvToPqMinQ(summary, controllerBus, r.q[i], r.qLimit[i], log, LOGGER);
                        break;
                    case MIN_REALISTIC_V:
                        Reports.reportPvToPqMinRealisticV(summary, controllerBus, r.qLimit[i], minRealisticVoltage, log, LOGGER);
                        break;
                    case MAX_REALISTIC_V:
                        Reports.reportPvToPqMaxRealisticV(summary, controllerBus, r.qLimit[i], maxRealisticVoltage, log, LOGGER);
                        break;
                }
            }

        }

        if (RL_TRACE) {
            rlPrintf("RL_PASS switchPvPq candidates=%d remainingPvBusCount=%d%n",
                    pvToPqBusCount, remainingPvBusCount);
            for (int i = 0; i < r.count; i++) {
                if (ReactiveLimitsDecisions.isSwitchToPq(r.decision[i])) {
                    rlPrintf("RL_SWITCH PV->PQ bus=%s q=%.9f qLimit=%.9f limitType=%s%n",
                            network.getBus(r.busNum[i]).getId(), r.q[i], r.qLimit[i],
                            toQLimitType(ReactiveLimitsDecisions.limitTypeOf(r.decision[i])));
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
                if (RL_TRACE) {
                    rlPrintf("RL_SWITCH PQ->PV bus=%s limitType=%s%n", network.getBus(r.busNum[i]).getId(),
                            ReactiveLimitsDecisions.isMinLimit(r.qLimitType[i]) ? LfBus.QLimitType.MIN_Q : LfBus.QLimitType.MAX_Q);
                }
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
                pqPvNodes.add(Reports.createRootReportPvPqSwitchLimit(reportNode, controllerBus, r.pvPqSwitchCount[i], log, LOGGER));
            } else {
                controllerBus.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(true);
                controllerBus.setQLimitType(null);
                pqPvSwitchCount++;

                if (!ReactiveLimitsDecisions.isMinLimit(r.qLimitType[i])) {
                    pqPvNodes.add(Reports.createRootReportPqToPvBusMaxLimit(
                            reportNode,
                            controllerBus,
                            controllerBus.getGeneratorVoltageControl().map(VoltageControl::getControlledBus).orElseThrow(),
                            getBusTargetV(controllerBus),
                            log,
                            LOGGER));
                } else {
                    pqPvNodes.add(Reports.createRootReportPqToPvBusMinLimit(
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
            ReportNode summary = Reports.reportPqToPvBuses(reportNode, pqPvSwitchCount, pqToPvBusCount - pqPvSwitchCount);
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

    /** OLF_RL_TRACE_BUS=<prefix>[,<prefix>]: every pinned-bus DECISION for the named buses - the
     *  comparison the switch-back turns on (controlled voltage against the group target, and the
     *  MARGIN between them) plus the limits and the frozen Q. A pin that ends differently on another
     *  implementation is only "the last bits" if that margin is at the noise floor. */
    private static final String RL_TRACE_BUS = System.getenv("OLF_RL_TRACE_BUS");

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
                    switchedNodes.add(Reports.createRootReportReactiveControllerBusesToPqMaxQ(reportNode, controllerBus, r.q[i], r.qLimit[i], log, LOGGER));
                    break;
                case MIN_Q:
                    switchedNodes.add(Reports.createRootReportReactiveControllerBusesToPqMinQ(reportNode, controllerBus, r.q[i], r.qLimit[i], log, LOGGER));
                    break;
                case MIN_REALISTIC_V, MAX_REALISTIC_V:
                    // Note: never happens for now. Robust mode applies only to remote voltage control generators
                    LOGGER.trace("Switch bus '{}' PV -> PQ, q set to {} = targetQ - v is outside realistic voltage limits [{}pu,{}pu] when remote voltage is maintained",
                            controllerBus.getId(), r.qLimit[i] * PerUnit.SB, minRealisticVoltage, maxRealisticVoltage);

                    break;
            }

        }

        if (!switchedNodes.isEmpty()) {
            ReportNode node = Reports.reportReactiveControllerBusesToPqBuses(reportNode, switchCount);
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
            iterationReportNode = Reports.createOuterLoopIterationReporter(reportNode, context.getOuterLoopTotalIterations() + 1);
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
            Reports.reportBusesWithUpdatedQLimits(iterationReportNode, busesWithUpdatedQLimitsCount);
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
