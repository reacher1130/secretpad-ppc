package org.secretflow.secretpad.service.model.bfia;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BfiaDatasetApplyRequest {
    private String auditId;
    private String contractId;
    private String datasetId;
    private String name;
    private String description;
}


