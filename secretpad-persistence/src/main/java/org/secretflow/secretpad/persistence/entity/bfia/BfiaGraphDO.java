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

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * BFIA independent graph data object.
 *
 * <p>Decoupled from the native {@code project_graph} table to isolate BFIA flow data
 * into the dedicated {@code bfia_graph} table.
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
@Table(name = "bfia_graph")
@SQLDelete(sql = "update bfia_graph set is_deleted = 1 where project_id = ? and graph_id = ?")
@Where(clause = "is_deleted = 0")
public class BfiaGraphDO extends BaseAggregationRoot<BfiaGraphDO> {
    @EmbeddedId
    private UPK upk;

    @Column(name = "name")
    private String name;

    @Column(name = "edges", columnDefinition = "text")
    private String edges;

    /**
     * In-memory only. BFIA graph nodes are loaded/saved manually via
     * {@link org.secretflow.secretpad.persistence.repository.bfia.BfiaGraphNodeRepository}
     * because the composite FK mapping (project_id, graph_id) cannot be maintained by a
     * standard {@code @OneToMany} when the child PK already contains those columns.
     */
    @Transient
    private List<BfiaGraphNodeDO> nodes;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "max_parallelism")
    private Integer maxParallelism;

    @Builder.Default
    @Column(name = "node_max_index", nullable = false)
    private Integer nodeMaxIndex = 32;

    /** Frontend convention: 0 editable draft, 1 submitted/frozen. */
    @Builder.Default
    @Column(name = "is_draft", nullable = false)
    private Integer isDraft = 0;

    @Override
    public String getProjectId() {
        return this.upk.projectId;
    }

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
    }
}
