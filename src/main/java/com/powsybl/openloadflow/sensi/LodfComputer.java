/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.fastdc.LodfCalculator;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;

import java.util.List;
import java.util.Objects;

/**
 * Network level entry point for the fast LODF (Line Outage Distribution Factor) matrix computation of
 * {@link LodfCalculator}, designed to be easily wired in external APIs (e.g. pypowsybl): it takes an iidm network and
 * branch ids, and hides the loading of the {@link LfNetwork} and the creation of the DC load flow context.
 *
 * <p>The computation is done on the main connected component, with phase control forced off (LODF factors are computed
 * at fixed phase tap positions) and zero impedance branches replaced by minimal impedance ones, as done in the Woodbury
 * based fast DC security analysis. Note that the minimal impedance replacement allows zero impedance branches to be
 * monitored, but the LODF factors of their outages remain undefined (reported as {@link Double#NaN}), as they transfer
 * almost all of their flow like a connectivity break.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public final class LodfComputer {

    private LodfComputer() {
    }

    public static DenseMatrix computeLodfMatrix(Network network, List<String> monitoredBranchIds, List<String> outagedBranchIds,
                                                LoadFlowParameters parameters) {
        return computeLodfMatrix(network, monitoredBranchIds, outagedBranchIds, parameters, new SparseMatrixFactory(), ReportNode.NO_OP);
    }

    /**
     * Computes the LODF matrix of the given monitored branches for single outages of the given branches.
     *
     * <p>The returned matrix is indexed by the positions in the given lists: row {@code l} corresponds to the
     * {@code l}-th monitored branch id and column {@code k} to the outage of the {@code k}-th outaged branch id.
     * See {@link LodfCalculator#computeLodfMatrix} for the conventions on the matrix values.
     *
     * @param network the network, whose main connected component is used for the computation
     * @param monitoredBranchIds the ids of the branches on which the flow change is observed
     * @param outagedBranchIds the ids of the branches whose outages are simulated
     * @param parameters the load flow parameters, used to build the DC equation system
     * @return the LODF matrix, with one row per monitored branch and one column per outaged branch
     */
    public static DenseMatrix computeLodfMatrix(Network network, List<String> monitoredBranchIds, List<String> outagedBranchIds,
                                                LoadFlowParameters parameters, MatrixFactory matrixFactory, ReportNode reportNode) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(monitoredBranchIds);
        Objects.requireNonNull(outagedBranchIds);
        Objects.requireNonNull(parameters);
        Objects.requireNonNull(matrixFactory);
        Objects.requireNonNull(reportNode);

        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.get(parameters);
        // phase control is forced off as LODF factors are computed at fixed phase tap positions
        DcLoadFlowParameters dcParameters = OpenLoadFlowParameters.createDcParameters(network, parameters, parametersExt,
                matrixFactory, new EvenShiloachGraphDecrementalConnectivityFactory<>(), true);
        // replace zero impedance branches by minimal impedance ones so that their LODF factors are defined
        dcParameters.getNetworkParameters().setMinImpedance(true);

        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters(), reportNode).getFirst();
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters, false)) {
            List<LfBranch> monitoredBranches = getLfBranches(lfNetwork, monitoredBranchIds);
            List<LfBranch> outagedBranches = getLfBranches(lfNetwork, outagedBranchIds);
            return LodfCalculator.computeLodfMatrix(context, monitoredBranches, outagedBranches);
        }
    }

    private static List<LfBranch> getLfBranches(LfNetwork lfNetwork, List<String> branchIds) {
        return branchIds.stream()
                .map(branchId -> {
                    LfBranch branch = lfNetwork.getBranchById(branchId);
                    if (branch == null) {
                        throw new PowsyblException("Branch '" + branchId + "' not found in the main connected component");
                    }
                    return branch;
                })
                .toList();
    }
}
