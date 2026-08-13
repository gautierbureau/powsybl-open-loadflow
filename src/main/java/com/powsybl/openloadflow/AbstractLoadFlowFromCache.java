/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.lf.AbstractLoadFlowParameters;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfTopoConfig;
import com.powsybl.openloadflow.network.impl.LfLegBranch;
import com.powsybl.openloadflow.util.Reports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractLoadFlowFromCache<P extends AbstractLoadFlowParameters<P>> {

    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractLoadFlowFromCache.class);

    protected final Network network;

    protected final LoadFlowParameters parameters;

    protected final OpenLoadFlowParameters parametersExt;

    protected final P acOrDcParameters;

    protected final ReportNode reportNode;

    protected AbstractLoadFlowFromCache(Network network, LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                               P acOrDcParameters, ReportNode reportNode) {
        this.network = Objects.requireNonNull(network);
        this.parameters = Objects.requireNonNull(parameters);
        this.parametersExt = Objects.requireNonNull(parametersExt);
        this.acOrDcParameters = Objects.requireNonNull(acOrDcParameters);
        this.reportNode = Objects.requireNonNull(reportNode);
    }

    /**
     * Give back to a cached {@link LfNetwork} a report node dedicated to the run which is starting.
     * <p>
     * A cached LfNetwork outlives the run that created it, but it keeps the report node it was built with.
     * Without this, every run appends its reports (voltage initializer, outer loops, completion status) to
     * the report node of the very first run: the reports of the current run are not visible from the report
     * node given to that run, and the tree kept alive by the cache grows by a few nodes at each run, without
     * any bound.
     */
    protected void refreshReportNode(LfNetwork lfNetwork) {
        ReportNode lfNetworkReportNode = acOrDcParameters.getNetworkParameters().isAcDcNetwork()
                ? Reports.createRootAcDcLfNetworkReportNode(reportNode, lfNetwork.getNumCC())
                : Reports.createRootLfNetworkReportNode(reportNode, lfNetwork.getNumCC(),
                        lfNetwork.getSynchronousNetworks().get(0).getNumSC());
        lfNetwork.setReportNode(Reports.includeLfNetworkReportNode(reportNode, lfNetworkReportNode));
    }

    protected void configureTopoConfig(LfTopoConfig topoConfig) {
        for (String switchId : parametersExt.getActionableSwitchesIds()) {
            Switch sw = network.getSwitch(switchId);
            if (sw != null) {
                if (sw.isOpen()) {
                    topoConfig.getSwitchesToClose().add(sw);
                } else {
                    topoConfig.getSwitchesToOpen().add(sw);
                }
            } else {
                LOGGER.warn("Actionable switch '{}' does not exist", switchId);
            }
        }
        for (String transformerId : parametersExt.getActionableTransformersIds()) {
            Branch<?> branch = network.getBranch(transformerId);
            if (branch != null) {
                topoConfig.addBranchIdWithRtcToRetain(transformerId);
                topoConfig.addBranchIdWithPtcToRetain(transformerId);
            } else {
                ThreeWindingsTransformer tw3 = network.getThreeWindingsTransformer(transformerId);
                if (tw3 != null) {
                    for (ThreeSides side : ThreeSides.values()) {
                        topoConfig.addBranchIdWithRtcToRetain(LfLegBranch.getId(side, transformerId));
                        topoConfig.addBranchIdWithPtcToRetain(LfLegBranch.getId(side, transformerId));
                    }
                }
                LOGGER.warn("Actionable transformer '{}' does not exist", transformerId);
            }
        }
        if (topoConfig.isBreaker()) {
            acOrDcParameters.getNetworkParameters().setBreakers(true);
        }
    }
}
