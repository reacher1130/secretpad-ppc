package org.secretflow.secretpad.service.model.bfia;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class BfiaListDatatableRequest {
    private int pageNum;
    private int pageSize;

    public BfiaListDatatableRequest(int pageNum, int pageSize) {
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }
}


