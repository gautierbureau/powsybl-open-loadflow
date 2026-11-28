/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.lf.outerloop.AbstractPhaseControlOuterLoop;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.lf.outerloop.PhaseControlDecisions;
import com.powsybl.openloadflow.lf.outerloop.PhaseControlVector;
import com.powsybl.openloadflow.network.Direction;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.PiModel;
import com.powsybl.openloadflow.network.TransformerPhaseControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class PhaseControlOuterLoop
        extends AbstractPhaseControlOuterLoop<AcVariableType, AcEquationType, AcLoadFlowParameters, AcLoadFlowContext, AcOuterLoopContext>
        implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhaseControlOuterLoop.class);

    public static final String NAME = "PhaseControl";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        List<LfBranch> controllerBranches = getControllerBranches(context.getNetwork());
        for (LfBranch controllerBranch : controllerBranches.stream()
                .filter(controllerBranch -> controllerBranch.getPhaseControl().orElseThrow().getMode() == TransformerPhaseControl.Mode.CONTROLLER)
                .collect(Collectors.toList())) {
            controllerBranch.setPhaseControlEnabled(true);
        }
        fixPhaseShifterNecessaryForConnectivity(context.getNetwork(), controllerBranches);
    }

    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        if (context.getIteration() == 0) {
            // at first outer loop iteration:
            // branches with active power control are switched off and taps are rounded
            // branches with current limiter control will wait for second iteration
            return new OuterLoopResult(this, firstIteration(context));
        } else if (context.getIteration() > 0) {
            // at second outer loop iteration:
            // flow of branches with fixed tap are recomputed
            return new OuterLoopResult(this, nextIteration(context));
        }
        return new OuterLoopResult(this, OuterLoopStatus.STABLE);
    }

    private OuterLoopStatus firstIteration(AcOuterLoopContext context) {
        // all branches with active power control are switched off
        List<LfBranch> controllerBranches = getControllerBranches(context.getNetwork());
        controllerBranches.stream()
                .flatMap(controllerBranch -> controllerBranch.getPhaseControl().stream())
                .filter(phaseControl -> phaseControl.getMode() == TransformerPhaseControl.Mode.CONTROLLER)
                .forEach(this::switchOffPhaseControl);

        // if at least one phase shifter has been switched off we need to continue
        return controllerBranches.isEmpty() ? OuterLoopStatus.STABLE : OuterLoopStatus.UNSTABLE;
    }

    /**
     * At the second outer loop iteration and after, the phase controls in limiter mode whose current is above the limit
     * move one tap against the sensitivity of the current to the phase shift ({@link
     * PhaseControlDecisions#discreteLimiterDecision}). A limiter is read, decided and moved before the next one is
     * read, as before: a tap move can change the current another limiter reads.
     */
    private OuterLoopStatus nextIteration(AcOuterLoopContext context) {
        List<TransformerPhaseControl> limiters = getControllerBranches(context.getNetwork()).stream()
                .flatMap(branch -> branch.getPhaseControl().stream())
                .filter(phaseControl -> phaseControl.getMode() == TransformerPhaseControl.Mode.LIMITER)
                .toList();
        PhaseControlVector v = new PhaseControlVector();
        v.reset(limiters.size());
        boolean changed = false;
        for (int k = 0; k < v.count; k++) {
            TransformerPhaseControl phaseControl = limiters.get(k);
            LfBranch controllerBranch = phaseControl.getControllerBranch();
            TwoSides side = phaseControl.getControlledSide();
            // only local control supported: controlled branch is controller branch.
            v.branchNum[k] = controllerBranch.getNum();
            v.limiter[k] = true;
            v.target[k] = phaseControl.getTargetValue();
            v.value[k] = side == TwoSides.ONE ? controllerBranch.getI1().eval() : controllerBranch.getI2().eval();
            boolean sensitivityPositive = PhaseControlDecisions.isAboveLimit(v.value[k], v.target[k])
                    && isSensitivityCurrentPerA1Positive(controllerBranch, side);
            v.decision[k] = PhaseControlDecisions.discreteLimiterDecision(v.value[k], v.target[k], sensitivityPositive);
            if (v.decision[k] != PhaseControlDecisions.DECISION_NONE) {
                Direction direction = v.decision[k] == PhaseControlDecisions.DECISION_SHIFT_ONE_TAP_UP ? Direction.INCREASE : Direction.DECREASE;
                changed |= controllerBranch.getPiModel().shiftOneTapPositionToChangeA1(direction);
            }
        }
        return changed ? OuterLoopStatus.UNSTABLE : OuterLoopStatus.STABLE;
    }

    private void switchOffPhaseControl(TransformerPhaseControl phaseControl) {
        // switch off phase control
        LfBranch controllerBranch = phaseControl.getControllerBranch();
        controllerBranch.setPhaseControlEnabled(false);

        // round the phase shift to the closest tap
        PiModel piModel = controllerBranch.getPiModel();
        double a1Value = piModel.getA1();
        piModel.roundA1ToClosestTap();
        double roundedA1Value = piModel.getA1();
        LOGGER.info("Round phase shift of '{}': {} -> {}", controllerBranch.getId(), a1Value, roundedA1Value);
    }

    @SuppressWarnings("unchecked")
    private boolean isSensitivityCurrentPerA1Positive(LfBranch controllerBranch, TwoSides controlledSide) {
        if (controlledSide == TwoSides.ONE) {
            EquationTerm<AcVariableType, AcEquationType> i1 = (EquationTerm<AcVariableType, AcEquationType>) controllerBranch.getI1();
            var a1Var = i1.getVariables().stream().filter(v -> v.getType() == AcVariableType.BRANCH_ALPHA1).findFirst().orElseThrow();
            return i1.der(a1Var) > 0;
        } else {
            EquationTerm<AcVariableType, AcEquationType> i2 = (EquationTerm<AcVariableType, AcEquationType>) controllerBranch.getI2();
            var a1Var = i2.getVariables().stream().filter(v -> v.getType() == AcVariableType.BRANCH_ALPHA1).findFirst().orElseThrow();
            return i2.der(a1Var) > 0;
        }
    }
}
