/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

/**
 * The decisions of {@link ReactiveLimitsOuterLoop}, as functions of numbers: which controlling bus switches to PQ and
 * at which target Q, which bus switched to PQ switches back or follows a limit that moved, which bus stays PV when
 * every one would switch, and which switch back the per-bus cap blocks.
 *
 * <p>The four rules ({@link #pvBusDecision}, {@link #resetsV}, {@link #frozenQ}, {@link #pqBusDecision}) are
 * expressions over primitives. The rest walks a {@link ReactiveLimitsVector}. Nothing reads or writes a network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class ReactiveLimitsDecisions {

    /** Nothing changes for the bus. */
    public static final int DECISION_NONE = 0;
    /** PV to PQ, target Q frozen at the minimum. */
    public static final int DECISION_TO_PQ_MIN_Q = 1;
    /** PV to PQ, target Q frozen at the maximum. */
    public static final int DECISION_TO_PQ_MAX_Q = 2;
    /** PV to PQ at the initial target Q: a remote controller whose own voltage is unrealistically low. */
    public static final int DECISION_TO_PQ_MIN_REALISTIC_V = 3;
    /** PV to PQ at the initial target Q: a remote controller whose own voltage is unrealistically high. */
    public static final int DECISION_TO_PQ_MAX_REALISTIC_V = 4;
    /** PQ back to PV. */
    public static final int DECISION_TO_PV = 5;
    /** Stays PQ, target Q frozen again at the limit, which moved. */
    public static final int DECISION_REFREEZE_AT_LIMIT = 6;
    /** Would switch back to PV, but already switched to PQ too many times: stays PQ. */
    public static final int DECISION_TO_PV_BLOCKED = 7;
    /** Would switch to PQ like every other controlling bus: the strongest one, it stays PV. */
    public static final int DECISION_KEPT_PV = 8;

    /** The bus is not at a limit. */
    public static final int LIMIT_NONE = 0;
    public static final int LIMIT_MIN_Q = 1;
    public static final int LIMIT_MAX_Q = 2;
    public static final int LIMIT_MIN_REALISTIC_V = 3;
    public static final int LIMIT_MAX_REALISTIC_V = 4;

    private ReactiveLimitsDecisions() {
    }

    /**
     * A bus whose control is on: it switches to PQ when its reactive power is outside its limits by more than the
     * tolerance or, for a remote controller in robust mode, when its own voltage is outside the realistic band.
     *
     * @param robustRemote robust mode, and the bus's voltage control controls another bus
     * @param vLow the realistic band's lower bound for the bus's own voltage
     * @param vHigh the realistic band's upper bound for the bus's own voltage
     */
    public static int pvBusDecision(double q, double minQ, double maxQ, double tol,
                                    boolean robustRemote, double v, double vLow, double vHigh) {
        return q < minQ - tol ? DECISION_TO_PQ_MIN_Q
             : q > maxQ + tol ? DECISION_TO_PQ_MAX_Q
             : robustRemote && v < vLow ? DECISION_TO_PQ_MIN_REALISTIC_V
             : robustRemote && v > vHigh ? DECISION_TO_PQ_MAX_REALISTIC_V
             : DECISION_NONE;
    }

    /** Whether a bus leaving PV has its voltage set back to 1 pu: a remote controller in robust mode, out of the band. */
    public static boolean resetsV(int decision, boolean robustRemote, double v, double vLow, double vHigh) {
        return decision != DECISION_NONE && robustRemote && (v < vLow || v > vHigh);
    }

    /** The target Q a switch to PQ freezes. */
    public static double frozenQ(int decision, double minQ, double maxQ, double initialTargetQ) {
        return decision == DECISION_TO_PQ_MIN_Q ? minQ
             : decision == DECISION_TO_PQ_MAX_Q ? maxQ
             : initialTargetQ;
    }

    public static boolean isMinLimit(int qLimitType) {
        return qLimitType == LIMIT_MIN_Q || qLimitType == LIMIT_MIN_REALISTIC_V;
    }

    /**
     * A bus switched to PQ at a limit ({@code qLimitType} is not {@link #LIMIT_NONE}): at a minimum it switches back
     * when the controlled voltage is below its target (more reactive power would raise it), at a maximum when it is
     * above. Otherwise, at a reactive limit that moved by more than the tolerance, it is frozen again at that limit.
     */
    public static int pqBusDecision(int qLimitType, double controlledV, double targetV, boolean canSwitchToPv,
                                    double minQ, double maxQ, double frozenTargetQ, double tol) {
        boolean atMin = isMinLimit(qLimitType);
        boolean wantsBack = atMin ? controlledV < targetV : controlledV > targetV;
        double limit = atMin ? minQ : maxQ;
        boolean limitMoved = (qLimitType == LIMIT_MIN_Q || qLimitType == LIMIT_MAX_Q) && Math.abs(limit - frozenTargetQ) > tol;
        return wantsBack && canSwitchToPv ? DECISION_TO_PV
             : limitMoved ? DECISION_REFREEZE_AT_LIMIT
             : DECISION_NONE;
    }

    public static boolean isSwitchToPq(int decision) {
        return decision >= DECISION_TO_PQ_MIN_Q && decision <= DECISION_TO_PQ_MAX_REALISTIC_V;
    }

    /** The limit type a switch to PQ leaves the bus at. The two share their numbering. */
    public static int limitTypeOf(int switchToPqDecision) {
        return switchToPqDecision;
    }

    /** The decision of entry {@code i}, from its inputs alone. */
    public static void decideEntry(ReactiveLimitsVector r, int i, double tol, boolean robust, double vLow, double vHigh) {
        if (r.controlEnabled[i]) {
            boolean robustRemote = robust && r.remoteController[i];
            int decision = pvBusDecision(r.q[i], r.minQ[i], r.maxQ[i], tol, robustRemote, r.v[i], vLow, vHigh);
            r.decision[i] = decision;
            r.resetV[i] = resetsV(decision, robustRemote, r.v[i], vLow, vHigh);
            r.qLimit[i] = frozenQ(decision, r.minQ[i], r.maxQ[i], r.initialTargetQ[i]);
        } else if (r.qLimitType[i] != LIMIT_NONE) {
            r.decision[i] = pqBusDecision(r.qLimitType[i], r.controlledV[i], r.targetV[i], r.canSwitchToPv[i],
                                          r.minQ[i], r.maxQ[i], r.frozenTargetQ[i], tol);
            r.resetV[i] = false;
            r.qLimit[i] = isMinLimit(r.qLimitType[i]) ? r.minQ[i] : r.maxQ[i];
        } else {
            r.decision[i] = DECISION_NONE;
            r.resetV[i] = false;
        }
    }

    /**
     * Every entry's decision. Entry by entry this is {@link #decideEntry}; an engine that decides entries one at a
     * time and applies {@code resetV} in between gets the same result unless a bus whose voltage is reset is the
     * controlled bus of a later entry, which then reads 1 pu.
     */
    public static void decideEntries(ReactiveLimitsVector r, double tol, boolean robust, double vLow, double vHigh) {
        for (int i = 0; i < r.count; i++) {
            decideEntry(r, i, tol, robust, vLow, vHigh);
        }
    }

    /** The entries with the given decision. */
    public static int count(ReactiveLimitsVector r, int decision) {
        int n = 0;
        for (int i = 0; i < r.count; i++) {
            if (r.decision[i] == decision) {
                n++;
            }
        }
        return n;
    }

    /** The controlling buses that switch to PQ. */
    public static int countSwitchesToPq(ReactiveLimitsVector r) {
        int n = 0;
        for (int i = 0; i < r.count; i++) {
            if (isSwitchToPq(r.decision[i])) {
                n++;
            }
        }
        return n;
    }

    /** The controlling buses that stay as they are. */
    public static int countRemainingPv(ReactiveLimitsVector r) {
        int n = 0;
        for (int i = 0; i < r.count; i++) {
            if (r.controlEnabled[i] && r.decision[i] == DECISION_NONE) {
                n++;
            }
        }
        return n;
    }

    /** Whether candidate {@code i} is stronger than candidate {@code j}: higher controlled nominal voltage, then
     *  higher target P, then lower id. */
    private static boolean isStronger(ReactiveLimitsVector r, int i, int j) {
        int c = Double.compare(-r.controlledNominalV[i], -r.controlledNominalV[j]);
        if (c != 0) {
            return c < 0;
        }
        c = Double.compare(-r.targetP[i], -r.targetP[j]);
        if (c != 0) {
            return c < 0;
        }
        return r.idRank[i] < r.idRank[j];
    }

    /**
     * Keeps one bus PV when every controlling bus would switch to PQ: the strongest of them, whose decision becomes
     * {@link #DECISION_KEPT_PV}. Reads {@code controlledNominalV}, {@code targetP} and {@code idRank} of the entries
     * that switch to PQ.
     *
     * @return the entry kept PV, -1 when no bus switches to PQ
     */
    public static int keepStrongestPv(ReactiveLimitsVector r) {
        int strongest = -1;
        for (int i = 0; i < r.count; i++) {
            if (isSwitchToPq(r.decision[i]) && (strongest < 0 || isStronger(r, i, strongest))) {
                strongest = i;
            }
        }
        if (strongest >= 0) {
            r.decision[strongest] = DECISION_KEPT_PV;
        }
        return strongest;
    }

    /** Blocks the switches back to PV of the buses that already switched to PQ {@code maxPqPvSwitch} times. */
    public static void blockSwitchesToPv(ReactiveLimitsVector r, int maxPqPvSwitch) {
        for (int i = 0; i < r.count; i++) {
            if (r.decision[i] == DECISION_TO_PV && r.pvPqSwitchCount[i] >= maxPqPvSwitch) {
                r.decision[i] = DECISION_TO_PV_BLOCKED;
            }
        }
    }

    /** Whether the decisions change the network's equations or targets, so that the solver must run again. */
    public static boolean changesNetwork(ReactiveLimitsVector r) {
        for (int i = 0; i < r.count; i++) {
            int decision = r.decision[i];
            if (isSwitchToPq(decision) || decision == DECISION_TO_PV || decision == DECISION_REFREEZE_AT_LIMIT) {
                return true;
            }
        }
        return false;
    }
}
