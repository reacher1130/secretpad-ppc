package org.secretflow.secretpad.persistence.entity.bfia;

import jakarta.persistence.*;
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
@Table(name = "bfia_audit")
@SQLDelete(sql = "update bfia_audit set is_deleted = 1 where audit_id = ?")
@Where(clause = "is_deleted = 0")
public class BfiaAuditDO extends BaseAggregationRoot<BfiaAuditDO> {

    @Id
    @Column(name = "audit_id", unique = true, nullable = false, length = 64)
    private String auditId;

    @Column(name = "contract_id", nullable = false, length = 64)
    private String contractId;

    @Column(name = "apply_node_id", nullable = false, length = 64)
    private String applyNodeId;

    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Column(name = "dataset_id", length = 64)
    private String datasetId;

    @Column(name = "project_id", length = 64)
    private String projectId;

    @Column(name = "flow_id", length = 64)
    private String flowId;

    @Column(name = "status", nullable = false, length = 64)
    private String status;

    @Column(name = "auth_scope", columnDefinition = "text")
    private String authScope;

    @Column(name = "time_limit")
    private Long timeLimit;

    @Column(name = "description", columnDefinition = "text")
    private String description;
}


