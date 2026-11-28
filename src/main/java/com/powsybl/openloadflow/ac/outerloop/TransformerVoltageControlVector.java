/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import java.util.Arrays;

/**
 * What one check of {@link IncrementalTransformerVoltageControlOuterLoop} reads and decides, as arrays: the controlled
 * buses out of their deadband, in the order the check looks at them, and for each the controllers it adjusts (enabled,
 * not known as insensitive), in the order of the voltage control's controller elements. The loop fills the inputs from
 * the network, {@link TransformerVoltageControlDecisions} decides on the arrays alone, and the loop replays the moves.
 *
 * <p>The moves are the output, in the order they are decided: a controller of a bus with several controllers moves
 * one tap at a time, and each move goes through the network's listeners as it did when the loop moved the taps itself.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class TransformerVoltageControlVector {

    // ---- controlled buses ----

    public int busCount;
    public int[] busNum = new int[0];
    /** Target voltage minus voltage (pu). */
    public double[] diffV = new double[0];
    public double[] halfTargetDeadband = new double[0];
    /** The controllers of bus {@code b} are {@code firstController[b]} to {@code firstController[b + 1] - 1}. */
    public int[] firstController = new int[1];

    // ---- controllers ----

    public int controllerCount;
    public int[] branchNum = new int[0];
    /** Sensitivity of the controlled bus voltage to the controller's ratio. */
    public double[] sensitivity = new double[0];
    /** The r1 of each tap of the controller, by tap index. */
    public double[][] tapR1s = new double[0][];
    /** The tap index before the check. */
    public int[] initialTapIndex = new int[0];
    /** The controller's r1 before the check: the tap's, or the continuous one that overrides it. */
    public double[] initialR1 = new double[0];
    /** The ratchet state ({@code IncrementalControlDecisions}): gathered before the check, moved by the decisions. */
    public int[] maxDirectionChange = new int[0];
    public int[] directionChangeCount = new int[0];
    public int[] currentDirection = new int[0];
    public int[] allowedDirection = new int[0];

    // ---- outputs ----

    /** The tap index after the check. */
    public int[] tapIndex = new int[0];
    /** The controller's r1 after the check. */
    public double[] r1 = new double[0];
    /** The controller was found too insensitive to act. */
    public boolean[] insensitive = new boolean[0];
    /** The controlled bus ends with all its controllers at a tap limit (for a single controller: moved to one). */
    public boolean[] allControllersAtLimit = new boolean[0];

    public int moveCount;
    /** Move {@code m} brings controller {@code moveController[m]} to tap index {@code moveTapIndex[m]}. */
    public int[] moveController = new int[0];
    public int[] moveTapIndex = new int[0];

    /** Starts a check of {@code newBusCount} buses and {@code newControllerCount} controllers. */
    public void reset(int newBusCount, int newControllerCount) {
        if (newBusCount > busNum.length) {
            busNum = new int[newBusCount];
            diffV = new double[newBusCount];
            halfTargetDeadband = new double[newBusCount];
            firstController = new int[newBusCount + 1];
            allControllersAtLimit = new boolean[newBusCount];
        }
        if (newControllerCount > branchNum.length) {
            branchNum = new int[newControllerCount];
            sensitivity = new double[newControllerCount];
            tapR1s = new double[newControllerCount][];
            initialTapIndex = new int[newControllerCount];
            initialR1 = new double[newControllerCount];
            maxDirectionChange = new int[newControllerCount];
            directionChangeCount = new int[newControllerCount];
            currentDirection = new int[newControllerCount];
            allowedDirection = new int[newControllerCount];
            tapIndex = new int[newControllerCount];
            r1 = new double[newControllerCount];
            insensitive = new boolean[newControllerCount];
        }
        busCount = newBusCount;
        controllerCount = newControllerCount;
        Arrays.fill(allControllersAtLimit, 0, newBusCount, false);
        Arrays.fill(insensitive, 0, newControllerCount, false);
        moveCount = 0;
    }

    void addMove(int controller, int newTapIndex) {
        if (moveCount == moveController.length) {
            int capacity = Math.max(4, 2 * moveCount);
            moveController = Arrays.copyOf(moveController, capacity);
            moveTapIndex = Arrays.copyOf(moveTapIndex, capacity);
        }
        moveController[moveCount] = controller;
        moveTapIndex[moveCount] = newTapIndex;
        moveCount++;
    }
}
