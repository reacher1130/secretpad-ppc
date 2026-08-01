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

package org.secretflow.secretpad.persistence.entity.bfia;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;
import org.secretflow.secretpad.persistence.entity.BaseAggregationRoot;

/**
 * BFIA independent job data object.
 *
 * <p>Decoupled from the native {@code project_job} table to isolate BFIA job
 * data into the dedicated {@code bfia_job} table.
 *
 * @author gaofeng
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@ToString
@Table(name = "bfia_job")
@SQLDelete(sql = "update bfia_job set is_deleted = 1 where job_id = ?")
@Where(clause = "is_deleted = 0")
public class BfiaJobDO extends BaseAggregationRoot<BfiaJobDO> {
    @Id
    @Column(name = "job_id", unique = true, nullable = false, length = 64)
    private String jobId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "graph_id", nullable = false, length = 64)
    private String graphId;

    @Column(name = "initiator_id", length = 64)
    private String initiatorId;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "job_params", columnDefinition = "text")
    private String jobParams;

    @Override
    public String getProjectId() {
        return this.projectId;
    }
}
