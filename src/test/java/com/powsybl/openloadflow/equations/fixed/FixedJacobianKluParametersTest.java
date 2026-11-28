/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.KluParameters;
import com.powsybl.math.matrix.SparseMatrix;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class FixedJacobianKluParametersTest {

    /** Keeps the sparse matrices it creates. */
    private static final class KeepingMatrixFactory extends SparseMatrixFactory {

        private final List<SparseMatrix> matrices = new ArrayList<>();

        @Override
        public SparseMatrix create(int rowCount, int columnCount, int estimatedValueCount) {
            SparseMatrix matrix = super.create(rowCount, columnCount, estimatedValueCount);
            matrices.add(matrix);
            return matrix;
        }
    }

    private static double[] solve(KeepingMatrixFactory matrixFactory) {
        Network network = IeeeCdfNetworkFactory.create300();
        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(matrixFactory, new EvenShiloachGraphDecrementalConnectivityFactory<>()));
        assertTrue(runner.run(network, new LoadFlowParameters()).isFullyConverged());
        return network.getBusView().getBusStream().flatMapToDouble(b -> Stream.of(b.getV(), b.getAngle()).mapToDouble(Double::doubleValue)).toArray();
    }

    /** Runs with the given olf.klu.* properties only, those of the JVM put back after. */
    private static <T> T withProperties(Properties properties, Supplier<T> run) {
        Properties saved = new Properties();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(FixedJacobianKluParameters.PROPERTY_PREFIX)) {
                saved.setProperty(name, System.getProperty(name));
                System.clearProperty(name);
            }
        }
        properties.stringPropertyNames().forEach(name -> System.setProperty(name, properties.getProperty(name)));
        try {
            return run.get();
        } finally {
            properties.stringPropertyNames().forEach(System::clearProperty);
            saved.stringPropertyNames().forEach(name -> System.setProperty(name, saved.getProperty(name)));
        }
    }

    @Test
    void minimumFillForTheFixedJacobian() {
        KeepingMatrixFactory matrixFactory = new KeepingMatrixFactory();
        withProperties(new Properties(), () -> solve(matrixFactory));
        // the matrix of the fixed Jacobian takes the minimum fill, the other matrices the defaults
        assertTrue(matrixFactory.matrices.stream().anyMatch(m -> m.getKluParameters() != null
                && m.getKluParameters().getOrdering() == KluParameters.Ordering.MINIMUM_FILL
                && m.getKluParameters().getCheck() == KluParameters.DEFAULT_CHECK));
    }

    @Test
    void systemProperties() {
        Properties properties = new Properties();
        properties.setProperty("olf.klu.ordering", "amd");
        properties.setProperty("olf.klu.check", "rgrowth");
        properties.setProperty("olf.klu.scale", "0");
        properties.setProperty("olf.klu.btf", "false");
        properties.setProperty("olf.klu.tol", "0.01");
        properties.setProperty("olf.klu.rcondThreshold", "1e-9");
        KluParameters parameters = withProperties(properties, FixedJacobianKluParameters::create);
        assertEquals(KluParameters.Ordering.AMD, parameters.getOrdering());
        assertEquals(KluParameters.Check.RGROWTH, parameters.getCheck());
        assertEquals(0, parameters.getScale());
        assertFalse(parameters.isBtf());
        assertEquals(0.01, parameters.getTol());
        assertEquals(1e-9, parameters.getRcondThreshold());
    }

    @Test
    void sameSolutionWhateverTheParameters() {
        double[] expected = withProperties(new Properties(), () -> solve(new KeepingMatrixFactory()));
        for (String[] variant : new String[][] {{"olf.klu.ordering", "amd"}, {"olf.klu.ordering", "colamd"}, {"olf.klu.check", "rgrowth"},
                                                {"olf.klu.check", "none"}, {"olf.klu.btf", "false"}, {"olf.klu.scale", "0"}}) {
            Properties properties = new Properties();
            properties.setProperty(variant[0], variant[1]);
            double[] actual = withProperties(properties, () -> solve(new KeepingMatrixFactory()));
            assertArrayEquals(expected, actual, 1e-6, String.join("=", variant));
        }
    }
}
