/**
 * Temporary harness (not for commit): the classical long-lived-script pattern —
 * constant loadflow parameters, a fresh network per iteration, cache enabled.
 *
 * Reports the cache entry count alongside heap (after a forced GC) and RSS, so
 * that genuine retention can be told apart from entries simply not evicted yet
 * (entries hold their LfNetwork strongly but the IIDM network only weakly, and
 * dead entries are reclaimed lazily, on the next get()).
 *
 * Run with e.g.
 *   java -cp ... -Xmx1G ... (see runner script)
 */
package com.powsybl.openloadflow;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

class NewNetworkCacheIT {

    private static long rssKb() throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS:")) {
                return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        }
        return -1;
    }

    private static long heapKb() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024;
    }

    @Test
    void measure() throws Exception {
        boolean cache = Boolean.parseBoolean(System.getProperty("olf.it.cache", "true"));
        int iterations = Integer.parseInt(System.getProperty("olf.it.iterations", "600"));

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new SparseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters).setNetworkCacheEnabled(cache);

        System.out.println("MEASURE cache=" + cache + " iterations=" + iterations);
        for (int i = 0; i < iterations; i++) {
            Network network = IeeeCdfNetworkFactory.create300();
            runner.run(network, parameters);
            // drop the only strong reference to the network, exactly as a script would
            network = null;

            if (i % 25 == 0 || i == iterations - 1) {
                System.gc();
                Thread.sleep(60);
                System.gc();
                System.out.println("MEASURE iter=" + i
                        + " acEntries=" + NetworkCache.AC_LF_INSTANCE.getEntryCount()
                        + " dcEntries=" + NetworkCache.DC_LF_INSTANCE.getEntryCount()
                        + " heapKb=" + heapKb()
                        + " rssKb=" + rssKb());
            }
        }
    }
}
