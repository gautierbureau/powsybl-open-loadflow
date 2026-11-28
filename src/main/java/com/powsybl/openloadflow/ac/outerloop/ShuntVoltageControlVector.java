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
 * What one check of {@link IncrementalShuntVoltageControlOuterLoop} reads and decides, as arrays, on three levels: the
 * controlled buses out of their deadband, in the order the check looks at them; for each, its enabled controller
 * shunts, by decreasing susceptance magnitude; for each shunt, its section controllers (one per shunt compensator). The
 * loop fills the inputs from the network, {@link ShuntVoltageControlDecisions} decides on the arrays alone, and the
 * loop replays the moves, one section at a time, in the order they are decided.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class ShuntVoltageControlVector {

    // ---- controlled buses ----

    public int busCount;
    public int[] busNum = new int[0];
    /** Target voltage minus voltage (pu). */
    public double[] diffV = new double[0];
    public double[] halfTargetDeadband = new double[0];
    /** The shunts of bus {@code b} are {@code firstShunt[b]} to {@code firstShunt[b + 1] - 1}. */
    public int[] firstShunt = new int[1];

    // ---- shunts ----

    public int shuntCount;
    public int[] shuntNum = new int[0];
    /** Sensitivity of the controlled bus voltage to the shunt's susceptance. */
    public double[] sensitivity = new double[0];
    /** The section controllers of shunt {@code s} are {@code firstController[s]} to {@code firstController[s + 1] - 1}. */
    public int[] firstController = new int[1];

    // ---- section controllers ----

    public int controllerCount;
    /** The controller's index among its shunt's controllers. */
    public int[] indexInShunt = new int[0];
    /** The susceptance of each section of the controller, by position. */
    public double[][] sectionBs = new double[0][];
    public int[] minPosition = new int[0];
    /** The section before the check. */
    public int[] initialPosition = new int[0];
    /** The ratchet state ({@code IncrementalControlDecisions}): gathered before the check, moved by the decisions. */
    public int[] maxDirectionChange = new int[0];
    public int[] directionChangeCount = new int[0];
    public int[] currentDirection = new int[0];
    public int[] allowedDirection = new int[0];

    // ---- outputs ----

    /** The section after the check. */
    public int[] position = new int[0];
    /** The moves the controller made in this check. */
    public int[] sectionShift = new int[0];

    public int moveCount;
    /** Move {@code m} brings controller {@code moveController[m]} to section {@code movePosition[m]}. */
    public int[] moveController = new int[0];
    public int[] movePosition = new int[0];

    /** Starts a check of the given numbers of buses, shunts and section controllers. */
    public void reset(int newBusCount, int newShuntCount, int newControllerCount) {
        if (newBusCount > busNum.length) {
            busNum = new int[newBusCount];
            diffV = new double[newBusCount];
            halfTargetDeadband = new double[newBusCount];
            firstShunt = new int[newBusCount + 1];
        }
        if (newShuntCount > shuntNum.length) {
            shuntNum = new int[newShuntCount];
            sensitivity = new double[newShuntCount];
            firstController = new int[newShuntCount + 1];
        }
        if (newControllerCount > indexInShunt.length) {
            indexInShunt = new int[newControllerCount];
            sectionBs = new double[newControllerCount][];
            minPosition = new int[newControllerCount];
            initialPosition = new int[newControllerCount];
            maxDirectionChange = new int[newControllerCount];
            directionChangeCount = new int[newControllerCount];
            currentDirection = new int[newControllerCount];
            allowedDirection = new int[newControllerCount];
            position = new int[newControllerCount];
            sectionShift = new int[newControllerCount];
        }
        busCount = newBusCount;
        shuntCount = newShuntCount;
        controllerCount = newControllerCount;
        Arrays.fill(sectionShift, 0, newControllerCount, 0);
        moveCount = 0;
    }

    void addMove(int controller, int newPosition) {
        if (moveCount == moveController.length) {
            int capacity = Math.max(4, 2 * moveCount);
            moveController = Arrays.copyOf(moveController, capacity);
            movePosition = Arrays.copyOf(movePosition, capacity);
        }
        moveController[moveCount] = controller;
        movePosition[moveCount] = newPosition;
        moveCount++;
    }
}
