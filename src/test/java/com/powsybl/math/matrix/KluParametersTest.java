/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.math.matrix;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class KluParametersTest {

    @BeforeAll
    static void checkAvailable() {
        assumeTrue(KluParameters.isAvailable(), "The native math library has no KLU parameters");
    }

    @AfterEach
    void restoreDefaults() {
        new KluParameters().apply();
    }

    private static double[] solve() {
        Network network = IeeeCdfNetworkFactory.create300();
        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>()));
        LoadFlowResult result = runner.run(network, new LoadFlowParameters());
        assertTrue(result.isFullyConverged());
        return network.getBusView().getBusStream().flatMapToDouble(b -> Stream.of(b.getV(), b.getAngle()).mapToDouble(Double::doubleValue)).toArray();
    }

    static Stream<Arguments> variants() {
        return Stream.of(
                Arguments.of(new KluParameters().setOrdering(KluParameters.Ordering.COLAMD)),
                Arguments.of(new KluParameters().setOrdering(KluParameters.Ordering.MINIMUM_FILL)),
                Arguments.of(new KluParameters().setBtf(false)),
                Arguments.of(new KluParameters().setScale(0)),
                Arguments.of(new KluParameters().setScale(-1)),
                Arguments.of(new KluParameters().setCheck(KluParameters.Check.RCOND)),
                Arguments.of(new KluParameters().setCheck(KluParameters.Check.NONE)),
                Arguments.of(new KluParameters().setOrdering(KluParameters.Ordering.MINIMUM_FILL).setBtf(false).setScale(0)
                        .setCheck(KluParameters.Check.RCOND)));
    }

    @ParameterizedTest
    @MethodSource("variants")
    void sameSolution(KluParameters parameters) {
        double[] expected = solve();
        parameters.apply();
        double[] actual = solve();
        assertArrayEquals(expected, actual, 1e-6, parameters.toString());
    }

    @Test
    void systemProperties() {
        System.setProperty("olf.klu.ordering", "minimum_fill");
        System.setProperty("olf.klu.check", "rcond");
        System.setProperty("olf.klu.scale", "0");
        try {
            KluParameters parameters = KluParameters.fromSystemProperties();
            assertEquals(KluParameters.Ordering.MINIMUM_FILL, parameters.getOrdering());
            assertEquals(KluParameters.Check.RCOND, parameters.getCheck());
            assertEquals(0, parameters.getScale());
            assertTrue(parameters.isBtf());
        } finally {
            System.clearProperty("olf.klu.ordering");
            System.clearProperty("olf.klu.check");
            System.clearProperty("olf.klu.scale");
        }
        assertThrows(IllegalArgumentException.class, () -> new KluParameters().setScale(3));
    }
}
