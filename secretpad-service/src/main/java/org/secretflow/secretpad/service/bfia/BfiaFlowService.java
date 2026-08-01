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

package org.secretflow.secretpad.service.bfia;

import org.secretflow.secretpad.service.model.graph.CompListVO;
import org.secretflow.secretpad.service.model.graph.CreateGraphRequest;
import org.secretflow.secretpad.service.model.graph.CreateGraphVO;
import org.secretflow.secretpad.service.model.graph.GetComponentRequest;
import org.secretflow.secretpad.service.model.graph.GraphMetaVO;
import org.secretflow.secretpad.service.model.graph.*;

import java.util.List;
import java.util.Map;

/**
 * BFIA flow service: orchestrates BFIA (interconn) flows. Unlike the standard SecretFlow flow which
 * submits a KusciaJob via gRPC, the BFIA flow submits the job to the kuscia interconn control-plane
 * HTTP API ({@code POST :8084/v1/interconn/schedule/job/create}).
 *
 * @author gaofeng
 */
public interface BfiaFlowService {

    /**
     * List BFIA components grouped by suite (consumed by the connectivity component tree).
     *
     * @return BFIA component list view objects keyed by suite name
     */
    Map<String, CompListVO> listComponents();

    /**
     * Batch query BFIA component specifications (inputs/outputs/attrs) used to render the canvas
     * node ports and the parameter config form. Reads from {@code config/bfiacomponents} rather than
     * the standard SecretFlow component registry.
     *
     * @param request requested components (may be ignored; all BFIA comps are returned)
     * @return the BFIA component specs as a list of maps
     */
    List<Map<String, Object>> batchGetComponent(List<GetComponentRequest> request);

    /**
     * Start a BFIA flow: build the project job from the graph, convert it to a BFIA job/create
     * payload and submit it to the kuscia interconn HTTP API.
     *
     * @param request start graph request (graphId == flowId)
     * @return start graph view object holding the submitted job id
     */
    StartGraphVO startGraph(StartGraphRequest request);

    /**
     * Create a BFIA flow. A BFIA flow is persisted as a standard project graph (flowId == graphId),
     * so this delegates to the standard graph creation. Aligns with InterOp 流程互通 (管理层 §7) where a
     * flow is the schedulable unit under a project.
     *
     * @param request create graph request (projectId + name)
     * @return created flow id (as graphId)
     */
    CreateGraphVO createFlow(CreateGraphRequest request);

    /**
     * List the flows (graphs) under a project for the flow list / audit page.
     *
     * @param projectId project id
     * @return flow meta list
     */
    List<GraphMetaVO> listFlow(String projectId);

    /**
     * Apply a flow audit (InterOp 管理层 §7.1 流程审批接口). Persists a {@code FLOW} audit record in
     * {@code bfia_audit} with status APPLIED. No-op-safe when the BFIA datasource is not configured.
     *
     * @param projectId  project id
     * @param flowId     flow id
     * @param contractId cooperation contract id (may be blank for single-node)
     * @param applyNodeId initiating node id
     * @return audit_id / status map
     */
    Map<String, String> applyFlowAudit(String projectId, String flowId, String contractId, String applyNodeId);

    /**
     * Confirm a flow audit (InterOp 管理层 §7.2 流程审批确认).
     *
     * @param auditId audit id
     * @param status  APPROVED / REJECTED (defaults APPROVED)
     * @return true on success
     */
    Boolean confirmFlowAudit(String auditId, String status);

    /**
     * Query BFIA graph node status from the persisted BFIA job record, syncing with
     * the kuscia control-plane on demand.
     *
     * @param request graph status request
     * @return graph status view object
     */
    GraphStatus listGraphNodeStatus(ListGraphNodeStatusRequest request);

    /**
     * Query BFIA graph node output. For leaf components (ecdh-psi, ss-*, etc.) the
     * output files are read from Kuscia runtime storage and returned as a table view.
     *
     * @param request graph node output request
     * @return graph node output view object
     */
    GraphNodeOutputVO getGraphNodeOutput(GraphNodeOutputRequest request);

    /**
     * Sync a single BFIA job status from the kuscia interconn control-plane and
     * persist it into {@code bfia_job}. Also updates {@code bfia_result.outputUri}
     * when the job reaches a terminal success state.
     *
     * @param jobId BFIA job id
     * @return the updated job DO, or null if not found
     */
    org.secretflow.secretpad.persistence.entity.bfia.BfiaJobDO syncJobStatus(String jobId);
}
