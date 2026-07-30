/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.SwitchContingency;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Probe (not a build test: the class name does not match the surefire pattern) running a security analysis with
 * alternative equations on the "full" fixtures of the test2 repository, which enable every OpenLoadFlow modeling
 * feature at once - in particular shared / coordinated voltage control, whose multi-controller groups are the
 * reactive power distribution (DISTR_Q) case suspected of leaving a variable without its equation when a contingency
 * trips one of the controllers of a group.
 *
 * <p>Point it at the fixtures with:
 * <pre>./mvnw test -Dtest=FullNetworkAlternativeEquationsProbe -Dfixtures.dir=/path/to/test2/python/data</pre>
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
class FullNetworkAlternativeEquationsProbe extends AbstractOpenSecurityAnalysisTest {

    FullNetworkAlternativeEquationsProbe(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    @BeforeEach
    void setUpProbe() {
        // sparse matrices, and quiet logs except the fallback warnings we are after
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
        loadFlowProvider = new OpenLoadFlowProvider(new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
        ((Logger) LoggerFactory.getLogger("com.powsybl")).setLevel(Level.WARN);
    }

    private static Path fixture(String name) {
        Path path = Path.of(System.getProperty("fixtures.dir", "target/full-networks")).resolve(name);
        assumeTrue(Files.exists(path), "fixture not available: " + path);
        return path;
    }

    /**
     * Contingencies on the generators sharing a voltage control with other generators: those are the ones that force
     * the reactive power distribution (DISTR_Q) of a group to be recomputed.
     */
    private static List<Contingency> sharedVoltageControlGeneratorContingencies(Network network, int max) {
        // group the voltage regulating generators by the bus they regulate: a bus regulated by several generators is a
        // shared (coordinated) voltage control, whose reactive distribution a generator loss reconfigures
        Map<String, List<String>> generatorsByRegulatedBus = new TreeMap<>();
        for (Generator generator : network.getGenerators()) {
            if (generator.isVoltageRegulatorOn() && generator.getRegulatingTerminal() != null
                    && generator.getRegulatingTerminal().getBusView().getBus() != null) {
                generatorsByRegulatedBus
                        .computeIfAbsent(generator.getRegulatingTerminal().getBusView().getBus().getId(), k -> new ArrayList<>())
                        .add(generator.getId());
            }
        }
        List<Contingency> contingencies = new ArrayList<>();
        for (List<String> generators : generatorsByRegulatedBus.values()) {
            if (generators.size() > 1) {
                // trip one controller of the group: the remaining ones must redistribute
                contingencies.add(Contingency.generator(generators.get(0)));
                if (contingencies.size() >= max) {
                    break;
                }
            }
        }
        System.out.printf("shared voltage control groups: %d, generator contingencies probed: %d%n",
                generatorsByRegulatedBus.values().stream().filter(g -> g.size() > 1).count(), contingencies.size());
        return contingencies;
    }

    private void probe(String fixtureName, int maxContingencies) {
        Network network = Network.read(fixture(fixtureName));
        System.out.printf("%s: %d buses, %d generators, %d branches%n", fixtureName,
                network.getBusView().getBusStream().count(), network.getGeneratorCount(),
                network.getBranchCount());

        List<Contingency> contingencies = new ArrayList<>(sharedVoltageControlGeneratorContingencies(network, maxContingencies));
        // also probe branch contingencies: islanding and topology changes are the other way a contingency
        // reconfigures which equations must be active
        network.getBranchStream().limit(maxContingencies * 5L)
                .forEach(branch -> contingencies.add(Contingency.branch(branch.getId())));
        // and the voltage regulating generators, whose loss switches their bus off voltage control
        network.getGeneratorStream().filter(Generator::isVoltageRegulatorOn).limit(maxContingencies)
                .forEach(generator -> contingencies.add(Contingency.generator(generator.getId())));
        // switch contingencies retain switches, which OLF models as zero impedance branches (the DUMMY_P / DUMMY_Q
        // variables with their ZERO_PHI / ZERO_V or DUMMY_TARGET equations): that regime dominates the reported
        // failure, and a bus gaining or losing a zero impedance branch changes its alternative equations eligibility
        network.getSwitchStream()
                .filter(sw -> !sw.isOpen())
                .limit(maxContingencies * 3L)
                .forEach(sw -> contingencies.add(new Contingency(sw.getId(), new SwitchContingency(sw.getId()))));
        System.out.printf("%s: %d contingencies probed%n", fixtureName, contingencies.size());
        assumeTrue(!contingencies.isEmpty(), "no contingency to probe in " + fixtureName);

        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = saParameters.getLoadFlowParameters();
        // transformer and shunt voltage control would disable the alternative equations up front (existing fallback),
        // so leave them off to actually exercise the alternative modeling on everything else this fixture enables
        // the fixture is documented as converging from a DC initialisation
        lfParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
        lfParameters.setTransformerVoltageControlOn(false);
        lfParameters.setShuntCompensatorVoltageControlOn(false);
        OpenLoadFlowParameters.create(lfParameters)
                .setAlternativeEquations(true)
                .setSecondaryVoltageControl(false);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, Collections.emptyList(), saParameters);
        System.out.printf("%s: pre-contingency %s, %d post-contingency results%n", fixtureName,
                result.getPreContingencyResult().getStatus(), result.getPostContingencyResults().size());
        result.getPostContingencyResults().stream()
                .collect(java.util.stream.Collectors.groupingBy(r -> r.getStatus(), TreeMap::new, java.util.stream.Collectors.counting()))
                .forEach((status, count) -> System.out.printf("  %s: %d%n", status, count));
    }

    @Test
    void probePegase9241Full() {
        probe("pegase9241_full.xiidm.gz", 40);
    }

    @Test
    void probePegase13659Full() {
        probe("pegase13659_full.xiidm.gz", 40);
    }
}
