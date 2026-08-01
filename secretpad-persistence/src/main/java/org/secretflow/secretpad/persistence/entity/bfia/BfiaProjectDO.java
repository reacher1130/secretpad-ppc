package org.secretflow.secretpad.persistence.entity.bfia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;
import org.secretflow.secretpad.persistence.entity.BaseAggregationRoot;

@Builder
@Entity
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Setter
@Getter
@Table(name = "bfia_project")
@SQLDelete(sql = "update bfia_project set is_deleted = 1 where project_id = ?")
@Where(clause = "is_deleted = 0")
public class BfiaProjectDO extends BaseAggregationRoot<BfiaProjectDO> {

    @Id
    @Column(name = "project_id", unique = true, nullable = false, length = 64)
    private String projectId;

    @Column(name = "contract_id", nullable = false, length = 64)
    private String contractId;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Column(name = "datasets", nullable = false, columnDefinition = "text")
    private String datasets;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Override
    public String getProjectId() {
        return this.projectId;
    }
}
