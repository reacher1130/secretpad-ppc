/*
 * Copyright 2023 Ant Group Co., Ltd.
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

package org.secretflow.secretpad.persistence.datasync.rest.p2p;

import org.secretflow.secretpad.common.dto.SecretPadResponse;
import org.secretflow.secretpad.common.dto.SyncDataDTO;
import org.secretflow.secretpad.persistence.datasync.listener.EntityChangeListener;
import org.secretflow.secretpad.persistence.datasync.rest.DataSyncRestTemplate;
import org.secretflow.secretpad.persistence.entity.BaseAggregationRoot;
import org.secretflow.secretpad.persistence.entity.NodeDO;
import org.secretflow.secretpad.persistence.repository.NodeRepository;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author yutu
 * @date 2023/12/10
 */
@Slf4j
@RequiredArgsConstructor
public class P2pDataSyncRestTemplate extends DataSyncRestTemplate {

    Map<EntityChangeListener.DbChangeEvent<BaseAggregationRoot>, AtomicInteger> retryTimes = new ConcurrentHashMap<>();
    static final int MAX_RETRY_TIMES = 3;

    @Resource
    private NodeRepository nodeRepository;

    @Value("${secretpad.node-id}")
    private String localNodeId;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public EntityChangeListener.DbChangeEvent<BaseAggregationRoot> send(String node) throws InterruptedException {
        int size = dataSyncDataBufferTemplate.size(node);
        EntityChangeListener.DbChangeEvent<BaseAggregationRoot> event = null;
        while (size > 0) {
            long startTime = System.currentTimeMillis();
            log.debug("data sync start to send {}, now size {}", node, size);
            event = dataSyncDataBufferTemplate.poll(node);
            if (!ObjectUtils.isEmpty(event)) {
                SecretPadResponse<EntityChangeListener.DbChangeEvent<BaseAggregationRoot>> syncResp;
                String routeId = "";
                SyncDataDTO<Object> syncDataDTO = SyncDataDTO.builder()
                        .tableName(event.getDType())
                        .action(event.getAction())
                        .data(event.getSource()).build();
                try {
                    routeId = resolveRouteId(node);
                    log.info("P2pDataSyncRestTemplate send, routeId:{} instId:{}", routeId, node);
                    String peerPadUrl = resolvePeerPadAddress(routeId) + "/api/v1alpha1/data/sync";
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.set("kuscia-origin-source", localNodeId);
                    HttpEntity<String> entity = new HttpEntity<>(syncDataDTO.toJson(), headers);
                    ResponseEntity<SecretPadResponse> response = restTemplate.postForEntity(peerPadUrl, entity, SecretPadResponse.class);
                    syncResp = response.getBody();
                    if (syncResp != null && syncResp.getStatus() != null && 0 == syncResp.getStatus().getCode()) {
                        onSuccess(node, event);
                        long duration = System.currentTimeMillis() - startTime;
                        recordMetrics(routeId, syncDataDTO.getTableName(), duration, "success", size);
                    } else {
                        String code = syncResp == null || syncResp.getStatus() == null ? "null" : String.valueOf(syncResp.getStatus().getCode());
                        String msg = syncResp == null || syncResp.getStatus() == null ? "null" : syncResp.getStatus().getMsg();
                        log.error("P2pDataSyncRestTemplate send error,{} {}", code, msg);
                        onError(node, event);
                        long duration = System.currentTimeMillis() - startTime;
                        recordMetrics(routeId, syncDataDTO.getTableName(), duration, msg, size);
                    }
                } catch (Exception e) {
                    log.error("P2pDataSyncRestTemplate send error", e);
                    onError(node, event);
                    long duration = System.currentTimeMillis() - startTime;
                    recordMetrics(routeId, syncDataDTO.getTableName(), duration, ObjectUtils.isEmpty(e.getMessage()) ? e.getClass().getName() : e.getMessage(), size);
                }
                size = dataSyncDataBufferTemplate.size(node);
                log.debug("data sync end to send {}, now size {}", node, size);
            } else {
                log.warn("data sync end to send {}, now size {} event is {}", node, size, event);
                return event;
            }
        }
        return event;
    }

    private String resolveRouteId(String instId) {
        String routeId = p2pPaddingNodeService.turnInstToRouteId(instId);
        if (StringUtils.hasText(routeId)) {
            return routeId;
        }
        List<NodeDO> nodes = nodeRepository.findByInstId(instId);
        if (!CollectionUtils.isEmpty(nodes)) {
            for (NodeDO node : nodes) {
                if (StringUtils.hasText(node.getNodeId())) {
                    return node.getNodeId();
                }
            }
        }
        throw new IllegalStateException("Cannot resolve routeId for instId: " + instId);
    }

    private String resolvePeerPadAddress(String routeId) {
        String envKey = "SECRETPAD_PEER_" + routeId.toUpperCase() + "_HTTP_ADDRESS";
        String addr = System.getenv(envKey);
        if (!StringUtils.hasText(addr)) {
            throw new IllegalStateException("Peer SecretPad HTTP address not configured, env: " + envKey);
        }
        if (!addr.startsWith("http://") && !addr.startsWith("https://")) {
            addr = "http://" + addr;
        }
        return StringUtils.trimTrailingCharacter(addr, '/');
    }

    @Override
    public void onError(String node, EntityChangeListener.DbChangeEvent<BaseAggregationRoot> event) {
        if (retryTimes.containsKey(event)) {
            retryTimes.get(event).incrementAndGet();
        } else {
            retryTimes.put(event, new AtomicInteger(0));
        }
        int i = retryTimes.get(event).get();
        if (i < MAX_RETRY_TIMES) {
            log.warn("data sync send error, retry {} times", i);
            dataSyncDataBufferTemplate.commit(node, event);
            dataSyncDataBufferTemplate.push(event);
        } else {
            log.error("data sync send error, retry {} times, remove it", i);
            dataSyncDataBufferTemplate.commit(node, event);
            retryTimes.remove(event);
        }
    }

    @Override
    public void onSuccess(String node, EntityChangeListener.DbChangeEvent<BaseAggregationRoot> event) {
        dataSyncDataBufferTemplate.commit(node, event);
    }

    private void recordMetrics(String target, String tableName, long duration, String status, int size) {
        log.info("recordMetrics target:{}, tableName:{}, duration:{}, status:{}, size:{}", target, tableName, duration, status, size);
        try {
            Timer timer = Timer.builder("p2p.data.sync.duration")
                    .tags(Tags.of("target", target, "tableName", tableName, "status", status, "size", String.valueOf(size)))
                    .register(meterRegistry);
            timer.record(Duration.ofMillis(duration));
        } catch (Exception e) {
            log.error("recordMetrics error", e);
        }
    }
}