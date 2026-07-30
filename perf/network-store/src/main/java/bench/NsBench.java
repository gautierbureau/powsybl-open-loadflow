package bench;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.BusContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.serde.NetworkSerDe;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.network.store.client.NetworkStoreService;
import com.powsybl.network.store.client.PreloadingStrategy;
import com.powsybl.openloadflow.network.NodeBreakerNetworkFactory;
import com.powsybl.openloadflow.sa.OpenSecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysis;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisReport;
import com.powsybl.security.SecurityAnalysisRunParameters;
import com.powsybl.security.results.PostContingencyResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs an Open Load Flow security analysis against a local powsybl-network-store server, at several
 * thread counts, and reports the wall clock time of each run.
 *
 * <pre>
 * NsBench import &lt;baseUri&gt; &lt;ieee118|ieee300|nodebreaker|/path/to/case.mat&gt;
 * NsBench run    &lt;baseUri&gt; &lt;uuid&gt; &lt;preloading&gt; &lt;threadCounts csv&gt; &lt;repeats&gt; &lt;contingencies|all&gt;
 * </pre>
 */
public final class NsBench {

    private NsBench() {
    }

    private static Network createSource(String spec) {
        return switch (spec) {
            case "ieee300" -> IeeeCdfNetworkFactory.create300();
            case "ieee118" -> IeeeCdfNetworkFactory.create118();
            case "nodebreaker" -> NodeBreakerNetworkFactory.create();
            default -> Network.read(Path.of(spec));
        };
    }

    private static List<Contingency> contingencies(Network network, int max, int busContingencies) {
        List<Contingency> contingencies = new ArrayList<>();
        // busbar section (node-breaker) or bus (bus-breaker) contingencies retain switches / fill
        // LfTopoConfig#busIdsToLose, which makes the LF network build go through the retained switch
        // path, hence through an IIDM working variant clone
        if (network.getBusbarSectionCount() > 0) {
            for (com.powsybl.iidm.network.BusbarSection bbs : network.getBusbarSections()) {
                if (contingencies.size() >= busContingencies) {
                    break;
                }
                contingencies.add(new Contingency(bbs.getId(),
                        new com.powsybl.contingency.BusbarSectionContingency(bbs.getId())));
            }
        } else {
            for (com.powsybl.iidm.network.Bus bus : network.getBusBreakerView().getBuses()) {
                if (contingencies.size() >= busContingencies) {
                    break;
                }
                contingencies.add(new Contingency(bus.getId(), new BusContingency(bus.getId())));
            }
        }
        for (Branch<?> branch : network.getBranches()) {
            if (contingencies.size() >= max) {
                break;
            }
            contingencies.add(new Contingency(branch.getId(), new BranchContingency(branch.getId())));
        }
        return contingencies;
    }

    /**
     * The network-store IIDM implementation has no OverloadManagementSystem adder (the copy into the
     * store fails with a null adder), so drop them. They are only simulated when the OLF
     * simulateAutomationSystems parameter is on, which is off by default.
     */
    private static int stripUnsupported(Network network) {
        List<com.powsybl.iidm.network.OverloadManagementSystem> systems = new ArrayList<>();
        for (com.powsybl.iidm.network.Substation substation : network.getSubstations()) {
            substation.getOverloadManagementSystems().forEach(systems::add);
        }
        systems.forEach(com.powsybl.iidm.network.OverloadManagementSystem::remove);
        return systems.size();
    }

    // extensions with no adder in the network-store IIDM implementation
    private static final java.util.Set<String> UNSUPPORTED_EXTENSIONS = java.util.Set.of("referenceTerminals");


    private static void doImport(String baseUri, String spec) throws java.io.IOException {
        long start = System.nanoTime();
        Network source = createSource(spec);
        int stripped = stripUnsupported(source);
        long readMs = (System.nanoTime() - start) / 1_000_000;
        if (stripped > 0) {
            System.out.printf("dropped %d overload management system(s) not supported by the network store%n", stripped);
        }
        // re-serialize without the extensions the network-store implementation has no adder for,
        // then read that back straight into the store network factory
        Path staged = java.nio.file.Files.createTempFile("nsbench-", ".xiidm");
        com.powsybl.iidm.serde.NetworkSerDe.write(source,
                new com.powsybl.iidm.serde.ExportOptions().setExcludedExtensions(UNSUPPORTED_EXTENSIONS), staged);
        try (NetworkStoreService service = new NetworkStoreService(baseUri)) {
            long copyStart = System.nanoTime();
            Network stored = com.powsybl.iidm.serde.NetworkSerDe.read(staged,
                    new com.powsybl.iidm.serde.ImportOptions().setExcludedExtensions(UNSUPPORTED_EXTENSIONS),
                    null, service.getNetworkFactory(), com.powsybl.commons.report.ReportNode.NO_OP);
            service.flush(stored);
            long storeMs = (System.nanoTime() - copyStart) / 1_000_000;
            System.out.printf("imported %s: %d buses, %d branches, %d generators, %d switches, %d busbar sections, %d VLs%n",
                    spec, source.getBusBreakerView().getBusCount(), source.getBranchCount(),
                    source.getGeneratorCount(), (int) source.getSwitchStream().count(),
                    (int) source.getBusbarSectionStream().count(), source.getVoltageLevelCount());
            System.out.printf("read %d ms, store %d ms%n", readMs, storeMs);
            System.out.println("UUID=" + service.getNetworkUuid(stored));
        } finally {
            java.nio.file.Files.deleteIfExists(staged);
        }
    }

    private static void doRun(String baseUri, UUID uuid, PreloadingStrategy preloading,
                              String[] threadCounts, int repeats, int contingencyCount, int busContingencies,
                              boolean contingencyPropagation) {
        for (int repeat = 0; repeat < repeats; repeat++) {
            for (String threadCountStr : threadCounts) {
                int threadCount = Integer.parseInt(threadCountStr.trim());
                // fresh service and network for every run: cold client cache, as in a gridsuite-style
                // process that loads the network once per analysis
                try (NetworkStoreService service = new NetworkStoreService(baseUri)) {
                    long loadStart = System.nanoTime();
                    Network network = service.getNetwork(uuid, preloading);
                    List<Contingency> contingencies = contingencies(network, contingencyCount, busContingencies);
                    long loadMs = (System.nanoTime() - loadStart) / 1_000_000;

                    SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();
                    // the full node-breaker PEGASE fixture does not converge from a flat start
                    parameters.setLoadFlowParameters(new LoadFlowParameters()
                            .setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES));
                    parameters.addExtension(OpenSecurityAnalysisParameters.class,
                            new OpenSecurityAnalysisParameters().setThreadCount(threadCount)
                                    .setContingencyPropagation(contingencyPropagation));

                    ContingenciesProvider provider = n -> contingencies;
                    SecurityAnalysisRunParameters runParameters = new SecurityAnalysisRunParameters()
                            .setSecurityAnalysisParameters(parameters)
                            .setComputationManager(LocalComputationManager.getDefault());

                    long start = System.nanoTime();
                    SecurityAnalysisReport report = SecurityAnalysis.run(network,
                            network.getVariantManager().getWorkingVariantId(), provider, runParameters);
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                    List<PostContingencyResult> results = report.getResult().getPostContingencyResults();
                    long violations = results.stream()
                            .mapToLong(r -> r.getLimitViolationsResult().getLimitViolations().size()).sum();
                    long converged = results.stream()
                            .filter(r -> "CONVERGED".equals(r.getStatus().name()))
                            .count();
                    System.out.printf("RESULT repeat=%d threads=%d sa=%d ms (network load %d ms) contingencies=%d results=%d converged=%d violations=%d%n",
                            repeat, threadCount, elapsedMs, loadMs, contingencies.size(), results.size(), converged, violations);
                }
            }
        }
    }

    public static void main(String[] args) throws java.io.IOException {
        String command = args[0];
        if ("import".equals(command)) {
            doImport(args[1], args[2]);
        } else if ("run".equals(command)) {
            doRun(args[1], UUID.fromString(args[2]), PreloadingStrategy.valueOf(args[3]),
                    args[4].split(","), Integer.parseInt(args[5]),
                    "all".equals(args[6]) ? Integer.MAX_VALUE : Integer.parseInt(args[6]),
                    args.length > 7 ? Integer.parseInt(args[7]) : 0,
                    args.length <= 8 || Boolean.parseBoolean(args[8]));
        } else {
            throw new IllegalArgumentException("unknown command " + command);
        }
    }
}
