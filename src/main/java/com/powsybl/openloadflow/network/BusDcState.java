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

    // Parallel arrays rather than four Map<generatorId, X>: restore() looks each value up by generator
    // id and a bus carries ~1 generator, so a scan over a tiny array is faster than four hash lookups —
    // and saving costs one pass instead of four streams, with no HashMap and no Double/Boolean boxing.
    // This is the hottest allocation in the codebase's state save: security analysis saves controller-bus
    // state per contingency, and the GPU JAVA hybrid does it per scenario per outer-loop round (1006
    // buses x 4 maps x thousands of scenarios). Note loadStates below is ALREADY positional.
    private final String[] generatorIds;
    private final double[] generatorsTargetP;
    private final double[] generatorsInitialTargetP;
    private final boolean[] participatingGenerators;
    private final boolean[] disablingStatusGenerators;
    private final List<LoadDcState> loadStates;

    protected static class LoadDcState {

        private double loadTargetP;
        private double loadInitialTargetP;
        private double absVariableLoadTargetP;
        private Map<String, Boolean> loadsDisablingStatus;
        private Map<String, Double> loadsP0;
        private Map<String, Double> loadsQ0;

        protected LoadDcState save(LfLoad load) {
            loadTargetP = load.getTargetP();
            loadInitialTargetP = load.getInitialTargetP();
            absVariableLoadTargetP = load.getAbsVariableTargetP();
            // The copy is load-bearing: restore() hands this map back to the load and callers MUTATE it
            // afterwards, so it must be a fresh mutable map even when empty (Map.of() here fails
            // testDcSaHvdcLineContingency with UnsupportedOperationException).
            loadsDisablingStatus = new HashMap<>(load.getOriginalLoadsDisablingStatus());
            loadsP0 = new HashMap<>(load.getOriginalLoadsP0());
            loadsQ0 = new HashMap<>(load.getOriginalLoadsQ0());
            return this;
        }

        protected void restore(LfLoad load) {
            // Replaying the set points first is what restores everything derived from them. The running totals they
            // move on the way are overwritten just below by their saved values -- and for targetQ by LoadState, which
            // is where reactive power is restored. Nothing to replay, and so nothing to pay, for a load whose set
            // points were never changed.
            loadsP0.forEach(load::setOriginalLoadP0);
            loadsQ0.forEach(load::setOriginalLoadQ0);
            load.setTargetP(loadTargetP);
            load.setInitialTargetP(loadInitialTargetP);
            load.setAbsVariableTargetP(absVariableLoadTargetP);
            load.setOriginalLoadsDisablingStatus(loadsDisablingStatus);
        }
    }

    public BusDcState(LfBus bus) {
        super(bus);
        List<LfGenerator> generators = bus.getGenerators();
        int nbGenerators = generators.size();
        this.generatorIds = new String[nbGenerators];
        this.generatorsTargetP = new double[nbGenerators];
        this.generatorsInitialTargetP = new double[nbGenerators];
        this.participatingGenerators = new boolean[nbGenerators];
        this.disablingStatusGenerators = new boolean[nbGenerators];
        for (int i = 0; i < nbGenerators; i++) {
            LfGenerator generator = generators.get(i);
            this.generatorIds[i] = generator.getId();
            this.generatorsTargetP[i] = generator.getTargetP();
            this.generatorsInitialTargetP[i] = generator.getInitialTargetP();
            this.participatingGenerators[i] = generator.isParticipating();
            this.disablingStatusGenerators[i] = generator.isDisabled();
        }
        loadStates = bus.getLoads().stream().map(load -> createLoadState().save(load)).toList();
    }

    protected LoadDcState createLoadState() {
        return new LoadDcState();
    }

    private static final boolean PROFILE = System.getenv("OLF_BUSSTATE_PROFILE") != null;

    private static final java.util.concurrent.atomic.LongAdder GEN_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder LOAD_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder SUPER_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder LOOKUP_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder TARGETP_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder OTHER_NS = new java.util.concurrent.atomic.LongAdder();

    /** "super=X ms generators=Y ms loads=Z ms", then resets. */
    public static String profile() {
        String out = "super(ElementState)=" + SUPER_NS.sum() / 1_000_000 + " ms"
                + " generators=" + GEN_NS.sum() / 1_000_000 + " ms"
                + " loads=" + LOAD_NS.sum() / 1_000_000 + " ms"
                + " (gen: idLookup=" + LOOKUP_NS.sum() / 1_000_000
                + " setTargetP=" + TARGETP_NS.sum() / 1_000_000
                + " other3=" + OTHER_NS.sum() / 1_000_000 + " ms)";
        SUPER_NS.reset();
        GEN_NS.reset();
        LOAD_NS.reset();
        LOOKUP_NS.reset();
        TARGETP_NS.reset();
        OTHER_NS.reset();
        return out;
    }

    @Override
    public void restore() {
        if (PROFILE) {
            long t0 = System.nanoTime();
            super.restore();
            long t1 = System.nanoTime();
            restoreGenerators();
            long t2 = System.nanoTime();
            restoreLoads();
            long t3 = System.nanoTime();
            SUPER_NS.add(t1 - t0);
            GEN_NS.add(t2 - t1);
            LOAD_NS.add(t3 - t2);
            return;
        }
        super.restore();
        restoreGenerators();
        restoreLoads();
    }

    private void restoreGenerators() {
        if (PROFILE) {
            for (LfGenerator generator : element.getGenerators()) {
                long a0 = System.nanoTime();
                int i = indexOf(generator.getId());
                long a1 = System.nanoTime();
                LOOKUP_NS.add(a1 - a0);
                if (i >= 0) {
                    generator.setTargetP(generatorsTargetP[i]);
                    long a2 = System.nanoTime();
                    TARGETP_NS.add(a2 - a1);
                    generator.setInitialTargetP(generatorsInitialTargetP[i]);
                    generator.setParticipating(participatingGenerators[i]);
                    generator.setDisabled(disablingStatusGenerators[i]);
                    OTHER_NS.add(System.nanoTime() - a2);
                }
            }
            return;
        }
        for (LfGenerator generator : element.getGenerators()) {
            int i = indexOf(generator.getId());
            if (i >= 0) {                                    // Map.get() returned null for an unsaved id,
                generator.setTargetP(generatorsTargetP[i]);  // and the setters would have NPE'd on unboxing
                generator.setInitialTargetP(generatorsInitialTargetP[i]);
                generator.setParticipating(participatingGenerators[i]);
                generator.setDisabled(disablingStatusGenerators[i]);
            }
        }
    }

    private void restoreLoads() {
        for (int i = 0; i < loadStates.size(); i++) {
            LfLoad load = element.getLoads().get(i);
            loadStates.get(i).restore(load);
        }
    }

    /** Index of the saved generator with this id, or -1. */
    private int indexOf(String generatorId) {
        for (int i = 0; i < generatorIds.length; i++) {
            if (generatorIds[i].equals(generatorId)) {
                return i;
            }
        }
        return -1;
    }

    public static BusDcState save(LfBus bus) {
        return new BusDcState(bus);
    }
}
