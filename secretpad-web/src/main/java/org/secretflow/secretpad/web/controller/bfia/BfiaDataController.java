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

import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.service.bfia.BfiaDataService;
import org.secretflow.secretpad.service.bfia.BfiaOutputStorage;
import org.secretflow.secretpad.service.model.bfia.BfiaConfirmRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaDatasetApplyRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaDatasetVO;
import org.secretflow.secretpad.service.model.bfia.BfiaListDatatableRequest;
import org.secretflow.secretpad.service.model.common.SecretPadPageResponse;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * PAD-facing BFIA dataset controller (north-south).
 *
 * <p>Serves the management console "数据管理" pages (我方数据 / 待我处理 / 合作方数据) under
 * {@code /api/v1bfia/dataset/*}. Implements the dataset interoperability semantics from the InterOp
 * spec (管理层 API 第 4 章 数据集互通接口: 4.1 公开列表 / 4.2 明细 / 4.3 授权申请 / 4.4 授权意向更新 /
 * 4.6 状态查询).
 *
 * <p>List/query/audit endpoints delegate to {@link BfiaDataService} (backed by {@code bfia_dataset} /
 * {@code bfia_audit}). The richer import/upload/authorize flows are progressively filled (P2);
 * unimplemented branches return safe-empty results so the pages render rather than 404.
 *
 * @author gaofeng
 */
@Slf4j
@RestController
@RequestMapping("/api/v1bfia/dataset")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaDataController {

    private final BfiaDataService bfiaDataService;
    private final BfiaOutputStorage bfiaOutputStorage;

    /**
     * 我方数据列表. Body: {pageNum, pageSize, types}.
     * Returns {records, total} to match frontend expectation.
     */
    @PostMapping("list")
    public SecretPadResponse<Map<String, Object>> list(@RequestBody(required = false) Map<String, Object> body) {
        int pageNum = intVal(body, "pageNum", 1);
        int pageSize = intVal(body, "pageSize", 10);
        BfiaListDatatableRequest req = new BfiaListDatatableRequest(pageNum, pageSize);
        SecretPadPageResponse<BfiaDatasetVO> page = bfiaDataService.listDataset(null, req, currentNodeId());
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getList());
        result.put("total", page.getTotal());
        return SecretPadResponse.success(result);
    }

    /**
     * 数据表明细 (管理层 §4.2). Body: {datatableId | dataset_id}.
     */
    @PostMapping("get")
    public SecretPadResponse<BfiaDatasetVO> get(@RequestBody(required = false) Map<String, Object> body) {
        String datasetId = firstNonBlank(str(body, "datatableId"), str(body, "dataset_id"), str(body, "datasetId"));
        String contractId = firstNonBlank(str(body, "contract_id"), str(body, "contractId"));
        return SecretPadResponse.success(bfiaDataService.queryDataset(contractId, datasetId));
    }

    /**
     * 公开数据给节点 (管理层 §4.1 公开). Body: {contract_id, dataset_id}.
     */
    @PostMapping("public")
    public SecretPadResponse<Boolean> publicDataset(@RequestBody(required = false) Map<String, Object> body) {
        String contractId = firstNonBlank(str(body, "contract_id"), str(body, "contractId"));
        String datasetId = firstNonBlank(str(body, "dataset_id"), str(body, "datasetId"));
        return SecretPadResponse.success(bfiaDataService.pubDataset(currentNodeId(), contractId, datasetId));
    }

    /**
     * 数据集授权申请 (管理层 §4.3). Body: {dataset_id, contract_id, time_limit, auth_scope}.
     */
    @PostMapping("audit/apply")
    public SecretPadResponse<Boolean> auditApply(@RequestBody(required = false) Map<String, Object> body) {
        BfiaDatasetApplyRequest req = new BfiaDatasetApplyRequest();
        req.setDatasetId(firstNonBlank(str(body, "dataset_id"), str(body, "datasetId")));
        req.setContractId(firstNonBlank(str(body, "contract_id"), str(body, "contractId")));
        return SecretPadResponse.success(bfiaDataService.apply(req, currentNodeId()));
    }

    /**
     * 数据集授权确认 (管理层 §4.4). Body: {audit_id, status}.
     */
    @PostMapping("audit/confirm")
    public SecretPadResponse<Boolean> auditConfirm(@RequestBody(required = false) Map<String, Object> body) {
        BfiaConfirmRequest req = BfiaConfirmRequest.builder()
                .auditId(firstNonBlank(str(body, "audit_id"), str(body, "auditId")))
                .status(str(body, "status"))
                .build();
        return SecretPadResponse.success(bfiaDataService.confirm(req, currentNodeId()));
    }

    /**
     * 授权状态查询 (管理层 §4.6). GET param: audit_id.
     */
    @GetMapping("audit/query")
    public SecretPadResponse<Map<String, String>> auditQuery(@RequestParam("audit_id") String auditId) {
        return SecretPadResponse.success(bfiaDataService.queryAuditStatus(auditId));
    }

    /**
     * 待我处理的数据 (列表). Backed by bfia_audit APPROVED records.
     */
    @GetMapping("list/approved")
    public SecretPadResponse<SecretPadPageResponse<BfiaDatasetVO>> listApproved(
            @RequestParam(value = "page_num", defaultValue = "1") int pageNum,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return SecretPadResponse.success(bfiaDataService.listApproved(currentNodeId(), pageNum, pageSize));
    }

    /**
     * 合作方公开数据列表.
     */
    @GetMapping("pub/list")
    public SecretPadResponse<SecretPadPageResponse<BfiaDatasetVO>> pubList(
            @RequestParam(value = "page_num", defaultValue = "1") int pageNum,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize,
            @RequestParam(value = "contract_id", required = false) String contractId) {
        return SecretPadResponse.success(bfiaDataService.listPub(contractId, pageNum, pageSize));
    }

    /**
     * 合作方公开数据明细查询. GET params: contract_id, dataset_id.
     */
    @GetMapping("pub/query")
    public SecretPadResponse<BfiaDatasetVO> pubQuery(
            @RequestParam(value = "contract_id", required = false) String contractId,
            @RequestParam("dataset_id") String datasetId) {
        return SecretPadResponse.success(bfiaDataService.queryDataset(contractId, datasetId));
    }

    /**
     * 授权历史 (我方).
     */
    @GetMapping("approved/history")
    public SecretPadResponse<List<Map<String, String>>> approvedHistory(
            @RequestParam(value = "datasetId", required = false) String datasetId,
            @RequestParam(value = "status", required = false) String status) {
        return SecretPadResponse.success(bfiaDataService.approvedHistory(currentNodeId(), datasetId, status));
    }

    /**
     * 申请历史 (合作方数据).
     */
    @GetMapping("applyed/history")
    public SecretPadResponse<List<Map<String, String>>> applyedHistory(
            @RequestParam(value = "datasetId", required = false) String datasetId,
            @RequestParam(value = "status", required = false) String status) {
        return SecretPadResponse.success(bfiaDataService.appliedHistory(currentNodeId(), datasetId, status));
    }

    /**
     * 创建数据表. Persists to bfia_dataset table.
     */
    @PostMapping("create")
    public SecretPadResponse<BfiaDatasetVO> create(@RequestBody(required = false) Map<String, Object> body) {
        String name = firstNonBlank(str(body, "name"), str(body, "dataset_name"));
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("dataset name is required");
        }
        String description = str(body, "description");
        String contractId = firstNonBlank(str(body, "contract_id"), str(body, "contractId"));
        return SecretPadResponse.success(bfiaDataService.createDataset(currentNodeId(), name, description, contractId));
    }

    /**
     * 更新数据集元数据 (fileName/fieldNames/hasLabel/namespace/rowCount).
     * These fields are required by the BFIA job converter to build per-party data references.
     */
    @PostMapping("update")
    public SecretPadResponse<BfiaDatasetVO> update(@RequestBody(required = false) Map<String, Object> body) {
        String datasetId = firstNonBlank(str(body, "dataset_id"), str(body, "datasetId"));
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("dataset_id is required");
        }
        String fileName = str(body, "file_name");
        if (fileName == null) {
            fileName = str(body, "fileName");
        }
        String fieldNames = str(body, "field_names");
        if (fieldNames == null) {
            fieldNames = str(body, "fieldNames");
        }
        String namespace = str(body, "namespace");
        Boolean hasLabel = null;
        Object hl = body == null ? null : body.get("has_label");
        if (hl == null) {
            hl = body == null ? null : body.get("hasLabel");
        }
        if (hl != null) {
            hasLabel = Boolean.parseBoolean(String.valueOf(hl));
        }
        Long rowCount = null;
        Object rc = body == null ? null : body.get("row_count");
        if (rc == null) {
            rc = body == null ? null : body.get("rowCount");
        }
        if (rc != null) {
            try { rowCount = Long.parseLong(String.valueOf(rc)); } catch (NumberFormatException ignored) {}
        }
        return SecretPadResponse.success(bfiaDataService.updateDataset(currentNodeId(), datasetId, fileName, fieldNames, hasLabel, namespace, rowCount));
    }

    /**
     * 删除数据表. Soft-deletes from bfia_dataset.
     */
    @PostMapping("delete")
    public SecretPadResponse<Boolean> delete(@RequestBody(required = false) Map<String, Object> body) {
        String datasetId = firstNonBlank(str(body, "dataset_id"), str(body, "datasetId"));
        return SecretPadResponse.success(bfiaDataService.deleteDataset(currentNodeId(), datasetId));
    }

    /**
     * 授权数据给项目. Creates an audit record and marks dataset as AUTHORIZED.
     */
    @PostMapping("authorize")
    public SecretPadResponse<Boolean> authorize(@RequestBody(required = false) Map<String, Object> body) {
        String datasetId = firstNonBlank(str(body, "dataset_id"), str(body, "datasetId"));
        String projectId = firstNonBlank(str(body, "project_id"), str(body, "projectId"));
        String authScope = str(body, "auth_scope");
        Long timeLimit = null;
        Object tl = body == null ? null : body.get("time_limit");
        if (tl != null) {
            try { timeLimit = Long.parseLong(String.valueOf(tl)); } catch (NumberFormatException ignored) {}
        }
        return SecretPadResponse.success(bfiaDataService.authorize(currentNodeId(), datasetId, projectId, authScope, timeLimit));
    }

    /**
     * 下载 BFIA 结果文件. Body: {nodeId, filePath}.
     * filePath is the outputUri stored in bfia_result (e.g. "rkig-host-0/rkig-xxx-result").
     */
    @PostMapping("downloadOutput")
    public ResponseEntity<byte[]> downloadOutput(@RequestBody(required = false) Map<String, Object> body) {
        String filePath = firstNonBlank(str(body, "filePath"), str(body, "outputUri"), str(body, "refId"));
        if (filePath == null || filePath.isBlank()) {
            return ResponseEntity.badRequest().body("filePath is required".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String normalized = filePath.replaceAll("^/+|/+$", "");
        int idx = normalized.indexOf('/');
        if (idx <= 0 || idx >= normalized.length() - 1) {
            return ResponseEntity.badRequest().body("invalid filePath format, expected namespace/name".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String namespace = normalized.substring(0, idx);
        String name = normalized.substring(idx + 1);
        byte[] bytes = bfiaOutputStorage.readBytes(namespace, name);
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        String filename = name + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("text/csv;charset=utf-8"));
        headers.setContentDispositionFormData("attachment", filename);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    private String currentNodeId() {
        return UserContext.getUser().getOwnerId();
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonBlank(String... vals) {
        if (vals != null) {
            for (String v : vals) {
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    private static int intVal(Map<String, Object> body, String key, int def) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
