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
    /** By position in the generators of the bus. */
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

        @Override
        protected String findDifference(LfLoad load) {
            String difference = super.findDifference(load);
            return difference != null || same(load.getTargetQ(), loadTargetQ) ? difference : "target Q of load " + load.getId();
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
        generatorsControlType = new LfGenerator.GeneratorControlType[generators.size()];
        for (int i = 0; i < generators.size(); i++) {
            generatorsControlType[i] = generators.get(i).getGeneratorControlType();
        }
        qLimitType = bus.getQLimitType().orElse(null);
    }

    @Override
    protected LoadDcState createLoadState() {
        return new LoadState();
    }

    /** Only the voltage magnitude and angle: what a precomputed state injected into the network moves on a bus
     *  the contingency did not otherwise touch (see {@link NetworkState#restore(java.util.Collection, java.util.Collection)}). */
    void restoreVoltage() {
        element.setAngle(angle);
        element.setV(voltage);
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
        List<LfGenerator> generators = element.getGenerators();
        for (int i = 0; i < generators.size(); i++) {
            generators.get(i).setGeneratorControlType(generatorsControlType[i]);
        }
        element.setQLimitType(qLimitType);
    }

    @Override
    String findDifference() {
        String difference = super.findDifference();
        if (difference != null) {
            return difference;
        }
        if (!same(element.getAngle(), angle) || !same(element.getV(), voltage)) {
            return "voltage";
        }
        if (element.isGeneratorVoltageControlEnabled() != voltageControlEnabled
                || element.isGeneratorReactivePowerControlEnabled() != reactiveControlEnabled) {
            return "generator control";
        }
        if (element.isGenerationTargetQFrozen() != isGenerationTargetQFrozen
                || isGenerationTargetQFrozen && !same(element.getGenerationTargetQ(), generationTargetQ)) {
            return "frozen generation target Q";
        }
        if (element.getQLimitType().orElse(null) != qLimitType) {
            return "reactive limit type";
        }
        List<LfGenerator> generators = element.getGenerators();
        for (int i = 0; i < generators.size(); i++) {
            if (generators.get(i).getGeneratorControlType() != generatorsControlType[i]) {
                return "control type of generator " + generators.get(i).getId();
            }
        }
        return findShuntDifference();
    }

    private String findShuntDifference() {
        LfShunt controllerShunt = element.getControllerShunt().orElse(null);
        if (controllerShunt != null && (controllerShunt.isVoltageControlEnabled() != shuntVoltageControlEnabled
                || !same(controllerShunt.getB(), controllerShuntB) || !same(controllerShunt.getG(), controllerShuntG))) {
            return "controller shunt";
        }
        LfShunt shunt = element.getShunt().orElse(null);
        if (shunt != null && (!same(shunt.getB(), shuntB) || !same(shunt.getG(), shuntG))) {
            return "shunt";
        }
        LfShunt svcShunt = element.getSvcShunt().orElse(null);
        return svcShunt == null || same(svcShunt.getB(), svcShuntB) ? null : "static var compensator shunt";
    }

    public static BusState save(LfBus bus) {
        return new BusState(bus);
    }
}
