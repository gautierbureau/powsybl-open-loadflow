/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.ReactiveLimits;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfNetworkParameters;
import com.powsybl.openloadflow.network.LfNetworkStateUpdateParameters;
import com.powsybl.openloadflow.util.PerUnit;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class LfBoundaryLineGenerator extends AbstractLfGenerator {

    private final Ref<BoundaryLine> boundaryLineRef;

    private final String originalId;

    private final double minP;

    private final double maxP;

    private final double initialTargetQ;

    private final ReactiveLimits reactiveLimits;

    private LfBoundaryLineGenerator(BoundaryLine boundaryLine, LfNetwork network, String controlledLfBusId, LfNetworkParameters parameters,
                                    LfNetworkLoadingReport report) {
        super(network, boundaryLine.getGeneration().getTargetP() / PerUnit.SB, parameters);
        this.boundaryLineRef = Ref.create(boundaryLine, parameters.isCacheEnabled());
        this.originalId = boundaryLine.getId();
        this.minP = boundaryLine.getGeneration().getMinP();
        this.maxP = boundaryLine.getGeneration().getMaxP();
        this.initialTargetQ = boundaryLine.getGeneration().getTargetQ();
        this.reactiveLimits = boundaryLine.getGeneration().getReactiveLimits();

        // local control only
        if (boundaryLine.getGeneration().isVoltageRegulationOn() && checkVoltageControlConsistency(parameters, report)) {
            // The controlled bus cannot be reached from the BoundaryLine parameters (there is no terminal in BoundaryLine.Generation)
            if (checkTargetV(getId(), boundaryLine.getGeneration().getTargetV() / boundaryLine.getTerminal().getVoltageLevel().getNominalV(),
                    boundaryLine.getTerminal().getVoltageLevel().getNominalV(), parameters, report)) {
                this.controlledBusId = Objects.requireNonNull(controlledLfBusId);
                this.targetV = boundaryLine.getGeneration().getTargetV() / boundaryLine.getTerminal().getVoltageLevel().getNominalV();
                this.generatorControlType = GeneratorControlType.VOLTAGE;
            }
        }
    }

    protected LfBoundaryLineGenerator(LfBoundaryLineGenerator other, LfNetwork network) {
        super(other, network);
        this.boundaryLineRef = other.boundaryLineRef;
        this.originalId = other.originalId;
        this.minP = other.minP;
        this.maxP = other.maxP;
        this.initialTargetQ = other.initialTargetQ;
        this.reactiveLimits = other.reactiveLimits;
    }

    public static LfBoundaryLineGenerator create(BoundaryLine boundaryLine, LfNetwork network, String controlledLfBusId, LfNetworkParameters parameters,
                                                 LfNetworkLoadingReport report) {
        Objects.requireNonNull(boundaryLine);
        Objects.requireNonNull(network);
        Objects.requireNonNull(parameters);
        Objects.requireNonNull(report);
        return new LfBoundaryLineGenerator(boundaryLine, network, controlledLfBusId, parameters, report);
    }

    @Override
    public String getId() {
        return originalId + "_GEN";
    }

    @Override
    public String getOriginalId() {
        return originalId;
    }

    @Override
    public OptionalDouble getRemoteControlReactiveKey() {
        return OptionalDouble.empty();
    }

    @Override
    public double getTargetQ() {
        return Networks.zeroIfNan(initialTargetQ) / PerUnit.SB;
    }

    @Override
    public double getMinP() {
        return minP / PerUnit.SB;
    }

    @Override
    public double getMaxP() {
        return maxP / PerUnit.SB;
    }

    @Override
    protected Optional<ReactiveLimits> getReactiveLimits() {
        return Optional.ofNullable(reactiveLimits);
    }

    @Override
    public void updateState(LfNetworkStateUpdateParameters parameters) {
        // nothing to update
    }
}
