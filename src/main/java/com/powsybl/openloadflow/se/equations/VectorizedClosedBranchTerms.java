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
import com.powsybl.openloadflow.ac.equations.vector.AcBranchVector;
import com.powsybl.openloadflow.ac.equations.vector.AcNetworkVector;
import com.powsybl.openloadflow.ac.equations.vector.AcVariableIndex;
import com.powsybl.openloadflow.equations.AbstractElementEquationTerm;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;

import java.util.List;
import java.util.Objects;

/**
 * Closed branch flow terms read from an {@link AcNetworkVector} instead of evaluated term by term.
 * <p>
 * The vector computes the flows of every closed branch, and their derivatives, once per state update, in one loop
 * with a single sine and cosine per branch. A flow term then costs an array read, where the analytic terms of
 * {@link MeasurementEquations#closedBranchTerms} recompute the same flow for the flow meter and for the injection
 * meters at both ends of the branch, at each evaluation and at each derivative. The values are the same.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class VectorizedClosedBranchTerms implements MeasurementEquations.ClosedBranchTerms {

    private final VariableSet<AcVariableType> variableSet;
    private final AcNetworkVector networkVector;
    private final MeasurementEquations.ClosedBranchTerms analyticTerms;

    /**
     * @param variableSet the variables of the estimation, every one of them already given its row
     * @param stateVector the state the estimation iterates on; each {@link StateVector#set} refreshes the vector
     */
    public VectorizedClosedBranchTerms(LfNetwork network, VariableSet<AcVariableType> variableSet,
                                       StateVector stateVector) {
        this.variableSet = Objects.requireNonNull(variableSet);
        networkVector = new AcNetworkVector(Objects.requireNonNull(network),
                AcVariableIndex.of(variableSet.getVariables(), stateVector), new AcEquationSystemCreationParameters());
        networkVector.startListening();
        networkVector.updateNetworkState(); // valid before the first state update too
        analyticTerms = MeasurementEquations.closedBranchTerms(variableSet);
    }

    @Override
    public SingleEquationTerm<AcVariableType, AcEquationType> create(LfBranch branch, MeasuredQuantity quantity, int side) {
        // the vector skips a disabled branch or one disconnected at a side: keep the analytic term there
        return networkVector.getBranchVector().isClosed(branch.getNum())
                ? new VectorFlowTerm(branch, quantity, side)
                : analyticTerms.create(branch, quantity, side);
    }

    private final class VectorFlowTerm extends AbstractElementEquationTerm<LfBranch, AcVariableType, AcEquationType>
            implements MeasurementEquations.RowTerm {

        private final int side;
        // indexed by branch number: the flow, then its derivatives by v1, v2, ph1 and ph2; a current magnitude has
        // only its value here, the network vector deriving its derivatives on demand
        private final double[][] arrays;
        private final List<Variable<AcVariableType>> variables;

        private VectorFlowTerm(LfBranch branch, MeasuredQuantity quantity, int side) {
            super(branch);
            this.side = side;
            AcBranchVector v = networkVector.getBranchVector();
            boolean side1 = side == 1;
            arrays = switch (quantity) {
                case BRANCH_P -> side1 ? new double[][] {v.p1, v.dp1dv1, v.dp1dv2, v.dp1dph1, v.dp1dph2}
                                       : new double[][] {v.p2, v.dp2dv1, v.dp2dv2, v.dp2dph1, v.dp2dph2};
                case BRANCH_Q -> side1 ? new double[][] {v.q1, v.dq1dv1, v.dq1dv2, v.dq1dph1, v.dq1dph2}
                                       : new double[][] {v.q2, v.dq2dv1, v.dq2dv2, v.dq2dph1, v.dq2dph2};
                case BRANCH_I -> new double[][] {side1 ? v.i1 : v.i2};
                default -> throw new IllegalArgumentException(quantity + " is not a branch quantity");
            };
            int bus1 = branch.getBus1().getNum();
            int bus2 = branch.getBus2().getNum();
            variables = List.of(variableSet.getVariable(bus1, AcVariableType.BUS_V),
                    variableSet.getVariable(bus2, AcVariableType.BUS_V),
                    variableSet.getVariable(bus1, AcVariableType.BUS_PHI),
                    variableSet.getVariable(bus2, AcVariableType.BUS_PHI));
        }

        @Override
        public List<Variable<AcVariableType>> getVariables() {
            return variables;
        }

        @Override
        public double eval() {
            return arrays[0][element.getNum()];
        }

        @Override
        public double der(Variable<AcVariableType> variable) {
            int k = variables.indexOf(variable);
            if (k < 0) {
                throw new IllegalStateException("Unknown variable: " + variable);
            }
            return der(k, element.getNum());
        }

        @Override
        public void addDerivatives(double[] row, int[] positions) {
            int b = element.getNum();
            for (int k = 0; k < 4; k++) {
                row[positions[k]] += der(k, b);
            }
        }

        /**
         * The derivative by the k-th variable, of v1, v2, ph1 and ph2, of the flow of branch b.
         */
        private double der(int k, int b) {
            if (arrays.length > 1) {
                return arrays[k + 1][b];
            }
            boolean side1 = side == 1;
            return switch (k) {
                case 0 -> side1 ? networkVector.di1dv1(b) : networkVector.di2dv1(b);
                case 1 -> side1 ? networkVector.di1dv2(b) : networkVector.di2dv2(b);
                case 2 -> side1 ? networkVector.di1dph1(b) : networkVector.di2dph1(b);
                default -> side1 ? networkVector.di1dph2(b) : networkVector.di2dph2(b);
            };
        }

        @Override
        protected String getName() {
            return "se_vector_flow";
        }
    }
}
