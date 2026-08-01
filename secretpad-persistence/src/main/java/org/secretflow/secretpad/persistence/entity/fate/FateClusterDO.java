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
 * FATE 2.x cluster registration data object.
 *
 * <p>Stored in the {@code fate_cluster} table. Each record represents a FATE-Flow
 * endpoint that SecretPad can submit jobs to.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@ToString
@Table(name = "fate_cluster")
@SQLDelete(sql = "update fate_cluster set is_deleted = 1 where cluster_id = ?")
@Where(clause = "is_deleted = 0")
public class FateClusterDO extends BaseAggregationRoot<FateClusterDO> {

    @Id
    @Column(name = "cluster_id", unique = true, nullable = false, length = 64)
    private String clusterId;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "flow_address", nullable = false, length = 256)
    private String flowAddress;

    @Column(name = "version", length = 32)
    private String version;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "status", length = 32)
    private String status;

    @Override
    public String getProjectId() {
        return "";
    }
}
