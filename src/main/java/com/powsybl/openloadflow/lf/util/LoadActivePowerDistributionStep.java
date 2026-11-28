/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.util;

import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LoadPowerFactorTerms;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class LoadActivePowerDistributionStep implements ActivePowerDistribution.Step {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoadActivePowerDistributionStep.class);

    private final boolean loadPowerFactorConstant;

    public LoadActivePowerDistributionStep(boolean loadPowerFactorConstant) {
        this.loadPowerFactorConstant = loadPowerFactorConstant;
    }

    @Override
    public String getElementType() {
        return "load";
    }

    @Override
    public ActivePowerDistribution.PreviousStateInfo resetToInitialState(Collection<LfBus> participatingBuses, LfGenerator referenceGenerator) {
        ActivePowerDistribution.PreviousStateInfo previousStateInfo = ActivePowerDistribution.Step.super.resetToInitialState(participatingBuses, referenceGenerator);
        // unlike in the case of generators, here we only have to capture the targetP-s
        for (LfBus bus : participatingBuses) {
            for (LfLoad load : bus.getLoads()) {
                previousStateInfo.previousTargetP().putIfAbsent(load, load.getTargetP());
            }
        }
        return previousStateInfo;
    }

    @Override
    public List<ParticipatingElement> getParticipatingElements(Collection<LfBus> participatingBuses, double mismatch) {
        return participatingBuses.stream()
                .flatMap(bus -> bus.getLoads().stream())
                .filter(load -> load.getAbsVariableTargetP() != 0)
                .map(load -> new ParticipatingElement(load, getParticipationFactor(load)))
                .collect(Collectors.toCollection(LinkedList::new));
    }

    private double getParticipationFactor(LfLoad load) {
        return load.getAbsVariableTargetP();
    }

    @Override
    public double run(List<ParticipatingElement> participatingElements, int iteration, double remainingMismatch) {
        // normalize participation factors at each iteration start as some
        // loads might have reach zero and have been discarded.
        ParticipatingElement.normalizeParticipationFactors(participatingElements);

        double done = 0d;
        int modifiedBuses = 0;
        int loadsAtMin = 0;
        Iterator<ParticipatingElement> it = participatingElements.iterator();
        while (it.hasNext()) {
            ParticipatingElement participatingBus = it.next();
            LfLoad load = (LfLoad) participatingBus.getElement();
            double factor = participatingBus.getFactor();

            double loadTargetP = load.getTargetP();
            double newLoadTargetP = loadTargetP - remainingMismatch * factor;

            if (newLoadTargetP != loadTargetP) {
                LOGGER.trace("Rescale '{}' active power target: {} -> {}",
                        load.getId(), loadTargetP * PerUnit.SB, newLoadTargetP * PerUnit.SB);

                if (loadPowerFactorConstant) {
                    ensurePowerFactorConstant(load, newLoadTargetP);
                }

                load.setTargetP(newLoadTargetP);
                done += loadTargetP - newLoadTargetP;
                modifiedBuses++;
            }
        }

        LOGGER.debug("{} MW / {} MW distributed at iteration {} to {} buses ({} at min consumption)",
                -done * PerUnit.SB, -remainingMismatch * PerUnit.SB, iteration, modifiedBuses, loadsAtMin);

        return done;
    }

    /**
     * The distribution on arrays, with the arithmetic of {@link ActivePowerDistribution.Step#distribute} and of
     * {@link #resetToInitialState}, {@link #getParticipatingElements}, {@link #run} and {@link
     * #ensurePowerFactorConstant}, step by step, so that the targets are the same to the last bit.
     *
     * <p>Gather: the loads of the participating buses, in their order, with their active and reactive power targets,
     * their variable active power (the participation factor) and, when the power factor is kept constant load by
     * load, the terms of {@link LfLoad#calculateNewTargetQ}. Decide: the factors normalised at each iteration, each
     * load moved by its share, its reactive power target following when the power factor is kept constant. A load is
     * never discarded: it may go to any value. Apply: each load is given its final targets once, the reactive one
     * first, instead of each move.
     *
     * <p>Whether the injections moved sums their moves in the order of the loads, the reference generator first,
     * where the default distribution sums them in the order of a hash map.
     */
    @Override
    public ActivePowerDistribution.Result distribute(LfGenerator referenceGenerator, Collection<LfBus> participatingBuses, double activePowerMismatch) {
        // the reference generator is reset, as ActivePowerDistribution.Step.resetToInitialState does; loads are not
        double referenceMismatch = 0.;
        double referencePreviousTargetP = Double.NaN;
        if (referenceGenerator != null) {
            referenceMismatch -= referenceGenerator.getInitialTargetP() - referenceGenerator.getTargetP();
            referencePreviousTargetP = referenceGenerator.getTargetP();
            referenceGenerator.setTargetP(referenceGenerator.getInitialTargetP());
        }

        // gather
        int capacity = 0;
        for (LfBus bus : participatingBuses) {
            capacity += bus.getLoads().size();
        }
        LfLoad[] loads = new LfLoad[capacity];
        double[] previousTargetP = new double[capacity];
        double[] targetP = new double[capacity];
        double[] targetQ = new double[capacity];
        double[] initialTargetP = new double[capacity];
        LoadPowerFactorTerms[] powerFactorTerms = new LoadPowerFactorTerms[capacity];
        double[] factor = new double[capacity];
        int[] elements = new int[capacity];
        int count = 0;
        int elementCount = 0;
        for (LfBus bus : participatingBuses) {
            for (LfLoad load : bus.getLoads()) {
                loads[count] = load;
                previousTargetP[count] = load.getTargetP();
                targetP[count] = load.getTargetP();
                targetQ[count] = load.getTargetQ();
                initialTargetP[count] = load.getInitialTargetP();
                if (loadPowerFactorConstant && load.ensurePowerFactorConstantByLoad()) {
                    powerFactorTerms[count] = load.getPowerFactorTerms();
                }
                // the elements that take power, as getParticipatingElements lists them
                if (load.getAbsVariableTargetP() != 0) {
                    factor[count] = getParticipationFactor(load);
                    elements[elementCount++] = count;
                }
                count++;
            }
        }
        double remainingMismatch = activePowerMismatch + referenceMismatch;

        // decide
        int iteration = 0;
        while (elementCount > 0 && Math.abs(remainingMismatch) > ActivePowerDistribution.P_RESIDUE_EPS) {
            double factorSum = ActivePowerDistribution.compensatedSum(factor, elements, elementCount);
            if (factorSum > 0.0) {
                for (int k = 0; k < elementCount; k++) {
                    factor[elements[k]] /= factorSum;
                }
                double done = 0d;
                int modifiedBuses = 0;
                for (int k = 0; k < elementCount; k++) {
                    int i = elements[k];
                    double loadTargetP = targetP[i];
                    double newLoadTargetP = loadTargetP - remainingMismatch * factor[i];
                    if (newLoadTargetP != loadTargetP) {
                        LOGGER.trace("Rescale '{}' active power target: {} -> {}",
                                loads[i].getId(), loadTargetP * PerUnit.SB, newLoadTargetP * PerUnit.SB);
                        if (loadPowerFactorConstant) {
                            double newLoadTargetQ = powerFactorTerms[i] != null
                                    ? powerFactorTerms[i].newTargetQ(newLoadTargetP - initialTargetP[i])
                                    : newLoadTargetP * targetQ[i] / loadTargetP;
                            if (newLoadTargetQ != targetQ[i]) {
                                LOGGER.trace("Rescale '{}' reactive power target on load: {} -> {}",
                                        loads[i].getId(), targetQ[i] * PerUnit.SB, newLoadTargetQ * PerUnit.SB);
                                targetQ[i] = newLoadTargetQ;
                            }
                        }
                        targetP[i] = newLoadTargetP;
                        done += loadTargetP - newLoadTargetP;
                        modifiedBuses++;
                    }
                }
                LOGGER.debug("{} MW / {} MW distributed at iteration {} to {} buses ({} at min consumption)",
                        -done * PerUnit.SB, -remainingMismatch * PerUnit.SB, iteration, modifiedBuses, 0);
                remainingMismatch -= done;
            } else {
                break;
            }
            iteration++;
        }

        // apply
        for (int i = 0; i < count; i++) {
            loads[i].setTargetQ(targetQ[i]);
            loads[i].setTargetP(targetP[i]);
        }

        // whether the injections moved, as PreviousStateInfo.moved computes it
        double movedSum = 0;
        double compensation = 0;
        double simpleSum = 0;
        for (int i = referenceGenerator != null ? -1 : 0; i < count; i++) {
            double value = i < 0 ? Math.abs(referenceGenerator.getTargetP() - referencePreviousTargetP)
                                 : Math.abs(targetP[i] - previousTargetP[i]);
            double tmp = value - compensation;
            double velvel = movedSum + tmp;
            compensation = (velvel - movedSum) - tmp;
            movedSum = velvel;
            simpleSum += value;
        }
        double moved = ActivePowerDistribution.finalSum(movedSum, compensation, simpleSum);

        return new ActivePowerDistribution.Result(iteration, remainingMismatch, moved > ActivePowerDistribution.P_RESIDUE_EPS * 0.9);
    }

    private static void ensurePowerFactorConstant(LfLoad load, double newLoadTargetP) {
        // if loadPowerFactorConstant is true, when updating targetP on loads,
        // we have to keep the power factor constant by updating targetQ.
        double newLoadTargetQ;
        if (load.ensurePowerFactorConstantByLoad()) {
            newLoadTargetQ = load.calculateNewTargetQ(newLoadTargetP - load.getInitialTargetP());
        } else {
            newLoadTargetQ = newLoadTargetP * load.getTargetQ() / load.getTargetP();
        }
        if (newLoadTargetQ != load.getTargetQ()) {
            LOGGER.trace("Rescale '{}' reactive power target on load: {} -> {}",
                    load.getId(), load.getTargetQ() * PerUnit.SB, newLoadTargetQ * PerUnit.SB);
            load.setTargetQ(newLoadTargetQ);
        }
    }
}
