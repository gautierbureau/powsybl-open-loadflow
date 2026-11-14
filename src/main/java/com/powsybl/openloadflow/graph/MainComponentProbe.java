/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.graph;

import java.util.Deque;
import java.util.Set;

/**
 * Main component questions on a connectivity's current graph, and the reverting / re-applying of a temporary changes
 * context's modifications on it: what lets a {@link ModificationsContext} compare the main component before and after
 * its modifications from the components they touch, instead of from a snapshot of every vertex outside the main
 * component taken when the context starts.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public interface MainComponentProbe<V, E> {

    boolean containsVertex(V vertex);

    /** Whether the vertex is in the main component: the one of {@code mainComponentVertex}, or the biggest when null. */
    boolean isInMainComponent(V vertex, V mainComponentVertex);

    /** A copy of the vertex's connected component. */
    Set<V> copyComponent(V vertex);

    /** The graph as before these modifications (undone in reverse order). */
    void revert(Deque<GraphModification<V, E>> modifications);

    /** The modifications applied again, in order, after {@link #revert}. */
    void reapply(Deque<GraphModification<V, E>> modifications);
}
