package org.secretflow.secretpad.persistence.entity.bfia;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;
import org.secretflow.secretpad.persistence.entity.BaseAggregationRoot;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@ToString
@Table(name = "bfia_result")
@SQLDelete(sql = "update bfia_result set is_deleted = 1 where result_id = ?")
@Where(clause = "is_deleted = 0")
public class BfiaResultDO extends BaseAggregationRoot<BfiaResultDO> {
    @Id
    @Column(name = "result_id", unique = true, nullable = false, length = 64)
    private String resultId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "job_id", length = 64)
    private String jobId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "node_id", length = 64)
    private String nodeId;

    @Column(name = "kind", length = 32)
    private String kind;

    @Column(name = "ref_id", length = 64)
    private String refId;

    @Column(name = "output_uri", columnDefinition = "text")
    private String outputUri;

    @Override
    public String getProjectId() {
        return this.projectId;
    }
}
