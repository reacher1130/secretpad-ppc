package org.secretflow.secretpad.service.model.bfia;

import lombok.*;

import java.util.List;

@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BfiaProjectVO {
    private String projectId;
    private String name;
    private String description;
    private String type;
    /**
     * Compute mode for frontend compatibility. "MPC" for BFIA secret-sharing algorithms.
     */
    private String computeMode;
    /**
     * Participating nodes with their datatables, so the DAG editor's DefaultTableSelect
     * (data_source config panel) can populate the input-dataset dropdown.
     * Mirrors the structure returned by the native ProjectServiceImpl.getProject().
     */
    private List<NodeVO> nodes;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class NodeVO {
        private String nodeId;
        private String nodeName;
        private List<DatatableVO> datatables;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DatatableVO {
        private String datatableId;
        private String datatableName;
    }
}


