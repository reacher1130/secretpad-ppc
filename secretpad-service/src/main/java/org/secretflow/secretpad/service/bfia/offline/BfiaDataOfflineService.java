package org.secretflow.secretpad.service.bfia.offline;

import org.secretflow.secretpad.service.model.bfia.BfiaAuditIdRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaConfirmRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaDatasetApplyRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaDatasetVO;
import org.secretflow.secretpad.service.model.bfia.BfiaListDatatableRequest;
import org.secretflow.secretpad.service.model.common.SecretPadPageResponse;

import java.util.HashMap;

public interface BfiaDataOfflineService {
    Boolean apply(BfiaDatasetApplyRequest request, String nodeId);
    Boolean confirm(BfiaConfirmRequest request, String nodeId);
    Boolean terminate(BfiaAuditIdRequest request);
    HashMap<String,String> queryAuditStatus(String auditId);
    SecretPadPageResponse<BfiaDatasetVO> listDataset(String contractId, BfiaListDatatableRequest request, String nodeId);
    BfiaDatasetVO queryDataset(String contractId, String datasetId);
    Boolean pubDataset(String nodeId, String contractId, String datasetId);
}


