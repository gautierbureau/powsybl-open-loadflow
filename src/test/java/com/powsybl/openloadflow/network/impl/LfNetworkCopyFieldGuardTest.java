/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.openloadflow.graph.GraphConnectivity;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.Evaluable;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards {@link LfNetworkCopier} against field drift: when a field is added to (or removed from) one
 * of the classes of the LfNetwork copy graph, this test fails so that the author classifies the new
 * field (copied / shared / reset / wired at network level) and updates the copy constructors and the
 * inventory below accordingly.
 *
 * <p>The classes of the copy graph are discovered from {@link LfNetwork} by following the instance
 * fields, rather than trusted to the inventory: a class entering the graph (a nested class held by a
 * field of its outer class, a new implementation of an interface, ...) fails the test too until its
 * fields are classified.</p>
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class LfNetworkCopyFieldGuardTest {

    /**
     * Types whose instances are outside the copy graph, so that the discovery does not follow the
     * fields holding them: IIDM handles and strategies shared with the original network, solver
     * injected evaluables, per network listeners and lazily recomputed structures.
     */
    private static final Set<Class<?>> OUT_OF_GRAPH_TYPES = Set.of(
            Ref.class, SlackBusSelector.class, ReferenceBusSelector.class, GraphConnectivityFactory.class,
            Evaluable.class,
            LfNetworkListener.class,
            GraphConnectivity.class, LfZeroImpedanceNetwork.class);

    private static Map<Class<?>, Set<String>> createFieldInventory() {
        Map<Class<?>, Set<String>> expected = new HashMap<>();
        expected.put(AbstractElement.class, Set.of("network", "num", "disabled"));
        expected.put(AbstractPropertyBag.class, Set.of("properties"));
        expected.put(AbstractLfBus.class, Set.of("slack", "reference", "v", "calculatedV", "angle", "hasGeneratorsWithSlope", "generatorVoltageControlEnabled",
                "generatorReactivePowerControlEnabled", "generationTargetP", "generationTargetQ", "invalidatedGenerationTargetQ", "qLimitType", "generators",
                "converters", "shunt", "controllerShunt", "svcShunt", "distributedOnConformLoad", "loads", "loadTargetP", "loadTargetQ", "branches", "hvdcs",
                "generatorVoltageControl", "generatorReactivePowerControl", "transformerVoltageControl", "voltageSourceConverterVoltageControl", "shuntVoltageControl",
                "p", "q", "remoteControlReactivePercent", "zeroImpedanceNetwork", "asym", "area", "isGenerationTargetQFrozen", "forceTargetQInReactiveLimits", "numSC"));
        expected.put(LfBusImpl.class, Set.of("busRef", "nominalV", "lowVoltageLimit", "highVoltageLimit", "participating", "breakers", "country", "bbsIds", "fictitiousInjectionTargetP",
                "fictitiousInjectionTargetQ", "violationLocation"));
        expected.put(LfStarBus.class, Set.of("t3wtRef", "nominalV"));
        expected.put(LfBoundaryLineBus.class, Set.of("boundaryLineRef", "nominalV"));
        expected.put(AbstractLfInjection.class, Set.of("initialTargetP", "targetP"));
        expected.put(AbstractLfGenerator.class, Set.of("network", "bus", "calculatedQ", "targetV", "switchedToLocalVoltageRegulation", "generatorControlType",
                "controlledBusId", "controlledBranchId", "controlledBranchSide", "remoteTargetQ", "disabled", "asym", "referencePriority", "reference", "extrapolateReactiveLimits"));
        expected.put(LfGeneratorImpl.class, Set.of("generatorRef", "initialParticipating", "participating", "droop", "participationFactor", "qPercent",
                "isTargetQForcedInReactiveLimits", "forceVoltageControl", "maxTargetP", "minTargetP", "forceTargetQInReactiveLimits"));
        expected.put(LfBatteryImpl.class, Set.of("batteryRef", "initialParticipating", "participating", "droop", "participationFactor", "maxTargetP", "minTargetP"));
        expected.put(LfStaticVarCompensatorImpl.class, Set.of("svcRef", "reactiveLimits", "nominalV", "slope", "targetQ", "standByAutomaton", "b0", "standByAutomatonShunt"));
        expected.put(LfStaticVarCompensatorImpl.SvcReactiveLimits.class, Set.of());
        expected.put(LfStaticVarCompensator.StandByAutomaton.class, Set.of("highVoltageThreshold", "lowVoltageThreshold", "highTargetV", "lowTargetV"));
        expected.put(LfVscConverterStationImpl.class, Set.of("stationRef", "lossFactor", "hvdc", "hvdcDanglingInIidm"));
        expected.put(LfBoundaryLineGenerator.class, Set.of("boundaryLineRef"));
        expected.put(LfLoadImpl.class, Set.of("bus", "loadModel", "loadsRefs", "lccCsRefs", "targetQ", "ensurePowerFactorConstantByLoad", "loadsAbsVariableTargetP",
                "absVariableTargetP", "distributedOnConformLoad", "loadsDisablingStatus", "p", "q"));
        expected.put(LfLoadModel.class, Set.of("expTermsP", "expTermsQ"));
        expected.put(LfLoadModel.ExpTerm.class, Set.of("c", "n"));
        expected.put(LfShunt.Controller.class, Set.of("id", "sectionsB", "sectionsG", "position", "minPosition", "bMagnitude"));
        expected.put(LfShuntImpl.ControllerImpl.class, Set.of("shuntCompensatorRef"));
        expected.put(LfShuntImpl.class, Set.of("shuntCompensatorsRefs", "bus", "voltageControl", "voltageControlCapability", "voltageControlEnabled", "controllers", "b", "zb", "g"));
        expected.put(AbstractLfShunt.class, Set.of("q", "p"));
        expected.put(LfStandbyAutomatonShunt.class, Set.of("svc", "b"));
        expected.put(AbstractLfBranch.class, Set.of("bus1", "bus2", "currentLimits1", "activePowerLimits1", "apparentPowerLimits1", "currentLimits2", "activePowerLimits2",
                "apparentPowerLimits2", "piModel", "phaseControl", "phaseControlEnabled", "voltageControl", "voltageControlEnabled", "transformerReactivePowerControl",
                "zeroImpedanceContextByModel", "a1", "generatorReactivePowerControl", "asymLine"));
        expected.put(AbstractImpedantLfBranch.class, Set.of("connectedSide1", "connectedSide2", "disconnectionAllowedSide1", "disconnectionAllowedSide2", "p1", "q1", "i1",
                "p2", "q2", "i2", "openP1", "openQ1", "openI1", "openP2", "openQ2", "openI2", "closedP1", "closedQ1", "closedI1", "closedP2", "closedQ2", "closedI2",
                "additionalOpenP1", "additionalClosedP1", "additionalOpenQ1", "additionalClosedQ1", "additionalOpenP2", "additionalClosedP2", "additionalOpenQ2", "additionalClosedQ2"));
        expected.put(LfBranchImpl.class, Set.of("branchRef"));
        expected.put(LfBranch.LfLimitsGroup.class, Set.of("sortedLimits", "operationalLimitsGroupId"));
        expected.put(LfBranch.LfLimit.class, Set.of("name", "acceptableDuration", "value", "scaling"));
        expected.put(AbstractLfBranch.ZeroImpedanceContext.class, Set.of("spanningTreeEdge", "zeroImpedance"));
        expected.put(LfLegBranch.class, Set.of("twtRef", "legRef"));
        expected.put(LfTieLineBranch.class, Set.of("boundaryLine1Ref", "boundaryLine2Ref", "id"));
        expected.put(LfBoundaryLineBranch.class, Set.of("boundaryLineRef"));
        expected.put(LfSwitch.class, Set.of("switchRef"));
        expected.put(LfHvdcImpl.class, Set.of("id", "bus1", "bus2", "p1", "p2", "r", "nominalV", "converterStation1", "converterStation2", "acEmulation", "acEmulationControl"));
        expected.put(LfHvdcImpl.AcEmulationControl.class, Set.of("hvdc", "droop", "p0", "pMaxFromCS1toCS2", "pMaxFromCS2toCS1", "acEmulationStatus"));
        expected.put(AbstractLfDcBus.class, Set.of("v", "nominalV"));
        expected.put(LfDcBusImpl.class, Set.of("dcBusRef", "isGrounded"));
        expected.put(AbstractLfDcLine.class, Set.of("dcBus1", "dcBus2", "p1", "i1", "p2", "i2", "r"));
        expected.put(LfDcLineImpl.class, Set.of("dcLineRef"));
        expected.put(AbstractLfAcDcConverter.class, Set.of("calculatedPac", "calculatedQac", "calculatedIconv1", "calculatedIconv2", "targetP", "pAc", "qAc", "lossFactors",
                "targetVdc", "controlMode", "dcBus1", "dcBus2", "bus1"));
        expected.put(LfAcDcConverter.LossFactors.class, Set.of("idleLoss", "switchingLoss", "resistiveLoss"));
        expected.put(LfVoltageSourceConverterImpl.class, Set.of("converterRef", "isVoltageRegulatorOn", "targetQ", "targetVac", "droopBands"));
        expected.put(LfVoltageSourceConverter.LfDroopReference.class, Set.of("k", "refVdc", "refP"));
        expected.put(LfAsymBus.class, Set.of("bus", "totalDeltaPa", "totalDeltaQa", "totalDeltaPb", "totalDeltaQb", "totalDeltaPc", "totalDeltaQc", "vz", "angleZ", "vn",
                "angleN", "bzEquiv", "gzEquiv", "bnEquiv", "gnEquiv", "ixZ", "iyZ", "ixN", "iyN"));
        expected.put(LfAsymGenerator.class, Set.of("bz", "gz", "gn", "bn"));
        expected.put(LfAsymLine.class, Set.of("piZeroComponent", "piPositiveComponent", "piNegativeComponent", "phaseOpenA", "phaseOpenB", "phaseOpenC", "admittanceMatrix"));
        expected.put(LfAsymLineAdmittanceMatrix.class, Set.of("mYzpn"));
        expected.put(LfAreaImpl.class, Set.of("areaRef", "interchangeTarget", "buses", "boundaries"));
        expected.put(LfAreaImpl.BoundaryImpl.class, Set.of("branch", "side"));
        expected.put(SimplePiModel.class, Set.of("r", "x", "g1", "b1", "g2", "b2", "r1", "a1"));
        expected.put(PiModelArray.class, Set.of("models", "lowTapPosition", "tapPositionIndex", "a1", "r1", "continuousR1", "branch", "minR1", "maxR1"));
        expected.put(Control.class, Set.of("targetValue"));
        expected.put(VoltageControl.class, Set.of("type", "priority", "targetPriority", "controlledBus", "controllerElements", "mergeStatus", "mergedDependentVoltageControls",
                "mainMergedVoltageControl", "disabled"));
        expected.put(DiscreteVoltageControl.class, Set.of("targetDeadband"));
        expected.put(GeneratorVoltageControl.class, Set.of());
        expected.put(TransformerVoltageControl.class, Set.of());
        expected.put(ShuntVoltageControl.class, Set.of());
        expected.put(VoltageSourceConverterVoltageControl.class, Set.of());
        expected.put(ReactivePowerControl.class, Set.of("controlledSide", "controlledBranch"));
        expected.put(GeneratorReactivePowerControl.class, Set.of("controllerBuses"));
        expected.put(TransformerReactivePowerControl.class, Set.of("controllerBranch", "targetDeadband"));
        expected.put(TransformerPhaseControl.class, Set.of("controllerBranch", "controlledBranch", "targetDeadband", "controlledSide", "mode", "unit"));
        expected.put(LfSecondaryVoltageControl.class, Set.of("controlUnits", "pilotBus", "targetValue", "zoneName"));
        expected.put(LfSecondaryVoltageControl.ControlUnit.class, Set.of("id", "participate", "generatorVoltageControl"));
        expected.put(LfOverloadManagementSystem.class, Set.of("monitoredBranch", "monitoredSide", "branchTrippingList"));
        expected.put(LfOverloadManagementSystem.LfBranchTripping.class, Set.of("branchToOperate", "branchOpen", "threshold"));
        expected.put(LfNetwork.class, Set.of("numCC", "synchronousNetworks", "slackBusSelector", "referenceBusSelector", "maxSlackBusCount", "busesById", "busesByIndex",
                "branches", "branchesById", "branchesByOriginalId", "shuntCount", "shuntsByIndex", "shuntsById", "generatorsById", "loadsById", "areasById", "areas", "hvdcs",
                "hvdcsById", "dcBusByIndex", "dcBusById", "dcLinesByIndex", "voltageSourceConvertersByIndex", "listeners", "validity", "connectivityFactory", "connectivity",
                "zeroImpedanceNetworksByModel", "reportNode", "secondaryVoltageControls", "voltageAngleLimits", "overloadManagementSystems", "connectivityRemovedBranches"));
        expected.put(LfSynchronousNetworkImpl.class, Set.of("lfNetwork", "numSC", "slackBusSelector", "referenceBusSelector", "maxSlackBusCount", "referenceBus", "slackBuses",
                "excludedSlackBuses", "referenceGenerator"));
        expected.put(LfNetwork.LfVoltageAngleLimit.class, Set.of("id", "from", "to", "highValue", "lowValue"));
        return expected;
    }

    @Test
    void testCopyGraphFieldInventory() {
        for (Map.Entry<Class<?>, Set<String>> entry : createFieldInventory().entrySet()) {
            Set<String> actual = Arrays.stream(entry.getKey().getDeclaredFields())
                    .filter(LfNetworkCopyFieldGuardTest::isInstanceField)
                    .map(Field::getName)
                    .collect(Collectors.toSet());
            assertEquals(entry.getValue(), actual,
                    "Field inventory of " + entry.getKey().getName() + " changed: classify the new/removed fields"
                            + " in the copy constructors used by LfNetworkCopier, then update this inventory");
        }
    }

    @Test
    void testCopyGraphClassInventory() throws IOException, URISyntaxException {
        Set<Class<?>> graphClasses = discoverCopyGraphClasses();
        Set<Class<?>> inventoriedClasses = createFieldInventory().keySet();
        assertEquals(Set.of(), namesOfClassesNotIn(graphClasses, inventoriedClasses),
                "Classes of the LfNetwork copy graph are missing from the field inventory: classify their fields in the copy"
                        + " constructors used by LfNetworkCopier, then add them to the inventory (or add their type to"
                        + " OUT_OF_GRAPH_TYPES if their instances are not part of the copy)");
        assertEquals(Set.of(), namesOfClassesNotIn(inventoriedClasses, graphClasses),
                "Classes of the field inventory are not reachable from LfNetwork anymore: remove them from the inventory");
    }

    private static Set<String> namesOfClassesNotIn(Set<Class<?>> classes, Set<Class<?>> reference) {
        return classes.stream()
                .filter(clazz -> !reference.contains(clazz))
                .map(Class::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Discovers the classes of the copy graph: starting from {@link LfNetwork}, a class of this library
     * belongs to the graph as soon as its instances can be held by an instance field of a class of the
     * graph, that is as soon as it is assignable to the declared type of the field or to one of its
     * type arguments ({@code List<LfBus>}, {@code Map<LoadFlowModel, ZeroImpedanceContext>}, ...), the
     * declared type being possibly foreign (an IIDM {@code ReactiveLimits} field holding a class of this
     * library). The superclasses of a class of the graph declare part of its fields, so they belong to
     * the graph too.
     */
    private static Set<Class<?>> discoverCopyGraphClasses() throws IOException, URISyntaxException {
        Set<Class<?>> libraryClasses = loadLibraryClasses();
        Set<Class<?>> graphClasses = new HashSet<>();
        Set<Class<?>> visitedTypes = new HashSet<>();
        Deque<Class<?>> typesToVisit = new ArrayDeque<>(List.of(LfNetwork.class));
        while (!typesToVisit.isEmpty()) {
            Class<?> declaredType = typesToVisit.pop();
            if (!visitedTypes.add(declaredType)) {
                continue;
            }
            for (Class<?> clazz : libraryClasses) {
                if (!declaredType.isAssignableFrom(clazz) || isOutOfGraph(clazz)) {
                    continue;
                }
                for (Class<?> type = clazz; libraryClasses.contains(type); type = type.getSuperclass()) {
                    if (graphClasses.add(type)) {
                        for (Field field : type.getDeclaredFields()) {
                            if (isInstanceField(field)) {
                                collectHeldTypes(field.getGenericType(), new HashSet<>(), typesToVisit);
                            }
                        }
                    }
                }
            }
        }
        return graphClasses;
    }

    /**
     * Collects the declared types of the instances a field of the given generic type can hold: the type
     * of the field itself and, for containers, the types of their elements. JDK types are containers or
     * values: only their type arguments are collected.
     */
    private static void collectHeldTypes(Type type, Set<Type> visited, Deque<Class<?>> heldTypes) {
        if (!visited.add(type)) {
            return; // recursive bound like T extends Comparable<T>
        }
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                collectHeldTypes(clazz.getComponentType(), visited, heldTypes);
            } else if (!clazz.isPrimitive() && !clazz.getName().startsWith("java.") && !isOutOfGraph(clazz)) {
                heldTypes.push(clazz);
            }
        } else if (type instanceof ParameterizedType parameterizedType) {
            if (!isOutOfGraph((Class<?>) parameterizedType.getRawType())) {
                collectHeldTypes(parameterizedType.getRawType(), visited, heldTypes);
                for (Type argument : parameterizedType.getActualTypeArguments()) {
                    collectHeldTypes(argument, visited, heldTypes);
                }
            }
        } else if (type instanceof GenericArrayType arrayType) {
            collectHeldTypes(arrayType.getGenericComponentType(), visited, heldTypes);
        } else if (type instanceof WildcardType wildcardType) {
            for (Type bound : wildcardType.getUpperBounds()) {
                collectHeldTypes(bound, visited, heldTypes);
            }
            for (Type bound : wildcardType.getLowerBounds()) {
                collectHeldTypes(bound, visited, heldTypes);
            }
        } else if (type instanceof TypeVariable<?> typeVariable) {
            for (Type bound : typeVariable.getBounds()) {
                collectHeldTypes(bound, visited, heldTypes);
            }
        }
    }

    private static boolean isOutOfGraph(Class<?> clazz) {
        return OUT_OF_GRAPH_TYPES.stream().anyMatch(type -> type.isAssignableFrom(clazz));
    }

    private static boolean isInstanceField(Field field) {
        return !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic();
    }

    /**
     * Loads, without initializing them, the main classes of this library which can have instance fields
     * (nested and anonymous classes included, interfaces and enums left out).
     */
    private static Set<Class<?>> loadLibraryClasses() throws IOException, URISyntaxException {
        Path classesDirectory = Path.of(LfNetwork.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            return files.map(file -> classesDirectory.relativize(file).toString())
                    .filter(file -> file.endsWith(".class") && !file.endsWith("-info.class"))
                    .map(file -> loadClass(file.substring(0, file.length() - ".class".length()).replace(File.separatorChar, '.')))
                    .filter(clazz -> !clazz.isInterface() && !Enum.class.isAssignableFrom(clazz))
                    .collect(Collectors.toSet());
        }
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className, false, LfNetwork.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
