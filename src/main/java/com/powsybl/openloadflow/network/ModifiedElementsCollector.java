/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.TwoSides;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Collects the buses and the branches that change while it listens to a network: what a contingency, the actions of an
 * operator strategy and the outer loops of the simulations that follow modify. A security analysis then restores
 * these elements only, with {@link NetworkState#restore(java.util.Collection, java.util.Collection)}, and not the
 * whole network after each contingency.
 * <p>Three kinds of changes come with no notification:
 * <ul>
 * <li>the voltage of the buses, written from the state vector: the partial restore writes the voltage of every bus;</li>
 * <li>the ratio and the phase shift of a transformer whose tap is a variable, written the same way: the branches that
 * can control a voltage or a flow are always in the collection;</li>
 * <li>what a contingency changes on the buses of the generators, loads and shunts it takes out:
 * {@link #add(LfContingency)} adds these buses.</li>
 * </ul>
 * HVDC lines and areas are few: the partial restore writes them all.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class ModifiedElementsCollector extends AbstractLfNetworkListener {

    /** By shunt number, the bus of each shunt. */
    private final LfBus[] shuntBus;
    private final List<LfBranch> controllerBranches = new ArrayList<>();

    private final boolean[] busModified;
    private final boolean[] branchModified;
    private final List<LfBus> buses = new ArrayList<>();
    private final List<LfBranch> branches = new ArrayList<>();
    private boolean enabled = true;

    public ModifiedElementsCollector(LfNetwork network) {
        Objects.requireNonNull(network);
        busModified = new boolean[network.getBuses().size()];
        branchModified = new boolean[network.getBranches().size()];
        shuntBus = new LfBus[network.getShunts().size()];
        for (LfBus bus : network.getBuses()) {
            bus.getShunt().ifPresent(shunt -> shuntBus[shunt.getNum()] = bus);
            bus.getControllerShunt().ifPresent(shunt -> shuntBus[shunt.getNum()] = bus);
            bus.getSvcShunt().ifPresent(shunt -> shuntBus[shunt.getNum()] = bus);
        }
        for (LfBranch branch : network.getBranches()) {
            if (branch.isVoltageController() || branch.isPhaseController()) {
                controllerBranches.add(branch);
            }
        }
        reset();
    }

    /** The buses modified since the last reset. */
    public List<LfBus> getBuses() {
        return buses;
    }

    /** The branches modified since the last reset, and the ones that can control a voltage or a flow. */
    public List<LfBranch> getBranches() {
        return branches;
    }

    /** Off while the collected elements are restored: restoring them is not modifying them. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void reset() {
        buses.forEach(bus -> busModified[bus.getNum()] = false);
        branches.forEach(branch -> branchModified[branch.getNum()] = false);
        buses.clear();
        branches.clear();
        controllerBranches.forEach(this::add);
    }

    /** The buses of the generators, loads and shunts a contingency takes out: it changes them without a notification. */
    public void add(LfContingency contingency) {
        contingency.getLoadAndGeneratorBuses().forEach(this::add);
        contingency.getShuntsShift().keySet().forEach(this::add);
    }

    public void add(LfBus bus) {
        if (enabled && bus != null && !busModified[bus.getNum()]) {
            busModified[bus.getNum()] = true;
            buses.add(bus);
        }
    }

    public void add(LfBranch branch) {
        if (enabled && branch != null && !branchModified[branch.getNum()]) {
            branchModified[branch.getNum()] = true;
            branches.add(branch);
        }
    }

    private void add(LfShunt shunt) {
        add(shuntBus[shunt.getNum()]);
    }

    @Override
    public void onGeneratorVoltageControlChange(LfBus controllerBus, boolean newVoltageControllerEnabled) {
        add(controllerBus);
    }

    @Override
    public void onGeneratorReactivePowerControlChange(LfBus controllerBus, boolean newReactiveControllerEnabled) {
        add(controllerBus);
    }

    @Override
    public void onTransformerPhaseControlChange(LfBranch controllerBranch, boolean newPhaseControlEnabled) {
        add(controllerBranch);
    }

    @Override
    public void onTransformerVoltageControlChange(LfBranch controllerBranch, boolean newVoltageControllerEnabled) {
        add(controllerBranch);
    }

    @Override
    public void onShuntVoltageControlChange(LfShunt controllerShunt, boolean newVoltageControllerEnabled) {
        add(controllerShunt);
    }

    @Override
    public void onLoadActivePowerTargetChange(LfLoad load, double oldTargetP, double newTargetP) {
        add(load.getBus());
    }

    @Override
    public void onLoadReactivePowerTargetChange(LfLoad load, double oldTargetQ, double newTargetQ) {
        add(load.getBus());
    }

    @Override
    public void onGenerationActivePowerTargetChange(LfGenerator generator, double oldGenerationTargetP, double newGenerationTargetP) {
        add(generator.getBus());
    }

    @Override
    public void onGenerationReactivePowerTargetChange(LfBus bus, double oldGenerationTargetQ, double newGenerationTargetQ) {
        add(bus);
    }

    @Override
    public void onDisableChange(LfElement element, boolean disabled) {
        if (element instanceof LfBus bus) {
            add(bus);
        } else if (element instanceof LfBranch branch) {
            add(branch);
        } else if (element instanceof LfShunt shunt) {
            add(shunt);
        }
    }

    @Override
    public void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition, boolean modifiedTapImpedance) {
        add(branch);
    }

    @Override
    public void onShuntSusceptanceChange(LfShunt shunt, double b) {
        add(shunt);
    }

    @Override
    public void onBranchConnectionStatusChange(LfBranch branch, TwoSides side, boolean connected) {
        add(branch);
    }
}
