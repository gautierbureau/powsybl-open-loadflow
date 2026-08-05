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
import com.powsybl.openloadflow.network.LfBranchFlowArrays;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfElement;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.*;
import com.powsybl.security.limitreduction.LimitReduction;
import net.jafama.FastMath;
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

    /**
     * When true (the default), the branch limit screen reads the flows from the branch-num indexed arrays of the
     * equation system when it publishes them, instead of walking the branch evaluables. Package-private and mutable so
     * that a benchmark can measure the two screening paths against each other; both report the same violations.
     */
    static boolean bulkFlowScreen = true;

    /** A checked branch the bulk screen must ignore, because the walk ignores it too: it is disabled. */
    private static final byte SCREEN_SKIP = 0;

    /** A checked branch whose flows the arrays hold: enabled, described by them, and connected on both sides. */
    private static final byte SCREEN_BULK = 1;

    /** A checked branch the arrays do not describe (open on a side, or modelled outside the vectorised terms). */
    private static final byte SCREEN_EVALUABLE = 2;

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

        // first pass: screen every check, one tight loop per kind over contiguous thresholds, with the flow accessor
        // fixed for the whole loop. The exceeded checks are packed as (rank, check index) so that the second pass can
        // report them in the original order.
        // The disabling status is tested first: the flow of a disabled branch cannot be evaluated, as its variables
        // are not in the state vector. A flow that does not exceed the lowest limit of the check cannot violate any of
        // its groups; an undefined (NaN) flow fails the comparison, as it failed the scan of the limits.
        ExceededChecks exceeded = new ExceededChecks();
        LfBranchFlowArrays flows = bulkFlowScreen ? network.getBranchFlowArrays() : null;
        if (flows != null) {
            screenFromFlowArrays(screen, isBranchDisabled, flows, exceeded);
        } else {
            screenFromEvaluables(screen, isBranchDisabled, exceeded);
        }

        // second pass: report the exceeded checks in the order the original detection visited them, so that the
        // violations keep their insertion order. Exceeded checks are rare, so this sorts a handful of elements
        int exceededCount = exceeded.size();
        if (exceededCount > 0) {
            long[] sorted = exceeded.sorted();
            LfBranch[] branches = screen.getBranches();
            LfBus[] buses = screen.getBuses();
            int[] ranks = screen.getRanks();
            List<LfBranch.LfLimitsGroup>[] groups = screen.getGroups();
            for (int i = 0; i < exceededCount; i++) {
                int k = (int) sorted[i];
                reportCheckViolations(branches[k], buses[k], groups[k], ranks[k] % BranchLimitScreen.KIND_COUNT);
            }
        }

        detectBusAndVoltageAngleViolations(network);
    }

    /**
     * Screen the checks reading the flows one branch evaluable at a time. Used when the equation system does not
     * publish its flows as arrays - the DC security analysis, and the AC one when the equation system is not the
     * vectorised one.
     */
    private static void screenFromEvaluables(BranchLimitScreen screen, Predicate<LfBranch> isBranchDisabled, ExceededChecks exceeded) {
        LfBranch[] branches = screen.getBranches();
        double[] thresholds = screen.getThresholds();
        int[] ranks = screen.getRanks();
        int[] kindStart = screen.getKindStart();
        for (int k = kindStart[BranchLimitScreen.SIDE_1_CURRENT]; k < kindStart[BranchLimitScreen.SIDE_1_CURRENT + 1]; k++) {
            LfBranch branch = branches[k];
            if (!isBranchDisabled.test(branch) && branch.getI1().eval() > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
        for (int k = kindStart[BranchLimitScreen.SIDE_1_ACTIVE_POWER]; k < kindStart[BranchLimitScreen.SIDE_1_ACTIVE_POWER + 1]; k++) {
            LfBranch branch = branches[k];
            if (!isBranchDisabled.test(branch) && Math.abs(branch.getP1().eval()) > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
        for (int k = kindStart[BranchLimitScreen.SIDE_1_APPARENT_POWER]; k < kindStart[BranchLimitScreen.SIDE_1_APPARENT_POWER + 1]; k++) {
            LfBranch branch = branches[k];
            if (!isBranchDisabled.test(branch) && branch.computeApparentPower1() > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
        for (int k = kindStart[BranchLimitScreen.SIDE_2_CURRENT]; k < kindStart[BranchLimitScreen.SIDE_2_CURRENT + 1]; k++) {
            LfBranch branch = branches[k];
            if (!isBranchDisabled.test(branch) && branch.getI2().eval() > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
        for (int k = kindStart[BranchLimitScreen.SIDE_2_ACTIVE_POWER]; k < kindStart[BranchLimitScreen.SIDE_2_ACTIVE_POWER + 1]; k++) {
            LfBranch branch = branches[k];
            if (!isBranchDisabled.test(branch) && Math.abs(branch.getP2().eval()) > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
        for (int k = kindStart[BranchLimitScreen.SIDE_2_APPARENT_POWER]; k < kindStart[BranchLimitScreen.SIDE_2_APPARENT_POWER + 1]; k++) {
            LfBranch branch = branches[k];
            if (!isBranchDisabled.test(branch) && branch.computeApparentPower2() > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
    }

    /**
     * Screen the checks reading the flows straight from the branch-num indexed arrays the equation system maintains.
     * Each loop is then a pure array pass - {@code p1[branchNum[k]] > threshold[k]} - with no branch, no evaluable and
     * no virtual call in it, which is what {@link BranchLimitScreen}'s layout was for: laying the thresholds out flat
     * while the compared value still came through the branch object graph left the cost of a structure of arrays
     * without its benefit.
     *
     * <p>Checks are ordered by ascending branch num within a kind, so each pass sweeps the flow array forwards.
     *
     * <p>Which branches the arrays actually describe is resolved first, once per checked branch rather than once per
     * check, into a num indexed buffer - it is the only thing the pass needs that is not already in an array. Three
     * cases, and neither of the last two can be dropped:
     * <ul>
     *     <li>disabled: skipped entirely, as the walk skips it. Its array entries are stale, and reporting it would
     *     evaluate an evaluable whose variables are no longer in the state vector.</li>
     *     <li>enabled but not described by the arrays - either because the equation system models it outside the
     *     vectorised terms ({@link LfBranchFlowArrays#describedBranches()}), or because it is open on a side, in
     *     which case the arrays are left stale and the branch evaluates through its open-branch terms. Screened
     *     through its evaluables instead.</li>
     *     <li>enabled, closed and described: screened from the arrays.</li>
     * </ul>
     * The last case is the overwhelming majority, so the branch is predictable and the cold cases cost nothing.
     */
    private static void screenFromFlowArrays(BranchLimitScreen screen, Predicate<LfBranch> isBranchDisabled,
                                             LfBranchFlowArrays flows, ExceededChecks exceeded) {
        boolean[] described = flows.describedBranches();
        byte[] mode = new byte[screen.getBranchNumBound()];
        for (LfBranch branch : screen.getCheckedBranches()) {
            int num = branch.getNum();
            byte branchMode;
            if (isBranchDisabled.test(branch)) {
                branchMode = SCREEN_SKIP;
            } else if (num < described.length && described[num] && branch.isConnectedSide1() && branch.isConnectedSide2()) {
                branchMode = SCREEN_BULK;
            } else {
                branchMode = SCREEN_EVALUABLE;
            }
            mode[num] = branchMode;
        }

        LfBranch[] branches = screen.getBranches();
        double[] thresholds = screen.getThresholds();
        int[] ranks = screen.getRanks();
        int[] kindStart = screen.getKindStart();
        int[] branchNums = screen.getBranchNums();
        double[] p1 = flows.p1();
        double[] q1 = flows.q1();
        double[] i1 = flows.i1();
        double[] p2 = flows.p2();
        double[] q2 = flows.q2();
        double[] i2 = flows.i2();

        for (int k = kindStart[BranchLimitScreen.SIDE_1_CURRENT]; k < kindStart[BranchLimitScreen.SIDE_1_CURRENT + 1]; k++) {
            int branchNum = branchNums[k];
            byte branchMode = mode[branchNum];
            if (branchMode == SCREEN_BULK ? i1[branchNum] > thresholds[k]
                    : branchMode == SCREEN_EVALUABLE && branches[k].getI1().eval() > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
        for (int k = kindStart[BranchLimitScreen.SIDE_1_ACTIVE_POWER]; k < kindStart[BranchLimitScreen.SIDE_1_ACTIVE_POWER + 1]; k++) {
            int branchNum = branchNums[k];
            byte branchMode = mode[branchNum];
            if (branchMode == SCREEN_BULK ? Math.abs(p1[branchNum]) > thresholds[k]
                    : branchMode == SCREEN_EVALUABLE && Math.abs(branches[k].getP1().eval()) > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
        for (int k = kindStart[BranchLimitScreen.SIDE_1_APPARENT_POWER]; k < kindStart[BranchLimitScreen.SIDE_1_APPARENT_POWER + 1]; k++) {
            int branchNum = branchNums[k];
            byte branchMode = mode[branchNum];
            // same expression as LfBranch#computeApparentPower1, so the same double
            double p = p1[branchNum];
            double q = q1[branchNum];
            if (branchMode == SCREEN_BULK ? FastMath.sqrt(p * p + q * q) > thresholds[k]
                    : branchMode == SCREEN_EVALUABLE && branches[k].computeApparentPower1() > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
        for (int k = kindStart[BranchLimitScreen.SIDE_2_CURRENT]; k < kindStart[BranchLimitScreen.SIDE_2_CURRENT + 1]; k++) {
            int branchNum = branchNums[k];
            byte branchMode = mode[branchNum];
            if (branchMode == SCREEN_BULK ? i2[branchNum] > thresholds[k]
                    : branchMode == SCREEN_EVALUABLE && branches[k].getI2().eval() > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
        for (int k = kindStart[BranchLimitScreen.SIDE_2_ACTIVE_POWER]; k < kindStart[BranchLimitScreen.SIDE_2_ACTIVE_POWER + 1]; k++) {
            int branchNum = branchNums[k];
            byte branchMode = mode[branchNum];
            if (branchMode == SCREEN_BULK ? Math.abs(p2[branchNum]) > thresholds[k]
                    : branchMode == SCREEN_EVALUABLE && Math.abs(branches[k].getP2().eval()) > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
        for (int k = kindStart[BranchLimitScreen.SIDE_2_APPARENT_POWER]; k < kindStart[BranchLimitScreen.SIDE_2_APPARENT_POWER + 1]; k++) {
            int branchNum = branchNums[k];
            byte branchMode = mode[branchNum];
            double p = p2[branchNum];
            double q = q2[branchNum];
            if (branchMode == SCREEN_BULK ? FastMath.sqrt(p * p + q * q) > thresholds[k]
                    : branchMode == SCREEN_EVALUABLE && branches[k].computeApparentPower2() > thresholds[k]) {
                exceeded.add(ranks[k], k);
            }
        }
    }

    /**
     * The checks whose flow exceeded their screening threshold, packed as (rank, check index) so that sorting them
     * restores the order in which the original detection visited the checks. Growable, and only appended to on the
     * rare path where a check is not screened out.
     */
    private static final class ExceededChecks {

        private long[] packed = new long[16];

        private int count;

        void add(int rank, int checkIndex) {
            if (count == packed.length) {
                packed = Arrays.copyOf(packed, packed.length * 2);
            }
            packed[count++] = ((long) rank << 32) | checkIndex;
        }

        int size() {
            return count;
        }

        long[] sorted() {
            Arrays.sort(packed, 0, count);
            return packed;
        }
    }

    private void reportCheckViolations(LfBranch branch, LfBus bus, List<LfBranch.LfLimitsGroup> groups, int kind) {
        switch (kind) {
            case BranchLimitScreen.SIDE_1_CURRENT -> detectBranchCurrentViolations(branch, bus, LfBranch::getI1, groups, TwoSides.ONE);
            case BranchLimitScreen.SIDE_1_ACTIVE_POWER -> detectBranchActivePowerViolations(branch, LfBranch::getP1, groups, TwoSides.ONE);
            case BranchLimitScreen.SIDE_1_APPARENT_POWER -> detectBranchApparentPowerViolations(branch, LfBranch::computeApparentPower1, groups, TwoSides.ONE);
            case BranchLimitScreen.SIDE_2_CURRENT -> detectBranchCurrentViolations(branch, bus, LfBranch::getI2, groups, TwoSides.TWO);
            case BranchLimitScreen.SIDE_2_ACTIVE_POWER -> detectBranchActivePowerViolations(branch, LfBranch::getP2, groups, TwoSides.TWO);
            case BranchLimitScreen.SIDE_2_APPARENT_POWER -> detectBranchApparentPowerViolations(branch, LfBranch::computeApparentPower2, groups, TwoSides.TWO);
            default -> throw new IllegalStateException("Unsupported branch limit check: " + kind);
        }
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
