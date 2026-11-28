/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import com.powsybl.openloadflow.network.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class IncrementalContextData {

    /**
     * The ratchet state of one controller ({@link IncrementalControlDecisions}): the direction changes so far, the
     * direction of the last move, the allowed direction, and whether it was found too insensitive to act.
     */
    public static final class ControllerContext {

        private final int maxDirectionChange;

        public ControllerContext(int maxDirectionChange) {
            this.maxDirectionChange = maxDirectionChange;
        }

        private int directionChangeCount = 0;

        private int allowedDirection = DiscretePositionDecisions.ALLOWED_BOTH;

        private int currentDirection = IncrementalControlDecisions.DIRECTION_NONE;

        public AllowedDirection getAllowedDirection() {
            return switch (allowedDirection) {
                case DiscretePositionDecisions.ALLOWED_INCREASE -> AllowedDirection.INCREASE;
                case DiscretePositionDecisions.ALLOWED_DECREASE -> AllowedDirection.DECREASE;
                default -> AllowedDirection.BOTH;
            };
        }

        /** The allowed direction, a {@code DiscretePositionDecisions.ALLOWED_*}. */
        public int getAllowedDirectionCode() {
            return allowedDirection;
        }

        public int getMaxDirectionChange() {
            return maxDirectionChange;
        }

        public int getDirectionChangeCount() {
            return directionChangeCount;
        }

        /** The direction of the last move, an {@code IncrementalControlDecisions.DIRECTION_*}. */
        public int getCurrentDirectionCode() {
            return currentDirection;
        }

        private boolean insensitive = false;

        public void updateAllowedDirection(Direction direction) {
            int move = direction == Direction.INCREASE ? IncrementalControlDecisions.DIRECTION_INCREASE : IncrementalControlDecisions.DIRECTION_DECREASE;
            int count = directionChangeCount;
            directionChangeCount = IncrementalControlDecisions.directionChangeCountAfter(count, maxDirectionChange, currentDirection, move);
            currentDirection = IncrementalControlDecisions.currentDirectionAfter(count, maxDirectionChange, currentDirection, move);
            allowedDirection = IncrementalControlDecisions.allowedDirectionAfter(count, maxDirectionChange, allowedDirection, move);
        }

        public void setInsensitive() {
            insensitive = true;
        }

        private void resetInsensitive() {
            insensitive = false;
        }

        public boolean isInsensitive() {
            return insensitive;
        }
    }

    private final Map<String, ControllerContext> controllersContexts = new HashMap<>();

    private final List<LfBus> candidateControlledBuses;

    private int lastOuterLoopTotalIterations = 0;

    public Map<String, ControllerContext> getControllersContexts() {
        return controllersContexts;
    }

    public List<LfBus> getCandidateControlledBuses() {
        return candidateControlledBuses;
    }

    public IncrementalContextData(LfNetwork network, VoltageControl.Type type) {
        candidateControlledBuses = network.getBuses().stream()
                .filter(bus -> bus.isVoltageControlled(type))
                .toList();
    }

    public IncrementalContextData() {
        candidateControlledBuses = Collections.emptyList();
    }

    public static List<LfBus> getControlledBuses(List<LfBus> candidateControlledBuses, VoltageControl.Type type) {
        return candidateControlledBuses.stream()
                .filter(bus -> bus.getVoltageControl(type).orElseThrow().getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                .filter(bus -> !bus.getVoltageControl(type).orElseThrow().isDisabled())
                .toList();
    }

    public static <E extends LfElement> List<E> getControllerElements(List<LfBus> candidateControlledBuses, VoltageControl.Type type) {
        return getControlledBuses(candidateControlledBuses, type).stream()
                .flatMap(bus -> bus.getVoltageControl(type).orElseThrow().getMergedControllerElements().stream())
                .filter(Predicate.not(LfElement::isDisabled))
                .map(element -> (E) element)
                .toList();
    }

    public void check(int outerLoopTotalIterations) {
        if (outerLoopTotalIterations > lastOuterLoopTotalIterations + 1) {
            // another outer loop executed before our last run, reset insensitive status
            controllersContexts.values().forEach(ControllerContext::resetInsensitive);
        }
        lastOuterLoopTotalIterations = outerLoopTotalIterations;
    }
}
