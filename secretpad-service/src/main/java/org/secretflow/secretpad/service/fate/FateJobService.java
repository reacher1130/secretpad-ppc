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

import org.secretflow.secretpad.service.model.fate.FateJobRequest;
import org.secretflow.secretpad.service.model.fate.FateJobVO;

import java.util.List;

/**
 * Service for submitting and tracking FATE 2.x jobs.
 */
public interface FateJobService {

    FateJobVO submit(FateJobRequest request);

    FateJobVO stop(String jobId);

    FateJobVO get(String jobId);

    List<FateJobVO> listByClusterId(String clusterId);

    List<FateJobVO> listByProjectId(String projectId);
}
