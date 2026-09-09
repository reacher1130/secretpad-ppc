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

package org.secretflow.secretpad.service.impl.fate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.secretflow.secretpad.common.errorcode.SystemErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.persistence.entity.fate.FateClusterDO;
import org.secretflow.secretpad.persistence.entity.fate.FateJobDO;
import org.secretflow.secretpad.persistence.repository.fate.FateClusterRepository;
import org.secretflow.secretpad.persistence.repository.fate.FateJobRepository;
import org.secretflow.secretpad.kuscia.v1alpha1.service.KusciaJobService;
import org.secretflow.secretpad.manager.integration.job.AbstractJobManager;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.fate.FateJobService;
import org.secretflow.secretpad.service.graph.converter.FateKusciaJobConverter;
import org.secretflow.secretpad.service.model.fate.FateJobRequest;
import org.secretflow.secretpad.service.model.fate.FateJobVO;
import org.secretflow.v1alpha1.kusciaapi.Job;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of {@link FateJobService}.
 *
 * <p>Submits FATE 2.x jobs through Kuscia by scheduling the fate2-adapter image,
 * persists local metadata, and refreshes status on demand by querying Kuscia.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FateJobServiceImpl implements FateJobService {

    private final FateJobRepository fateJobRepository;
    private final FateClusterRepository fateClusterRepository;
    private final EnvService envService;
    private final AbstractJobManager jobManager;
    private final FateKusciaJobConverter fateKusciaJobConverter;
    private final KusciaJobService kusciaJobService;

    @Override
    @Transactional
    public FateJobVO submit(FateJobRequest request) {
        validate(request);
        FateClusterDO cluster = fateClusterRepository.findById(request.getClusterId())
                .orElseThrow(() -> SecretpadException.of(SystemErrorCode.HTTP_404_ERROR,
                        "fate cluster not found: " + request.getClusterId()));

        String jobId = generateJobId();
        String initiatorNodeId = envService.getPlatformNodeId();
        String dagJson = toJson(request.getDag());
        Map<String, Object> storedConfig = new LinkedHashMap<>(request.getConfig());
        storedConfig.put("_executionBackend", "native-fate-v2");
        String configJson = toJson(storedConfig);

        String status = "PENDING";
        String errMsg = "";
        try {
            // Submit the FATE job through Kuscia so that the fate2-adapter image is scheduled
            // and the adapter calls FATE-Flow on behalf of SecretPad.
            Job.CreateJobRequest kusciaJobRequest = fateKusciaJobConverter.convert(jobId, request, cluster,
                    envService.getPlatformNodeId());
            jobManager.createJob(kusciaJobRequest);
            status = "RUNNING";
            log.info("submitted FATE job to Kuscia: jobId={}, clusterId={}, initiator={}",
                    jobId, cluster.getClusterId(), kusciaJobRequest.getInitiator());
        } catch (Exception e) {
            status = "FAILED";
            errMsg = StringUtils.defaultIfBlank(e.getMessage(), "Kuscia 创建作业失败，请检查调度服务日志");
            log.error("submit FATE job failed: clusterId={}, jobId={}", cluster.getClusterId(), jobId, e);
        }

        FateJobDO entity = FateJobDO.builder()
                .jobId(jobId)
                .clusterId(cluster.getClusterId())
                .projectId(StringUtils.defaultString(request.getProjectId()))
                .graphId(StringUtils.defaultString(request.getGraphId()))
                .name(StringUtils.defaultString(request.getName(), jobId))
                .initiatorNodeId(initiatorNodeId)
                .status(status)
                .dag(dagJson)
                .jobConfig(configJson)
                .errMsg(errMsg)
                .build();
        FateJobDO saved = fateJobRepository.save(entity);
        log.info("persisted fate job: jobId={}, clusterId={}, status={}", jobId, cluster.getClusterId(), status);
        return toVO(saved);
    }

    @Override
    @Transactional
    public FateJobVO stop(String jobId) {
        FateJobDO job = findJob(jobId);
        try {
            Job.StopJobRequest request = Job.StopJobRequest.newBuilder()
                    .setJobId(jobId)
                    .build();
            kusciaJobService.stopJob(request, job.getInitiatorNodeId());
            job.setStatus(refreshStatus(job).getStatus());
        } catch (Exception e) {
            job.setErrMsg(e.getMessage());
            log.error("stop FATE job failed: jobId={}", jobId, e);
        }
        return toVO(fateJobRepository.save(job));
    }

    @Override
    @Transactional
    public FateJobVO get(String jobId) {
        FateJobDO job = findJob(jobId);
        try {
            JobStatus refreshed = refreshStatus(job);
            job.setStatus(refreshed.getStatus());
            job.setErrMsg(refreshed.getErrMsg());
        } catch (Exception e) {
            log.warn("refresh FATE job status failed: jobId={}, err={}", jobId, e.getMessage());
        }
        return toVO(fateJobRepository.save(job));
    }

    @Override
    @Transactional
    public List<FateJobVO> listByClusterId(String clusterId) {
        if (StringUtils.isBlank(clusterId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "clusterId is required");
        }
        return fateJobRepository.findByClusterId(clusterId).stream()
                .map(this::refreshAndConvert)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public List<FateJobVO> listByProjectId(String projectId) {
        if (StringUtils.isBlank(projectId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "projectId is required");
        }
        return fateJobRepository.findByProjectId(projectId).stream()
                .map(this::refreshAndConvert)
                .collect(Collectors.toList());
    }

    private FateJobVO refreshAndConvert(FateJobDO job) {
        try {
            JobStatus refreshed = refreshStatus(job);
            job.setStatus(refreshed.getStatus());
            job.setErrMsg(refreshed.getErrMsg());
        } catch (Exception e) {
            log.debug("refresh FATE job status while listing failed: jobId={}, err={}",
                    job.getJobId(), e.getMessage());
        }
        return toVO(fateJobRepository.save(job));
    }

    private FateJobDO findJob(String jobId) {
        if (StringUtils.isBlank(jobId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "jobId is required");
        }
        return fateJobRepository.findById(jobId)
                .orElseThrow(() -> SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate job not found: " + jobId));
    }

    private FateClusterDO findCluster(String clusterId) {
        return fateClusterRepository.findById(clusterId)
                .orElseThrow(() -> SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate cluster not found: " + clusterId));
    }

    private JobStatus refreshStatus(FateJobDO job) {
        Job.QueryJobRequest request = Job.QueryJobRequest.newBuilder()
                .setJobId(job.getJobId())
                .build();
        Job.QueryJobResponse response = kusciaJobService.queryJob(request, job.getInitiatorNodeId());
        if (response.getData() == null || !response.getData().hasStatus()) {
            return new JobStatus(job.getStatus(), job.getErrMsg());
        }
        Job.JobStatusDetail status = response.getData().getStatus();
        if ("Succeeded".equals(status.getState()) && !usesNativeExecution(job)) {
            return new JobStatus("UNVERIFIED", "旧适配器仅验证退出状态，未验证原生 FATE 算子成功；请重新提交验收");
        }
        return new JobStatus(mapKusciaState(status.getState()), status.getErrMsg());
    }

    private boolean usesNativeExecution(FateJobDO job) {
        try {
            return "native-fate-v2".equals(JsonUtils.toJavaMap(job.getJobConfig()).get("_executionBackend"));
        } catch (Exception e) {
            return false;
        }
    }

    private String mapKusciaState(String state) {
        if (StringUtils.isBlank(state)) {
            return "UNKNOWN";
        }
        switch (state) {
            case "Succeeded":
                return "SUCCESS";
            case "Failed":
                return "FAILED";
            case "Running":
                return "RUNNING";
            case "Pending":
                return "PENDING";
            case "Cancelled":
                return "CANCELED";
            case "AwaitingApproval":
                return "AWAITING_APPROVAL";
            case "ApprovalReject":
                return "REJECTED";
            case "Suspended":
                return "SUSPENDED";
            default:
                return state.toUpperCase();
        }
    }

    /**
     * Simple holder for a refreshed FATE-style status and optional error message.
     */
    private static class JobStatus {
        private final String status;
        private final String errMsg;

        JobStatus(String status, String errMsg) {
            this.status = status;
            this.errMsg = errMsg;
        }

        String getStatus() {
            return status;
        }

        String getErrMsg() {
            return errMsg;
        }
    }

    private void validate(FateJobRequest request) {
        if (request == null) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "request is null");
        }
        if (StringUtils.isBlank(request.getClusterId())) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "clusterId is required");
        }
        if (request.getDag() == null || request.getDag().isEmpty()) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "dag is required");
        }
        if (request.getConfig() == null || request.getConfig().isEmpty()) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "config is required");
        }
    }

    private String generateJobId() {
        return "fj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        return JsonUtils.toJSONString(value);
    }

    private FateJobVO toVO(FateJobDO entity) {
        return FateJobVO.builder()
                .jobId(entity.getJobId())
                .clusterId(entity.getClusterId())
                .projectId(entity.getProjectId())
                .graphId(entity.getGraphId())
                .name(entity.getName())
                .initiatorNodeId(entity.getInitiatorNodeId())
                .status(entity.getStatus())
                .errMsg(entity.getErrMsg())
                .gmtCreate(toDate(entity.getGmtCreate()))
                .gmtModified(toDate(entity.getGmtModified()))
                .build();
    }

    private Date toDate(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}
