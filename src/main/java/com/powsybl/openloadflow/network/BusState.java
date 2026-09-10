/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import java.util.List;

/**
 * @author Florian Dupuy {@literal <florian.dupuy at rte-france.com>}
 */
public class BusState extends BusDcState {

    private final double angle;
    private final double voltage;
    private final double generationTargetQ;
    private final boolean isGenerationTargetQFrozen;
    private final boolean voltageControlEnabled;
    private final Boolean shuntVoltageControlEnabled;
    private final Boolean reactiveControlEnabled;
    private final double shuntB;
    private final double shuntG;
    private final double controllerShuntB;
    private final double controllerShuntG;
    private final double svcShuntB;
    // Parallel arrays rather than a Map<generatorId, controlType>: restore() looks the type up by
    // generator id, and a bus carries a handful of generators, so a scan over a 1-3 element array is
    // both faster and allocation-free where the map cost a stream, a HashMap and String hashing PER BUS
    // PER SAVE. Security analysis saves controller-bus state once per contingency (and the GPU hybrid
    // once per scenario per outer-loop round), so this sat on the hottest path either arm has.
    private final String[] generatorIds;
    private final LfGenerator.GeneratorControlType[] generatorsControlType;
    private final LfBus.QLimitType qLimitType;

    private static final class LoadState extends LoadDcState {

        private double loadTargetQ;

        @Override
        protected LoadDcState save(LfLoad load) {
            super.save(load);
            loadTargetQ = load.getTargetQ();
            return this;
        }

        @Override
        protected void restore(LfLoad load) {
            super.restore(load);
            load.setTargetQ(loadTargetQ);
        }
    }

    public BusState(LfBus bus) {
        super(bus);
        this.angle = bus.getAngle();
        this.voltage = bus.getV();
        this.generationTargetQ = bus.getGenerationTargetQ();
        this.isGenerationTargetQFrozen = bus.isGenerationTargetQFrozen();
        this.voltageControlEnabled = bus.isGeneratorVoltageControlEnabled();
        this.reactiveControlEnabled = bus.isGeneratorReactivePowerControlEnabled();
        LfShunt controllerShunt = bus.getControllerShunt().orElse(null);
        shuntVoltageControlEnabled = controllerShunt != null ? controllerShunt.isVoltageControlEnabled() : null;
        controllerShuntB = controllerShunt != null ? controllerShunt.getB() : Double.NaN;
        controllerShuntG = controllerShunt != null ? controllerShunt.getG() : Double.NaN;
        LfShunt shunt = bus.getShunt().orElse(null);
        shuntB = shunt != null ? shunt.getB() : Double.NaN;
        shuntG = shunt != null ? shunt.getG() : Double.NaN;
        LfShunt svcShunt = bus.getSvcShunt().orElse(null);
        svcShuntB = svcShunt != null ? svcShunt.getB() : Double.NaN;
        List<LfGenerator> generators = bus.getGenerators();
        this.generatorIds = new String[generators.size()];
        this.generatorsControlType = new LfGenerator.GeneratorControlType[generators.size()];
        for (int i = 0; i < generators.size(); i++) {
            this.generatorIds[i] = generators.get(i).getId();
            this.generatorsControlType[i] = generators.get(i).getGeneratorControlType();
        }
        qLimitType = bus.getQLimitType().orElse(null);
    }

    @Override
    protected LoadDcState createLoadState() {
        return new LoadState();
    }

    @Override
    public void restore() {
        super.restore();
        element.setAngle(angle);
        element.setV(voltage);
        element.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(voltageControlEnabled);
        if (isGenerationTargetQFrozen) {
            element.freezeGenerationTargetQAndDisableGeneratorVoltageControl(generationTargetQ);
        }
        element.setGeneratorReactivePowerControlEnabled(reactiveControlEnabled);
        if (shuntVoltageControlEnabled != null) {
            element.getControllerShunt().orElseThrow().setVoltageControlEnabled(shuntVoltageControlEnabled);
        }
        if (!Double.isNaN(controllerShuntB)) {
            element.getControllerShunt().orElseThrow().setB(controllerShuntB);
        }
        if (!Double.isNaN(controllerShuntG)) {
            element.getControllerShunt().orElseThrow().setG(controllerShuntG);
        }
        if (!Double.isNaN(shuntB)) {
            element.getShunt().orElseThrow().setB(shuntB);
        }
        if (!Double.isNaN(shuntG)) {
            element.getShunt().orElseThrow().setG(shuntG);
        }
        if (!Double.isNaN(svcShuntB)) {
            element.getSvcShunt().orElseThrow().setB(svcShuntB);
        }
        element.getGenerators().forEach(g -> g.setGeneratorControlType(controlTypeOf(g.getId())));
        element.setQLimitType(qLimitType);
    }

    /** The saved control type for {@code generatorId}, or null when it was not saved — the exact
     *  semantics of the Map.get() this replaces. */
    private LfGenerator.GeneratorControlType controlTypeOf(String generatorId) {
        for (int i = 0; i < generatorIds.length; i++) {
            if (generatorIds[i].equals(generatorId)) {
                return generatorsControlType[i];
            }
        }
        return null;
    }

    public static BusState save(LfBus bus) {
        return new BusState(bus);
    }
}
