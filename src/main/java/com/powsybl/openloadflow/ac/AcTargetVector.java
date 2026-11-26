/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;
import gnu.trove.list.array.TIntArrayList;

import java.util.*;

/**
 * The targets of the AC equations, in the columns of the index.
 *
 * <p>The target of each equation is kept by equation type and element, and computed again only when an event of the
 * network changes what it is computed from: the active power of a bus for its active balance, its reactive power
 * (or its active power, or its voltage control switched, which compute it again) for its reactive balance and its
 * voltage target with a slope, a tap for the targets of its branch, a susceptance
 * for the target of its shunt. A bus or a branch disabled or enabled, or a branch connected or disconnected at one
 * side, forgets the targets of its number and the targets that read other elements (voltage targets, reactive power
 * and slack distributions); a zero impedance network split or merged, the voltage targets and the reactive power
 * distribution. Other events (another element disabled or enabled, a control switched, a target of a control
 * changed, the slack bus moved) are rarer: every target is computed again. When the index changes, the targets are gathered into its columns: a target is computed only for
 * an equation that has none yet. The right-hand side of a single equation is read at each gather.
 *
 * <p>With assertions enabled, each gather is compared with the targets computed again for every equation.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcTargetVector extends TargetVector<AcVariableType, AcEquationType> {

    private static double getBusTargetV(LfBus bus) {
        Objects.requireNonNull(bus);
        double targetV = bus.getHighestPriorityTargetV()
                .orElseThrow(() -> new IllegalStateException("No active voltage control has been found for bus '" + bus.getId() + "'"));
        if (bus.hasGeneratorsWithSlope()) {
            // take first generator with slope: network loading ensures that there's only one generator with slope
            double slope = bus.getGeneratorsControllingVoltageWithSlope().get(0).getSlope();
            targetV -= slope * (bus.getLoadTargetQ() - bus.getGenerationTargetQ());
        }
        return targetV;
    }

    private static double getGeneratorReactivePowerDistributionTarget(LfNetwork network, int busNum) {
        LfBus controllerBus = network.getBus(busNum);
        double target = (controllerBus.getRemoteControlReactivePercent() - 1) * controllerBus.getTargetQ();
        List<LfBus> mergedControllerBuses;
        if (controllerBus.getGeneratorVoltageControl().isPresent()) {
            mergedControllerBuses = controllerBus.getGeneratorVoltageControl().orElseThrow().getMergedControllerElements();
        } else if (controllerBus.hasGeneratorReactivePowerControl()) {
            mergedControllerBuses = controllerBus.getGeneratorReactivePowerControl().orElseThrow().getControllerBuses();
        } else {
            throw new PowsyblException("Controller bus '" + controllerBus.getId() + "' has no voltage or reactive remote control");
        }
        return updateReactivePowerDistributionTarget(target, controllerBus, mergedControllerBuses);
    }

    private static double updateReactivePowerDistributionTarget(double target, LfBus controllerBus, List<LfBus> controllerBuses) {
        double updatedTarget = target;
        for (LfBus otherControllerBus : controllerBuses) {
            if (otherControllerBus != controllerBus) {
                updatedTarget += controllerBus.getRemoteControlReactivePercent() * otherControllerBus.getTargetQ();
            }
        }
        return updatedTarget;
    }

    private static double getReactivePowerControlTarget(LfBranch branch) {
        Objects.requireNonNull(branch);
        return branch.getGeneratorReactivePowerControl().map(GeneratorReactivePowerControl::getTargetValue)
                .orElseThrow(() -> new PowsyblException("Branch '" + branch.getId() + "' has no target in for generator reactive remote control"));
    }

    public static void init(SingleEquation<AcVariableType, AcEquationType> equation, LfNetwork network, double[] targets) {
        init(equation.getType(), equation.getColumn(), equation.getElementNum(), network, targets);
        targets[equation.getColumn()] -= equation.rhs();
    }

    public static void init(AcEquationType equationType, int column, int elementNum, LfNetwork network, double[] targets) {
        switch (equationType) {
            case BUS_TARGET_P:
                targets[column] = network.getBus(elementNum).getTargetP();
                break;

            case BUS_DISTR_SLACK_P:
                LfBus bus = network.getBus(elementNum);
                LfSynchronousNetwork lfScNetwork = network.getSynchronousNetwork(bus.getNumSC());
                targets[column] = bus.getTargetP() - lfScNetwork.getSlackBuses().get(0).getTargetP();
                break;

            case BUS_TARGET_Q:
                targets[column] = network.getBus(elementNum).getTargetQ();
                break;

            case BUS_TARGET_V:
                targets[column] = getBusTargetV(network.getBus(elementNum));
                break;

            case BUS_TARGET_PHI:
                targets[column] = 0;
                break;

            case SHUNT_TARGET_B:
                targets[column] = network.getShunt(elementNum).getB();
                break;

            case BRANCH_TARGET_P1:
            case BRANCH_TARGET_P2:
                targets[column] = LfBranch.getDiscretePhaseControlTarget(network.getBranch(elementNum), TransformerPhaseControl.Unit.MW);
                break;

            case BRANCH_TARGET_Q1:
            case BRANCH_TARGET_Q2:
                targets[column] = getReactivePowerControlTarget(network.getBranch(elementNum));
                break;

            case BRANCH_TARGET_ALPHA1:
                targets[column] = network.getBranch(elementNum).getPiModel().getA1();
                break;

            case BRANCH_TARGET_RHO1:
                targets[column] = network.getBranch(elementNum).getPiModel().getR1();
                break;

            case DISTR_Q:
                targets[column] = getGeneratorReactivePowerDistributionTarget(network, elementNum);
                break;

            case ZERO_V:
                targets[column] = 0;
                break;

            case ZERO_PHI:
                targets[column] = LfBranch.getA(network.getBranch(elementNum));
                break;

            case AC_CONV_TARGET_P_REF:
                targets[column] = network.getVoltageSourceConverter(elementNum).getTargetP();
                break;

            case AC_CONV_TARGET_Q_REF:
                targets[column] = network.getVoltageSourceConverter(elementNum).getTargetQ();
                break;

            case DC_BUS_TARGET_V_REF:
                targets[column] = network.getVoltageSourceConverter(elementNum).getTargetVdc();
                break;

            case DISTR_RHO,
                 DISTR_SHUNT_B,
                 DUMMY_TARGET_P,
                 DUMMY_TARGET_Q,
                 BUS_TARGET_IX_ZERO,
                 BUS_TARGET_IY_ZERO,
                 BUS_TARGET_IX_NEGATIVE,
                 BUS_TARGET_IY_NEGATIVE,
                 DC_BUS_TARGET_I,
                 DC_BUS_GROUND,
                 ACDC_CONV_DC_DROOP:
                targets[column] = 0;
                break;

            default:
                throw new IllegalStateException("Unknown state variable type: " + equationType);
        }
    }

    public static void init(EquationArray<AcVariableType, AcEquationType> equationArray, LfNetwork network, double[] targets) {
        for (int elementNum = 0; elementNum < equationArray.getElementCount(); elementNum++) {
            if (equationArray.isElementActive(elementNum)) {
                int column = equationArray.getElementNumToColumn(elementNum);
                init(equationArray.getType(), column, elementNum, network, targets);
            }
        }
    }

    private static final Initializer<AcVariableType, AcEquationType> INITIALIZER = new Initializer<>() {
        @Override
        public void initialize(SingleEquation<AcVariableType, AcEquationType> equation, LfNetwork network, double[] targets) {
            AcTargetVector.init(equation, network, targets);
        }

        @Override
        public void initialize(EquationArray<AcVariableType, AcEquationType> equationArray, LfNetwork network, double[] targets) {
            AcTargetVector.init(equationArray, network, targets);
        }
    };

    private final LfNetwork network;

    private static final AcEquationType[] TYPES = AcEquationType.values();

    private static final int TYPE_COUNT = TYPES.length;

    /** For each equation type, by ordinal, the target of each element, valid where {@link #computed} is set. */
    private final double[][] targets = new double[TYPE_COUNT][];
    /** For each equation type, by ordinal, whether the target of each element is kept; as long as its row of targets. */
    private final boolean[][] computed = new boolean[TYPE_COUNT][];

    private int computedCount;

    /*
     * The partial update of the equation arrays, which hold most of the columns: for each type of an equation array, by
     * ordinal, the elements whose target was forgotten or whose activity changed since the last update (an inactive column of the
     * index active again, or an element becoming one), or all of them. Only their columns are written again; the single
     * equations, whose right hand side can change without an event, are all written again.
     */
    private final TIntArrayList[] changedElements = new TIntArrayList[TYPE_COUNT];
    private final boolean[] allChanged = new boolean[TYPE_COUNT];
    private boolean partialUpdateValid;

    private final EquationSystemListener<AcVariableType, AcEquationType> activityListener = new EquationSystemListener<>() {

        @Override
        public void onEquationChange(SingleEquation<AcVariableType, AcEquationType> equation, EquationEventType eventType) {
            // the single equations are all written again
        }

        @Override
        public void onEquationTermChange(SingleEquationTerm<AcVariableType, AcEquationType> term, EquationTermEventType eventType) {
            // no target changes
        }

        @Override
        public void onEquationArrayChange(EquationArray<AcVariableType, AcEquationType> equationArray, int elementNum, EquationEventType eventType) {
            changed(equationArray.getType().ordinal(), elementNum);
        }

        @Override
        public void onEquationTermArrayChange(EquationTermArray<AcVariableType, AcEquationType> equationTermArray, int termNum, EquationTermEventType eventType) {
            // no target changes
        }

        @Override
        public void onEquationTermConstantsChange() {
            // no target changes
        }
    };

    private final LfNetworkListener targetListener = new AbstractLfNetworkListener() {

        @Override
        public void onLoadActivePowerTargetChange(LfLoad load, double oldTargetP, double newTargetP) {
            onActivePowerChange(load.getBus());
        }

        @Override
        public void onGenerationActivePowerTargetChange(LfGenerator generator, double oldGenerationTargetP, double newGenerationTargetP) {
            onActivePowerChange(generator.getBus());
        }

        @Override
        public void onLoadReactivePowerTargetChange(LfLoad load, double oldTargetQ, double newTargetQ) {
            onReactivePowerChange(load.getBus());
        }

        @Override
        public void onGeneratorStatusChange(LfGenerator generator) {
            onActivePowerChange(generator.getBus());
        }

        @Override
        public void onGenerationReactivePowerTargetChange(LfBus bus, double oldGenerationTargetQ, double newGenerationTargetQ) {
            onReactivePowerChange(bus);
        }

        @Override
        public void onGeneratorVoltageControlChange(LfBus controllerBus, boolean newVoltageControllerEnabled) {
            // the generation target Q of the bus is computed again when it is read, without an event
            onReactivePowerChange(controllerBus);
        }

        @Override
        public void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition, boolean modifiedTapImpedance) {
            int num = branch.getNum();
            forget(AcEquationType.BRANCH_TARGET_P1, num);
            forget(AcEquationType.BRANCH_TARGET_P2, num);
            forget(AcEquationType.BRANCH_TARGET_ALPHA1, num);
            forget(AcEquationType.BRANCH_TARGET_RHO1, num);
            forget(AcEquationType.ZERO_PHI, num);
        }

        @Override
        public void onShuntSusceptanceChange(LfShunt shunt, double b) {
            forget(AcEquationType.SHUNT_TARGET_B, shunt.getNum());
        }

        @Override
        public void onGeneratorVoltageControlTargetChange(GeneratorVoltageControl control, double newTargetVoltage) {
            forgetAll();
        }

        @Override
        public void onTransformerVoltageControlTargetChange(TransformerVoltageControl transformerVoltageControl, double newTargetVoltage) {
            forgetAll();
        }

        @Override
        public void onDisableChange(LfElement element, boolean disabled) {
            if (element.getType() == ElementType.BUS || element.getType() == ElementType.BRANCH) {
                forgetElement(element.getNum());
            } else {
                forgetAll();
            }
        }

        /**
         * The status changes the active power of the converter stations in their buses (none in linear mode, the
         * equations carry it) and the reactive limits they read at it: an active power change of their two buses.
         */
        @Override
        public void onHvdcAcEmulationStatusChange(LfHvdc hvdc, LfHvdc.AcEmulationControl.AcEmulationStatus acEmulationStatus) {
            onActivePowerChange(hvdc.getConverterStation1().getBus());
            onActivePowerChange(hvdc.getConverterStation2().getBus());
        }

        @Override
        public void onGeneratorReactivePowerControlChange(LfBus controllerBus, boolean newReactiveControllerEnabled) {
            forgetAll();
        }

        @Override
        public void onTransformerPhaseControlChange(LfBranch controllerBranch, boolean newPhaseControlEnabled) {
            forgetAll();
        }

        @Override
        public void onTransformerVoltageControlChange(LfBranch controllerBranch, boolean newVoltageControllerEnabled) {
            forgetAll();
        }

        @Override
        public void onShuntVoltageControlChange(LfShunt controllerShunt, boolean newVoltageControllerEnabled) {
            forgetAll();
        }

        @Override
        public void onZeroImpedanceNetworkSplit(LfZeroImpedanceNetwork initialNetwork, List<LfZeroImpedanceNetwork> splitNetworks, LoadFlowModel loadFlowModel) {
            forgetVoltageTargets();
        }

        @Override
        public void onZeroImpedanceNetworkMerge(LfZeroImpedanceNetwork network1, LfZeroImpedanceNetwork network2, LfZeroImpedanceNetwork mergedNetwork, LoadFlowModel loadFlowModel) {
            forgetVoltageTargets();
        }

        @Override
        public void onBranchConnectionStatusChange(LfBranch branch, TwoSides side, boolean connected) {
            forgetElement(branch.getNum());
        }

        @Override
        public void onSlackBusChange(LfBus bus, boolean slack) {
            forgetAll();
        }
    };

    public AcTargetVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        super(network, equationSystem, INITIALIZER);
        this.network = network;
        network.addListener(targetListener);
        equationSystem.addListener(activityListener);
    }

    private void changed(int type, int elementNum) {
        if (!allChanged[type]) {
            TIntArrayList elements = changedElements[type];
            if (elements == null) {
                elements = new TIntArrayList();
                changedElements[type] = elements;
            }
            elements.add(elementNum);
        }
    }

    private void allChanged(int type) {
        allChanged[type] = true;
        if (changedElements[type] != null) {
            changedElements[type].resetQuick();
        }
    }

    private void clearChanges() {
        Arrays.fill(allChanged, false);
        for (TIntArrayList elements : changedElements) {
            if (elements != null) {
                elements.resetQuick();
            }
        }
    }

    /**
     * The active power of a bus is in its active balance, and the slack's in the distribution of every bus. A
     * generator whose target Q follows its reactive limits moves it with the active power, without an event.
     */
    private void onActivePowerChange(LfBus bus) {
        forget(AcEquationType.BUS_TARGET_P, bus.getNum());
        if (bus.isSlack()) {
            forget(AcEquationType.BUS_DISTR_SLACK_P);
        } else {
            forget(AcEquationType.BUS_DISTR_SLACK_P, bus.getNum());
        }
        onReactivePowerChange(bus);
    }

    /**
     * The reactive power of a bus is in its reactive balance, in its voltage target when a generator has a slope, and
     * in the targets of the reactive power distribution of every control it may belong to.
     */
    private void onReactivePowerChange(LfBus bus) {
        forget(AcEquationType.BUS_TARGET_Q, bus.getNum());
        forget(AcEquationType.BUS_TARGET_V, bus.getNum());
        forget(AcEquationType.DISTR_Q);
        invalidateValues();
    }

    private void forget(AcEquationType type, int elementNum) {
        boolean[] c = computed[type.ordinal()];
        if (c != null && elementNum < c.length) {
            c[elementNum] = false;
        }
        changed(type.ordinal(), elementNum);
    }

    private void forget(AcEquationType type) {
        boolean[] c = computed[type.ordinal()];
        if (c != null) {
            Arrays.fill(c, false);
        }
        allChanged(type.ordinal());
    }

    /**
     * The voltage target of a bus is read from the main voltage controls of its zero impedance network, and the
     * targets of the reactive power distribution from the controller buses of the merged controls.
     */
    private void forgetVoltageTargets() {
        forget(AcEquationType.BUS_TARGET_V);
        forget(AcEquationType.DISTR_Q);
        invalidateValues();
    }

    /**
     * A bus or a branch disabled or enabled, or a branch connected or disconnected at one side: the targets of the
     * element, of every type whose elements have its number (of any kind, more than needed), and the targets that
     * read other elements than their own (the voltage targets, the reactive power distribution, the slack
     * distribution).
     */
    private void forgetElement(int num) {
        for (AcEquationType type : TYPES) {
            forget(type, num);
        }
        forget(AcEquationType.BUS_DISTR_SLACK_P);
        forgetVoltageTargets();
    }

    private void forgetAll() {
        for (boolean[] c : computed) {
            if (c != null) {
                Arrays.fill(c, false);
            }
        }
        for (int type = 0; type < TYPE_COUNT; type++) {
            allChanged(type);
        }
        invalidateValues();
    }

    /** Makes the rows of a type long enough for the given element. */
    private void ensureCapacity(int type, int elementNum) {
        double[] t = targets[type];
        if (t == null || elementNum >= t.length) {
            int length = t == null ? Math.max(16, elementNum + 1) : Math.max(2 * t.length, elementNum + 1);
            targets[type] = t == null ? new double[length] : Arrays.copyOf(t, length);
            computed[type] = t == null ? new boolean[length] : Arrays.copyOf(computed[type], length);
        }
    }

    private double target(AcEquationType type, int elementNum) {
        int ordinal = type.ordinal();
        ensureCapacity(ordinal, elementNum);
        return target(type, elementNum, computed[ordinal], targets[ordinal]);
    }

    private double target(AcEquationType type, int elementNum, boolean[] c, double[] t) {
        if (!c[elementNum]) {
            init(type, elementNum, elementNum, network, t);
            c[elementNum] = true;
            computedCount++;
        }
        return t[elementNum];
    }

    /** Writes every column; with {@code partial}, only the columns of the array elements that changed. */
    private void gather(double[] array, boolean partial) {
        for (SingleEquation<AcVariableType, AcEquationType> equation : equationSystem.getIndex().getSortedSingleEquationsToSolve()) {
            int column = equation.getColumn();
            if (!equation.isActive()) {
                // an inactive column of the index: zero
                array[column] = 0;
                continue;
            }
            array[column] = target(equation.getType(), equation.getElementNum());
            array[column] -= equation.rhs();
        }
        for (EquationArray<AcVariableType, AcEquationType> equationArray : equationSystem.getEquationArrays()) {
            AcEquationType type = equationArray.getType();
            int elementCount = equationArray.getElementCount();
            if (elementCount == 0) {
                continue;
            }
            // the rows of the type, read once for all its elements
            int ordinal = type.ordinal();
            ensureCapacity(ordinal, elementCount - 1);
            boolean[] c = computed[ordinal];
            double[] t = targets[ordinal];
            int[] columns = equationArray.getElementNumToColumn();
            TIntArrayList elements = changedElements[ordinal];
            if (partial && !allChanged[ordinal]) {
                if (elements != null) {
                    for (int i = 0; i < elements.size(); i++) {
                        int elementNum = elements.getQuick(i);
                        if (elementNum < elementCount) {
                            write(equationArray, elementNum, columns, array, type, c, t);
                        }
                    }
                }
            } else {
                for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                    write(equationArray, elementNum, columns, array, type, c, t);
                }
            }
        }
        clearChanges();
        partialUpdateValid = true;
        assert sameAsComputedAgain(array);
    }

    private void write(EquationArray<AcVariableType, AcEquationType> equationArray, int elementNum, int[] columns, double[] array,
                       AcEquationType type, boolean[] c, double[] t) {
        if (equationArray.isElementActive(elementNum)) {
            array[columns[elementNum]] = target(type, elementNum, c, t);
        } else if (columns[elementNum] >= 0) {
            // an inactive column of the index: zero
            array[columns[elementNum]] = 0;
        }
    }

    private boolean sameAsComputedAgain(double[] array) {
        double[] again = createArray(network, equationSystem, INITIALIZER);
        for (int column = 0; column < again.length; column++) {
            if (Double.doubleToLongBits(array[column]) != Double.doubleToLongBits(again[column])) {
                throw new AssertionError("Kept target of " + equationSystem.getIndex().getEquationAtColumn(column)
                        + " is " + array[column] + ", computed again " + again[column]);
            }
        }
        return true;
    }

    @Override
    protected double[] createArray() {
        double[] array = reuseOrCreateArray(equationSystem.getIndex().getColumnCount());
        gather(array, false);
        return array;
    }

    @Override
    protected void updateArray(double[] array) {
        // the columns are those of the last write as long as the vector is not created again
        gather(array, partialUpdateValid);
    }

    /** How many targets were computed since the vector was created. */
    public int getComputedCount() {
        return computedCount;
    }

    @Override
    public void close() {
        super.close();
        network.removeListener(targetListener);
        equationSystem.removeListener(activityListener);
    }
}
