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

import org.secretflow.secretpad.service.graph.converter.BfiaJobConverter;
import org.secretflow.secretpad.service.model.graph.GraphNodeInfo;
import org.secretflow.secretpad.service.model.graph.ProjectJob;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BfiaJobConverterTest {

    @Test
    @SuppressWarnings("unchecked")
    void emitsUniqueAliasesAndBfiaDependencies() {
        // Dependency conversion itself does not need dataset persistence; planner tests cover
        // folding configured dataset selectors into the root component.
        BfiaJobConverter converter = new BfiaJobConverter(null, null);

        GraphNodeInfo psi = GraphNodeInfo.builder()
                .graphNodeId("psi-node")
                .codeName("隐私求交/ecdh-psi")
                .inputs(List.of())
                .outputs(List.of("psi-output"))
                .nodeDef(Map.of())
                .build();
        GraphNodeInfo lr = GraphNodeInfo.builder()
                .graphNodeId("lr-node")
                .codeName("联邦学习/ss-lr")
                .inputs(List.of("psi-output"))
                .outputs(List.of("lr-output"))
                .nodeDef(Map.of())
                .build();
        ProjectJob job = ProjectJob.builder()
                .jobId("job-1")
                .graphId("flow-1")
                .projectId("project-1")
                .tasks(List.of(task("task-1", psi), task("task-2", lr)))
                .build();

        Map<String, Object> payload = converter.convert(job, "alice");
        Map<String, Object> dag = (Map<String, Object>) payload.get("dag");
        List<Map<String, Object>> components = (List<Map<String, Object>>) dag.get("components");
        String psiAlias = (String) components.get(0).get("name");
        String lrAlias = (String) components.get(1).get("name");
        List<Map<String, Object>> lrInputs = (List<Map<String, Object>>) components.get(1).get("input");

        assertEquals("ecdh-psi-psi-node", psiAlias);
        assertEquals("ss-lr-lr-node", lrAlias);
        assertEquals("ecdh-psi-psi-node.result", lrInputs.get(0).get("key"));
    }

    private ProjectJob.JobTask task(String taskId, GraphNodeInfo node) {
        return ProjectJob.JobTask.builder()
                .taskId(taskId)
                .parties(List.of("alice", "bob"))
                .node(node)
                .build();
    }
}
