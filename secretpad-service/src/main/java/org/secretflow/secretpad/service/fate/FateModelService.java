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

package org.secretflow.secretpad.service.fate;

import org.secretflow.secretpad.service.model.fate.FateModelRequest;
import org.secretflow.secretpad.service.model.fate.FateModelVO;

import java.util.List;

/**
 * Service for managing registered FATE 2.x model/result references.
 */
public interface FateModelService {

    FateModelVO create(FateModelRequest request);

    FateModelVO update(String modelId, FateModelRequest request);

    void delete(String modelId);

    FateModelVO get(String modelId);

    List<FateModelVO> listByJobId(String jobId);

    List<FateModelVO> listByClusterId(String clusterId);
}
