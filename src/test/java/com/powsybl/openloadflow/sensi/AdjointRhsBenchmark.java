/**
 * Benchmark of the sensitivity right-hand side on a large network. Untracked, run by hand:
 *
 *   mvn test -Dtest=AdjointRhsBenchmark -DfailIfNoTests=false -DargLine="-Xmx6g"
 *
 * Deliberately uses only generator target voltages, shunt susceptances and branch admittances, so it
 * runs UNCHANGED against a build without the transformer two-pass work — the point is to compare the
 * dense right-hand side against the descriptors on identical factors.
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.contingency.ContingencyContext;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.sensitivity.SensitivityAnalysis;
import com.powsybl.sensitivity.SensitivityAnalysisParameters;
import com.powsybl.sensitivity.SensitivityAnalysisRunParameters;
import com.powsybl.sensitivity.SensitivityFactor;
import com.powsybl.sensitivity.SensitivityFunctionType;
import com.powsybl.sensitivity.SensitivityVariableType;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdjointRhsBenchmark {

    private static final Path DATA = Path.of("/home/bureaugau/Projects/powsybl/test2/python/data");
    private static final int WARMUP = 3;
    private static final int RUNS = 10;

    private static LoadFlowParameters params() {
        LoadFlowParameters lfp = new LoadFlowParameters()
                .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
        OpenLoadFlowParameters.create(lfp).setNetworkCacheEnabled(true);
        return lfp;
    }

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Bytes allocated by THIS thread during the call — the exact figure, not a heap-usage sample. */
    private static long allocatedBy(Runnable r) {
        long before = THREADS.getCurrentThreadAllocatedBytes();
        r.run();
        return THREADS.getCurrentThreadAllocatedBytes() - before;
    }

    private static String mib(long bytes) {
        return String.format("%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compare);
        return s.get(s.size() / 2);
    }

    private void run(String file, int nVariables, int nFunctions) {
        Path p = DATA.resolve(file);
        if (!Files.exists(p)) {
            System.out.println("SKIP (missing): " + p);
            return;
        }
        Network network = Network.read(p);
        LoadFlowParameters lfp = params();
        if (!LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged()) {
            System.out.println("SKIP (did not converge): " + file);
            return;
        }

        // monitored functions: bus voltages
        List<String> buses = network.getBusView().getBusStream()
                .map(b -> b.getId()).limit(nFunctions).toList();

        // variables: generator target voltages (one-hot), shunts (one-hot), lines (4 non-zeros)
        List<String> gens = network.getGeneratorStream()
                .filter(g -> g.isVoltageRegulatorOn() && g.getTerminal().isConnected())
                .map(g -> g.getId()).limit(nVariables / 2).toList();
        List<String> shunts = network.getShuntCompensatorStream()
                .filter(sc -> sc.getTerminal().isConnected())
                .map(sc -> sc.getId()).limit(nVariables / 4).toList();
        List<String> lines = network.getLineStream()
                .filter(l -> l.getTerminal1().isConnected() && l.getTerminal2().isConnected())
                .map(l -> l.getId()).limit(nVariables / 4).toList();

        SensitivityFunctionType ft = SensitivityFunctionType.BUS_VOLTAGE;
        Map<String, Double> cot = new HashMap<>();
        for (int i = 0; i < buses.size(); i++) {
            cot.put(AcSensitivityAnalysis.functionCotangentKey(ft, buses.get(i)), 1.0 + 0.001 * i);
        }

        List<AcSensitivityAnalysis.AdjointBlock> blocks = List.of(
                new AcSensitivityAnalysis.AdjointBlock(ft, buses, adjointVars(gens, SensitivityVariableType.BUS_TARGET_VOLTAGE)),
                new AcSensitivityAnalysis.AdjointBlock(ft, buses, adjointVars(shunts, SensitivityVariableType.SHUNT_COMPENSATOR_SUSCEPTANCE)),
                new AcSensitivityAnalysis.AdjointBlock(ft, buses, adjointVars(lines, SensitivityVariableType.BRANCH_ADMITTANCE)));

        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(lfp);
        AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);
        String variant = network.getVariantManager().getWorkingVariantId();

        int groups = gens.size() + shunts.size() + lines.size();
        long nBus = network.getBusView().getBusStream().count();
        // The dense right-hand side the old path allocated is equationCount x groups doubles, OFF-HEAP
        // (DenseMatrix stores its values in a ByteBuffer.allocateDirect), so it is invisible to the
        // thread-allocation counter below and to -Xmx. equationCount is about 2 per bus.
        System.out.printf("%n=== %s : %d monitored functions, %d variable groups, %d buses ===%n",
                file, buses.size(), groups, nBus);
        System.out.printf("  dense RHS would be ~%s off-heap (2 x %d rows x %d cols x 8B)%n",
                mib(2 * nBus * groups * 8L), nBus, groups);

        List<Double> adj = new ArrayList<>();
        for (int i = 0; i < WARMUP + RUNS; i++) {
            long t0 = System.nanoTime();
            Map<String, Double> theta = analysis.runAdjoint(network, variant, List.of(), blocks, cot);
            double ms = (System.nanoTime() - t0) / 1e6;
            if (i >= WARMUP) {
                adj.add(ms);
            }
            assertTrue(theta.size() == groups, "expected " + groups + " gradients, got " + theta.size());
        }
        System.out.printf("  runAdjoint (all %d groups, one solve) : median %.1f ms   min %.1f ms%n",
                groups, median(adj), adj.stream().mapToDouble(Double::doubleValue).min().orElse(0));
        long alloc = allocatedBy(() -> analysis.runAdjoint(network, variant, List.of(), blocks, cot));
        System.out.printf("  runAdjoint allocation                 : %s per call%n", mib(alloc));

        // forward, for scale: the same information as a matrix
        List<SensitivityFactor> full = new ArrayList<>();
        addFactors(full, ft, buses, gens, SensitivityVariableType.BUS_TARGET_VOLTAGE);
        addFactors(full, ft, buses, shunts, SensitivityVariableType.SHUNT_COMPENSATOR_SUSCEPTANCE);
        addFactors(full, ft, buses, lines, SensitivityVariableType.BRANCH_ADMITTANCE);
        // Repeated: the forward path now allocates a descriptor per group where it used to write straight
        // into the matrix, so a regression there has to be ruled out rather than argued away. One sample
        // cannot do it — analyse rebuilds the network and re-solves every call, so the RHS is a small part
        // of a noisy whole.
        List<Double> fwd = new ArrayList<>();
        for (int i = 0; i < 2 + 5; i++) {
            long t0 = System.nanoTime();
            SensitivityAnalysis.find().run(network, full, new SensitivityAnalysisRunParameters().setParameters(sensiParams));
            double ms = (System.nanoTime() - t0) / 1e6;
            if (i >= 2) {
                fwd.add(ms);
            }
        }
        System.out.printf("  forward analyse (%d factors)      : median %.1f ms   min %.1f ms%n",
                full.size(), median(fwd), fwd.stream().mapToDouble(Double::doubleValue).min().orElse(0));
    }

    private static void addFactors(List<SensitivityFactor> out, SensitivityFunctionType ft,
                                   List<String> functions, List<String> variables, SensitivityVariableType vt) {
        for (String v : variables) {
            for (String f : functions) {
                out.add(new SensitivityFactor(ft, f, vt, v, false, ContingencyContext.all()));
            }
        }
    }

    private static List<AcSensitivityAnalysis.AdjointVariable> adjointVars(List<String> ids, SensitivityVariableType vt) {
        return ids.stream().map(v -> new AcSensitivityAnalysis.AdjointVariable(v, vt, false)).toList();
    }

    /**
     * The UNFAVOURABLE case, deliberately.
     *
     * <p>The main benchmark uses the mix the TVC problem actually has — target voltages, shunts, branch
     * admittances — where a column carries 1 to 4 non-zeros and describing it instead of materialising it is
     * all upside. This one inverts that: {@code INJECTION_ACTIVE_POWER} under
     * {@code PROPORTIONAL_TO_LOAD} slack distribution gives every column one entry per participating LOAD
     * bus, so the columns are a large fraction of the equation count and the descriptors lose their
     * asymptotic edge. What is left is the comparison that actually decides whether the refactor is safe as
     * a default: a contiguous scan over a dense array, against an indirect gather over index arrays that
     * are individually allocated per group.</p>
     */
    /**
     * The case Part B is about: a request carrying TRANSFORMER target voltages.
     *
     * <p>Serving one used to mean switching the voltage controls back on, which invalidates the Jacobian's
     * structure and forces a refactorisation inside the call (plus a second one, lazily, when the flags are
     * restored). Expressing the target voltage as a combination of ratio columns instead costs one extra
     * multi-right-hand-side solve on the factorisation already there. This times the whole runAdjoint, so the
     * difference between the two commits is the refactorisation.</p>
     */
    @Test
    void benchmarkTransformerTargetVoltages() {
        for (String file : List.of("rte6515_full.xiidm.gz", "pegase9241_full.xiidm.gz", "pegase13659_full.xiidm.gz")) {
            Path p = DATA.resolve(file);
            if (!Files.exists(p)) {
                System.out.println("SKIP (missing): " + p);
                continue;
            }
            Network network = Network.read(p);
            LoadFlowParameters lfp = params();
            lfp.setTransformerVoltageControlOn(true);
            if (!LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged()) {
                System.out.println("SKIP (did not converge): " + file);
                continue;
            }

            List<String> allRtcs = network.getTwoWindingsTransformerStream()
                    .filter(t -> t.getRatioTapChanger() != null && t.getRatioTapChanger().isRegulating()
                            && t.getTerminal1().isConnected() && t.getTerminal2().isConnected())
                    .map(t -> t.getId()).toList();
            if (allRtcs.isEmpty()) {
                System.out.println("SKIP (no regulating RTC): " + file);
                continue;
            }
            List<String> buses = network.getBusView().getBusStream().map(b -> b.getId()).limit(200).toList();

            SensitivityFunctionType ft = SensitivityFunctionType.BUS_VOLTAGE;
            Map<String, Double> cot = new HashMap<>();
            for (int i = 0; i < buses.size(); i++) {
                cot.put(AcSensitivityAnalysis.functionCotangentKey(ft, buses.get(i)), 1.0 + 0.001 * i);
            }
            SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
            sensiParams.setLoadFlowParameters(lfp);
            AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                    new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);
            String variant = network.getVariantManager().getWorkingVariantId();

            System.out.printf("%n=== %s TRANSFORMER case : up to %d regulating changers ===%n",
                    file, allRtcs.size());
            // Swept, because the two approaches scale differently in the number of declared changers: the
            // reduction pays O(k^2) reads and an O(k^3) dense LU on the coordination matrix, the
            // refactorisation pays one sparse LU whatever k is. Where they cross decides which is the default.
            for (int k : new int[] {1, 5, 10, 30, 100, allRtcs.size()}) {
                if (k > allRtcs.size()) {
                    continue;
                }
                List<AcSensitivityAnalysis.AdjointBlock> blocks = List.of(
                        new AcSensitivityAnalysis.AdjointBlock(ft, buses,
                                adjointVars(allRtcs.subList(0, k), SensitivityVariableType.BUS_TARGET_VOLTAGE)));
                List<Double> adj = new ArrayList<>();
                for (int i = 0; i < WARMUP + RUNS; i++) {
                    long t0 = System.nanoTime();
                    analysis.runAdjoint(network, variant, List.of(), blocks, cot);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    if (i >= WARMUP) {
                        adj.add(ms);
                    }
                }
                System.out.printf("  k=%-5d median %6.1f ms   min %6.1f ms%n", k, median(adj),
                        adj.stream().mapToDouble(Double::doubleValue).min().orElse(0));
            }
        }
    }

    @Test
    void benchmarkDenseColumns() {
        for (String file : List.of("rte6515_full.xiidm.gz", "pegase9241_full.xiidm.gz")) {
            Path p = DATA.resolve(file);
            if (!Files.exists(p)) {
                System.out.println("SKIP (missing): " + p);
                continue;
            }
            Network network = Network.read(p);
            LoadFlowParameters lfp = new LoadFlowParameters()
                    .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES)
                    .setDistributedSlack(true)
                    .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
            OpenLoadFlowParameters.create(lfp).setNetworkCacheEnabled(true);
            if (!LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged()) {
                System.out.println("SKIP (did not converge): " + file);
                continue;
            }

            // BRANCH_ACTIVE_POWER_1, not BUS_VOLTAGE: an active-power injection is only defined against a
            // flow function ("Variable type INJECTION_ACTIVE_POWER not supported with function type
            // BUS_VOLTAGE").
            List<String> buses = network.getLineStream()
                    .filter(l -> l.getTerminal1().isConnected() && l.getTerminal2().isConnected())
                    .map(l -> l.getId()).limit(200).toList();
            List<String> gens = network.getGeneratorStream()
                    .filter(g -> g.getTerminal().isConnected())
                    .map(g -> g.getId()).limit(500).toList();

            long connectedLoads = network.getLoadStream().filter(l -> l.getTerminal().isConnected()).count();
            System.out.printf("%n=== %s DENSE-COLUMN case : %d injection groups, participation spread over "
                    + "~%d load buses ===%n", file, gens.size(), connectedLoads);

            SensitivityFunctionType ft = SensitivityFunctionType.BRANCH_ACTIVE_POWER_1;
            Map<String, Double> cot = new HashMap<>();
            for (int i = 0; i < buses.size(); i++) {
                cot.put(AcSensitivityAnalysis.functionCotangentKey(ft, buses.get(i)), 1.0 + 0.001 * i);
            }
            List<AcSensitivityAnalysis.AdjointBlock> blocks = List.of(
                    new AcSensitivityAnalysis.AdjointBlock(ft, buses,
                            adjointVars(gens, SensitivityVariableType.INJECTION_ACTIVE_POWER)));

            SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
            sensiParams.setLoadFlowParameters(lfp);
            AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                    new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);
            String variant = network.getVariantManager().getWorkingVariantId();

            List<Double> adj = new ArrayList<>();
            for (int i = 0; i < WARMUP + RUNS; i++) {
                long t0 = System.nanoTime();
                analysis.runAdjoint(network, variant, List.of(), blocks, cot);
                double ms = (System.nanoTime() - t0) / 1e6;
                if (i >= WARMUP) {
                    adj.add(ms);
                }
            }
            System.out.printf("  runAdjoint (%d dense groups)          : median %.1f ms   min %.1f ms%n",
                    gens.size(), median(adj), adj.stream().mapToDouble(Double::doubleValue).min().orElse(0));
            long alloc = allocatedBy(() -> analysis.runAdjoint(network, variant, List.of(), blocks, cot));
            System.out.printf("  runAdjoint allocation                 : %s per call%n", mib(alloc));
        }
    }

    @Test
    void benchmark() {
        run("rte6515_full.xiidm.gz", 1000, 200);
        run("pegase9241_full.xiidm.gz", 1000, 200);
    }
}
