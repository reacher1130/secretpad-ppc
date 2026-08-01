package org.secretflow.secretpad.service.bfia;

import org.secretflow.secretpad.service.model.bfia.BfiaConfirmRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaProjectApplyRequest;
import org.secretflow.secretpad.service.model.bfia.BfiaProjectListInfo;
import org.secretflow.secretpad.service.model.bfia.BfiaProjectVO;
import org.secretflow.secretpad.service.model.common.SecretPadPageResponse;

import java.util.HashMap;

public interface BfiaProjectService {
    HashMap<String, String> apply(BfiaProjectApplyRequest request, String nodeId);
    Boolean confirm(BfiaConfirmRequest request, String nodeId);
    HashMap<String, String> queryAuditStatus(String auditId);
    SecretPadPageResponse<BfiaProjectListInfo> listProject(String contractId, int pageNum, int pageSize);
    BfiaProjectVO queryProject(String contractId, String projectId);
}


