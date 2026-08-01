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
import org.secretflow.secretpad.service.fate.FateClusterService;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.model.fate.FateClusterRequest;
import org.secretflow.secretpad.service.model.fate.FateClusterVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * FATE 2.x cluster management endpoints.
 *
 * <p>Provides a self-contained CRUD API for registering FATE-Flow endpoints.
 * These endpoints are isolated from native/BFIA flows and only touch the
 * {@code fate_cluster} table.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1alpha1/fate/cluster")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "secretpad.fate", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FateClusterController {

    private final FateClusterService fateClusterService;

    @PostMapping("create")
    public SecretPadResponse<FateClusterVO> create(@RequestBody FateClusterRequest request) {
        return SecretPadResponse.success(fateClusterService.create(request));
    }

    @PostMapping("update")
    public SecretPadResponse<FateClusterVO> update(
            @RequestParam("clusterId") String clusterId,
            @RequestBody FateClusterRequest request) {
        return SecretPadResponse.success(fateClusterService.update(clusterId, request));
    }

    @PostMapping("delete")
    public SecretPadResponse<Boolean> delete(@RequestParam("clusterId") String clusterId) {
        fateClusterService.delete(clusterId);
        return SecretPadResponse.success(true);
    }

    @GetMapping("get")
    public SecretPadResponse<FateClusterVO> get(@RequestParam("clusterId") String clusterId) {
        return SecretPadResponse.success(fateClusterService.get(clusterId));
    }

    @GetMapping("list")
    public SecretPadResponse<List<FateClusterVO>> list(
            @RequestParam(value = "nodeId", required = false) String nodeId) {
        return SecretPadResponse.success(fateClusterService.list(nodeId));
    }
}
