/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.TwoSides;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A network listener that collects the elements whose state is modified while it is enabled. It is used by the
 * fast (Woodbury) DC security analysis to restore only the elements actually impacted by a contingency (and the
 * associated operator strategy actions) instead of the whole network after each contingency.
 *
 * <p>Modifications of sub-elements (generators, loads, shunts) are mapped to their bus, which is the granularity
 * at which the per-bus state is saved and restored (see {@link BusState}). Modifications of branches and HVDC
 * links are collected as such.</p>
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class ModifiedElementsCollector extends AbstractLfNetworkListener {

    private final Set<LfBus> modifiedBuses = new LinkedHashSet<>();

    private final Set<LfBranch> modifiedBranches = new LinkedHashSet<>();

    private final Set<LfHvdc> modifiedHvdcs = new LinkedHashSet<>();

    private boolean enabled = true;

    public Set<LfBus> getModifiedBuses() {
        return modifiedBuses;
    }

    public Set<LfBranch> getModifiedBranches() {
        return modifiedBranches;
    }

    public Set<LfHvdc> getModifiedHvdcs() {
        return modifiedHvdcs;
    }

    /**
     * Enable or disable the collection. Disabling is used while restoring the collected elements, so that the
     * setter calls performed during the restoration do not add back the elements being iterated over.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void reset() {
        modifiedBuses.clear();
        modifiedBranches.clear();
        modifiedHvdcs.clear();
    }

    public void addModifiedBus(LfBus bus) {
        if (enabled && bus != null) {
            modifiedBuses.add(bus);
        }
    }

    public void addModifiedBranch(LfBranch branch) {
        if (enabled && branch != null) {
            modifiedBranches.add(branch);
        }
    }

    public void addModifiedHvdc(LfHvdc hvdc) {
        if (enabled && hvdc != null) {
            modifiedHvdcs.add(hvdc);
        }
    }

    @Override
    public void onGeneratorVoltageControlChange(LfBus controllerBus, boolean newVoltageControllerEnabled) {
        addModifiedBus(controllerBus);
    }

    @Override
    public void onGeneratorReactivePowerControlChange(LfBus controllerBus, boolean newReactiveControllerEnabled) {
        addModifiedBus(controllerBus);
    }

    @Override
    public void onLoadActivePowerTargetChange(LfLoad load, double oldTargetP, double newTargetP) {
        addModifiedBus(load.getBus());
    }

    @Override
    public void onLoadReactivePowerTargetChange(LfLoad load, double oldTargetQ, double newTargetQ) {
        addModifiedBus(load.getBus());
    }

    @Override
    public void onGenerationActivePowerTargetChange(LfGenerator generator, double oldGenerationTargetP, double newGenerationTargetP) {
        addModifiedBus(generator.getBus());
    }

    @Override
    public void onGenerationReactivePowerTargetChange(LfBus bus, double oldGenerationTargetQ, double newGenerationTargetQ) {
        addModifiedBus(bus);
    }

    @Override
    public void onLoadDisablingStatusChange(LfLoad load) {
        addModifiedBus(load.getBus());
    }

    @Override
    public void onGenerationDisablingStatusChange(LfGenerator generator, boolean disabled) {
        addModifiedBus(generator.getBus());
    }

    @Override
    public void onShuntSusceptanceChange(LfShunt shunt, double b) {
        addModifiedBus(shunt.getBus());
    }

    @Override
    public void onShuntVoltageControlChange(LfShunt controllerShunt, boolean newVoltageControllerEnabled) {
        addModifiedBus(controllerShunt.getBus());
    }

    @Override
    public void onTransformerPhaseControlChange(LfBranch controllerBranch, boolean newPhaseControlEnabled) {
        addModifiedBranch(controllerBranch);
    }

    @Override
    public void onTransformerVoltageControlChange(LfBranch controllerBranch, boolean newVoltageControllerEnabled) {
        addModifiedBranch(controllerBranch);
    }

    @Override
    public void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition) {
        addModifiedBranch(branch);
    }

    @Override
    public void onBranchConnectionStatusChange(LfBranch branch, TwoSides side, boolean connected) {
        addModifiedBranch(branch);
    }

    @Override
    public void onHvdcAcEmulationStatusChange(LfHvdc hvdc, LfHvdc.AcEmulationControl.AcEmulationStatus acEmulationStatus) {
        addModifiedHvdc(hvdc);
    }

    @Override
    public void onDisableChange(LfElement element, boolean disabled) {
        switch (element) {
            case LfBus bus -> addModifiedBus(bus);
            case LfBranch branch -> addModifiedBranch(branch);
            case LfHvdc hvdc -> addModifiedHvdc(hvdc);
            case LfGenerator generator -> addModifiedBus(generator.getBus());
            case LfShunt shunt -> addModifiedBus(shunt.getBus());
            default -> {
                // other element types have no per-bus/branch/hvdc saved state to restore
            }
        }
    }
}
