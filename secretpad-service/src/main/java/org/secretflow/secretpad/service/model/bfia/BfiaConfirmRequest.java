package org.secretflow.secretpad.service.model.bfia;

import lombok.*;

@Builder
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BfiaConfirmRequest {
    private String auditId;
    private String status;
}


