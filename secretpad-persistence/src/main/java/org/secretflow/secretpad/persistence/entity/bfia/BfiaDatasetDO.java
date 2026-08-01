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
@Table(name = "bfia_dataset")
@SQLDelete(sql = "update bfia_dataset set is_deleted = 1 where dataset_id = ?")
@Where(clause = "is_deleted = 0")
public class BfiaDatasetDO extends BaseAggregationRoot<BfiaDatasetDO> {

    @Id
    @Column(name = "dataset_id", unique = true, nullable = false, length = 64)
    private String datasetId;

    @Column(name = "contract_id", nullable = false, length = 64)
    private String contractId;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "status", length = 64)
    private String status;

    @Builder.Default
    @Column(name = "namespace", length = 64)
    private String namespace = "data";

    @Column(name = "file_name", length = 256)
    private String fileName;

    @Column(name = "field_names", length = 256)
    private String fieldNames;

    @Column(name = "has_label")
    private Boolean hasLabel;

    @Column(name = "row_count")
    private Long rowCount;
}


