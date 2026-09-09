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
import org.secretflow.secretpad.service.fate.FateJobService;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.model.fate.FateJobRequest;
import org.secretflow.secretpad.service.model.fate.FateJobVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * FATE 2.x job management endpoints.
 *
 * <p>Allows users to submit BFIA jobs to a registered FATE cluster, query their
 * latest status, stop them, and list them by cluster or project.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1alpha1/fate/job")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "secretpad.fate", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FateJobController {

    private final FateJobService fateJobService;

    @PostMapping("submit")
    public SecretPadResponse<FateJobVO> submit(@RequestBody FateJobRequest request) {
        FateJobVO job = fateJobService.submit(request);
        if ("FAILED".equals(job.getStatus())) {
            return new SecretPadResponse<>(new SecretPadResponse.SecretPadResponseStatus(
                    202011902, "FATE 作业提交失败：" + job.getErrMsg()), job);
        }
        return SecretPadResponse.success(job);
    }

    @PostMapping("stop")
    public SecretPadResponse<FateJobVO> stop(@RequestParam("jobId") String jobId) {
        return SecretPadResponse.success(fateJobService.stop(jobId));
    }

    @GetMapping("get")
    public SecretPadResponse<FateJobVO> get(@RequestParam("jobId") String jobId) {
        return SecretPadResponse.success(fateJobService.get(jobId));
    }

    @GetMapping("list")
    public SecretPadResponse<List<FateJobVO>> list(
            @RequestParam(value = "clusterId", required = false) String clusterId,
            @RequestParam(value = "projectId", required = false) String projectId) {
        if (clusterId != null && !clusterId.isBlank()) {
            return SecretPadResponse.success(fateJobService.listByClusterId(clusterId));
        }
        return SecretPadResponse.success(fateJobService.listByProjectId(projectId));
    }
}
