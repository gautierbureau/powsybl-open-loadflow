/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.lf.outerloop.DiscreteControllerChange;
import com.powsybl.openloadflow.lf.outerloop.IncrementalContextData;
import com.powsybl.openloadflow.lf.outerloop.IncrementalControlDecisions;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.lf.util.LfReports;
import com.powsybl.openloadflow.network.*;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class IncrementalTransformerVoltageControlOuterLoop extends AbstractTransformerVoltageControlOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalTransformerVoltageControlOuterLoop.class);

    public static final String NAME = "IncrementalTransformerVoltageControl";

    private static final int MAX_DIRECTION_CHANGE = 3;

    private static final double MIN_SENSI_FILTER = 0.05; // voltage vs. tap changer ratio sensitivity

    private final int maxTapShift;

    public IncrementalTransformerVoltageControlOuterLoop(int maxTapShift) {
        this.maxTapShift = maxTapShift;
    }

    @Override
    public String getName() {
        return NAME;
    }

    public static List<LfBus> getControlledBusesOutOfDeadband(IncrementalContextData contextData) {
        return IncrementalContextData.getControlledBuses(contextData.getCandidateControlledBuses(), VoltageControl.Type.TRANSFORMER).stream()
                .filter(bus -> isOutOfDeadband(bus.getTransformerVoltageControl().orElseThrow()))
                .toList();
    }

    public static List<LfBranch> getControllerElementsOutOfDeadband(List<LfBus> controlledBusesOutOfDeadband) {
        return controlledBusesOutOfDeadband.stream()
                .flatMap(bus -> bus.getTransformerVoltageControl().orElseThrow().getMergedControllerElements().stream())
                .filter(Predicate.not(LfBranch::isDisabled))
                .filter(LfBranch::isConnectedAtBothSides)
                .toList();
    }

    public static List<LfBranch> getControllerElements(IncrementalContextData contextData) {
        return IncrementalContextData.getControllerElements(contextData.getCandidateControlledBuses(), VoltageControl.Type.TRANSFORMER);
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        var contextData = new IncrementalContextData(context.getNetwork(), VoltageControl.Type.TRANSFORMER);
        context.setData(contextData);

        // All transformer voltage control are disabled as in this outer loop voltage adjustment is not
        // done into the equation system
        for (LfBranch branch : getControllerElements(contextData)) {
            branch.getVoltageControl().ifPresent(voltageControl -> branch.setVoltageControlEnabled(false));
            contextData.getControllersContexts().put(branch.getId(), new IncrementalContextData.ControllerContext(MAX_DIRECTION_CHANGE));
        }
    }

    static class SensitivityContext {

        private final DenseMatrix sensitivities;

        private final int[] controllerBranchIndex;

        SensitivityContext(LfNetwork network, List<LfBranch> controllerBranches,
                                  EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                  JacobianMatrix<AcVariableType, AcEquationType> j) {
            controllerBranchIndex = LfBranch.createIndex(network, controllerBranches);
            sensitivities = calculateSensitivityValues(controllerBranches, controllerBranchIndex, equationSystem, j);
        }

        private static DenseMatrix calculateSensitivityValues(List<LfBranch> controllerBranches, int[] controllerBranchIndex,
                                                              EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                              JacobianMatrix<AcVariableType, AcEquationType> j) {
            int nRows = equationSystem.getIndex().getColumnCount();
            int nColumns = controllerBranches.size();

            DenseMatrix rhs = new DenseMatrix(nRows, nColumns);
            for (LfBranch controllerBranch : controllerBranches) {
                equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.BRANCH_TARGET_RHO1)
                        .ifPresent(equation -> rhs.set(equation.getColumn(), controllerBranchIndex[controllerBranch.getNum()], 1d));
            }
            j.solveTransposed(rhs);
            return rhs;
        }

        @SuppressWarnings("unchecked")
        private static EquationTerm<AcVariableType, AcEquationType> getCalculatedV(LfBus controlledBus) {
            return (EquationTerm<AcVariableType, AcEquationType>) controlledBus.getCalculatedV();
        }

        double calculateSensitivityFromRToV(LfBranch controllerBranch, LfBus controlledBus) {
            return getCalculatedV(controlledBus)
                    .calculateSensi(sensitivities, controllerBranchIndex[controllerBranch.getNum()]);
        }
    }

    private static double getDiffV(TransformerVoltageControl voltageControl) {
        double targetV = voltageControl.getControlledBus().getHighestPriorityTargetV().orElseThrow();
        double v = voltageControl.getControlledBus().getV();
        return targetV - v;
    }

    private static boolean isOutOfDeadband(TransformerVoltageControl voltageControl) {
        double diffV = getDiffV(voltageControl);
        double halfTargetDeadband = getHalfTargetDeadband(voltageControl);
        boolean outOfDeadband = IncrementalControlDecisions.isOutOfDeadband(diffV, halfTargetDeadband);
        if (outOfDeadband) {
            List<LfBranch> controllers = voltageControl.getMergedControllerElements().stream()
                    .filter(b -> !b.isDisabled())
                    .toList();
            LOGGER.trace("Controlled bus '{}' ({} controllers) is outside of its deadband (half is {} kV) and could need a voltage adjustment of {} kV",
                    voltageControl.getControlledBus().getId(), controllers.size(), halfTargetDeadband * voltageControl.getControlledBus().getNominalV(),
                    diffV * voltageControl.getControlledBus().getNominalV());
        }
        return outOfDeadband;
    }

    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        MutableObject<OuterLoopStatus> status = new MutableObject<>(OuterLoopStatus.STABLE);

        LfNetwork network = context.getNetwork();
        AcLoadFlowContext loadFlowContext = context.getLoadFlowContext();
        var contextData = (IncrementalContextData) context.getData();

        // If another outerloop than us ran, this will reset insensitive statuses
        // because insensitive statuses may have changed, in particular in case of PV/PQ switching.
        contextData.check(context.getOuterLoopTotalIterations());

        // filter out buses/branches which are outside their deadbands or insensitive
        List<LfBus> controlledBusesOutOfDeadband = getControlledBusesOutOfDeadband(contextData);
        List<LfBranch> controllerBranchesOutOfDeadband = getControllerElementsOutOfDeadband(controlledBusesOutOfDeadband)
                .stream().filter(b -> !contextData.getControllersContexts().get(b.getId()).isInsensitive())
                .toList();

        // all branches are within their deadbands or are insensitive
        if (controllerBranchesOutOfDeadband.isEmpty()) {
            return new OuterLoopResult(this, status.get());
        }

        SensitivityContext sensitivityContext = new SensitivityContext(network, controllerBranchesOutOfDeadband,
                loadFlowContext.getEquationSystem(), loadFlowContext.getJacobianMatrix());

        // for synthetics logs
        List<DiscreteControllerChange> controllerBranchesAdjusted = new ArrayList<>();
        List<String> controlledBusesWithAllItsControllersToLimit = new ArrayList<>();

        TransformerControlVector v = gather(controlledBusesOutOfDeadband, contextData, sensitivityContext);
        TransformerControlDecisions.decide(v, maxTapShift, MIN_SENSI_FILTER);
        apply(v, network, contextData, controllerBranchesAdjusted, controlledBusesWithAllItsControllersToLimit);

        if (!controllerBranchesAdjusted.isEmpty()) {
            status.setValue(OuterLoopStatus.UNSTABLE);
        }

        ReportNode iterationReportNode = !controlledBusesOutOfDeadband.isEmpty() || !controllerBranchesAdjusted.isEmpty() || !controlledBusesWithAllItsControllersToLimit.isEmpty() ?
                LfReports.createOuterLoopIterationReporter(reportNode, context.getOuterLoopTotalIterations() + 1) : null;

        if (!controlledBusesOutOfDeadband.isEmpty()) {
            if (LOGGER.isInfoEnabled()) {
                Map<String, Double> largestMismatches = controlledBusesOutOfDeadband.stream()
                        .map(controlledBus -> Pair.of(controlledBus.getId(), Math.abs(getDiffV(controlledBus.getTransformerVoltageControl().orElseThrow()) * controlledBus.getNominalV())))
                        .sorted((p1, p2) -> Double.compare(p2.getRight(), p1.getRight()))
                        .limit(3) // 3 largest
                        .collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (key1, key2) -> key1, LinkedHashMap::new));
                LOGGER.info("{} controlled bus voltages are outside of their target deadband, largest ones are: {}",
                        controlledBusesOutOfDeadband.size(), largestMismatches);
            }
            LfReports.reportTransformerControlBusesOutsideDeadband(Objects.requireNonNull(iterationReportNode), controlledBusesOutOfDeadband.size());
        }
        if (!controllerBranchesAdjusted.isEmpty()) {
            LOGGER.info("{} controlled bus voltages have been adjusted by changing at least one tap",
                    controllerBranchesAdjusted.size());
            ReportNode summary = LfReports.reportTransformerControlChangedTaps(Objects.requireNonNull(iterationReportNode), controllerBranchesAdjusted.size());
            controllerBranchesAdjusted
                    .forEach(controllerDetails -> LfReports.reportTransformerControlChangedTapsDetail(summary, controllerDetails));

        }
        if (!controlledBusesWithAllItsControllersToLimit.isEmpty()) {
            LOGGER.info("{} controlled buses have all its controllers to a tap limit: {}",
                    controlledBusesWithAllItsControllersToLimit.size(), controlledBusesWithAllItsControllersToLimit);
            LfReports.reportTransformerControlTapLimit(Objects.requireNonNull(iterationReportNode), controlledBusesWithAllItsControllersToLimit.size());
        }

        return new OuterLoopResult(this, status.get());
    }

    /**
     * The controlled buses out of their deadband and, for each, the controllers it adjusts: enabled and not known as
     * insensitive, in the order of the voltage control's controller elements, with their sensitivity, their taps' r1,
     * their tap and their ratchet state.
     */
    static TransformerControlVector gather(List<LfBus> controlledBusesOutOfDeadband, IncrementalContextData contextData,
                                                  SensitivityContext sensitivityContext) {
        List<List<LfBranch>> controllersPerBus = new ArrayList<>(controlledBusesOutOfDeadband.size());
        int controllerCount = 0;
        for (LfBus controlledBus : controlledBusesOutOfDeadband) {
            List<LfBranch> controllers = controlledBus.getTransformerVoltageControl().orElseThrow().getMergedControllerElements().stream()
                    .filter(b -> !b.isDisabled())
                    .filter(controller -> !contextData.getControllersContexts().get(controller.getId()).isInsensitive())
                    .toList();
            controllersPerBus.add(controllers);
            controllerCount += controllers.size();
        }
        TransformerControlVector v = new TransformerControlVector();
        v.reset(controlledBusesOutOfDeadband.size(), controllerCount);
        int c = 0;
        for (int b = 0; b < v.controlledCount; b++) {
            LfBus controlledBus = controlledBusesOutOfDeadband.get(b);
            TransformerVoltageControl voltageControl = controlledBus.getTransformerVoltageControl().orElseThrow();
            v.controlledNum[b] = controlledBus.getNum();
            v.diff[b] = getDiffV(voltageControl);
            v.halfTargetDeadband[b] = getHalfTargetDeadband(voltageControl);
            v.firstController[b] = c;
            for (LfBranch controllerBranch : controllersPerBus.get(b)) {
                PiModelArray piModel = getPiModelArray(controllerBranch);
                IncrementalContextData.ControllerContext controllerContext = contextData.getControllersContexts().get(controllerBranch.getId());
                v.branchNum[c] = controllerBranch.getNum();
                v.sensitivity[c] = sensitivityContext.calculateSensitivityFromRToV(controllerBranch, controlledBus);
                v.tapR1s[c] = piModel.getTapR1s();
                v.initialTapIndex[c] = piModel.getTapPositionIndex();
                v.initialR1[c] = piModel.getR1();
                v.maxDirectionChange[c] = controllerContext.getMaxDirectionChange();
                v.directionChangeCount[c] = controllerContext.getDirectionChangeCount();
                v.currentDirection[c] = controllerContext.getCurrentDirectionCode();
                v.allowedDirection[c] = controllerContext.getAllowedDirectionCode();
                c++;
            }
        }
        v.firstController[v.controlledCount] = c;
        return v;
    }

    private static PiModelArray getPiModelArray(LfBranch controllerBranch) {
        if (controllerBranch.getPiModel() instanceof PiModelArray piModelArray) {
            return piModelArray;
        }
        throw new IllegalStateException("Transformer voltage controller branch '" + controllerBranch.getId() + "' has no tap changer");
    }

    /**
     * Replays the decided moves in their order, through the same tap moves and ratchet as before, marks the
     * insensitive controllers, and lists the changes and the buses whose controllers are all at a tap limit.
     */
    private static void apply(TransformerControlVector v, LfNetwork network, IncrementalContextData contextData,
                              List<DiscreteControllerChange> controllerBranchesAdjusted, List<String> controlledBusesWithAllItsControllersToLimit) {
        for (int m = 0; m < v.moveCount; m++) {
            LfBranch controllerBranch = network.getBranch(v.branchNum[v.moveController[m]]);
            getPiModelArray(controllerBranch).applyTapPositionIndex(v.moveTapIndex[m], true)
                    .ifPresent(contextData.getControllersContexts().get(controllerBranch.getId())::updateAllowedDirection);
        }
        for (int b = 0; b < v.controlledCount; b++) {
            LfBus controlledBus = network.getBus(v.controlledNum[b]);
            boolean single = v.firstController[b + 1] - v.firstController[b] == 1;
            for (int c = v.firstController[b]; c < v.firstController[b + 1]; c++) {
                LfBranch controllerBranch = network.getBranch(v.branchNum[c]);
                IncrementalContextData.ControllerContext controllerContext = contextData.getControllersContexts().get(controllerBranch.getId());
                if (v.insensitive[c]) {
                    // Cache info in controller context. We will not recompute for consecutive outer loops.
                    controllerContext.setInsensitive();
                    LOGGER.debug("Controller branch '{}' ratio tap sensitivity to bus {} voltage too low ({}), no control performed",
                            controllerBranch.getId(), controlledBus.getId(), v.sensitivity[c]);
                }
                assert controllerContext.getAllowedDirectionCode() == v.allowedDirection[c]
                        && controllerContext.getDirectionChangeCount() == v.directionChangeCount[c]
                        && controllerContext.getCurrentDirectionCode() == v.currentDirection[c]
                        : "Ratchet of controller '" + controllerBranch.getId() + "' differs from its decided state";
                PiModel piModel = controllerBranch.getPiModel();
                int previousTapPosition = ((PiModelArray) piModel).getLowTapPosition() + v.initialTapIndex[c];
                if (piModel.getTapPosition() != previousTapPosition) {
                    Range<Integer> tapPositionRange = piModel.getTapPositionRange();
                    if (single) {
                        LOGGER.debug("Controller branch '{}' change tap from {} to {} (full range: {})", controllerBranch.getId(),
                                previousTapPosition, piModel.getTapPosition(), tapPositionRange);
                    } else {
                        LOGGER.debug("Controller branch '{}' (controlled bus '{}') change tap from {} to {} (full range: {})",
                                controllerBranch.getId(), controlledBus.getId(), previousTapPosition,
                                piModel.getTapPosition(), tapPositionRange);
                    }
                    controllerBranchesAdjusted.add(DiscreteControllerChange.ofTransformer(controllerBranch, previousTapPosition, piModel.getTapPosition()));
                }
            }
            if (v.allControllersAtLimit[b]) {
                controlledBusesWithAllItsControllersToLimit.add(controlledBus.getId());
            }
        }
    }

}
