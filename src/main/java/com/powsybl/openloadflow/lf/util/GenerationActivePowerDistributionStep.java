/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * Copyright (c) 2023, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.util;

import com.powsybl.commons.PowsyblException;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Caio Luke {@literal <caio.luke at artelys.com>}
 */
public class GenerationActivePowerDistributionStep implements ActivePowerDistribution.Step {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationActivePowerDistributionStep.class);

    public enum ParticipationType {
        MAX,
        TARGET,
        PARTICIPATION_FACTOR,
        REMAINING_MARGIN
    }

    private final ParticipationType participationType;

    private final boolean useActiveLimits;

    public GenerationActivePowerDistributionStep(ParticipationType pParticipationType, boolean useActiveLimits) {
        this.participationType = pParticipationType;
        this.useActiveLimits = useActiveLimits;
    }

    @Override
    public String getElementType() {
        return "generation";
    }

    @Override
    public ActivePowerDistribution.PreviousStateInfo resetToInitialState(Collection<LfBus> participatingBuses, LfGenerator referenceGenerator) {
        ActivePowerDistribution.PreviousStateInfo previousStateInfo = ActivePowerDistribution.Step.super.resetToInitialState(participatingBuses, referenceGenerator);
        double previousMismatch = 0;
        for (LfBus bus : participatingBuses) {
            for (LfGenerator generator : bus.getGenerators()) {
                if (generator.isParticipating()) {
                    previousMismatch -= generator.getInitialTargetP() - generator.getTargetP();
                    // putIfAbsent because the generator might be the reference generator (in which case it was already reinitialized)
                    previousStateInfo.previousTargetP().putIfAbsent(generator, generator.getTargetP());
                    generator.setTargetP(generator.getInitialTargetP());
                }
            }
        }
        return new ActivePowerDistribution.PreviousStateInfo(previousMismatch + previousStateInfo.previousMismatch(), previousStateInfo.previousTargetP());
    }

    @Override
    public List<ParticipatingElement> getParticipatingElements(Collection<LfBus> participatingBuses, double mismatch) {
        return participatingBuses.stream()
                .flatMap(bus -> bus.getGenerators().stream())
                .map(gen -> {
                    double factor = getParticipationFactor(gen, mismatch);
                    if (isParticipating(gen) && factor != 0) {
                        return new ParticipatingElement(gen, factor);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedList::new));
    }

    @Override
    public double run(List<ParticipatingElement> participatingElements, int iteration, double remainingMismatch) {
        // normalize participation factors at each iteration start as some
        // generators might have reach a limit and have been discarded
        ParticipatingElement.normalizeParticipationFactors(participatingElements);

        double done = 0d;

        int modifiedBuses = 0;
        int generatorsAtMax = 0;
        int generatorsAtMin = 0;
        Iterator<ParticipatingElement> it = participatingElements.iterator();
        while (it.hasNext()) {
            ParticipatingElement participatingGenerator = it.next();
            LfGenerator generator = (LfGenerator) participatingGenerator.getElement();
            double factor = participatingGenerator.getFactor();

            double minTargetP = useActiveLimits ? generator.getMinTargetP() : -Double.MAX_VALUE;
            double maxTargetP = useActiveLimits ? generator.getMaxTargetP() : Double.MAX_VALUE;
            double targetP = generator.getTargetP();

            // we don't want to change the generation sign
            if (targetP < 0) {
                maxTargetP = Math.min(maxTargetP, 0);
            } else {
                minTargetP = Math.max(minTargetP, 0);
            }

            double newTargetP = targetP + remainingMismatch * factor;
            if (remainingMismatch > 0 && newTargetP > maxTargetP) {
                newTargetP = maxTargetP;
                generatorsAtMax++;
                it.remove();
            } else if (remainingMismatch < 0 && newTargetP < minTargetP) {
                newTargetP = minTargetP;
                generatorsAtMin++;
                it.remove();
            }

            if (newTargetP != targetP) {
                LOGGER.trace("Rescale '{}' active power target: {} -> {}",
                        generator.getId(), targetP * PerUnit.SB, newTargetP * PerUnit.SB);
                generator.setTargetP(newTargetP);
                done += newTargetP - targetP;
                modifiedBuses++;
            }
        }

        LOGGER.debug("{} MW / {} MW distributed at iteration {} to {} generators ({} at max power, {} at min power)",
                done * PerUnit.SB, remainingMismatch * PerUnit.SB, iteration, modifiedBuses,
                generatorsAtMax, generatorsAtMin);

        return done;
    }

    /**
     * The distribution on arrays, with the arithmetic of {@link ActivePowerDistribution.Step#distribute} and of
     * {@link #resetToInitialState}, {@link #getParticipatingElements} and {@link #run}, step by step, so that the
     * targets are the same to the last bit.
     *
     * <p>Gather: the participating generators of the participating buses, in their order, with their target before
     * the reset and their initial target. Decide: the factors, normalised over the generators still taking power at
     * each iteration, each generator moved and clamped at its limits, removed when it overshoots one. Apply: each
     * generator is given its final target once, instead of its initial target and then each move.
     *
     * <p>Whether the injections moved sums their moves in the order of the generators, the reference generator first,
     * where the default distribution sums them in the order of a hash map.
     */
    @Override
    public ActivePowerDistribution.Result distribute(LfGenerator referenceGenerator, Collection<LfBus> participatingBuses, double activePowerMismatch) {
        // the reference generator is reset first, as ActivePowerDistribution.Step.resetToInitialState does
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
            capacity += bus.getGenerators().size();
        }
        LfGenerator[] generators = new LfGenerator[capacity];
        double[] previousTargetP = new double[capacity];
        double[] targetP = new double[capacity];
        int count = 0;
        double previousMismatch = 0;
        boolean referenceGathered = false;
        for (LfBus bus : participatingBuses) {
            for (LfGenerator generator : bus.getGenerators()) {
                if (generator.isParticipating()) {
                    previousMismatch -= generator.getInitialTargetP() - generator.getTargetP();
                    generators[count] = generator;
                    if (generator == referenceGenerator) {
                        previousTargetP[count] = referencePreviousTargetP;
                        referenceGathered = true;
                    } else {
                        previousTargetP[count] = generator.getTargetP();
                    }
                    targetP[count] = generator.getInitialTargetP();
                    count++;
                }
            }
        }
        double remainingMismatch = activePowerMismatch + (previousMismatch + referenceMismatch);

        // the elements that take power, as getParticipatingElements lists them
        double[] factor = new double[count];
        int[] elements = new int[count];
        int elementCount = 0;
        for (int i = 0; i < count; i++) {
            double f = getParticipationFactor(generators[i], targetP[i], remainingMismatch);
            if (isParticipating(generators[i]) && f != 0) {
                factor[i] = f;
                elements[elementCount++] = i;
            }
        }

        // decide
        int iteration = 0;
        while (elementCount > 0 && Math.abs(remainingMismatch) > ActivePowerDistribution.P_RESIDUE_EPS) {
            double factorSum = sum(factor, elements, elementCount);
            if (factorSum > 0.0) {
                for (int k = 0; k < elementCount; k++) {
                    factor[elements[k]] /= factorSum;
                }
                double done = 0d;
                int modifiedBuses = 0;
                int generatorsAtMax = 0;
                int generatorsAtMin = 0;
                int kept = 0;
                for (int k = 0; k < elementCount; k++) {
                    int i = elements[k];
                    LfGenerator generator = generators[i];
                    double minTargetP = useActiveLimits ? generator.getMinTargetP() : -Double.MAX_VALUE;
                    double maxTargetP = useActiveLimits ? generator.getMaxTargetP() : Double.MAX_VALUE;
                    double t = targetP[i];

                    // we don't want to change the generation sign
                    if (t < 0) {
                        maxTargetP = Math.min(maxTargetP, 0);
                    } else {
                        minTargetP = Math.max(minTargetP, 0);
                    }

                    double newTargetP = t + remainingMismatch * factor[i];
                    boolean removed = false;
                    if (remainingMismatch > 0 && newTargetP > maxTargetP) {
                        newTargetP = maxTargetP;
                        generatorsAtMax++;
                        removed = true;
                    } else if (remainingMismatch < 0 && newTargetP < minTargetP) {
                        newTargetP = minTargetP;
                        generatorsAtMin++;
                        removed = true;
                    }
                    if (!removed) {
                        elements[kept++] = i;
                    }

                    if (newTargetP != t) {
                        LOGGER.trace("Rescale '{}' active power target: {} -> {}",
                                generator.getId(), t * PerUnit.SB, newTargetP * PerUnit.SB);
                        targetP[i] = newTargetP;
                        done += newTargetP - t;
                        modifiedBuses++;
                    }
                }
                elementCount = kept;

                LOGGER.debug("{} MW / {} MW distributed at iteration {} to {} generators ({} at max power, {} at min power)",
                        done * PerUnit.SB, remainingMismatch * PerUnit.SB, iteration, modifiedBuses,
                        generatorsAtMax, generatorsAtMin);

                remainingMismatch -= done;
            } else {
                break;
            }
            iteration++;
        }

        // apply
        for (int i = 0; i < count; i++) {
            generators[i].setTargetP(targetP[i]);
        }

        // whether the injections moved, as PreviousStateInfo.moved computes it
        double movedSum = 0;
        double compensation = 0;
        double simpleSum = 0;
        int first = referenceGenerator != null && !referenceGathered ? -1 : 0;
        for (int i = first; i < count; i++) {
            double value = i < 0 ? Math.abs(referenceGenerator.getTargetP() - referencePreviousTargetP)
                                 : Math.abs(targetP[i] - previousTargetP[i]);
            double tmp = value - compensation;
            double velvel = movedSum + tmp;
            compensation = (velvel - movedSum) - tmp;
            movedSum = velvel;
            simpleSum += value;
        }
        double moved = finalSum(movedSum, compensation, simpleSum);

        return new ActivePowerDistribution.Result(iteration, remainingMismatch, moved > ActivePowerDistribution.P_RESIDUE_EPS * 0.9);
    }

    /** The compensated sum of {@code DoubleStream.sum}, term for term, of the values at the given indices. */
    private static double sum(double[] values, int[] indices, int count) {
        double sum = 0;
        double compensation = 0;
        double simpleSum = 0;
        for (int k = 0; k < count; k++) {
            double value = values[indices[k]];
            double tmp = value - compensation;
            double velvel = sum + tmp;
            compensation = (velvel - sum) - tmp;
            sum = velvel;
            simpleSum += value;
        }
        return finalSum(sum, compensation, simpleSum);
    }

    private static double finalSum(double sum, double compensation, double simpleSum) {
        double tmp = sum - compensation;
        return Double.isNaN(tmp) && Double.isInfinite(simpleSum) ? simpleSum : tmp;
    }

    private double getParticipationFactor(LfGenerator generator, double mismatch) {
        return getParticipationFactor(generator, generator.getTargetP(), mismatch);
    }

    private double getParticipationFactor(LfGenerator generator, double generatorTargetP, double mismatch) {
        return switch (participationType) {
            case MAX -> generator.getMaxP() / generator.getDroop();
            case TARGET -> Math.abs(generatorTargetP);
            case PARTICIPATION_FACTOR -> generator.getParticipationFactor();
            case REMAINING_MARGIN -> {
                if (Double.isNaN(mismatch)) {
                    throw new PowsyblException("The sign of the active power mismatch is unknown, it is mandatory for REMAINING_MARGIN participation type");
                }
                // Distribution does not change sign of targetP, we take this into account in remaining margin calculation.
                // All participating generators should hit their limit together, depending on the direction and initial targetP,
                // their limit may be not only minP/maxP, but also the forbidden zero crossing.
                double targetP = generatorTargetP;
                double minP = generator.getMinP();
                double maxP = generator.getMaxP();
                if (targetP < 0) {
                    maxP = Math.min(maxP, 0);
                } else {
                    minP = Math.max(minP, 0);
                }
                yield mismatch > 0. ? Math.max(0.0, maxP - targetP) : Math.max(0.0, targetP - minP);
            }
        };
    }

    private boolean isParticipating(LfGenerator generator) {
        // check first if generator is set to be participating
        if (!generator.isParticipating()) {
            return false;
        }
        // then depending on participation type, a generator may be found to not participate
        switch (participationType) {
            case MAX:
                return generator.getDroop() != 0;
            case PARTICIPATION_FACTOR:
                return generator.getParticipationFactor() > 0;
            case TARGET,
                 REMAINING_MARGIN:
                // nothing more to do here: the check whether TargetP is within Pmin-Pmax range
                // was already made in AbstractLfGenerator#checkActivePowerControl
                // whose result is reflected in generator.isParticipating()
                return true;
            default:
                throw new UnsupportedOperationException("Unknown balance type mode: " + participationType);
        }
    }
}
