/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.se.equations;

import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreationParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.OpenBranchSide1ActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.OpenBranchSide1ReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.OpenBranchSide2ActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.OpenBranchSide2ReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.ShuntCompensatorActiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.ShuntCompensatorReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.vector.AcBranchVector;
import com.powsybl.openloadflow.ac.equations.vector.AcNetworkVector;
import com.powsybl.openloadflow.ac.equations.vector.AcVariableIndex;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfShunt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Measurement equations that read the flows of closed branches, and their derivatives, from an
 * {@link AcNetworkVector} instead of evaluating one flow term per measurement.
 * <p>
 * The vector computes every closed branch's flows and derivatives once per state update, in one loop with a single
 * sine and cosine per branch. A branch flow measurement then costs an array read, and a bus injection a sum of
 * array reads over its incident branches, where the scalar equations of {@link MeasurementEquations} recompute the
 * same flow for the flow meter and for both incident injections, at each evaluation and at each derivative.
 * <p>
 * Voltage magnitudes, open-side branch terms and shunts stay scalar, as in the vectorized load flow. The values are
 * identical to {@link MeasurementEquations}, so both can be used interchangeably.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class VectorizedMeasurementEquations {

    private final VariableSet<AcVariableType> variableSet;
    private final StateVector stateVector;
    private final AcNetworkVector networkVector;
    private final AcBranchVector branchVector;

    /**
     * @param variableSet the variables of the estimation, every one of them already given its row
     * @param stateVector the state the estimation iterates on; each {@link StateVector#set} refreshes the vector
     */
    public VectorizedMeasurementEquations(LfNetwork network, VariableSet<AcVariableType> variableSet,
                                          StateVector stateVector) {
        this.variableSet = Objects.requireNonNull(variableSet);
        this.stateVector = Objects.requireNonNull(stateVector);
        networkVector = new AcNetworkVector(Objects.requireNonNull(network),
                AcVariableIndex.of(variableSet.getVariables(), stateVector), new AcEquationSystemCreationParameters());
        networkVector.startListening();
        networkVector.updateNetworkState(); // valid before the first state update too
        branchVector = networkVector.getBranchVector();
    }

    public MeasurementEquation busVoltage(LfBus bus) {
        return MeasurementEquations.busVoltage(bus, variableSet, stateVector);
    }

    public MeasurementEquation busInjection(LfBus bus, boolean active) {
        Objects.requireNonNull(bus);
        if (bus.getBranches().stream().anyMatch(this::skippedByVector)) {
            return MeasurementEquations.busInjection(bus, active, variableSet, stateVector);
        }
        List<BranchSide> closed = new ArrayList<>();
        List<SingleEquationTerm<AcVariableType, AcEquationType>> scalarTerms = new ArrayList<>();
        for (LfBranch branch : bus.getBranches()) {
            LfBus bus1 = branch.getBus1();
            LfBus bus2 = branch.getBus2();
            if (bus1 != null && bus2 != null) {
                int side = bus1.getNum() == bus.getNum() ? 1 : 2;
                closed.add(branchSide(branch, active ? MeasuredQuantity.BRANCH_P : MeasuredQuantity.BRANCH_Q, side));
            } else if (bus1 == null && bus2 != null && bus2.getNum() == bus.getNum()) {
                scalarTerms.add(active
                        ? new OpenBranchSide1ActiveFlowEquationTerm(branch, bus2, variableSet)
                        : new OpenBranchSide1ReactiveFlowEquationTerm(branch, bus2, variableSet));
            } else if (bus2 == null && bus1 != null && bus1.getNum() == bus.getNum()) {
                scalarTerms.add(active
                        ? new OpenBranchSide2ActiveFlowEquationTerm(branch, bus1, variableSet)
                        : new OpenBranchSide2ReactiveFlowEquationTerm(branch, bus1, variableSet));
            }
        }
        for (LfShunt shunt : shunts(bus)) {
            scalarTerms.add(active
                    ? new ShuntCompensatorActiveFlowEquationTerm(shunt, bus, variableSet)
                    : new ShuntCompensatorReactiveFlowEquationTerm(shunt, bus, variableSet, false));
        }
        scalarTerms.forEach(t -> t.setStateVector(stateVector));
        return new InjectionEquation(closed, scalarTerms);
    }

    public MeasurementEquation branchFlow(LfBranch branch, MeasuredQuantity quantity, int side) {
        Objects.requireNonNull(branch);
        if (side != 1 && side != 2) {
            throw new IllegalArgumentException("Branch side must be 1 or 2, was " + side);
        }
        if (branch.getBus1() == null || branch.getBus2() == null) {
            throw new IllegalArgumentException("Branch " + branch.getId()
                    + " is open at one side and carries no measurable flow at side " + side);
        }
        if (skippedByVector(branch)) {
            return MeasurementEquations.branchFlow(branch, quantity, side, variableSet, stateVector);
        }
        return new FlowEquation(branchSide(branch, quantity, side));
    }

    /**
     * A branch with both buses whose flows the vector does not compute (disabled, or disconnected at one side while
     * keeping its bus): its scalar terms are kept, so the equations stay identical to {@link MeasurementEquations}.
     */
    private boolean skippedByVector(LfBranch branch) {
        return branch.getBus1() != null && branch.getBus2() != null && !branchVector.isClosed(branch.getNum());
    }

    private BranchSide branchSide(LfBranch branch, MeasuredQuantity quantity, int side) {
        if (quantity != MeasuredQuantity.BRANCH_P && quantity != MeasuredQuantity.BRANCH_Q
                && quantity != MeasuredQuantity.BRANCH_I) {
            throw new IllegalArgumentException(quantity + " is not a branch quantity");
        }
        int bus1Num = branch.getBus1().getNum();
        int bus2Num = branch.getBus2().getNum();
        return new BranchSide(branch.getNum(), quantity, side,
                variableSet.getVariable(bus1Num, AcVariableType.BUS_V),
                variableSet.getVariable(bus2Num, AcVariableType.BUS_V),
                variableSet.getVariable(bus1Num, AcVariableType.BUS_PHI),
                variableSet.getVariable(bus2Num, AcVariableType.BUS_PHI));
    }

    private static List<LfShunt> shunts(LfBus bus) {
        List<LfShunt> shunts = new ArrayList<>(3);
        bus.getShunt().ifPresent(shunts::add);
        bus.getControllerShunt().ifPresent(shunts::add);
        bus.getSvcShunt().ifPresent(shunts::add);
        return shunts;
    }

    /**
     * One side of one closed branch, and the four variables its flow depends on.
     */
    private record BranchSide(int num, MeasuredQuantity quantity, int side,
                              Variable<AcVariableType> v1, Variable<AcVariableType> v2,
                              Variable<AcVariableType> ph1, Variable<AcVariableType> ph2) {
    }

    private double value(BranchSide s) {
        int b = s.num();
        return switch (s.quantity()) {
            case BRANCH_P -> s.side() == 1 ? branchVector.p1[b] : branchVector.p2[b];
            case BRANCH_Q -> s.side() == 1 ? branchVector.q1[b] : branchVector.q2[b];
            default -> s.side() == 1 ? branchVector.i1[b] : branchVector.i2[b];
        };
    }

    private double der(BranchSide s, Variable<AcVariableType> variable) {
        int b = s.num();
        boolean side1 = s.side() == 1;
        if (variable == s.v1()) {
            return switch (s.quantity()) {
                case BRANCH_P -> side1 ? branchVector.dp1dv1[b] : branchVector.dp2dv1[b];
                case BRANCH_Q -> side1 ? branchVector.dq1dv1[b] : branchVector.dq2dv1[b];
                default -> side1 ? networkVector.di1dv1(b) : networkVector.di2dv1(b);
            };
        } else if (variable == s.v2()) {
            return switch (s.quantity()) {
                case BRANCH_P -> side1 ? branchVector.dp1dv2[b] : branchVector.dp2dv2[b];
                case BRANCH_Q -> side1 ? branchVector.dq1dv2[b] : branchVector.dq2dv2[b];
                default -> side1 ? networkVector.di1dv2(b) : networkVector.di2dv2(b);
            };
        } else if (variable == s.ph1()) {
            return switch (s.quantity()) {
                case BRANCH_P -> side1 ? branchVector.dp1dph1[b] : branchVector.dp2dph1[b];
                case BRANCH_Q -> side1 ? branchVector.dq1dph1[b] : branchVector.dq2dph1[b];
                default -> side1 ? networkVector.di1dph1(b) : networkVector.di2dph1(b);
            };
        } else if (variable == s.ph2()) {
            return switch (s.quantity()) {
                case BRANCH_P -> side1 ? branchVector.dp1dph2[b] : branchVector.dp2dph2[b];
                case BRANCH_Q -> side1 ? branchVector.dq1dph2[b] : branchVector.dq2dph2[b];
                default -> side1 ? networkVector.di1dph2(b) : networkVector.di2dph2(b);
            };
        }
        return 0;
    }

    private final class FlowEquation implements MeasurementEquation {

        private final BranchSide branchSide;
        private final List<Variable<AcVariableType>> variables;

        private FlowEquation(BranchSide branchSide) {
            this.branchSide = branchSide;
            this.variables = List.of(branchSide.v1(), branchSide.v2(), branchSide.ph1(), branchSide.ph2());
        }

        @Override
        public double eval() {
            return value(branchSide);
        }

        @Override
        public Collection<Variable<AcVariableType>> variables() {
            return variables;
        }

        @Override
        public double der(Variable<AcVariableType> variable) {
            return VectorizedMeasurementEquations.this.der(branchSide, variable);
        }
    }

    private final class InjectionEquation implements MeasurementEquation {

        private final List<BranchSide> closed;
        private final List<SingleEquationTerm<AcVariableType, AcEquationType>> scalarTerms;
        private final Set<Variable<AcVariableType>> variables = new LinkedHashSet<>();

        private InjectionEquation(List<BranchSide> closed, List<SingleEquationTerm<AcVariableType, AcEquationType>> scalarTerms) {
            this.closed = closed;
            this.scalarTerms = scalarTerms;
            for (BranchSide s : closed) {
                variables.add(s.v1());
                variables.add(s.v2());
                variables.add(s.ph1());
                variables.add(s.ph2());
            }
            scalarTerms.forEach(t -> variables.addAll(t.getVariables()));
        }

        @Override
        public double eval() {
            double sum = 0;
            for (BranchSide s : closed) {
                sum += value(s);
            }
            for (SingleEquationTerm<AcVariableType, AcEquationType> term : scalarTerms) {
                sum += term.eval();
            }
            return sum;
        }

        @Override
        public Collection<Variable<AcVariableType>> variables() {
            return variables;
        }

        @Override
        public double der(Variable<AcVariableType> variable) {
            double sum = 0;
            for (BranchSide s : closed) {
                sum += VectorizedMeasurementEquations.this.der(s, variable);
            }
            for (SingleEquationTerm<AcVariableType, AcEquationType> term : scalarTerms) {
                if (term.getVariables().contains(variable)) {
                    sum += term.der(variable);
                }
            }
            return sum;
        }
    }
}
