/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.util.mt;

import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContextType;
import com.powsybl.contingency.strategy.OperatorStrategy;
import com.powsybl.openloadflow.util.Lists2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Splits the work of a security analysis into a fixed number of partitions, one per thread.
 *
 * <p>By default the split is done on the contingencies only: each partition owns a disjoint subset of the
 * contingencies and runs all their operator strategies (this is the historical behaviour).</p>
 *
 * <p>When operator strategy parallelization is requested, the split is instead balanced over the operator
 * strategies: the strategies of a given contingency can be spread over several partitions so that a workload
 * made of few contingencies but many operator strategies (in the extreme, a single contingency with many
 * operator strategies) is distributed over all threads. A contingency may then appear in several partitions;
 * every partition that runs at least one of its strategies re-simulates the post-contingency state, but only
 * one partition emits its post-contingency result (see {@code AbstractSecurityAnalysis} result merging).</p>
 *
 * <p>Balancing is only possible when every operator strategy targets a specific contingency
 * ({@link ContingencyContextType#SPECIFIC}). A strategy with any other contingency context applies to all
 * contingencies, which is incompatible with spreading a contingency over several partitions; in that case the
 * partitioner falls back to the contingency-only split.</p>
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class SecurityAnalysisPartitioner {

    /**
     * Fixed cost of an extra partition when its network is rebuilt from the IIDM network under a lock
     * ({@code NetworkPerThreadMode.REBUILD}), expressed in units of one post-contingency / operator strategy load flow
     * and used by {@link #estimateMakespan}. It is deliberately larger than one simulation: the serialized build plus
     * pre-contingency solve mean that opening a new partition to spread operator strategies is only worth it when it
     * removes several simulations from the critical path.
     */
    public static final double PARTITION_FIXED_COST_REBUILD = 6.0;

    /**
     * Fixed cost of an extra partition when its network is a deep copy of the network built once
     * ({@code NetworkPerThreadMode.COPY}, the default). A copy is much cheaper than a rebuild (it is lock free and, with
     * network presolving, skips the pre-contingency solve), so operator strategies are spread more aggressively.
     */
    public static final double PARTITION_FIXED_COST_COPY = 1.5;

    private SecurityAnalysisPartitioner() {
    }

    /**
     * A single partition of work: the contingencies to simulate and the operator strategies to evaluate on this thread.
     */
    public record Partition(List<Contingency> contingencies, List<OperatorStrategy> operatorStrategies) {
    }

    /**
     * @return {@code true} if the operator strategy balancing can be applied to the given operator strategies, i.e.
     * there is at least one operator strategy and all of them target a specific contingency.
     */
    public static boolean canBalanceOperatorStrategies(List<OperatorStrategy> operatorStrategies) {
        return !operatorStrategies.isEmpty()
                && operatorStrategies.stream()
                        .allMatch(os -> os.getContingencyContext().getContextType() == ContingencyContextType.SPECIFIC);
    }

    /**
     * Builds {@code partitionCount} partitions.
     *
     * @param contingencies          the contingencies of the analysis
     * @param operatorStrategies     the operator strategies of the analysis
     * @param partitionCount         the number of partitions (threads)
     * @param balanceOperatorStrategies when {@code true} and {@link #canBalanceOperatorStrategies} holds, spread the
     *                                  operator strategies over the partitions; otherwise split on the contingencies only
     */
    public static List<Partition> partition(List<Contingency> contingencies, List<OperatorStrategy> operatorStrategies,
                                            int partitionCount, boolean balanceOperatorStrategies) {
        return partition(contingencies, operatorStrategies, partitionCount, balanceOperatorStrategies, PARTITION_FIXED_COST_REBUILD);
    }

    /**
     * Builds {@code partitionCount} partitions, using {@code partitionFixedCost} (in units of one load flow) as the cost
     * of opening an extra partition when estimating which plan (spread vs one partition per contingency) is faster. Pass
     * {@link #PARTITION_FIXED_COST_COPY} in copy mode and {@link #PARTITION_FIXED_COST_REBUILD} in rebuild mode.
     */
    public static List<Partition> partition(List<Contingency> contingencies, List<OperatorStrategy> operatorStrategies,
                                            int partitionCount, boolean balanceOperatorStrategies, double partitionFixedCost) {
        Objects.requireNonNull(contingencies);
        Objects.requireNonNull(operatorStrategies);
        if (partitionCount < 1) {
            throw new IllegalArgumentException("Partition count should be > 0");
        }
        if (balanceOperatorStrategies && canBalanceOperatorStrategies(operatorStrategies)) {
            Map<String, List<OperatorStrategy>> strategiesByContingencyId = groupByContingencyId(operatorStrategies);
            // Two candidate plans: spread the operator strategies over the partitions, or keep each contingency (with
            // all its operator strategies) on a single partition. Spreading balances the operator strategy work but a
            // contingency then appears in several partitions, and each extra partition pays a network provisioning cost
            // plus a redundant post-contingency solve. We therefore keep whichever plan has the lower estimated
            // makespan, so balancing can never do worse than the plain contingency-level parallelization.
            List<Partition> spread = spreadPartition(contingencies, strategiesByContingencyId, partitionCount);
            List<Partition> perContingency = contingencyPartition(contingencies, strategiesByContingencyId, partitionCount);
            return estimateMakespan(spread, partitionFixedCost) < estimateMakespan(perContingency, partitionFixedCost) ? spread : perContingency;
        }
        // contingency-only split: each partition gets a disjoint subset of contingencies and the full list of
        // operator strategies (filtered per contingency later on by OperatorStrategies.indexByContingencyId).
        return Lists2.partition(contingencies, partitionCount).stream()
                .map(partitionContingencies -> new Partition(partitionContingencies, operatorStrategies))
                .toList();
    }

    private static Map<String, List<OperatorStrategy>> groupByContingencyId(List<OperatorStrategy> operatorStrategies) {
        // group the (specific) operator strategies by contingency id, keeping input order
        Map<String, List<OperatorStrategy>> strategiesByContingencyId = new LinkedHashMap<>();
        for (OperatorStrategy operatorStrategy : operatorStrategies) {
            strategiesByContingencyId.computeIfAbsent(operatorStrategy.getContingencyContext().getContingencyId(), k -> new ArrayList<>())
                    .add(operatorStrategy);
        }
        return strategiesByContingencyId;
    }

    /**
     * Estimated makespan of a partitioning, in "simulation" units (one unit = one post-contingency or one operator
     * strategy load flow). Each non-empty partition pays {@code partitionFixedCost} for its network provisioning (build
     * or copy) and, in rebuild mode, its pre-contingency solve; those fixed costs add up on the critical path, while
     * the per-partition simulation load runs in parallel, hence the max.
     */
    private static double estimateMakespan(List<Partition> partitions, double partitionFixedCost) {
        long usedPartitions = partitions.stream().filter(p -> !p.contingencies().isEmpty()).count();
        long maxLoad = partitions.stream()
                .mapToLong(p -> (long) p.contingencies().size() + p.operatorStrategies().size())
                .max().orElse(0);
        return usedPartitions * partitionFixedCost + maxLoad;
    }

    /**
     * Keeps each contingency (and all its operator strategies) on a single partition, bin-packing the contingencies
     * over the partitions by decreasing load. No contingency is duplicated, so there is no redundant post-contingency
     * solve. This is the operator-strategy-aware equivalent of the plain contingency-level parallelization.
     */
    private static List<Partition> contingencyPartition(List<Contingency> contingencies,
                                                        Map<String, List<OperatorStrategy>> strategiesByContingencyId, int partitionCount) {
        long[] loads = new long[partitionCount];
        List<List<Contingency>> contingenciesByBucket = new ArrayList<>(partitionCount);
        List<List<OperatorStrategy>> strategiesByBucket = new ArrayList<>(partitionCount);
        for (int i = 0; i < partitionCount; i++) {
            contingenciesByBucket.add(new ArrayList<>());
            strategiesByBucket.add(new ArrayList<>());
        }
        List<Contingency> sortedContingencies = new ArrayList<>(contingencies);
        sortedContingencies.sort((c1, c2) -> Long.compare(contingencyCost(c2, strategiesByContingencyId), contingencyCost(c1, strategiesByContingencyId)));
        for (Contingency contingency : sortedContingencies) {
            int bucket = leastLoadedBucket(loads);
            contingenciesByBucket.get(bucket).add(contingency);
            List<OperatorStrategy> strategies = strategiesByContingencyId.getOrDefault(contingency.getId(), List.of());
            strategiesByBucket.get(bucket).addAll(strategies);
            loads[bucket] += contingencyCost(contingency, strategiesByContingencyId);
        }
        List<Partition> partitions = new ArrayList<>(partitionCount);
        for (int i = 0; i < partitionCount; i++) {
            List<Contingency> bucketContingencyIds = contingenciesByBucket.get(i);
            List<Contingency> bucketContingencies = contingencies.stream()
                    .filter(bucketContingencyIds::contains)
                    .toList();
            partitions.add(new Partition(bucketContingencies, strategiesByBucket.get(i)));
        }
        return partitions;
    }

    private static List<Partition> spreadPartition(List<Contingency> contingencies,
                                                   Map<String, List<OperatorStrategy>> strategiesByContingencyId, int partitionCount) {
        long[] loads = new long[partitionCount];
        List<Set<String>> contingencyIdsByBucket = new ArrayList<>(partitionCount);
        List<List<OperatorStrategy>> strategiesByBucket = new ArrayList<>(partitionCount);
        for (int i = 0; i < partitionCount; i++) {
            contingencyIdsByBucket.add(new TreeSet<>());
            strategiesByBucket.add(new ArrayList<>());
        }

        // heaviest contingencies first (cost = one post-contingency simulation + one simulation per operator strategy)
        List<Contingency> sortedContingencies = new ArrayList<>(contingencies);
        sortedContingencies.sort((c1, c2) -> Long.compare(contingencyCost(c2, strategiesByContingencyId), contingencyCost(c1, strategiesByContingencyId)));

        for (Contingency contingency : sortedContingencies) {
            String contingencyId = contingency.getId();
            // ensure the contingency is owned by at least one (the least loaded) bucket, even without any strategy
            addContingencyToBucket(leastLoadedBucket(loads), contingencyId, loads, contingencyIdsByBucket);
            // then assign each of its strategies to the bucket that minimizes the resulting load
            for (OperatorStrategy operatorStrategy : strategiesByContingencyId.getOrDefault(contingencyId, List.of())) {
                int bucket = bestBucketForStrategy(contingencyId, loads, contingencyIdsByBucket);
                addContingencyToBucket(bucket, contingencyId, loads, contingencyIdsByBucket);
                strategiesByBucket.get(bucket).add(operatorStrategy);
                loads[bucket]++;
            }
        }

        // build the partitions, keeping contingencies in their original order within each bucket
        List<Partition> partitions = new ArrayList<>(partitionCount);
        for (int i = 0; i < partitionCount; i++) {
            Set<String> bucketContingencyIds = contingencyIdsByBucket.get(i);
            List<Contingency> bucketContingencies = contingencies.stream()
                    .filter(c -> bucketContingencyIds.contains(c.getId()))
                    .toList();
            partitions.add(new Partition(bucketContingencies, strategiesByBucket.get(i)));
        }
        return partitions;
    }

    private static long contingencyCost(Contingency contingency, Map<String, List<OperatorStrategy>> strategiesByContingencyId) {
        return 1L + strategiesByContingencyId.getOrDefault(contingency.getId(), List.of()).size();
    }

    private static void addContingencyToBucket(int bucket, String contingencyId, long[] loads, List<Set<String>> contingencyIdsByBucket) {
        if (contingencyIdsByBucket.get(bucket).add(contingencyId)) {
            // first time this bucket sees the contingency: it will have to run its post-contingency simulation
            loads[bucket]++;
        }
    }

    private static int leastLoadedBucket(long[] loads) {
        int best = 0;
        for (int i = 1; i < loads.length; i++) {
            if (loads[i] < loads[best]) {
                best = i;
            }
        }
        return best;
    }

    private static int bestBucketForStrategy(String contingencyId, long[] loads, List<Set<String>> contingencyIdsByBucket) {
        int best = 0;
        long bestCost = Long.MAX_VALUE;
        for (int i = 0; i < loads.length; i++) {
            // running the strategy costs one simulation, plus one post-contingency simulation if the bucket does not
            // already simulate this contingency
            long cost = loads[i] + (contingencyIdsByBucket.get(i).contains(contingencyId) ? 1 : 2);
            if (cost < bestCost) {
                bestCost = cost;
                best = i;
            }
        }
        return best;
    }
}
