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

package org.secretflow.secretpad.service.test;

import org.secretflow.secretpad.service.graph.converter.BfiaGraphExecutionPlanner;
import org.secretflow.secretpad.service.model.graph.GraphNodeInfo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BfiaGraphExecutionPlannerTest {

    private final BfiaGraphExecutionPlanner planner = new BfiaGraphExecutionPlanner();

    @Test
    void expandsUpstreamNodesAndFoldsDatasetSelectors() {
        GraphNodeInfo host = dataNode("host-data", "host-output", "host-dataset");
        GraphNodeInfo guest = dataNode("guest-data", "guest-output", "guest-dataset");
        GraphNodeInfo psi = algorithmNode(
                "psi-node", "隐私求交/ecdh-psi", List.of("host-output", "guest-output"), "psi-output");
        GraphNodeInfo lr = algorithmNode(
                "lr-node", "联邦学习/ss-lr", List.of("psi-output"), "lr-output");

        List<GraphNodeInfo> planned = planner.plan(List.of(host, guest, psi, lr), List.of("lr-node"));

        assertEquals(List.of("psi-node", "lr-node"),
                planned.stream().map(GraphNodeInfo::getGraphNodeId).toList());
        assertEquals(List.of(), planned.get(0).getInputs());
        assertEquals("host-dataset", ((Map<?, ?>) planned.get(0).getNodeDef()).get("host_dataset_id"));
        assertEquals("guest-dataset", ((Map<?, ?>) planned.get(0).getNodeDef()).get("guest_dataset_id"));
        assertEquals(List.of("psi-output"), planned.get(1).getInputs());
    }

    @Test
    void rejectsCycles() {
        GraphNodeInfo first = algorithmNode("first", "联邦学习/ss-lr", List.of("second-out"), "first-out");
        GraphNodeInfo second = algorithmNode("second", "联邦学习/secureboot", List.of("first-out"), "second-out");

        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(first, second), List.of("first")));
    }

    private GraphNodeInfo dataNode(String id, String output, String datasetId) {
        return GraphNodeInfo.builder()
                .graphNodeId(id)
                .codeName("read_data/datatable")
                .inputs(List.of())
                .outputs(List.of(output))
                .nodeDef(Map.of(
                        "attrPaths", List.of("datatable_selected"),
                        "attrs", List.of(Map.of("s", datasetId))))
                .build();
    }

    private GraphNodeInfo algorithmNode(String id, String codeName, List<String> inputs, String output) {
        return GraphNodeInfo.builder()
                .graphNodeId(id)
                .codeName(codeName)
                .inputs(inputs)
                .outputs(List.of(output))
                .nodeDef(Map.of())
                .build();
    }
}
