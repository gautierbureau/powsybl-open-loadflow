/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static com.powsybl.openloadflow.ac.outerloop.ReactiveLimitsDecisions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The reference vectors of {@link ReactiveLimitsDecisions}: inputs and the result the Java gives, one call per line.
 * Another implementation of the same rules is checked against the file, call by call and bit for bit.
 *
 * <p>A line is {@code <function> <arguments> = <result>}: a double is the 16 hexadecimal digits of its bits, a
 * boolean is 0 or 1, an int is decimal. The test fails when the file is not what the Java gives now; run it with
 * {@code -Dolf.updateTestVectors=true} to write the file again after a deliberate change of a rule.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ReactiveLimitsDecisionsVectorsTest {

    private static final String RESOURCE = "/reactiveLimitsDecisionsVectors.txt";

    private static final double V_LOW = 0.5 * 1.02;
    private static final double V_HIGH = 2 / 1.02;

    private static String d(double value) {
        return String.format("%016x", Double.doubleToRawLongBits(value));
    }

    private static String b(boolean value) {
        return value ? "1" : "0";
    }

    private static void pvBus(StringBuilder out, double q, double minQ, double maxQ, double tol, boolean robustRemote, double v) {
        int decision = pvBusDecision(q, minQ, maxQ, tol, robustRemote, v, V_LOW, V_HIGH);
        out.append("pvBusDecision ").append(d(q)).append(' ').append(d(minQ)).append(' ').append(d(maxQ)).append(' ').append(d(tol))
                .append(' ').append(b(robustRemote)).append(' ').append(d(v)).append(' ').append(d(V_LOW)).append(' ').append(d(V_HIGH))
                .append(" = ").append(decision).append('\n');
    }

    private static void pqBus(StringBuilder out, int qLimitType, double controlledV, double targetV, boolean canSwitchToPv,
                              double minQ, double maxQ, double frozenTargetQ, double tol) {
        int decision = pqBusDecision(qLimitType, controlledV, targetV, canSwitchToPv, minQ, maxQ, frozenTargetQ, tol);
        out.append("pqBusDecision ").append(qLimitType).append(' ').append(d(controlledV)).append(' ').append(d(targetV)).append(' ')
                .append(b(canSwitchToPv)).append(' ').append(d(minQ)).append(' ').append(d(maxQ)).append(' ').append(d(frozenTargetQ))
                .append(' ').append(d(tol)).append(" = ").append(decision).append('\n');
    }

    /** Around each comparison of the rules: on it, one bit under, one bit over, far from it, and undefined. */
    private static String vectors() {
        StringBuilder out = new StringBuilder();
        double[][] limits = {{-1, 1}, {1, -1}, {0, 0}, {-Double.MAX_VALUE, Double.MAX_VALUE}, {Double.NaN, 1}};
        double[] tolerances = {1e-4, 0};
        double[] voltages = {1, V_LOW, Math.nextDown(V_LOW), V_HIGH, Math.nextUp(V_HIGH), Double.NaN};
        for (double[] limit : limits) {
            double minQ = limit[0];
            double maxQ = limit[1];
            for (double tol : tolerances) {
                double[] qs = {0, minQ - tol, Math.nextDown(minQ - tol), Math.nextUp(minQ - tol), maxQ + tol, Math.nextDown(maxQ + tol),
                    Math.nextUp(maxQ + tol), Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
                for (double q : qs) {
                    for (double v : voltages) {
                        pvBus(out, q, minQ, maxQ, tol, false, v);
                        pvBus(out, q, minQ, maxQ, tol, true, v);
                    }
                }
            }
        }
        for (int decision = DECISION_NONE; decision <= DECISION_KEPT_PV; decision++) {
            for (double v : voltages) {
                for (boolean robustRemote : new boolean[] {false, true}) {
                    out.append("resetsV ").append(decision).append(' ').append(b(robustRemote)).append(' ').append(d(v)).append(' ')
                            .append(d(V_LOW)).append(' ').append(d(V_HIGH)).append(" = ")
                            .append(b(resetsV(decision, robustRemote, v, V_LOW, V_HIGH))).append('\n');
                }
            }
            out.append("frozenQ ").append(decision).append(' ').append(d(-1)).append(' ').append(d(1)).append(' ').append(d(0.3))
                    .append(" = ").append(d(frozenQ(decision, -1, 1, 0.3))).append('\n');
            out.append("frozenQ ").append(decision).append(' ').append(d(-0.0)).append(' ').append(d(Double.MAX_VALUE)).append(' ')
                    .append(d(Double.NaN)).append(" = ").append(d(frozenQ(decision, -0.0, Double.MAX_VALUE, Double.NaN))).append('\n');
            out.append("isSwitchToPq ").append(decision).append(" = ").append(b(isSwitchToPq(decision))).append('\n');
        }
        for (int switchToPq = DECISION_TO_PQ_MIN_Q; switchToPq <= DECISION_TO_PQ_MAX_REALISTIC_V; switchToPq++) {
            out.append("limitTypeOf ").append(switchToPq).append(" = ").append(limitTypeOf(switchToPq)).append('\n');
        }
        double[][] comparedVoltages = {{1, 1}, {Math.nextDown(1), 1}, {Math.nextUp(1), 1}, {0.9, 1}, {1.1, 1}, {Double.NaN, 1}, {1, Double.NaN}};
        // minQ, maxQ, frozen target Q, tolerance
        double[][] frozen = {{-1, 1, -1, 1e-4}, {-1, 1, 1, 1e-4}, {-1.1, 1, -1, 1e-4}, {-1, 0.9, 1, 1e-4},
            {-1 - 1e-4, 1, -1, 1e-4}, {Math.nextDown(-1 - 1e-4), 1, -1, 1e-4}, {-1, Math.nextUp(1 + 1e-4), 1, 1e-4},
            {-1, 1, -1, 0}, {Math.nextDown(-1), Math.nextUp(1), -1, 0}, {Double.NaN, Double.NaN, 0.3, 1e-4}};
        for (int qLimitType = LIMIT_NONE; qLimitType <= LIMIT_MAX_REALISTIC_V; qLimitType++) {
            out.append("isMinLimit ").append(qLimitType).append(" = ").append(b(isMinLimit(qLimitType))).append('\n');
            for (double[] voltage : comparedVoltages) {
                for (double[] f : frozen) {
                    pqBus(out, qLimitType, voltage[0], voltage[1], true, f[0], f[1], f[2], f[3]);
                    pqBus(out, qLimitType, voltage[0], voltage[1], false, f[0], f[1], f[2], f[3]);
                }
            }
        }
        // and anywhere: the same seed gives the same doubles on every Java
        Random random = new Random(20261110L);
        for (int k = 0; k < 300; k++) {
            double minQ = -2 * random.nextDouble();
            double maxQ = 2 * random.nextDouble();
            double tol = k % 3 == 0 ? 0 : 1e-4 * random.nextDouble();
            pvBus(out, 6 * random.nextDouble() - 3, minQ, maxQ, tol, random.nextBoolean(), 3 * random.nextDouble());
            pqBus(out, random.nextInt(LIMIT_MAX_REALISTIC_V + 1), 0.9 + 0.2 * random.nextDouble(), 0.9 + 0.2 * random.nextDouble(),
                    random.nextBoolean(), minQ, maxQ, random.nextBoolean() ? minQ : 6 * random.nextDouble() - 3, tol);
        }
        return out.toString();
    }

    @Test
    void vectorsAreWhatTheJavaGives() throws IOException {
        String vectors = vectors();
        if (Boolean.getBoolean("olf.updateTestVectors")) {
            Files.writeString(Path.of("src", "test", "resources", RESOURCE.substring(1)), vectors, StandardCharsets.UTF_8);
            return;
        }
        String committed;
        try (InputStream is = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(is, "missing " + RESOURCE + ": run this test with -Dolf.updateTestVectors=true");
            committed = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] expected = committed.split("\n");
        String[] actual = vectors.split("\n");
        for (int i = 0; i < Math.min(expected.length, actual.length); i++) {
            assertEquals(expected[i], actual[i], "line " + (i + 1) + " of " + RESOURCE);
        }
        assertEquals(expected.length, actual.length, "number of lines of " + RESOURCE);
    }
}
