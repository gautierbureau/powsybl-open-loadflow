/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

/**
 * The choice of a position of a discrete controller, as functions of numbers: the tap of a transformer, the section of
 * a shunt compensator. A controller is the array of the value each position gives (the {@code r1} or the {@code a1} of
 * each tap, the susceptance of each section), indexed from 0, and its current index.
 *
 * <p>{@link PiModelArray} and {@link LfShunt.Controller} move their positions with these functions, and so do the outer
 * loops that decide on arrays; an engine holding the same arrays gets the same positions. Nothing reads or writes a
 * network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class DiscretePositionDecisions {

    /** The position may only increase. */
    public static final int ALLOWED_INCREASE = 0;
    /** The position may only decrease. */
    public static final int ALLOWED_DECREASE = 1;
    /** The position may move both ways. */
    public static final int ALLOWED_BOTH = 2;

    private DiscretePositionDecisions() {
    }

    public static int toCode(AllowedDirection allowedDirection) {
        return switch (allowedDirection) {
            case INCREASE -> ALLOWED_INCREASE;
            case DECREASE -> ALLOWED_DECREASE;
            case BOTH -> ALLOWED_BOTH;
        };
    }

    /** The lowest index a move from {@code current} may reach, positions below {@code min} being forbidden. */
    public static int allowedFrom(int allowedDirection, int current, int min) {
        return allowedDirection == ALLOWED_INCREASE ? current : min;
    }

    /** The highest index a move from {@code current} may reach, among {@code count} positions. */
    public static int allowedTo(int allowedDirection, int current, int count) {
        return allowedDirection == ALLOWED_DECREASE ? current : count - 1;
    }

    /**
     * The index of the position whose value is the closest to {@code target}, among {@code from} to {@code to} and no
     * further than {@code maxShift} positions from {@code current}. A position replaces the current one only when
     * strictly closer than {@code initialDistance}, then than the best one so far, so that on a tie the lowest index
     * wins and the current position is kept when none is strictly closer.
     *
     * @param initialDistance the distance the current position stands at: {@code |target - values[current]|} for a
     *                        tap, the requested change itself for a shunt section
     */
    public static int closestPosition(double[] values, int current, int from, int to, int maxShift,
                                      double target, double initialDistance) {
        int closest = current;
        double smallestDistance = initialDistance;
        for (int i = from; i <= to; i++) {
            if (Math.abs(i - current) > maxShift) { // we are not allowed to go further than maxShift positions
                continue;
            }
            double distance = Math.abs(target - values[i]);
            if (distance < smallestDistance) {
                closest = i;
                smallestDistance = distance;
            }
        }
        return closest;
    }

    /** Whether going from position {@code n2} to position {@code n1} moves the value against {@code valueShift}. */
    public static boolean isDescending(double[] values, int n1, int n2, double valueShift) {
        return valueShift > 0 ? values[n1] < values[n2] : values[n1] > values[n2];
    }

    /**
     * The neighbour of position {@code i} that moves the value in the direction of {@code valueShift}, -1 when none
     * does (or when the shift is zero).
     */
    public static int nextPosition(double[] values, int i, double valueShift) {
        if (valueShift == 0) {
            return -1;
        }
        int count = values.length;
        if (i == 0 && isDescending(values, i + 1, i, valueShift)) {
            return -1;
        }
        if (i > 0 && isDescending(values, i, i - 1, valueShift)) {
            return i - 1;
        }
        if (i < count - 1 && isDescending(values, i, i + 1, valueShift)) {
            return i + 1;
        }
        return -1;
    }

    /**
     * The position reached by walking from {@code current} in the direction of {@code valueShift}, one neighbour at a
     * time, until the value has moved by at least the shift: the first position at or beyond the shifted value. The
     * walk stays within {@code from} to {@code to} and within {@code maxShift} positions of {@code current}.
     */
    public static int firstPositionAbove(double[] values, int current, int from, int to, int maxShift, double valueShift) {
        int position = current;
        double remainingValueShift = valueShift;
        int next;
        while ((next = nextPosition(values, position, remainingValueShift)) != -1) {
            if (next < from || next > to || Math.abs(next - current) > maxShift) {
                break;
            }
            double value = values[position];
            double nextValue = values[next];
            position = next;
            // stop when shift is not enough to go to next position
            if (remainingValueShift < 0 && value + remainingValueShift > nextValue
                    || remainingValueShift > 0 && value + remainingValueShift < nextValue) {
                break;
            }
            remainingValueShift -= nextValue - value;
        }
        return position;
    }

    /**
     * One position up or down so that the value moves in the requested direction from {@code currentValue}: the next
     * position is tried first, then the previous one from wherever that left the index, as {@link
     * PiModel#shiftOneTapPositionToChangeA1} always did.
     *
     * @param increase the value should increase; otherwise decrease
     */
    public static int shiftOnePosition(double[] values, int current, double currentValue, boolean increase) {
        int position = current;
        if (position < values.length - 1) {
            double nextValue = values[position + 1];
            if (increase && nextValue > currentValue || !increase && nextValue < currentValue) {
                position++;
            }
        }
        if (position > 0) {
            double previousValue = values[position - 1];
            if (increase && previousValue > currentValue || !increase && previousValue < currentValue) {
                position--;
            }
        }
        return position;
    }

    /**
     * Splits a total value over controllers, in order: each one takes the position closest to an equal share of what
     * the previous ones left, from its minimum position up, and the remainder is returned. This is the rounding of a
     * continuous shunt susceptance to sections.
     *
     * @param valuesPerController the value of each position, per controller
     * @param minPositions the lowest position of each controller
     * @param positions the current position of each controller, updated
     * @return the part of {@code total} the positions do not reach
     */
    public static double dispatch(double total, double[][] valuesPerController, int[] minPositions, int[] positions) {
        double residue = total;
        int remaining = valuesPerController.length;
        for (int c = 0; c < valuesPerController.length; c++) {
            double[] values = valuesPerController[c];
            double share = residue / remaining--;
            positions[c] = closestPosition(values, positions[c], minPositions[c], values.length - 1, Integer.MAX_VALUE,
                    share, Math.abs(share - values[positions[c]]));
            residue -= values[positions[c]];
        }
        return residue;
    }
}
