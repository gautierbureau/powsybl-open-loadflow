/**
 * Temporary harness (not for commit): dumps a live-object class histogram early and
 * late in a long cached-loadflow run, so the classes that grow linearly can be
 * identified by diffing the two.
 *
 * Run with e.g.
 *   mvn test -Dtest=HeapHistogramIT -Dolf.it.cache=true
 */
package com.powsybl.openloadflow;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

class HeapHistogramIT {

    private static void histogram(String outPath) throws Exception {
        long pid = ProcessHandle.current().pid();
        Process p = new ProcessBuilder("jcmd", String.valueOf(pid), "GC.class_histogram")
                .redirectOutput(new File(outPath))
                .redirectErrorStream(false)
                .start();
        p.waitFor();
        System.out.println("HISTOGRAM written to " + outPath);
    }

    @Test
    void measure() throws Exception {
        boolean cache = Boolean.parseBoolean(System.getProperty("olf.it.cache", "true"));
        int iterations = Integer.parseInt(System.getProperty("olf.it.iterations", "4000"));
        String outDir = System.getProperty("olf.it.outDir", "/tmp");

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new SparseMatrixFactory()));
        Network network = IeeeCdfNetworkFactory.create300();

        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters).setNetworkCacheEnabled(cache);

        Load load = network.getLoads().iterator().next();
        double baseP0 = load.getP0();

        int early = iterations / 8;
        for (int i = 0; i < iterations; i++) {
            load.setP0(baseP0 * (1.0 + 0.001 * (i % 50)));
            runner.run(network, parameters);

            if (i == early) {
                System.gc();
                histogram(Path.of(outDir, "hist_early.txt").toString());
            }
        }
        System.gc();
        histogram(Path.of(outDir, "hist_late.txt").toString());
        System.out.println("HISTOGRAM iterations between snapshots: " + (iterations - early));
    }
}
