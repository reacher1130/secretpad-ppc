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

package org.secretflow.secretpad.web.controller.fate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.secretflow.secretpad.service.fate.FateDatasetService;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.model.fate.FateDatasetRequest;
import org.secretflow.secretpad.service.model.fate.FateDatasetVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * FATE 2.x dataset reference management endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1alpha1/fate/dataset")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "secretpad.fate", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FateDatasetController {

    private final FateDatasetService fateDatasetService;

    @PostMapping("create")
    public SecretPadResponse<FateDatasetVO> create(@RequestBody FateDatasetRequest request) {
        return SecretPadResponse.success(fateDatasetService.create(request));
    }

    @PostMapping("update")
    public SecretPadResponse<FateDatasetVO> update(
            @RequestParam("datasetId") String datasetId,
            @RequestBody FateDatasetRequest request) {
        return SecretPadResponse.success(fateDatasetService.update(datasetId, request));
    }

    @PostMapping("delete")
    public SecretPadResponse<Boolean> delete(@RequestParam("datasetId") String datasetId) {
        fateDatasetService.delete(datasetId);
        return SecretPadResponse.success(true);
    }

    @GetMapping("get")
    public SecretPadResponse<FateDatasetVO> get(@RequestParam("datasetId") String datasetId) {
        return SecretPadResponse.success(fateDatasetService.get(datasetId));
    }

    @GetMapping("list")
    public SecretPadResponse<List<FateDatasetVO>> list(
            @RequestParam(value = "clusterId", required = false) String clusterId,
            @RequestParam(value = "nodeId", required = false) String nodeId) {
        if (clusterId != null && !clusterId.isBlank()) {
            return SecretPadResponse.success(fateDatasetService.listByClusterId(clusterId));
        }
        return SecretPadResponse.success(fateDatasetService.listByNodeId(nodeId));
    }
}
