package org.secretflow.secretpad.service.bfia;

import org.secretflow.secretpad.service.model.bfia.BfiaAuditIdRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaConfirmRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaDatasetApplyRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaDatasetVO;
import org.secretflow.secretpad.service.model.bfia.BfiaListDatatableRequest;
import org.secretflow.secretpad.service.model.common.SecretPadPageResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface BfiaDataService {
    Boolean apply(BfiaDatasetApplyRequest request, String nodeId);
    Boolean confirm(BfiaConfirmRequest request, String nodeId);
    Boolean terminate(BfiaAuditIdRequest request);
    HashMap<String,String> queryAuditStatus(String auditId);
    SecretPadPageResponse<BfiaDatasetVO> listDataset(String contractId, BfiaListDatatableRequest request, String nodeId);
    BfiaDatasetVO queryDataset(String contractId, String datasetId);
    Boolean pubDataset(String nodeId, String contractId, String datasetId);

    /** 创建数据集 */
    BfiaDatasetVO createDataset(String nodeId, String name, String description, String contractId);
    /** 更新数据集元数据 (fileName/fieldNames/hasLabel/namespace/rowCount) */
    BfiaDatasetVO updateDataset(String nodeId, String datasetId, String fileName, String fieldNames, Boolean hasLabel, String namespace, Long rowCount);
    /** 删除数据集 */
    Boolean deleteDataset(String nodeId, String datasetId);
    /** 授权数据集给项目 */
    Boolean authorize(String nodeId, String datasetId, String projectId, String authScope, Long timeLimit);
    /** 查询已授权数据集列表 */
    SecretPadPageResponse<BfiaDatasetVO> listApproved(String nodeId, int pageNum, int pageSize);
    /** 查询合作方公开数据列表 */
    SecretPadPageResponse<BfiaDatasetVO> listPub(String contractId, int pageNum, int pageSize);
    /** 查询授权历史 */
    List<Map<String, String>> approvedHistory(String nodeId, String datasetId, String status);
    /** 查询申请历史 */
    List<Map<String, String>> appliedHistory(String nodeId, String datasetId, String status);
}


