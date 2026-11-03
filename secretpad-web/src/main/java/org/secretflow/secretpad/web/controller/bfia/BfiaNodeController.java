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

import org.secretflow.secretpad.common.annotation.resource.ApiResource;
import org.secretflow.secretpad.common.constant.resource.ApiResourceCodeConstants;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaContractDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaNodeDO;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaContractRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaNodeRepository;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.model.node.NodeVO;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

/**
 * PAD-facing BFIA cooperative-node controller (north-south).
 *
 * <p>Serves the management console "合作节点" pages and the project collaborator picker under
 * {@code /api/v1bfia/node/*}. Implements the node interoperability semantics from the InterOp spec
 * (管理层 API 第 3 章 节点互通接口: 3.1 合作申请 / 3.2 更新合作意向 / 3.5 节点合作查询 / 3.6 节点信息查询).
 *
 * <p>Contract history and the legacy project collaborator directory use separate BFIA tables.
 *
 * @author gaofeng
 */
@RestController
@RequestMapping("/api/v1bfia/node")
@RequiredArgsConstructor
public class BfiaNodeController {

    private final BfiaNodeRepository bfiaNodeRepository;
    private final BfiaContractRepository bfiaContractRepository;
    private final BfiaNodeCooperation cooperation;
    private final BfiaNodePeerChannel channel;

    /**
     * Build a lightweight NodeVO from a BfiaNodeDO without triggering gRPC calls to Kuscia.
     * The BFIA frontend only needs nodeId / name / contractId for the collaborator picker.
     */
    private NodeVO toNodeVO(BfiaNodeDO do_) {
        NodeVO vo = new NodeVO();
        vo.setNodeId(do_.getNodeId());
        vo.setNodeName(do_.getName());
        vo.setName(do_.getName());
        vo.setContractId(do_.getNodeId());
        vo.setNetAddress(do_.getNetAddress());
        vo.setInstId(do_.getInstId());
        return vo;
    }

    /**
     * 已授权(已合作)节点列表 — used by the create-project collaborator picker.
     * Queries the node table directly (no gRPC to Kuscia) to avoid SSL/cert issues in dev mode.
     */
    @GetMapping("list/approved")
    public SecretPadResponse<List<NodeVO>> listApproved() {
        String ownerId = currentNode();
        List<BfiaNodeDO> allNodes = bfiaNodeRepository.findAll();
        List<NodeVO> nodes = allNodes.stream()
                .map(this::toNodeVO)
                .filter(n -> !ownerId.equals(n.getNodeId()))
                .collect(Collectors.toList());
        return SecretPadResponse.success(nodes);
    }

    @GetMapping("list/configured")
    public SecretPadResponse<List<NodeVO>> listConfigured() {
        String owner = currentNode();
        if (!channel.ready() || !channel.local().equals(owner)) {
            return SecretPadResponse.success(List.of());
        }
        return SecretPadResponse.success(List.of(toNodeVO(BfiaNodeDO.builder().nodeId(channel.peer())
                .name(channel.peer()).netAddress(channel.address()).build())));
    }

    /**
     * 全部节点列表 (管理层 §3.6 节点信息查询). Direct DB query, no gRPC.
     */
    @PostMapping("list")
    public SecretPadResponse<List<NodeVO>> listNode() {
        List<NodeVO> nodes = bfiaNodeRepository.findAll().stream()
                .map(this::toNodeVO)
                .collect(Collectors.toList());
        return SecretPadResponse.success(nodes);
    }

    /**
     * 合作申请待办分页 (管理层 §3.5). 查询本节点收到的合作申请记录。
     */
    @GetMapping("list/apply")
    public SecretPadResponse<Map<String, Object>> listApply(
            @RequestParam(value = "page_num", defaultValue = "1") int pageNum,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        PageRequest pageRequest = PageRequest.of(pageNum - 1, pageSize);
        String owner = currentNode();
        Page<BfiaContractDO> page = bfiaContractRepository.findForParty(owner, pageRequest);
        List<Map<String, Object>> records = page.getContent().stream()
                .map(contract -> toRouterVO(contract, owner))
                .collect(Collectors.toList());
        Map<String, Object> pageResp = new LinkedHashMap<>();
        pageResp.put("records", records);
        pageResp.put("total", page.getTotalElements());
        return SecretPadResponse.success(pageResp);
    }

    /**
     * 节点合作申请 (管理层 §3.1 合作申请接口).
     * 保存合作申请记录，状态为 UNAPPROVED（待审批）。
     */
    @PostMapping("contract/apply")
    @ApiResource(code = ApiResourceCodeConstants.NODE_CREATE)
    public SecretPadResponse<Boolean> contractApply(@RequestBody Map<String, Object> body) {
        cooperation.apply(currentNode(), (String) body.get("node_id"),
                (String) body.get("pad_address"), Long.parseLong(String.valueOf(body.get("expired_time"))));
        return SecretPadResponse.success(Boolean.TRUE);
    }

    /**
     * 更新合作意向 (管理层 §3.2). 审批合作申请，更新状态为 APPROVED/REJECTED。
     * 审批通过后自动将合作节点写入 bfia_node 表，供项目协作时选择。
     */
    @PostMapping("contract/confirm")
    @ApiResource(code = ApiResourceCodeConstants.NODE_CREATE)
    public SecretPadResponse<Boolean> contractConfirm(@RequestBody Map<String, Object> body) {
        cooperation.confirm(currentNode(), (String) body.get("contract_id"), (String) body.get("status"));
        return SecretPadResponse.success(Boolean.TRUE);
    }

    private String currentNode() {
        var user = UserContext.getUser();
        if (Boolean.TRUE.equals(user.getVirtualUserForNode()) || !channel.local().equals(user.getPlatformNodeId())) {
            throw new IllegalArgumentException("操作需要本方平台用户身份");
        }
        // The login owner ID can be an institution ID, not a Kuscia node ID.
        return user.getPlatformNodeId();
    }

    /**
     * 将 BfiaContractDO 转换为前端期望的 NodeRouterVO 格式。
     */
    private Map<String, Object> toRouterVO(BfiaContractDO contract, String owner) {
        boolean inbound = owner.equals(contract.getNodeId());
        boolean outbound = owner.equals(contract.getInitiatorNodeId());
        String counterpart = inbound ? contract.getInitiatorNodeId() : contract.getNodeId();
        if (!inbound && !outbound && channel.peer().equals(contract.getInitiatorNodeId())) {
            counterpart = channel.peer();
        }
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("contractId", contract.getContractId());
        vo.put("name", inbound ? counterpart : StringUtils.defaultIfBlank(contract.getName(), counterpart));
        vo.put("nodeId", counterpart);
        vo.put("direction", inbound ? "INBOUND" : outbound ? "OUTBOUND" : null);
        vo.put("canConfirm", cooperation.canConfirm(contract, owner));
        vo.put("initiatorNodeName", contract.getInitiatorNodeId());
        vo.put("initiatorNodeId", contract.getInitiatorNodeId());
        vo.put("expiredTime", contract.getExpiredTime());
        vo.put("authType", contract.getAuthType());
        vo.put("address", StringUtils.defaultIfBlank(contract.getAddress(), contract.getPadAddress()));
        vo.put("status", contract.getStatus());
        vo.put("description", contract.getDescription());
        vo.put("instId", contract.getInstId());
        vo.put("institution", contract.getInstitution());
        vo.put("padAddress", contract.getPadAddress());
        vo.put("gmtCreate", contract.getGmtCreate() != null ? contract.getGmtCreate().toEpochSecond(java.time.ZoneOffset.UTC) : null);
        return vo;
    }
}
