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

/**
 * @author Florian Dupuy {@literal <florian.dupuy at rte-france.com>}
 */
public class BusDcState extends ElementState<LfBus> {

    // a bus keeps its generators once the network is loaded: their states are saved by position, like the load states
    private final double[] generatorsTargetP;
    private final double[] generatorsInitialTargetP;
    private final boolean[] participatingGenerators;
    private final boolean[] disablingStatusGenerators;
    private final List<LoadDcState> loadStates;

    protected static class LoadDcState {

        private double loadTargetP;
        private double absVariableLoadTargetP;
        private Map<String, Boolean> loadsDisablingStatus;

        protected LoadDcState save(LfLoad load) {
            loadTargetP = load.getTargetP();
            absVariableLoadTargetP = load.getAbsVariableTargetP();
            loadsDisablingStatus = new HashMap<>(load.getOriginalLoadsDisablingStatus());
            return this;
        }

        protected void restore(LfLoad load) {
            load.setTargetP(loadTargetP);
            load.setAbsVariableTargetP(absVariableLoadTargetP);
            load.setOriginalLoadsDisablingStatus(loadsDisablingStatus);
        }
    }

    public BusDcState(LfBus bus) {
        super(bus);
        List<LfGenerator> generators = bus.getGenerators();
        this.generatorsTargetP = new double[generators.size()];
        this.generatorsInitialTargetP = new double[generators.size()];
        this.participatingGenerators = new boolean[generators.size()];
        this.disablingStatusGenerators = new boolean[generators.size()];
        for (int i = 0; i < generators.size(); i++) {
            LfGenerator generator = generators.get(i);
            generatorsTargetP[i] = generator.getTargetP();
            generatorsInitialTargetP[i] = generator.getInitialTargetP();
            participatingGenerators[i] = generator.isParticipating();
            disablingStatusGenerators[i] = generator.isDisabled();
        }
        loadStates = bus.getLoads().stream().map(load -> createLoadState().save(load)).toList();
    }

    protected LoadDcState createLoadState() {
        return new LoadDcState();
    }

    @Override
    public void restore() {
        super.restore();
        List<LfGenerator> generators = element.getGenerators();
        for (int i = 0; i < generators.size(); i++) {
            LfGenerator generator = generators.get(i);
            generator.setTargetP(generatorsTargetP[i]);
            generator.setInitialTargetP(generatorsInitialTargetP[i]);
            generator.setParticipating(participatingGenerators[i]);
            generator.setDisabled(disablingStatusGenerators[i]);
        }
        for (int i = 0; i < loadStates.size(); i++) {
            LfLoad load = element.getLoads().get(i);
            loadStates.get(i).restore(load);
        }
    }

    public static BusDcState save(LfBus bus) {
        return new BusDcState(bus);
    }
}
