/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.ReactiveLimits;
import com.powsybl.iidm.network.extensions.VoltageRegulation;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfNetworkParameters;
import com.powsybl.openloadflow.network.LfNetworkStateUpdateParameters;
import com.powsybl.openloadflow.util.PerUnit;

import java.util.Objects;
import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class LfBatteryImpl extends AbstractLfGenerator {

    private final Ref<Battery> batteryRef;

    private final String id;

    private final double minP;

    private final double maxP;

    private final double initialTargetQ;

    private final ReactiveLimits reactiveLimits;

    private boolean initialParticipating;

    private boolean participating;

    private final double droop;

    private final double participationFactor;

    private final double maxTargetP;

    private final double minTargetP;

    private LfBatteryImpl(Battery battery, LfNetwork network, LfNetworkParameters parameters, LfNetworkLoadingReport report) {
        super(network, battery.getTargetP() / PerUnit.SB, parameters);
        this.batteryRef = Ref.create(battery, parameters.isCacheEnabled());
        this.id = battery.getId();
        this.minP = battery.getMinP();
        this.maxP = battery.getMaxP();
        this.initialTargetQ = battery.getTargetQ();
        this.reactiveLimits = battery.getReactiveLimits();
        var apcHelper = ActivePowerControlHelper.create(battery, battery.getMinP(), battery.getMaxP());
        initialParticipating = apcHelper.participating();
        participating = initialParticipating;
        participationFactor = apcHelper.participationFactor();
        droop = apcHelper.droop();
        minTargetP = apcHelper.minTargetP();
        maxTargetP = apcHelper.maxTargetP();

        if (!checkActivePowerControl(getId(), battery.getTargetP(), battery.getMaxP(), minTargetP, maxTargetP,
                parameters, report)) {
            participating = false;
        }

        // get voltage control from extension
        VoltageRegulation voltageRegulation = battery.getExtension(VoltageRegulation.class);
        if (voltageRegulation != null && voltageRegulation.isVoltageRegulatorOn()) {
            setVoltageControl(voltageRegulation.getTargetV(), battery.getTerminal(), voltageRegulation.getRegulatingTerminal(), parameters, report);
        }
    }

    protected LfBatteryImpl(LfBatteryImpl other, LfNetwork network) {
        super(other, network);
        this.batteryRef = other.batteryRef;
        this.id = other.id;
        this.minP = other.minP;
        this.maxP = other.maxP;
        this.initialTargetQ = other.initialTargetQ;
        this.reactiveLimits = other.reactiveLimits;
        this.initialParticipating = other.initialParticipating;
        this.participating = other.participating;
        this.droop = other.droop;
        this.participationFactor = other.participationFactor;
        this.maxTargetP = other.maxTargetP;
        this.minTargetP = other.minTargetP;
    }

    public static LfBatteryImpl create(Battery battery, LfNetwork network, LfNetworkParameters parameters, LfNetworkLoadingReport report) {
        Objects.requireNonNull(battery);
        Objects.requireNonNull(network);
        Objects.requireNonNull(parameters);
        Objects.requireNonNull(report);
        return new LfBatteryImpl(battery, network, parameters, report);
    }

    private Battery getBattery() {
        return batteryRef.get();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public double getTargetQ() {
        return initialTargetQ / PerUnit.SB;
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
    public double getMinTargetP() {
        return minTargetP / PerUnit.SB;
    }

    @Override
    public double getMaxTargetP() {
        return maxTargetP / PerUnit.SB;
    }

    @Override
    protected Optional<ReactiveLimits> getReactiveLimits() {
        return Optional.of(reactiveLimits);
    }

    @Override
    public boolean isParticipating() {
        return participating;
    }

    @Override
    public void setParticipating(boolean participating) {
        this.participating = participating;
    }

    @Override
    public double getDroop() {
        return droop;
    }

    @Override
    public double getParticipationFactor() {
        return participationFactor;
    }

    @Override
    public void updateState(LfNetworkStateUpdateParameters parameters) {
        var battery = getBattery();
        battery.getTerminal()
                .setP(-targetP * PerUnit.SB)
                .setQ(Double.isNaN(calculatedQ) ? -getTargetQ() * PerUnit.SB : -calculatedQ * PerUnit.SB);
    }

    @Override
    public void reApplyActivePowerControlChecks(LfNetworkParameters parameters, LfNetworkLoadingReport report) {
        participating = initialParticipating;
        if (!checkActivePowerControl(id, targetP * PerUnit.SB, maxP, minTargetP, maxTargetP,
                parameters, report)) {
            participating = false;
        }
    }
}
