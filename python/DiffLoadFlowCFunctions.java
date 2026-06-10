/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * SPDX-License-Identifier: MPL-2.0
 *
 * REFERENCE (pypowsybl-side) — NOT compiled in powsybl-open-loadflow.
 * This file belongs in the pypowsybl repository, e.g.
 *   java/pypowsybl/src/main/java/com/powsybl/python/loadflow/DiffLoadFlowCFunctions.java
 * It depends on the pypowsybl native bindings AND on the OLF adjoint helpers
 *   com.powsybl.openloadflow.ac.equations.ClassicVoltageControlVjp  (targetV)
 *   com.powsybl.openloadflow.ac.equations.LoadFlowAdjoint           (f_I / f_J, ratio, shunt B).
 *
 * It implements the @CEntryPoint s consumed by python/differentiable_olf_vjp.py:
 *   - runLoadFlowKeepContext  : classic PV/PQ AC LF at given setpoints/levers, keep the converged context
 *   - getControlledBusVoltages: read the monitored voltages (forward output, f_V)
 *   - getMonitoredBranchFlows : read the monitored branch currents and active flows (forward output, f_I/f_J)
 *   - loadFlowVjp             : active-set adjoint over f = f_V + f_I + f_J -> dL/dtheta for all levers
 *   - freeLoadFlowContext     : release the kept context
 * (run_keep_context in Python = runLoadFlowKeepContext + getControlledBusVoltages + getMonitoredBranchFlows.)
 *
 * Lever order exposed to Python (and the order of the returned theta_bar):
 *   [ generator targetV (controllers) ; transformer ratio (rtcBranches) ; shunt B (shuntBuses) ].
 */
package com.powsybl.python.loadflow;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.ClassicVoltageControlVjp;
import com.powsybl.openloadflow.ac.equations.LoadFlowAdjoint;
import com.powsybl.openloadflow.graph.NaiveGraphConnectivityFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.python.commons.CTypeUtil;
import com.powsybl.python.commons.PyPowsyblApiHeader;
import com.powsybl.python.commons.PyPowsyblApiHeader.ArrayPointer;
import com.powsybl.python.commons.Util;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.ObjectHandles;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CDoublePointer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * pypowsybl native entry points for the OpenLoadFlow {@code custom_vjp} (classic-solution active-set
 * adjoint) over the full tertiary-voltage-control objective {@code f = f_V + f_I + f_J}. See file header.
 */
public final class DiffLoadFlowCFunctions {

    private DiffLoadFlowCFunctions() {
    }

    /** Everything the backward pass needs, kept alive between the forward and the vjp calls. */
    private static final class DiffContext {
        final AcLoadFlowContext acContext;
        final LfNetwork lfNetwork;
        final List<LfBus> controllers;       // generator voltage-controlled buses (targetV levers)
        final List<LfBranch> rtcBranches;    // controllable transformer branches (ratio levers, control off)
        final List<LfBus> shuntBuses;        // controllable shunt buses (susceptance levers, control off)
        final List<LfBranch> monitoredBranches; // branches whose side-1 current/flow enter f_I / f_J

        DiffContext(AcLoadFlowContext acContext, LfNetwork lfNetwork, List<LfBus> controllers,
                    List<LfBranch> rtcBranches, List<LfBus> shuntBuses, List<LfBranch> monitoredBranches) {
            this.acContext = acContext;
            this.lfNetwork = lfNetwork;
            this.controllers = controllers;
            this.rtcBranches = rtcBranches;
            this.shuntBuses = shuntBuses;
            this.monitoredBranches = monitoredBranches;
        }
    }

    private static AcLoadFlowParameters acParameters() {
        // classic PV/PQ: reactive-limit outer loop ON (robust forward); transformer/shunt voltage control
        // OFF so the relaxed ratio / B values supplied from Python are honoured as fixed parameters.
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setTransformerVoltageControlOn(false)
                .setShuntCompensatorVoltageControlOn(false);
        return OpenLoadFlowParameters.createAcParameters(
                lfParameters,
                new OpenLoadFlowParameters(),
                new SparseMatrixFactory(),
                new NaiveGraphConnectivityFactory<>(LfBus::getNum),
                false,
                false);
    }

    // -------- forward: classic LF at the given setpoints/levers, keep the context --------

    @CEntryPoint(name = "runLoadFlowKeepContext")
    public static ObjectHandle runLoadFlowKeepContext(IsolateThread thread,
                                                      ObjectHandle networkHandle,
                                                      CCharPointerPointer controllerIdsPtr, int controllerCount,
                                                      CDoublePointer targetVPtr, int targetVCount,
                                                      CCharPointerPointer rtcBranchIdsPtr, int rtcCount,
                                                      CDoublePointer ratioPtr, int ratioCount,
                                                      CCharPointerPointer shuntBusIdsPtr, int shuntCount,
                                                      CDoublePointer shuntBPtr, int shuntBCount,
                                                      CCharPointerPointer monitoredBranchIdsPtr, int monitoredCount,
                                                      PyPowsyblApiHeader.ExceptionHandlerPointer exceptionHandlerPtr) {
        return Util.doCatch(exceptionHandlerPtr, () -> {
            Network network = ObjectHandles.getGlobal().get(networkHandle);
            List<String> controllerIds = CTypeUtil.toStringList(controllerIdsPtr, controllerCount);
            List<Double> targetV = CTypeUtil.toDoubleList(targetVPtr, targetVCount);
            List<String> rtcBranchIds = CTypeUtil.toStringList(rtcBranchIdsPtr, rtcCount);
            List<Double> ratio = CTypeUtil.toDoubleList(ratioPtr, ratioCount);
            List<String> shuntBusIds = CTypeUtil.toStringList(shuntBusIdsPtr, shuntCount);
            List<Double> shuntB = CTypeUtil.toDoubleList(shuntBPtr, shuntBCount);
            List<String> monitoredBranchIds = CTypeUtil.toStringList(monitoredBranchIdsPtr, monitoredCount);

            AcLoadFlowParameters parameters = acParameters();
            LfNetwork lf = LfNetwork.load(network, new LfNetworkLoaderImpl(), parameters.getNetworkParameters()).get(0);

            // generator voltage setpoints (per unit), in the Python-facing order
            List<LfBus> controllers = new ArrayList<>(controllerCount);
            for (int i = 0; i < controllerCount; i++) {
                LfBus bus = lf.getBusById(controllerIds.get(i));
                bus.getGeneratorVoltageControl().orElseThrow().setTargetValue(targetV.get(i));
                controllers.add(bus);
            }
            // relaxed transformer ratios (control off -> fixed continuous parameter)
            List<LfBranch> rtcBranches = new ArrayList<>(rtcCount);
            for (int i = 0; i < rtcCount; i++) {
                LfBranch branch = lf.getBranchById(rtcBranchIds.get(i));
                branch.getPiModel().setR1(ratio.get(i));
                rtcBranches.add(branch);
            }
            // relaxed shunt susceptances (control off -> fixed continuous parameter)
            List<LfBus> shuntBuses = new ArrayList<>(shuntCount);
            for (int i = 0; i < shuntCount; i++) {
                LfBus bus = lf.getBusById(shuntBusIds.get(i));
                bus.getShunt().orElseThrow().setB(shuntB.get(i));
                shuntBuses.add(bus);
            }
            List<LfBranch> monitoredBranches = new ArrayList<>(monitoredCount);
            for (String id : monitoredBranchIds) {
                monitoredBranches.add(lf.getBranchById(id));
            }

            AcLoadFlowContext acContext = new AcLoadFlowContext(lf, parameters);
            new AcloadFlowEngine(acContext).run(); // converges with the reactive-limit outer loop

            return ObjectHandles.getGlobal().create(
                    new DiffContext(acContext, lf, controllers, rtcBranches, shuntBuses, monitoredBranches));
        });
    }

    @CEntryPoint(name = "getControlledBusVoltages")
    public static ArrayPointer<CDoublePointer> getControlledBusVoltages(IsolateThread thread, ObjectHandle ctxHandle,
                                                                        PyPowsyblApiHeader.ExceptionHandlerPointer exceptionHandlerPtr) {
        return Util.doCatch(exceptionHandlerPtr, () -> {
            DiffContext c = ObjectHandles.getGlobal().get(ctxHandle);
            List<Double> v = new ArrayList<>(c.controllers.size());
            for (LfBus bus : c.controllers) {
                v.add(bus.getV()); // per unit
            }
            return Util.createDoubleArray(v);
        });
    }

    /** Monitored branch outputs for f_I / f_J: [I1(e) for e ...] followed by [P1(e) for e ...] (per unit). */
    @CEntryPoint(name = "getMonitoredBranchFlows")
    public static ArrayPointer<CDoublePointer> getMonitoredBranchFlows(IsolateThread thread, ObjectHandle ctxHandle,
                                                                       PyPowsyblApiHeader.ExceptionHandlerPointer exceptionHandlerPtr) {
        return Util.doCatch(exceptionHandlerPtr, () -> {
            DiffContext c = ObjectHandles.getGlobal().get(ctxHandle);
            List<Double> out = new ArrayList<>(2 * c.monitoredBranches.size());
            for (LfBranch branch : c.monitoredBranches) {
                out.add(branch.getI1().eval());
            }
            for (LfBranch branch : c.monitoredBranches) {
                out.add(branch.getP1().eval());
            }
            return Util.createDoubleArray(out);
        });
    }

    // -------- backward: x_bar from the f_V/f_I/f_J cotangents, then the active-set adjoint --------

    @CEntryPoint(name = "loadFlowVjp")
    public static ArrayPointer<CDoublePointer> loadFlowVjp(IsolateThread thread, ObjectHandle ctxHandle,
                                                           CDoublePointer vBarPtr, int vBarCount,
                                                           CDoublePointer iBarPtr, int iBarCount,
                                                           CDoublePointer pBarPtr, int pBarCount,
                                                           PyPowsyblApiHeader.ExceptionHandlerPointer exceptionHandlerPtr) {
        return Util.doCatch(exceptionHandlerPtr, () -> {
            DiffContext c = ObjectHandles.getGlobal().get(ctxHandle);
            List<Double> vBar = CTypeUtil.toDoubleList(vBarPtr, vBarCount);   // dL/dV   on controlled buses (f_V)
            List<Double> iBar = CTypeUtil.toDoubleList(iBarPtr, iBarCount);   // dL/dI1  on monitored branches (f_I)
            List<Double> pBar = CTypeUtil.toDoubleList(pBarPtr, pBarCount);   // dL/dP1  on monitored branches (f_J)
            var equationSystem = c.acContext.getEquationSystem();

            // x_bar = (d output / d x)^T . output_bar, assembled term by term:
            //   f_V : identity on each controlled bus's BUS_V row
            //   f_I : iBar_e * d I1_e / d x   (current-magnitude term derivatives)
            //   f_J : pBar_e * d P1_e / d x   (active-flow term derivatives)
            double[] xBar = new double[equationSystem.getIndex().getColumnCount()];
            for (int i = 0; i < c.controllers.size(); i++) {
                int vRow = equationSystem.getVariable(c.controllers.get(i).getNum(), AcVariableType.BUS_V).getRow();
                if (vRow != -1) {
                    xBar[vRow] += vBar.get(i);
                }
            }
            for (int e = 0; e < c.monitoredBranches.size(); e++) {
                LoadFlowAdjoint.accumulateCurrentMagnitudeCotangent(c.monitoredBranches.get(e), TwoSides.ONE, iBar.get(e), xBar);
                LoadFlowAdjoint.accumulateActivePowerCotangent(c.monitoredBranches.get(e), TwoSides.ONE, pBar.get(e), xBar);
            }

            // single adjoint solve Jᵀ λ = x_bar, reused for every lever
            double[] lambda = LoadFlowAdjoint.solveAdjoint(c.acContext, xBar);

            // theta_bar in lever order: [ generator targetV ; transformer ratio ; shunt B ]
            // (generator targetV reuses the active-set rule: lambda_v for PV, 0 for saturated/PQ)
            Map<LfBus, Double> targetVCotangents = ClassicVoltageControlVjp.computeTargetVoltageCotangents(c.acContext, xBar);
            List<Double> thetaBar = new ArrayList<>(c.controllers.size() + c.rtcBranches.size() + c.shuntBuses.size());
            for (LfBus bus : c.controllers) {
                thetaBar.add(targetVCotangents.getOrDefault(bus, 0.0));
            }
            for (LfBranch branch : c.rtcBranches) {
                thetaBar.add(LoadFlowAdjoint.ratioCotangent(c.acContext, branch, lambda));
            }
            for (LfBus bus : c.shuntBuses) {
                thetaBar.add(LoadFlowAdjoint.shuntSusceptanceCotangent(c.acContext, bus, lambda));
            }
            return Util.createDoubleArray(thetaBar);
        });
    }

    @CEntryPoint(name = "freeLoadFlowContext")
    public static void freeLoadFlowContext(IsolateThread thread, ObjectHandle ctxHandle,
                                           PyPowsyblApiHeader.ExceptionHandlerPointer exceptionHandlerPtr) {
        Util.doCatch(exceptionHandlerPtr, () -> {
            DiffContext c = ObjectHandles.getGlobal().get(ctxHandle);
            c.acContext.close();                       // dispose the factorised Jacobian / LU
            ObjectHandles.getGlobal().destroy(ctxHandle);
        });
    }
}
