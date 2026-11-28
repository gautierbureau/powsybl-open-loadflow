/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import org.apache.commons.lang3.Range;
import org.apache.commons.math3.complex.Complex;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class PiModelArray implements PiModel {

    private final List<PiModel> models;

    private final int lowTapPosition;

    private int tapPositionIndex;

    private double a1 = Double.NaN; // override a1 at current tap position if not NaN

    private double r1 = Double.NaN; // override r1 at current tap position if not NaN

    private double continuousR1 = Double.NaN;

    private LfBranch branch;

    private final double minR1;

    private final double maxR1;

    // the r1 and the a1 of each tap, by tap index: read once, the taps' models do not change their ratio or their shift
    private final double[] tapR1s;

    private final double[] tapA1s;

    public PiModelArray(List<PiModel> models, int lowTapPosition, int tapPosition) {
        this.models = Objects.requireNonNull(models);
        this.lowTapPosition = lowTapPosition;
        tapPositionIndex = tapPosition - lowTapPosition;
        minR1 = this.models.stream().mapToDouble(PiModel::getMinR1).min().orElseThrow();
        maxR1 = this.models.stream().mapToDouble(PiModel::getMaxR1).max().orElseThrow();
        tapR1s = this.models.stream().mapToDouble(PiModel::getR1).toArray();
        tapA1s = this.models.stream().mapToDouble(PiModel::getA1).toArray();
    }

    /** The r1 of each tap, by tap index (tap position minus the low tap position). Not to be modified. */
    public double[] getTapR1s() {
        return tapR1s;
    }

    /** The a1 of each tap, by tap index (tap position minus the low tap position). Not to be modified. */
    public double[] getTapA1s() {
        return tapA1s;
    }

    public int getLowTapPosition() {
        return lowTapPosition;
    }

    /** The index of the current tap: the tap position minus the low tap position. */
    public int getTapPositionIndex() {
        return tapPositionIndex;
    }

    List<PiModel> getModels() {
        return models;
    }

    private PiModel getModel() {
        return models.get(tapPositionIndex);
    }

    public PiModel getModel(int tapPosition) {
        return models.get(tapPosition - lowTapPosition);
    }

    @Override
    public double getR() {
        return getModel().getR();
    }

    @Override
    public PiModel setR(double r) {
        return getModel().setR(r);
    }

    @Override
    public double getX() {
        return getModel().getX();
    }

    @Override
    public PiModel setX(double x) {
        return getModel().setX(x);
    }

    @Override
    public double getZ() {
        return getModel().getZ();
    }

    @Override
    public double getY() {
        return getModel().getY();
    }

    @Override
    public double getKsi() {
        return getModel().getKsi();
    }

    @Override
    public double getG1() {
        return getModel().getG1();
    }

    @Override
    public double getB1() {
        return getModel().getB1();
    }

    @Override
    public double getG2() {
        return getModel().getG2();
    }

    @Override
    public double getB2() {
        return getModel().getB2();
    }

    public double getModifiedR1() {
        return r1;
    }

    public double getModifiedA1() {
        return a1;
    }

    @Override
    public double getR1() {
        return Double.isNaN(r1) ? getModel().getR1() : r1;
    }

    @Override
    public double getContinuousR1() {
        return continuousR1;
    }

    @Override
    public double getA1() {
        return Double.isNaN(a1) ? getModel().getA1() : a1;
    }

    @Override
    public PiModelArray setA1(double a1) {
        this.a1 = a1;
        return this;
    }

    @Override
    public PiModelArray setR1(double r1) {
        this.r1 = r1;
        return this;
    }

    /** Moves to tap index {@code newPositionIndex}, telling the listeners. */
    private Optional<Direction> moveToTapPositionIndex(int newPositionIndex) {
        int oldPositionIndex = tapPositionIndex;
        tapPositionIndex = newPositionIndex;

        if (tapPositionIndex != oldPositionIndex) {
            PiModel oldPiModel = models.get(oldPositionIndex);
            PiModel newPiModel = models.get(tapPositionIndex);
            boolean modifiedTapImpedance = oldPiModel.getR() != newPiModel.getR() || oldPiModel.getX() != newPiModel.getX();
            for (LfNetworkListener listener : branch.getNetwork().getListeners()) {
                listener.onTapPositionChange(branch, lowTapPosition + oldPositionIndex, lowTapPosition + tapPositionIndex, modifiedTapImpedance);
            }

            return Optional.of(tapPositionIndex - oldPositionIndex > 0 ? Direction.INCREASE : Direction.DECREASE);
        }

        return Optional.empty();
    }

    /**
     * Moves to the tap the decisions on arrays chose, with the effects of a move of {@link
     * #updateTapPositionToReachNewR1} (the overriding r1 dropped) when {@code ratio}, of {@link
     * #updateTapPositionToReachNewA1} (the overriding a1 dropped) otherwise. Nothing changes when the index is the
     * current one.
     *
     * @param newPositionIndex a tap index, as {@link #getTapPositionIndex()}
     */
    public Optional<Direction> applyTapPositionIndex(int newPositionIndex, boolean ratio) {
        if (newPositionIndex < 0 || newPositionIndex >= models.size()) {
            throw new IllegalArgumentException("Tap index " + newPositionIndex + " out of range [0, " + (models.size() - 1) + "]");
        }
        Optional<Direction> direction = moveToTapPositionIndex(newPositionIndex);
        if (direction.isPresent()) {
            if (ratio) {
                r1 = Double.NaN;
            } else {
                a1 = Double.NaN;
            }
        }
        return direction;
    }

    private int closestTapPositionIndex(double[] values, double target, AllowedDirection allowedDirection, int maxTapShift) {
        int allowed = DiscretePositionDecisions.toCode(allowedDirection);
        return DiscretePositionDecisions.closestPosition(values, tapPositionIndex,
                DiscretePositionDecisions.allowedFrom(allowed, tapPositionIndex, 0),
                DiscretePositionDecisions.allowedTo(allowed, tapPositionIndex, models.size()),
                maxTapShift, target, Math.abs(target - values[tapPositionIndex]));
    }

    @Override
    public void roundA1ToClosestTap() {
        if (Double.isNaN(a1)) {
            return; // nothing to do because a1 has not been modified
        }

        // find tap position with the closest a1 value
        moveToTapPositionIndex(closestTapPositionIndex(tapA1s, a1, AllowedDirection.BOTH, Integer.MAX_VALUE));
        a1 = Double.NaN;
    }

    @Override
    public void roundR1ToClosestTap() {
        if (Double.isNaN(r1)) {
            return; // nothing to do because r1 has not been modified
        }

        // find tap position with the closest r1 value
        moveToTapPositionIndex(closestTapPositionIndex(tapR1s, r1, AllowedDirection.BOTH, Integer.MAX_VALUE));
        continuousR1 = r1;
        r1 = Double.NaN;
    }

    @Override
    public boolean shiftOneTapPositionToChangeA1(Direction direction) {
        // an increase direction means that A1 should increase.
        // a decrease direction means that A1 should decrease.
        double currentA1 = getA1();
        int oldTapPositionIndex = tapPositionIndex;

        tapPositionIndex = DiscretePositionDecisions.shiftOnePosition(tapA1s, tapPositionIndex, currentA1, direction == Direction.INCREASE);

        if (tapPositionIndex != oldTapPositionIndex) {
            a1 = Double.NaN;
            PiModel oldPiModel = models.get(oldTapPositionIndex);
            PiModel newPiModel = models.get(tapPositionIndex);
            boolean modifiedTapImpedance = oldPiModel.getR() != newPiModel.getR() || oldPiModel.getX() != newPiModel.getX();
            for (LfNetworkListener listener : branch.getNetwork().getListeners()) {
                listener.onTapPositionChange(branch, lowTapPosition + oldTapPositionIndex, lowTapPosition + tapPositionIndex, modifiedTapImpedance);
            }
            return true;
        }

        return false;
    }

    @Override
    public Optional<Direction> updateTapPositionToReachNewR1(double deltaR1, int maxTapShift, AllowedDirection allowedDirection) {
        double newR1 = getR1() + deltaR1;
        Optional<Direction> direction = moveToTapPositionIndex(closestTapPositionIndex(tapR1s, newR1, allowedDirection, maxTapShift));
        if (direction.isPresent()) {
            r1 = Double.NaN;
        }
        return direction;
    }

    @Override
    public Optional<Direction> updateTapPositionToExceedNewA1(double deltaA1, int maxTapShift, AllowedDirection allowedDirection) {
        int allowed = DiscretePositionDecisions.toCode(allowedDirection);
        Optional<Direction> direction = moveToTapPositionIndex(DiscretePositionDecisions.firstPositionAbove(tapA1s, tapPositionIndex,
                DiscretePositionDecisions.allowedFrom(allowed, tapPositionIndex, 0),
                DiscretePositionDecisions.allowedTo(allowed, tapPositionIndex, models.size()),
                maxTapShift, deltaA1));
        if (direction.isPresent()) {
            a1 = Double.NaN;
        }
        return direction;
    }

    @Override
    public Optional<Direction> updateTapPositionToReachNewA1(double deltaA1, int maxTapShift, AllowedDirection allowedDirection) {
        double newA1 = getA1() + deltaA1;
        Optional<Direction> direction = moveToTapPositionIndex(closestTapPositionIndex(tapA1s, newA1, allowedDirection, maxTapShift));
        if (direction.isPresent()) {
            a1 = Double.NaN;
        }
        return direction;
    }

    @Override
    public Optional<Complex> getCutZ(double minZ, LoadFlowModel loadFlowModel) {
        return getModel().getCutZ(minZ, loadFlowModel);
    }

    @Override
    public boolean setMinZ(double minZ, LoadFlowModel loadFlowModel) {
        boolean done = false;
        for (PiModel model : models) {
            done |= model.setMinZ(minZ, loadFlowModel);
        }
        return done;
    }

    @Override
    public void setBranch(LfBranch branch) {
        this.branch = Objects.requireNonNull(branch);
    }

    @Override
    public int getTapPosition() {
        return lowTapPosition + tapPositionIndex;
    }

    @Override
    public PiModel setTapPosition(int tapPosition) {
        Range<Integer> tapPositionRange = getTapPositionRange();
        if (!tapPositionRange.contains(tapPosition)) {
            throw new IllegalArgumentException("Tap position " + tapPosition + " out of range " + tapPositionRange);
        }
        if (tapPosition - lowTapPosition != tapPositionIndex) {
            int oldTapPositionIndex = tapPositionIndex;
            tapPositionIndex = tapPosition - lowTapPosition;
            r1 = Double.NaN;
            continuousR1 = Double.NaN;
            a1 = Double.NaN;
            PiModel oldPiModel = models.get(oldTapPositionIndex);
            PiModel newPiModel = models.get(tapPosition - lowTapPosition);
            boolean modifiedTapImpedance = oldPiModel.getR() != newPiModel.getR() || oldPiModel.getX() != newPiModel.getX();
            for (LfNetworkListener listener : branch.getNetwork().getListeners()) {
                listener.onTapPositionChange(branch, lowTapPosition + oldTapPositionIndex, tapPosition, modifiedTapImpedance);
            }
        }
        return this;
    }

    @Override
    public double getMinR1() {
        return minR1;
    }

    @Override
    public double getMaxR1() {
        return maxR1;
    }

    @Override
    public Range<Integer> getTapPositionRange() {
        return Range.of(lowTapPosition, lowTapPosition + models.size() - 1);
    }

    @Override
    public PiModelArray copy() {
        List<PiModel> modelCopies = models.stream().map(PiModel::copy).collect(Collectors.toList());
        PiModelArray c = new PiModelArray(modelCopies, lowTapPosition, lowTapPosition + tapPositionIndex);
        c.a1 = a1;
        c.r1 = r1;
        c.continuousR1 = continuousR1;
        return c;
    }

}
