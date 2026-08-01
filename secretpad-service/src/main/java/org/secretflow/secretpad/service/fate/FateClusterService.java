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

import org.secretflow.secretpad.service.model.fate.FateClusterRequest;
import org.secretflow.secretpad.service.model.fate.FateClusterVO;

import java.util.List;

/**
 * Service for managing registered FATE 2.x clusters.
 */
public interface FateClusterService {

    FateClusterVO create(FateClusterRequest request);

    FateClusterVO update(String clusterId, FateClusterRequest request);

    void delete(String clusterId);

    FateClusterVO get(String clusterId);

    List<FateClusterVO> list(String nodeId);
}
