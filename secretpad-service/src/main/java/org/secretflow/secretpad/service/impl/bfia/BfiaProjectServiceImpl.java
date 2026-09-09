package org.secretflow.secretpad.service.impl.bfia;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.secretflow.secretpad.common.errorcode.SystemErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.persistence.entity.NodeDO;
import org.secretflow.secretpad.persistence.entity.ProjectNodeDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaAuditDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaDatasetDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaProjectDO;
import org.secretflow.secretpad.persistence.repository.NodeRepository;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaAuditRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaDatasetRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaProjectRepository;
import org.secretflow.secretpad.service.bfia.BfiaProjectService;
import org.secretflow.secretpad.service.model.bfia.*;
import org.secretflow.secretpad.service.model.common.SecretPadPageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaProjectServiceImpl implements BfiaProjectService {

    private final org.secretflow.secretpad.persistence.repository.bfia.BfiaProjectRepositoryOffline projectRepository;
    private final BfiaAuditRepository auditRepository;
    private final NodeRepository nodeRepository;
    private final ProjectNodeRepository projectNodeRepository;
    private final BfiaDatasetRepository bfiaDatasetRepository;
    private final org.secretflow.secretpad.persistence.repository.bfia.BfiaContractRepository bfiaContractRepository;
    private final org.secretflow.secretpad.service.bfia.BfiaInterconnClient bfiaInterconnClient;

    @org.springframework.beans.factory.annotation.Value("${secretpad.node-id}")
    private String localNodeId;

    @Override
    @Transactional(transactionManager = "bfiaTransactionManager")
    public HashMap<String, String> apply(BfiaProjectApplyRequest request, String nodeId) {
        if (request == null || StringUtils.isBlank(request.getName())) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "项目名称为空");
        }
        String projectId = StringUtils.defaultIfBlank(request.getProjectId(), UUID.randomUUID().toString().replaceAll("-", ""));
        BfiaProjectDO project = BfiaProjectDO.builder()
                .projectId(projectId)
                .contractId(request.getContractId())
                .name(request.getName())
                .type(request.getType())
                .datasets("[]")
                .description(request.getDescription())
                .build();
        projectRepository.save(project);

        String auditId = StringUtils.defaultIfBlank(request.getAuditId(), UUID.randomUUID().toString().replaceAll("-", ""));
        BfiaAuditDO audit = BfiaAuditDO.builder()
                .auditId(auditId)
                .contractId(request.getContractId())
                .applyNodeId(nodeId)
                .type("PROJECT")
                .projectId(projectId)
                .status("UNAPPROVED")
                .build();
        auditRepository.save(audit);

        HashMap<String, String> result = new HashMap<>();
        result.put("audit_id", auditId);
        result.put("project_id", projectId);
        return result;
    }

    @Override
    @Transactional(transactionManager = "bfiaTransactionManager")
    public Boolean confirm(BfiaConfirmRequest request, String nodeId) {
        if (request == null || StringUtils.isBlank(request.getAuditId())) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "审批ID为空");
        }
        BfiaAuditDO audit = auditRepository.findById(request.getAuditId()).orElse(null);
        if (audit == null) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "审批不存在");
        }
        if (!java.util.Set.of("APPROVED", "REJECTED").contains(StringUtils.defaultString(request.getStatus()))) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "无效的项目审批状态");
        }
        if (StringUtils.equals(nodeId, audit.getApplyNodeId())
                || !projectNodeRepository.findProjectNodesByProjectId(audit.getProjectId()).contains(nodeId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "只有受邀参与方可以审批项目");
        }
        audit.setStatus(request.getStatus());
        auditRepository.save(audit);
        return true;
    }

    @Override
    public HashMap<String, String> queryAuditStatus(String auditId) {
        BfiaAuditDO audit = auditRepository.findById(auditId).orElse(null);
        if (audit == null) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "审批不存在");
        }
        HashMap<String, String> result = new HashMap<>();
        result.put("audit_id", audit.getAuditId());
        result.put("status", audit.getStatus());
        return result;
    }

    @Override
    public SecretPadPageResponse<BfiaProjectListInfo> listProject(String contractId, int pageNum, int pageSize) {
        Page<BfiaProjectDO> page;
        org.springframework.data.domain.Pageable pageable = PageRequest.of(pageNum - 1, pageSize);
        if (StringUtils.isBlank(contractId)) {
            page = projectRepository.findAll(pageable);
        } else {
            page = projectRepository.findAllByContractId(contractId, pageable);
        }
        return SecretPadPageResponse.toPage(
                page.map(p -> {
                    BfiaProjectListInfo info = new BfiaProjectListInfo();
                    info.setProjectId(p.getProjectId());
                    info.setName(p.getName());
                    info.setDescription(p.getDescription());
                    info.setType(p.getType());
                    if (p.getGmtCreate() != null) {
                        info.setGmtCreate(p.getGmtCreate().atZone(ZoneOffset.systemDefault()).toEpochSecond());
                    }
                    List<BfiaAuditDO> audits = auditRepository.findByProjectId(p.getProjectId());
                    info.setStatus("UNAPPROVED");
                    if (!audits.isEmpty()) {
                        BfiaAuditDO audit = audits.get(0);
                        info.setAuditId(audit.getAuditId());
                        info.setStatus(audit.getStatus());
                        info.setInitiatorNodeId(audit.getApplyNodeId());
                        info.setInitiatorName(audit.getApplyNodeId());
                    }
                    List<BfiaProjectListInfo.NodeInfo> nodeInfos = projectParticipants(p.getProjectId());
                    info.setNodes(nodeInfos);
                    info.setPartyVoteInfos(nodeInfos.stream()
                            .filter(n -> !StringUtils.equals(n.getNodeId(), info.getInitiatorNodeId()))
                            .collect(Collectors.toList()));
                    return info;
                }).getContent(),
                page.getTotalElements());
    }

    @Override
    public BfiaProjectVO queryProject(String contractId, String projectId) {
        BfiaProjectDO p = projectRepository.findById(projectId).orElse(null);
        if (p == null) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "项目不存在");
        }
        // Build nodes+datatables so the DAG editor's DefaultTableSelect (data_source config
        // panel) can populate the input-dataset dropdown. The frontend reads:
        //   data.nodes[].nodeName / data.nodes[].datatables[].datatableId / datatableName
        List<BfiaProjectVO.NodeVO> nodeVOs = buildProjectNodes(projectId);
        return BfiaProjectVO.builder()
                .projectId(p.getProjectId())
                .name(p.getName())
                .description(p.getDescription())
                .type(p.getType())
                .computeMode("MPC")
                .nodes(nodeVOs)
                .build();
    }

    /**
     * Build the participating-node list with their datatables for a BFIA project.
     * <p>
     * Nodes come from {@code project_node} (written during project creation/sync).
     * Datasets come from {@code bfia_dataset} (created via the BFIA data management UI).
     * Each BFIA dataset is mapped to the frontend-expected datatable shape:
     * {@code datatableId = datasetId}, {@code datatableName = name}.
     */
    private List<BfiaProjectVO.NodeVO> buildProjectNodes(String projectId) {
        // Only explicitly enrolled project participants may supply data. The native
        // node table also contains Kimi and must never define BFIA membership.
        List<BfiaProjectListInfo.NodeInfo> allNodes = projectParticipants(projectId);
        List<BfiaDatasetDO> localDatasets = bfiaDatasetRepository.findAll();

        // Resolve the BFIA contract id for this project (needed for remote dataset queries).
        String contractId = "";
        if (projectId != null) {
            BfiaProjectDO project = projectRepository.findById(projectId).orElse(null);
            if (project != null && project.getContractId() != null) {
                contractId = project.getContractId();
            }
        }

        // Build contractId -> nodeId mapping so each local dataset can be associated with
        // its owning node via bfia_dataset.contract_id -> bfia_contract.node_id.
        java.util.Map<String, String> contractNodeMap = bfiaContractRepository.findAll().stream()
                .collect(Collectors.toMap(
                        c -> c.getContractId(),
                        c -> c.getNodeId() != null ? c.getNodeId() : "",
                        (a, b) -> a));

        String finalContractId = contractId;
        return allNodes.stream().map(node -> {
            List<BfiaProjectVO.DatatableVO> nodeDatatables = new java.util.ArrayList<>();

            if (node.getNodeId().equals(localNodeId)) {
                // Local node: use local BFIA datasets filtered by contract's nodeId.
                nodeDatatables = localDatasets.stream()
                        .filter(ds -> {
                            String dsNodeId = contractNodeMap.get(ds.getContractId());
                            return dsNodeId == null || dsNodeId.isEmpty() || dsNodeId.equals(node.getNodeId());
                        })
                        .map(ds -> BfiaProjectVO.DatatableVO.builder()
                                .datatableId(ds.getDatasetId())
                                .datatableName(ds.getName())
                                .build())
                        .collect(Collectors.toList());
            } else {
                // Remote node: query its datasets via the interconn data list endpoint.
                java.util.List<java.util.Map<String, Object>> remoteDs =
                        bfiaInterconnClient.listRemoteDatasets(localNodeId, node.getNodeId(), finalContractId);
                nodeDatatables = remoteDs.stream()
                        .map(ds -> BfiaProjectVO.DatatableVO.builder()
                                .datatableId((String) ds.get("datasetId"))
                                .datatableName((String) ds.get("name"))
                                .build())
                        .collect(Collectors.toList());
            }

            return BfiaProjectVO.NodeVO.builder()
                    .nodeId(node.getNodeId())
                    .nodeName(node.getNodeName())
                    .datatables(nodeDatatables)
                    .build();
        }).collect(Collectors.toList());
    }

    private List<BfiaProjectListInfo.NodeInfo> projectParticipants(String projectId) {
        return projectNodeRepository.findProjectNodesByProjectId(projectId).stream()
                .distinct().map(id -> new BfiaProjectListInfo.NodeInfo(id, id))
                .collect(Collectors.toList());
    }
}

