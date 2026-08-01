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

package org.secretflow.secretpad.service.model.fate;

import lombok.*;

import java.util.Date;

/**
 * FATE job view object.
 */
@Builder
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FateJobVO {
    private String jobId;
    private String clusterId;
    private String projectId;
    private String graphId;
    private String name;
    private String initiatorNodeId;
    private String status;
    private String errMsg;
    private Date gmtCreate;
    private Date gmtModified;
}
