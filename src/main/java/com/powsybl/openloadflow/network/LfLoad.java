/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.util.Evaluable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public interface LfLoad extends PropertyBag {

    String getId();

    LfBus getBus();

    boolean isOriginalLoadNotParticipating(String originalId);

    Optional<LfLoadModel> getLoadModel();

    double getInitialTargetP();

    double getTargetP();

    double getNonFictitiousLoadTargetP();

    void setTargetP(double targetP);

    void setInitialTargetP(double initialTargetP);

    /**
     * Returns the active power set point of one of the original loads aggregated in this load, in per unit.
     */
    double getOriginalLoadP0(String originalId);

    /**
     * Returns the active power set point of every original load aggregated in this load, in per unit, by load id.
     * Original loads that are not IIDM loads, such as LCC converter stations, are not part of it.
     */
    Map<String, Double> getOriginalLoadsP0();

    /**
     * Sets the active power set point of one of the original loads aggregated in this load, in per unit, and updates
     * everything this load derives from that set point, so that it stays equivalent to a load built from a network
     * carrying it.
     *
     * <p>This is not {@link #setTargetP(double)} on one load. That one moves the aggregate away from its set point
     * without changing the set point itself, which is what slack distribution does, and so it deliberately leaves the
     * derived state alone. This one means the load consumes something else than it was built with.
     */
    void setOriginalLoadP0(String originalId, double p0);

    /**
     * Returns the reactive power set point of one of the original loads aggregated in this load, in per unit.
     */
    double getOriginalLoadQ0(String originalId);

    /**
     * Returns the reactive power set point of every original load aggregated in this load, in per unit, by load id.
     * Original loads that are not IIDM loads, such as LCC converter stations, are not part of it.
     */
    Map<String, Double> getOriginalLoadsQ0();

    /**
     * Sets the reactive power set point of one of the original loads aggregated in this load, in per unit, and updates
     * everything this load derives from that set point, so that it stays equivalent to a load built from a network
     * carrying it.
     *
     * <p>The counterpart of {@link #setOriginalLoadP0} for reactive power, and not {@link #setTargetQ(double)} on one
     * load, which rescales the aggregate around its set points rather than changing them.
     */
    void setOriginalLoadQ0(String originalId, double q0);

    double getTargetQ();

    void setTargetQ(double targetQ);

    boolean ensurePowerFactorConstantByLoad();

    double getAbsVariableTargetP();

    void setAbsVariableTargetP(double absVariableTargetP);

    double calculateNewTargetQ(double diffTargetP);

    List<String> getOriginalIds();

    int getOriginalLoadCount();

    boolean isOriginalLoadDisabled(String originalId);

    void setOriginalLoadDisabled(String originalId, boolean disabled);

    Map<String, Boolean> getOriginalLoadsDisablingStatus();

    void setOriginalLoadsDisablingStatus(Map<String, Boolean> originalLoadsDisablingStatus);

    void updateState(boolean loadPowerFactorConstant, boolean breakers);

    Evaluable getP();

    void setP(Evaluable p);

    Evaluable getQ();

    void setQ(Evaluable q);
}
