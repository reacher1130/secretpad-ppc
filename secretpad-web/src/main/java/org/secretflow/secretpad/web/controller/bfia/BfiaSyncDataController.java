package org.secretflow.secretpad.web.controller.bfia;

import lombok.RequiredArgsConstructor;
import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.service.bfia.BfiaDataService;
import org.secretflow.secretpad.service.model.bfia.*;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;

/**
 * BFIA node-to-node dataset interconnect controller (east-west).
 *
 * <p>Implements the management-layer dataset interconnect interfaces defined by the InterOp spec
 * (管理层 API 第 4 章 数据集互通接口: 4.3 授权申请 / 4.4 授权意向更新 / 4.5 授权解除 / 4.6 状态查询 / 4.1 列表 / 4.2 明细).
 * Invoked by peer SecretPad nodes via the kuscia network, authenticated by {@code kuscia-origin-source}.
 * The PAD-facing dataset API lives in {@link BfiaDataController} under {@code /api/v1bfia/dataset}.
 *
 * @author gaofeng
 */
@RestController
@RequestMapping("/v1/interconn/data/")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaSyncDataController {

    private final BfiaDataService bfiaDataService;

    @PostMapping("audit/apply")
    public SecretPadResponse<Boolean> apply(@RequestHeader("kuscia-origin-source") String nodeId, @RequestBody String p) {
        BfiaDatasetApplyRequest req = JsonUtils.toJavaObject(p, BfiaDatasetApplyRequest.class);
        return SecretPadResponse.success(bfiaDataService.apply(req, nodeId));
    }

    @PostMapping("audit/confirm")
    public SecretPadResponse<Boolean> confirm(@RequestHeader("kuscia-origin-source") String nodeId, @RequestBody String p) {
        BfiaConfirmRequest request = JsonUtils.toJavaObject(p, BfiaConfirmRequest.class);
        return SecretPadResponse.success(bfiaDataService.confirm(request, nodeId));
    }

    @PostMapping("audit/terminate")
    public SecretPadResponse<Boolean> terminate(@RequestHeader("kuscia-origin-source") String nodeId, @RequestBody String p) {
        BfiaAuditIdRequest req = JsonUtils.toJavaObject(p, BfiaAuditIdRequest.class);
        return SecretPadResponse.success(bfiaDataService.terminate(req));
    }

    @GetMapping("audit/query")
    public SecretPadResponse<HashMap<String, String>> query(@RequestHeader("kuscia-origin-source") String nodeId, @RequestParam("audit_id") String auditId) {
        return SecretPadResponse.success(bfiaDataService.queryAuditStatus(auditId));
    }

    @GetMapping("list")
    public SecretPadResponse<Object> list(@RequestHeader("kuscia-origin-source") String nodeId,
                                          @RequestParam("contract_id") String contractId,
                                          @RequestParam("page_num") int pageNum,
                                          @RequestParam("page_size") int pageSize) {
        BfiaListDatatableRequest req = new BfiaListDatatableRequest();
        req.setPageNum(pageNum);
        req.setPageSize(pageSize);
        Object page = bfiaDataService.listDataset(contractId, req, nodeId);
        return SecretPadResponse.success(page);
    }

    @GetMapping("query")
    public SecretPadResponse<BfiaDatasetVO> queryDataset(@RequestHeader("kuscia-origin-source") String nodeId,
                                                         @RequestParam("contract_id") String contractId,
                                                         @RequestParam("dataset_id") String datasetId) {
        return SecretPadResponse.success(bfiaDataService.queryDataset(contractId, datasetId));
    }
}
