/**
 * Temporary harness (not for commit): measures whether the per-run cost grows
 * when loadflow parameters alternate between runs on a long living network.
 */
package com.powsybl.openloadflow;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import org.junit.jupiter.api.Test;

class ListenerLeakPerfIT {

    @Test
    void measure() {
        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        Network network = IeeeCdfNetworkFactory.create300();

        LoadFlowParameters[] variants = new LoadFlowParameters[2];
        for (int i = 0; i < 2; i++) {
            variants[i] = new LoadFlowParameters().setDistributedSlack(i == 0);
            OpenLoadFlowParameters.create(variants[i]).setNetworkCacheEnabled(true);
        }

        int iterations = Integer.parseInt(System.getProperty("olf.it.iterations", "1500"));
        int block = 250;
        long blockStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            runner.run(network, variants[i % 2]);
            if ((i + 1) % block == 0) {
                long now = System.nanoTime();
                System.out.println("MEASURE block ending at " + (i + 1) + ": "
                        + (now - blockStart) / 1_000_000 + " ms");
                blockStart = now;
            }
        }
    }
}
