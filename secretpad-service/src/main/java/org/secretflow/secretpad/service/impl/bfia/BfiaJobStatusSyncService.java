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

package org.secretflow.secretpad.service.impl.bfia;

import org.secretflow.secretpad.persistence.entity.ProjectJobDO;
import org.secretflow.secretpad.persistence.entity.ProjectTaskDO;
import org.secretflow.secretpad.persistence.model.GraphJobStatus;
import org.secretflow.secretpad.persistence.model.GraphNodeTaskStatus;
import org.secretflow.secretpad.persistence.repository.ProjectJobRepository;
import org.secretflow.secretpad.persistence.repository.ProjectJobTaskRepository;
import org.secretflow.secretpad.manager.integration.job.JobManager;
import org.secretflow.secretpad.service.bfia.BfiaInterconnClient;
import org.secretflow.secretpad.service.configuration.ProtocolConfigurationProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * BFIA job status polling service.
 * <p>
 * Periodically queries the kuscia interconn control-plane HTTP API (port 8084) at
 * {@code /v1/interconn/schedule/job/status_all} for all RUNNING jobs and updates the
 * persisted job/task status in the database. Only active when {@link BfiaInterconnClient}
 * is available in the application context.
 * <p>
 * The initiator domain id (needed to resolve the target kuscia node) is resolved from
 * the first party of the first task of each job, mirroring
 * {@link org.secretflow.secretpad.service.impl.bfia.BfiaFlowServiceImpl#resolveInitiator}.
 *
 * @author gaofeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaJobStatusSyncService {

    private final ObjectProvider<ProjectJobRepository> jobRepoProvider;
    private final ObjectProvider<ProjectJobTaskRepository> taskRepoProvider;
    private final BfiaInterconnClient bfiaInterconnClient;
    private final ProtocolConfigurationProperties protocolProperties;
    /**
     * Prototype-scoped job manager used to sync task results (DomainData → project_result)
     * when a BFIA task transitions to SUCCEED. Wrapped in ObjectProvider because the
     * {@code jobManager} bean is {@code @Scope(SCOPE_PROTOTYPE)} and may pull in many
     * dependencies; we only need it when a task actually succeeds.
     */
    private final ObjectProvider<JobManager> jobManagerProvider;

    /**
     * Poll all RUNNING BFIA jobs and sync their status from the kuscia control-plane.
     * Runs every 5 seconds (fixed delay between end of previous run and start of next).
     */
    @Scheduled(fixedDelay = 5000)
    public void syncBfiaJobStatus() {
        // Protocol isolation: only poll BFIA jobs when the node is running in BFIA mode.
        String defaultProtocol = protocolProperties.getDefaultProtocol();
        if (!StringUtils.hasText(defaultProtocol) || !"bfia".equalsIgnoreCase(defaultProtocol.trim())) {
            return;
        }

        ProjectJobRepository jobRepo = jobRepoProvider.getIfAvailable();
        if (jobRepo == null) {
            return;
        }

        List<ProjectJobDO> runningJobs = jobRepo.findByStatus(GraphJobStatus.RUNNING);
        if (CollectionUtils.isEmpty(runningJobs)) {
            return;
        }

        for (ProjectJobDO job : runningJobs) {
            String jobId = job.getUpk().getJobId();
            try {
                String initiatorId = resolveInitiator(job);
                Map<String, Object> resp = bfiaInterconnClient.queryJobStatus(initiatorId, jobId);
                updateJobStatus(job, resp);
            } catch (Exception e) {
                log.warn("Failed to sync BFIA job status: jobId={}", jobId, e);
            }
        }
    }

    /**
     * Update the persisted job and task status from the BFIA status_all response.
     */
    @SuppressWarnings("unchecked")
    private void updateJobStatus(ProjectJobDO job, Map<String, Object> resp) {
        String jobId = job.getUpk().getJobId();
        Object codeObj = resp.get("code");
        if (codeObj != null && !"0".equals(String.valueOf(codeObj))) {
            log.warn("BFIA job status query failed: jobId={}, resp={}", jobId, resp);
            return;
        }

        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        if (data == null) {
            return;
        }

        String jobStatus = (String) data.get("jobStatus");
        Map<String, String> taskStatusMap = (Map<String, String>) data.get("status");

        // Update task statuses using the EAGER-loaded tasks map on the job entity.
        // BFIA task status keys are Kuscia-internal taskIds (e.g. "dswv-4236caaf3181") which
        // do not match the graph_node_id stored in project_job_task. Since a BFIA job typically
        // has a single DAG component (one task per node), we derive the overall task status
        // from the job-level status when direct key lookup fails.
        ProjectJobTaskRepository taskRepo = taskRepoProvider.getIfAvailable();
        if (taskRepo != null && taskStatusMap != null && job.getTasks() != null) {
            // Collect all distinct task statuses from the BFIA response
            java.util.Collection<String> allStatuses = taskStatusMap.values();
            for (ProjectTaskDO task : job.getTasks().values()) {
                String bfiaStatus = taskStatusMap.get(task.getGraphNodeId());
                if (bfiaStatus == null) {
                    // Fallback: if there is only one task in the BFIA response, use it;
                    // otherwise derive from the job-level status.
                    if (allStatuses.size() == 1) {
                        bfiaStatus = allStatuses.iterator().next();
                    } else if (jobStatus != null) {
                        bfiaStatus = jobStatus;
                    }
                }
                if (bfiaStatus != null) {
                    GraphNodeTaskStatus mapped = mapBfiaTaskStatus(bfiaStatus);
                    GraphNodeTaskStatus previous = task.getStatus();
                    task.setStatus(mapped);
                    taskRepo.save(task);
                    // When a task transitions into SUCCEED, sync its output DomainData
                    // into project_result so the frontend can render the result panel.
                    if (mapped == GraphNodeTaskStatus.SUCCEED && previous != GraphNodeTaskStatus.SUCCEED) {
                        syncTaskResult(task);
                    }
                }
            }
        }

        // Update job status
        if (jobStatus != null) {
            GraphJobStatus mapped = mapBfiaJobStatus(jobStatus);
            job.setStatus(mapped);
            ProjectJobRepository jobRepo = jobRepoProvider.getIfAvailable();
            if (jobRepo != null) {
                jobRepo.save(job);
            }
            log.info("BFIA job status updated: jobId={}, status={}", jobId, mapped);
        }
    }

    /**
     * Sync a single task's output DomainData into the {@code project_result} table by
     * delegating to the standard {@link JobManager#syncResult(ProjectTaskDO)}.
     * <p>
     * The prototype-scoped {@code jobManager} bean is fetched on demand via
     * {@link ObjectProvider} so this bean does not hard-wire the full job-manager
     * dependency graph unless a result actually needs to be synced.
     * <p>
     * Failures are logged and swallowed: result sync must never break the status
     * polling loop (the job/task status has already been persisted at this point).
     */
    private void syncTaskResult(ProjectTaskDO task) {
        JobManager jobManager = jobManagerProvider.getIfAvailable();
        if (jobManager == null) {
            log.warn("JobManager not available, skip BFIA result sync: jobId={}, taskId={}",
                    task.getUpk().getJobId(), task.getUpk().getTaskId());
            return;
        }
        try {
            jobManager.syncResult(task);
            log.info("BFIA task result synced: jobId={}, taskId={}, graphNodeId={}",
                    task.getUpk().getJobId(), task.getUpk().getTaskId(), task.getGraphNodeId());
        } catch (Exception e) {
            log.warn("Failed to sync BFIA task result (status already persisted, safe to ignore): jobId={}, taskId={}",
                    task.getUpk().getJobId(), task.getUpk().getTaskId(), e);
        }
    }

    /**
     * Resolve the initiator domain id from the first task's first party.
     * Mirrors {@link BfiaFlowServiceImpl#resolveInitiator} logic.
     */
    private String resolveInitiator(ProjectJobDO job) {
        if (job.getTasks() != null) {
            for (ProjectTaskDO task : job.getTasks().values()) {
                List<String> parties = task.getParties();
                if (!CollectionUtils.isEmpty(parties)) {
                    return parties.get(0);
                }
            }
        }
        return null;
    }

    /**
     * Map BFIA job status string to {@link GraphJobStatus}.
     * PENDING maps to RUNNING (job is submitted and waiting; from our perspective it is running).
     */
    private GraphJobStatus mapBfiaJobStatus(String bfiaStatus) {
        if (bfiaStatus == null) {
            return GraphJobStatus.RUNNING;
        }
        return switch (bfiaStatus.toUpperCase()) {
            case "RUNNING" -> GraphJobStatus.RUNNING;
            case "SUCCESS" -> GraphJobStatus.SUCCEED;
            case "FAILED" -> GraphJobStatus.FAILED;
            default -> GraphJobStatus.RUNNING;
        };
    }

    /**
     * Map BFIA task status string to {@link GraphNodeTaskStatus}.
     * PENDING maps to STAGING (task is queued but not yet started).
     */
    private GraphNodeTaskStatus mapBfiaTaskStatus(String bfiaStatus) {
        if (bfiaStatus == null) {
            return GraphNodeTaskStatus.STAGING;
        }
        return switch (bfiaStatus.toUpperCase()) {
            case "PENDING" -> GraphNodeTaskStatus.STAGING;
            case "RUNNING" -> GraphNodeTaskStatus.RUNNING;
            case "SUCCESS" -> GraphNodeTaskStatus.SUCCEED;
            case "FAILED" -> GraphNodeTaskStatus.FAILED;
            default -> GraphNodeTaskStatus.STAGING;
        };
    }
}
