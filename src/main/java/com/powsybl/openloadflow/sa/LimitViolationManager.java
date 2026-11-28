/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.contingency.violations.LimitViolation;
import com.powsybl.contingency.violations.LimitViolationBuilder;
import com.powsybl.contingency.violations.LimitViolationType;
import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfElement;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LimitScalingManager;
import com.powsybl.openloadflow.util.ArrayEvaluable;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.EvaluableConstants;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.*;
import com.powsybl.security.limitscaling.LimitScaling;
import net.jafama.FastMath;
import org.apache.commons.lang3.function.TriFunction;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Limit violation manager. A reference limit violation manager could be specified to only report violations that
 * are more severe than reference one.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class LimitViolationManager {

    private final LimitViolationManager reference;

    private final LimitScalingManager limitScalingManager;

    private SecurityAnalysisParameters.IncreasedViolationsParameters parameters;

    private final Map<Pair<Object, String>, LimitViolation> violations = new LinkedHashMap<>(); // All limit violations indexed by network element and OperationalLimitsGroup (if it exists)

    /**
     * As a reference: the voltage violation of each bus, by bus number, or null; read from {@link #violations} once it
     * is complete, at the first post-contingency detection, instead of a key built and looked up for each bus.
     */
    private LimitViolation[] busViolationsByNum;

    public LimitViolationManager(LimitViolationManager reference, List<LimitScaling> limitScalings,
                                 SecurityAnalysisParameters.IncreasedViolationsParameters parameters) {
        this.reference = reference;
        if (reference != null) {
            this.parameters = Objects.requireNonNull(parameters);
        }
        this.limitScalingManager = LimitScalingManager.create(limitScalings);
    }

    public LimitViolationManager(List<LimitScaling> limitScalings) {
        this(null, limitScalings, null);
    }

    public List<LimitViolation> getLimitViolations() {
        return new ArrayList<>(violations.values());
    }

    /**
     * Detect violations on branches and on buses
     * @param network network on which the violation limits are checked
     */
    public void detectViolations(LfNetwork network) {
        detectViolations(network, LfElement::isDisabled);
    }

    /**
     * Detect violations on branches and on buses
     * @param network network on which the violation limits are checked
     * @param isBranchDisabled predicate to evaluate if a branch of the network is disabled or not
     */
    public void detectViolations(LfNetwork network, Predicate<LfBranch> isBranchDisabled) {
        Objects.requireNonNull(network);

        // Detect violation limits on branches: fast pre-filter on flat arrays (no access to branch objects), detailed
        // detection only for branch sides above their smallest limit
        getMonitoredBranchSides(network);
        FlatMonitoredBranchSides flatSides = getFlatMonitoredBranchSides(network);
        MonitoredBranchSide[] sides = flatSides.sides;
        double[] busVoltages = network.getBusVoltages();
        double[] busNominalVoltages = network.getBusNominalVoltages();
        for (int k = 0; k < sides.length; k++) {
            if (flatSides.mayBeViolated(k, busVoltages, busNominalVoltages)) {
                MonitoredBranchSide monitoredSide = sides[k];
                if (!isBranchDisabled.test(monitoredSide.branch)) {
                    detectBranchSideViolations(monitoredSide);
                }
            }
        }

        // Detect violation limits on buses: same pre-filter with voltages and limits read from flat arrays
        FlatBusLimits busLimits = flatSides.busLimits;
        for (int num = 0; num < busLimits.lowVoltageLimit.length; num++) {
            // as LfBus.getV computes it, from the arrays the network keeps the voltages in
            double v = busVoltages[num] / busNominalVoltages[num];
            if (v > busLimits.highVoltageLimit[num] || v < busLimits.lowVoltageLimit[num]) {
                LfBus bus = network.getBus(num);
                if (!bus.isDisabled()) {
                    detectBusViolations(bus);
                }
            }
        }

        detectVoltageAngleViolations(network);
    }

    /**
     * Detect the voltage angle limit violations: a detector of its own for the branches and the buses can leave these to
     * this manager.
     * @param network network on which the voltage angle limits are checked
     */
    public void detectVoltageAngleViolations(LfNetwork network) {
        network.getVoltageAngleLimits().stream()
                .filter(limit -> !limit.getFrom().isDisabled() && !limit.getTo().isDisabled())
                .forEach(this::detectVoltageAngleLimitViolations);
    }

    private static Pair<String, ThreeSides> getSubjectIdSide(LimitViolation limitViolation) {
        return Pair.of(limitViolation.getSubjectId(), limitViolation.getSide());
    }

    private void addLimitViolation(LimitViolation limitViolation, Pair<Object, String> key) {
        if (reference != null) {
            var referenceLimitViolation = reference.violations.get(key);
            if (referenceLimitViolation == null || !violationWeakenedOrEquivalent(referenceLimitViolation, limitViolation, parameters)) {
                violations.put(key, limitViolation);
            }
        } else {
            violations.put(key, limitViolation);
        }
    }

    /**
     * Add a branch violation detected elsewhere, under the same key and with the same filtering against the reference
     * manager as the violations {@link #detectViolations} finds: a detector of its own reports through this manager.
     */
    public void addBranchLimitViolation(LimitViolation limitViolation) {
        addLimitViolation(limitViolation, Pair.of(getSubjectIdSide(limitViolation), limitViolation.getOperationalLimitsGroupId()));
    }

    /**
     * Add a bus violation detected elsewhere. See {@link #addBranchLimitViolation}.
     */
    public void addBusLimitViolation(LimitViolation limitViolation, LfBus bus) {
        addLimitViolation(limitViolation, Pair.of(bus.getId(), limitViolation.getOperationalLimitsGroupId()));
    }

    /** Feed in a voltage-angle violation detected elsewhere. See {@link #addBranchLimitViolation}. */
    public void addVoltageAngleLimitViolation(LimitViolation limitViolation, LfNetwork.LfVoltageAngleLimit voltageAngleLimit) {
        addLimitViolation(limitViolation, Pair.of(voltageAngleLimit.getId(), limitViolation.getOperationalLimitsGroupId()));
    }

    private void detectBranchCurrentViolations(LfBranch branch, LfBus bus, Function<LfBranch, Evaluable> iGetter, LfBranch.LfLimitsGroup limitsGroup, TwoSides side) {
        List<LfBranch.LfLimit> limits = limitsGroup.getSortedLimits();
        String operationalLimitsGroupId = limitsGroup.getOperationalLimitsGroupId();
        double i = iGetter.apply(branch).eval();
        for (LfBranch.LfLimit limit : limits) {
            if (i > limit.getScaledValue()) {
                addBranchLimitViolation(createLimitViolation(branch, operationalLimitsGroupId, limit, LimitViolationType.CURRENT, PerUnit.ib(bus.getNominalV()), i, side));
                break;
            }
        }
    }

    private void detectBranchActivePowerViolations(LfBranch branch, Function<LfBranch, Evaluable> pGetter, LfBranch.LfLimitsGroup limitsGroup, TwoSides side) {
        List<LfBranch.LfLimit> limits = limitsGroup.getSortedLimits();
        String operationalLimitsGroupId = limitsGroup.getOperationalLimitsGroupId();
        double p = pGetter.apply(branch).eval();
        for (LfBranch.LfLimit limit : limits) {
            if (Math.abs(p) > limit.getScaledValue()) {
                addBranchLimitViolation(createLimitViolation(branch, operationalLimitsGroupId, limit, LimitViolationType.ACTIVE_POWER, PerUnit.SB, p, side));
                break;
            }
        }
    }

    private void detectBranchApparentPowerViolations(LfBranch branch, ToDoubleFunction<LfBranch> sGetter, LfBranch.LfLimitsGroup limitsGroup, TwoSides side) {
        List<LfBranch.LfLimit> limits = limitsGroup.getSortedLimits();
        String operationalLimitsGroupId = limitsGroup.getOperationalLimitsGroupId();
        //Apparent power is not relevant for fictitious branches and may be NaN
        double s = sGetter.applyAsDouble(branch);
        if (!Double.isNaN(s)) {
            for (LfBranch.LfLimit limit : limits) {
                if (s > limit.getScaledValue()) {
                    addBranchLimitViolation(createLimitViolation(branch, operationalLimitsGroupId, limit, LimitViolationType.APPARENT_POWER, PerUnit.SB, s, side));
                    break;
                }
            }
        }
    }

    /**
     * A branch side with at least one limit, with its limit groups and the smallest scaled limit value of each
     * limit type: a violation can only exist if the value is above it, so that the detailed detection is only run in
     * that case.
     */
    private record MonitoredBranchSide(LfBranch branch, LfBus bus, TwoSides side,
                                       List<LfBranch.LfLimitsGroup> currentLimitsGroups, double minCurrentLimit,
                                       List<LfBranch.LfLimitsGroup> activePowerLimitsGroups, double minActivePowerLimit,
                                       List<LfBranch.LfLimitsGroup> apparentPowerLimitsGroups, double minApparentPowerLimit) {
    }

    private static final class MonitoredBranchSides {
        private final LfNetwork network;
        private final List<MonitoredBranchSide> sides;
        private FlatMonitoredBranchSides flatSides;

        private MonitoredBranchSides(LfNetwork network, List<MonitoredBranchSide> sides) {
            this.network = network;
            this.sides = sides;
        }

        LfNetwork network() {
            return network;
        }

        List<MonitoredBranchSide> sides() {
            return sides;
        }
    }

    /**
     * Voltage limits (per unit) of buses indexed by bus number (NaN if no limit).
     */
    private record FlatBusLimits(double[] lowVoltageLimit, double[] highVoltageLimit) {

        static FlatBusLimits create(LfNetwork network) {
            int busCount = network.getBuses().size();
            double[] low = new double[busCount];
            double[] high = new double[busCount];
            for (int num = 0; num < busCount; num++) {
                LfBus bus = network.getBus(num);
                low[num] = bus.getLowVoltageLimit();
                high[num] = bus.getHighVoltageLimit();
            }
            return new FlatBusLimits(low, high);
        }
    }

    /**
     * Flat view of monitored branch sides for a fast pre-filter: smallest limit of each type and direct access to
     * active and reactive power values when they are stored in arrays ({@link ArrayEvaluable}). A side may be violated
     * only if a value is above the corresponding smallest limit. Sides whose powers are not stored in arrays, or with
     * current limits and a current that is not the one of the closed branch, always go through the detailed detection.
     *
     * <p>The current of a closed branch side is estimated as |S| / V, which is its magnitude in per unit; it may differ
     * from the one of the current term in the last bits, so that a side is only skipped when the estimate is below its
     * smallest current limit by a margin far larger than that: the detailed detection, with the current term, decides
     * for the others.
     */
    private static final class FlatMonitoredBranchSides {

        /** Relative margin of the current estimate, far above the rounding differences with the current term. */
        private static final double CURRENT_ESTIMATE_MARGIN = 1e-6;

        private final int branchEvaluableVersion;
        private final MonitoredBranchSide[] sides;
        private final boolean[] alwaysDetailed;
        private final double[] minActivePowerLimit;
        private final double[] minApparentPowerLimit;
        private final double[] minCurrentLimit;
        private final int[] busNum;
        private final double[][] pValues;
        private final int[] pIndex;
        private final double[][] qValues;
        private final int[] qIndex;
        private final FlatBusLimits busLimits;

        private FlatMonitoredBranchSides(LfNetwork network, List<MonitoredBranchSide> sideList) {
            branchEvaluableVersion = network.getBranchEvaluableVersion();
            sides = sideList.toArray(new MonitoredBranchSide[0]);
            int size = sides.length;
            alwaysDetailed = new boolean[size];
            minActivePowerLimit = new double[size];
            minApparentPowerLimit = new double[size];
            minCurrentLimit = new double[size];
            busNum = new int[size];
            pValues = new double[size][];
            pIndex = new int[size];
            qValues = new double[size][];
            qIndex = new int[size];
            for (int k = 0; k < size; k++) {
                MonitoredBranchSide m = sides[k];
                boolean side1 = m.side() == TwoSides.ONE;
                minActivePowerLimit[k] = m.activePowerLimitsGroups().isEmpty() ? Double.POSITIVE_INFINITY : m.minActivePowerLimit();
                minApparentPowerLimit[k] = m.apparentPowerLimitsGroups().isEmpty() ? Double.POSITIVE_INFINITY : m.minApparentPowerLimit();
                minCurrentLimit[k] = m.currentLimitsGroups().isEmpty() ? Double.POSITIVE_INFINITY : m.minCurrentLimit();
                busNum[k] = m.bus().getNum();
                Evaluable p = side1 ? m.branch().getP1() : m.branch().getP2();
                Evaluable q = side1 ? m.branch().getQ1() : m.branch().getQ2();
                Evaluable i = side1 ? m.branch().getI1() : m.branch().getI2();
                Evaluable closedI = side1 ? m.branch().getClosedI1() : m.branch().getClosedI2();
                // the current term of the closed branch (not in DC, where a branch has none)
                boolean currentFromPowers = m.currentLimitsGroups().isEmpty() || i == closedI && i != EvaluableConstants.NAN;
                if (!currentFromPowers || !(p instanceof ArrayEvaluable) || !(q instanceof ArrayEvaluable)) {
                    alwaysDetailed[k] = true;
                } else {
                    pValues[k] = ((ArrayEvaluable) p).getValues();
                    pIndex[k] = ((ArrayEvaluable) p).getValueIndex();
                    qValues[k] = ((ArrayEvaluable) q).getValues();
                    qIndex[k] = ((ArrayEvaluable) q).getValueIndex();
                }
            }
            busLimits = FlatBusLimits.create(network);
        }

        boolean mayBeViolated(int k, double[] busVoltages, double[] busNominalVoltages) {
            if (alwaysDetailed[k]) {
                return true;
            }
            double p = pValues[k][pIndex[k]];
            if (Math.abs(p) > minActivePowerLimit[k]) {
                return true;
            }
            double q = qValues[k][qIndex[k]];
            // same computation as LfBranch.computeApparentPower1 and computeApparentPower2
            double s = FastMath.sqrt(p * p + q * q);
            if (s > minApparentPowerLimit[k]) {
                return true;
            }
            double minCurrent = minCurrentLimit[k];
            if (minCurrent == Double.POSITIVE_INFINITY) {
                return false;
            }
            double v = busVoltages[busNum[k]] / busNominalVoltages[busNum[k]];
            if (!(v > 0)) {
                return true;
            }
            return s / v > minCurrent * (1 - CURRENT_ESTIMATE_MARGIN);
        }
    }

    private FlatMonitoredBranchSides getFlatMonitoredBranchSides(LfNetwork network) {
        LimitViolationManager owner = reference != null ? reference : this;
        MonitoredBranchSides cache = owner.monitoredBranchSides;
        if (cache.flatSides == null || cache.flatSides.branchEvaluableVersion != network.getBranchEvaluableVersion()) {
            cache.flatSides = new FlatMonitoredBranchSides(network, cache.sides());
        }
        return cache.flatSides;
    }

    // cache of the monitored branch sides, shared with managers using this one as reference
    private MonitoredBranchSides monitoredBranchSides;

    private static double minScaledValue(List<LfBranch.LfLimitsGroup> limitsGroups) {
        double min = Double.POSITIVE_INFINITY;
        for (LfBranch.LfLimitsGroup limitsGroup : limitsGroups) {
            for (LfBranch.LfLimit limit : limitsGroup.getSortedLimits()) {
                min = Math.min(min, limit.getScaledValue());
            }
        }
        return min;
    }

    private void addMonitoredBranchSide(List<MonitoredBranchSide> sides, LfBranch branch, LfBus bus, TwoSides side,
                                        TriFunction<LfBranch, LimitType, LimitScalingManager, List<LfBranch.LfLimitsGroup>> limitsGetter) {
        List<LfBranch.LfLimitsGroup> current = limitsGetter.apply(branch, LimitType.CURRENT, limitScalingManager);
        List<LfBranch.LfLimitsGroup> activePower = limitsGetter.apply(branch, LimitType.ACTIVE_POWER, limitScalingManager);
        List<LfBranch.LfLimitsGroup> apparentPower = limitsGetter.apply(branch, LimitType.APPARENT_POWER, limitScalingManager);
        if (!current.isEmpty() || !activePower.isEmpty() || !apparentPower.isEmpty()) {
            sides.add(new MonitoredBranchSide(branch, bus, side, current, minScaledValue(current),
                    activePower, minScaledValue(activePower), apparentPower, minScaledValue(apparentPower)));
        }
    }

    private List<MonitoredBranchSide> getMonitoredBranchSides(LfNetwork network) {
        LimitViolationManager owner = reference != null ? reference : this;
        MonitoredBranchSides cache = owner.monitoredBranchSides;
        if (cache == null || cache.network() != network) {
            List<MonitoredBranchSide> sides = new ArrayList<>();
            for (LfBranch branch : network.getBranches()) {
                if (branch.getBus1() != null) {
                    addMonitoredBranchSide(sides, branch, branch.getBus1(), TwoSides.ONE, LfBranch::getLimits1);
                }
                if (branch.getBus2() != null) {
                    addMonitoredBranchSide(sides, branch, branch.getBus2(), TwoSides.TWO, LfBranch::getLimits2);
                }
            }
            cache = new MonitoredBranchSides(network, sides);
            owner.monitoredBranchSides = cache;
        }
        return cache.sides();
    }

    private void detectBranchSideViolations(MonitoredBranchSide m) {
        LfBranch branch = m.branch();
        boolean side1 = m.side() == TwoSides.ONE;
        if (!m.currentLimitsGroups().isEmpty()) {
            double i = side1 ? branch.getI1().eval() : branch.getI2().eval();
            if (i > m.minCurrentLimit()) {
                for (LfBranch.LfLimitsGroup limitsGroup : m.currentLimitsGroups()) {
                    detectBranchCurrentViolations(branch, m.bus(), side1 ? LfBranch::getI1 : LfBranch::getI2, limitsGroup, m.side());
                }
            }
        }
        if (!m.activePowerLimitsGroups().isEmpty()) {
            double p = side1 ? branch.getP1().eval() : branch.getP2().eval();
            if (Math.abs(p) > m.minActivePowerLimit()) {
                for (LfBranch.LfLimitsGroup limitsGroup : m.activePowerLimitsGroups()) {
                    detectBranchActivePowerViolations(branch, side1 ? LfBranch::getP1 : LfBranch::getP2, limitsGroup, m.side());
                }
            }
        }
        if (!m.apparentPowerLimitsGroups().isEmpty()) {
            double s = side1 ? branch.computeApparentPower1() : branch.computeApparentPower2();
            if (s > m.minApparentPowerLimit()) {
                for (LfBranch.LfLimitsGroup limitsGroup : m.apparentPowerLimitsGroups()) {
                    detectBranchApparentPowerViolations(branch, side1 ? LfBranch::computeApparentPower1 : LfBranch::computeApparentPower2, limitsGroup, m.side());
                }
            }
        }
    }

    /**
     * The violation of an exceeded branch limit. Every field but the value comes from the branch and the limit, so that a
     * detector of its own needs only the limit and the value to report it.
     */
    public static LimitViolation createLimitViolation(LfBranch branch, String operationalLimitsGroupId, LfBranch.LfLimit limit,
                                                      LimitViolationType type, double scale, double value,
                                                      TwoSides side) {
        return new LimitViolationBuilder()
                .subject(branch.getMainOriginalId())
                .operationalLimitsGroupId(operationalLimitsGroupId)
                .type(type)
                .limitName(limit.getName())
                .duration(limit.getAcceptableDuration())
                .limit(limit.getValue() * scale)
                .scaling(limit.getScaling())
                .value(value * scale)
                .side(branch.getOriginalSide().orElse(side.toThreeSides()))
                .build();
    }

    /**
     * Detect violation limits on one branch and add them to the given list
     * @param bus branch of interest
     */
    /**
     * True if a bus violation would be discarded because the reference (pre-contingency) violation of this bus is
     * equivalent or stronger: the violation does not need to be built.
     */
    private boolean isBusViolationDiscarded(LfBus bus, LimitViolationType type, double limit, double value) {
        if (reference == null) {
            return false;
        }
        LimitViolation referenceLimitViolation = reference.getBusViolation(bus);
        return referenceLimitViolation != null
                && violationWeakenedOrEquivalent(referenceLimitViolation, type, limit, value, parameters);
    }

    /** The voltage violation of a bus, as {@code violations.get(Pair.of(bus.getId(), null))} gives it. */
    private LimitViolation getBusViolation(LfBus bus) {
        LimitViolation[] byNum = busViolationsByNum;
        if (byNum == null) {
            List<LfBus> buses = bus.getNetwork().getBuses();
            byNum = new LimitViolation[buses.size()];
            if (!violations.isEmpty()) {
                for (LfBus b : buses) {
                    byNum[b.getNum()] = violations.get(Pair.of(b.getId(), null));
                }
            }
            busViolationsByNum = byNum;
        }
        return byNum[bus.getNum()];
    }

    private void detectBusViolations(LfBus bus) {
        // detect violation limits on a bus
        double scale = bus.getNominalV();
        double busV = bus.getV();
        if (!Double.isNaN(bus.getHighVoltageLimit()) && busV > bus.getHighVoltageLimit()
                && !isBusViolationDiscarded(bus, LimitViolationType.HIGH_VOLTAGE, bus.getHighVoltageLimit() * scale, busV * scale)) {
            addBusLimitViolation(createBusLimitViolation(bus, LimitViolationType.HIGH_VOLTAGE, bus.getHighVoltageLimit() * scale, busV * scale), bus);
        }
        if (!Double.isNaN(bus.getLowVoltageLimit()) && busV < bus.getLowVoltageLimit()
                && !isBusViolationDiscarded(bus, LimitViolationType.LOW_VOLTAGE, bus.getLowVoltageLimit() * scale, busV * scale)) {
            addBusLimitViolation(createBusLimitViolation(bus, LimitViolationType.LOW_VOLTAGE, bus.getLowVoltageLimit() * scale, busV * scale), bus);
        }
    }

    /**
     * The violation of an exceeded bus voltage limit, the limit and the value already scaled to kV: the bus counterpart
     * of {@link #createLimitViolation}.
     */
    public static LimitViolation createBusLimitViolation(LfBus bus, LimitViolationType type, double limit, double value) {
        return new LimitViolationBuilder()
                .subject(bus.getVoltageLevelId())
                .type(type)
                .limit(limit)
                .value(value)
                .violationLocation(bus.getViolationLocation())
                .build();
    }

    /**
     * Detect violation limits on one voltage angle limit and add them to the given list
     * @param limit voltage angle limit of interest
     */
    private void detectVoltageAngleLimitViolations(LfNetwork.LfVoltageAngleLimit limit) {
        double difference = limit.getTo().getAngle() - limit.getFrom().getAngle();
        if (!Double.isNaN(limit.getHighValue()) && difference > limit.getHighValue()) {
            LimitViolation limitViolationHigh = new LimitViolationBuilder()
                    .subject(limit.getId())
                    .type(LimitViolationType.HIGH_VOLTAGE_ANGLE)
                    .limit(Math.toDegrees(limit.getHighValue()))
                    .value(Math.toDegrees(difference))
                    .build();
            addVoltageAngleLimitViolation(limitViolationHigh, limit);
        }
        if (!Double.isNaN(limit.getLowValue()) && difference < limit.getLowValue()) {
            LimitViolation limitViolationLow = new LimitViolationBuilder()
                    .subject(limit.getId())
                    .type(LimitViolationType.LOW_VOLTAGE_ANGLE)
                    .limit(Math.toDegrees(limit.getLowValue()))
                    .value(Math.toDegrees(difference))
                    .build();
            addVoltageAngleLimitViolation(limitViolationLow, limit);
        }
    }

    /**
     * Compares two limit violations
     * @param violation1 first limit violation
     * @param violation2 second limit violation
     * @return true if violation2 is weaker than or equivalent to violation1, otherwise false
     */
    public static boolean violationWeakenedOrEquivalent(LimitViolation violation1, LimitViolation violation2,
                                                        SecurityAnalysisParameters.IncreasedViolationsParameters violationsParameters) {
        return violation2 != null
                && violationWeakenedOrEquivalent(violation1, violation2.getLimitType(), violation2.getLimit(), violation2.getValue(), violationsParameters);
    }

    /**
     * Same as {@link #violationWeakenedOrEquivalent(LimitViolation, LimitViolation, SecurityAnalysisParameters.IncreasedViolationsParameters)}
     * with the type, limit and value of the second violation, so that it does not need to be built.
     */
    private static boolean violationWeakenedOrEquivalent(LimitViolation violation1, LimitViolationType type2, double limit2, double value2,
                                                         SecurityAnalysisParameters.IncreasedViolationsParameters violationsParameters) {
        if (violation1.getLimitType() == type2) {
            if (limit2 < violation1.getLimit()) {
                // the limit violated is smaller hence the violation is weaker, for flow violations only.
                // for voltage limits, we have only one limit by limit type.
                return true;
            }
            if (limit2 == violation1.getLimit()) {
                // the limit violated is the same: we consider the violations equivalent if the new value is close to previous one.
                if (isFlowViolation(type2)) {
                    return Math.abs(value2) <= Math.abs(violation1.getValue()) * (1 + violationsParameters.getFlowProportionalThreshold());
                } else if (type2 == LimitViolationType.HIGH_VOLTAGE) {
                    double value = Math.min(violationsParameters.getHighVoltageAbsoluteThreshold(), violation1.getValue() * violationsParameters.getHighVoltageProportionalThreshold());
                    return value2 <= violation1.getValue() + value;
                } else if (type2 == LimitViolationType.LOW_VOLTAGE) {
                    return value2 >= violation1.getValue() - Math.min(violationsParameters.getLowVoltageAbsoluteThreshold(),
                        violation1.getValue() * violationsParameters.getLowVoltageProportionalThreshold());
                } else {
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean isFlowViolation(LimitViolationType type) {
        return type == LimitViolationType.CURRENT || type == LimitViolationType.ACTIVE_POWER || type == LimitViolationType.APPARENT_POWER;
    }
}
