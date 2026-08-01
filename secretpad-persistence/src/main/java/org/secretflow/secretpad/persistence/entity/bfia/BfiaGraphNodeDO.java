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

import java.io.Serial;
import java.io.Serializable;

/**
 * BFIA independent graph node data object.
 *
 * <p>Decoupled from the native {@code project_graph_node} table to isolate BFIA flow-node
 * data into the dedicated {@code bfia_graph_node} table.
 *
 * @author gaofeng
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Entity
@Table(name = "bfia_graph_node")
@SQLDelete(sql = "update bfia_graph_node set is_deleted = 1 where graph_node_id = ?")
@Where(clause = "is_deleted = 0")
public class BfiaGraphNodeDO {
    @EmbeddedId
    private UPK upk;

    @Column(name = "graph_node_name")
    private String graphNodeName;

    @Column(name = "node_def_id", length = 128)
    private String nodeDefId;

    @Column(name = "params", columnDefinition = "text")
    private String params;

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @EqualsAndHashCode
    @ToString
    @Embeddable
    public static class UPK implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        @Column(name = "project_id", nullable = false, length = 64)
        private String projectId;
        @Column(name = "graph_id", nullable = false, length = 64)
        private String graphId;
        @Column(name = "graph_node_id", nullable = false, length = 64)
        private String graphNodeId;
    }
}
