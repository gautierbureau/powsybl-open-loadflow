/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.commons.PowsyblException;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfZeroImpedanceNetwork;
import com.powsybl.openloadflow.network.LoadFlowModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Merges in the AC equation system the buses of a zero impedance network flagged as merged (see
 * {@link LfZeroImpedanceNetwork#isMerged()}): all the buses share the voltage variables of the representative bus
 * (variable aliasing), and the terms of their active and reactive power balance equations are moved to the
 * representative bus equations. The zero impedance branches of such a network have no equation at all.
 * When a zero impedance network is split or merged (switch opening or closing), terms and aliases are reassigned
 * to the new representative buses.
 * Works with both single equations and equation arrays (vectorized equation system).
 */
public class ZeroImpedanceBusMerger {

    private final LfNetwork network;

    private final EquationSystem<AcVariableType, AcEquationType> equationSystem;

    /**
     * The terms originally created in the power balance equations of each bus.
     */
    private final Map<LfBus, List<EquationTerm<AcVariableType, AcEquationType>>> ownPTerms = new HashMap<>();

    private final Map<LfBus, List<EquationTerm<AcVariableType, AcEquationType>>> ownQTerms = new HashMap<>();

    public ZeroImpedanceBusMerger(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        this.network = Objects.requireNonNull(network);
        this.equationSystem = Objects.requireNonNull(equationSystem);
    }

    private Equation<AcVariableType, AcEquationType> getEquation(LfBus bus, AcEquationType type) {
        return equationSystem.getEquation(bus.getNum(), type).orElseThrow();
    }

    /**
     * To call once all the equations have been created.
     */
    public void mergeAll() {
        boolean changed = false;
        for (LfZeroImpedanceNetwork zn : network.getZeroImpedanceNetworks(LoadFlowModel.AC)) {
            if (zn.isMerged()) {
                for (LfBus bus : zn.getGraph().vertexSet()) {
                    ownPTerms.put(bus, new ArrayList<>(getEquation(bus, AcEquationType.BUS_TARGET_P).getTerms()));
                    ownQTerms.put(bus, new ArrayList<>(getEquation(bus, AcEquationType.BUS_TARGET_Q).getTerms()));
                }
                merge(zn);
                changed = true;
            }
        }
        if (changed) {
            equationSystem.notifyVariableAliasChange();
        }
    }

    /**
     * To call when zero impedance networks have been split or merged.
     */
    public void update(List<LfZeroImpedanceNetwork> zeroImpedanceNetworks) {
        boolean changed = false;
        for (LfZeroImpedanceNetwork zn : zeroImpedanceNetworks) {
            if (zn.isMerged()) {
                merge(zn);
                changed = true;
            }
        }
        if (changed) {
            equationSystem.notifyVariableAliasChange();
        }
    }

    private void moveTerm(EquationTerm<AcVariableType, AcEquationType> term, Equation<AcVariableType, AcEquationType> target) {
        if (term instanceof EquationTermArray.EquationTermArrayElementImpl<AcVariableType, AcEquationType> arrayElement) {
            // vectorized term: just re-point it to the target equation of the array
            arrayElement.getEquationTermArray().moveTermElement(arrayElement.getTermElementNum(), target.getElementNum());
        } else if (term instanceof SingleEquationTerm<AcVariableType, AcEquationType> singleTerm) {
            Equation<AcVariableType, AcEquationType> current = singleTerm.getEquation();
            if (current != null && current.getType() == target.getType() && current.getElementNum() == target.getElementNum()) {
                return;
            }
            if (current instanceof SingleEquation<AcVariableType, AcEquationType> singleEquation) {
                singleEquation.removeTerm(singleTerm);
            } else if (current != null) {
                // additional single term of an equation array
                equationSystem.getEquationArray(current.getType())
                        .orElseThrow()
                        .removeSingleTerm(current.getElementNum(), singleTerm);
            }
            target.addTerm(singleTerm);
        } else {
            throw new PowsyblException("Unsupported equation term: " + term);
        }
    }

    private void moveTerms(List<EquationTerm<AcVariableType, AcEquationType>> terms, Equation<AcVariableType, AcEquationType> target) {
        if (terms != null) {
            for (EquationTerm<AcVariableType, AcEquationType> term : terms) {
                moveTerm(term, target);
            }
        }
    }

    private void merge(LfZeroImpedanceNetwork zn) {
        LfBus representative = zn.getRepresentative();
        var pEq = getEquation(representative, AcEquationType.BUS_TARGET_P);
        var qEq = getEquation(representative, AcEquationType.BUS_TARGET_Q);
        var vVar = equationSystem.getVariable(representative.getNum(), AcVariableType.BUS_V);
        var phiVar = equationSystem.getVariable(representative.getNum(), AcVariableType.BUS_PHI);
        vVar.setAlias(null);
        phiVar.setAlias(null);
        for (LfBus bus : zn.getGraph().vertexSet()) {
            moveTerms(ownPTerms.get(bus), pEq);
            moveTerms(ownQTerms.get(bus), qEq);
            if (bus != representative) {
                equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_V).setAlias(vVar);
                equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_PHI).setAlias(phiVar);
                getEquation(bus, AcEquationType.BUS_TARGET_P).setActive(false);
                getEquation(bus, AcEquationType.BUS_TARGET_Q).setActive(false);
            }
        }
        boolean active = !representative.isDisabled();
        pEq.setActive(active);
        qEq.setActive(active);
    }

    /**
     * To call after the equations of a bus have been (re)activated because of a disabling status change: the power
     * balance equations of a merged bus that is not the representative one must stay inactive.
     */
    public void onBusDisableChange(LfBus bus) {
        if (LfZeroImpedanceNetwork.isMergedDependent(bus)) {
            getEquation(bus, AcEquationType.BUS_TARGET_P).setActive(false);
            getEquation(bus, AcEquationType.BUS_TARGET_Q).setActive(false);
        }
    }
}
