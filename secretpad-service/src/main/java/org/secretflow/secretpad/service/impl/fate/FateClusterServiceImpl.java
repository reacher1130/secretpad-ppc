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
import org.secretflow.secretpad.persistence.entity.fate.FateClusterDO;
import org.secretflow.secretpad.persistence.repository.fate.FateClusterRepository;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.fate.FateClusterService;
import org.secretflow.secretpad.service.fate.FateFlowClient;
import org.secretflow.secretpad.service.model.fate.FateClusterRequest;
import org.secretflow.secretpad.service.model.fate.FateClusterVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link FateClusterService}.
 *
 * <p>Maintains a registry of FATE-Flow endpoints. The status of a cluster is
 * lazily verified by calling FATE-Flow; unreachable clusters remain in status
 * {@code UNKNOWN} and do not block registration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FateClusterServiceImpl implements FateClusterService {

    private final FateClusterRepository fateClusterRepository;
    private final FateFlowClient fateFlowClient;
    private final EnvService envService;

    @Override
    @Transactional
    public FateClusterVO create(FateClusterRequest request) {
        validate(request);
        String clusterId = StringUtils.defaultIfBlank(request.getClusterId(), generateClusterId());
        if (fateClusterRepository.findById(clusterId).isPresent()) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "cluster already exists: " + clusterId);
        }
        String nodeId = resolveNodeId(request.getNodeId());
        String status = probeStatus(request.getFlowAddress());

        FateClusterDO entity = FateClusterDO.builder()
                .clusterId(clusterId)
                .name(request.getName().trim())
                .nodeId(nodeId)
                .flowAddress(request.getFlowAddress().trim())
                .version(StringUtils.defaultString(request.getVersion()))
                .description(StringUtils.defaultString(request.getDescription()))
                .status(status)
                .build();
        FateClusterDO saved = fateClusterRepository.save(entity);
        log.info("created fate cluster: clusterId={}, nodeId={}, address={}", clusterId, nodeId, saved.getFlowAddress());
        return toVO(saved);
    }

    @Override
    @Transactional
    public FateClusterVO update(String clusterId, FateClusterRequest request) {
        if (StringUtils.isBlank(clusterId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "clusterId is required");
        }
        FateClusterDO entity = fateClusterRepository.findById(clusterId)
                .orElseThrow(() -> SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate cluster not found: " + clusterId));
        validateForUpdate(request);
        if (StringUtils.isNotBlank(request.getName())) {
            entity.setName(request.getName().trim());
        }
        if (StringUtils.isNotBlank(request.getFlowAddress())) {
            entity.setFlowAddress(request.getFlowAddress().trim());
        }
        if (request.getVersion() != null) {
            entity.setVersion(request.getVersion());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        if (StringUtils.isNotBlank(request.getNodeId())) {
            entity.setNodeId(request.getNodeId().trim());
        }
        entity.setStatus(probeStatus(entity.getFlowAddress()));
        FateClusterDO saved = fateClusterRepository.save(entity);
        return toVO(saved);
    }

    @Override
    @Transactional
    public void delete(String clusterId) {
        if (StringUtils.isBlank(clusterId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "clusterId is required");
        }
        if (!fateClusterRepository.existsById(clusterId)) {
            throw SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate cluster not found: " + clusterId);
        }
        fateClusterRepository.deleteById(clusterId);
        log.info("deleted fate cluster: clusterId={}", clusterId);
    }

    @Override
    @Transactional(readOnly = true)
    public FateClusterVO get(String clusterId) {
        if (StringUtils.isBlank(clusterId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "clusterId is required");
        }
        FateClusterDO entity = fateClusterRepository.findById(clusterId)
                .orElseThrow(() -> SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate cluster not found: " + clusterId));
        return toVO(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FateClusterVO> list(String nodeId) {
        List<FateClusterDO> entities;
        if (StringUtils.isBlank(nodeId)) {
            entities = fateClusterRepository.findAll();
        } else {
            entities = fateClusterRepository.findByNodeId(nodeId);
        }
        return entities.stream().map(this::toVO).collect(Collectors.toList());
    }

    private void validate(FateClusterRequest request) {
        if (request == null) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "request is null");
        }
        if (StringUtils.isBlank(request.getName())) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "cluster name is required");
        }
        if (StringUtils.isBlank(request.getFlowAddress())) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "flowAddress is required");
        }
    }

    private void validateForUpdate(FateClusterRequest request) {
        if (request == null) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "request is null");
        }
        if (StringUtils.isNotBlank(request.getFlowAddress()) && !request.getFlowAddress().startsWith("http")) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "flowAddress must be a valid http(s) URL");
        }
    }

    private String resolveNodeId(String requestNodeId) {
        return StringUtils.defaultIfBlank(requestNodeId, envService.getPlatformNodeId());
    }

    private String generateClusterId() {
        return "fc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Best-effort status probe. Returns ACTIVE if FATE-Flow responds to a status
     * query with a non-empty body, otherwise UNKNOWN.
     */
    private String probeStatus(String flowAddress) {
        return fateFlowClient.checkConnection(flowAddress) ? "ACTIVE" : "UNKNOWN";
    }

    private FateClusterVO toVO(FateClusterDO entity) {
        return FateClusterVO.builder()
                .clusterId(entity.getClusterId())
                .name(entity.getName())
                .nodeId(entity.getNodeId())
                .flowAddress(entity.getFlowAddress())
                .version(entity.getVersion())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .gmtCreate(toDate(entity.getGmtCreate()))
                .build();
    }

    private Date toDate(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}
