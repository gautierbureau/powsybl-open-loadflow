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
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.*;
import com.powsybl.security.limitreduction.LimitReduction;
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

    private final LimitReductionManager limitReductionManager;

    private SecurityAnalysisParameters.IncreasedViolationsParameters parameters;

    private final Map<Pair<Object, String>, LimitViolation> violations = new LinkedHashMap<>(); // All limit violations indexed by network element and OperationalLimitsGroup (if it exists)

    // branch limit checks laid out as parallel arrays, built once per network and cached on the pre-contingency manager
    private BranchLimitScreen branchLimitScreen;

    private LfNetwork screenedNetwork;

    public LimitViolationManager(LimitViolationManager reference, List<LimitReduction> limitReductions,
                                 SecurityAnalysisParameters.IncreasedViolationsParameters parameters) {
        this.reference = reference;
        if (reference != null) {
            this.parameters = Objects.requireNonNull(parameters);
        }
        this.limitReductionManager = LimitReductionManager.create(limitReductions);
    }

    public LimitViolationManager(List<LimitReduction> limitReductions) {
        this(null, limitReductions, null);
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
        detectViolations(network, isBranchDisabled, getOrBuildScreen(network));
    }

    /**
     * The branch limit checks of the given network, laid out as parallel arrays. Branch limits and their reductions do
     * not change between contingencies, so the screen is built once and cached on the pre-contingency manager, which
     * every post-contingency and post-action manager of the analysis references: a security analysis then builds it
     * once and reuses it for all its contingencies.
     */
    private BranchLimitScreen getOrBuildScreen(LfNetwork network) {
        // the reference manager is the pre-contingency one, shared by all the managers of the analysis
        LimitViolationManager owner = reference != null ? reference : this;
        if (owner.branchLimitScreen == null || owner.screenedNetwork != network) {
            owner.branchLimitScreen = BranchLimitScreen.build(network, limitReductionManager);
            owner.screenedNetwork = network;
        }
        return owner.branchLimitScreen;
    }

    public LimitReductionManager getLimitReductionManager() {
        return limitReductionManager;
    }

    /**
     * Detect violations on branches and on buses, screening the branch limit checks with a {@link BranchLimitScreen}
     * built once for the whole security analysis. Each check is ruled out by a single comparison against the lowest
     * limit of its side and type, and only the checks that exceed it visit their limits groups, which reproduces
     * {@link #detectViolations(LfNetwork, Predicate)} exactly.
     * @param network network on which the violation limits are checked
     * @param isBranchDisabled predicate to evaluate if a branch of the network is disabled or not
     * @param screen the branch limit checks of the network, laid out as parallel arrays
     */
    public void detectViolations(LfNetwork network, Predicate<LfBranch> isBranchDisabled, BranchLimitScreen screen) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(screen);

        LfBranch[] branches = screen.getBranches();
        LfBus[] buses = screen.getBuses();
        double[] thresholds = screen.getThresholds();
        byte[] kinds = screen.getKinds();
        List<LfBranch.LfLimitsGroup>[] groups = screen.getGroups();
        for (int k = 0; k < branches.length; k++) {
            LfBranch branch = branches[k];
            // the disabling status is tested first: the flow of a disabled branch cannot be evaluated, as its
            // variables are not in the state vector
            if (isBranchDisabled.test(branch)) {
                continue;
            }
            // a flow that does not exceed the lowest limit of the check cannot violate any of its groups; an
            // undefined (NaN) flow fails the comparison, as it failed the scan of the limits
            switch (kinds[k]) {
                case BranchLimitScreen.SIDE_1_CURRENT -> {
                    if (branch.getI1().eval() > thresholds[k]) {
                        detectBranchCurrentViolations(branch, buses[k], LfBranch::getI1, groups[k], TwoSides.ONE);
                    }
                }
                case BranchLimitScreen.SIDE_1_ACTIVE_POWER -> {
                    if (Math.abs(branch.getP1().eval()) > thresholds[k]) {
                        detectBranchActivePowerViolations(branch, LfBranch::getP1, groups[k], TwoSides.ONE);
                    }
                }
                case BranchLimitScreen.SIDE_1_APPARENT_POWER -> {
                    if (branch.computeApparentPower1() > thresholds[k]) {
                        detectBranchApparentPowerViolations(branch, LfBranch::computeApparentPower1, groups[k], TwoSides.ONE);
                    }
                }
                case BranchLimitScreen.SIDE_2_CURRENT -> {
                    if (branch.getI2().eval() > thresholds[k]) {
                        detectBranchCurrentViolations(branch, buses[k], LfBranch::getI2, groups[k], TwoSides.TWO);
                    }
                }
                case BranchLimitScreen.SIDE_2_ACTIVE_POWER -> {
                    if (Math.abs(branch.getP2().eval()) > thresholds[k]) {
                        detectBranchActivePowerViolations(branch, LfBranch::getP2, groups[k], TwoSides.TWO);
                    }
                }
                case BranchLimitScreen.SIDE_2_APPARENT_POWER -> {
                    if (branch.computeApparentPower2() > thresholds[k]) {
                        detectBranchApparentPowerViolations(branch, LfBranch::computeApparentPower2, groups[k], TwoSides.TWO);
                    }
                }
                default -> throw new IllegalStateException("Unsupported branch limit check: " + kinds[k]);
            }
        }

        detectBusAndVoltageAngleViolations(network);
    }

    private void detectBranchCurrentViolations(LfBranch branch, LfBus bus, Function<LfBranch, Evaluable> iGetter,
                                               List<LfBranch.LfLimitsGroup> limitsGroups, TwoSides side) {
        for (LfBranch.LfLimitsGroup limitsGroup : limitsGroups) {
            detectBranchCurrentViolations(branch, bus, iGetter, limitsGroup, side);
        }
    }

    private void detectBranchActivePowerViolations(LfBranch branch, Function<LfBranch, Evaluable> pGetter,
                                                   List<LfBranch.LfLimitsGroup> limitsGroups, TwoSides side) {
        for (LfBranch.LfLimitsGroup limitsGroup : limitsGroups) {
            detectBranchActivePowerViolations(branch, pGetter, limitsGroup, side);
        }
    }

    private void detectBranchApparentPowerViolations(LfBranch branch, ToDoubleFunction<LfBranch> sGetter,
                                                     List<LfBranch.LfLimitsGroup> limitsGroups, TwoSides side) {
        for (LfBranch.LfLimitsGroup limitsGroup : limitsGroups) {
            detectBranchApparentPowerViolations(branch, sGetter, limitsGroup, side);
        }
    }

    private void detectBusAndVoltageAngleViolations(LfNetwork network) {
        // Detect violation limits on buses
        network.getBuses().stream().filter(b -> !b.isDisabled()).forEach(this::detectBusViolations);

        // Detect voltage angle limits
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

    private void addBranchLimitViolation(LimitViolation limitViolation) {
        addLimitViolation(limitViolation, Pair.of(getSubjectIdSide(limitViolation), limitViolation.getOperationalLimitsGroupId()));
    }

    private void addBusLimitViolation(LimitViolation limitViolation, LfBus bus) {
        addLimitViolation(limitViolation, Pair.of(bus.getId(), limitViolation.getOperationalLimitsGroupId()));
    }

    private void addVoltageAngleLimitViolation(LimitViolation limitViolation, LfNetwork.LfVoltageAngleLimit voltageAngleLimit) {
        addLimitViolation(limitViolation, Pair.of(voltageAngleLimit.getId(), limitViolation.getOperationalLimitsGroupId()));
    }

    private void detectBranchCurrentViolations(LfBranch branch, LfBus bus, Function<LfBranch, Evaluable> iGetter, LfBranch.LfLimitsGroup limitsGroup, TwoSides side) {
        double i = iGetter.apply(branch).eval();
        if (i <= limitsGroup.getMinReducedValue()) {
            // below the lowest limit of the group: no limit can be violated, skip the scan of the sorted limits
            return;
        }
        List<LfBranch.LfLimit> limits = limitsGroup.getSortedLimits();
        String operationalLimitsGroupId = limitsGroup.getOperationalLimitsGroupId();
        for (LfBranch.LfLimit temporaryLimit : limits) {
            if (i > temporaryLimit.getReducedValue()) {
                addBranchLimitViolation(createLimitViolation(branch, operationalLimitsGroupId, temporaryLimit, LimitViolationType.CURRENT, PerUnit.ib(bus.getNominalV()), i, side));
                break;
            }
        }
    }

    private void detectBranchActivePowerViolations(LfBranch branch, Function<LfBranch, Evaluable> pGetter, LfBranch.LfLimitsGroup limitsGroup, TwoSides side) {
        double p = pGetter.apply(branch).eval();
        if (Math.abs(p) <= limitsGroup.getMinReducedValue()) {
            // below the lowest limit of the group: no limit can be violated, skip the scan of the sorted limits
            return;
        }
        List<LfBranch.LfLimit> limits = limitsGroup.getSortedLimits();
        String operationalLimitsGroupId = limitsGroup.getOperationalLimitsGroupId();
        for (LfBranch.LfLimit temporaryLimit : limits) {
            if (Math.abs(p) > temporaryLimit.getReducedValue()) {
                addBranchLimitViolation(createLimitViolation(branch, operationalLimitsGroupId, temporaryLimit, LimitViolationType.ACTIVE_POWER, PerUnit.SB, p, side));
                break;
            }
        }
    }

    private void detectBranchApparentPowerViolations(LfBranch branch, ToDoubleFunction<LfBranch> sGetter, LfBranch.LfLimitsGroup limitsGroup, TwoSides side) {
        //Apparent power is not relevant for fictitious branches and may be NaN
        double s = sGetter.applyAsDouble(branch);
        if (s <= limitsGroup.getMinReducedValue()) {
            // below the lowest limit of the group: no limit can be violated, skip the scan of the sorted limits
            return;
        }
        List<LfBranch.LfLimit> limits = limitsGroup.getSortedLimits();
        String operationalLimitsGroupId = limitsGroup.getOperationalLimitsGroupId();
        if (!Double.isNaN(s)) {
            for (LfBranch.LfLimit temporaryLimit : limits) {
                if (s > temporaryLimit.getReducedValue()) {
                    addBranchLimitViolation(createLimitViolation(branch, operationalLimitsGroupId, temporaryLimit, LimitViolationType.APPARENT_POWER, PerUnit.SB, s, side));
                    break;
                }
            }
        }
    }

    private static LimitViolation createLimitViolation(LfBranch branch, String operationalLimitsGroupId, LfBranch.LfLimit temporaryLimit,
                                                       LimitViolationType type, double scale, double value,
                                                       TwoSides side) {
        return new LimitViolationBuilder()
                .subject(branch.getMainOriginalId())
                .operationalLimitsGroupId(operationalLimitsGroupId)
                .type(type)
                .limitName(temporaryLimit.getName())
                .duration(temporaryLimit.getAcceptableDuration())
                .limit(temporaryLimit.getValue() * scale)
                .reduction(temporaryLimit.getReduction())
                .value(value * scale)
                .side(branch.getOriginalSide().orElse(side.toThreeSides()))
                .build();
    }

    /**
     * Detect violation limits on one branch and add them to the given list
     * @param bus branch of interest
     */
    private void detectBusViolations(LfBus bus) {
        // detect violation limits on a bus
        double scale = bus.getNominalV();
        double busV = bus.getV();
        if (!Double.isNaN(bus.getHighVoltageLimit()) && busV > bus.getHighVoltageLimit()) {
            LimitViolation limitViolationHigh = new LimitViolationBuilder()
                    .subject(bus.getVoltageLevelId())
                    .type(LimitViolationType.HIGH_VOLTAGE)
                    .limit(bus.getHighVoltageLimit() * scale)
                    .value(busV * scale)
                    .violationLocation(bus.getViolationLocation())
                    .build();
            addBusLimitViolation(limitViolationHigh, bus);
        }
        if (!Double.isNaN(bus.getLowVoltageLimit()) && busV < bus.getLowVoltageLimit()) {
            LimitViolation limitViolationLow = new LimitViolationBuilder()
                    .subject(bus.getVoltageLevelId())
                    .type(LimitViolationType.LOW_VOLTAGE)
                    .limit(bus.getLowVoltageLimit() * scale)
                    .value(busV * scale)
                    .violationLocation(bus.getViolationLocation())
                    .build();
            addBusLimitViolation(limitViolationLow, bus);
        }
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
        if (violation2 != null && violation1.getLimitType() == violation2.getLimitType()) {
            if (violation2.getLimit() < violation1.getLimit()) {
                // the limit violated is smaller hence the violation is weaker, for flow violations only.
                // for voltage limits, we have only one limit by limit type.
                return true;
            }
            if (violation2.getLimit() == violation1.getLimit()) {
                // the limit violated is the same: we consider the violations equivalent if the new value is close to previous one.
                if (isFlowViolation(violation2)) {
                    return Math.abs(violation2.getValue()) <= Math.abs(violation1.getValue()) * (1 + violationsParameters.getFlowProportionalThreshold());
                } else if (violation2.getLimitType() == LimitViolationType.HIGH_VOLTAGE) {
                    double value = Math.min(violationsParameters.getHighVoltageAbsoluteThreshold(), violation1.getValue() * violationsParameters.getHighVoltageProportionalThreshold());
                    return violation2.getValue() <= violation1.getValue() + value;
                } else if (violation2.getLimitType() == LimitViolationType.LOW_VOLTAGE) {
                    return violation2.getValue() >= violation1.getValue() - Math.min(violationsParameters.getLowVoltageAbsoluteThreshold(),
                        violation1.getValue() * violationsParameters.getLowVoltageProportionalThreshold());
                } else {
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean isFlowViolation(LimitViolation limit) {
        return limit.getLimitType() == LimitViolationType.CURRENT || limit.getLimitType() == LimitViolationType.ACTIVE_POWER || limit.getLimitType() == LimitViolationType.APPARENT_POWER;
    }
}
