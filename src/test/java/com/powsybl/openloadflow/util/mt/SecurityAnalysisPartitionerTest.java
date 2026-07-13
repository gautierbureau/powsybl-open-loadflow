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
        // a single contingency carrying many operator strategies: contingency-level parallelization is useless, so the
        // strategies must be spread over the threads (the extra network builds are worth it given the number of strategies)
        List<Contingency> contingencies = List.of(contingency("c1"));
        List<OperatorStrategy> operatorStrategies = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            operatorStrategies.add(specificStrategy("s" + i, "c1"));
        }

        List<SecurityAnalysisPartitioner.Partition> partitions = SecurityAnalysisPartitioner.partition(contingencies, operatorStrategies, 4, true);

        assertEquals(4, partitions.size());
        // the strategies are spread over several partitions
        long partitionsWithStrategies = partitions.stream().filter(p -> !p.operatorStrategies().isEmpty()).count();
        assertTrue(partitionsWithStrategies > 1, "operator strategies should be spread over several partitions");
        // every partition that runs a strategy also simulates the (single) contingency
        for (SecurityAnalysisPartitioner.Partition partition : partitions) {
            if (!partition.operatorStrategies().isEmpty()) {
                assertEquals(List.of("c1"), contingencyIds(partition));
            }
        }
        // every strategy is assigned exactly once, all covered
        assertEachStrategyAssignedOnce(operatorStrategies, partitions);
    }

    @Test
    void fewStrategiesPerContingencyAreNotSpread() {
        // a few contingencies each carrying a handful of operator strategies: contingency-level parallelization already
        // fills the threads, so spreading (which would duplicate contingencies and add network builds) must not happen
        List<Contingency> contingencies = List.of(contingency("c1"), contingency("c2"), contingency("c3"), contingency("c4"));
        List<OperatorStrategy> operatorStrategies = new ArrayList<>();
        for (Contingency contingency : contingencies) {
            for (int j = 0; j < 3; j++) {
                operatorStrategies.add(specificStrategy(contingency.getId() + "_s" + j, contingency.getId()));
            }
        }

        List<SecurityAnalysisPartitioner.Partition> partitions = SecurityAnalysisPartitioner.partition(contingencies, operatorStrategies, 4, true);

        // each contingency is simulated by exactly one partition (no duplication, no redundant post-contingency solve)
        List<String> allContingencyIds = partitions.stream().flatMap(p -> contingencyIds(p).stream()).toList();
        assertEquals(allContingencyIds.size(), new HashSet<>(allContingencyIds).size(), "no contingency should be duplicated across partitions");
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

    @Test
    void cheaperPartitionCostSpreadsMore() {
        // a single contingency with 16 operator strategies, 4 partitions: with the expensive rebuild cost the strategies
        // are not worth spreading (one partition), but with the cheaper copy cost they are spread over the partitions
        List<Contingency> contingencies = List.of(contingency("c1"));
        List<OperatorStrategy> operatorStrategies = new ArrayList<>();
        for (int j = 0; j < 16; j++) {
            operatorStrategies.add(specificStrategy("s" + j, "c1"));
        }

        List<SecurityAnalysisPartitioner.Partition> rebuild = SecurityAnalysisPartitioner.partition(contingencies,
                operatorStrategies, 4, true, SecurityAnalysisPartitioner.PARTITION_FIXED_COST_REBUILD);
        List<SecurityAnalysisPartitioner.Partition> copy = SecurityAnalysisPartitioner.partition(contingencies,
                operatorStrategies, 4, true, SecurityAnalysisPartitioner.PARTITION_FIXED_COST_COPY);

        assertEquals(1, rebuild.stream().filter(p -> !p.operatorStrategies().isEmpty()).count(), "rebuild cost keeps the strategies on one partition");
        assertTrue(copy.stream().filter(p -> !p.operatorStrategies().isEmpty()).count() > 1, "copy cost spreads the strategies over several partitions");
        assertEachStrategyAssignedOnce(operatorStrategies, copy);
    }

    @Test
    void multipleContingenciesSpreadOverDisjointPartitionBlocks() {
        // 2 contingencies, 16 operator strategies each, 4 partitions, cheap copy cost: each contingency must be spread
        // over its own disjoint block of 2 partitions (not leaked into all 4), so it is simulated by exactly 2 of them
        List<Contingency> contingencies = List.of(contingency("c1"), contingency("c2"));
        List<OperatorStrategy> operatorStrategies = new ArrayList<>();
        for (Contingency contingency : contingencies) {
            for (int j = 0; j < 16; j++) {
                operatorStrategies.add(specificStrategy(contingency.getId() + "_s" + j, contingency.getId()));
            }
        }

        List<SecurityAnalysisPartitioner.Partition> partitions = SecurityAnalysisPartitioner.partition(contingencies,
                operatorStrategies, 4, true, SecurityAnalysisPartitioner.PARTITION_FIXED_COST_COPY);

        // all 4 partitions are used, each with operator strategies
        assertEquals(4, partitions.stream().filter(p -> !p.operatorStrategies().isEmpty()).count());
        // each contingency is simulated by exactly 2 partitions (its disjoint block), not all 4
        for (String contingencyId : List.of("c1", "c2")) {
            long partitionsSimulating = partitions.stream().filter(p -> contingencyIds(p).contains(contingencyId)).count();
            assertEquals(2, partitionsSimulating, contingencyId + " should be spread over exactly 2 partitions");
        }
        // no partition simulates both contingencies (disjoint blocks)
        assertTrue(partitions.stream().allMatch(p -> contingencyIds(p).size() <= 1), "partition blocks must be disjoint");
        assertEachStrategyAssignedOnce(operatorStrategies, partitions);
    }

    @Test
    void unevenStrategyCountsAllocatePartitionsByLargestRemainder() {
        // three strategy-bearing contingencies with uneven strategy counts (20 / 3 / 1), more partitions than
        // contingencies (6): the partitions are allocated proportionally to the strategy counts (largest remainder
        // method), and the single leftover partition goes to the largest remainder (c1), giving blocks of 4 / 1 / 1.
        // The cheap copy cost makes spreading the chosen plan.
        List<Contingency> contingencies = List.of(contingency("c1"), contingency("c2"), contingency("c3"));
        List<OperatorStrategy> operatorStrategies = new ArrayList<>();
        for (int j = 0; j < 20; j++) {
            operatorStrategies.add(specificStrategy("c1_s" + j, "c1"));
        }
        for (int j = 0; j < 3; j++) {
            operatorStrategies.add(specificStrategy("c2_s" + j, "c2"));
        }
        operatorStrategies.add(specificStrategy("c3_s0", "c3"));

        List<SecurityAnalysisPartitioner.Partition> partitions = SecurityAnalysisPartitioner.partition(contingencies,
                operatorStrategies, 6, true, SecurityAnalysisPartitioner.PARTITION_FIXED_COST_COPY);

        assertEquals(6, partitions.size());
        // proportional allocation with the leftover partition given to the largest remainder (c1): c1 -> 4, c2 -> 1, c3 -> 1
        assertEquals(4, partitions.stream().filter(p -> contingencyIds(p).contains("c1")).count(), "c1 block size");
        assertEquals(1, partitions.stream().filter(p -> contingencyIds(p).contains("c2")).count(), "c2 block size");
        assertEquals(1, partitions.stream().filter(p -> contingencyIds(p).contains("c3")).count(), "c3 block size");
        assertEachStrategyAssignedOnce(operatorStrategies, partitions);
    }

    @Test
    void strategyLessContingenciesPackedIntoDistinctPartitions() {
        // one strategy-heavy contingency (spread over all partitions) plus several contingencies without operator
        // strategies (which still need a post-contingency simulation): those must be packed into the least loaded
        // partitions, one each, not all piled onto the same partition.
        List<Contingency> contingencies = List.of(contingency("c1"), contingency("c2"), contingency("c3"),
                contingency("c4"), contingency("c5"));
        List<OperatorStrategy> operatorStrategies = new ArrayList<>();
        for (int j = 0; j < 12; j++) {
            operatorStrategies.add(specificStrategy("c1_s" + j, "c1"));
        }

        List<SecurityAnalysisPartitioner.Partition> partitions = SecurityAnalysisPartitioner.partition(contingencies,
                operatorStrategies, 4, true, SecurityAnalysisPartitioner.PARTITION_FIXED_COST_COPY);

        assertEquals(4, partitions.size());
        // the heavy contingency is spread over all partitions
        assertEquals(4, partitions.stream().filter(p -> contingencyIds(p).contains("c1")).count());
        // every strategy-less contingency is simulated exactly once
        for (String contingencyId : List.of("c2", "c3", "c4", "c5")) {
            assertEquals(1, partitions.stream().filter(p -> contingencyIds(p).contains(contingencyId)).count(),
                    contingencyId + " should be simulated by exactly one partition");
        }
        // the four strategy-less contingencies are packed into distinct partitions, not piled onto a single one
        long partitionsWithStrategyLessContingency = partitions.stream()
                .filter(p -> contingencyIds(p).stream().anyMatch(id -> !id.equals("c1")))
                .count();
        assertEquals(4, partitionsWithStrategyLessContingency, "strategy-less contingencies should be spread over distinct partitions");
        assertEachStrategyAssignedOnce(operatorStrategies, partitions);
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
