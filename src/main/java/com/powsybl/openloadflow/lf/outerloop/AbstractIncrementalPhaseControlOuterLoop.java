/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import com.powsybl.iidm.network.TwoSides;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.lf.AbstractLoadFlowParameters;
import com.powsybl.openloadflow.lf.LoadFlowContext;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;
import org.apache.commons.lang3.Range;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractIncrementalPhaseControlOuterLoop<V extends Enum<V> & Quantity,
                                                               E extends Enum<E> & Quantity,
                                                               P extends AbstractLoadFlowParameters,
                                                               C extends LoadFlowContext<V, E, P>,
                                                               O extends OuterLoopContext<V, E, P, C>>
        extends AbstractPhaseControlOuterLoop<V, E, P, C, O> {

    public static final int MAX_DIRECTION_CHANGE = 3;
    public static final int MAX_TAP_SHIFT = Integer.MAX_VALUE;
    public static final double MIN_TARGET_DEADBAND = PhaseControlDecisions.MIN_TARGET_DEADBAND; // 1 MW
    public static final double SENSI_EPS = PhaseControlDecisions.SENSI_EPS;
    public static final double PHASE_SHIFT_CROSS_IMPACT_MARGIN = 0.75;
    public static final String NAME = "IncrementalPhaseControl";
    protected final Logger logger;

    protected AbstractIncrementalPhaseControlOuterLoop(Logger logger) {
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public void initialize(O context) {
        var contextData = new IncrementalContextData();
        context.setData(contextData);

        List<LfBranch> controllerBranches = getControllerBranches(context.getNetwork());
        for (LfBranch controllerBranch : controllerBranches) {
            contextData.getControllersContexts().put(controllerBranch.getId(), new IncrementalContextData.ControllerContext(MAX_DIRECTION_CHANGE));
        }

        fixPhaseShifterNecessaryForConnectivity(context.getNetwork(), controllerBranches);
    }

    public static double getHalfTargetDeadband(TransformerPhaseControl phaseControl) {
        return PhaseControlDecisions.halfTargetDeadband(phaseControl.getTargetDeadband());
    }

    @Override
    public String getName() {
        return NAME;
    }

    public abstract static class AbstractSensitivityContext<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        private final List<LfBranch> controllerBranches;
        private final EquationSystem<V, E> equationSystem;
        private final JacobianMatrix<V, E> jacobianMatrix;
        private final int[] controllerBranchIndex;
        private DenseMatrix sensitivities;

        protected AbstractSensitivityContext(LfNetwork network, List<LfBranch> controllerBranches,
                                  EquationSystem<V, E> equationSystem,
                                  JacobianMatrix<V, E> jacobianMatrix) {
            this.controllerBranches = Objects.requireNonNull(controllerBranches);
            this.equationSystem = Objects.requireNonNull(equationSystem);
            this.jacobianMatrix = Objects.requireNonNull(jacobianMatrix);
            controllerBranchIndex = LfBranch.createIndex(network, controllerBranches);
        }

        private DenseMatrix getSensitivities() {
            if (sensitivities == null) {
                sensitivities = calculateSensitivityValues(controllerBranches, controllerBranchIndex, equationSystem, jacobianMatrix);
            }
            return sensitivities;
        }

        public abstract DenseMatrix calculateSensitivityValues(List<LfBranch> controllerBranches, int[] controllerBranchIndex,
                                                               EquationSystem<V, E> equationSystem,
                                                               JacobianMatrix<V, E> jacobianMatrix);

        private EquationTerm<V, E> getP1(LfBranch controlledBranch) {
            return (EquationTerm<V, E>) controlledBranch.getP1();
        }

        private EquationTerm<V, E> getP2(LfBranch controlledBranch) {
            return (EquationTerm<V, E>) controlledBranch.getP2();
        }

        protected double calculateSensitivityFromA2S(LfBranch controllerBranch, EquationTerm<V, E> s) {
            return s.calculateSensi(getSensitivities(), controllerBranchIndex[controllerBranch.getNum()]);
        }

        public double calculateSensitivityFromA2P(LfBranch controllerBranch, LfBranch controlledBranch, TwoSides controlledSide) {
            var p = controlledSide == TwoSides.ONE ? getP1(controlledBranch) : getP2(controlledBranch);
            return calculateSensitivityFromA2S(controllerBranch, p);
        }
    }

    /**
     * The active power controllers out of their target deadband move to the tap closest to the phase shift the
     * sensitivity asks for ({@link PhaseControlDecisions#incrementalDecision}). A controller is read, decided and moved
     * before the next one is read.
     */
    protected void checkActivePowerControlPhaseControls(AbstractSensitivityContext<V, E> sensitivityContext, IncrementalContextData contextData,
                                                           List<TransformerPhaseControl> activePowerControlPhaseControls,
                                                           List<DiscreteControllerChange> activePowerControlPstsThatChangedTap) {
        PhaseControlVector v = new PhaseControlVector();
        v.reset(activePowerControlPhaseControls.size());
        for (int k = 0; k < v.count; k++) {
            TransformerPhaseControl phaseControl = activePowerControlPhaseControls.get(k);
            LfBranch controllerBranch = phaseControl.getControllerBranch();
            LfBranch controlledBranch = phaseControl.getControlledBranch();
            gatherActivePowerControl(v, k, phaseControl);
            if (PhaseControlDecisions.needsSensitivity(false, v.value[k], v.target[k], v.halfTargetDeadband[k])) {
                v.sensitivity[k] = sensitivityContext.calculateSensitivityFromA2P(controllerBranch, controlledBranch, phaseControl.getControlledSide());
            }
            PhaseControlDecisions.decideIncrementalEntry(v, k);
            if (v.decision[k] == PhaseControlDecisions.DECISION_REACH_PHASE_SHIFT) {
                double da = v.phaseShift[k];
                logger.trace("Controlled branch '{}' active power is {} MW and out of target value {} MW (half deadband={} MW), a phase shift of {}° is required",
                        controlledBranch.getId(), v.value[k] * PerUnit.SB, v.target[k] * PerUnit.SB, v.halfTargetDeadband[k] * PerUnit.SB, Math.toDegrees(da));
                var controllerContext = contextData.getControllersContexts().get(controllerBranch.getId());
                PiModel piModel = controllerBranch.getPiModel();

                int oldTapPosition = piModel.getTapPosition();
                Range<Integer> tapPositionRange = piModel.getTapPositionRange();
                piModel.updateTapPositionToReachNewA1(da, MAX_TAP_SHIFT, controllerContext.getAllowedDirection())
                        .ifPresent(controllerContext::updateAllowedDirection);

                if (piModel.getTapPosition() != oldTapPosition) {
                    logger.debug("Controller branch '{}' change tap from {} to {} to reach active power target (full range: {})", controllerBranch.getId(),
                            oldTapPosition, piModel.getTapPosition(), tapPositionRange);
                    DiscreteControllerChange change = DiscreteControllerChange.ofTransformer(controllerBranch, oldTapPosition, piModel.getTapPosition());
                    activePowerControlPstsThatChangedTap.add(change);
                }
            }
        }
    }

    /** Entry {@code k}: an active power controller, its controlled active power, target and half deadband. */
    protected static void gatherActivePowerControl(PhaseControlVector v, int k, TransformerPhaseControl phaseControl) {
        LfBranch controlledBranch = phaseControl.getControlledBranch();
        v.branchNum[k] = phaseControl.getControllerBranch().getNum();
        v.limiter[k] = false;
        v.value[k] = (phaseControl.getControlledSide() == TwoSides.ONE ? controlledBranch.getP1() : controlledBranch.getP2()).eval();
        v.target[k] = phaseControl.getTargetValue();
        v.halfTargetDeadband[k] = getHalfTargetDeadband(phaseControl);
    }

}
