package org.secretflow.secretpad.service.impl.bfia;

import lombok.RequiredArgsConstructor;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaAuditDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaDatasetDO;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaAuditRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaDatasetRepository;
import org.secretflow.secretpad.service.bfia.BfiaDataService;
import org.secretflow.secretpad.service.model.bfia.BfiaConfirmRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaDatasetVO;
import org.secretflow.secretpad.service.model.bfia.BfiaListDatatableRequest;
import org.secretflow.secretpad.service.model.common.SecretPadPageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaDataServiceImpl implements BfiaDataService {

    private final BfiaDatasetRepository datasetRepository;
    private final BfiaAuditRepository auditRepository;

    @Override
    public Boolean apply(org.secretflow.secretpad.service.model.bfia.BfiaDatasetApplyRequest request, String nodeId) {
        BfiaAuditDO audit = BfiaAuditDO.builder()
                .auditId(UUID.randomUUID().toString())
                .contractId(request.getContractId() != null ? request.getContractId() : "")
                .applyNodeId(nodeId)
                .type("DATASET")
                .datasetId(request.getDatasetId())
                .status("PENDING")
                .build();
        auditRepository.save(audit);
        return true;
    }

    @Override
    public Boolean confirm(BfiaConfirmRequest request, String nodeId) {
        BfiaAuditDO audit = auditRepository.findById(request.getAuditId()).orElse(null);
        if (audit == null) {
            return false;
        }
        audit.setStatus("APPROVED".equalsIgnoreCase(request.getStatus()) ? "APPROVED" : "REJECTED");
        auditRepository.save(audit);
        return true;
    }

    @Override
    public Boolean terminate(org.secretflow.secretpad.service.model.bfia.BfiaAuditIdRequest request) {
        BfiaAuditDO audit = auditRepository.findById(request.getAuditId()).orElse(null);
        if (audit == null) {
            return false;
        }
        audit.setStatus("TERMINATED");
        auditRepository.save(audit);
        return true;
    }

    @Override
    public HashMap<String, String> queryAuditStatus(String auditId) {
        HashMap<String, String> result = new HashMap<>();
        result.put("audit_id", auditId);
        BfiaAuditDO audit = auditRepository.findById(auditId).orElse(null);
        result.put("status", audit != null ? audit.getStatus() : "NOT_FOUND");
        return result;
    }

    @Override
    public SecretPadPageResponse<BfiaDatasetVO> listDataset(String contractId, BfiaListDatatableRequest request, String nodeId) {
        Page<BfiaDatasetDO> page = datasetRepository.findAll(PageRequest.of(request.getPageNum() - 1, request.getPageSize()));
        List<BfiaDatasetVO> list = page.map(this::toVO).getContent();
        return SecretPadPageResponse.toPage(list, page.getTotalElements());
    }

    @Override
    public BfiaDatasetVO queryDataset(String contractId, String datasetId) {
        BfiaDatasetDO d = datasetRepository.findById(datasetId).orElse(null);
        return d == null ? null : toVO(d);
    }

    @Override
    public Boolean pubDataset(String nodeId, String contractId, String datasetId) {
        BfiaDatasetDO d = datasetRepository.findById(datasetId).orElse(null);
        if (d == null) {
            return false;
        }
        d.setStatus("PUBLISHED");
        if (contractId != null && !contractId.isBlank()) {
            d.setContractId(contractId);
        }
        datasetRepository.save(d);
        return true;
    }

    @Override
    public BfiaDatasetVO createDataset(String nodeId, String name, String description, String contractId) {
        String datasetId = "ds_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        BfiaDatasetDO d = BfiaDatasetDO.builder()
                .datasetId(datasetId)
                .contractId(contractId != null ? contractId : nodeId)
                .name(name)
                .description(description)
                .status("DRAFT")
                .build();
        datasetRepository.save(d);
        return toVO(d);
    }

    @Override
    public BfiaDatasetVO updateDataset(String nodeId, String datasetId, String fileName, String fieldNames, Boolean hasLabel, String namespace, Long rowCount) {
        BfiaDatasetDO d = datasetRepository.findById(datasetId).orElse(null);
        if (d == null) {
            throw new IllegalArgumentException("dataset not found: " + datasetId);
        }
        if (fileName != null) {
            d.setFileName(fileName);
        }
        if (fieldNames != null) {
            d.setFieldNames(fieldNames);
        }
        if (hasLabel != null) {
            d.setHasLabel(hasLabel);
        }
        if (namespace != null && !namespace.isBlank()) {
            d.setNamespace(namespace);
        }
        if (rowCount != null) {
            d.setRowCount(rowCount);
        }
        // When the dataset has both fileName and fieldNames populated, mark it as Available
        // so the BFIA frontend (which filters on status === 'Available') displays it.
        if (d.getFileName() != null && !d.getFileName().isBlank()
                && d.getFieldNames() != null && !d.getFieldNames().isBlank()) {
            d.setStatus("Available");
        }
        datasetRepository.save(d);
        return toVO(d);
    }

    @Override
    public Boolean deleteDataset(String nodeId, String datasetId) {
        BfiaDatasetDO d = datasetRepository.findById(datasetId).orElse(null);
        if (d == null) {
            return false;
        }
        datasetRepository.delete(d);
        return true;
    }

    @Override
    public Boolean authorize(String nodeId, String datasetId, String projectId, String authScope, Long timeLimit) {
        BfiaDatasetDO d = datasetRepository.findById(datasetId).orElse(null);
        if (d == null) {
            return false;
        }
        BfiaAuditDO audit = BfiaAuditDO.builder()
                .auditId(UUID.randomUUID().toString())
                .contractId(d.getContractId())
                .applyNodeId(nodeId)
                .type("DATASET_AUTH")
                .datasetId(datasetId)
                .projectId(projectId)
                .status("APPROVED")
                .authScope(authScope)
                .timeLimit(timeLimit)
                .build();
        auditRepository.save(audit);
        d.setStatus("AUTHORIZED");
        datasetRepository.save(d);
        return true;
    }

    @Override
    public SecretPadPageResponse<BfiaDatasetVO> listApproved(String nodeId, int pageNum, int pageSize) {
        List<BfiaAuditDO> audits = auditRepository.findByStatus("APPROVED");
        Set<String> datasetIds = audits.stream()
                .map(BfiaAuditDO::getDatasetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<BfiaDatasetVO> list = datasetIds.stream()
                .map(id -> datasetRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .map(this::toVO)
                .collect(Collectors.toList());
        int total = list.size();
        int from = Math.min((pageNum - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        return SecretPadPageResponse.toPage(list.subList(from, to), (long) total);
    }

    @Override
    public SecretPadPageResponse<BfiaDatasetVO> listPub(String contractId, int pageNum, int pageSize) {
        Page<BfiaDatasetDO> page;
        if (contractId != null && !contractId.isBlank()) {
            page = datasetRepository.findAllByContractId(contractId, PageRequest.of(pageNum - 1, pageSize));
        } else {
            page = datasetRepository.findAll(PageRequest.of(pageNum - 1, pageSize));
        }
        List<BfiaDatasetVO> list = page.map(this::toVO).getContent();
        return SecretPadPageResponse.toPage(list, page.getTotalElements());
    }

    @Override
    public List<Map<String, String>> approvedHistory(String nodeId, String datasetId, String status) {
        List<BfiaAuditDO> audits = auditRepository.findAll();
        return audits.stream()
                .filter(a -> "DATASET".equals(a.getType()) || "DATASET_AUTH".equals(a.getType()))
                .filter(a -> datasetId == null || datasetId.equals(a.getDatasetId()))
                .filter(a -> status == null || status.isBlank() || status.equals(a.getStatus()))
                .map(this::auditToMap)
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, String>> appliedHistory(String nodeId, String datasetId, String status) {
        List<BfiaAuditDO> audits = auditRepository.findAll();
        return audits.stream()
                .filter(a -> nodeId == null || nodeId.equals(a.getApplyNodeId()))
                .filter(a -> datasetId == null || datasetId.equals(a.getDatasetId()))
                .filter(a -> status == null || status.isBlank() || status.equals(a.getStatus()))
                .map(this::auditToMap)
                .collect(Collectors.toList());
    }

    private BfiaDatasetVO toVO(BfiaDatasetDO d) {
        BfiaDatasetVO vo = new BfiaDatasetVO();
        vo.setDatasetId(d.getDatasetId());
        vo.setName(d.getName());
        vo.setDescription(d.getDescription());
        vo.setStatus(d.getStatus());
        vo.setNamespace(d.getNamespace());
        vo.setFileName(d.getFileName());
        vo.setFieldNames(d.getFieldNames());
        vo.setHasLabel(d.getHasLabel());
        vo.setRowCount(d.getRowCount());
        // Frontend compatibility: tableName mirrors name, datasourceType defaults to LOCAL
        vo.setTableName(d.getName());
        vo.setDatasourceType("LOCAL");
        if (d.getGmtCreate() != null) {
            vo.setGmtCreate(d.getGmtCreate().atZone(java.time.ZoneOffset.systemDefault()).toEpochSecond());
        }
        return vo;
    }

    private Map<String, String> auditToMap(BfiaAuditDO a) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("audit_id", a.getAuditId());
        m.put("dataset_id", a.getDatasetId() != null ? a.getDatasetId() : "");
        m.put("status", a.getStatus());
        m.put("type", a.getType());
        m.put("apply_node_id", a.getApplyNodeId());
        m.put("project_id", a.getProjectId() != null ? a.getProjectId() : "");
        return m;
    }
}
