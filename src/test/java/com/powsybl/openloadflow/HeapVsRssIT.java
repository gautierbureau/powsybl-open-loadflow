/**
 * Temporary harness (not for commit): separates Java heap growth from total RSS
 * growth over many cached loadflows, to tell a heap leak from a native/off-heap
 * one (sparse LU decomposition holds native KLU state, direct ByteBuffers are
 * off-heap, and neither is bounded by -Xmx).
 *
 * Run with e.g.
 *   mvn test -Dtest=HeapVsRssIT -DargLine="-Xmx1G" -Dolf.it.cache=true
 */
package com.powsybl.openloadflow;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

class HeapVsRssIT {

    private static long rssKb() throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS:")) {
                return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        }
        return -1;
    }

    private static long heapKb() {
        var mem = ManagementFactory.getMemoryMXBean();
        return mem.getHeapMemoryUsage().getUsed() / 1024;
    }

    @Test
    void measure() throws Exception {
        boolean cache = Boolean.parseBoolean(System.getProperty("olf.it.cache", "true"));
        int iterations = Integer.parseInt(System.getProperty("olf.it.iterations", "4000"));

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new SparseMatrixFactory()));
        Network network = IeeeCdfNetworkFactory.create300();

        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters).setNetworkCacheEnabled(cache);

        Load load = network.getLoads().iterator().next();
        double baseP0 = load.getP0();

        System.out.println("MEASURE cache=" + cache + " iterations=" + iterations);
        for (int i = 0; i < iterations; i++) {
            load.setP0(baseP0 * (1.0 + 0.001 * (i % 50)));
            runner.run(network, parameters);

            if (i % 250 == 0 || i == iterations - 1) {
                System.gc();
                Thread.sleep(50); // let cleaners run so off-heap frees are counted
                System.gc();
                long heap = heapKb();
                long rss = rssKb();
                System.out.println("MEASURE iter=" + i + " heapKb=" + heap + " rssKb=" + rss
                        + " nonHeapRssKb=" + (rss - heap));
            }
        }
    }
}
