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
import org.secretflow.secretpad.persistence.entity.fate.FateModelDO;
import org.secretflow.secretpad.persistence.repository.fate.FateModelRepository;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.fate.FateModelService;
import org.secretflow.secretpad.service.model.fate.FateModelRequest;
import org.secretflow.secretpad.service.model.fate.FateModelVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link FateModelService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FateModelServiceImpl implements FateModelService {

    private final FateModelRepository fateModelRepository;
    private final EnvService envService;

    @Override
    @Transactional
    public FateModelVO create(FateModelRequest request) {
        validate(request);
        String modelId = StringUtils.defaultIfBlank(request.getModelId(), generateId("fm-"));
        if (fateModelRepository.findById(modelId).isPresent()) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "model already exists: " + modelId);
        }
        String nodeId = StringUtils.defaultIfBlank(request.getNodeId(), envService.getPlatformNodeId());

        FateModelDO entity = FateModelDO.builder()
                .modelId(modelId)
                .jobId(StringUtils.defaultString(request.getJobId()))
                .clusterId(StringUtils.defaultString(request.getClusterId()))
                .nodeId(nodeId)
                .name(StringUtils.defaultString(request.getName()))
                .componentName(StringUtils.defaultString(request.getComponentName()))
                .modelType(StringUtils.defaultString(request.getModelType()))
                .metric(toJson(request.getMetric()))
                .build();
        return toVO(fateModelRepository.save(entity));
    }

    @Override
    @Transactional
    public FateModelVO update(String modelId, FateModelRequest request) {
        validate(request);
        if (StringUtils.isBlank(modelId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "modelId is required");
        }
        FateModelDO entity = fateModelRepository.findById(modelId)
                .orElseThrow(() -> SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate model not found: " + modelId));
        entity.setJobId(StringUtils.defaultString(request.getJobId()));
        entity.setClusterId(StringUtils.defaultString(request.getClusterId()));
        entity.setName(StringUtils.defaultString(request.getName()));
        entity.setComponentName(StringUtils.defaultString(request.getComponentName()));
        entity.setModelType(StringUtils.defaultString(request.getModelType()));
        entity.setMetric(toJson(request.getMetric()));
        return toVO(fateModelRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "modelId is required");
        }
        if (!fateModelRepository.existsById(modelId)) {
            throw SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate model not found: " + modelId);
        }
        fateModelRepository.deleteById(modelId);
    }

    @Override
    @Transactional(readOnly = true)
    public FateModelVO get(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "modelId is required");
        }
        FateModelDO entity = fateModelRepository.findById(modelId)
                .orElseThrow(() -> SecretpadException.of(SystemErrorCode.HTTP_404_ERROR, "fate model not found: " + modelId));
        return toVO(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FateModelVO> listByJobId(String jobId) {
        if (StringUtils.isBlank(jobId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "jobId is required");
        }
        return fateModelRepository.findByJobId(jobId).stream()
                .map(this::toVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FateModelVO> listByClusterId(String clusterId) {
        if (StringUtils.isBlank(clusterId)) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "clusterId is required");
        }
        return fateModelRepository.findByClusterId(clusterId).stream()
                .map(this::toVO).collect(Collectors.toList());
    }

    private void validate(FateModelRequest request) {
        if (request == null) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "request is null");
        }
        if (StringUtils.isBlank(request.getJobId())) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "jobId is required");
        }
        if (StringUtils.isBlank(request.getClusterId())) {
            throw SecretpadException.of(SystemErrorCode.VALIDATION_ERROR, "clusterId is required");
        }
    }

    private String generateId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String toJson(Object value) {
        return value == null ? "{}" : JsonUtils.toJSONString(value);
    }

    private FateModelVO toVO(FateModelDO entity) {
        return FateModelVO.builder()
                .modelId(entity.getModelId())
                .jobId(entity.getJobId())
                .clusterId(entity.getClusterId())
                .nodeId(entity.getNodeId())
                .name(entity.getName())
                .componentName(entity.getComponentName())
                .modelType(entity.getModelType())
                .metric(entity.getMetric())
                .gmtCreate(toDate(entity.getGmtCreate()))
                .build();
    }

    private Date toDate(LocalDateTime localDateTime) {
        return localDateTime == null ? null : Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}
