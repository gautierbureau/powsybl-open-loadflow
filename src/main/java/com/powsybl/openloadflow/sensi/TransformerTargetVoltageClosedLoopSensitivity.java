/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.outerloop.IncrementalTransformerVoltageControlOuterLoop;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closed-loop transformer target-voltage sensitivity coefficients.
 *
 * <p>Given a converged AC state in which the transformer voltage controls are DISABLED — which is the only
 * state there is, because every {@code transformerVoltageControlMode} disables them before rounding the taps
 * — computes, per controlled bus, a weight {@code w[j]} on each controller branch's
 * {@link com.powsybl.openloadflow.ac.equations.AcEquationType#BRANCH_TARGET_RHO1} equation such that
 * perturbing that bus's voltage target by +1 is equivalent to perturbing the pinned ratios by {@code w}.
 *
 * <p>Plugging {@code w} into the standard RHS builder (one non-zero per controller branch's
 * {@code BRANCH_TARGET_RHO1} column) and running the transpose solve as usual then yields
 * {@code d(anything) / dV*} exactly, with the Jacobian left alone. This mirrors
 * {@link SvcPilotPointClosedLoopSensitivity}, which does the same for an SVC pilot point, and it is the
 * reason neither needs to rebuild or refactorise the equation system.
 *
 * <h2>The reduction</h2>
 *
 * <p>Two equation systems describe the same device. In the cached one (call it A) the ratio is pinned,
 * {@code rho_j = rho*_j}, and {@code rho*} is a parameter. In the control-active one (B) that row is replaced
 * by {@code V_c = V*}, plus {@code DISTR_RHO} rows tying a zone's ratios together when several transformers
 * share a controlled bus. The unknowns are identical; only those rows differ.
 *
 * <p>So B's solution is A's, evaluated at the {@code rho} that satisfies the control law. With
 * {@code M[z'][z] = dV_{c_z'} / drho_z} — the response of every controlled bus to moving zone {@code z}'s
 * ratios together, all computed in A — the law {@code V_c = V*} gives {@code drho = M^-1 dV*}, hence
 * {@code w[j] = (M^-1)[z(j)][z]} for the queried zone {@code z}. For the common case of one changer per
 * controlled bus, {@code M} is 1x1 and {@code w} is the scalar {@code 1 / (dV_c/drho)}.
 *
 * <p>{@code M} costs one multi-right-hand-side solve on the factorisation the load flow already left, reusing
 * {@link IncrementalTransformerVoltageControlOuterLoop.SensitivityContext} — the same matrix that outer loop
 * computes every pass, in production, to walk its taps.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class TransformerTargetVoltageClosedLoopSensitivity {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransformerTargetVoltageClosedLoopSensitivity.class);

    private TransformerTargetVoltageClosedLoopSensitivity() {
    }

    /**
     * Build the coordination once for the whole network and reuse it per queried controlled bus: {@code M} and
     * its factorisation do not depend on which bus is asked about.
     *
     * @return {@code null} when no transformer voltage control exists at all.
     */
    public static Coordination buildCoordination(AcLoadFlowContext context) {
        LfNetwork network = context.getNetwork();

        // Zones, keyed by controlled bus: a zone is one controlled bus and the changers regulating it. The
        // controls are disabled here (that is the state the load flow leaves), so membership is read from the
        // control objects, which survive, not from their enabled flags, which do not.
        Map<LfBus, List<LfBranch>> controllersByControlledBus = new LinkedHashMap<>();
        for (LfBranch branch : network.getBranches()) {
            if (branch.isDisabled()) {
                continue;
            }
            branch.getVoltageControl().ifPresent(vc ->
                    controllersByControlledBus.computeIfAbsent(vc.getControlledBus(), k -> new ArrayList<>()).add(branch));
        }
        if (controllersByControlledBus.isEmpty()) {
            return null;
        }

        List<LfBus> candidateBuses = new ArrayList<>(controllersByControlledBus.keySet());
        List<LfBranch> allControllers = controllersByControlledBus.values().stream().flatMap(List::stream).toList();

        // dV/drho for every (controller branch, controlled bus) pair: ONE multi-RHS solve on the existing factor.
        var sensitivities = new IncrementalTransformerVoltageControlOuterLoop.SensitivityContext(
                network, allControllers, context.getEquationSystem(), context.getJacobianMatrix());

        // Keep only the zones whose changers actually move their own bus. A zone with no authority contributes
        // a zero row AND a zero column, which makes M singular and takes every other zone down with it — on
        // rte6515, one such zone in 300 is enough. The incremental outer loop applies the same threshold for
        // the same reason: below it, the tap cannot realise a voltage target, so there is no closed loop to
        // reduce and the honest gradient is a (reported) zero.
        List<LfBus> controlledBuses = new ArrayList<>();
        List<LfBus> insensitiveBuses = new ArrayList<>();
        for (LfBus bus : candidateBuses) {
            double self = 0;
            for (LfBranch controller : controllersByControlledBus.get(bus)) {
                self += sensitivities.calculateSensitivityFromRToV(controller, bus);
            }
            if (Math.abs(self) < IncrementalTransformerVoltageControlOuterLoop.MIN_SENSI_FILTER) {
                insensitiveBuses.add(bus);
            } else {
                controlledBuses.add(bus);
            }
        }
        if (!insensitiveBuses.isEmpty()) {
            LOGGER.debug("{} transformer-regulated bus(es) below the |dV/drho| threshold, left out of the "
                    + "coordination", insensitiveBuses.size());
        }
        if (controlledBuses.isEmpty()) {
            return null;
        }

        int size = controlledBuses.size();
        DenseMatrix m = new DenseMatrix(size, size);
        for (int z = 0; z < size; z++) {
            // Column z: move every changer of zone z together, because DISTR_RHO ties them. For a
            // single-controller zone this is just that changer.
            for (LfBranch controller : controllersByControlledBus.get(controlledBuses.get(z))) {
                for (int zp = 0; zp < size; zp++) {
                    m.add(zp, z, sensitivities.calculateSensitivityFromRToV(controller, controlledBuses.get(zp)));
                }
            }
        }

        Map<LfBus, Integer> indexByControlledBus = new LinkedHashMap<>();
        for (int z = 0; z < size; z++) {
            indexByControlledBus.put(controlledBuses.get(z), z);
        }
        return new Coordination(controllersByControlledBus, controlledBuses, indexByControlledBus,
                m.decomposeLU(), size);
    }

    /** {@code M} factorised, plus the zone structure needed to turn a solve into per-branch weights. */
    public static final class Coordination implements AutoCloseable {

        private final Map<LfBus, List<LfBranch>> controllersByControlledBus;
        private final List<LfBus> controlledBuses;
        private final Map<LfBus, Integer> indexByControlledBus;
        private final LUDecomposition luM;
        private final int size;

        private Coordination(Map<LfBus, List<LfBranch>> controllersByControlledBus, List<LfBus> controlledBuses,
                             Map<LfBus, Integer> indexByControlledBus, LUDecomposition luM, int size) {
            this.controllersByControlledBus = controllersByControlledBus;
            this.controlledBuses = controlledBuses;
            this.indexByControlledBus = indexByControlledBus;
            this.luM = luM;
            this.size = size;
        }

        /** Whether this bus is regulated by a transformer at all. */
        public boolean covers(LfBus controlledBus) {
            return indexByControlledBus.containsKey(controlledBus);
        }

        /**
         * Whether this bus was left out of the coordination — either it is not transformer-regulated, or its
         * changers have too little authority over it for the reduction to mean anything (the threshold is the
         * one the incremental outer loop uses to give up on a changer; below it, dividing by {@code dV/drho}
         * amplifies noise rather than carrying a gradient).
         */
        public boolean isInsensitive(LfBus controlledBus) {
            return !indexByControlledBus.containsKey(controlledBus);
        }

        /**
         * {@code w[branch]}: the perturbation of each pinned ratio equivalent to +1 on this bus's voltage
         * target. Empty when the bus is not transformer-regulated.
         */
        public Map<LfBranch, Double> weightsForControlledBus(LfBus controlledBus) {
            Integer z = indexByControlledBus.get(controlledBus);
            if (z == null) {
                return Map.of();
            }
            // Solving M y = e_z gives the z-th column of M^-1, i.e. drho_{z'} / dV*_z for every zone z'.
            DenseMatrix rhs = new DenseMatrix(size, 1);
            rhs.set(z, 0, 1d);
            luM.solve(rhs);

            Map<LfBranch, Double> weights = new LinkedHashMap<>();
            for (int zp = 0; zp < size; zp++) {
                double w = rhs.get(zp, 0);
                if (w != 0d) {
                    for (LfBranch controller : controllersByControlledBus.get(controlledBuses.get(zp))) {
                        weights.put(controller, w);
                    }
                }
            }
            LOGGER.trace("Transformer target voltage at bus {} maps to {} ratio weight(s)",
                    controlledBus.getId(), weights.size());
            return weights;
        }

        @Override
        public void close() {
            luM.close();
        }
    }
}
