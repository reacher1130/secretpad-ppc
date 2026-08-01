package org.secretflow.secretpad.web.controller.bfia;

import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.persistence.entity.NodeDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaResultDO;
import org.secretflow.secretpad.persistence.repository.NodeRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaResultRepository;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1bfia/result")
@RequiredArgsConstructor
public class BfiaResultController {

    private final BfiaResultRepository bfiaResultRepository;
    private final NodeRepository nodeRepository;

    @PostMapping("list")
    public SecretPadResponse<Map<String, Object>> list(@RequestBody(required = false) Map<String, Object> body) {
        String projectId = body != null ? (String) body.get("projectId") : null;
        String nodeId = body != null ? (String) body.get("nodeId") : null;
        List<Map<String, Object>> records = new ArrayList<>();
        if (projectId != null && !projectId.isBlank()) {
            records = bfiaResultRepository.findByProjectId(projectId).stream()
                    .map(this::toVO)
                    .collect(Collectors.toList());
        } else {
            // standalone result manager: list by current node (fallback to all)
            String ownerNodeId = nodeId;
            if (ownerNodeId == null || ownerNodeId.isBlank()) {
                ownerNodeId = UserContext.getUser().getOwnerId();
            }
            if (ownerNodeId != null && !ownerNodeId.isBlank()) {
                // The URL carries the institution/owner id, while bfia_result.node_id
                // stores the Kuscia domain id (e.g. "alice"). Translate via node table
                // so that standalone result manager works regardless of which id the
                // frontend passes.
                String searchNodeId = ownerNodeId;
                List<NodeDO> nodes = nodeRepository.findByInstId(ownerNodeId);
                if (nodes != null && !nodes.isEmpty()) {
                    searchNodeId = nodes.get(0).getNodeId();
                }
                records = bfiaResultRepository.findByNodeId(searchNodeId).stream()
                        .map(this::toVO)
                        .collect(Collectors.toList());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", records.size());
        return SecretPadResponse.success(result);
    }

    @PostMapping("get")
    public SecretPadResponse<Map<String, Object>> get(@RequestBody Map<String, Object> body) {
        String resultId = (String) body.get("resultId");
        return bfiaResultRepository.findById(resultId)
                .map(r -> SecretPadResponse.success(toVO(r)))
                .orElse(SecretPadResponse.success(null));
    }

    private Map<String, Object> toVO(BfiaResultDO do_) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("resultId", do_.getResultId());
        vo.put("projectId", do_.getProjectId());
        vo.put("jobId", do_.getJobId());
        vo.put("taskId", do_.getTaskId());
        vo.put("nodeId", do_.getNodeId());
        vo.put("kind", do_.getKind());
        vo.put("refId", do_.getRefId());
        vo.put("outputUri", do_.getOutputUri());
        vo.put("gmtCreate", do_.getGmtCreate());
        return vo;
    }
}
