/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.service.graph.converter;

import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.service.model.graph.GraphNodeInfo;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the executable BFIA sub-DAG selected on the canvas.
 *
 * <p>{@code read_data/datatable} nodes are logical dataset selectors rather than Kuscia
 * AppImages. They are folded into the first executable component's host/guest private
 * parameters. Real BFIA components stay in topological order and retain only executable
 * predecessor ports, which the converter turns into BFIA task dependencies.</p>
 */
@Component
public class BfiaGraphExecutionPlanner {

    private static final String DATASET_ATTR = "datatable_selected";
    private static final String HOST_DATASET_ATTR = "host_dataset_id";
    private static final String GUEST_DATASET_ATTR = "guest_dataset_id";

    public List<GraphNodeInfo> plan(List<GraphNodeInfo> graphNodes, Collection<String> requestedNodeIds) {
        if (graphNodes == null || graphNodes.isEmpty()) {
            throw new IllegalArgumentException("BFIA graph has no nodes");
        }

        Map<String, GraphNodeInfo> nodesById = new LinkedHashMap<>();
        Map<String, GraphNodeInfo> outputOwners = new HashMap<>();
        for (GraphNodeInfo node : graphNodes) {
            if (node == null || isBlank(node.getGraphNodeId())) {
                throw new IllegalArgumentException("BFIA graph contains a node without graphNodeId");
            }
            if (nodesById.put(node.getGraphNodeId(), node) != null) {
                throw new IllegalArgumentException("Duplicate BFIA graph node id: " + node.getGraphNodeId());
            }
            if (node.getOutputs() == null || node.getOutputs().isEmpty()) {
                node.setOutputs(List.of(node.getGraphNodeId() + "-output-0"));
            }
            for (String output : node.getOutputs()) {
                if (isBlank(output)) {
                    continue;
                }
                GraphNodeInfo previous = outputOwners.put(output, node);
                if (previous != null && previous != node) {
                    throw new IllegalArgumentException("Duplicate BFIA graph output port: " + output);
                }
            }
        }

        Set<String> roots = new LinkedHashSet<>();
        if (requestedNodeIds == null || requestedNodeIds.isEmpty()) {
            for (GraphNodeInfo node : graphNodes) {
                if (!isDataSource(node)) {
                    roots.add(node.getGraphNodeId());
                }
            }
        } else {
            for (String nodeId : requestedNodeIds) {
                if (!nodesById.containsKey(nodeId)) {
                    throw new IllegalArgumentException("Unknown BFIA graph node: " + nodeId);
                }
                roots.add(nodeId);
            }
        }

        List<GraphNodeInfo> orderedClosure = new ArrayList<>();
        Map<String, VisitState> states = new HashMap<>();
        for (String root : roots) {
            visit(nodesById.get(root), outputOwners, states, orderedClosure);
        }

        List<GraphNodeInfo> executable = new ArrayList<>();
        for (GraphNodeInfo original : orderedClosure) {
            if (isDataSource(original)) {
                continue;
            }
            executable.add(toExecutableNode(original, outputOwners));
        }
        if (executable.isEmpty()) {
            throw new IllegalArgumentException("BFIA selection contains no executable algorithm node");
        }
        return executable;
    }

    private void visit(GraphNodeInfo node, Map<String, GraphNodeInfo> outputOwners,
                       Map<String, VisitState> states, List<GraphNodeInfo> ordered) {
        VisitState state = states.get(node.getGraphNodeId());
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            throw new IllegalArgumentException("BFIA graph contains a cycle at node: " + node.getGraphNodeId());
        }
        states.put(node.getGraphNodeId(), VisitState.VISITING);
        for (String input : safeList(node.getInputs())) {
            GraphNodeInfo predecessor = outputOwners.get(input);
            if (predecessor != null) {
                visit(predecessor, outputOwners, states, ordered);
            }
        }
        states.put(node.getGraphNodeId(), VisitState.VISITED);
        ordered.add(node);
    }

    private GraphNodeInfo toExecutableNode(GraphNodeInfo original, Map<String, GraphNodeInfo> outputOwners) {
        List<String> dependencyPorts = new ArrayList<>();
        List<String> datasetIds = new ArrayList<>();

        for (String input : safeList(original.getInputs())) {
            if (isBlank(input)) {
                continue;
            }
            GraphNodeInfo predecessor = outputOwners.get(input);
            if (predecessor == null) {
                // Backward compatibility for early BFIA graphs that stored dataset ids directly.
                datasetIds.add(input);
            } else if (isDataSource(predecessor)) {
                String datasetId = extractAttr(predecessor.getNodeDef(), DATASET_ATTR);
                if (isBlank(datasetId)) {
                    throw new IllegalArgumentException(
                            "BFIA data source node is not configured: " + predecessor.getGraphNodeId());
                }
                datasetIds.add(datasetId);
            } else {
                dependencyPorts.add(input);
            }
        }

        if (!datasetIds.isEmpty() && !dependencyPorts.isEmpty()) {
            throw new IllegalArgumentException(
                    "BFIA component cannot mix raw datasets and upstream task outputs: "
                            + original.getGraphNodeId());
        }

        Map<String, Object> nodeDef = toMap(original.getNodeDef());
        if (dependencyPorts.isEmpty()) {
            String hostDataset = extractAttr(nodeDef, HOST_DATASET_ATTR);
            String guestDataset = extractAttr(nodeDef, GUEST_DATASET_ATTR);
            if (isBlank(hostDataset) && !datasetIds.isEmpty()) {
                hostDataset = datasetIds.get(0);
                nodeDef.put(HOST_DATASET_ATTR, hostDataset);
            }
            if (isBlank(guestDataset) && datasetIds.size() > 1) {
                guestDataset = datasetIds.get(1);
                nodeDef.put(GUEST_DATASET_ATTR, guestDataset);
            }
            if (isBlank(hostDataset) || isBlank(guestDataset)) {
                throw new IllegalArgumentException(
                        "BFIA root component requires both host and guest datasets: "
                                + original.getGraphNodeId());
            }
        }

        return GraphNodeInfo.builder()
                .codeName(original.getCodeName())
                .graphNodeId(original.getGraphNodeId())
                .label(original.getLabel())
                .x(original.getX())
                .y(original.getY())
                .inputs(dependencyPorts)
                .outputs(new ArrayList<>(safeList(original.getOutputs())))
                .nodeDef(nodeDef)
                .build();
    }

    private Map<String, Object> toMap(Object nodeDef) {
        if (nodeDef == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(
                JsonUtils.toJavaMap(JsonUtils.toJSONString(nodeDef), Object.class));
    }

    private String extractAttr(Object nodeDef, String name) {
        Map<String, Object> def = toMap(nodeDef);
        Object pathsObject = def.get("attrPaths");
        Object attrsObject = def.get("attrs");
        if (pathsObject instanceof List<?> paths && attrsObject instanceof List<?> attrs
                && paths.size() == attrs.size()) {
            for (int i = 0; i < paths.size(); i++) {
                if (name.equals(String.valueOf(paths.get(i)))) {
                    Object value = unwrap(attrs.get(i));
                    return value == null ? null : String.valueOf(value);
                }
            }
        }
        Object value = unwrap(def.get(name));
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Object unwrap(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("s", "i64", "f", "b", "ss", "i64s", "fs", "bs")) {
                Object unwrapped = ((Map<String, Object>) map).get(key);
                if (unwrapped != null) {
                    return unwrapped;
                }
            }
        }
        return value;
    }

    public static boolean isDataSource(GraphNodeInfo node) {
        if (node == null || node.getCodeName() == null) {
            return false;
        }
        String codeName = node.getCodeName().trim().toLowerCase();
        return "read_data/datatable".equals(codeName)
                || "input-output/read-data".equals(codeName)
                || codeName.endsWith("/read_data");
    }

    private List<String> safeList(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
