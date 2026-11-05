/**
 * Temporary harness (not for commit): standalone main so the JVM flags are fully
 * under our control (surefire overrides argLine), used to record a JFR profile
 * with old-object sampling and find what retains the growing objects.
 */
package com.powsybl.openloadflow;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;

import java.lang.management.ManagementFactory;

public final class LeakMain {

    private LeakMain() {
    }

    public static void main(String[] args) throws Exception {
        boolean cache = Boolean.parseBoolean(System.getProperty("olf.it.cache", "true"));
        int iterations = Integer.parseInt(System.getProperty("olf.it.iterations", "4000"));

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new SparseMatrixFactory()));
        Network network = IeeeCdfNetworkFactory.create300();
        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters).setNetworkCacheEnabled(cache);

        Load load = network.getLoads().iterator().next();
        double baseP0 = load.getP0();

        for (int i = 0; i < iterations; i++) {
            load.setP0(baseP0 * (1.0 + 0.001 * (i % 50)));
            runner.run(network, parameters);
            if (i % 500 == 0 || i == iterations - 1) {
                System.gc();
                System.out.println("MEASURE iter=" + i + " heapKb="
                        + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024);
            }
        }
        System.gc();
        Thread.sleep(1000); // give the old-object sampler a chance to observe the live set
    }
}
