/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.contingency.violations.BusBreakerViolationLocation;
import com.powsybl.contingency.violations.NodeBreakerViolationLocation;
import com.powsybl.contingency.violations.ViolationLocation;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.LoadAsymmetrical;
import com.powsybl.iidm.network.extensions.ReferenceTerminals;
import com.powsybl.iidm.network.extensions.SlackTerminal;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.results.BusResult;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class LfBusImpl extends AbstractLfBus {

    private final Ref<Bus> busRef;

    private final String id;

    private final double nominalV;

    private final double lowVoltageLimit;

    private final double highVoltageLimit;

    private final boolean participating;

    private final boolean breakers;

    private final Country country;

    private final List<String> bbsIds;

    private final double fictitiousInjectionTargetP;

    private final double fictitiousInjectionTargetQ;

    // Lazy initialiation
    private ViolationLocation violationLocation = null;

    private boolean violationLocationComputed = false;

    // voltage level id, cached at build time so the results and violations never go back to the
    // iidm network (see the iidm free run phase of the multi thread copy mode)
    private final String voltageLevelId;

    // bus breaker bus ids of this (bus view) bus, lazily computed from the iidm network and
    // materialized before the copies are taken in the multi thread copy mode
    private List<String> mergedBusIds;

    protected LfBusImpl(Bus bus, LfNetwork network, double v, double angle, LfNetworkParameters parameters,
                        boolean participating) {
        super(network, v, angle, bus.getSynchronousComponent().getNum(), parameters);
        this.busRef = Ref.create(bus, parameters.isCacheEnabled());
        this.id = bus.getId();
        voltageLevelId = bus.getVoltageLevel().getId();
        nominalV = bus.getVoltageLevel().getNominalV();
        lowVoltageLimit = bus.getVoltageLevel().getLowVoltageLimit();
        highVoltageLimit = bus.getVoltageLevel().getHighVoltageLimit();
        this.participating = participating;
        this.breakers = parameters.isBreakers();
        country = bus.getVoltageLevel().getSubstation().flatMap(Substation::getCountry).orElse(null);
        fictitiousInjectionTargetP = bus.getFictitiousP0() / PerUnit.SB;
        fictitiousInjectionTargetQ = bus.getFictitiousQ0() / PerUnit.SB;
        if (bus.getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER) {
            bbsIds = bus.getConnectedTerminalStream()
                    .map(Terminal::getConnectable)
                    .filter(BusbarSection.class::isInstance)
                    .map(Connectable::getId)
                    .toList();
        } else {
            bbsIds = Collections.emptyList();
        }

    }

    private static void createAsym(Bus bus, LfBusImpl lfBus) {
        double totalDeltaPa = 0;
        double totalDeltaQa = 0;
        double totalDeltaPb = 0;
        double totalDeltaQb = 0;
        double totalDeltaPc = 0;
        double totalDeltaQc = 0;
        for (Load load : bus.getLoads()) {
            var extension = load.getExtension(LoadAsymmetrical.class);
            if (extension != null) {
                totalDeltaPa += extension.getDeltaPa() / PerUnit.SB;
                totalDeltaQa += extension.getDeltaQa() / PerUnit.SB;
                totalDeltaPb += extension.getDeltaPb() / PerUnit.SB;
                totalDeltaQb += extension.getDeltaQb() / PerUnit.SB;
                totalDeltaPc += extension.getDeltaPc() / PerUnit.SB;
                totalDeltaQc += extension.getDeltaQc() / PerUnit.SB;
            }
        }
        lfBus.setAsym(new LfAsymBus(totalDeltaPa, totalDeltaQa, totalDeltaPb, totalDeltaQb, totalDeltaPc, totalDeltaQc));
    }

    protected LfBusImpl(LfBusImpl other, LfNetwork network) {
        super(other, network);
        this.busRef = other.busRef;
        this.id = other.id;
        this.voltageLevelId = other.voltageLevelId;
        this.nominalV = other.nominalV;
        this.lowVoltageLimit = other.lowVoltageLimit;
        this.highVoltageLimit = other.highVoltageLimit;
        this.participating = other.participating;
        this.breakers = other.breakers;
        this.country = other.country;
        this.bbsIds = other.bbsIds;
        this.fictitiousInjectionTargetP = other.fictitiousInjectionTargetP;
        this.fictitiousInjectionTargetQ = other.fictitiousInjectionTargetQ;
        // lazy caches of iidm derived data: shared, they are immutable once computed (and
        // materialized before the copies are taken in the multi thread copy mode)
        this.violationLocation = other.violationLocation;
        this.violationLocationComputed = other.violationLocationComputed;
        this.mergedBusIds = other.mergedBusIds;
    }

    public static LfBusImpl create(Bus bus, LfNetwork network, LfNetworkParameters parameters, boolean participating) {
        Objects.requireNonNull(bus);
        Objects.requireNonNull(parameters);
        var lfBus = new LfBusImpl(bus, network, bus.getV(), Math.toRadians(bus.getAngle()), parameters, participating);
        if (parameters.isAsymmetrical()) {
            createAsym(bus, lfBus);
        }
        return lfBus;
    }

    private Bus getBus() {
        return busRef.get();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getVoltageLevelId() {
        return voltageLevelId;
    }

    @Override
    public boolean isFictitious() {
        return false;
    }

    @Override
    public double getNominalV() {
        return nominalV;
    }

    @Override
    public double getLowVoltageLimit() {
        return lowVoltageLimit / nominalV;
    }

    @Override
    public double getHighVoltageLimit() {
        return highVoltageLimit / nominalV;
    }

    @Override
    public void updateState(LfNetworkStateUpdateParameters parameters) {
        var bus = getBus();
        if (!parameters.isDc()) {
            bus.setV(Math.max(v, 0.0));
        }
        bus.setAngle(Math.toDegrees(angle));

        // update slack bus
        if (slack && parameters.isWriteSlackBus()) {
            SlackTerminal.attach(bus);
        }
        if (reference && parameters.isWriteReferenceTerminals() && parameters.getReferenceBusSelectionMode() == ReferenceBusSelectionMode.FIRST_SLACK) {
            bus.getConnectedTerminalStream().findFirst().ifPresent(ReferenceTerminals::addTerminal);
        }

        super.updateState(parameters);
    }

    @Override
    public boolean isParticipating() {
        return participating;
    }

    @Override
    public List<BusResult> createBusResults() {
        if (breakers) {
            if (bbsIds.isEmpty()) {
                return List.of(new BusResult(getVoltageLevelId(), id, v, Math.toDegrees(angle)));
            } else {
                return bbsIds.stream()
                        .map(bbsId -> new BusResult(getVoltageLevelId(), bbsId, v, Math.toDegrees(angle)))
                        .collect(Collectors.toList());
            }
        } else {
            if (mergedBusIds == null) {
                // only reached at build time (materializeIidmDerivedData), copies share the computed list
                var bus = getBus();
                mergedBusIds = bus.getVoltageLevel().getBusBreakerView().getBusesFromBusViewBusId(bus.getId())
                        .stream().map(Identifiable::getId).toList();
            }
            return mergedBusIds.stream()
                    .map(busId -> new BusResult(getVoltageLevelId(), busId, v, Math.toDegrees(angle))).collect(Collectors.toList());
        }
    }

    @Override
    public void materializeIidmDerivedData() {
        createBusResults();
        getViolationLocation();
    }

    @Override
    public Optional<Country> getCountry() {
        return Optional.ofNullable(country);
    }

    @Override
    public double getTargetP() {
        if (asym != null) {
            return getGenerationTargetP();
            // we use the detection of the asymmetry extension at bus to check if we are in asymmetrical calculation
            // in this case, load target is set to zero and the constant-balanced load model (in 3 phased representation) is replaced by a model depending on v1, v2, v0 (equivalent fortescue representation)
        }
        return super.getTargetP();
    }

    @Override
    public double getTargetQ() {
        if (asym != null) {
            return getGenerationTargetQ();
            // we use the detection of the asymmetry extension at bus to check if we are in asymmetrical calculation
            // in this case, load target is set to zero and the constant power load model (in 3 phased representation) is replaced by a model depending on v1, v2, v0 (equivalent fortescue representation)
        }
        return super.getTargetQ();
    }

    @Override
    public double getFictitiousInjectionTargetP() {
        return fictitiousInjectionTargetP;
    }

    @Override
    public double getFictitiousInjectionTargetQ() {
        return fictitiousInjectionTargetQ;
    }

    public ViolationLocation getViolationLocation() {
        if (!violationLocationComputed) {
            TopologyKind topologyKind = getBus().getVoltageLevel().getTopologyKind();
            violationLocation = switch (topologyKind) {
                case NODE_BREAKER -> {
                    List<Integer> nodes = getBus().getConnectedTerminalStream().map(t -> t.getNodeBreakerView().getNode()).toList();
                    yield nodes.isEmpty() ? null : new NodeBreakerViolationLocation(getVoltageLevelId(), nodes);
                }
                case BUS_BREAKER -> {
                    // are we in breaker mode ?
                    var busBreakerView = getBus().getVoltageLevel().getBusBreakerView();
                    if (getBus() == busBreakerView.getBus(getBus().getId())) {
                        yield new BusBreakerViolationLocation(List.of(getBus().getId()));
                    } else {
                        // Bus is a merged bus from thebus view
                        List<String> busIds = busBreakerView
                                .getBusStreamFromBusViewBusId(getBus().getId())
                                .map(Identifiable::getId)
                                .sorted().toList();
                        yield busIds.isEmpty() ? null : new BusBreakerViolationLocation(busIds);
                    }
                }
            };
            violationLocationComputed = true;
        }
        return violationLocation;
    }
}
