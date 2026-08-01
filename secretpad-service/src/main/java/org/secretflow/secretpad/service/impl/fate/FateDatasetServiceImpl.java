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
import org.secretflow.secretpad.persistence.entity.fate.FateDatasetDO;
import org.secretflow.secretpad.persistence.repository.fate.FateDatasetRepository;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.fate.FateDatasetService;
import org.secretflow.secretpad.service.model.fate.FateDatasetRequest;
import org.secretflow.secretpad.service.model.fate.FateDatasetVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link FateDatasetService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FateDatasetServiceImpl implements FateDatasetService {

    private final FateDatasetRepository fateDatasetRepository;
    private final EnvService envService;

    @Override
    @Transactional
    public FateDatasetVO create(FateDatasetRequest request) {
        validate(request);
        String datasetId = StringUtils.defaultIfBlank(request.getDatasetId(), generateId("fd-"));
        if (fateDatasetRepository.findById(datasetId).isPresent()) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "dataset already exists: " + datasetId);
        }
        String nodeId = StringUtils.defaultIfBlank(request.getNodeId(), envService.getPlatformNodeId());

        FateDatasetDO entity = FateDatasetDO.builder()
                .datasetId(datasetId)
                .clusterId(request.getClusterId())
                .nodeId(nodeId)
                .name(request.getName().trim())
                .namespace(StringUtils.defaultString(request.getNamespace()))
                .tableName(StringUtils.defaultString(request.getTableName()))
                .role(StringUtils.defaultString(request.getRole()))
                .meta(toJson(request.getMeta()))
                .build();
        return toVO(fateDatasetRepository.save(entity));
    }

    @Override
    @Transactional
    public FateDatasetVO update(String datasetId, FateDatasetRequest request) {
        validate(request);
        if (StringUtils.isBlank(datasetId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "datasetId is required");
        }
        FateDatasetDO entity = fateDatasetRepository.findById(datasetId)
                .orElseThrow(() -> SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate dataset not found: " + datasetId));
        entity.setClusterId(request.getClusterId());
        entity.setName(request.getName().trim());
        entity.setNamespace(StringUtils.defaultString(request.getNamespace()));
        entity.setTableName(StringUtils.defaultString(request.getTableName()));
        entity.setRole(StringUtils.defaultString(request.getRole()));
        entity.setMeta(toJson(request.getMeta()));
        return toVO(fateDatasetRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(String datasetId) {
        if (StringUtils.isBlank(datasetId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "datasetId is required");
        }
        if (!fateDatasetRepository.existsById(datasetId)) {
            throw SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate dataset not found: " + datasetId);
        }
        fateDatasetRepository.deleteById(datasetId);
    }

    @Override
    @Transactional(readOnly = true)
    public FateDatasetVO get(String datasetId) {
        if (StringUtils.isBlank(datasetId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "datasetId is required");
        }
        FateDatasetDO entity = fateDatasetRepository.findById(datasetId)
                .orElseThrow(() -> SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate dataset not found: " + datasetId));
        return toVO(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FateDatasetVO> listByClusterId(String clusterId) {
        if (StringUtils.isBlank(clusterId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "clusterId is required");
        }
        return fateDatasetRepository.findByClusterId(clusterId).stream()
                .map(this::toVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FateDatasetVO> listByNodeId(String nodeId) {
        if (StringUtils.isBlank(nodeId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "nodeId is required");
        }
        return fateDatasetRepository.findByNodeId(nodeId).stream()
                .map(this::toVO).collect(Collectors.toList());
    }

    private void validate(FateDatasetRequest request) {
        if (request == null) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "request is null");
        }
        if (StringUtils.isBlank(request.getClusterId())) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "clusterId is required");
        }
        if (StringUtils.isBlank(request.getName())) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "dataset name is required");
        }
    }

    private String generateId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String toJson(Object value) {
        return value == null ? "{}" : JsonUtils.toJSONString(value);
    }

    private FateDatasetVO toVO(FateDatasetDO entity) {
        return FateDatasetVO.builder()
                .datasetId(entity.getDatasetId())
                .clusterId(entity.getClusterId())
                .nodeId(entity.getNodeId())
                .name(entity.getName())
                .namespace(entity.getNamespace())
                .tableName(entity.getTableName())
                .role(entity.getRole())
                .meta(entity.getMeta())
                .gmtCreate(toDate(entity.getGmtCreate()))
                .build();
    }

    private Date toDate(LocalDateTime localDateTime) {
        return localDateTime == null ? null : Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}
