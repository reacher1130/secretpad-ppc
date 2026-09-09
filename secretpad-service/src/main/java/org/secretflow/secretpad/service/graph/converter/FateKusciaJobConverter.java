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
        // Only the local platform schedules an adapter. FATE party identifiers
        // belong to the independent native FATE federation, never Kuscia domains.
        if (StringUtils.isBlank(localNode)) {
            throw new IllegalArgumentException("Local FATE adapter node is required");
        }
        if (StringUtils.isNotBlank(request.getInitiatorNodeId())
                && !localNode.equals(request.getInitiatorNodeId())) {
            throw new IllegalArgumentException("FATE adapter must be initiated on the local platform");
        }
        validateNativeParties(request.getConfig());
        String initiator = localNode;
        List<Job.Party> parties = List.of(Job.Party.newBuilder().setDomainId(localNode).build());

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

    private void validateNativeParties(Map<String, Object> config) {
        if (config == null || !(config.get("role") instanceof Map<?, ?> roles)) {
            throw new IllegalArgumentException("FATE native party roles are required");
        }
        for (String role : List.of("guest", "host")) {
            if (!(roles.get(role) instanceof List<?> ids) || ids.isEmpty()) {
                throw new IllegalArgumentException("FATE native " + role + " party is required");
            }
        }
        for (Object value : roles.values()) {
            if (!(value instanceof List<?> ids)) {
                throw new IllegalArgumentException("FATE role must contain a list of native party IDs");
            }
            for (Object id : ids) {
                String party = id == null ? "" : id.toString().trim();
                if (party.isEmpty() || List.of("alice", "bob", "kimi").contains(party.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("Use native FATE party IDs; Alice/Bob/Kimi are platform domains");
                }
            }
        }
    }

    private String buildTaskInputConfig(String jobId, FateJobRequest request, String flowAddress) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", jobId);
        // FATE 2.x requires flow_id and old_job_id even for a fresh graph.
        // Use the generated local job id as a stable flow id fallback.
        body.put("flow_id", StringUtils.defaultIfBlank(request.getGraphId(), jobId));
        body.put("old_job_id", "");
        body.put("dag", request.getDag());
        body.put("config", request.getConfig());
        body.put("fate_flow_address", flowAddress);
        return JsonUtils.toJSONString(body);
    }
}
