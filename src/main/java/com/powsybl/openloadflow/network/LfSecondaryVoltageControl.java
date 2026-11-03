/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class LfSecondaryVoltageControl implements LfCopyable<LfSecondaryVoltageControl, LfNetwork> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfSecondaryVoltageControl.class);

    /**
     * How the reactive power of the control units of a zone is aligned by the secondary voltage control.
     */
    public enum AlignmentMode {
        /**
         * Align k = (2q - qmax - qmin) / (qmax - qmin), which is -1 at qmin and 1 at qmax: the reactive power of each
         * unit is placed at the same relative position of its reactive diagram.
         */
        K,
        /**
         * Align the level q / qr, where qr is the participation factor of the unit (half of its reactive range by
         * default), which is the alignment variable of the reactive power control loops of the Dynawo/DynaFlow
         * secondary voltage control models: the reactive power is shared proportionally to the participation factors,
         * and the level saturates at -1 and 1.
         */
        Q_QR
    }

    public static final class ControlUnit implements LfCopyable<ControlUnit, LfNetwork> {
        private final String id;

        private boolean participate;

        private final GeneratorVoltageControl generatorVoltageControl;

        /**
         * Participation factor qr of the unit (pu), only used with {@link AlignmentMode#Q_QR}. NaN means default value
         * (half of the reactive range of the unit controller bus).
         */
        private double participationFactor = Double.NaN;

        public ControlUnit(String id, boolean participate, GeneratorVoltageControl generatorVoltageControl) {
            this.id = Objects.requireNonNull(id);
            this.participate = participate;
            this.generatorVoltageControl = Objects.requireNonNull(generatorVoltageControl);
        }

        public ControlUnit copy(LfNetwork copyNetwork) {
            ControlUnit copy = new ControlUnit(id, participate, copyNetwork.getBusById(generatorVoltageControl.getControlledBus().getId()).getGeneratorVoltageControl().orElseThrow());
            copy.participationFactor = participationFactor;
            return copy;
        }

        public double getParticipationFactor() {
            return participationFactor;
        }

        public void setParticipationFactor(double participationFactor) {
            this.participationFactor = participationFactor;
        }

        public String getId() {
            return id;
        }

        public boolean isParticipate() {
            return participate;
        }

        public void setParticipate(boolean participate) {
            this.participate = participate;
        }

        public GeneratorVoltageControl getGeneratorVoltageControl() {
            return generatorVoltageControl;
        }
    }

    private final String zoneName;

    private final LfBus pilotBus;

    private final List<ControlUnit> controlUnits;

    private double targetValue;

    private final AlignmentMode alignmentMode;

    public LfSecondaryVoltageControl(String zoneName, LfBus pilotBus, double targetValue, List<ControlUnit> controlUnits) {
        this(zoneName, pilotBus, targetValue, controlUnits, AlignmentMode.K);
    }

    public LfSecondaryVoltageControl(String zoneName, LfBus pilotBus, double targetValue, List<ControlUnit> controlUnits,
                                     AlignmentMode alignmentMode) {
        this.zoneName = Objects.requireNonNull(zoneName);
        this.pilotBus = Objects.requireNonNull(pilotBus);
        this.targetValue = targetValue;
        this.controlUnits = List.copyOf(controlUnits);
        this.alignmentMode = Objects.requireNonNull(alignmentMode);
        applyParticipationBounds();
    }

    public LfSecondaryVoltageControl copy(LfNetwork copyNetwork) {
        return new LfSecondaryVoltageControl(zoneName,
                copyNetwork.getBusById(pilotBus.getId()),
                targetValue,
                controlUnits.stream().map(controlUnit -> controlUnit.copy(copyNetwork)).toList(),
                alignmentMode);
    }

    public AlignmentMode getAlignmentMode() {
        return alignmentMode;
    }

    /**
     * Set the participation factor (pu) of a control unit, used by {@link AlignmentMode#Q_QR}.
     */
    public void setParticipationFactor(String controlUnitId, double participationFactor) {
        for (ControlUnit controlUnit : controlUnits) {
            if (controlUnit.getId().equals(controlUnitId)) {
                controlUnit.setParticipationFactor(participationFactor);
            }
        }
        applyParticipationBounds();
    }

    /**
     * Half of the reactive range of the generators of a controller bus (pu), without the bounds set by the secondary
     * voltage control itself.
     */
    private static double getHalfReactiveRange(LfBus controllerBus) {
        double minQ = 0;
        double maxQ = 0;
        for (LfGenerator generator : controllerBus.getGenerators()) {
            if (!generator.isDisabled() && generator.getGeneratorControlType() == LfGenerator.GeneratorControlType.VOLTAGE) {
                minQ += generator.getMinQ();
                maxQ += generator.getMaxQ();
            }
        }
        return (maxQ - minQ) / 2;
    }

    /**
     * Participation factor qr (pu) of a controller bus: sum of the participation factors of the control units connected
     * to this bus, or half of the bus reactive range when none has been defined.
     */
    public double getParticipationFactor(LfBus controllerBus) {
        double qr = 0;
        boolean defined = false;
        for (ControlUnit controlUnit : controlUnits) {
            if (controlUnit.isParticipate()
                    && !Double.isNaN(controlUnit.getParticipationFactor())
                    && controlUnit.getGeneratorVoltageControl().getMergedControllerElements().contains(controllerBus)) {
                qr += controlUnit.getParticipationFactor();
                defined = true;
            }
        }
        return defined ? qr : getHalfReactiveRange(controllerBus);
    }

    /**
     * In {@link AlignmentMode#Q_QR}, the level is saturated at -1 and 1 like in the dynamic secondary voltage control
     * models: a participating unit cannot be asked more than its participation factor qr, so the reactive power of its
     * controller bus is bounded to [-qr, qr] (on top of its own reactive limits). Buses of units that do not participate
     * anymore get their bound removed.
     */
    private void applyParticipationBounds() {
        Set<LfBus> participatingBuses = new LinkedHashSet<>();
        Set<LfBus> allBuses = new LinkedHashSet<>();
        for (ControlUnit controlUnit : controlUnits) {
            List<LfBus> buses = controlUnit.getGeneratorVoltageControl().getMergedControllerElements();
            allBuses.addAll(buses);
            if (controlUnit.isParticipate()) {
                participatingBuses.addAll(buses);
            }
        }
        for (LfBus controllerBus : allBuses) {
            if (alignmentMode == AlignmentMode.Q_QR && participatingBuses.contains(controllerBus)) {
                double qr = getParticipationFactor(controllerBus);
                controllerBus.setSecondaryVoltageControlReactiveBounds(-qr, qr);
            } else {
                controllerBus.setSecondaryVoltageControlReactiveBounds(Double.NaN, Double.NaN);
            }
        }
    }

    private static double getControllerBusQ(LfBus controllerBus) {
        return controllerBus.getQ().eval() + controllerBus.getLoadTargetQ();
    }

    /**
     * Alignment variable of a controller bus (see {@link AlignmentMode}), in [-1, 1] when the reactive power is inside
     * its range.
     */
    public double calculateK(LfBus controllerBus) {
        return qToK(getControllerBusQ(controllerBus), controllerBus);
    }

    public double qToK(double q, LfBus controllerBus) {
        return switch (alignmentMode) {
            case K -> (2 * q - controllerBus.getMaxQ() - controllerBus.getMinQ())
                    / (controllerBus.getMaxQ() - controllerBus.getMinQ());
            case Q_QR -> q / getParticipationFactor(controllerBus);
        };
    }

    /**
     * Derivative of the alignment variable with respect to the controller bus reactive power injection.
     */
    public double calculateDkDq(LfBus controllerBus) {
        return switch (alignmentMode) {
            case K -> 2 / (controllerBus.getMaxQ() - controllerBus.getMinQ());
            case Q_QR -> 1 / getParticipationFactor(controllerBus);
        };
    }

    public String getZoneName() {
        return zoneName;
    }

    public double getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(double targetValue) {
        if (this.targetValue != targetValue) {
            this.targetValue = targetValue;
            tryToReEnableHelpfulControllerBuses();
        }
    }

    public LfBus getPilotBus() {
        return pilotBus;
    }

    public void addParticipatingControlUnit(String id) {
        if (setParticipatingControlUnit(id, true)) {
            tryToReEnableHelpfulControllerBuses();
        }
    }

    public void removeParticipatingControlUnit(String id) {
        if (setParticipatingControlUnit(id, false)) {
            tryToReEnableHelpfulControllerBuses();
        }
    }

    private boolean setParticipatingControlUnit(String id, boolean participate) {
        boolean changed = false;
        for (ControlUnit controlUnit : controlUnits) {
            if (controlUnit.getId().equals(id) && controlUnit.isParticipate() != participate) {
                controlUnit.setParticipate(participate);
                changed = true;
            }
        }
        if (changed) {
            applyParticipationBounds();
        }
        return changed;
    }

    public List<ControlUnit> getControlUnits() {
        return controlUnits;
    }

    public Set<GeneratorVoltageControl> getGeneratorVoltageControls() {
        return controlUnits.stream()
                .filter(ControlUnit::isParticipate)
                .map(ControlUnit::getGeneratorVoltageControl)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<LfBus> findControllerBuses(LfBus controlledBus) {
        return controlledBus.getGeneratorVoltageControl().orElseThrow()
                .getMergedControllerElements().stream()
                .filter(controllerBus -> !controllerBus.isDisabled())
                .toList();
    }

    public List<LfBus> getControlledBuses() {
        return getGeneratorVoltageControls().stream()
                .filter(voltageControl -> voltageControl.isVisible() && voltageControl.getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                .map(VoltageControl::getControlledBus)
                .toList();
    }

    private static Optional<LfBus> findAnyControllerBusWithVoltageControlEnabled(LfBus controlledBus) {
        return findControllerBuses(controlledBus).stream()
                .filter(LfBus::isGeneratorVoltageControlEnabled)
                .findAny();
    }

    public Optional<LfBus> findAnyControlledBusWithAtLeastOneControllerBusWithVoltageControlEnabled() {
        return getControlledBuses().stream()
                .flatMap(controlledBus -> findAnyControllerBusWithVoltageControlEnabled(controlledBus).stream())
                .findAny();
    }

    public List<LfBus> getControllerBuses() {
        return getControlledBuses().stream()
                .flatMap(controlledBus -> findControllerBuses(controlledBus).stream())
                .toList();
    }

    public List<LfBus> getEnabledControllerBuses() {
        return getControllerBuses().stream()
                .filter(LfBus::isGeneratorVoltageControlEnabled)
                .toList();
    }

    public void tryToReEnableHelpfulControllerBuses() {
        List<LfBus> controllerBusesToMinQ = new ArrayList<>();
        List<LfBus> controllerBusesToMaxQ = new ArrayList<>();
        List<LfBus> allControllerBuses = new ArrayList<>();
        classifyControllerBuses(allControllerBuses, controllerBusesToMinQ, controllerBusesToMaxQ);

        List<LfBus> controllerBusesToLimit = new ArrayList<>(controllerBusesToMinQ.size() + controllerBusesToMaxQ.size());
        controllerBusesToLimit.addAll(controllerBusesToMinQ);
        controllerBusesToLimit.addAll(controllerBusesToMaxQ);
        // if there is at least one of the generator to the limit but not all, re-enable all generators to help
        // the secondary voltage control to get a reactive power alignment
        if (!controllerBusesToLimit.isEmpty() && controllerBusesToLimit.size() < allControllerBuses.size()) {
            for (LfBus controllerBus : controllerBusesToLimit) {
                controllerBus.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(true);
                controllerBus.setQLimitType(null);
            }
            LOGGER.debug("Secondary voltage control of zone '{}': controller buses {} have been re-enabled because might help to reach pilot bus target",
                    getZoneName(), controllerBusesToLimit);
        }
    }

    private void classifyControllerBuses(List<LfBus> allControllerBuses, List<LfBus> controllerBusesToMinQ, List<LfBus> controllerBusesToMaxQ) {
        getControllerBuses()
                .forEach(controllerBus -> {
                    allControllerBuses.add(controllerBus);
                    controllerBus.getQLimitType().ifPresent(qLimitType -> {
                        if (qLimitType == LfBus.QLimitType.MIN_Q) {
                            controllerBusesToMinQ.add(controllerBus);
                        } else { // MAX_Q
                            controllerBusesToMaxQ.add(controllerBus);
                        }
                    });
                });
    }
}
