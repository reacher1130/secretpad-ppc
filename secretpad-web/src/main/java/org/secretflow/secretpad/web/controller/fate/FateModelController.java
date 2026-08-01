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
import org.secretflow.secretpad.service.fate.FateModelService;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.model.fate.FateModelRequest;
import org.secretflow.secretpad.service.model.fate.FateModelVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * FATE 2.x model/result reference management endpoints.
 *
 * <p>Stores references to FATE models and evaluation outputs so that they can
 * be displayed in SecretPad without querying FATE-Flow on every page load.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1alpha1/fate/model")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "secretpad.fate", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FateModelController {

    private final FateModelService fateModelService;

    @PostMapping("create")
    public SecretPadResponse<FateModelVO> create(@RequestBody FateModelRequest request) {
        return SecretPadResponse.success(fateModelService.create(request));
    }

    @PostMapping("update")
    public SecretPadResponse<FateModelVO> update(
            @RequestParam("modelId") String modelId,
            @RequestBody FateModelRequest request) {
        return SecretPadResponse.success(fateModelService.update(modelId, request));
    }

    @PostMapping("delete")
    public SecretPadResponse<Boolean> delete(@RequestParam("modelId") String modelId) {
        fateModelService.delete(modelId);
        return SecretPadResponse.success(true);
    }

    @GetMapping("get")
    public SecretPadResponse<FateModelVO> get(@RequestParam("modelId") String modelId) {
        return SecretPadResponse.success(fateModelService.get(modelId));
    }

    @GetMapping("list")
    public SecretPadResponse<List<FateModelVO>> list(
            @RequestParam(value = "jobId", required = false) String jobId,
            @RequestParam(value = "clusterId", required = false) String clusterId) {
        if (jobId != null && !jobId.isBlank()) {
            return SecretPadResponse.success(fateModelService.listByJobId(jobId));
        }
        return SecretPadResponse.success(fateModelService.listByClusterId(clusterId));
    }
}
