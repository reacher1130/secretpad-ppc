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

package org.secretflow.secretpad.web.controller.bfia;

import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.service.GraphService;
import org.secretflow.secretpad.service.ProjectService;
import org.secretflow.secretpad.service.bfia.BfiaFlowService;
import org.secretflow.secretpad.service.bfia.BfiaGraphService;
import org.secretflow.secretpad.service.model.common.SecretPadPageResponse;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.model.graph.*;
import org.secretflow.secretpad.service.model.project.ProjectOutputVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BFIA flow controller. Mirrors the standard {@code /api/v1alpha1/graph/*} endpoints under
 * {@code /api/v1bfia/flow/*}, accepting {@code flowId}/{@code flowNodeId} (the frontend renames
 * graphId/graphNodeId). CRUD endpoints delegate to the standard {@link GraphService} (a BFIA flow is
 * persisted as a normal project graph); only {@code start} diverges to the BFIA submission path.
 *
 * @author gaofeng
 */
@Slf4j
@RestController
@RequestMapping("/api/v1bfia/flow")
@RequiredArgsConstructor
public class BfiaFlowController {

    private final GraphService graphService;
    private final BfiaFlowService bfiaFlowService;
    private final BfiaGraphService bfiaGraphService;
    private final ProjectService projectService;
    private final org.secretflow.secretpad.service.bfia.BfiaFlowLifecycleService lifecycle;
    private final BfiaPeerPsiResult peerPsiResult;
    @org.springframework.beans.factory.annotation.Value("${secretpad.node-id}")
    private String localNodeId;

    @PostMapping("/component/list")
    public SecretPadResponse<Map<String, CompListVO>> listComponents() {
        return SecretPadResponse.success(bfiaFlowService.listComponents());
    }

    @PostMapping("/component/i18n")
    public SecretPadResponse<Object> listComponentI18n() {
        return SecretPadResponse.success(graphService.listComponentI18n());
    }

    @PostMapping("/component/batch")
    public SecretPadResponse<Object> batchGetComponent(@RequestBody List<GetComponentRequest> request) {
        try {
            return SecretPadResponse.success(bfiaFlowService.batchGetComponent(request));
        } catch (Exception e) {
            log.warn("BFIA batchGetComponent failed, returning empty list", e);
            return SecretPadResponse.success(new ArrayList<>());
        }
    }

    @PostMapping("/update")
    public SecretPadResponse<Void> fullUpdateGraph(@RequestBody Map<String, Object> body) {
        bfiaGraphService.fullUpdateGraph(toRequest(body, FullUpdateGraphRequest.class));
        return SecretPadResponse.success();
    }

    @PostMapping("/save")
    public SecretPadResponse<Void> saveGraph(@RequestBody Map<String, Object> body) {
        bfiaGraphService.fullUpdateGraph(toRequest(body, FullUpdateGraphRequest.class));
        if ("1".equals(String.valueOf(body.get("isDraft")))) {
            lifecycle.submit(str(body, "projectId"), firstNonBlank(str(body, "flowId"), str(body, "graphId")));
        }
        return SecretPadResponse.success();
    }

    @PostMapping("/detail")
    public SecretPadResponse<GraphDetailVO> getGraphDetail(@RequestBody Map<String, Object> body) {
        return SecretPadResponse.success(bfiaGraphService.getGraphDetail(toRequest(body, GetGraphRequest.class)));
    }

    @PostMapping("/node/update")
    public SecretPadResponse<Void> updateGraphNode(@RequestBody Map<String, Object> body) {
        bfiaGraphService.updateGraphNode(toRequest(body, UpdateGraphNodeRequest.class));
        return SecretPadResponse.success();
    }

    @PostMapping("/node/status")
    public SecretPadResponse<GraphStatus> listGraphNodeStatus(@RequestBody Map<String, Object> body) {
        var request = toRequest(body, ListGraphNodeStatusRequest.class);
        GraphStatus peerStatus = peerPsiResult.status(request.getProjectId(), request.getGraphId());
        return SecretPadResponse.success(peerStatus != null ? peerStatus : bfiaFlowService.listGraphNodeStatus(request));
    }

    @PostMapping("/node/output")
    public SecretPadResponse<GraphNodeOutputVO> getGraphNodeOutput(@RequestBody Map<String, Object> body) {
        var request = toRequest(body, GraphNodeOutputRequest.class);
        GraphNodeOutputVO peerOutput = peerPsiResult.output(request);
        return SecretPadResponse.success(peerOutput != null ? peerOutput : bfiaFlowService.getGraphNodeOutput(request));
    }

    @PostMapping("/node/max_index")
    public SecretPadResponse<GraphNodeMaxIndexRefreshVO> graphNodeMaxIndexRefresh(@RequestBody Map<String, Object> body) {
        return SecretPadResponse.success(bfiaGraphService.refreshNodeMaxIndex(toRequest(body, GraphNodeMaxIndexRefreshRequest.class)));
    }

    @PostMapping("/start")
    public SecretPadResponse<StartGraphVO> startGraph(@RequestBody Map<String, Object> body) {
        return SecretPadResponse.success(bfiaFlowService.startGraph(toRequest(body, StartGraphRequest.class)));
    }

    /**
     * 流程产出表查询 (结果视图). Body: {projectId, flowId(=graphId)}. Reuses the native project out-table.
     */
    @PostMapping("/getOutTable")
    public SecretPadResponse<Object> getOutTable(@RequestBody Map<String, Object> body) {
        String projectId = str(body, "projectId");
        String graphId = firstNonBlank(str(body, "flowId"), str(body, "graphId"));
        GetGraphRequest request = new GetGraphRequest();
        request.setProjectId(projectId);
        request.setGraphId(graphId);
        var graph = bfiaGraphService.getGraphDetail(request);
        if (graph == null) throw new IllegalArgumentException("BFIA graph does not exist");
        // These are output port definitions, not claims that a computation produced a result.
        var outputs = graph.getNodes().stream()
                .filter(n -> !"read_data/datatable".equals(n.getCodeName()))
                .map(n -> Map.of("graphNodeId", n.getGraphNodeId(),
                        "outputs", n.getOutputs() == null ? List.of() : n.getOutputs())).toList();
        return SecretPadResponse.success(Map.of("projectId", projectId, "nodes", outputs));
    }

    /**
     * 创建流程 (InterOp 管理层 §7 流程互通). Body: {projectId, name, description}. Returns {flowId, graphId}.
     */
    @PostMapping("/create")
    public SecretPadResponse<Map<String, String>> createFlow(@RequestBody Map<String, Object> body) {
        CreateGraphRequest request = new CreateGraphRequest();
        request.setProjectId(str(body, "projectId"));
        request.setName(str(body, "name"));
        CreateGraphVO vo = bfiaGraphService.createGraph(request);
        Map<String, String> result = new HashMap<>();
        result.put("flowId", vo.getGraphId());
        result.put("graphId", vo.getGraphId());
        return SecretPadResponse.success(result);
    }

    /**
     * 流程列表/审批分页 (InterOp 管理层 §7). GET params: projectId, page_num, page_size.
     *
     * <p>Frontend {@code flow-list/index.tsx} reads {@code data.content} / {@code data.totalElements},
     * so the response is shaped as {@code {content, totalElements}} rather than the native
     * {@code SecretPadPageResponse.list} field.
     */
    @GetMapping("/audit/page")
    public SecretPadResponse<Map<String, Object>> page(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "page_num", defaultValue = "1") int pageNum,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        ListGraphRequest listReq = new ListGraphRequest();
        listReq.setProjectId(projectId);
        List<GraphMetaVO> all = bfiaGraphService.listGraph(listReq);
        // 前端 flow-list 读取 record.flowId，但 GraphMetaVO 字段名为 graphId，需要转换
        List<Map<String, Object>> content = new ArrayList<>();
        for (GraphMetaVO vo : all) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("flowId", vo.getGraphId());
            item.put("graphId", vo.getGraphId());
            item.put("projectId", vo.getProjectId());
            item.put("name", vo.getName());
            item.put("ownerId", vo.getOwnerId());
            var graph = bfiaGraphService.getGraphDO(projectId, vo.getGraphId());
            var audit = lifecycle.latestAudit(projectId, vo.getGraphId());
            item.put("isDraft", graph.getIsDraft());
            item.put("status", audit == null ? null : audit.getStatus());
            item.put("auditId", audit == null ? null : audit.getAuditId());
            item.put("applyNodeId", audit == null ? null : audit.getApplyNodeId());
            item.put("applyNodeName", audit == null ? null : audit.getApplyNodeId());
            content.add(item);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", content.size());
        return SecretPadResponse.success(result);
    }

    /**
     * 流程审批申请 (InterOp 管理层 §7.1). Body: {projectId, flowId, contract_id?}.
     */
    @PostMapping("/audit/apply")
    public SecretPadResponse<Map<String, String>> applyFlow(@RequestBody Map<String, Object> body) {
        String projectId = str(body, "projectId");
        String flowId = firstNonBlank(str(body, "flowId"), str(body, "graphId"));
        String contractId = firstNonBlank(str(body, "contract_id"), str(body, "contractId"));
        return SecretPadResponse.success(
                lifecycle.apply(projectId, flowId));
    }

    /**
     * 流程审批确认 (InterOp 管理层 §7.2). Body: {audit_id, status}.
     */
    @PostMapping("/audit/confirm")
    public SecretPadResponse<Boolean> confirmFlow(@RequestBody Map<String, Object> body) {
        String auditId = firstNonBlank(str(body, "audit_id"), str(body, "auditId"));
        String status = str(body, "status");
        return SecretPadResponse.success(lifecycle.confirm(auditId, status, localNodeId, true));
    }

    private String currentNodeId() {
        return localNodeId;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /**
     * Translate BFIA request keys ({@code flowId}/{@code flowNodeId}) back to the standard
     * graph request keys and bind to the target request type.
     */
    private <T> T toRequest(Map<String, Object> body, Class<T> clazz) {
        if (body.containsKey("flowId")) {
            body.putIfAbsent("graphId", body.get("flowId"));
        }
        if (body.containsKey("flowNodeId")) {
            body.putIfAbsent("graphNodeId", body.get("flowNodeId"));
        }
        return JsonUtils.toJavaObject(JsonUtils.toJSONString(body), clazz);
    }
}
