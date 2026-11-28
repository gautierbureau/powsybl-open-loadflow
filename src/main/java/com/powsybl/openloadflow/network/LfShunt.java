/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.util.Evaluable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface LfShunt extends LfElement, LfCopyable<LfShunt, LfBus> {

    Logger LOGGER = LoggerFactory.getLogger(LfShunt.class);

    class Controller {

        private final String id;

        private final List<Double> sectionsB;

        private final List<Double> sectionsG;

        private int position;

        private final int minPosition;

        private final double bMagnitude;

        // the susceptance of each section, as an array: read once, the sections do not change
        private final double[] sectionBs;

        public Controller(String id, List<Double> sectionsB, List<Double> sectionsG, int position, int minPosition) {
            this(id, sectionsB, sectionsB.stream().mapToDouble(Double::doubleValue).toArray(), sectionsG, position, minPosition);
        }

        /** A controller over the sections of another one: the array of their susceptances is shared. */
        protected Controller(String id, Controller sections, int position) {
            this(id, sections.sectionsB, sections.sectionBs, sections.sectionsG, position, sections.minPosition);
        }

        private Controller(String id, List<Double> sectionsB, double[] sectionBs, List<Double> sectionsG, int position, int minPosition) {
            this.id = Objects.requireNonNull(id);
            this.sectionsB = Objects.requireNonNull(sectionsB);
            this.sectionBs = sectionBs;
            this.sectionsG = Objects.requireNonNull(sectionsG);
            this.position = position;
            this.minPosition = minPosition;
            double bMin = Math.min(sectionsB.get(minPosition), sectionsB.getLast());
            double bMax = Math.max(sectionsB.get(minPosition), sectionsB.getLast());
            this.bMagnitude = Math.abs(bMax - bMin);
        }

        public String getId() {
            return id;
        }

        public List<Double> getSectionsB() {
            return sectionsB;
        }

        /** The susceptance of each section, by position. Not to be modified. */
        public double[] getSectionBs() {
            return sectionBs;
        }

        public List<Double> getSectionsG() {
            return sectionsG;
        }

        public int getPosition() {
            return position;
        }

        public int getMinPosition() {
            return minPosition;
        }

        public void setPosition(int position) {
            this.position = position;
        }

        public double getB() {
            return sectionsB.get(this.position);
        }

        public double getG() {
            return sectionsG.get(this.position);
        }

        public double getBMagnitude() {
            return bMagnitude;
        }

        public Optional<Direction> updateSectionB(double deltaB, int maxSectionShift, AllowedDirection allowedDirection) {
            // an increase allowed direction means that the section could increase.
            // a decrease allowed direction means that the section could decrease.
            double newB = getB() + deltaB;
            int allowed = DiscretePositionDecisions.toCode(allowedDirection);

            int oldSection = position;
            // find section with the closest b value without exceeding the maximum of sections to switch.
            position = DiscretePositionDecisions.closestPosition(sectionBs, oldSection,
                    DiscretePositionDecisions.allowedFrom(allowed, oldSection, minPosition),
                    DiscretePositionDecisions.allowedTo(allowed, oldSection, sectionBs.length),
                    maxSectionShift, newB, Math.abs(deltaB));

            boolean hasChanged = position != oldSection;
            if (hasChanged) {
                LOGGER.debug("Controller '{}' change section from {} to {}", id, oldSection, position);
                return Optional.of(position - oldSection > 0 ? Direction.INCREASE : Direction.DECREASE);
            }
            return Optional.empty();
        }

        public void updateSectionB(int newPosition) {
            position = newPosition;
        }
    }

    double getB();

    default double getBMagnitude() {
        return Math.abs(getB());
    }

    void setB(double b);

    double dispatchB();

    double getG();

    void setG(double g);

    void updateState(LfNetworkStateUpdateParameters parameters);

    boolean hasVoltageControlCapability();

    void setVoltageControlCapability(boolean voltageControlCapability);

    boolean isVoltageControlEnabled();

    void setVoltageControlEnabled(boolean voltageControlEnabled);

    Optional<ShuntVoltageControl> getVoltageControl();

    void setVoltageControl(ShuntVoltageControl voltageControl);

    void reInit();

    List<Controller> getControllers();

    Evaluable getP();

    void setP(Evaluable evaluable);

    Evaluable getQ();

    void setQ(Evaluable evaluable);
}
