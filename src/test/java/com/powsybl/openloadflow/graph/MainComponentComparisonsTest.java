/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.graph;

import com.powsybl.openloadflow.graph.dtree.DTreeGraphConnectivity;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The DTree's main component comparisons, computed from the components the modifications touch, against the naive
 * connectivity's (from the snapshot of every vertex out of the main component): random graphs with many components,
 * random edge removals, additions, re-additions and vertex additions, nested temporary changes, queries interleaved.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class MainComponentComparisonsTest {

    private static final int VERTICES = 40;

    @Test
    void localComparisonsEqualTheSnapshotOnes() {
        for (int seed = 0; seed < 300; seed++) {
            Random random = new Random(seed);
            GraphConnectivity<Integer, String> naive = new NaiveGraphConnectivity<>(v -> v);
            GraphConnectivity<Integer, String> dtree = new DTreeGraphConnectivity<>();
            List<GraphConnectivity<Integer, String>> both = List.of(naive, dtree);
            Map<String, int[]> edges = new LinkedHashMap<>();
            for (int v = 0; v < VERTICES; v++) {
                int vertex = v;
                both.forEach(c -> c.addVertex(vertex));
            }
            // a sparse graph: a main part and many small ones (as the dead bays of a network with every breaker retained)
            for (int k = 0; k < VERTICES + random.nextInt(VERTICES); k++) {
                int v1 = random.nextInt(VERTICES);
                int v2 = random.nextInt(v1 < VERTICES / 2 ? VERTICES / 2 : VERTICES);
                if (v1 != v2) {
                    addEdge(both, edges, v1, v2, "e" + k);
                }
            }
            both.forEach(c -> c.setMainComponentVertex(0));
            int nextVertex = VERTICES;
            int nextEdge = 0;
            for (int depth = 0; depth < 1 + random.nextInt(2); depth++) {
                both.forEach(GraphConnectivity::startTemporaryChanges);
                for (int step = 0; step < 1 + random.nextInt(8); step++) {
                    int op = random.nextInt(4);
                    List<String> present = new ArrayList<>(edges.keySet());
                    if (op <= 1 && !present.isEmpty()) {
                        String e = present.get(random.nextInt(present.size()));
                        both.forEach(c -> c.removeEdge(e));
                        edges.remove(e);
                    } else if (op == 2) {
                        int v1 = random.nextInt(nextVertex);
                        int v2 = random.nextInt(nextVertex);
                        if (v1 != v2) {
                            addEdge(both, edges, v1, v2, "n" + nextEdge++);
                        }
                    } else {
                        int vertex = nextVertex++;
                        both.forEach(c -> c.addVertex(vertex));
                        int other = random.nextInt(vertex);
                        if (random.nextBoolean()) {
                            addEdge(both, edges, vertex, other, "n" + nextEdge++);
                        }
                    }
                    if (random.nextInt(3) == 0) {
                        assertSameComparisons(naive, dtree, seed);    // a query, then more modifications
                    }
                    if (random.nextInt(6) == 0) {
                        // another main component vertex, one that was in the main component before (else both refuse it)
                        int vertex = random.nextInt(VERTICES);
                        boolean accepted;
                        try {
                            naive.setMainComponentVertex(vertex);
                            accepted = true;
                        } catch (com.powsybl.commons.PowsyblException e) {
                            accepted = false;
                        }
                        try {
                            dtree.setMainComponentVertex(vertex);   // the same call (a refusal still sets the context's vertex here)
                        } catch (com.powsybl.commons.PowsyblException e) {
                            assertEquals(false, accepted, "seed " + seed);
                        }
                    }
                }
                assertSameComparisons(naive, dtree, seed);
            }
        }
    }

    private static void addEdge(List<GraphConnectivity<Integer, String>> both, Map<String, int[]> edges, int v1, int v2, String e) {
        both.forEach(c -> c.addEdge(v1, v2, e));
        edges.put(e, new int[] {v1, v2});
    }

    private static void assertSameComparisons(GraphConnectivity<Integer, String> naive, GraphConnectivity<Integer, String> dtree, int seed) {
        String context = "seed " + seed;
        assertEquals(new HashSet<>(naive.getVerticesRemovedFromMainComponent()), new HashSet<>(dtree.getVerticesRemovedFromMainComponent()), context);
        assertEquals(new HashSet<>(naive.getVerticesAddedToMainComponent()), new HashSet<>(dtree.getVerticesAddedToMainComponent()), context);
        assertEquals(new HashSet<>(naive.getEdgesRemovedFromMainComponent()), new HashSet<>(dtree.getEdgesRemovedFromMainComponent()), context);
        assertEquals(new HashSet<>(naive.getEdgesAddedToMainComponent()), new HashSet<>(dtree.getEdgesAddedToMainComponent()), context);
    }
}
