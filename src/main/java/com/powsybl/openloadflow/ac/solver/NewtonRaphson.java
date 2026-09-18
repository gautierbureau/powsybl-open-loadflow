/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.math.matrix.MatrixException;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfSynchronousNetwork;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import com.powsybl.openloadflow.util.Reports;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class NewtonRaphson extends AbstractAcSolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewtonRaphson.class);

    protected final NewtonRaphsonParameters parameters;

    public NewtonRaphson(LfNetwork network, NewtonRaphsonParameters parameters,
                         EquationSystem<AcVariableType, AcEquationType> equationSystem,
                         JacobianMatrix<AcVariableType, AcEquationType> j,
                         TargetVector<AcVariableType, AcEquationType> targetVector,
                         EquationVector<AcVariableType, AcEquationType> equationVector,
                         boolean detailedReport) {
        super(network, equationSystem, j, targetVector, equationVector, detailedReport);
        this.parameters = Objects.requireNonNull(parameters);
    }

    @Override
    public String getName() {
        return "Newton-Raphson";
    }

    private static boolean dxDumped;
    private static boolean eqDumped;

    /** OLF_EQ_DUMP=&lt;path&gt;: the equation system OLF is about to solve, as text, once per JVM.
     *  The device's row layout is a per-scenario overlay on a single base system, so "which equation
     *  is at this row" is the one question a residual cannot answer on its own — and OLF will simply
     *  print it. Inactive equations included: a row the device assembles and OLF has deactivated is
     *  exactly the kind of mismatch this is for. */
    private void dumpEquations(String path) {
        eqDumped = true;
        try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(path))) {
            w.println("=== variables (row -> variable)");
            for (var v : equationSystem.getIndex().getSortedVariablesToFind()) {
                w.printf("var row=%d type=%s elementNum=%d%n", v.getRow(), v.getType(), v.getElementNum());
            }
            w.println("=== equations (column -> equation)");
            int nCols = equationSystem.getIndex().getSortedVariablesToFind().size();
            for (int c = 0; c < nCols; c++) {
                var eq = equationSystem.getIndex().getEquationAtColumn(c);
                var el = eq == null ? null
                        : network.getElement(eq.getType().getElementType(), eq.getElementNum());
                w.printf("eq col=%d type=%s elementNum=%d id=%s%n", c,
                        eq == null ? "?" : eq.getType().toString(),
                        eq == null ? -1 : eq.getElementNum(), el == null ? "?" : el.getId());
            }
            w.println("=== writeToString(true)");
            w.println(equationSystem.writeToString(true));
        } catch (java.io.IOException e) {
            System.err.println("OLF_EQ_DUMP failed: " + e);
        }
        System.err.println("OLF_EQ_DUMP wrote " + path);
    }

    private void dumpFirstStep(String path, double[] fx, double[] dx) {
        com.powsybl.math.matrix.Matrix m = j.getMatrix();
        if (!(m instanceof com.powsybl.math.matrix.SparseMatrix sm)) {
            System.err.println("OLF_NR_DX_DUMP: not a SparseMatrix, skipped");
            return;
        }
        int[] colStart = sm.getColumnStart();
        int[] rowIdx = sm.getRowIndices();
        double[] vals = sm.getValues();
        try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter(path)))) {
            w.printf("N %d %d%n", fx.length, vals.length);
            for (int i = 0; i < fx.length; i++) {
                w.printf("F %d %.17e%n", i, fx[i]);
                w.printf("DX %d %.17e%n", i, dx[i]);
            }
            for (int c = 0; c < sm.getColumnCount(); c++) {
                int a = colStart[c];
                int b = colStart[c + 1];
                if (a < 0 || b < 0) {
                    continue;
                }
                for (int k = a; k < b; k++) {
                    // (varRow, eqCol, value) — the device writes (eqRow, varCol, value), i.e. the
                    // transpose, so the comparison script swaps one of the two.
                    w.printf("J %d %d %.17e%n", rowIdx[k], c, vals[k]);
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("OLF_NR_DX_DUMP failed: " + e);
        }
        System.err.printf("OLF_NR_DX_DUMP wrote %s (n=%d nnz=%d)%n", path, fx.length, vals.length);
    }

    private AcSolverStatus runIteration(StateVectorScaling svScaling, MutableInt iterations, ReportNode reportNode) {
        LOGGER.debug("Start iteration {}", iterations);

        try {
            // create iteration report
            // - add 1 to iteration so that it starts at 1 instead of 0
            ReportNode iterationReportNode = detailedReport ? Reports.createAcMismatchReporter(reportNode, iterations.intValue() + 1) : null;

            // OLF_NR_DX_DUMP=<path>: the FIRST Newton step of the FIRST solve, written out whole —
            // the mismatch F it starts from, the assembled Jacobian as triplets, and the step dx it
            // produces. The device writes the same three under OLF_GPU_DX_DUMP. In OLF's TRANSPOSED
            // storage the matrix ROW is the variable and the COLUMN is the equation, so a triplet is
            // (varRow, eqCol, dF_eqCol/dx_varRow); the device stores the transpose of that. dx is
            // indexed by variable and compares term by term across the arms with no mapping at all.
            double[] dumpF = null;
            boolean doDump = System.getenv("OLF_NR_DX_DUMP") != null && iterations.intValue() == 0 && !dxDumped;
            if (doDump) {
                dumpF = equationVector.getArray().clone();
            }
            // solve f(x) = j * dx
            try {
                j.solveTransposed(equationVector.getArray());
            } catch (MatrixException e) {
                LOGGER.error(e.toString(), e);
                Reports.reportAcSolverError(reportNode, getName(), e.toString());
                return AcSolverStatus.SOLVER_FAILED;
            }
            // f(x) now contains dx
            if (doDump) {
                dxDumped = true;
                dumpFirstStep(System.getenv("OLF_NR_DX_DUMP"), dumpF, equationVector.getArray());
            }

            svScaling.apply(equationVector.getArray(), equationSystem, iterationReportNode);

            // update x and f(x) will be automatically updated
            equationSystem.getStateVector().minus(equationVector.getArray());

            // subtract targets from f(x)
            equationVector.minus(targetVector);
            // f(x) now contains equation mismatches

            if (LOGGER.isTraceEnabled()) {
                findLargestMismatches(equationSystem, equationVector.getArray(), 5)
                        .forEach(e -> {
                            Equation<AcVariableType, AcEquationType> equation = e.getKey();
                            String elementId = network.getElement(equation.getType().getElementType(), equation.getElementNum()).getId();
                            LOGGER.trace("Mismatch for {}: {} (element={})", equation, e.getValue(), elementId);
                        });
            }

            // test stopping criteria
            NewtonRaphsonStoppingCriteria.TestResult testResult = parameters.getStoppingCriteria().test(equationVector.getArray(), equationSystem);

            testResult = svScaling.applyAfter(equationSystem, equationVector, targetVector,
                                              parameters.getStoppingCriteria(), testResult,
                                              iterationReportNode);

            return reportAndReturnStatus(LOGGER, testResult, iterationReportNode);
        } finally {
            iterations.increment();
        }
    }

    @Override
    public AcSolverResult run(VoltageInitializer voltageInitializer, ReportNode reportNode) {
        if (System.getenv("OLF_EQ_DUMP") != null && !eqDumped) {
            dumpEquations(System.getenv("OLF_EQ_DUMP"));
        }
        // initialize state vector
        AcSolverUtil.initStateVector(network, equationSystem, voltageInitializer);

        Vectors.minus(equationVector.getArray(), targetVector.getArray());

        NewtonRaphsonStoppingCriteria.TestResult initialTestResult = parameters.getStoppingCriteria().test(equationVector.getArray(), equationSystem);
        StateVectorScaling svScaling = StateVectorScaling.fromMode(parameters, initialTestResult);

        LOGGER.debug("|f(x0)|={}", initialTestResult.getNorm());

        ReportNode initialReportNode = detailedReport ? Reports.createAcMismatchReporter(reportNode, 0) : null;
        if (detailedReport) {
            Reports.reportSolverNorm(initialReportNode, initialTestResult.getNorm());
        }
        if (detailedReport || LOGGER.isTraceEnabled()) {
            reportAndLogLargestMismatchByAcEquationType(initialReportNode, equationSystem, equationVector.getArray(), LOGGER);
        }

        if (System.getenv("OLF_NR_TRACE") != null) {
            // The residual this solve STARTS from, before any step. Two implementations that agree on
            // the base state, the contingency and the pre-distribution must enter the first
            // post-contingency solve at the SAME initial mismatch; a difference here is a difference in
            // the targets or in what was disabled, not in the solving.
            double ssq0 = 0;
            double inf0 = 0;
            for (double v : equationVector.getArray()) {
                ssq0 += v * v;
                inf0 = Math.max(inf0, Math.abs(v));
            }
            System.err.printf("NR_ENTER ||F||2=%.17e ||F||inf=%.17e%n", Math.sqrt(ssq0), inf0);
        }
        // start iterations
        AcSolverStatus status = AcSolverStatus.NO_CALCULATION;
        MutableInt iterations = new MutableInt();
        while (iterations.getValue() <= parameters.getMaxIterations()) {
            // OLF_NR_ITER_TRACE=1: the residual at the START of every iteration, the CPU counterpart of
            // the device's per-iteration BATCH_TRACE. Comparing the two SEQUENCES is the only way to see
            // WHERE two implementations part company; the exit values alone say only that they did.
            // OLF_NR_PROBE_DUMMYQ=<branchId> adds that branch's DUMMY_Q, by VARIABLE IDENTITY rather
            // than row index — the two arms number their rows differently, so an index is not portable.
            if (System.getenv("OLF_NR_ITER_TRACE") != null) {
                double ssq = 0;
                double inf = 0;
                for (double v : equationVector.getArray()) {
                    ssq += v * v;
                    inf = Math.max(inf, Math.abs(v));
                }
                String probe = "";
                String probeBranch = System.getenv("OLF_NR_PROBE_DUMMYQ");
                if (probeBranch != null) {
                    var br = network.getBranchById(probeBranch);
                    if (br != null) {
                        var dq = equationSystem.getVariableSet()
                                .getVariable(br.getNum(), AcVariableType.DUMMY_Q);
                        var dp = equationSystem.getVariableSet()
                                .getVariable(br.getNum(), AcVariableType.DUMMY_P);
                        probe = String.format(" dummyQ=%.9f(row=%d) dummyP=%.9f(row=%d)",
                                dq != null && dq.getRow() >= 0 ? equationSystem.getStateVector().get(dq.getRow()) : Double.NaN,
                                dq == null ? -1 : dq.getRow(),
                                dp != null && dp.getRow() >= 0 ? equationSystem.getStateVector().get(dp.getRow()) : Double.NaN,
                                dp == null ? -1 : dp.getRow());
                    }
                }
                // The WORST row's identity, not just its magnitude. A solve that enters at a
                // residual the other arm does not have is carrying a target the other arm did not
                // set, and the only way to say WHICH is to name the equation and its element.
                int argw = -1;
                double[] fa = equationVector.getArray();
                for (int i = 0; i < fa.length; i++) {
                    if (Math.abs(fa[i]) >= inf) {
                        argw = i;
                        break;
                    }
                }
                String worst = "";
                if (argw >= 0) {
                    var eqw = equationSystem.getIndex().getEquationAtColumn(argw);
                    var elw = eqw == null ? null
                            : network.getElement(eqw.getType().getElementType(), eqw.getElementNum());
                    worst = String.format(" worstRow=%d eq=%s elem=%d id=%s target=%.17e", argw,
                            eqw == null ? "?" : eqw.getType().toString(),
                            eqw == null ? -1 : eqw.getElementNum(),
                            elw == null ? "?" : elw.getId(),
                            targetVector.getArray()[argw]);
                }
                System.err.printf("NR_ITER it=%d ||F||2=%.17e ||F||inf=%.17e%s%s%n",
                        iterations.getValue(), Math.sqrt(ssq), inf, probe, worst);
            }
            AcSolverStatus newStatus = runIteration(svScaling, iterations, reportNode);
            if (newStatus != null) {
                status = newStatus;
                break;
            }
        }

        if (iterations.getValue() >= parameters.getMaxIterations()) {
            status = AcSolverStatus.MAX_ITERATION_REACHED;
        }

        if (status == AcSolverStatus.CONVERGED || parameters.isAlwaysUpdateNetwork()) {
            AcSolverUtil.updateNetwork(network, equationSystem);
        }

        if (System.getenv("OLF_NR_TRACE") != null) {
            // How DEEP this solve converged, not just that it did. The slack bus absorbs the
            // network's ACCUMULATED imbalance, so two states that both satisfy the criterion can
            // still report slack mismatches an order of magnitude apart.
            double ssq = 0;
            double inf = 0;
            for (double v : equationVector.getArray()) {
                ssq += v * v;
                inf = Math.max(inf, Math.abs(v));
            }
            System.err.printf("NR_EXIT iters=%d status=%s ||F||2=%.17e ||F||inf=%.17e%n",
                    iterations.getValue(), status, Math.sqrt(ssq), inf);
        }
        Map<Integer, Double> slackBusActivePowerMismatch = new TreeMap<>();
        for (LfSynchronousNetwork lfScNetwork : network.getSynchronousNetworks()) {
            slackBusActivePowerMismatch.put(lfScNetwork.getNumSC(), lfScNetwork.getSlackBuses().stream().mapToDouble(LfBus::getMismatchP).sum());
        }

        return new AcSolverResult(status, iterations.getValue(), slackBusActivePowerMismatch);
    }
}
