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

import org.secretflow.secretpad.persistence.repository.bfia.BfiaJobRepository;
import org.secretflow.secretpad.service.bfia.BfiaFlowService;
import org.secretflow.secretpad.service.protocol.ProtocolConfigService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Polling service that keeps BFIA job records in sync with the kuscia control-plane.
 * <p>
 * Unlike {@link BfiaJobStatusSyncService} which syncs native {@code project_job} rows,
 * this service operates on the dedicated {@code bfia_job} table populated by
 * {@link BfiaFlowServiceImpl#startGraph}. It runs every 5 seconds and only touches
 * jobs that are not already in a terminal state.
 *
 * @author gaofeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaJobStatusPollingService {

    private final BfiaJobRepository bfiaJobRepository;
    private final BfiaFlowService bfiaFlowService;
    private final ProtocolConfigService protocolConfigService;

    @Scheduled(fixedDelay = 5000)
    public void poll() {
        // Protocol isolation: only poll BFIA jobs when the node is running in BFIA mode.
        if (!protocolConfigService.isActive("bfia")) {
            return;
        }

        List<String> jobIds = bfiaJobRepository.findUnfinishedJobIds();
        for (String jobId : jobIds) {
            if (StringUtils.isBlank(jobId)) {
                continue;
            }
            try {
                bfiaFlowService.syncJobStatus(jobId);
            } catch (Exception e) {
                log.warn("BFIA job status polling failed: jobId={}", jobId, e);
            }
        }
    }
}
