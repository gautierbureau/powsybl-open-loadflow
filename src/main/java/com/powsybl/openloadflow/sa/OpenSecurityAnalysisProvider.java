/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.google.auto.service.AutoService;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.commons.extensions.Extension;
import com.powsybl.commons.extensions.ExtensionJsonSerializer;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.graph.NaiveGraphConnectivityFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.util.PowsyblOpenLoadFlowVersion;
import com.powsybl.openloadflow.util.ProviderConstants;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisProvider;
import com.powsybl.security.SecurityAnalysisReport;
import com.powsybl.security.SecurityAnalysisRunParameters;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.writer.SecurityAnalysisResultWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * @author Florian Dupuy {@literal <florian.dupuy at rte-france.com>}
 */
@AutoService(SecurityAnalysisProvider.class)
public class OpenSecurityAnalysisProvider implements SecurityAnalysisProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenSecurityAnalysisProvider.class);

    private final MatrixFactory matrixFactory;

    private final GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory;

    public OpenSecurityAnalysisProvider(MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory) {
        this.matrixFactory = matrixFactory;
        this.connectivityFactory = connectivityFactory;
    }

    public OpenSecurityAnalysisProvider() {
        this(new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
    }

    @Override
    public CompletableFuture<SecurityAnalysisReport> run(Network network,
                                                         String workingVariantId,
                                                         ContingenciesProvider contingenciesProvider,
                                                         SecurityAnalysisRunParameters runParameters) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(workingVariantId);
        Objects.requireNonNull(contingenciesProvider);
        Objects.requireNonNull(runParameters);

        LOGGER.info("Version: {}", new PowsyblOpenLoadFlowVersion());

        LoadFlowParameters loadFlowParameters = runParameters.getSecurityAnalysisParameters().getLoadFlowParameters();
        OpenLoadFlowParameters loadFlowParametersExt = OpenLoadFlowParameters.get(loadFlowParameters);
        OpenLoadFlowParameters.log(loadFlowParameters, loadFlowParametersExt);

        // FIXME implement a fast incremental connectivity algorithm
        GraphConnectivityFactory<LfBus, LfBranch> selectedConnectivityFactory;
        if (runParameters.getOperatorStrategies().isEmpty() && !loadFlowParametersExt.isSimulateAutomationSystems()) {
            selectedConnectivityFactory = connectivityFactory;
        } else {
            LOGGER.warn("Naive (and slow!!!) connectivity algorithm has been selected because at least one operator strategy is configured");
            selectedConnectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        }

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters =
                OpenSecurityAnalysisParameters.getOrDefault(runParameters.getSecurityAnalysisParameters());

        // when "monitor all branches" is requested, synthesize a single state monitor covering all branches for the base
        // case and every contingency, instead of forcing the caller to enumerate them.
        List<StateMonitor> monitors = runParameters.getMonitors();
        if (openSecurityAnalysisParameters.isMonitorAllBranches()) {
            monitors = new ArrayList<>(monitors);
            Set<String> allBranchIds = network.getBranchStream()
                    .map(Identifiable::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            monitors.add(new StateMonitor(ContingencyContext.all(), allBranchIds, Collections.emptySet(), Collections.emptySet()));
        }

        AbstractSecurityAnalysis<?, ?, ?, ?, ?> securityAnalysis;
        if (loadFlowParameters.isDc()) {
            if (openSecurityAnalysisParameters.isDcFastMode()) {
                securityAnalysis = new WoodburyDcSecurityAnalysis(network, matrixFactory, selectedConnectivityFactory, monitors, runParameters.getReportNode());
            } else {
                securityAnalysis = new DcSecurityAnalysis(network, matrixFactory, selectedConnectivityFactory, monitors, runParameters.getReportNode());
            }
        } else {
            securityAnalysis = new AcSecurityAnalysis(network, matrixFactory, selectedConnectivityFactory, monitors, runParameters.getReportNode());
        }

        // streaming sink for results (default is a no-op). The run takes ownership and closes it once finished.
        SecurityAnalysisResultWriter resultWriter = runParameters.getResultWriter();
        securityAnalysis.setResultWriter(resultWriter);

        return securityAnalysis.run(workingVariantId, runParameters.getSecurityAnalysisParameters(), contingenciesProvider,
                runParameters.getComputationManager(), runParameters.getOperatorStrategies(), runParameters.getActions(), runParameters.getLimitReductions())
                .whenComplete((report, throwable) -> closeQuietly(resultWriter));
    }

    private static void closeQuietly(SecurityAnalysisResultWriter resultWriter) {
        try {
            resultWriter.close();
        } catch (Exception e) {
            LOGGER.error("Failed to close the security analysis result writer", e);
        }
    }

    @Override
    public String getName() {
        return ProviderConstants.NAME;
    }

    @Override
    public String getVersion() {
        return new PowsyblOpenLoadFlowVersion().toString();
    }

    @Override
    public Optional<String> getLoadFlowProviderName() {
        return Optional.of(ProviderConstants.NAME);
    }

    @Override
    public Optional<ExtensionJsonSerializer> getSpecificParametersSerializer() {
        return Optional.of(new OpenSecurityAnalysisParameterJsonSerializer());
    }

    @Override
    public Optional<Extension<SecurityAnalysisParameters>> loadSpecificParameters(PlatformConfig platformConfig) {
        return Optional.of(OpenSecurityAnalysisParameters.load(platformConfig));
    }

    @Override
    public Optional<Extension<SecurityAnalysisParameters>> loadSpecificParameters(Map<String, String> properties) {
        return Optional.of(OpenSecurityAnalysisParameters.load(properties));
    }

    @Override
    public List<String> getSpecificParametersNames() {
        return OpenSecurityAnalysisParameters.SPECIFIC_PARAMETERS_NAMES;
    }

    @Override
    public void updateSpecificParameters(Extension<SecurityAnalysisParameters> extension, Map<String, String> properties) {
        ((OpenSecurityAnalysisParameters) extension).update(properties);
    }
}
