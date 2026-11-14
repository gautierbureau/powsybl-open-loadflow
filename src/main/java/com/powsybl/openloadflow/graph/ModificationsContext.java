/**
 * Copyright (c) 2022-2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.graph;

import com.powsybl.openloadflow.graph.dtree.AbstractSetView;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Florian Dupuy {@literal <florian.dupuy at rte-france.com>}
 */
public class ModificationsContext<V, E> {

    private final Deque<GraphModification<V, E>> modifications = new ArrayDeque<>();
    private final Function<V, Set<V>> verticesNotInMainComponentGetter;
    private Set<V> verticesNotInMainComponentBefore;
    private Set<V> verticesAddedToMainComponent;
    private Set<V> verticesRemovedFromMainComponent;
    private Set<E> edgesAddedToMainComponent;
    private Set<E> edgesRemovedFromMainComponent;
    private Map<E, AbstractEdgeModification<V, E>> edgeFirstModificationMap;
    private V mainComponentVertex;

    /** When not null, the comparisons are computed from the components the modifications touch (see {@link MainComponentProbe}),
     *  and no snapshot of the vertices outside the main component is taken when the context starts. */
    private final MainComponentProbe<V, E> probe;
    /** The main component vertex when the context started: the "before" of the comparisons is relative to it. */
    private final V initialMainComponentVertex;
    /** The main component membership before the modifications of the vertices the local comparisons looked at. */
    private Map<V, Boolean> inMainComponentBefore;

    public ModificationsContext(Function<V, Set<V>> verticesNotInMainComponentGetter, V mainComponentVertex) {
        this(verticesNotInMainComponentGetter, mainComponentVertex, null);
    }

    public ModificationsContext(Function<V, Set<V>> verticesNotInMainComponentGetter, V mainComponentVertex, MainComponentProbe<V, E> probe) {
        this.mainComponentVertex = mainComponentVertex;
        this.verticesNotInMainComponentGetter = verticesNotInMainComponentGetter;
        // the main component is the vertex's: the biggest one (null vertex) may change with the modifications anywhere
        this.probe = mainComponentVertex != null ? probe : null;
        this.initialMainComponentVertex = mainComponentVertex;
    }

    public void computeVerticesNotInMainComponentBefore() {
        if (probe != null) {
            return;
        }
        this.verticesNotInMainComponentBefore = AbstractSetView.copyIfNeeded(verticesNotInMainComponentGetter.apply(mainComponentVertex));
    }

    public void add(GraphModification<V, E> graphModification) {
        invalidateComparisons();
        modifications.add(graphModification);
    }

    private void invalidateComparisons() {
        verticesAddedToMainComponent = null;
        edgesAddedToMainComponent = null;
        verticesRemovedFromMainComponent = null;
        edgesRemovedFromMainComponent = null;
        edgeFirstModificationMap = null;
        inMainComponentBefore = null;
    }

    public Deque<GraphModification<V, E>> getModifications() {
        return modifications;
    }

    public Set<E> getEdgesRemovedFromMainComponent(GraphModel<V, E> graph) {
        if (edgesRemovedFromMainComponent == null) {
            edgesRemovedFromMainComponent = computeEdgesRemovedFromMainComponent(graph);
        }
        return edgesRemovedFromMainComponent;
    }

    public Set<V> getVerticesRemovedFromMainComponent() {
        if (verticesRemovedFromMainComponent == null && localComparisons()) {
            computeLocalComparisons();
        }
        if (verticesRemovedFromMainComponent == null) {
            Set<V> result = new HashSet<>(getVerticesNotInMainComponentAfter());
            result.removeAll(getVerticesNotInMainComponentBefore());

            if (!result.isEmpty()) {
                // remove vertices added in between
                // note that there is no VertexRemove modification, thus we do not need to check if vertex is in the graph in the end
                getAddedVertexStream().forEach(result::remove);
            }
            verticesRemovedFromMainComponent = result;
        }
        return verticesRemovedFromMainComponent;
    }

    public Set<E> getEdgesAddedToMainComponent(GraphModel<V, E> graph) {
        if (edgesAddedToMainComponent == null) {
            edgesAddedToMainComponent = computeEdgesAddedToMainComponent(graph);
        }
        return edgesAddedToMainComponent;
    }

    public Set<V> getVerticesAddedToMainComponent() {
        if (verticesAddedToMainComponent == null && localComparisons()) {
            computeLocalComparisons();
        }
        if (verticesAddedToMainComponent == null) {
            Set<V> result = new HashSet<>(getVerticesNotInMainComponentBefore());
            Set<V> verticesNotInMainComponentAfter = getVerticesNotInMainComponentAfter();
            result.removeAll(verticesNotInMainComponentAfter);

            // add vertices added to main component in between
            // note that there is no VertexRemove modification, thus we do not need to check if vertex is in the graph before / in the end
            getAddedVertexStream().filter(addedVertex -> !verticesNotInMainComponentAfter.contains(addedVertex)).forEach(result::add);
            verticesAddedToMainComponent = result;
        }
        return verticesAddedToMainComponent;
    }

    private Set<V> getVerticesNotInMainComponentAfter() {
        return verticesNotInMainComponentGetter.apply(mainComponentVertex);
    }

    private Set<E> computeEdgesRemovedFromMainComponent(GraphModel<V, E> graph) {
        Set<V> verticesRemoved = getVerticesRemovedFromMainComponent();
        Set<E> result = verticesRemoved.stream().map(graph::getNeighborEdgesOf).flatMap(Set::stream).collect(Collectors.toSet());

        // We need to look in modifications to adjust the computation of the edges above, indeed:
        //  - result contains the edges which were added in the small components
        //  - result is missing the edges removed in main component with an EdgeRemove modification

        computeEdgeFirstModificationMap();

        // Remove the new edges
        modifications.stream().filter(EdgeAdd.class::isInstance).map(m -> ((EdgeAdd<V, E>) m).e)
                .filter(graph::containsEdge) // the edge is in the graph: it was not removed afterwards
                .filter(edgeAdded -> !graphContainedEdgeBefore(edgeAdded)) // the edge did not exist in the graph before the modifications
                .forEach(result::remove);

        // Add edges explicitly removed (with an EdgeRemove modification)
        modifications.stream().filter(EdgeRemove.class::isInstance).map(m -> ((EdgeRemove<V, E>) m).e)
                .filter(edgeRemoved -> !graph.containsEdge(edgeRemoved)) // the edge was not added afterwards
                .filter(this::graphContainedEdgeBefore) // the edge was in the graph before the modifications
                .filter(edgeRemoved -> isInMainComponentBefore((edgeFirstModificationMap.get(edgeRemoved)).v1)) // one of the original vertices of the edge was in the main component
                .forEach(result::add);

        return result;
    }

    private Set<E> computeEdgesAddedToMainComponent(GraphModel<V, E> graph) {
        Set<E> result = getVerticesAddedToMainComponent().stream().map(graph::getNeighborEdgesOf).flatMap(Set::stream).collect(Collectors.toSet());

        // We need to look in modifications to adjust the computation of the edges above
        // Indeed result is missing the edges added in main component with an EdgeAdd modification

        computeEdgeFirstModificationMap();

        // Add edges added to main component in between
        Set<V> verticesNotInMainComponentAfter = getVerticesNotInMainComponentAfter();
        modifications.stream().filter(EdgeAdd.class::isInstance).map(m -> ((EdgeAdd<V, E>) m).e)
                .filter(graph::containsEdge) // the edge is in the graph: it was not removed afterwards
                .filter(edgeAdded -> !graphContainedEdgeBefore(edgeAdded)) // the edge did not exist in the graph before the modifications
                .filter(edgeAdded -> !verticesNotInMainComponentAfter.contains(graph.getEdgeSource(edgeAdded))) // one of the final vertices of the edge is in the main component
                .forEach(result::add);

        return result;
    }

    private void computeEdgeFirstModificationMap() {
        if (edgeFirstModificationMap == null) {
            edgeFirstModificationMap = modifications.stream()
                    .filter(AbstractEdgeModification.class::isInstance)
                    .map(m -> (AbstractEdgeModification<V, E>) m)
                    .collect(Collectors.toMap(m -> m.e, m -> m, (m1, m2) -> m1));
        }
    }

    private boolean graphContainedEdgeBefore(E edge) {
        // If first modification is a EdgeRemove, knowing that the non-effective modifications are not added in the queue,
        // we can conclude that the graph contained the edge before the modifications were applied
        return edgeFirstModificationMap.get(edge) instanceof EdgeRemove;
    }

    private Stream<V> getAddedVertexStream() {
        return modifications.stream().filter(VertexAdd.class::isInstance).map(m -> ((VertexAdd<V, E>) m).v);
    }

    public void setMainComponentVertex(V mainComponentVertex) {
        invalidateComparisons();
        this.mainComponentVertex = mainComponentVertex;
    }

    public boolean isInMainComponentBefore(V vertex) {
        if (probe == null) {
            return !verticesNotInMainComponentBefore.contains(vertex);
        }
        if (modifications.isEmpty()) {
            return probe.isInMainComponent(vertex, initialMainComponentVertex);
        }
        if (localComparisons()) {
            computeLocalComparisons();
            Boolean before = inMainComponentBefore.get(vertex);
            if (before != null) {
                return before;
            }
        }
        probe.revert(modifications);
        try {
            return probe.containsVertex(vertex) && probe.isInMainComponent(vertex, initialMainComponentVertex);
        } finally {
            probe.reapply(modifications);
        }
    }

    /** The comparisons are local while the main component vertex is the one the context started with. */
    private boolean localComparisons() {
        return probe != null && Objects.equals(mainComponentVertex, initialMainComponentVertex);
    }

    /** The snapshot of the vertices out of the main component before the modifications: taken when the context started,
     *  or, with a probe whose main component vertex changed since, on the graph with the modifications reverted. */
    private Set<V> getVerticesNotInMainComponentBefore() {
        if (verticesNotInMainComponentBefore == null) {
            probe.revert(modifications);
            try {
                verticesNotInMainComponentBefore = AbstractSetView.copyIfNeeded(verticesNotInMainComponentGetter.apply(initialMainComponentVertex));
            } finally {
                probe.reapply(modifications);
            }
        }
        return verticesNotInMainComponentBefore;
    }

    /**
     * The vertices removed from and added to the main component, from the components the modifications touch: a
     * component no modification touches is the same before and after, in the main component or out of it both times.
     * So a vertex removed is out of the main component after, in a component holding a touched vertex, and was in
     * it before; a vertex added was out of it before, in a component holding a touched vertex, and is in it after.
     * The before side is read on the graph with the modifications reverted (then applied again): the cost is that of
     * the modifications and of the components they touch, not of every vertex out of the main component.
     */
    private void computeLocalComparisons() {
        if (inMainComponentBefore != null) {
            return;
        }
        Set<V> addedVertices = getAddedVertexStream().collect(Collectors.toCollection(HashSet::new));
        Set<V> touched = new LinkedHashSet<>();
        for (GraphModification<V, E> m : modifications) {
            if (m instanceof AbstractEdgeModification<V, E> edgeModification) {
                touched.add(edgeModification.v1);
                touched.add(edgeModification.v2);
            } else if (m instanceof VertexAdd<V, E> vertexAdd) {
                touched.add(vertexAdd.v);
            }
        }
        Set<V> outAfter = new HashSet<>();
        for (V v : touched) {
            if (!outAfter.contains(v) && probe.containsVertex(v) && !probe.isInMainComponent(v, mainComponentVertex)) {
                outAfter.addAll(probe.copyComponent(v));
            }
        }
        Map<V, Boolean> before = new HashMap<>();
        Set<V> outBefore = new HashSet<>();
        probe.revert(modifications);
        try {
            for (V v : touched) {
                if (!addedVertices.contains(v) && probe.containsVertex(v)) {
                    boolean in = probe.isInMainComponent(v, mainComponentVertex);
                    before.put(v, in);
                    if (!in && !outBefore.contains(v)) {
                        outBefore.addAll(probe.copyComponent(v));
                    }
                }
            }
            for (V v : outAfter) {
                if (!addedVertices.contains(v) && !before.containsKey(v)) {
                    before.put(v, probe.isInMainComponent(v, mainComponentVertex));
                }
            }
        } finally {
            probe.reapply(modifications);
        }
        Set<V> removed = new HashSet<>();
        for (V v : outAfter) {
            if (Boolean.TRUE.equals(before.get(v))) {
                removed.add(v);
            }
        }
        Set<V> added = new HashSet<>();
        for (V v : outBefore) {
            if (probe.isInMainComponent(v, mainComponentVertex)) {
                added.add(v);
            }
        }
        for (V v : addedVertices) {
            if (probe.isInMainComponent(v, mainComponentVertex)) {
                added.add(v);
            }
        }
        inMainComponentBefore = before;
        verticesRemovedFromMainComponent = removed;
        verticesAddedToMainComponent = added;
    }

}
