package org.secretflow.secretpad.service.model.bfia;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.*;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BfiaDatasetVO {
    private String datasetId;
    private String name;
    private String description;
    private String status;
    private String namespace;
    private String fileName;
    private String fieldNames;
    private Boolean hasLabel;
    private Long rowCount;

    // Frontend compatibility fields (BFIA my-data-manager expects these)
    /** Frontend "数据表名" column reads tableName */
    private String tableName;
    /** Frontend "数据类型" column reads datasourceType (LOCAL/OSS/HTTP) */
    private String datasourceType;
    /** Frontend "已授权项目" column reads authProjects */
    private List<AuthProjectVO> authProjects;
    /** Frontend "创建时间" column reads gmtCreate (unix seconds) */
    private Long gmtCreate;

    /**
     * The legacy BFIA management pages use snake_case for dataset identifiers.
     * Keep the canonical camelCase field for new clients and expose this
     * read-only alias so existing list/detail/action buttons address the same
     * dataset instead of sending an empty id.
     */
    @JsonGetter("dataset_id")
    public String getDataset_id() {
        return datasetId;
    }

    /** Simple auth-project VO for the dataset list */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AuthProjectVO {
        private String projectId;
        private String name;
    }
}
