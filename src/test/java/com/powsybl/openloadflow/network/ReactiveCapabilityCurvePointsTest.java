/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ReactiveCapabilityCurve;
import com.powsybl.iidm.network.ReactiveCapabilityCurveAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The limits read from the points as arrays are those IIDM computes, to the last bit.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ReactiveCapabilityCurvePointsTest {

    private static ReactiveCapabilityCurve curve(Generator generator, double[][] points) {
        ReactiveCapabilityCurveAdder adder = generator.newReactiveCapabilityCurve();
        for (double[] point : points) {
            adder.beginPoint().setP(point[0]).setMinQ(point[1]).setMaxQ(point[2]).endPoint();
        }
        adder.add();
        return generator.getReactiveLimits(ReactiveCapabilityCurve.class);
    }

    private static void assertSameLimits(ReactiveCapabilityCurve curve, List<Double> activePowers) {
        ReactiveCapabilityCurvePoints points = ReactiveCapabilityCurvePoints.of(curve);
        for (boolean extrapolate : new boolean[] {false, true}) {
            for (double p : activePowers) {
                assertEquals(Double.doubleToRawLongBits(curve.getMinQ(p, extrapolate)), Double.doubleToRawLongBits(points.getMinQ(p, extrapolate)),
                    () -> "minQ at " + p + ", extrapolate " + extrapolate);
                assertEquals(Double.doubleToRawLongBits(curve.getMaxQ(p, extrapolate)), Double.doubleToRawLongBits(points.getMaxQ(p, extrapolate)),
                    () -> "maxQ at " + p + ", extrapolate " + extrapolate);
            }
        }
    }

    private static List<Double> activePowersAround(double[][] points, Random random) {
        List<Double> activePowers = new ArrayList<>(List.of(0.0, -0.0));
        TreeSet<Double> sorted = new TreeSet<>();
        for (double[] point : points) {
            sorted.add(point[0]);
            activePowers.add(point[0]);
            activePowers.add(Math.nextUp(point[0]));
            activePowers.add(Math.nextDown(point[0]));
        }
        double first = sorted.first();
        double last = sorted.last();
        for (int i = 0; i < 50; i++) {
            activePowers.add(first + (last - first) * random.nextDouble()); // inside
            activePowers.add(first - 100 * random.nextDouble()); // below
            activePowers.add(last + 100 * random.nextDouble()); // above
        }
        return activePowers;
    }

    @Test
    void testRandomCurves() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        Random random = new Random(20261119L);
        for (int c = 0; c < 500; c++) {
            int count = 2 + random.nextInt(6);
            double[][] points = new double[count][];
            double p = -200 + 100 * random.nextDouble();
            for (int i = 0; i < count; i++) {
                p += random.nextBoolean() ? 1 + random.nextInt(50) : 0.1 + 50 * random.nextDouble(); // whole and fractional powers
                double minQ = -300 + 250 * random.nextDouble();
                double maxQ = minQ + 300 * random.nextDouble();
                points[i] = new double[] {p, minQ, maxQ};
            }
            if (random.nextBoolean()) { // points given in any order
                List<double[]> shuffled = new ArrayList<>(List.of(points));
                Collections.shuffle(shuffled, random);
                points = shuffled.toArray(new double[0][]);
            }
            assertSameLimits(curve(generator, points), activePowersAround(points, random));
        }
    }

    @Test
    void testIntersectingLimitsWhenExtrapolated() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        // the limits close in above the last point and below the first one
        double[][] points = {{0, -100, 100}, {100, -50, 50}, {200, -10, 10}};
        ReactiveCapabilityCurve curve = curve(generator, points);
        assertSameLimits(curve, List.of(250.0, 300.0, 1000.0, -1000.0, -0.0));
        ReactiveCapabilityCurvePoints curvePoints = ReactiveCapabilityCurvePoints.of(curve);
        assertEquals(curvePoints.getMinQ(1000, true), curvePoints.getMaxQ(1000, true));
    }

    @Test
    void testZeroPoint() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        double[][] points = {{-0.0, -100, 100}, {100, -50, 50}};
        assertSameLimits(curve(generator, points), List.of(0.0, -0.0, 50.0));
    }

    /** The points a generator holds are the curve's, as arrays, and its limits are read from them. */
    @Test
    void testGeneratorPoints() {
        Network network = EurostagTutorialExample1Factory.create();
        Generator generator = network.getGenerator("GEN");
        double[][] points = {{0, -100, 100}, {200, -50, 60}};
        curve(generator, points);
        LfNetwork lfNetwork = com.powsybl.openloadflow.network.impl.Networks.load(network, new LfNetworkParameters()).getFirst();
        LfGenerator lfGenerator = lfNetwork.getGeneratorById("GEN");
        ReactiveCapabilityCurvePoints curvePoints = lfGenerator.getReactiveCapabilityCurvePoints().orElseThrow();
        assertArrayEquals(new double[] {0, 200}, curvePoints.getP());
        assertArrayEquals(new double[] {-100, -50}, curvePoints.getMinQ());
        assertArrayEquals(new double[] {100, 60}, curvePoints.getMaxQ());
        double activePower = lfGenerator.getTargetP();
        assertEquals(curvePoints.getMinQ(activePower * com.powsybl.openloadflow.util.PerUnit.SB, lfGenerator.isExtrapolateReactiveLimits())
                / com.powsybl.openloadflow.util.PerUnit.SB, lfGenerator.getMinQ());
        // a generator with min max limits has no points
        network.getGenerator("GEN").newMinMaxReactiveLimits().setMinQ(-10).setMaxQ(10).add();
        LfNetwork minMax = com.powsybl.openloadflow.network.impl.Networks.load(network, new LfNetworkParameters()).getFirst();
        assertTrue(minMax.getGeneratorById("GEN").getReactiveCapabilityCurvePoints().isEmpty());
    }
}
