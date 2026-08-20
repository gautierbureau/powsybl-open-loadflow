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
            if (Math.abs(self) < tolerance()) {
                insensitiveBuses.add(bus);
            } else {
                controlledBuses.add(bus);
            }
        }
        if (!insensitiveBuses.isEmpty()) {
            LOGGER.debug("{} transformer-regulated bus(es) below the |dV/drho| threshold, left out of the "
                    + "coordination", insensitiveBuses.size());
        }
        if (Boolean.parseBoolean(String.valueOf(System.getenv("OLF_DEBUG_TVC")))) {
            double[] selfs = candidateBuses.stream().mapToDouble(b -> {
                double v = 0;
                for (LfBranch c : controllersByControlledBus.get(b)) {
                    v += sensitivities.calculateSensitivityFromRToV(c, b);
                }
                return Math.abs(v);
            }).sorted().toArray();
            LOGGER.info("TVC-DEBUG candidates={} kept={} dropped={} |dV/drho| min={} p25={} median={} max={}",
                    candidateBuses.size(), controlledBuses.size(), insensitiveBuses.size(),
                    selfs.length == 0 ? "-" : selfs[0],
                    selfs.length == 0 ? "-" : selfs[selfs.length / 4],
                    selfs.length == 0 ? "-" : selfs[selfs.length / 2],
                    selfs.length == 0 ? "-" : selfs[selfs.length - 1]);
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

    /**
     * Below this {@code |dV/drho|} a zone is excluded from the coordination: its row and column are zero,
     * which makes {@code M} singular and takes every other zone down with it.
     *
     * <p>This is a SINGULARITY guard and nothing else. It was briefly
     * {@link IncrementalTransformerVoltageControlOuterLoop#MIN_SENSI_FILTER} (0.05), which is a different
     * quantity — the authority an outer loop demands before walking a tap is worth it — and using it here
     * discarded zones that are weak but perfectly differentiable. Measured against forward mode on
     * pegase9241 (299 zones), lowering it moved the agreement:</p>
     *
     * <pre>
     *   threshold   zones kept   levers zeroed   median rel   p90 rel
     *   0.05        253          14              2.02e-04     7.63e-02
     *   1e-2        264           3              1.13e-05     9.39e-03
     *   1e-3        269           0              2.27e-06     5.97e-03
     *   1e-9        269           0              2.27e-06     5.97e-03
     * </pre>
     *
     * <p>Flat below 1e-3 because the distribution is bimodal — about 30 zones sit at exactly zero and the
     * rest above 1e-3, with nothing in between — so anything in that range excludes the degenerate zones
     * and keeps every real one. 1e-3 is chosen over 1e-9 for margin against a zone that is not exactly zero
     * but still has no usable authority.</p>
     */
    static final double SINGULAR_ZONE_TOL = 1e-3;

    /** The drop threshold, overridable by {@code OLF_TVC_SENSI_TOL} for the sweep that measured it. */
    private static double tolerance() {
        String override = System.getenv("OLF_TVC_SENSI_TOL");
        return override == null ? SINGULAR_ZONE_TOL : Double.parseDouble(override);
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

        /** The zones, in the order {@link #index} numbers them. */
        public List<LfBus> controlledBuses() {
            return controlledBuses;
        }

        /** The changers regulating this bus — a zone's single degree of freedom, tied by {@code DISTR_RHO}. */
        public List<LfBranch> controllersOf(LfBus controlledBus) {
            return controllersByControlledBus.getOrDefault(controlledBus, List.of());
        }

        public int index(LfBus controlledBus) {
            return indexByControlledBus.get(controlledBus);
        }

        public int size() {
            return size;
        }

        /**
         * {@code g <- M^-T g}, in place.
         *
         * <p>This is the whole contraction, for every declared lever at once. With
         * {@code g_z = dObj/drho_z} read off the adjoint state, {@code theta_bar = M^-T g} — because the
         * closed-loop column for {@code V*_z} is {@code sum_z' (M^-1)[z'][z]} times zone {@code z'}'s ratio
         * column, and contracting that with lambda leaves exactly the transpose solve. Computing the columns
         * of {@code M^-1} one lever at a time and contracting them afterwards, as an earlier version did, is
         * the same arithmetic done k times over — forward mode, inside the reduction that exists to avoid it.
         */
        public void solveTransposed(double[] g) {
            luM.solveTransposed(g);
        }

        @Override
        public void close() {
            luM.close();
        }
    }
}
