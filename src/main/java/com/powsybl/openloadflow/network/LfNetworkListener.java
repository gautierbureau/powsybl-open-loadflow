/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.TwoSides;

import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface LfNetworkListener {

    void onGeneratorVoltageControlChange(LfBus controllerBus, boolean newVoltageControllerEnabled);

    void onGeneratorVoltageControlTargetChange(GeneratorVoltageControl control, double newTargetVoltage);

    void onGeneratorReactivePowerControlChange(LfBus controllerBus, boolean newReactiveControllerEnabled);

    void onTransformerPhaseControlChange(LfBranch controllerBranch, boolean newPhaseControlEnabled);

    void onTransformerVoltageControlChange(LfBranch controllerBranch, boolean newVoltageControllerEnabled);

    void onTransformerVoltageControlTargetChange(TransformerVoltageControl transformerVoltageControl, double newTargetVoltage);

    void onShuntVoltageControlChange(LfShunt controllerShunt, boolean newVoltageControllerEnabled);

    void onLoadActivePowerTargetChange(LfLoad load, double oldTargetP, double newTargetP);

    void onLoadReactivePowerTargetChange(LfLoad load, double oldTargetQ, double newTargetQ);

    void onGenerationActivePowerTargetChange(LfGenerator generator, double oldGenerationTargetP, double newGenerationTargetP);

    void onGenerationReactivePowerTargetChange(LfBus bus, double oldGenerationTargetQ, double newGenerationTargetQ);

    void onDisableChange(LfElement element, boolean disabled);

    void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition);

    void onShuntSusceptanceChange(LfShunt shunt, double b);

    void onZeroImpedanceNetworkSpanningTreeChange(LfBranch branch, LoadFlowModel loadFlowModel, boolean spanningTree);

    void onZeroImpedanceNetworkSplit(LfZeroImpedanceNetwork initialNetwork, List<LfZeroImpedanceNetwork> splitNetworks, LoadFlowModel loadFlowModel);

    void onZeroImpedanceNetworkMerge(LfZeroImpedanceNetwork network1, LfZeroImpedanceNetwork network2, LfZeroImpedanceNetwork mergedNetwork, LoadFlowModel loadFlowModel);

    void onBranchConnectionStatusChange(LfBranch branch, TwoSides side, boolean connected);

    void onSlackBusChange(LfBus bus, boolean slack);

    void onReferenceBusChange(LfBus bus, boolean reference);

    void onHvdcAcEmulationStatusChange(LfHvdc hvdc, LfHvdc.AcEmulationControl.AcEmulationStatus acEmulationStatus);

    // ---- Mutations that BusState / BusDcState save but that had no event ----------------------------
    // A listener could not previously tell whether a bus needed restoring: the reactive-limits loop sets
    // a bus's q-limit type, freezes its generation target Q and changes a generator's control type, and
    // load state carries two more fields, all silently. Anything that wants to restore only what changed
    // has to see these, and guessing that they co-occur with a covered event is exactly the kind of
    // silent miss that state save/restore must not have. Defaulted so no implementer has to change.

    /** The bus hit (or left) a reactive limit — {@code newQLimitType} null when released. */
    default void onQLimitTypeChange(LfBus bus, LfBus.QLimitType newQLimitType) {
        // no-op by default
    }

    /** The bus's generation target Q was frozen at a limit (PV -> PQ) or released. */
    default void onGenerationTargetQFrozenChange(LfBus bus, boolean frozen) {
        // no-op by default
    }

    /** The generator's control type changed (VOLTAGE / REMOTE_REACTIVE_POWER / MONITORING_VOLTAGE). */
    default void onGeneratorControlTypeChange(LfGenerator generator, LfGenerator.GeneratorControlType newControlType) {
        // no-op by default
    }

    /** The load's absolute variable active target changed. */
    default void onLoadAbsVariableTargetPChange(LfLoad load, double oldAbsVariableTargetP, double newAbsVariableTargetP) {
        // no-op by default
    }

    /** The load's per-original-load disabling status map was replaced. */
    default void onLoadOriginalDisablingStatusChange(LfLoad load) {
        // no-op by default
    }
}
