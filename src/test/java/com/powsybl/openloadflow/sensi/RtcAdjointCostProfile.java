package com.powsybl.openloadflow.sensi;

import com.powsybl.contingency.ContingencyContext;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.json.JsonLoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.NetworkCache;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.outerloop.IncrementalTransformerVoltageControlOuterLoop;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.sensitivity.SensitivityAnalysis;
import com.powsybl.sensitivity.SensitivityAnalysisParameters;
import com.powsybl.sensitivity.SensitivityAnalysisResult;
import com.powsybl.sensitivity.SensitivityAnalysisRunParameters;
import com.powsybl.sensitivity.SensitivityFactor;
import com.powsybl.sensitivity.SensitivityFunctionType;
import com.powsybl.sensitivity.SensitivityVariableType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a transformer target voltage costs in the adjoint, on a REAL case, broken down.
 *
 * <p>Run by hand — it is skipped unless a network is named:</p>
 * <pre>
 *   PROFILE_NETWORK=/path/CASE.xiidm PROFILE_RTC_CSV=/path/data/config/rtc.csv \
 *       PROFILE_LF_PARAMS=/path/tvc-loadflow-default-parameters.json \
 *       mvn test -Dtest=RtcAdjointCostProfile -DfailIfNoTests=false -DargLine="-Xmx10g"
 * </pre>
 *
 * <p>Environment variables rather than system properties: surefire forks its own JVM and does not
 * forward {@code -D} to it, so a property-gated test silently reports "Skipped".</p>
 *
 * <p>The tap changer lever is the one whose gradient is not a plain contraction: the converged state
 * has no active control equation, so it is reduced onto the ratio rows, and that reduction costs one
 * back-substitution per CONTROLLED BUS in the network — Z, a property of the case, not of how many
 * levers the caller declares. This times the pieces separately so the trade is visible:</p>
 *
 * <ul>
 *   <li>an adjoint with NO transformer variable — the floor;</li>
 *   <li>the coordination build, split into its Z-column solve, its Z² reads and its dense LU;</li>
 *   <li>a full adjoint WITH the transformer variables;</li>
 *   <li>a bare refactorisation of the network Jacobian — the alternative, which is O(1) in Z.</li>
 * </ul>
 *
 * <p>The last two are the decision: Z back-substitutions against one refactorisation.</p>
 *
 * <p>Prints sizes and timings. Element ids appear only with {@code PROFILE_VERBOSE=true}.</p>
 */
@EnabledIfEnvironmentVariable(named = "PROFILE_NETWORK", matches = ".+")
class RtcAdjointCostProfile {

    private static final int WARMUP = 2;
    private static final int RUNS = 7;

    private static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compare);
        return s.get(s.size() / 2);
    }

    /**
     * The caller's OWN load flow parameters, read from JSON, or a minimal stand-in.
     *
     * <p>This is not a detail. Which transformer controls exist in the LF model — and therefore Z, the
     * whole cost driver — is decided by settings a hand-rolled parameter set gets wrong in both
     * directions: {@code generatorVoltageControlMinNominalVoltage} stands the generators down below its
     * threshold so the transformers inherit the job, CREATING controls, while
     * {@code disableInconsistentVoltageControls} drops others outright. Profiling with anything but the
     * parameters the gradients are actually taken at measures a different network.</p>
     */
    private static LoadFlowParameters params() {
        String json = System.getenv("PROFILE_LF_PARAMS");
        LoadFlowParameters lfp;
        if (json != null) {
            lfp = JsonLoadFlowParameters.read(Path.of(json));
            System.out.printf("  load flow parameters               %s%n", Path.of(json).getFileName());
        } else {
            System.out.println("  load flow parameters               !! built-in stand-in, NOT the caller's."
                    + " Set PROFILE_LF_PARAMS to the real JSON.");
            lfp = new LoadFlowParameters()
                    .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES)
                    .setTransformerVoltageControlOn(true);
            OpenLoadFlowParameters.create(lfp).setTransformerVoltageControlMode(
                    OpenLoadFlowParameters.TransformerVoltageControlMode.AFTER_GENERATOR_VOLTAGE_CONTROL);
        }
        // runAdjoint reuses the load flow retained in the network cache, so this one is not negotiable.
        OpenLoadFlowParameters.get(lfp).setNetworkCacheEnabled(true);
        var ext = OpenLoadFlowParameters.get(lfp);
        System.out.printf("  ... init / tvc mode                %s / %s%n",
                lfp.getVoltageInitMode(), ext.getTransformerVoltageControlMode());
        System.out.printf("  ... genVCtrlMinNominalV / disableInconsistent   %.0f kV / %s%n",
                ext.getGeneratorVoltageControlMinNominalVoltage(), ext.isDisableInconsistentVoltageControls());
        return lfp;
    }

    /**
     * The lever ids from the config, expanded the way the caller does it: an entry that is already a
     * transformer is kept, anything else is read as a VOLTAGE LEVEL and replaced by the changers
     * regulating a bus in it. First column, semicolon-separated, header skipped.
     */
    private static List<String> readLeverConfig(Path csv, Network network) throws Exception {
        Set<String> wanted = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) { // skip the header
            String line = lines.get(i).trim();
            if (!line.isEmpty()) {
                wanted.add(line.split(";")[0].trim());
            }
        }
        List<String> out = new ArrayList<>();
        int fromVoltageLevel = 0;
        for (String id : wanted) {
            TwoWindingsTransformer t = network.getTwoWindingsTransformer(id);
            if (t != null && t.getRatioTapChanger() != null) {
                out.add(id);
                continue;
            }
            var vl = network.getVoltageLevel(id);
            if (vl == null) {
                continue;
            }
            for (TwoWindingsTransformer twt : network.getTwoWindingsTransformers()) {
                var rtc = twt.getRatioTapChanger();
                if (rtc != null && rtc.getRegulationTerminal() != null
                        && rtc.getRegulationTerminal().getVoltageLevel() == vl && !out.contains(twt.getId())) {
                    out.add(twt.getId());
                    fromVoltageLevel++;
                }
            }
        }
        System.out.printf("  config entries %d -> %d changer(s) (%d via voltage-level expansion)%n",
                wanted.size(), out.size(), fromVoltageLevel);
        return out;
    }

    @Test
    void profile() throws Exception {
        Path networkPath = Path.of(System.getenv("PROFILE_NETWORK"));
        String csv = System.getenv("PROFILE_RTC_CSV");
        boolean verbose = Boolean.parseBoolean(String.valueOf(System.getenv("PROFILE_VERBOSE")));

        double load = Double.parseDouble(Files.readString(Path.of("/proc/loadavg")).split("\\s+")[0]);
        if (load > 2.0) {
            System.out.printf("%n!! load average %.1f — timings below are NOT trustworthy, re-run when idle%n", load);
        }

        Network network = Network.read(networkPath);
        System.out.printf("%n=== %s ===%n", networkPath.getFileName());
        LoadFlowParameters lfp = params();
        long t0 = System.nanoTime();
        boolean converged = LoadFlow.find("OpenLoadFlow").run(network, lfp).isFullyConverged();
        double lfMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf("  load flow                          %8.1f ms   %s%n", lfMs,
                converged ? "converged" : "!! DID NOT CONVERGE");
        if (!converged) {
            return;
        }

        var value = NetworkCache.AC_LF_INSTANCE.findEntry(network).orElseThrow().getValues().get(0);
        AcLoadFlowContext context = value.getContext();
        LfNetwork lfNetwork = value.getNetwork();
        System.out.printf("  buses / equations                  %8d / %d%n",
                lfNetwork.getBuses().size(), context.getEquationSystem().getIndex().getColumnCount());

        // ---- the fleet, as OLF sees it (not as the network file declares it) ---------------------
        Map<LfBus, List<LfBranch>> zones = new LinkedHashMap<>();
        for (LfBranch b : lfNetwork.getBranches()) {
            if (!b.isDisabled()) {
                b.getVoltageControl().ifPresent(vc ->
                        zones.computeIfAbsent(vc.getControlledBus(), k -> new ArrayList<>()).add(b));
            }
        }
        Map<Integer, Integer> histogram = new TreeMap<>();
        zones.values().forEach(c -> histogram.merge(c.size(), 1, Integer::sum));
        System.out.printf("  Z, controlled buses (LF model)     %8d%n", zones.size());
        System.out.printf("  zone sizes                         %s%n", histogram);

        List<String> levers = csv == null
                ? network.getTwoWindingsTransformerStream()
                        .filter(t -> t.getRatioTapChanger() != null && t.getRatioTapChanger().isRegulating())
                        .map(TwoWindingsTransformer::getId).toList()
                : readLeverConfig(Path.of(csv), network);
        System.out.printf("  declared tap changer levers        %8d%n", levers.size());
        if (levers.isEmpty()) {
            System.out.println("  !! nothing to profile");
            return;
        }
        if (verbose) {
            levers.forEach(l -> System.out.println("     " + l));
        }

        // ---- the pieces --------------------------------------------------------------------------
        List<String> buses = lfNetwork.getBuses().stream().map(LfBus::getId).limit(200).toList();
        SensitivityFunctionType ft = SensitivityFunctionType.BUS_VOLTAGE;
        Map<String, Double> cot = new LinkedHashMap<>();
        for (int i = 0; i < buses.size(); i++) {
            cot.put(AcSensitivityAnalysis.functionCotangentKey(ft, buses.get(i)), 1.0 + 0.001 * i);
        }
        SensitivityAnalysisParameters sensiParams = new SensitivityAnalysisParameters();
        sensiParams.setLoadFlowParameters(lfp);
        AcSensitivityAnalysis analysis = new AcSensitivityAnalysis(new SparseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), sensiParams);
        String variant = network.getVariantManager().getWorkingVariantId();

        // (a) floor: an adjoint with no transformer variable at all (a shunt-free proxy: monitor only)
        List<String> gens = network.getGeneratorStream().filter(g -> g.isVoltageRegulatorOn())
                .map(g -> g.getId()).limit(levers.size()).toList();
        System.out.println();
        if (!gens.isEmpty()) {
            var genBlocks = List.of(new AcSensitivityAnalysis.AdjointBlock(ft, buses,
                    gens.stream().map(g -> new AcSensitivityAnalysis.AdjointVariable(
                            g, SensitivityVariableType.BUS_TARGET_VOLTAGE, false)).toList()));
            System.out.printf("  adjoint, %d GENERATOR targets       %8.1f ms   (the floor: no reduction)%n",
                    gens.size(), timeAdjoint(analysis, network, variant, genBlocks, cot));
        }

        // (b) the coordination build, split
        timeCoordination(context, lfNetwork, zones);

        // (c) full adjoint with the transformer levers
        var rtcBlocks = List.of(new AcSensitivityAnalysis.AdjointBlock(ft, buses,
                levers.stream().map(l -> new AcSensitivityAnalysis.AdjointVariable(
                        l, SensitivityVariableType.BUS_TARGET_VOLTAGE, false)).toList()));
        System.out.printf("  adjoint, %d TRANSFORMER targets     %8.1f ms%n",
                levers.size(), timeAdjoint(analysis, network, variant, rtcBlocks, cot));

        if (Boolean.parseBoolean(String.valueOf(System.getenv("PROFILE_COMPARE")))) {
            var sensi = new IncrementalTransformerVoltageControlOuterLoop.SensitivityContext(
                    lfNetwork, zones.values().stream().flatMap(List::stream).toList(),
                    context.getEquationSystem(), context.getJacobianMatrix());
            Map<String, Double> selfSensiByLever = new LinkedHashMap<>();
            for (var e : zones.entrySet()) {
                double self = 0;
                for (LfBranch c : e.getValue()) {
                    self += sensi.calculateSensitivityFromRToV(c, e.getKey());
                }
                for (LfBranch c : e.getValue()) {
                    selfSensiByLever.put(c.getId(), self);
                }
            }
            // What the reduction ITSELF kept, against what the same formula says here. These must agree;
            // if they do not, the filter is not firing where its own diagnostics claim.
            long belowHere = selfSensiByLever.values().stream().distinct()
                    .filter(v -> Math.abs(v) < IncrementalTransformerVoltageControlOuterLoop.MIN_SENSI_FILTER)
                    .count();
            try (var coord = TransformerTargetVoltageClosedLoopSensitivity.buildCoordination(context)) {
                System.out.printf("%n  zones total / kept by the reduction  %5d / %d%n",
                        zones.size(), coord == null ? 0 : coord.size());
                System.out.printf("  zones below the threshold, computed here  %5d%n", belowHere);
                if (coord != null && zones.size() - coord.size() != belowHere) {
                    System.out.println("  !! MISMATCH: the filter kept zones this formula says are below it");
                }
            }
            // Which zones the reduction excludes, and how strongly each surviving lever is coupled to them.
            double tol = System.getenv("OLF_TVC_SENSI_TOL") == null
                    ? IncrementalTransformerVoltageControlOuterLoop.MIN_SENSI_FILTER
                    : Double.parseDouble(System.getenv("OLF_TVC_SENSI_TOL"));
            List<LfBus> dropped = new ArrayList<>();
            for (var e : zones.entrySet()) {
                double self = 0;
                for (LfBranch c : e.getValue()) {
                    self += sensi.calculateSensitivityFromRToV(c, e.getKey());
                }
                if (Math.abs(self) < tol) {
                    dropped.add(e.getKey());
                }
            }
            Map<String, Double> couplingToDropped = new LinkedHashMap<>();
            for (var e : zones.entrySet()) {
                double coupling = 0;
                for (LfBus d : dropped) {
                    for (LfBranch c : zones.get(d)) {
                        coupling += Math.abs(sensi.calculateSensitivityFromRToV(c, e.getKey()));
                    }
                }
                for (LfBranch c : e.getValue()) {
                    couplingToDropped.put(c.getId(), coupling);
                }
            }
            System.out.printf("  zones dropped at tol=%.0e        %8d%n", tol, dropped.size());
            Map<String, LfBus> busByLever = new LinkedHashMap<>();
            for (var e : zones.entrySet()) {
                for (LfBranch c : e.getValue()) {
                    busByLever.put(c.getId(), e.getKey());
                }
            }
            // How many DROPPED zones are dropped because a generator is holding the bus?
            long droppedHeldByGen = dropped.stream().filter(LfBus::isGeneratorVoltageControlled).count();
            System.out.printf("  of the %d dropped zones, %d have their bus held by a GENERATOR%n",
                    dropped.size(), droppedHeldByGen);
            compare(analysis, network, variant, levers, buses, cot, sensiParams, verbose,
                    selfSensiByLever, couplingToDropped, busByLever);
        }

        // (d) the alternative: one refactorisation of the network Jacobian
        List<Double> refac = new ArrayList<>();
        for (int i = 0; i < WARMUP + RUNS; i++) {
            LfBranch victim = zones.values().iterator().next().get(0);
            long t = System.nanoTime();
            victim.setVoltageControlEnabled(true);      // invalidates the Jacobian structure
            context.getJacobianMatrix().getMatrix();    // forces the rebuild + refactorisation
            double ms = (System.nanoTime() - t) / 1e6;
            victim.setVoltageControlEnabled(false);
            context.getJacobianMatrix().getMatrix();
            if (i >= WARMUP) {
                refac.add(ms);
            }
        }
        System.out.printf("  ONE Jacobian refactorisation       %8.1f ms   (the O(1)-in-Z alternative)%n",
                median(refac));
    }

    /**
     * Do the two ways of answering a transformer target voltage agree, at THIS network's scale?
     *
     * <p>The reduction is gated in the unit tests against forward {@code analyse} — but on fixtures with
     * one or two changers. Forward mode IS the rebuild path (it re-enables the controls and refactorises
     * before solving), so contracting its sensitivity matrix with the same cotangent gives exactly what
     * the refactorising adjoint would return, at whatever scale the case has. That makes it an
     * independent oracle rather than a second copy of the same arithmetic.</p>
     *
     * <p>Two disagreements are expected rather than alarming, and are counted separately: levers the
     * reduction ZEROED because their zone fell below the insensitivity threshold (the refactorisation
     * gives them a small but real gradient), and levers forward mode itself reports as zero.</p>
     */
    private static void compare(AcSensitivityAnalysis analysis, Network network, String variant,
                                List<String> levers, List<String> buses, Map<String, Double> cot,
                                SensitivityAnalysisParameters sensiParams, boolean verbose,
                                Map<String, Double> selfSensiByLever,
                                Map<String, Double> couplingToDroppedByLever,
                                Map<String, LfBus> busByLever) {
        SensitivityFunctionType ft = SensitivityFunctionType.BUS_VOLTAGE;
        SensitivityVariableType vt = SensitivityVariableType.BUS_TARGET_VOLTAGE;

        var blocks = List.of(new AcSensitivityAnalysis.AdjointBlock(ft, buses,
                levers.stream().map(l -> new AcSensitivityAnalysis.AdjointVariable(l, vt, false)).toList()));
        Map<String, Double> reduced = analysis.runAdjoint(network, variant, List.of(), blocks, cot);

        List<SensitivityFactor> full = new ArrayList<>();
        for (String lever : levers) {
            for (String bus : buses) {
                full.add(new SensitivityFactor(ft, bus, vt, lever, false, ContingencyContext.all()));
            }
        }
        long exactZeros = levers.stream().map(reduced::get)
                .filter(v -> v != null && v == 0.0).count();
        long missing = levers.stream().filter(l -> !reduced.containsKey(l)).count();
        System.out.printf("%n  declared %d | returned %d | exactly-zero theta_bar %d | absent %d%n",
                levers.size(), reduced.size(), exactZeros, missing);
        System.out.printf("  comparing against forward analyse (%d factors) ...%n", full.size());
        long t = System.nanoTime();
        SensitivityAnalysisResult fwd = SensitivityAnalysis.find().run(network, full,
                new SensitivityAnalysisRunParameters().setParameters(sensiParams));
        System.out.printf("  forward analyse                    %8.1f ms%n", (System.nanoTime() - t) / 1e6);

        // Scale-relative, not value-relative: forward analyse RE-RUNS its own load flow and lands on a
        // slightly different operating point than the cached one the adjoint reuses, so exact agreement is
        // not on offer and a lever whose gradient is near zero would otherwise report a meaningless
        // relative error. Normalise by the typical magnitude across the fleet instead.
        List<Double> expectedAll = new ArrayList<>();
        List<Double> gotAll = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int zeroedByFilter = 0;
        int forwardZero = 0;
        for (String lever : levers) {
            double expected = 0;
            for (String bus : buses) {
                expected += cot.get(AcSensitivityAnalysis.functionCotangentKey(ft, bus))
                        * fwd.getBusVoltageSensitivityValue(lever, bus, vt);
            }
            Double got = reduced.get(lever);
            if (got == null) {
                continue;
            }
            if (got == 0.0 && Math.abs(expected) > 1e-9) {
                zeroedByFilter++;
                continue;
            }
            if (Math.abs(expected) < 1e-9) {
                forwardZero++;
                continue;
            }
            expectedAll.add(expected);
            gotAll.add(got);
            ids.add(lever);
        }
        if (expectedAll.isEmpty()) {
            System.out.println("  nothing comparable");
            return;
        }
        List<Double> magnitudes = new ArrayList<>(expectedAll.stream().map(Math::abs).toList());
        magnitudes.sort(Double::compare);
        double scale = magnitudes.get(magnitudes.size() / 2); // median |theta_bar| over the fleet

        List<Double> rels = new ArrayList<>();
        double worstRel = 0;
        String worstId = null;
        for (int i = 0; i < ids.size(); i++) {
            double rel = Math.abs(gotAll.get(i) - expectedAll.get(i))
                    / Math.max(Math.abs(expectedAll.get(i)), scale);
            rels.add(rel);
            if (rel > worstRel) {
                worstRel = rel;
                worstId = ids.get(i);
            }
            if (verbose && rel > 1e-3) {
                System.out.printf("     %-24s reduction %+.6e  forward %+.6e  rel %.2e%n",
                        ids.get(i), gotAll.get(i), expectedAll.get(i), rel);
            }
        }
        List<Double> sorted = new ArrayList<>(rels);
        sorted.sort(Double::compare);
        System.out.println();
        System.out.printf("  levers compared                    %8d%n", ids.size());
        System.out.printf("  ... zeroed by the insensitivity filter %5d   (forward gives them a gradient)%n",
                zeroedByFilter);
        System.out.printf("  ... forward itself ~0              %8d%n", forwardZero);
        System.out.printf("  scale-relative difference: median  %8.2e%n", sorted.get(sorted.size() / 2));
        System.out.printf("                             p90     %8.2e%n", sorted.get((int) (sorted.size() * 0.9)));
        System.out.printf("                             p99     %8.2e%n", sorted.get((int) (sorted.size() * 0.99)));
        System.out.printf("                             max     %8.2e%s%n", worstRel,
                verbose && worstId != null ? "   on " + worstId : "");

        // Is the tail the WEAKLY COUPLED zones? |dV/drho| near zero makes M near-singular in that
        // direction, and M^-1 amplifies whatever the two operating points disagree about. If so the
        // insensitivity threshold is protecting accuracy, not just avoiding a singular matrix - and
        // LOWERING it would make this worse, not better.
        // Does the surviving tail sit on levers COUPLED TO A DROPPED ZONE? Excluding a zone from M
        // freezes its ratio, while forward keeps its control active — so a lever whose bus those ratios
        // move must disagree, however well conditioned it is itself. If the tail tracks this, it is
        // inherent to the reduction rather than a tuning problem.
        double[] cEdges = {1e-6, 1e-3, 1e-2, 1e-1, Double.MAX_VALUE};
        System.out.println();
        System.out.println("  disagreement by coupling to the DROPPED zones:");
        for (int b = 0; b < cEdges.length; b++) {
            double lo = b == 0 ? 0 : cEdges[b - 1];
            List<Double> bucket = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                Double c = couplingToDroppedByLever.get(ids.get(i));
                if (c != null && c >= lo && c < cEdges[b]) {
                    bucket.add(rels.get(i));
                }
            }
            if (bucket.isEmpty()) {
                continue;
            }
            bucket.sort(Double::compare);
            System.out.printf("    coupling in [%.0e, %s)  n=%-4d median rel %8.2e   p90 %8.2e   max %8.2e%n",
                    lo, cEdges[b] == Double.MAX_VALUE ? "inf" : String.format("%.0e", cEdges[b]),
                    bucket.size(), bucket.get(bucket.size() / 2),
                    bucket.get((int) (bucket.size() * 0.9)), bucket.get(bucket.size() - 1));
        }

        // Cross-tabulate the two: is the badly-disagreeing population the WEAKLY COUPLED one? If the
        // levers with no coupling to the dropped zones are also the ones with little authority over their
        // own bus, then both diagnostics are seeing one population — peripheral changers — and the tail is
        // ill-conditioning of M, not the drops.
        List<Double> selfOfLowCoupling = new ArrayList<>();
        List<Double> selfOfRest = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Double c = couplingToDroppedByLever.get(ids.get(i));
            Double self = selfSensiByLever.get(ids.get(i));
            if (c == null || self == null) {
                continue;
            }
            (c < 1e-6 ? selfOfLowCoupling : selfOfRest).add(Math.abs(self));
        }
        selfOfLowCoupling.sort(Double::compare);
        selfOfRest.sort(Double::compare);
        System.out.println();
        System.out.println("  CROSS-TAB — |dV/drho| of each population:");
        if (!selfOfLowCoupling.isEmpty()) {
            System.out.printf("    coupling < 1e-6  (the tail)  n=%-4d |dV/drho| median %8.2e   max %8.2e%n",
                    selfOfLowCoupling.size(), selfOfLowCoupling.get(selfOfLowCoupling.size() / 2),
                    selfOfLowCoupling.get(selfOfLowCoupling.size() - 1));
        }
        if (!selfOfRest.isEmpty()) {
            System.out.printf("    coupling >= 1e-6 (the rest)  n=%-4d |dV/drho| median %8.2e   max %8.2e%n",
                    selfOfRest.size(), selfOfRest.get(selfOfRest.size() / 2),
                    selfOfRest.get(selfOfRest.size() - 1));
        }
        // And the ten worst offenders, with both properties side by side.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            order.add(i);
        }
        order.sort((x, y) -> Double.compare(rels.get(y), rels.get(x)));
        System.out.println();
        System.out.println("  TEN WORST — the actual adjoint values:");
        System.out.printf("    %-22s %14s %14s %10s %11s %-13s %s%n",
                "lever", "reduction", "forward", "rel", "|dV/drho|", "in M?", "bus also held by");
        for (int k = 0; k < Math.min(10, order.size()); k++) {
            int i = order.get(k);
            Double self = selfSensiByLever.get(ids.get(i));
            LfBus bus = busByLever.get(ids.get(i));
            String heldBy = bus == null ? "?"
                    : (bus.isGeneratorVoltageControlled() ? "GENERATOR"
                        + (bus.isGeneratorVoltageControlEnabled() ? " (enabled)" : " (off)") : "nothing else");
            System.out.printf("    %-22s %+14.6e %+14.6e %10.2e %11s %-13s %s%n",
                    ids.get(i), gotAll.get(i), expectedAll.get(i), rels.get(i),
                    self == null ? "?" : String.format("%.3e", Math.abs(self)),
                    self == null ? "unknown" : (Math.abs(self) < 1e-3 ? "NO (dropped)" : "yes"), heldBy);
        }

        double[] edges = {0.05, 0.1, 0.2, 0.5, 1.0, Double.MAX_VALUE};
        System.out.println();
        System.out.println("  disagreement by the zone's own |dV/drho|:");
        for (int b = 0; b < edges.length; b++) {
            double lo = b == 0 ? 0 : edges[b - 1];
            List<Double> bucket = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                Double self = selfSensiByLever.get(ids.get(i));
                if (self != null && Math.abs(self) >= lo && Math.abs(self) < edges[b]) {
                    bucket.add(rels.get(i));
                }
            }
            if (bucket.isEmpty()) {
                continue;
            }
            bucket.sort(Double::compare);
            System.out.printf("    |dV/drho| in [%.2f, %s)   n=%-4d median rel %8.2e   max %8.2e%n",
                    lo, edges[b] == Double.MAX_VALUE ? "inf" : String.format("%.2f", edges[b]),
                    bucket.size(), bucket.get(bucket.size() / 2), bucket.get(bucket.size() - 1));
        }
        double median = sorted.get(sorted.size() / 2);
        if (median > 1e-2) {
            System.out.println("  !! the two methods do NOT agree — the reduction has a real problem at this scale");
        } else if (median > 1e-3) {
            System.out.println("  => they agree only roughly; more than a re-solved operating point explains");
        } else {
            System.out.println("  => they agree, to about what re-solving the operating point costs");
        }
    }

    private static double timeAdjoint(AcSensitivityAnalysis analysis, Network network, String variant,
                                      List<AcSensitivityAnalysis.AdjointBlock> blocks, Map<String, Double> cot) {
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < WARMUP + RUNS; i++) {
            long t = System.nanoTime();
            analysis.runAdjoint(network, variant, List.of(), blocks, cot);
            double ms = (System.nanoTime() - t) / 1e6;
            if (i >= WARMUP) {
                samples.add(ms);
            }
        }
        return median(samples);
    }

    /** The reduction's own cost, split into the Z-column solve, the Z^2 reads and the dense LU. */
    private static void timeCoordination(AcLoadFlowContext context, LfNetwork lfNetwork,
                                         Map<LfBus, List<LfBranch>> zones) {
        List<LfBus> buses = new ArrayList<>(zones.keySet());
        List<LfBranch> controllers = zones.values().stream().flatMap(List::stream).toList();
        int z = buses.size();
        List<Double> solve = new ArrayList<>();
        List<Double> reads = new ArrayList<>();
        List<Double> lu = new ArrayList<>();
        for (int i = 0; i < WARMUP + RUNS; i++) {
            long t = System.nanoTime();
            var sensi = new IncrementalTransformerVoltageControlOuterLoop.SensitivityContext(
                    lfNetwork, controllers, context.getEquationSystem(), context.getJacobianMatrix());
            double tSolve = (System.nanoTime() - t) / 1e6;

            t = System.nanoTime();
            DenseMatrix m = new DenseMatrix(z, z);
            for (int col = 0; col < z; col++) {
                for (LfBranch c : zones.get(buses.get(col))) {
                    for (int row = 0; row < z; row++) {
                        m.add(row, col, sensi.calculateSensitivityFromRToV(c, buses.get(row)));
                    }
                }
            }
            double tReads = (System.nanoTime() - t) / 1e6;

            t = System.nanoTime();
            m.decomposeLU().close();
            double tLu = (System.nanoTime() - t) / 1e6;
            if (i >= WARMUP) {
                solve.add(tSolve);
                reads.add(tReads);
                lu.add(tLu);
            }
        }
        System.out.printf("  coordination: %d-column solve      %8.1f ms   <-- Z back-substitutions%n",
                z, median(solve));
        System.out.printf("  coordination: %d^2 dV/drho reads   %8.1f ms%n", z, median(reads));
        System.out.printf("  coordination: %dx%d dense LU        %8.1f ms%n", z, z, median(lu));
    }
}
