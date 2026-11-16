/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.LfLegBranch;
import com.powsybl.openloadflow.network.impl.LfStarBus;
import com.powsybl.openloadflow.network.impl.Transformers;
import com.powsybl.openloadflow.network.util.ZeroImpedanceFlows;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.monitor.StateMonitorIndex;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.BusResult;
import com.powsybl.security.results.ChangedPhaseTapChanger;
import com.powsybl.security.results.ThreeWindingsTransformerResult;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.powsybl.openloadflow.network.LfBranch.BranchType.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractNetworkResult {

    protected final LfNetwork network;

    protected final StateMonitorIndex monitorIndex;

    protected final StateMonitorIndex zeroImpedanceMonitorIndex;

    protected final MonitoredElementIndex monitoredElementIndex;

    protected final boolean createResultExtension;

    protected final LoadFlowModel loadFlowModel;

    protected final double dcPowerFactor;

    protected Map<LfBranch, Integer> phaseTapChangerInfos = new HashMap<>();

    static final List<LfBranch.BranchType> T3WT_BRANCH_TYPES = List.of(TRANSFO_3_LEG_1, TRANSFO_3_LEG_2, TRANSFO_3_LEG_3);

    /**
     * The monitors of a security analysis, and the elements they select in its networks: share one
     * {@link MonitoredElementIndex} across the results of a run so each network is searched once per monitor.
     */
    public record StateMonitorIndexes(StateMonitorIndex monitorIndex, StateMonitorIndex zeroImpedanceMonitorIndex,
                                      MonitoredElementIndex monitoredElementIndex) {

        public StateMonitorIndexes {
            Objects.requireNonNull(monitorIndex);
            Objects.requireNonNull(zeroImpedanceMonitorIndex);
            Objects.requireNonNull(monitoredElementIndex);
        }

        public StateMonitorIndexes(StateMonitorIndex monitorIndex, StateMonitorIndex zeroImpedanceMonitorIndex) {
            this(monitorIndex, zeroImpedanceMonitorIndex, new MonitoredElementIndex());
        }
    }

    protected final Map<LfBranch, ChangedPhaseTapChanger> changedPhaseTapChangers = new HashMap<>();

    protected AbstractNetworkResult(LfNetwork network, StateMonitorIndexes monitorIndexes, boolean createResultExtension, LoadFlowModel loadFlowModel, double dcPowerFactor) {
        this.network = Objects.requireNonNull(network);
        this.monitorIndex = Objects.requireNonNull(monitorIndexes.monitorIndex);
        this.zeroImpedanceMonitorIndex = Objects.requireNonNull(monitorIndexes.zeroImpedanceMonitorIndex);
        this.monitoredElementIndex = monitorIndexes.monitoredElementIndex;
        this.createResultExtension = createResultExtension;
        this.loadFlowModel = loadFlowModel;
        this.dcPowerFactor = dcPowerFactor;
    }

    protected void addResults(StateMonitor monitor, Consumer<LfBranch> branchConsumer, Predicate<LfBranch> isBranchDisabled,
                              Consumer<LfBus> busConsumer, Consumer<String> threeWindingsTransformerResultsConsumer) {
        Objects.requireNonNull(monitor);
        for (LfBranch lfBranch : monitoredElementIndex.getBranches(network, monitor)) {
            if (!isBranchDisabled.test(lfBranch)) {
                branchConsumer.accept(lfBranch);
            }
        }

        for (LfBus lfBus : monitoredElementIndex.getBuses(network, monitor)) {
            if (!lfBus.isDisabled()) {
                busConsumer.accept(lfBus);
            }
        }

        if (!monitor.getThreeWindingsTransformerIds().isEmpty()) {
            monitor.getThreeWindingsTransformerIds().stream()
                    .filter(id -> network.getBusById(LfStarBus.getId(id)) != null && !network.getBusById(LfStarBus.getId(id)).isDisabled())
                    .forEach(threeWindingsTransformerResultsConsumer);
        }
    }

    public abstract List<BusResult> getBusResults();

    public abstract List<ThreeWindingsTransformerResult> getThreeWindingsTransformerResults();

    public abstract List<BranchResult> getBranchResults();

    protected abstract void storeInitialPhaseTapChangerInfo();

    protected void updateChangedPhaseTapChanger() {
        for (Map.Entry<LfBranch, Integer> ptcInfo : phaseTapChangerInfos.entrySet()) {
            LfBranch b = ptcInfo.getKey();
            int currentTap = ptcInfo.getValue();
            int newTapPosition = Transformers.findTapPosition(b.getPhaseTapChanger().orElseThrow(),
                    Math.toDegrees(b.getPiModel().getA1()));
            if (currentTap != newTapPosition) {
                changedPhaseTapChangers.put(b, new ChangedPhaseTapChanger(b.getMainOriginalId(), b.getOriginalSide().orElse(null), currentTap, newTapPosition));
            }
        }
    }

    public abstract void update();

    private boolean isATransfo3WBranch(LfBranch lfBranch) {
        return T3WT_BRANCH_TYPES.contains(lfBranch.getBranchType());
    }

    private boolean isContainingAMonitoredBranch(LfZeroImpedanceNetwork zeroImpedanceNetwork, StateMonitor monitor) {
        for (LfBranch lfBranch : zeroImpedanceNetwork.getGraph().edgeSet()) {
            if (isATransfo3WBranch(lfBranch)) {
                LfLegBranch lfLegBranch = (LfLegBranch) lfBranch;
                if (monitor.getThreeWindingsTransformerIds().contains(lfLegBranch.getTwt().getId())) {
                    return true;
                }
            } else {
                if (monitor.getBranchIds().contains(lfBranch.getId())) {
                    return true;
                }
            }
        }

        return false;
    }

    protected Map<String, LfBranch.LfBranchResults> storeResultsForZeroImpedanceBranches(StateMonitor monitor, LfNetwork network) {
        Map<String, LfBranch.LfBranchResults> zeroImpedanceFlows = new LinkedHashMap<>();
        if (monitor.getBranchIds().isEmpty() && monitor.getThreeWindingsTransformerIds().isEmpty()) {
            // Nothing to store as no branches are monitored
            return zeroImpedanceFlows;
        }
        for (LfZeroImpedanceNetwork zeroImpedanceNetwork : network.getZeroImpedanceNetworks(loadFlowModel)) {
            if (isContainingAMonitoredBranch(zeroImpedanceNetwork, monitor)) {
                new ZeroImpedanceFlows(zeroImpedanceNetwork.getGraph(), zeroImpedanceNetwork.getSpanningTree(), loadFlowModel, dcPowerFactor)
                        .computeFlows(true, zeroImpedanceFlows);
            }
        }
        return zeroImpedanceFlows;
    }

    public Map<LfBranch, ChangedPhaseTapChanger> getChangedPhaseTapChangers() {
        return changedPhaseTapChangers;
    }
}
