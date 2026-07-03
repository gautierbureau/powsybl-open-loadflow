/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.util.mt;

import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.strategy.OperatorStrategy;
import com.powsybl.contingency.strategy.condition.TrueCondition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class SecurityAnalysisPartitionerTest {

    private static Contingency contingency(String id) {
        return new Contingency(id, List.of());
    }

    private static OperatorStrategy specificStrategy(String id, String contingencyId) {
        return new OperatorStrategy(id, ContingencyContext.specificContingency(contingencyId), new TrueCondition(), List.of(id + "_action"));
    }

    @Test
    void contingencyOnlySplitWhenBalancingDisabled() {
        List<Contingency> contingencies = List.of(contingency("c1"), contingency("c2"), contingency("c3"), contingency("c4"));
        List<OperatorStrategy> operatorStrategies = List.of(specificStrategy("s1", "c1"), specificStrategy("s2", "c2"));

        List<SecurityAnalysisPartitioner.Partition> partitions = SecurityAnalysisPartitioner.partition(contingencies, operatorStrategies, 2, false);

        assertEquals(2, partitions.size());
        // disjoint contingencies, no duplication
        assertEquals(List.of("c1", "c2"), contingencyIds(partitions.get(0)));
        assertEquals(List.of("c3", "c4"), contingencyIds(partitions.get(1)));
        // each partition receives the full list of operator strategies (filtered later per contingency)
        assertEquals(operatorStrategies, partitions.get(0).operatorStrategies());
        assertEquals(operatorStrategies, partitions.get(1).operatorStrategies());
    }

    @Test
    void canBalanceOnlyWithSpecificStrategies() {
        assertFalse(SecurityAnalysisPartitioner.canBalanceOperatorStrategies(List.of()));
        assertTrue(SecurityAnalysisPartitioner.canBalanceOperatorStrategies(List.of(specificStrategy("s1", "c1"))));
        OperatorStrategy allContingencies = new OperatorStrategy("sAll", ContingencyContext.all(), new TrueCondition(), List.of("a"));
        assertFalse(SecurityAnalysisPartitioner.canBalanceOperatorStrategies(List.of(specificStrategy("s1", "c1"), allContingencies)));
    }

    @Test
    void fallBackToContingencySplitWhenNonSpecificStrategy() {
        List<Contingency> contingencies = List.of(contingency("c1"), contingency("c2"));
        OperatorStrategy allContingencies = new OperatorStrategy("sAll", ContingencyContext.all(), new TrueCondition(), List.of("a"));
        List<OperatorStrategy> operatorStrategies = List.of(specificStrategy("s1", "c1"), allContingencies);

        List<SecurityAnalysisPartitioner.Partition> partitions = SecurityAnalysisPartitioner.partition(contingencies, operatorStrategies, 2, true);

        // one contingency per partition, full strategy list each (historical behaviour)
        assertEquals(List.of("c1"), contingencyIds(partitions.get(0)));
        assertEquals(List.of("c2"), contingencyIds(partitions.get(1)));
        assertEquals(operatorStrategies, partitions.get(0).operatorStrategies());
        assertEquals(operatorStrategies, partitions.get(1).operatorStrategies());
    }

    @Test
    void singleContingencyManyStrategiesSpreadOverThreads() {
        List<Contingency> contingencies = List.of(contingency("c1"));
        List<OperatorStrategy> operatorStrategies = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            operatorStrategies.add(specificStrategy("s" + i, "c1"));
        }

        List<SecurityAnalysisPartitioner.Partition> partitions = SecurityAnalysisPartitioner.partition(contingencies, operatorStrategies, 4, true);

        assertEquals(4, partitions.size());
        // the single contingency is simulated by every partition that runs one of its strategies
        for (SecurityAnalysisPartitioner.Partition partition : partitions) {
            assertEquals(List.of("c1"), contingencyIds(partition));
            assertEquals(1, partition.operatorStrategies().size());
        }
        // every strategy is assigned exactly once, all covered
        assertEachStrategyAssignedOnce(operatorStrategies, partitions);
    }

    @Test
    void balancingKeepsCoverageAndNoDuplication() {
        List<Contingency> contingencies = List.of(contingency("c1"), contingency("c2"), contingency("c3"));
        // c1 is strategy-heavy, c2 has one strategy, c3 has none
        List<OperatorStrategy> operatorStrategies = List.of(
                specificStrategy("s1a", "c1"), specificStrategy("s1b", "c1"), specificStrategy("s1c", "c1"),
                specificStrategy("s2", "c2"));

        List<SecurityAnalysisPartitioner.Partition> partitions = SecurityAnalysisPartitioner.partition(contingencies, operatorStrategies, 3, true);

        assertEquals(3, partitions.size());
        // every contingency is simulated by at least one partition (including the strategy-less c3)
        Set<String> coveredContingencies = new HashSet<>();
        partitions.forEach(p -> coveredContingencies.addAll(contingencyIds(p)));
        assertEquals(Set.of("c1", "c2", "c3"), coveredContingencies);
        // every strategy assigned exactly once
        assertEachStrategyAssignedOnce(operatorStrategies, partitions);
        // a partition that runs a strategy of a contingency must also simulate that contingency
        for (SecurityAnalysisPartitioner.Partition p : partitions) {
            Set<String> partitionContingencyIds = new HashSet<>(contingencyIds(p));
            for (OperatorStrategy os : p.operatorStrategies()) {
                assertTrue(partitionContingencyIds.contains(os.getContingencyContext().getContingencyId()));
            }
        }
    }

    private static List<String> contingencyIds(SecurityAnalysisPartitioner.Partition partition) {
        return partition.contingencies().stream().map(Contingency::getId).toList();
    }

    private static void assertEachStrategyAssignedOnce(List<OperatorStrategy> operatorStrategies, List<SecurityAnalysisPartitioner.Partition> partitions) {
        List<String> assigned = partitions.stream()
                .flatMap(p -> p.operatorStrategies().stream())
                .map(OperatorStrategy::getId)
                .toList();
        assertEquals(operatorStrategies.size(), assigned.size());
        assertEquals(operatorStrategies.stream().map(OperatorStrategy::getId).collect(java.util.stream.Collectors.toSet()), new HashSet<>(assigned));
    }
}
