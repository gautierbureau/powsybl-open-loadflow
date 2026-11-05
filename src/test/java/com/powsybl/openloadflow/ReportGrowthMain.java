/**
 * Temporary harness (not for commit): counts the report tree of the cached LfNetwork
 * across successive cached runs, to quantify per-run accumulation.
 */
package com.powsybl.openloadflow;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;

public final class ReportGrowthMain {

    private ReportGrowthMain() {
    }

    private static int countNodes(ReportNode node) {
        int n = 1;
        for (ReportNode child : node.getChildren()) {
            n += countNodes(child);
        }
        return n;
    }

    public static void main(String[] args) {
        int iterations = Integer.parseInt(System.getProperty("olf.it.iterations", "20"));

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new SparseMatrixFactory()));
        Network network = IeeeCdfNetworkFactory.create300();
        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters).setNetworkCacheEnabled(true);

        Load load = network.getLoads().iterator().next();
        double baseP0 = load.getP0();

        for (int i = 0; i < iterations; i++) {
            load.setP0(baseP0 * (1.0 + 0.001 * (i % 50)));
            runner.run(network, parameters);

            var entry = NetworkCache.AC_LF_INSTANCE.findEntry(network).orElseThrow();
            ReportNode lfReport = entry.getValues().get(0).getNetwork().getReportNode();
            System.out.println("REPORT run=" + (i + 1)
                    + " directChildren=" + lfReport.getChildren().size()
                    + " totalNodes=" + countNodes(lfReport));
            if (i == iterations - 1) {
                for (ReportNode child : lfReport.getChildren()) {
                    System.out.println("   CHILD " + child.getMessageKey());
                }
            }
        }
    }
}
