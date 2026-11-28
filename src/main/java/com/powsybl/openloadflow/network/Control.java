/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.util.PerUnit;

import java.util.Arrays;
import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class Control {

    protected double targetValue;

    protected Control(double targetValue) {
        this.targetValue = targetValue;
    }

    public double getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(double targetValue) {
        this.targetValue = targetValue;
    }

    private static double[] createUniformReactiveKeys(List<LfBus> controllerBuses, LfGenerator.GeneratorControlType generatorControlType) {
        double[] qKeys = new double[controllerBuses.size()];
        for (int i = 0; i < controllerBuses.size(); i++) {
            LfBus controllerBus = controllerBuses.get(i);
            qKeys[i] = controllerBus.getGenerators().stream()
                    .filter(gen -> gen.getGeneratorControlType() == generatorControlType).count();
        }
        return qKeys;
    }

    private static double[] createReactiveKeysFromMaxReactivePowerRange(List<LfBus> controllerBuses, LfGenerator.GeneratorControlType generatorControlType) {
        double[] qKeys = new double[controllerBuses.size()];
        // try to build keys from reactive power range
        for (int i = 0; i < controllerBuses.size(); i++) {
            LfBus controllerBus = controllerBuses.get(i);
            for (LfGenerator generator : controllerBus.getGenerators()) {
                if (generator.isDisabled()) {
                    continue; // lost by a contingency
                }
                double maxRangeQ = generator.getRangeQ(LfGenerator.ReactiveRangeMode.MAX);
                // if one reactive range is not plausible, we fallback to uniform keys
                if (maxRangeQ < PlausibleValues.MIN_REACTIVE_RANGE / PerUnit.SB || maxRangeQ > PlausibleValues.MAX_REACTIVE_RANGE / PerUnit.SB) {
                    return createUniformReactiveKeys(controllerBuses, generatorControlType);
                } else {
                    qKeys[i] += maxRangeQ;
                }
            }
        }
        return qKeys;
    }

    /**
     * The share of each controller in a distribution of reactive power: its key over the sum of the keys of the active
     * controllers, 0 for an inactive one. The sum is the one of {@link Arrays#stream(double[])}, a compensated sum in
     * member order, so that the shares are the same to the last bit wherever they are computed.
     *
     * @param keys the raw keys of the controllers, in member order; the inactive ones are set to 0
     * @param active whether each controller takes part
     * @param shares where the shares are written
     */
    static void distribute(double[] keys, boolean[] active, double[] shares) {
        for (int i = 0; i < keys.length; i++) {
            if (!active[i]) {
                keys[i] = 0d;
            }
        }
        double sum = Arrays.stream(keys).sum();
        for (int i = 0; i < keys.length; i++) {
            shares[i] = sum == 0 ? 0 : keys[i] / sum;
        }
    }

    public static double[] createReactiveKeys(List<LfBus> controllerBuses, LfGenerator.GeneratorControlType generatorControlType) {
        double[] qKeys = new double[controllerBuses.size()];
        for (int i = 0; i < controllerBuses.size(); i++) {
            LfBus controllerBus = controllerBuses.get(i);
            for (LfGenerator generator : controllerBus.getGenerators()) {
                if (generator.isDisabled()) {
                    continue; // lost by a contingency
                }
                double qKey = generator.getRemoteControlReactiveKey().orElse(Double.NaN);
                if (Double.isNaN(qKey)) {
                    // in case of one missing key, we fallback to keys based on reactive power range
                    return createReactiveKeysFromMaxReactivePowerRange(controllerBuses, generatorControlType);
                } else {
                    qKeys[i] += qKey;
                }
            }
        }
        return qKeys;
    }
}
