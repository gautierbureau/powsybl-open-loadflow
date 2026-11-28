/*
 * Copyright (c) 2023-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.*;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class SecondaryVoltageControlOuterLoop implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecondaryVoltageControlOuterLoop.class);

    public static final String NAME = "SecondaryVoltageControl";

    private static final double DV_EPS = SecondaryVoltageControlDecisions.DV_EPS;
    private static final double DK_DIFF_MAX_EPS = SecondaryVoltageControlDecisions.DK_DIFF_MAX_EPS;
    private static final double MAX_TARGET_VOLTAGE_STEP_KV = SecondaryVoltageControlDecisions.MAX_TARGET_VOLTAGE_STEP_KV;

    private final double minPlausibleTargetVoltage;

    private final double maxPlausibleTargetVoltage;

    @Override
    public String getName() {
        return NAME;
    }

    public SecondaryVoltageControlOuterLoop(double minPlausibleTargetVoltage, double maxPlausibleTargetVoltage) {
        this.minPlausibleTargetVoltage = minPlausibleTargetVoltage;
        this.maxPlausibleTargetVoltage = maxPlausibleTargetVoltage;
    }

    /**
     * The progress tracking of each zone, by its index in {@link LfNetwork#getSecondaryVoltageControls()}: the error of
     * the zone at its last adjustment (pilot point voltage difference and controller buses k difference), the
     * adjustments in a row without progress, and whether it was given up. It detects zones that cannot be adjusted
     * anymore (for instance because their controlled bus target voltages are clipped to the plausible voltage range, or
     * because their controller buses reactive power cannot be aligned any better).
     */
    private static final class ContextData {

        private final Map<LfSecondaryVoltageControl, Integer> zoneIndex = new IdentityHashMap<>();

        private final boolean[] hasLastError;

        private final double[] lastPilotDvAbs;

        private final double[] lastDkDiffMax;

        private final int[] noProgressCount;

        private final boolean[] stalled;

        private ContextData(List<LfSecondaryVoltageControl> zones) {
            for (int z = 0; z < zones.size(); z++) {
                zoneIndex.put(zones.get(z), z);
            }
            hasLastError = new boolean[zones.size()];
            lastPilotDvAbs = new double[zones.size()];
            lastDkDiffMax = new double[zones.size()];
            noProgressCount = new int[zones.size()];
            stalled = new boolean[zones.size()];
        }

        private void resetProgress(int z) {
            hasLastError[z] = false;
            noProgressCount[z] = 0;
        }

        private List<String> getStalledZoneNames(List<LfSecondaryVoltageControl> zones) {
            List<String> names = new ArrayList<>();
            for (int z = 0; z < stalled.length; z++) {
                if (stalled[z]) {
                    names.add(zones.get(z).getZoneName());
                }
            }
            return names;
        }
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        context.setData(new ContextData(context.getNetwork().getSecondaryVoltageControls()));
    }

    public static class SensitivityContext {

        private final Map<Integer, Integer> controlledBusIndex;

        private final DenseMatrix sensitivities;

        SensitivityContext(Map<Integer, Integer> controlledBusIndex, DenseMatrix sensitivities) {
            this.controlledBusIndex = Objects.requireNonNull(controlledBusIndex);
            this.sensitivities = Objects.requireNonNull(sensitivities);
        }

        public static SensitivityContext create(List<LfBus> controlledBuses, Map<Integer, Integer> controlledBusIndex, AcLoadFlowContext context) {
            DenseMatrix sensitivities = calculateSensitivityValues(controlledBuses, controlledBusIndex, context.getEquationSystem(),
                                                                   context.getJacobianMatrix());

            return new SensitivityContext(controlledBusIndex, sensitivities);
        }

        private static DenseMatrix calculateSensitivityValues(List<LfBus> controlledBuses, Map<Integer, Integer> busNumToSensiColumn,
                                                              EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                              JacobianMatrix<AcVariableType, AcEquationType> j) {
            DenseMatrix rhs = new DenseMatrix(equationSystem.getIndex().getColumnCount(), controlledBuses.size());
            for (LfBus controlledBus : controlledBuses) {
                equationSystem.getEquation(controlledBus.getNum(), AcEquationType.BUS_TARGET_V)
                        .ifPresent(equation -> rhs.set(equation.getColumn(), busNumToSensiColumn.get(controlledBus.getNum()), 1d));
            }
            j.solveTransposed(rhs);
            return rhs;
        }

        @SuppressWarnings("unchecked")
        private static EquationTerm<AcVariableType, AcEquationType> getCalculatedV(LfBus pilotBus) {
            return (EquationTerm<AcVariableType, AcEquationType>) pilotBus.getCalculatedV(); // this is safe
        }

        @SuppressWarnings("unchecked")
        private static EquationTerm<AcEquationType, AcEquationType> getQ1(LfBranch branch) {
            return (EquationTerm<AcEquationType, AcEquationType>) branch.getQ1();
        }

        @SuppressWarnings("unchecked")
        private static EquationTerm<AcEquationType, AcEquationType> getQ2(LfBranch branch) {
            return (EquationTerm<AcEquationType, AcEquationType>) branch.getQ2();
        }

        @SuppressWarnings("unchecked")
        private static EquationTerm<AcEquationType, AcEquationType> getQ(LfShunt shunt) {
            return (EquationTerm<AcEquationType, AcEquationType>) shunt.getQ();
        }

        double calculateSensiK(LfBus controllerBus, LfBus controlledBus) {
            return 2 * calculateSensiQ(controllerBus, controlledBus)
                    / (controllerBus.getMaxQ() - controllerBus.getMinQ());
        }

        /**
         * Calculate controlled bus voltage to controller bus reactive power injection sensitivity.
         */
        double calculateSensiQ(LfBus controllerBus, LfBus controlledBus) {
            int controlledBusSensiColumn = controlledBusIndex.get(controlledBus.getNum());

            MutableDouble sq = new MutableDouble();
            for (LfBranch branch : controllerBus.getBranches()) {
                // we can skip branches disconnected at the other side
                if (branch.getBus1() == controllerBus && branch.getBus2() != null) {
                    sq.add(getQ1(branch).calculateSensi(sensitivities, controlledBusSensiColumn));
                } else if (branch.getBus2() == controllerBus && branch.getBus1() != null) {
                    sq.add(getQ2(branch).calculateSensi(sensitivities, controlledBusSensiColumn));
                }
            }

            controllerBus.getShunt().ifPresent(shunt -> sq.add(getQ(shunt).calculateSensi(sensitivities, controlledBusSensiColumn)));
            controllerBus.getControllerShunt().ifPresent(shunt -> sq.add(getQ(shunt).calculateSensi(sensitivities, controlledBusSensiColumn)));
            controllerBus.getSvcShunt().ifPresent(shunt -> sq.add(getQ(shunt).calculateSensi(sensitivities, controlledBusSensiColumn)));

            return sq.getValue();
        }

        /**
         * Calculate controlled buses voltage to pilot bus voltage sensitivities.
         */
        double calculateSensiVpp(LfBus controlledBus, LfBus pilotBus) {
            int controlledBusSensiColumn = controlledBusIndex.get(controlledBus.getNum());
            return getCalculatedV(pilotBus).calculateSensi(sensitivities, controlledBusSensiColumn);
        }
    }

    private static double qToK(double q, LfBus controllerBus) {
        return SecondaryVoltageControlDecisions.qToK(q, controllerBus.getMinQ(), controllerBus.getMaxQ());
    }

    private static double calculateK(LfBus controllerBus) {
        double q = controllerBus.getQ().eval() + controllerBus.getLoadTargetQ();
        return qToK(q, controllerBus);
    }

    public static DenseMatrix createA(List<LfSecondaryVoltageControl> secondaryVoltageControls,
                                       List<LfBus> allControllerBuses, Map<Integer, Integer> controllerBusIndex) {
        DenseMatrix a = new DenseMatrix(allControllerBuses.size(), allControllerBuses.size());
        // build a block in the global matrix for each of the secondary voltage control
        for (LfSecondaryVoltageControl secondaryVoltageControl : secondaryVoltageControls) {
            List<LfBus> controllerBuses = secondaryVoltageControl.getEnabledControllerBuses();
            int nControl = controllerBuses.size();
            for (LfBus controllerBusI : controllerBuses) {
                for (LfBus controllerBusJ : controllerBuses) {
                    int i = controllerBusIndex.get(controllerBusI.getNum());
                    int j = controllerBusIndex.get(controllerBusJ.getNum());
                    a.set(i, j, i == j ? 1d - (1d / nControl) : -1d / nControl);
                }
            }
        }
        return a;
    }

    private static DenseMatrix createK0(List<LfBus> controllerBuses, Map<Integer, Integer> controllerBusIndex) {
        DenseMatrix k0 = new DenseMatrix(controllerBuses.size(), 1);
        for (LfBus controllerBus : controllerBuses) {
            int i = controllerBusIndex.get(controllerBus.getNum());
            k0.set(i, 0, calculateK(controllerBus));
        }
        return k0;
    }

    public static DenseMatrix createJk(SensitivityContext sensitivityContext,
                                        List<LfBus> controllerBuses, List<LfBus> controlledBuses,
                                        Map<Integer, Integer> controllerBusIndex, Map<Integer, Integer> controlledBusIndex) {
        DenseMatrix jK = new DenseMatrix(controlledBuses.size(), controllerBuses.size());
        for (LfBus controlledBusI : controlledBuses) {
            for (LfBus controllerBusJ : controllerBuses) {
                int i = controlledBusIndex.get(controlledBusI.getNum());
                int j = controllerBusIndex.get(controllerBusJ.getNum());
                jK.set(i, j, sensitivityContext.calculateSensiK(controllerBusJ, controlledBusI));
            }
        }
        return jK;
    }

    public static DenseMatrix createJvpp(SensitivityContext sensitivityContext, LfBus pilotBus,
                                          List<LfBus> controlledBuses, Map<Integer, Integer> controlledBusIndex) {
        DenseMatrix jVpp = new DenseMatrix(controlledBuses.size(), 1);
        for (LfBus controlledBus : controlledBuses) {
            int i = controlledBusIndex.get(controlledBus.getNum());
            jVpp.set(i, 0, sensitivityContext.calculateSensiVpp(controlledBus, pilotBus));
        }
        return jVpp;
    }

    private record KStats(double kAverage, double dkDiffMax, boolean allAtLimits) {
    }

    private static KStats calculateKStats(List<LfBus> controllerBuses) {
        double[] ks = controllerBuses.stream().mapToDouble(SecondaryVoltageControlOuterLoop::calculateK).toArray();
        return new KStats(SecondaryVoltageControlDecisions.kAverage(ks), SecondaryVoltageControlDecisions.dkDiffMax(ks),
                SecondaryVoltageControlDecisions.allAtLimits(ks));
    }

    private static double calculatePilotPointDv(LfSecondaryVoltageControl secondaryVoltageControl) {
        return secondaryVoltageControl.getTargetValue() - secondaryVoltageControl.getPilotBus().getV();
    }

    /**
     * A zone is stalled when its error did not decrease anymore during the last adjustments: adjusting it again would
     * just repeat the same ineffective target voltage change until max outer loop iteration is reached.
     */
    private static boolean isStalled(ContextData contextData, int z, String zoneName, double pilotDvAbs, double dkDiffMax, double pilotNominalV) {
        int count = SecondaryVoltageControlDecisions.noProgressCountAfter(contextData.hasLastError[z], pilotDvAbs, dkDiffMax,
                contextData.lastPilotDvAbs[z], contextData.lastDkDiffMax[z], contextData.noProgressCount[z]);
        contextData.hasLastError[z] = true;
        contextData.lastPilotDvAbs[z] = pilotDvAbs;
        contextData.lastDkDiffMax[z] = dkDiffMax;
        contextData.noProgressCount[z] = count;
        if (!SecondaryVoltageControlDecisions.isStalled(count)) {
            return false;
        }
        contextData.stalled[z] = true;
        LOGGER.warn("Secondary voltage control of zone '{}' is stalled after {} adjustments without progress (remaining pilot point dv is {} kV, " +
                "controller buses dk diff max is {}): it cannot be adjusted any further and is now ignored",
                zoneName, count, pilotDvAbs * pilotNominalV, dkDiffMax);
        return true;
    }

    private record TargetVoltageChange(DenseMatrix dv, int clippedTargetVoltageCount, double maxTargetVoltageStepKv) {
    }

    private record TargetVoltage(double value, boolean clipped) {
    }

    private static TargetVoltageChange clipTargetVoltageChange(List<LfBus> controlledBuses, DenseMatrix dv,
                                                               Map<Integer, Integer> controlledBusIndex) {
        DenseMatrix clippedDv = new DenseMatrix(dv.getRowCount(), dv.getColumnCount());
        int clippedTargetVoltageCount = 0;
        double maxTargetVoltageStepKv = 0;
        for (LfBus controlledBus : controlledBuses) {
            int i = controlledBusIndex.get(controlledBus.getNum());
            double dvValue = dv.get(i, 0);
            double nominalV = controlledBus.getNominalV();
            maxTargetVoltageStepKv = Math.max(maxTargetVoltageStepKv, Math.abs(dvValue * nominalV));
            clippedDv.set(i, 0, SecondaryVoltageControlDecisions.clippedStep(dvValue, nominalV));
            if (SecondaryVoltageControlDecisions.isStepClipped(dvValue, nominalV)) {
                clippedTargetVoltageCount++;
            }
        }
        return new TargetVoltageChange(clippedDv, clippedTargetVoltageCount, maxTargetVoltageStepKv);
    }

    private TargetVoltage clipTargetVoltageToPlausibleRange(double targetV) {
        return new TargetVoltage(SecondaryVoltageControlDecisions.clipToPlausibleRange(targetV, minPlausibleTargetVoltage, maxPlausibleTargetVoltage),
                SecondaryVoltageControlDecisions.isOutOfPlausibleRange(targetV, minPlausibleTargetVoltage, maxPlausibleTargetVoltage));
    }

    private List<String> processSecondaryVoltageControl(List<LfSecondaryVoltageControl> secondaryVoltageControls,
                                                        AcLoadFlowContext loadFlowContext,
                                                        ContextData contextData) {
        List<String> adjustedZoneNames = new ArrayList<>();

        for (LfSecondaryVoltageControl secondaryVoltageControl : secondaryVoltageControls) {
            String zoneName = secondaryVoltageControl.getZoneName();
            int z = contextData.zoneIndex.get(secondaryVoltageControl);
            if (contextData.stalled[z]) {
                continue;
            }
            double pilotDv = calculatePilotPointDv(secondaryVoltageControl);
            List<LfBus> controllerBuses = secondaryVoltageControl.getControllerBuses();
            KStats kStats = calculateKStats(controllerBuses);
            if (SecondaryVoltageControlDecisions.shouldAdjust(pilotDv, kStats.kAverage(), kStats.dkDiffMax(), kStats.allAtLimits())) {
                var pilotBus = secondaryVoltageControl.getPilotBus();
                if (LOGGER.isDebugEnabled()) {
                    int allControllerBusesCount = secondaryVoltageControl.getControllerBuses().size();
                    LOGGER.debug("Secondary voltage control of zone '{}': {}/{} controller buses available, pilot point dv is {} kV, controller buses dk diff max is {} (k average is {})",
                            zoneName, controllerBuses.size(), allControllerBusesCount, pilotDv * pilotBus.getNominalV(), kStats.dkDiffMax(), kStats.kAverage());
                }
                if (isStalled(contextData, z, zoneName, Math.abs(pilotDv), kStats.dkDiffMax(), pilotBus.getNominalV())) {
                    continue;
                }
                adjustedZoneNames.add(zoneName);
            } else {
                // zone does not need to be adjusted anymore, reset its progress tracking
                contextData.resetProgress(z);
            }
        }

        if (adjustedZoneNames.isEmpty()) {
            return Collections.emptyList();
        }

        List<LfBus> allControllerBuses = secondaryVoltageControls.stream()
                .flatMap(control -> control.getEnabledControllerBuses().stream())
                .toList();

        List<LfBus> allControlledBuses = allControllerBuses.stream()
                .map(b -> b.getGeneratorVoltageControl().orElseThrow().getControlledBus())
                .distinct()
                .toList();

        var controllerBusIndex = LfBus.buildIndex(allControllerBuses);
        var controlledBusIndex = LfBus.buildIndex(allControlledBuses);

        // compute target voltage sensitivities for all controlled buses
        SensitivityContext sensitivityContext = SensitivityContext.create(allControlledBuses, controlledBusIndex, loadFlowContext);

        DenseMatrix a = createA(secondaryVoltageControls, allControllerBuses, controllerBusIndex);

        DenseMatrix k0 = createK0(allControllerBuses, controllerBusIndex);

        DenseMatrix rhs = a.times(k0, -1);

        DenseMatrix jK = createJk(sensitivityContext, allControllerBuses, allControlledBuses, controllerBusIndex, controlledBusIndex).transpose();
        DenseMatrix b = a.times(jK);

        // replace last row for each secondary voltage control block
        for (LfSecondaryVoltageControl secondaryVoltageControl : secondaryVoltageControls) {
            List<LfBus> controllerBuses = secondaryVoltageControl.getEnabledControllerBuses();
            List<LfBus> controlledBuses = controllerBuses.stream()
                    .map(bus -> bus.getGeneratorVoltageControl().orElseThrow().getControlledBus())
                    .distinct()
                    .toList();
            LfBus lastControlledBus = controlledBuses.get(controlledBuses.size() - 1);
            int i = controlledBusIndex.get(lastControlledBus.getNum());

            DenseMatrix jVpp = createJvpp(sensitivityContext, secondaryVoltageControl.getPilotBus(), allControlledBuses, controlledBusIndex);
            for (int j = 0; j < b.getColumnCount(); j++) {
                b.set(i, j, jVpp.get(j, 0));
            }

            double pilotDv = calculatePilotPointDv(secondaryVoltageControl);
            rhs.set(i, 0, pilotDv);
        }

        try (LUDecomposition luDecomposition = b.decomposeLU()) {
            luDecomposition.solve(rhs);
        }
        @SuppressWarnings("UnnecessaryLocalVariable")
        DenseMatrix dv = rhs;

        TargetVoltageChange targetVoltageChange = clipTargetVoltageChange(allControlledBuses, dv, controlledBusIndex);
        if (targetVoltageChange.clippedTargetVoltageCount() > 0) {
            LOGGER.info("Clip {} secondary voltage control target voltage changes to {} kV (unclipped max step was {} kV)",
                    targetVoltageChange.clippedTargetVoltageCount(), MAX_TARGET_VOLTAGE_STEP_KV,
                    targetVoltageChange.maxTargetVoltageStepKv());
        }

        // compute new controlled bus target voltages
        Map<LfBus, Double> newControlledBusTargetV = new HashMap<>(allControlledBuses.size());
        int clippedPlausibleTargetVoltageCount = 0;
        for (LfBus controlledBus : allControlledBuses) {
            int i = controlledBusIndex.get(controlledBus.getNum());
            var vc = controlledBus.getGeneratorVoltageControl().orElseThrow();
            TargetVoltage newTargetV = clipTargetVoltageToPlausibleRange(vc.getTargetValue() + targetVoltageChange.dv().get(i, 0));
            if (newTargetV.clipped()) {
                clippedPlausibleTargetVoltageCount++;
            }
            newControlledBusTargetV.put(controlledBus, newTargetV.value());
        }
        if (clippedPlausibleTargetVoltageCount > 0) {
            LOGGER.info("Clip {} secondary voltage control target voltages to plausible range [{}, {}] pu",
                    clippedPlausibleTargetVoltageCount, minPlausibleTargetVoltage, maxPlausibleTargetVoltage);
        }

        for (var e : newControlledBusTargetV.entrySet()) {
            LfBus controlledBus = e.getKey();
            double newTargetV = e.getValue();
            var vc = controlledBus.getGeneratorVoltageControl().orElseThrow();
            LOGGER.trace("Adjust target voltage of controlled bus '{}': {} -> {}",
                    controlledBus.getId(), vc.getTargetValue() * controlledBus.getNominalV(),
                    newTargetV * controlledBus.getNominalV());
            vc.setTargetValue(newTargetV);
        }

        return adjustedZoneNames;
    }

    private static void tryToReEnableHelpfulControllerBuses(LfNetwork network) {
        network.getEnabledSecondaryVoltageControls()
                .forEach(LfSecondaryVoltageControl::tryToReEnableHelpfulControllerBuses);
    }

    private static void logZonesWithAllBusControllersAtReactivePowerLimit(LfNetwork network) {
        List<String> zoneNames = network.getEnabledSecondaryVoltageControls().stream()
                .filter(control -> control.getEnabledControllerBuses().isEmpty())
                .map(LfSecondaryVoltageControl::getZoneName)
                .toList();
        if (!zoneNames.isEmpty()) {
            LOGGER.info("Controller buses of secondary voltage control zones {} cannot produce or absorb more reactive power",
                    zoneNames);
        }
    }

    @Override
    public void cleanup(AcOuterLoopContext context) {
        logSecondaryVoltageControlSummary(context.getNetwork(), (ContextData) context.getData());
    }

    /**
     * Log, for each secondary voltage control zone, whether the pilot point voltage target has been reached and
     * whether reactive power of the controller buses has been aligned.
     */
    private static void logSecondaryVoltageControlSummary(LfNetwork network, ContextData contextData) {
        List<LfSecondaryVoltageControl> secondaryVoltageControls = network.getSecondaryVoltageControls();
        if (secondaryVoltageControls.isEmpty() || !LOGGER.isInfoEnabled()) {
            return;
        }
        List<String> pilotPointNotReached = new ArrayList<>();
        List<String> reactivePowerNotAligned = new ArrayList<>();
        int okCount = 0;
        for (LfSecondaryVoltageControl secondaryVoltageControl : secondaryVoltageControls) {
            String zoneName = secondaryVoltageControl.getZoneName();
            List<LfBus> controllerBuses = secondaryVoltageControl.getControllerBuses();
            if (controllerBuses.isEmpty()) {
                pilotPointNotReached.add(zoneName + " (no controller bus)");
                continue;
            }
            LfBus pilotBus = secondaryVoltageControl.getPilotBus();
            double pilotDv = calculatePilotPointDv(secondaryVoltageControl);
            KStats kStats = calculateKStats(controllerBuses);
            boolean pilotPointReached = Math.abs(pilotDv) <= DV_EPS;
            // a zone where all controller buses are at their reactive power limit cannot align them any better
            boolean reactivePowerAligned = kStats.dkDiffMax() <= DK_DIFF_MAX_EPS || kStats.allAtLimits();
            if (!pilotPointReached) {
                pilotPointNotReached.add(String.format(Locale.ROOT, "%s (dv=%.3f kV)", zoneName, pilotDv * pilotBus.getNominalV()));
            }
            if (!reactivePowerAligned) {
                reactivePowerNotAligned.add(String.format(Locale.ROOT, "%s (dk=%.4f)", zoneName, kStats.dkDiffMax()));
            }
            if (pilotPointReached && reactivePowerAligned) {
                okCount++;
            }
        }
        LOGGER.info("Secondary voltage control summary: {}/{} zones with pilot point voltage target reached and reactive power aligned",
                okCount, secondaryVoltageControls.size());
        if (!pilotPointNotReached.isEmpty()) {
            LOGGER.info("Secondary voltage control zones with pilot point voltage target not reached: {}", pilotPointNotReached);
        }
        if (!reactivePowerNotAligned.isEmpty()) {
            LOGGER.info("Secondary voltage control zones with reactive power not aligned: {}", reactivePowerNotAligned);
        }
        List<String> stalledZoneNames = contextData != null ? contextData.getStalledZoneNames(secondaryVoltageControls) : List.of();
        if (!stalledZoneNames.isEmpty()) {
            LOGGER.info("Secondary voltage control zones given up because they could not be adjusted any further: {}",
                    stalledZoneNames);
        }
    }

    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        LfNetwork network = context.getNetwork();

        // try to re-enable controller buses that have reached a reactive power limit (so bus switched to PQ) if they
        // can help to reach pilot point voltage target
        tryToReEnableHelpfulControllerBuses(network);

        // log zones where all controllers have reached reactive power limit
        logZonesWithAllBusControllersAtReactivePowerLimit(network);

        // for the remaining process, only keep secondary voltage control zones that have at least one enabled controller
        // bus, so zones that can still be adjusted
        List<LfSecondaryVoltageControl> secondaryVoltageControls = network.getEnabledSecondaryVoltageControls().stream()
                .filter(control -> control.findAnyControlledBusWithAtLeastOneControllerBusWithVoltageControlEnabled().isPresent())
                .toList();

        if (secondaryVoltageControls.isEmpty()) {
            return new OuterLoopResult(this, OuterLoopStatus.STABLE);
        }

        List<String> adjustedZoneNames = processSecondaryVoltageControl(secondaryVoltageControls, context.getLoadFlowContext(),
                (ContextData) context.getData());

        OuterLoopStatus status = OuterLoopStatus.STABLE;
        if (!adjustedZoneNames.isEmpty()) {
            status = OuterLoopStatus.UNSTABLE;
            LOGGER.info("{} secondary voltage control zones have been adjusted: {}",
                    adjustedZoneNames.size(), adjustedZoneNames);
        }

        return new OuterLoopResult(this, status);
    }
}
