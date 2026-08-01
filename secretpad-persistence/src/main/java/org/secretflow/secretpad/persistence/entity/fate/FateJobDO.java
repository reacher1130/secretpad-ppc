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
 * FATE 2.x job data object.
 *
 * <p>Stored in the {@code fate_job} table. It mirrors the BFIA job created on
 * FATE-Flow so that SecretPad can list and track FATE jobs independently from
 * native and BFIA jobs.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@ToString
@Table(name = "fate_job")
@SQLDelete(sql = "update fate_job set is_deleted = 1 where job_id = ?")
@Where(clause = "is_deleted = 0")
public class FateJobDO extends BaseAggregationRoot<FateJobDO> {

    @Id
    @Column(name = "job_id", unique = true, nullable = false, length = 64)
    private String jobId;

    @Column(name = "cluster_id", nullable = false, length = 64)
    private String clusterId;

    @Column(name = "project_id", length = 64)
    private String projectId;

    @Column(name = "graph_id", length = 64)
    private String graphId;

    @Column(name = "name", length = 256)
    private String name;

    @Column(name = "initiator_node_id", length = 64)
    private String initiatorNodeId;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "dag", columnDefinition = "text")
    private String dag;

    @Column(name = "job_config", columnDefinition = "text")
    private String jobConfig;

    @Column(name = "err_msg", columnDefinition = "text")
    private String errMsg;

    @Override
    public String getProjectId() {
        return this.projectId == null ? "" : this.projectId;
    }
}
