/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Florian Dupuy {@literal <florian.dupuy at rte-france.com>}
 */
public class BusDcState extends ElementState<LfBus> {

    private final Map<String, Double> generatorsTargetP;
    private final Map<String, Double> generatorsInitialTargetP;
    private final Map<String, Boolean> participatingGenerators;
    private final Map<String, Boolean> disablingStatusGenerators;
    private final List<LoadDcState> loadStates;

    protected static class LoadDcState {

        private double loadTargetP;
        private double loadInitialTargetP;
        private double absVariableLoadTargetP;
        private Map<String, Boolean> loadsDisablingStatus;
        private Map<String, Double> loadsP0;

        protected LoadDcState save(LfLoad load) {
            loadTargetP = load.getTargetP();
            loadInitialTargetP = load.getInitialTargetP();
            absVariableLoadTargetP = load.getAbsVariableTargetP();
            loadsDisablingStatus = new HashMap<>(load.getOriginalLoadsDisablingStatus());
            loadsP0 = new HashMap<>(load.getOriginalLoadsP0());
            return this;
        }

        protected void restore(LfLoad load) {
            // Replaying the set points first is what restores everything derived from them. The running totals they
            // move on the way are overwritten just below by their saved values. Nothing to replay, and so nothing to
            // pay, for a load whose set points were never changed.
            loadsP0.forEach(load::setOriginalLoadP0);
            load.setTargetP(loadTargetP);
            load.setInitialTargetP(loadInitialTargetP);
            load.setAbsVariableTargetP(absVariableLoadTargetP);
            load.setOriginalLoadsDisablingStatus(loadsDisablingStatus);
        }
    }

    public BusDcState(LfBus bus) {
        super(bus);
        this.generatorsTargetP = bus.getGenerators().stream().collect(Collectors.toMap(LfGenerator::getId, LfGenerator::getTargetP));
        this.generatorsInitialTargetP = bus.getGenerators().stream().collect(Collectors.toMap(LfGenerator::getId, LfGenerator::getInitialTargetP));
        this.participatingGenerators = bus.getGenerators().stream().collect(Collectors.toMap(LfGenerator::getId, LfGenerator::isParticipating));
        this.disablingStatusGenerators = bus.getGenerators().stream().collect(Collectors.toMap(LfGenerator::getId, LfGenerator::isDisabled));
        loadStates = bus.getLoads().stream().map(load -> createLoadState().save(load)).toList();
    }

    protected LoadDcState createLoadState() {
        return new LoadDcState();
    }

    @Override
    public void restore() {
        super.restore();
        element.getGenerators().forEach(g -> g.setTargetP(generatorsTargetP.get(g.getId())));
        element.getGenerators().forEach(g -> g.setInitialTargetP(generatorsInitialTargetP.get(g.getId())));
        element.getGenerators().forEach(g -> g.setParticipating(participatingGenerators.get(g.getId())));
        element.getGenerators().forEach(g -> g.setDisabled(disablingStatusGenerators.get(g.getId())));
        for (int i = 0; i < loadStates.size(); i++) {
            LfLoad load = element.getLoads().get(i);
            loadStates.get(i).restore(load);
        }
    }

    public static BusDcState save(LfBus bus) {
        return new BusDcState(bus);
    }
}
