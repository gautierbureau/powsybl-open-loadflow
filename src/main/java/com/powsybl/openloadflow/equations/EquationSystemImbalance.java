/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.openloadflow.network.ElementType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Describes why an equation system is not square, i.e. why a variable is left without the equation determining it.
 *
 * <p>Reported both when the invariant fails at solve time (see {@link EquationSystemNotSquareException}) and when it is
 * detected up front, so that a log line is enough to identify the culprit: the counts of variables and active equations
 * per type point at the kind of control involved, and the elements whose variable and active equation counts differ
 * point at the exact bus or branch, which a count per type cannot do on a large network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public final class EquationSystemImbalance {

    /** Maximum number of imbalanced elements described, to keep a log line readable on a large network. */
    private static final int MAX_DESCRIBED_ELEMENTS = 5;

    /**
     * An element carrying more (or fewer) variables than the active equations determining them.
     */
    public record ImbalancedElement(ElementType elementType, int elementNum, int variableCount, int equationCount) {
        @Override
        public String toString() {
            return elementType + " " + elementNum + " (" + variableCount + " variables, " + equationCount + " active equations)";
        }
    }

    private EquationSystemImbalance() {
    }

    /**
     * The elements whose variable count differs from the count of the active equations determining them. Variables and
     * equations are both indexed by element, and each element's variables are determined by as many equations, so an
     * element that does not balance is where the modeling left a variable orphaned.
     */
    public static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> List<ImbalancedElement> findImbalancedElements(EquationSystem<V, E> equationSystem) {
        Map<ElementKey, int[]> countsByElement = new LinkedHashMap<>();
        for (Variable<V> variable : equationSystem.getIndex().getSortedVariablesToFind()) {
            countsByElement.computeIfAbsent(new ElementKey(variable.getType().getElementType(), variable.getElementNum()),
                    k -> new int[2])[0]++;
        }
        for (var equation : equationSystem.getIndex().getSortedSingleEquationsToSolve()) {
            countsByElement.computeIfAbsent(new ElementKey(equation.getType().getElementType(), equation.getElementNum()),
                    k -> new int[2])[1]++;
        }
        for (var equationArray : equationSystem.getIndex().getSortedEquationArraysToSolve()) {
            ElementType elementType = equationArray.getType().getElementType();
            for (int elementNum = 0; elementNum < equationArray.getElementCount(); elementNum++) {
                if (equationArray.isElementActive(elementNum)) {
                    countsByElement.computeIfAbsent(new ElementKey(elementType, elementNum), k -> new int[2])[1]++;
                }
            }
        }
        List<ImbalancedElement> imbalancedElements = new ArrayList<>();
        for (Map.Entry<ElementKey, int[]> entry : countsByElement.entrySet()) {
            int[] counts = entry.getValue();
            if (counts[0] != counts[1]) {
                imbalancedElements.add(new ImbalancedElement(entry.getKey().elementType(), entry.getKey().elementNum(),
                        counts[0], counts[1]));
            }
        }
        return imbalancedElements;
    }

    /**
     * Describe the imbalance: the counts of variables and of active equations by type, and the elements that do not
     * balance (truncated, with the total count, to keep the description readable).
     */
    public static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> String describe(EquationSystem<V, E> equationSystem) {
        StringBuilder description = new StringBuilder(describeTypes(equationSystem));
        List<ImbalancedElement> imbalancedElements = findImbalancedElements(equationSystem);
        if (!imbalancedElements.isEmpty()) {
            description.append(", imbalanced elements (").append(imbalancedElements.size()).append(")=")
                    .append(imbalancedElements.subList(0, Math.min(MAX_DESCRIBED_ELEMENTS, imbalancedElements.size())));
            if (imbalancedElements.size() > MAX_DESCRIBED_ELEMENTS) {
                description.append(", ...");
            }
        }
        return description.toString();
    }

    /**
     * The equations an element carries, with whether each is active, e.g. {@code [BUS_TARGET_Q(inactive),
     * DISTR_Q(inactive)]}. This is what tells apart the two ways a variable ends up orphaned: no equation of the
     * expected kind was ever created for the element, or one exists but every alternative of it was left deactivated -
     * the case a control whose controller set was reconfigured produces.
     */
    public static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> String describeElementEquations(EquationSystem<V, E> equationSystem,
                                                                                                              ElementType elementType,
                                                                                                              int elementNum) {
        List<String> equations = new ArrayList<>();
        for (var equation : equationSystem.getEquations(elementType, elementNum)) {
            equations.add(equation.getType() + (equation.isActive() ? "(active)" : "(inactive)"));
        }
        for (var equationArray : equationSystem.getEquationArrays()) {
            if (equationArray.getType().getElementType() == elementType && elementNum < equationArray.getElementCount()) {
                equations.add(equationArray.getType()
                        + (equationArray.isElementActive(elementNum) ? "(active)" : "(inactive)"));
            }
        }
        return equations.toString();
    }

    /**
     * Describe the imbalance by the counts of variables and of active equations per type only, for callers that name
     * the imbalanced elements themselves (a caller holding the network can resolve their ids, which is more useful).
     */
    public static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> String describeTypes(EquationSystem<V, E> equationSystem) {
        Map<String, Integer> variablesByType = new TreeMap<>();
        for (Variable<V> variable : equationSystem.getIndex().getSortedVariablesToFind()) {
            variablesByType.merge(variable.getType().toString(), 1, Integer::sum);
        }
        Map<String, Integer> equationsByType = new TreeMap<>();
        for (var equation : equationSystem.getIndex().getSortedSingleEquationsToSolve()) {
            equationsByType.merge(equation.getType().toString(), 1, Integer::sum);
        }
        for (var equationArray : equationSystem.getIndex().getSortedEquationArraysToSolve()) {
            equationsByType.merge(equationArray.getType().toString(), equationArray.getLength(), Integer::sum);
        }
        return "variables by type=" + variablesByType + ", active equations by type=" + equationsByType;
    }

    private record ElementKey(ElementType elementType, int elementNum) {
    }
}
