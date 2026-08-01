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

package org.secretflow.secretpad.persistence.entity.fate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;
import org.secretflow.secretpad.persistence.entity.BaseAggregationRoot;

/**
 * FATE 2.x model/result data object.
 *
 * <p>Stored in the {@code fate_model} table. It caches FATE model/output metadata
 * so that SecretPad can display model and evaluation results without querying
 * FATE-Flow on every page load.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@ToString
@Table(name = "fate_model")
@SQLDelete(sql = "update fate_model set is_deleted = 1 where model_id = ?")
@Where(clause = "is_deleted = 0")
public class FateModelDO extends BaseAggregationRoot<FateModelDO> {

    @Id
    @Column(name = "model_id", unique = true, nullable = false, length = 64)
    private String modelId;

    @Column(name = "job_id", nullable = false, length = 64)
    private String jobId;

    @Column(name = "cluster_id", nullable = false, length = 64)
    private String clusterId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "name", length = 256)
    private String name;

    @Column(name = "component_name", length = 128)
    private String componentName;

    @Column(name = "model_type", length = 64)
    private String modelType;

    @Column(name = "metric", columnDefinition = "text")
    private String metric;

    @Override
    public String getProjectId() {
        return "";
    }
}
