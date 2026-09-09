package org.secretflow.secretpad.web.controller.bfia;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.persistence.entity.ProjectNodeDO;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.service.bfia.BfiaProjectService;
import org.secretflow.secretpad.service.model.bfia.*;
import org.secretflow.secretpad.service.model.common.SecretPadPageResponse;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;

/**
 * BFIA node-to-node project interconnect controller (east-west).
 *
 * <p>Implements the management-layer project interconnect interfaces defined by the InterOp spec
 * (管理层 API 第 5 章 项目互通接口: 5.1 审批申请 / 5.2 审批确认 / 5.4 状态查询 / 5.5 列表 / 5.6 信息查询).
 * These endpoints are invoked by peer SecretPad nodes through the kuscia network and authenticated by
 * the {@code kuscia-origin-source} header, NOT by the PAD login session. The PAD-facing project API
 * lives in {@link BfiaProjectController} under {@code /api/v1bfia/project}.
 *
 * @author gaofeng
 */
@Slf4j
@RestController
@RequestMapping("/v1/interconn/project/")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaSyncProjectController {

    private final BfiaProjectService bfiaProjectService;
    private final ProjectNodeRepository projectNodeRepository;

    @org.springframework.beans.factory.annotation.Value("${secretpad.node-id}")
    private String localNodeId;

    @PostMapping("audit/apply")
    public SecretPadResponse<HashMap<String, String>> apply(@RequestHeader("kuscia-origin-source") String nodeId, @RequestBody String p) {
        BfiaProjectApplyRequest request = JsonUtils.toJavaObject(p, BfiaProjectApplyRequest.class);
        HashMap<String, String> result = bfiaProjectService.apply(request, nodeId);
        // 补写标准 project_node 表，使 fullUpdateGraph / getControlNodeIds 能找到参与方节点
        ensureProjectNode(result.get("project_id"), nodeId);
        ensureProjectNode(result.get("project_id"), localNodeId);
        return SecretPadResponse.success(result);
    }

    @PostMapping("audit/confirm")
    public SecretPadResponse<Boolean> confirm(@RequestHeader("kuscia-origin-source") String nodeId, @RequestBody String p) {
        BfiaConfirmRequest request = JsonUtils.toJavaObject(p, BfiaConfirmRequest.class);
        return SecretPadResponse.success(bfiaProjectService.confirm(request, nodeId));
    }

    @GetMapping("audit/query")
    public SecretPadResponse<HashMap<String, String>> query(@RequestHeader("kuscia-origin-source") String nodeId, @RequestParam("audit_id") String auditId) {
        return SecretPadResponse.success(bfiaProjectService.queryAuditStatus(auditId));
    }

    @GetMapping("list")
    public SecretPadResponse<SecretPadPageResponse<BfiaProjectListInfo>> listProject(@RequestHeader("kuscia-origin-source") String nodeId,
                                                                                     @RequestParam(value = "contract_id", required = false) String contractId,
                                                                                     @RequestParam("page_num") int pageNum,
                                                                                     @RequestParam("page_size") int pageSize) {
        SecretPadPageResponse<BfiaProjectListInfo> page = bfiaProjectService.listProject(contractId, pageNum, pageSize);
        return SecretPadResponse.success(page);
    }

    @GetMapping("query")
    public SecretPadResponse<BfiaProjectVO> queryProject(@RequestHeader("kuscia-origin-source") String nodeId,
                                                         @RequestParam("contract_id") String contractId,
                                                         @RequestParam("project_id") String projectId) {
        return SecretPadResponse.success(bfiaProjectService.queryProject(contractId, projectId));
    }

    /** 写入 project_node 表（幂等），与 BfiaProjectController.ensureProjectNode 同理 */
    private void ensureProjectNode(String projectId, String nodeId) {
        if (projectId == null || nodeId == null) {
            return;
        }
        try {
            ProjectNodeDO.UPK upk = new ProjectNodeDO.UPK(projectId, nodeId);
            if (!projectNodeRepository.findById(upk).isPresent()) {
                projectNodeRepository.save(ProjectNodeDO.Factory.newProjectNode(projectId, nodeId));
                log.info("BFIA sync ensureProjectNode: created project_node projectId={}, nodeId={}", projectId, nodeId);
            }
        } catch (Exception e) {
            log.warn("BFIA sync ensureProjectNode failed: projectId={}, nodeId={}, err={}", projectId, nodeId, e.getMessage());
        }
    }
}
