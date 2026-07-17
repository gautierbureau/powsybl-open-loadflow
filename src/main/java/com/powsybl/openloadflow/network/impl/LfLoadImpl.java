/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.BoundaryLine;
import com.powsybl.iidm.network.LccConverterStation;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.LoadType;
import com.powsybl.iidm.network.extensions.LoadDetail;
import com.powsybl.iidm.network.util.HvdcUtils;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.EvaluableConstants;
import com.powsybl.openloadflow.util.PerUnit;

import java.util.*;
import java.util.stream.Stream;

/**
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class LfLoadImpl extends AbstractLfInjection implements LfLoad {

    private final LfBus bus;

    private final LfLoadModel loadModel;

    private final Map<String, Ref<Load>> loadsRefs = new HashMap<>();

    private final List<Ref<LccConverterStation>> lccCsRefs = new ArrayList<>();

    private double targetQ = 0;

    private boolean ensurePowerFactorConstantByLoad = false;

    private final HashMap<String, Double> loadsAbsVariableTargetP = new HashMap<>();

    // active power set point of each original load, in per unit: what the load consumes before slack distribution
    // moves the aggregate around. Everything below that is derived from p0 is rebuilt from this map.
    private final HashMap<String, Double> loadsP0 = new HashMap<>();

    private double absVariableTargetP = 0;

    private final boolean distributedOnConformLoad;

    private Map<String, Boolean> loadsDisablingStatus = new LinkedHashMap<>();

    private Evaluable p = EvaluableConstants.NAN;

    private Evaluable q = EvaluableConstants.NAN;

    LfLoadImpl(LfBus bus, boolean distributedOnConformLoad, LfLoadModel loadModel) {
        super(0, 0);
        this.bus = Objects.requireNonNull(bus);
        this.distributedOnConformLoad = distributedOnConformLoad;
        this.loadModel = loadModel;
    }

    @Override
    public String getId() {
        return bus.getId() + "_load";
    }

    @Override
    public List<String> getOriginalIds() {
        return Stream.concat(loadsRefs.values().stream().map(r -> r.get().getId()),
                             lccCsRefs.stream().map(r -> r.get().getId()))
                .toList();
    }

    @Override
    public LfBus getBus() {
        return bus;
    }

    @Override
    public boolean isOriginalLoadNotParticipating(String originalLoadId) {
        if (loadsRefs.get(originalLoadId) == null) {
            return false;
        }
        return isLoadNotParticipating(loadsRefs.get(originalLoadId).get());
    }

    @Override
    public Optional<LfLoadModel> getLoadModel() {
        return Optional.ofNullable(loadModel);
    }

    void add(Load load, LfNetworkParameters parameters) {
        loadsRefs.put(load.getId(), Ref.create(load, parameters.isCacheEnabled()));
        loadsDisablingStatus.put(load.getId(), false);
        double p0 = load.getP0();
        double q0 = load.getQ0();
        targetP += p0 / PerUnit.SB;
        initialTargetP += p0 / PerUnit.SB;
        targetQ += q0 / PerUnit.SB;
        loadsP0.put(load.getId(), p0 / PerUnit.SB);
        if (needsPowerFactorConstantByLoad(load, p0, distributedOnConformLoad)) {
            ensurePowerFactorConstantByLoad = true;
        }
        double absTargetP = getAbsVariableTargetPPerUnit(load, distributedOnConformLoad);
        loadsAbsVariableTargetP.put(load.getId(), absTargetP);
        absVariableTargetP += absTargetP;
    }

    /**
     * A load whose reactive power cannot be rescaled from the aggregated power factor, and needs its own.
     */
    private static boolean needsPowerFactorConstantByLoad(Load load, double p0, boolean distributedOnConformLoad) {
        boolean hasVariableActivePower = false;
        if (distributedOnConformLoad) {
            LoadDetail loadDetail = load.getExtension(LoadDetail.class);
            if (loadDetail != null) {
                hasVariableActivePower = loadDetail.getFixedActivePower() != p0;
            }
        }
        boolean reactiveOnlyLoad = p0 == 0 && load.getQ0() != 0;
        return p0 < 0 || hasVariableActivePower || reactiveOnlyLoad;
    }

    /**
     * Recomputed from scratch: the flag is an OR over the original loads, so a single load leaving the cases above
     * cannot be undone incrementally.
     */
    private void updateEnsurePowerFactorConstantByLoad() {
        ensurePowerFactorConstantByLoad = loadsRefs.entrySet().stream()
                .anyMatch(e -> needsPowerFactorConstantByLoad(e.getValue().get(), loadsP0.get(e.getKey()) * PerUnit.SB, distributedOnConformLoad));
    }

    void add(LccConverterStation lccCs, LfNetworkParameters parameters) {
        // note that LCC converter station are out of the slack distribution.
        lccCsRefs.add(Ref.create(lccCs, parameters.isCacheEnabled()));
        double lccTargetP = HvdcUtils.getConverterStationTargetP(lccCs);
        this.targetP += lccTargetP / PerUnit.SB;
        initialTargetP += lccTargetP / PerUnit.SB;
        targetQ += HvdcUtils.getLccConverterStationLoadTargetQ(lccCs) / PerUnit.SB;
    }

    public void add(BoundaryLine boundaryLine) {
        targetP += boundaryLine.getP0() / PerUnit.SB;
        targetQ += boundaryLine.getQ0() / PerUnit.SB;
    }

    @Override
    public void setTargetP(double targetP) {
        if (targetP != this.targetP) {
            double oldTargetP = this.targetP;
            this.targetP = targetP;
            bus.invalidateLoadTargetP();
            for (LfNetworkListener listener : bus.getNetwork().getListeners()) {
                listener.onLoadActivePowerTargetChange(this, oldTargetP, targetP);
            }
        }
    }

    @Override
    public double getOriginalLoadP0(String originalId) {
        return getLoadP0(originalId);
    }

    @Override
    public Map<String, Double> getOriginalLoadsP0() {
        return Collections.unmodifiableMap(loadsP0);
    }

    @Override
    public void setOriginalLoadP0(String originalId, double p0) {
        double oldP0 = getLoadP0(originalId);
        if (p0 == oldP0) {
            return;
        }
        loadsP0.put(originalId, p0);
        // The aggregate follows the set point by the same amount, keeping whatever slack distribution had already
        // moved it by. On a network that has just been restored the two are equal, so both end up on the new p0.
        double diffP0 = p0 - oldP0;
        initialTargetP += diffP0;
        setTargetP(targetP + diffP0);

        // Slack participation follows p0 only when the slack is distributed proportionally to it: on conform load it
        // is driven by the LoadDetail variable active power instead, which a new p0 does not change, and which is why
        // this has to go back through the same accessor the network was built with rather than take abs(p0) directly.
        double absTargetP = getAbsVariableTargetPPerUnit(loadsRefs.get(originalId).get(), distributedOnConformLoad, p0 * PerUnit.SB);
        absVariableTargetP += absTargetP - loadsAbsVariableTargetP.put(originalId, absTargetP);

        updateEnsurePowerFactorConstantByLoad();
    }

    private double getLoadP0(String originalId) {
        Double p0 = loadsP0.get(originalId);
        if (p0 == null) {
            throw new PowsyblException("Load '" + originalId + "' is not an original load of '" + getId() + "'");
        }
        return p0;
    }

    @Override
    public double getTargetQ() {
        return targetQ;
    }

    @Override
    public void setTargetQ(double targetQ) {
        if (targetQ != this.targetQ) {
            double oldTargetQ = this.targetQ;
            this.targetQ = targetQ;
            bus.invalidateLoadTargetQ();
            for (LfNetworkListener listener : bus.getNetwork().getListeners()) {
                listener.onLoadReactivePowerTargetChange(this, oldTargetQ, targetQ);
            }
        }
    }

    @Override
    public boolean ensurePowerFactorConstantByLoad() {
        return ensurePowerFactorConstantByLoad;
    }

    @Override
    public double getAbsVariableTargetP() {
        return absVariableTargetP;
    }

    @Override
    public void setAbsVariableTargetP(double absVariableTargetP) {
        this.absVariableTargetP = absVariableTargetP;
    }

    public static double getAbsVariableTargetPPerUnit(Load load, boolean distributedOnConformLoad) {
        return getAbsVariableTargetPPerUnit(load, distributedOnConformLoad, load.getP0());
    }

    private static double getAbsVariableTargetPPerUnit(Load load, boolean distributedOnConformLoad, double p0) {
        if (isLoadNotParticipating(load)) {
            return 0.0;
        }
        double varP;
        if (distributedOnConformLoad) {
            varP = load.getExtension(LoadDetail.class) == null ? 0 : load.getExtension(LoadDetail.class).getVariableActivePower();
        } else {
            varP = p0;
        }
        return Math.abs(varP) / PerUnit.SB;
    }

    @Override
    public int getOriginalLoadCount() {
        return loadsRefs.size();
    }

    private double getParticipationFactor(String originalLoadId) {
        // FIXME
        // After a load contingency or a load action, only the global variable targetP is updated.
        // The list loadsAbsVariableTargetP never changes. It is not an issue for security analysis as the network is
        // never updated. Excepted if loadPowerFactorConstant is true, the new targetQ could be wrong after a load contingency
        // or a load action.
        return absVariableTargetP != 0 ? loadsAbsVariableTargetP.get(originalLoadId) / absVariableTargetP : 0;
    }

    private double calculateP() {
        return p.eval() + getLoadModel()
                .flatMap(lm -> lm.getExpTermP(0).map(term -> targetP * term.c()))
                .orElse(0d);
    }

    private double calculateQ() {
        return q.eval() + getLoadModel()
                .flatMap(lm -> lm.getExpTermQ(0).map(term -> targetQ * term.c()))
                .orElse(0d);
    }

    @Override
    public void updateState(boolean loadPowerFactorConstant, boolean breakers) {
        double pv = p == EvaluableConstants.NAN ? 1 : calculateP() / targetP; // extract part of p that is dependent to voltage
        double qv = q == EvaluableConstants.NAN ? 1 : calculateQ() / targetQ;
        double diffLoadTargetP = targetP - initialTargetP;
        for (Ref<Load> refLoad : loadsRefs.values()) {
            Load load = refLoad.get();
            double diffP0 = diffLoadTargetP * getParticipationFactor(load.getId()) * PerUnit.SB;
            double updatedP0 = load.getP0() + diffP0;
            double updatedQ0 = load.getQ0() + (loadPowerFactorConstant ? getPowerFactor(load) * diffP0 : 0.0);
            load.getTerminal()
                    .setP(updatedP0 * pv)
                    .setQ(updatedQ0 * qv);
        }

        // update lcc converter station power
        for (Ref<LccConverterStation> lccCsRef : lccCsRefs) {
            LccConverterStation lccCs = lccCsRef.get();
            double pCs = HvdcUtils.getConverterStationTargetP(lccCs); // A LCC station has active losses.
            double qCs = HvdcUtils.getLccConverterStationLoadTargetQ(lccCs); // A LCC station always consumes reactive power.
            lccCs.getTerminal()
                    .setP(pCs)
                    .setQ(qCs);
        }
    }

    @Override
    public double calculateNewTargetQ(double diffTargetP) {
        double newLoadTargetQ = 0;
        for (Ref<Load> refLoad : loadsRefs.values()) {
            Load load = refLoad.get();
            double updatedQ0 = load.getQ0() / PerUnit.SB + getPowerFactor(load) * diffTargetP * getParticipationFactor(load.getId());
            newLoadTargetQ += updatedQ0;
        }
        return newLoadTargetQ;
    }

    @Override
    public boolean isOriginalLoadDisabled(String originalId) {
        return loadsDisablingStatus.get(originalId);
    }

    @Override
    public void setOriginalLoadDisabled(String originalId, boolean disabled) {
        loadsDisablingStatus.put(originalId, disabled);
    }

    @Override
    public Map<String, Boolean> getOriginalLoadsDisablingStatus() {
        return loadsDisablingStatus;
    }

    @Override
    public void setOriginalLoadsDisablingStatus(Map<String, Boolean> originalLoadsDisablingStatus) {
        this.loadsDisablingStatus = Objects.requireNonNull(originalLoadsDisablingStatus);
    }

    /**
     * Taken from the set point this load carries rather than from the IIDM one, so that it stays the power factor of
     * the load as simulated once the two have been made to differ by {@link #setOriginalLoadP0}. They are equal for a
     * load that has only ever been built and solved.
     */
    private double getPowerFactor(Load load) {
        double p0 = getLoadP0(load.getId()) * PerUnit.SB;
        return p0 != 0 ? load.getQ0() / p0 : 1;
    }

    /**
     * Returns true if the load does not participate to slack distribution
     */
    public static boolean isLoadNotParticipating(Load load) {
        // Fictitious loads that do not participate to slack distribution.
        return isLoadFictitious(load);
    }

    /**
     * Returns whether the load is tagged fictitious in the grid model
     */
    public static boolean isLoadFictitious(Load load) {
        // Fictitious loads that do not participate to slack distribution.
        return load.isFictitious() || LoadType.FICTITIOUS.equals(load.getLoadType());
    }

    @Override
    public double getNonFictitiousLoadTargetP() {
        return loadsRefs.values().stream()
                .map(Ref::get)
                .filter(Objects::nonNull)
                .filter(l -> !isLoadFictitious(l))
                .mapToDouble(Load::getP0)
                .sum();
    }

    @Override
    public Evaluable getP() {
        return p;
    }

    @Override
    public void setP(Evaluable p) {
        this.p = p;
    }

    @Override
    public Evaluable getQ() {
        return q;
    }

    @Override
    public void setQ(Evaluable q) {
        this.q = q;
    }

    @Override
    public String toString() {
        return getId();
    }
}
