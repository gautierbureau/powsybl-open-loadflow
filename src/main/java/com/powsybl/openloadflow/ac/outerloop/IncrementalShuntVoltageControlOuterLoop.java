/**
 * Copyright (c) 2022, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
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
import org.apache.commons.lang3.mutable.MutableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * @author Hadrien Godard {@literal <hadrien.godard at artelys.com>}
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class IncrementalShuntVoltageControlOuterLoop extends AbstractShuntVoltageControlOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalShuntVoltageControlOuterLoop.class);

    public static final String NAME = "IncrementalShuntVoltageControl";

    // Maximum number of directional inversions for each controller during successive incremental outer loops
    private static final int MAX_DIRECTION_CHANGE = 3;

    // Maximum section number shift for each controller within a single outerloop
    public static final int MAX_SECTION_SHIFT_DEFAULT_VALUE = 3;

    private static final double MIN_TARGET_DEADBAND_KV = 0.1; // kV

    private final int maxSectionShift;

    public IncrementalShuntVoltageControlOuterLoop(int maxSectionShift) {
        this.maxSectionShift = maxSectionShift;
    }

    @Override
    public String getName() {
        return NAME;
    }

    public static List<LfBus> getControlledBusesOutOfDeadband(IncrementalContextData contextData) {
        return IncrementalContextData.getControlledBuses(contextData.getCandidateControlledBuses(), VoltageControl.Type.SHUNT).stream()
                .filter(bus -> isOutOfDeadband(bus.getShuntVoltageControl().orElseThrow()))
                .toList();
    }

    public static List<LfShunt> getControllerElementsOutOfDeadband(List<LfBus> controlledBusesOutOfDeadband) {
        return controlledBusesOutOfDeadband.stream()
                .flatMap(bus -> bus.getShuntVoltageControl().orElseThrow().getMergedControllerElements().stream())
                .filter(Predicate.not(LfShunt::isDisabled))
                .toList();
    }

    public static List<LfShunt> getControllerElements(IncrementalContextData contextData) {
        return IncrementalContextData.getControllerElements(contextData.getCandidateControlledBuses(), VoltageControl.Type.SHUNT);
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        var contextData = new IncrementalContextData(context.getNetwork(), VoltageControl.Type.SHUNT);
        context.setData(contextData);

        // All shunt voltage control are disabled for the first equation system resolution.
        for (LfShunt shunt : getControllerElements(contextData)) {
            shunt.getVoltageControl().ifPresent(voltageControl -> shunt.setVoltageControlEnabled(false));
            for (LfShunt.Controller lfShuntController : shunt.getControllers()) {
                contextData.getControllersContexts().put(lfShuntController.getId(), new IncrementalContextData.ControllerContext(MAX_DIRECTION_CHANGE));
            }
        }
    }

    static class SensitivityContext {

        private final DenseMatrix sensitivities;

        private final int[] controllerShuntIndex;

        SensitivityContext(LfNetwork network, List<LfShunt> controllerShunts,
                                  EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                  JacobianMatrix<AcVariableType, AcEquationType> j) {
            controllerShuntIndex = createControllerShuntIndex(network, controllerShunts);
            sensitivities = calculateSensitivityValues(controllerShunts, controllerShuntIndex, equationSystem, j);
        }

        private static int[] createControllerShuntIndex(LfNetwork network, List<LfShunt> controllerShunts) {
            int[] controllerShuntIndex = new int[network.getShunts().size()];
            for (int i = 0; i < controllerShunts.size(); i++) {
                LfShunt controllerShunt = controllerShunts.get(i);
                controllerShuntIndex[controllerShunt.getNum()] = i;
            }
            return controllerShuntIndex;
        }

        private static DenseMatrix calculateSensitivityValues(List<LfShunt> controllerShunts, int[] controllerShuntIndex,
                                                              EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                              JacobianMatrix<AcVariableType, AcEquationType> j) {
            int nRows = equationSystem.getIndex().getColumnCount();
            int nColumns = controllerShunts.size();

            DenseMatrix rhs = new DenseMatrix(nRows, nColumns);
            for (LfShunt controllerShunt : controllerShunts) {
                equationSystem.getEquation(controllerShunt.getNum(), AcEquationType.SHUNT_TARGET_B)
                        .ifPresent(equation -> rhs.set(equation.getColumn(), controllerShuntIndex[controllerShunt.getNum()], 1d));
            }
            j.solveTransposed(rhs);
            return rhs;
        }

        @SuppressWarnings("unchecked")
        private static EquationTerm<AcVariableType, AcEquationType> getCalculatedV(LfBus controlledBus) {
            return (EquationTerm<AcVariableType, AcEquationType>) controlledBus.getCalculatedV();
        }

        double calculateSensitivityFromBToV(LfShunt controllerShunt, LfBus controlledBus) {
            return getCalculatedV(controlledBus)
                    .calculateSensi(sensitivities, controllerShuntIndex[controllerShunt.getNum()]);
        }
    }

    private static double getDiffV(ShuntVoltageControl voltageControl) {
        double targetV = voltageControl.getControlledBus().getHighestPriorityTargetV().orElseThrow();
        double v = voltageControl.getControlledBus().getV();
        return targetV - v;
    }

    private static boolean isOutOfDeadband(ShuntVoltageControl voltageControl) {
        double diffV = getDiffV(voltageControl);
        double halfTargetDeadband = getHalfTargetDeadband(voltageControl);
        boolean outOfDeadband = IncrementalControlDecisions.isOutOfDeadband(diffV, halfTargetDeadband);
        if (outOfDeadband) {
            List<LfShunt> controllers = voltageControl.getMergedControllerElements().stream()
                    .filter(shunt -> !shunt.isDisabled())
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

        // check which shunts are not within their deadbands
        List<LfBus> controlledBusesOutOfDeadband = getControlledBusesOutOfDeadband(contextData);
        List<LfShunt> controllerShuntsOutOfDeadband = getControllerElementsOutOfDeadband(controlledBusesOutOfDeadband);

        // all shunts are within their deadbands
        if (controllerShuntsOutOfDeadband.isEmpty()) {
            return new OuterLoopResult(this, status.get());
        }

        List<DiscreteControllerChange> adjustedControllers = new ArrayList<>();

        SensitivityContext sensitivityContext = new SensitivityContext(network, controllerShuntsOutOfDeadband,
                loadFlowContext.getEquationSystem(), loadFlowContext.getJacobianMatrix());

        ShuntVoltageControlVector v = gather(controlledBusesOutOfDeadband, contextData, sensitivityContext);
        ShuntVoltageControlDecisions.decide(v, maxSectionShift);
        apply(v, network, contextData, adjustedControllers);

        if (!adjustedControllers.isEmpty()) {
            status.setValue(OuterLoopStatus.UNSTABLE);
            ReportNode iterationReportNode = LfReports.createOuterLoopIterationReporter(reportNode, context.getOuterLoopTotalIterations() + 1);
            ReportNode summary = LfReports.reportShuntVoltageControlChangedSection(iterationReportNode, adjustedControllers.size());
            adjustedControllers.forEach(c -> LfReports.reportShuntVoltageControlChangedSectionDetail(summary, c));
        }

        return new OuterLoopResult(this, status.get());
    }

    /**
     * The controlled buses out of their deadband and, for each, its enabled controller shunts by decreasing
     * susceptance magnitude with their sensitivity, and their section controllers with their sections, their section
     * and their ratchet state.
     */
    static ShuntVoltageControlVector gather(List<LfBus> controlledBusesOutOfDeadband, IncrementalContextData contextData,
                                            SensitivityContext sensitivityContext) {
        List<List<LfShunt>> shuntsPerBus = new ArrayList<>(controlledBusesOutOfDeadband.size());
        int shuntCount = 0;
        int controllerCount = 0;
        for (LfBus controlledBus : controlledBusesOutOfDeadband) {
            List<LfShunt> sortedControllers = controlledBus.getShuntVoltageControl().orElseThrow().getMergedControllerElements().stream()
                    .filter(shunt -> !shunt.isDisabled())
                    .sorted(Comparator.comparingDouble(LfShunt::getBMagnitude).reversed())
                    .toList();
            shuntsPerBus.add(sortedControllers);
            shuntCount += sortedControllers.size();
            for (LfShunt shunt : sortedControllers) {
                controllerCount += shunt.getControllers().size();
            }
        }
        ShuntVoltageControlVector v = new ShuntVoltageControlVector();
        v.reset(controlledBusesOutOfDeadband.size(), shuntCount, controllerCount);
        int s = 0;
        int k = 0;
        for (int b = 0; b < v.busCount; b++) {
            LfBus controlledBus = controlledBusesOutOfDeadband.get(b);
            ShuntVoltageControl voltageControl = controlledBus.getShuntVoltageControl().orElseThrow();
            v.busNum[b] = controlledBus.getNum();
            v.diffV[b] = getDiffV(voltageControl);
            v.halfTargetDeadband[b] = getHalfTargetDeadband(voltageControl);
            v.firstShunt[b] = s;
            for (LfShunt shunt : shuntsPerBus.get(b)) {
                v.shuntNum[s] = shunt.getNum();
                v.firstController[s] = k;
                List<LfShunt.Controller> controllers = shunt.getControllers();
                v.sensitivity[s] = controllers.isEmpty() ? Double.NaN : sensitivityContext.calculateSensitivityFromBToV(shunt, controlledBus);
                for (int i = 0; i < controllers.size(); i++) {
                    LfShunt.Controller controller = controllers.get(i);
                    IncrementalContextData.ControllerContext controllerContext = contextData.getControllersContexts().get(controller.getId());
                    v.indexInShunt[k] = i;
                    v.sectionBs[k] = controller.getSectionBs();
                    v.minPosition[k] = controller.getMinPosition();
                    v.initialPosition[k] = controller.getPosition();
                    v.maxDirectionChange[k] = controllerContext.getMaxDirectionChange();
                    v.directionChangeCount[k] = controllerContext.getDirectionChangeCount();
                    v.currentDirection[k] = controllerContext.getCurrentDirectionCode();
                    v.allowedDirection[k] = controllerContext.getAllowedDirectionCode();
                    k++;
                }
                s++;
            }
        }
        v.firstShunt[v.busCount] = s;
        v.firstController[v.shuntCount] = k;
        return v;
    }

    /** The shunt each section controller belongs to. */
    private static int[] shuntOfController(ShuntVoltageControlVector v) {
        int[] shuntOf = new int[v.controllerCount];
        for (int s = 0; s < v.shuntCount; s++) {
            for (int k = v.firstController[s]; k < v.firstController[s + 1]; k++) {
                shuntOf[k] = s;
            }
        }
        return shuntOf;
    }

    /**
     * Replays the decided moves in their order, through the same section changes (the shunt's susceptance and
     * conductance summed again, its listeners told) and ratchet as before, then lists a change per controller that
     * moved.
     */
    private static void apply(ShuntVoltageControlVector v, LfNetwork network, IncrementalContextData contextData,
                              List<DiscreteControllerChange> adjustedControllers) {
        int[] shuntOf = shuntOfController(v);
        for (int m = 0; m < v.moveCount; m++) {
            int k = v.moveController[m];
            LfShunt.Controller controller = network.getShunt(v.shuntNum[shuntOf[k]]).getControllers().get(v.indexInShunt[k]);
            int oldSection = controller.getPosition();
            int newSection = v.movePosition[m];
            controller.updateSectionB(newSection);
            LOGGER.debug("Controller '{}' change section from {} to {}", controller.getId(), oldSection, newSection);
            contextData.getControllersContexts().get(controller.getId())
                    .updateAllowedDirection(newSection - oldSection > 0 ? Direction.INCREASE : Direction.DECREASE);
        }
        for (int k = 0; k < v.controllerCount; k++) {
            LfShunt.Controller controller = network.getShunt(v.shuntNum[shuntOf[k]]).getControllers().get(v.indexInShunt[k]);
            IncrementalContextData.ControllerContext controllerContext = contextData.getControllersContexts().get(controller.getId());
            assert controllerContext.getAllowedDirectionCode() == v.allowedDirection[k]
                    && controllerContext.getDirectionChangeCount() == v.directionChangeCount[k]
                    && controllerContext.getCurrentDirectionCode() == v.currentDirection[k]
                    && controller.getPosition() == v.position[k]
                    : "Shunt controller '" + controller.getId() + "' differs from its decided state";
            if (v.sectionShift[k] > 0) {
                adjustedControllers.add(DiscreteControllerChange.ofShunt(controller, v.initialPosition[k], controller.getPosition()));
            }
        }
    }

    protected static double getHalfTargetDeadband(ShuntVoltageControl voltageControl) {
        return IncrementalControlDecisions.halfVoltageTargetDeadband(voltageControl.getTargetDeadband().orElse(Double.NaN),
                voltageControl.getControlledBus().getNominalV(), MIN_TARGET_DEADBAND_KV);
    }
}
