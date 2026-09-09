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
import org.secretflow.secretpad.persistence.entity.ProjectNodeDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaContractDO;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaContractRepository;
import org.secretflow.secretpad.service.ProjectService;
import org.secretflow.secretpad.service.bfia.BfiaInterconnClient;
import org.secretflow.secretpad.service.bfia.BfiaProjectService;
import org.secretflow.secretpad.service.impl.bfia.BfiaProjectServiceImpl;
import org.secretflow.secretpad.service.model.project.GetProjectDatatableRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaConfirmRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaProjectApplyRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaProjectListInfo;
import org.secretflow.secretpad.service.model.bfia.BfiaProjectVO;
import org.secretflow.secretpad.service.model.common.SecretPadPageResponse;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PAD-facing BFIA project controller (north-south).
 *
 * <p>Serves the management console "我的项目" pages under {@code /api/v1bfia/project/*}. Implements the
 * project interoperability semantics from the InterOp spec (管理层 API 第 5 章: 5.1 审批申请 / 5.2 审批确认 /
 * 5.4 状态查询 / 5.5 列表 / 5.6 信息查询). These endpoints are login-authenticated (see
 * {@code LoginConfiguration}); the current node id comes from {@link UserContext}, not a request header.
 * Node-to-node propagation lives in {@link BfiaSyncProjectController} under {@code /v1/interconn/project}.
 *
 * @author gaofeng
 */
@Slf4j
@RestController
@RequestMapping("/api/v1bfia/project")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaProjectController {

    private final BfiaProjectService bfiaProjectService;
    private final org.secretflow.secretpad.service.bfia.BfiaDataService bfiaDataService;
    private final BfiaInterconnClient bfiaInterconnClient;
    private final ProjectService projectService;
    private final BfiaContractRepository bfiaContractRepository;
    private final ProjectNodeRepository projectNodeRepository;
    private final org.secretflow.secretpad.persistence.repository.bfia.BfiaNodeRepository bfiaNodeRepository;
    private final org.secretflow.secretpad.persistence.repository.bfia.BfiaAuditRepository bfiaAuditRepository;

    /**
     * 项目列表 (管理层 §5.5). GET params: page_num, page_size, contract_id(optional).
     *
     * <p>Frontend {@code p2p-project-list/index.tsx} reads {@code data.records} / {@code data.total},
     * so the response is shaped as {@code {records, total}} rather than the native
     * {@code SecretPadPageResponse.list} field.
     */
    @GetMapping("list")
    public SecretPadResponse<Map<String, Object>> list(
            @RequestParam(value = "contract_id", required = false) String contractId,
            @RequestParam(value = "page_num", defaultValue = "1") int pageNum,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        SecretPadPageResponse<BfiaProjectListInfo> page =
                bfiaProjectService.listProject(contractId, pageNum, pageSize);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getList());
        result.put("total", page.getTotal());
        return SecretPadResponse.success(result);
    }

    /**
     * 申请新建项目 (管理层 §5.1). Body: {name, contract_id, description}.
     */
    @PostMapping("audit/apply")
    public SecretPadResponse<Map<String, String>> apply(@RequestBody Map<String, Object> body) {
        BfiaProjectApplyRequest request = BfiaProjectApplyRequest.builder()
                .name(str(body, "name"))
                .contractId(firstNonBlank(str(body, "contract_id"), str(body, "contractId")))
                .description(str(body, "description"))
                .type(firstNonBlank(str(body, "type"), "PROJECT"))
                .build();
        String sourceNodeId = currentNodeId();
        String targetNodeId = firstNonBlank(str(body, "target_node_id"), str(body, "targetNodeId"));
        // If target_node_id not explicitly provided, derive it from the contract_id (cooperative
        // node record stores the peer node id).
        if (targetNodeId == null && request.getContractId() != null) {
            BfiaContractDO contract = bfiaContractRepository.findByContractId(request.getContractId());
            if (contract != null) {
                targetNodeId = contract.getNodeId();
            } else if (bfiaNodeRepository.existsById(request.getContractId())) {
                // Older imported BFIA nodes use the registered node ID as contractId.
                targetNodeId = request.getContractId();
            }
        }
        if (targetNodeId == null || sourceNodeId.equals(targetNodeId)
                || !bfiaNodeRepository.existsById(targetNodeId)) {
            throw new IllegalArgumentException("请选择已登记的 BFIA 对端节点");
        }
        Map<String, String> result = bfiaProjectService.apply(request, sourceNodeId);
        String projectId = result.get("project_id");
        ensureProjectNode(projectId, sourceNodeId);
        // 合作方也要写入 project_node，否则后续 BFIA 作业无法解析参与方。
        if (targetNodeId != null) {
            ensureProjectNode(projectId, targetNodeId);
        }
        if (bfiaInterconnClient.isEnabled() && targetNodeId != null) {
            request.setProjectId(projectId);
            request.setAuditId(result.get("audit_id"));
            bfiaInterconnClient.postChecked(sourceNodeId, targetNodeId, "/v1/interconn/project/audit/apply", request);
        }
        return SecretPadResponse.success(result);
    }

    /**
     * 审批确认 (管理层 §5.2). Body: {audit_id, status}.
     */
    @PostMapping("audit/confirm")
    public SecretPadResponse<Boolean> confirm(@RequestBody Map<String, Object> body) {
        BfiaConfirmRequest request = BfiaConfirmRequest.builder()
                .auditId(firstNonBlank(str(body, "audit_id"), str(body, "auditId")))
                .status(str(body, "status"))
                .build();
        String sourceNodeId = currentNodeId();
        var audit = bfiaAuditRepository.findById(request.getAuditId())
                .orElseThrow(() -> new IllegalArgumentException("审批不存在"));
        if (sourceNodeId.equals(audit.getApplyNodeId())
                || !projectNodeRepository.findProjectNodesByProjectId(audit.getProjectId()).contains(sourceNodeId)) {
            throw new IllegalArgumentException("只有受邀参与方可以审批项目");
        }
        if (bfiaInterconnClient.isEnabled()) {
            bfiaInterconnClient.postChecked(sourceNodeId, audit.getApplyNodeId(),
                    "/v1/interconn/project/audit/confirm", request);
        }
        return SecretPadResponse.success(bfiaProjectService.confirm(request, sourceNodeId));
    }

    /**
     * 审批状态查询 (管理层 §5.4). GET param: audit_id.
     */
    @GetMapping("audit/query")
    public SecretPadResponse<Map<String, String>> query(@RequestParam("audit_id") String auditId) {
        return SecretPadResponse.success(bfiaProjectService.queryAuditStatus(auditId));
    }

    /**
     * 项目信息查询 (管理层 §5.6). Body: {projectId, contract_id(optional)}.
     */
    @PostMapping("get")
    public SecretPadResponse<BfiaProjectVO> get(@RequestBody Map<String, Object> body) {
        String projectId = firstNonBlank(str(body, "projectId"), str(body, "project_id"));
        String contractId = firstNonBlank(str(body, "contractId"), str(body, "contract_id"));
        return SecretPadResponse.success(bfiaProjectService.queryProject(contractId, projectId));
    }

    /**
     * 项目数据表明细 (结果视图). Body: {projectId, ...}. Reuses the native project datatable detail.
     */
    @PostMapping("datatable/get")
    public SecretPadResponse<Object> getProjectDatatable(@RequestBody Map<String, Object> body) {
        String projectId = str(body, "projectId");
        String datasetId = str(body, "datatableId");
        String nodeId = str(body, "nodeId");
        var nodes = projectNodeRepository.findProjectNodesByProjectId(projectId);
        if (!nodes.contains(currentNodeId()) || !nodes.contains(nodeId)) {
            throw new IllegalArgumentException("BFIA dataset node is not a project participant");
        }
        Map<String, Object> dataset;
        if (currentNodeId().equals(nodeId)) {
            dataset = JsonUtils.toJavaMap(JsonUtils.toJSONString(bfiaDataService.queryDataset(null, datasetId)), Object.class);
        } else {
            dataset = bfiaInterconnClient.queryRemoteDataset(currentNodeId(), nodeId, datasetId);
        }
        if (dataset == null || dataset.get("fieldNames") == null) {
            throw new IllegalArgumentException("BFIA dataset schema is unavailable");
        }
        var configs = new java.util.ArrayList<Map<String, Object>>();
        for (String field : String.valueOf(dataset.get("fieldNames")).split(",")) {
            if (!field.isBlank()) configs.add(Map.of("colName", field.trim(), "colType", "str",
                    "isAssociateKey", false, "isGroupKey", false, "isLabelKey", false));
        }
        return SecretPadResponse.success(Map.of("datatableId", datasetId,
                "datatableName", String.valueOf(dataset.get("name")), "configs", configs));
    }

    private String currentNodeId() {
        // ownerId 是机构/实例 ID，platformNodeId 才是当前节点域名（alice / bob / kimi）。
        return UserContext.getUser().getPlatformNodeId();
    }

    /**
     * 写入 project_node 表（幂等）。BFIA 项目记录在 bfia_project 表，但标准 SecretPad 的
     * fullUpdateGraph / getControlNodeIds 依赖 project_node 表来校验参与方节点。
     */
    private void ensureProjectNode(String projectId, String nodeId) {
        if (projectId == null || nodeId == null) {
            return;
        }
        try {
            ProjectNodeDO.UPK upk = new ProjectNodeDO.UPK(projectId, nodeId);
            if (!projectNodeRepository.findById(upk).isPresent()) {
                projectNodeRepository.save(ProjectNodeDO.Factory.newProjectNode(projectId, nodeId));
                log.info("BFIA ensureProjectNode: created project_node projectId={}, nodeId={}", projectId, nodeId);
            }
        } catch (Exception e) {
            log.warn("BFIA ensureProjectNode failed: projectId={}, nodeId={}, err={}", projectId, nodeId, e.getMessage());
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
