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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;
import org.secretflow.secretpad.persistence.entity.BaseAggregationRoot;

/**
 * BFIA independent node data object.
 *
 * <p>Decoupled from the native {@code node} table to isolate BFIA cooperative-node data
 * into the dedicated {@code bfia_node} table.
 *
 * @author gaofeng
 */
@Builder
@Entity
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Setter
@Getter
@Table(name = "bfia_node")
@SQLDelete(sql = "update bfia_node set is_deleted = 1 where node_id = ?")
@Where(clause = "is_deleted = 0")
public class BfiaNodeDO extends BaseAggregationRoot<BfiaNodeDO> {

    @Id
    @Column(name = "node_id", unique = true, nullable = false, length = 64)
    private String nodeId;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "net_address", length = 200)
    private String netAddress;

    @Column(name = "pad_address", length = 200)
    private String padAddress;

    @Column(name = "inst_id", length = 64)
    private String instId;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Override
    public String getProjectId() {
        return null;
    }
}
