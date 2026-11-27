/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.violations.LimitViolationFilter;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.SecurityAnalysisRunParameters;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.PostContingencyResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The AC security analysis refactorising each simulation on the pivots of the previous one gives the results of the
 * one choosing pivots of its own, to the solver precision.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ReusePivotsTest {

    private static SecurityAnalysisResult run(Network network, List<Contingency> contingencies, boolean reusePivots) {
        Set<String> branchIds = network.getBranchStream().map(Identifiable::getId).collect(Collectors.toSet());
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters().setLoadFlowParameters(lfParameters);
        parameters.addExtension(OpenSecurityAnalysisParameters.class, new OpenSecurityAnalysisParameters()
                .setCreateResultExtension(false)
                .setReusePivots(reusePivots));
        SecurityAnalysisRunParameters runParameters = new SecurityAnalysisRunParameters()
                .setFilter(new LimitViolationFilter())
                .setComputationManager(LocalComputationManager.getDefault())
                .setSecurityAnalysisParameters(parameters)
                .setReportNode(ReportNode.NO_OP)
                .setMonitors(List.of(new StateMonitor(ContingencyContext.all(), branchIds, Collections.emptySet(), Collections.emptySet())));
        return new OpenSecurityAnalysisProvider(new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>())
                .run(network, network.getVariantManager().getWorkingVariantId(), n -> contingencies, runParameters)
                .join()
                .getResult();
    }

    @Test
    void sameResultsWithReusedPivots() {
        Network network = IeeeCdfNetworkFactory.create118();
        List<Contingency> contingencies = network.getLineStream()
                .map(l -> new Contingency(l.getId(), new BranchContingency(l.getId())))
                .toList();

        SecurityAnalysisResult own = run(network, contingencies, false);
        SecurityAnalysisResult reused = run(network, contingencies, true);

        assertEquals(own.getPreContingencyResult().getStatus(), reused.getPreContingencyResult().getStatus());
        assertEquals(contingencies.size(), reused.getPostContingencyResults().size());
        for (int i = 0; i < own.getPostContingencyResults().size(); i++) {
            PostContingencyResult o = own.getPostContingencyResults().get(i);
            PostContingencyResult r = reused.getPostContingencyResults().get(i);
            assertEquals(o.getContingency().getId(), r.getContingency().getId());
            assertEquals(o.getStatus(), r.getStatus());
            List<BranchResult> oBranches = o.getNetworkResult().getBranchResults();
            List<BranchResult> rBranches = r.getNetworkResult().getBranchResults();
            assertEquals(oBranches.size(), rBranches.size());
            assertFalse(oBranches.isEmpty());
            for (int k = 0; k < oBranches.size(); k++) {
                assertEquals(oBranches.get(k).getBranchId(), rBranches.get(k).getBranchId());
                assertEquals(oBranches.get(k).getP1(), rBranches.get(k).getP1(), 1e-6);
                assertEquals(oBranches.get(k).getQ1(), rBranches.get(k).getQ1(), 1e-6);
            }
            assertEquals(o.getLimitViolationsResult().getLimitViolations().size(),
                         r.getLimitViolationsResult().getLimitViolations().size());
        }
    }
}
