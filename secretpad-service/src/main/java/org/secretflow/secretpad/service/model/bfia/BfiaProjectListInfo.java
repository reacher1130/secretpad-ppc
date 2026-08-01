package org.secretflow.secretpad.service.model.bfia;

import lombok.*;

import java.util.List;

/**
 * BFIA project list item.
 *
 * <p>The connectivity frontend ({@code p2p-project-list/index.tsx}) reads the following fields
 * when rendering project cards:
 * <ul>
 *   <li>{@code projectId}, {@code name}, {@code description}, {@code type} — basic info</li>
 *   <li>{@code status} — when {@code "APPROVED"} the card shows participating nodes / pipeline / task counts,
 *       otherwise it shows an approval tag</li>
 *   <li>{@code initiatorNodeId}, {@code initiatorName} — the project initiator</li>
 *   <li>{@code nodes} — participating node list (each with {@code nodeId} / {@code nodeName})</li>
 *   <li>{@code gmtCreate} — unix seconds (frontend does {@code formatTimestamp(gmtCreate * 1000)})</li>
 * </ul>
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BfiaProjectListInfo {
    private String projectId;
    private String name;
    private String description;
    private String type;
    /** Unix seconds — frontend multiplies by 1000 before formatting. */
    private Long gmtCreate;

    /** Approval status: APPROVED / UNAPPROVED / REJECTED / etc. */
    private String status;
    /** Initiator node id of the project. */
    private String initiatorNodeId;
    /** Initiator node display name. */
    private String initiatorName;
    /**
     * Audit id of the project approval record. The connectivity frontend's
     * "进入项目" button reads {@code project.auditId} when building the project-detail URL.
     */
    private String auditId;
    /** Participating nodes (nodeId + nodeName). */
    private List<NodeInfo> nodes;
    /**
     * Party vote infos — the connectivity frontend's "进入项目" button reads
     * {@code partyVoteInfos[0].nodeName} / {@code partyVoteInfos[0].nodeId} when
     * building the project-detail URL. We mirror {@link #nodes} here so the
     * click handler does not throw on undefined.
     */
    private List<NodeInfo> partyVoteInfos;

    /** Lightweight node info used by the project list. */
    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class NodeInfo {
        private String nodeId;
        private String nodeName;
    }
}
