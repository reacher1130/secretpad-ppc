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

package org.secretflow.secretpad.service.graph.converter;

import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.persistence.entity.fate.FateClusterDO;
import org.secretflow.secretpad.service.model.fate.FateJobRequest;
import org.secretflow.v1alpha1.kusciaapi.Job;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a SecretPad FATE 2.x job request into a Kuscia {@link Job.CreateJobRequest}
 * that schedules the {@code fate2-adapter} image.
 *
 * <p>The FATE-Flow address is injected into {@code task_input_config.fate_flow_address}
 * so that a single AppImage can target different FATE clusters registered in SecretPad.
 */
@Slf4j
@Component
public class FateKusciaJobConverter {

    private static final String APP_IMAGE = "fate2-adapter";
    private static final String TASK_ID_PREFIX = "fate2-task-";

    @Value("${job.max-parallelism:1}")
    private int maxParallelism;

    /**
     * Build a Kuscia create-job request from a FATE job request.
     *
     * @param jobId     the local FATE job id (also used as Kuscia job id)
     * @param request   the FATE job request
     * @param cluster   the registered FATE cluster (provides FATE-Flow address)
     * @param localNode the local platform node id, used as initiator fallback
     * @return Kuscia create job request
     */
    public Job.CreateJobRequest convert(String jobId, FateJobRequest request, FateClusterDO cluster, String localNode) {
        String initiator = resolveInitiator(request, localNode);
        List<Job.Party> parties = buildParties(request.getConfig());
        if (CollectionUtils.isEmpty(parties)) {
            // Single-party fallback: run on the initiator node so local development still works.
            parties = List.of(Job.Party.newBuilder().setDomainId(initiator).build());
        }

        String taskInputConfig = buildTaskInputConfig(jobId, request, cluster.getFlowAddress());
        String taskId = TASK_ID_PREFIX + jobId;

        Job.Task task = Job.Task.newBuilder()
                .setTaskId(taskId)
                .setAlias(taskId)
                .setAppImage(APP_IMAGE)
                .addAllParties(parties)
                .setTaskInputConfig(taskInputConfig)
                .build();

        return Job.CreateJobRequest.newBuilder()
                .setJobId(jobId)
                .setInitiator(initiator)
                .setMaxParallelism(maxParallelism)
                .addAllTasks(List.of(task))
                .build();
    }

    @SuppressWarnings("unchecked")
    private String resolveInitiator(FateJobRequest request, String localNode) {
        Map<String, Object> config = request.getConfig();
        if (config != null) {
            Object initiatorObj = config.get("initiator");
            if (initiatorObj instanceof Map<?, ?> initiatorMap) {
                Object nodeId = initiatorMap.get("node_id");
                if (nodeId != null && StringUtils.isNotBlank(String.valueOf(nodeId))) {
                    return String.valueOf(nodeId);
                }
            }
        }
        if (StringUtils.isNotBlank(request.getInitiatorNodeId())) {
            return request.getInitiatorNodeId();
        }
        return localNode;
    }

    @SuppressWarnings("unchecked")
    private List<Job.Party> buildParties(Map<String, Object> config) {
        List<Job.Party> parties = new ArrayList<>();
        if (config == null) {
            return parties;
        }
        Object roleObj = config.get("role");
        if (!(roleObj instanceof Map<?, ?> roleMap)) {
            return parties;
        }
        for (Map.Entry<?, ?> entry : roleMap.entrySet()) {
            String role = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (!(value instanceof List<?> domainIds)) {
                continue;
            }
            for (Object domainIdObj : domainIds) {
                String domainId = String.valueOf(domainIdObj);
                if (StringUtils.isBlank(domainId)) {
                    continue;
                }
                parties.add(Job.Party.newBuilder()
                        .setDomainId(domainId)
                        .setRole(role)
                        .build());
            }
        }
        return parties;
    }

    private String buildTaskInputConfig(String jobId, FateJobRequest request, String flowAddress) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", jobId);
        if (StringUtils.isNotBlank(request.getGraphId())) {
            body.put("flow_id", request.getGraphId());
        }
        body.put("dag", request.getDag());
        body.put("config", request.getConfig());
        body.put("fate_flow_address", flowAddress);
        return JsonUtils.toJSONString(body);
    }
}
