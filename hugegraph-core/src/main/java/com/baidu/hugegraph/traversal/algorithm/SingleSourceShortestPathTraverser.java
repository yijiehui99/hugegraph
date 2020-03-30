/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.traversal.algorithm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.tinkerpop.gremlin.structure.Edge;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.query.QueryResults;
import com.baidu.hugegraph.structure.HugeEdge;
import com.baidu.hugegraph.type.define.Directions;
import com.baidu.hugegraph.util.E;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class SingleSourceShortestPathTraverser extends HugeTraverser {

    public SingleSourceShortestPathTraverser(HugeGraph graph) {
        super(graph);
    }

    public ShortestPaths singleSourceShortestPaths(Id sourceV, Directions dir,
                                                   String label, String weight,
                                                   long degree, long skipDegree,
                                                   long capacity, long limit) {
        E.checkNotNull(sourceV, "source vertex id");
        E.checkNotNull(dir, "direction");
        checkDegree(degree);
        checkCapacity(capacity);
        checkSkipDegree(skipDegree, degree, capacity);
        checkLimit(limit);

        Id labelId = this.getEdgeLabelId(label);
        Traverser traverser = new Traverser(sourceV, dir, labelId, weight,
                                            degree, skipDegree, capacity,
                                            limit);
        while (true) {
            // Found, reach max depth or reach capacity, stop searching
            traverser.forward();
            if (traverser.done()) {
                return traverser.shortestPaths();
            }
            checkCapacity(traverser.capacity, traverser.size, "shortest path");
        }
    }

    public NodeWithWeight weightedShortestPath(Id sourceV, Id targetV,
                                               Directions dir, String label,
                                               String weight, long degree,
                                               long skipDegree, long capacity) {
        E.checkNotNull(sourceV, "source vertex id");
        E.checkNotNull(dir, "direction");
        checkDegree(degree);
        checkCapacity(capacity);
        checkSkipDegree(skipDegree, degree, capacity);

        Id labelId = this.getEdgeLabelId(label);
        Traverser traverser = new Traverser(sourceV, dir, labelId, weight,
                                            degree, skipDegree, capacity,
                                            NO_LIMIT);
        while (true) {
            traverser.forward();
            Map<Id, NodeWithWeight> results = traverser.shortestPaths();
            if (results.containsKey(targetV) || traverser.done()) {
                return results.get(targetV);
            }
            checkCapacity(traverser.capacity, traverser.size, "shortest path");
        }
    }

    private static void checkSkipDegree(long skipDegree, long degree,
                                        long capacity) {
        E.checkArgument(skipDegree >= 0L,
                        "The skipped degree must be >= 0, but got '%s'",
                        skipDegree);
        if (capacity != NO_LIMIT) {
            E.checkArgument(degree != NO_LIMIT && degree < capacity,
                            "The degree must be < capacity");
            E.checkArgument(skipDegree < capacity,
                            "The skipped degree must be < capacity");
        }
        if (skipDegree > 0L) {
            E.checkArgument(degree != NO_LIMIT && skipDegree >= degree,
                            "The skipped degree must be >= degree, " +
                                    "but got skipped degree '%s' and degree '%s'",
                            skipDegree, degree);
        }
    }

    private class Traverser {

        private ShortestPaths findingNodes = new ShortestPaths();
        private ShortestPaths foundNodes = new ShortestPaths();
        private Set<NodeWithWeight> sources;
        private Id source;
        private final Directions direction;
        private final Id label;
        private final String weight;
        private final long degree;
        private final long skipDegree;
        private final long capacity;
        private final long limit;
        private long size;
        private boolean done = false;

        public Traverser(Id sourceV, Directions dir, Id label, String weight,
                         long degree, long skipDegree, long capacity,
                         long limit) {
            this.source = sourceV;
            this.sources = ImmutableSet.of(new NodeWithWeight(
                           0D, new Node(sourceV, null)));
            this.direction = dir;
            this.label = label;
            this.weight = weight;
            this.degree = degree;
            this.skipDegree = skipDegree;
            this.capacity = capacity;
            this.limit = limit;
            this.size = 0L;
        }

        /**
         * Search forward from source
         */
        public void forward() {
            long degree = this.skipDegree > 0L ? this.skipDegree : this.degree;
            for (NodeWithWeight node : this.sources) {
                Iterator<Edge> edges = edgesOfVertex(node.node().id(),
                                                     this.direction,
                                                     this.label, degree);
                edges = this.skipSuperNodeIfNeeded(edges);
                while (edges.hasNext()) {
                    HugeEdge edge = (HugeEdge) edges.next();
                    Id target = edge.id().otherVertexId();

                    if (this.foundNodes.containsKey(target) ||
                        this.source.equals(target)) {
                        // Already find shortest path for target, skip
                        continue;
                    }

                    double currentWeight = this.edgeWeight(edge);
                    double weight = currentWeight + node.weight();
                    NodeWithWeight nw = new NodeWithWeight(weight, target, node);
                    if (!this.findingNodes.containsKey(target) ||
                        weight < this.findingNodes.get(target).weight()) {
                        /*
                         * There are 2 scenarios to update finding nodes:
                         * 1. The 'target' found first time, add current path
                         * 2. Already exist path for 'target' and current
                         *    path is shorter, update path for 'target'
                         */
                        this.findingNodes.put(target, nw);
                    }
                }
            }

            List<NodeWithWeight> sorted = this.findingNodes.values().stream()
                                          .sorted(Comparator.comparing
                                          (NodeWithWeight::weight))
                                          .collect(Collectors.toList());
            double minWeight = 0;
            Set<NodeWithWeight> newSources = new HashSet<>();
            for (NodeWithWeight nw : sorted) {
                if (minWeight == 0) {
                    minWeight = nw.weight();
                } else if (nw.weight() > minWeight) {
                    break;
                }
                Id id = nw.node().id();
                // Move shortest paths from 'findingNodes' to 'foundNodes'
                this.foundNodes.put(id, nw);
                if (this.limit != NO_LIMIT &&
                    this.foundNodes.size() >= this.limit) {
                    this.done = true;
                    return;
                }
                this.findingNodes.remove(id);
                // Re-init 'sources'
                newSources.add(nw);
            }
            this.sources = newSources;
            if (this.sources.isEmpty()) {
                this.done = true;
            }
        }

        public boolean done() {
            return this.done;
        }

        public ShortestPaths shortestPaths() {
            return this.foundNodes;
        }

        private double edgeWeight(HugeEdge edge) {
            double edgeWeight;
            if (this.weight == null ||
                !edge.property(this.weight).isPresent()) {
                edgeWeight = 1;
            } else {
                edgeWeight = edge.value(this.weight);
            }
            return edgeWeight;
        }

        private Iterator<Edge> skipSuperNodeIfNeeded(Iterator<Edge> edges) {
            if (this.skipDegree <= 0L) {
                return edges;
            }
            List<Edge> edgeList = new ArrayList<>();
            for (int i = 1; edges.hasNext(); i++) {
                if (i <= this.degree) {
                    edgeList.add(edges.next());
                }
                if (i >= this.skipDegree) {
                    return QueryResults.emptyIterator();
                }
            }
            return edgeList.iterator();
        }
    }

    public static class NodeWithWeight {

        private final double weight;
        private final Node node;

        public NodeWithWeight(double weight, Node node) {
            this.weight = weight;
            this.node = node;
        }

        public NodeWithWeight(double weight, Id id, NodeWithWeight prio) {
            this(weight, new Node(id, prio.node()));
        }

        public double weight() {
            return weight;
        }

        public Node node() {
            return this.node;
        }

        public Map<String, Object> toMap() {
            return ImmutableMap.of("weight", this.weight,
                                   "path", this.node().path());
        }
    }

    public static class ShortestPaths extends HashMap<Id, NodeWithWeight> {

        public Set<Id> vertices() {
            Set<Id> vertices = new HashSet<>();
            vertices.addAll(this.keySet());
            for (NodeWithWeight nw : this.values()) {
                vertices.addAll(nw.node().path());
            }
            return vertices;
        }

        public Map<Id, Map<String, Object>> toMap() {
            Map<Id, Map<String, Object>> results = new HashMap<>();
            for (Map.Entry<Id, NodeWithWeight> entry : this.entrySet()) {
                Id source = entry.getKey();
                NodeWithWeight nw = entry.getValue();
                Map<String, Object> result = nw.toMap();
                results.put(source, result);
            }
            return results;
        }
    }
}