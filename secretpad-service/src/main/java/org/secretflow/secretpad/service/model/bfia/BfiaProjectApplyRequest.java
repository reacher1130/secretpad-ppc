package org.secretflow.secretpad.service.model.bfia;

import lombok.*;

import java.util.List;

@Builder
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BfiaProjectApplyRequest {
    private String auditId;
    private String type;
    private String contractId;
    private String projectId;
    private String description;
    private String name;
    private List<Object> datasets;
}


