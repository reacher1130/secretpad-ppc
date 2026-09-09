package org.secretflow.secretpad.service.bfia;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.persistence.entity.bfia.*;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.persistence.repository.bfia.*;
import org.secretflow.secretpad.service.model.graph.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Submitted graph snapshots and peer decisions, isolated from native graph approvals. */
@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "bfiaTransactionManager", rollbackFor = Exception.class)
public class BfiaFlowLifecycleService {
    private final BfiaGraphRepository graphs;
    private final BfiaAuditRepository audits;
    private final ProjectNodeRepository participants;
    private final BfiaGraphService graphService;
    private final BfiaInterconnClient client;
    @Value("${secretpad.node-id}") private String localNodeId;

    private BfiaGraphDO graph(String projectId, String flowId) {
        return graphs.findForIndexUpdate(projectId, flowId)
                .orElseThrow(() -> new IllegalArgumentException("BFIA flow not found"));
    }

    private void requireProject(String projectId, String actor) {
        if (!participants.findProjectNodesByProjectId(projectId).contains(actor)) {
            throw new IllegalArgumentException("Node is not a BFIA project participant");
        }
        if (audits.findByProjectId(projectId).stream().noneMatch(a ->
                "PROJECT".equals(a.getType()) && "APPROVED".equals(a.getStatus()))) {
            throw new IllegalStateException("BFIA project is not approved");
        }
    }

    public BfiaAuditDO latestAudit(String projectId, String flowId) {
        return audits.findByProjectId(projectId).stream()
                .filter(a -> "FLOW".equals(a.getType()) && flowId.equals(a.getFlowId()))
                .max(Comparator.comparing(a -> a.getGmtCreate())).orElse(null);
    }

    public void submit(String projectId, String flowId) {
        requireProject(projectId, localNodeId);
        BfiaGraphDO graph = graph(projectId, flowId);
        // Older local drafts stored institution ownerId; only their authenticated owner can migrate them.
        if (!localNodeId.equals(graph.getOwnerId())
                && !UserContext.getUser().getOwnerId().equals(graph.getOwnerId())) {
            throw new IllegalArgumentException("Only the flow owner may submit");
        }
        if (graphService.getGraphDO(projectId, flowId).getNodes().isEmpty()) {
            throw new IllegalArgumentException("Cannot submit an empty BFIA flow");
        }
        graph.setOwnerId(localNodeId);
        graph.setIsDraft(1);
        graphs.save(graph);
    }

    public Map<String, String> apply(String projectId, String flowId) {
        requireProject(projectId, localNodeId);
        BfiaGraphDO graph = graph(projectId, flowId);
        if (!localNodeId.equals(graph.getOwnerId()) || !Integer.valueOf(1).equals(graph.getIsDraft())) {
            throw new IllegalStateException("Only the owner of a submitted BFIA flow may apply");
        }
        List<String> peers = participants.findProjectNodesByProjectId(projectId).stream()
                .filter(n -> !localNodeId.equals(n)).toList();
        if (peers.size() != 1 || !client.isEnabled()) {
            throw new IllegalStateException("BFIA flow approval currently requires one connected peer");
        }
        BfiaAuditDO audit = latestAudit(projectId, flowId);
        if (audit == null) {
            audit = BfiaAuditDO.builder().auditId(UUID.randomUUID().toString().replace("-", ""))
                    .projectId(projectId).flowId(flowId).contractId(peers.get(0))
                    .applyNodeId(localNodeId).type("FLOW").status("UNAPPROVED").build();
        }
        if (!"UNAPPROVED".equals(audit.getStatus())) {
            throw new IllegalStateException("BFIA flow already has a final approval decision");
        }
        BfiaGraphDO detail = graphService.getGraphDO(projectId, flowId);
        FullUpdateGraphRequest snapshot = new FullUpdateGraphRequest();
        snapshot.setProjectId(projectId);
        snapshot.setGraphId(flowId);
        snapshot.setMaxParallelism(detail.getMaxParallelism());
        snapshot.setEdges(detail.getEdges() == null ? List.of() : JsonUtils.toJavaList(detail.getEdges(), GraphEdge.class));
        snapshot.setNodes(detail.getNodes().stream()
                .map(n -> JsonUtils.toJavaObject(n.getParams(), GraphNodeInfo.class)).toList());
        var request = new PeerApply();
        request.setAuditId(audit.getAuditId());
        request.setName(graph.getName());
        request.setGraph(snapshot);
        client.postChecked(localNodeId, peers.get(0), "/v1/interconn/flow/audit/apply", request);
        audits.save(audit);
        return Map.of("audit_id", audit.getAuditId(), "status", audit.getStatus());
    }

    public void receive(String source, PeerApply request) {
        var snapshot = Objects.requireNonNull(request.getGraph(), "graph");
        requireProject(snapshot.getProjectId(), source);
        requireProject(snapshot.getProjectId(), localNodeId);
        if (localNodeId.equals(source)) throw new IllegalArgumentException("Peer must differ from local node");
        var existingAudit = audits.findById(request.getAuditId());
        if (existingAudit.isPresent()) {
            var existing = existingAudit.get();
            if (!source.equals(existing.getApplyNodeId()) || !snapshot.getGraphId().equals(existing.getFlowId())
                    || !snapshot.getProjectId().equals(existing.getProjectId())) {
                throw new IllegalArgumentException("BFIA audit identity mismatch");
            }
            return;
        }
        if (graphs.findById(new BfiaGraphDO.UPK(snapshot.getProjectId(), snapshot.getGraphId())).isPresent()) {
            throw new IllegalArgumentException("BFIA peer graph already exists with a different audit");
        }
        graphService.fullUpdateGraph(snapshot);
        var graph = graph(snapshot.getProjectId(), snapshot.getGraphId());
        graph.setName(request.getName());
        graph.setOwnerId(source);
        graph.setIsDraft(1);
        graphs.save(graph);
        audits.save(BfiaAuditDO.builder().auditId(request.getAuditId()).projectId(snapshot.getProjectId())
                .flowId(snapshot.getGraphId()).contractId(localNodeId).applyNodeId(source)
                .type("FLOW").status("UNAPPROVED").build());
    }

    public boolean confirm(String auditId, String status, String actor, boolean propagate) {
        if (!Set.of("APPROVED", "REJECTED").contains(status)) throw new IllegalArgumentException("Invalid decision");
        var audit = audits.findById(auditId).orElseThrow(() -> new IllegalArgumentException("BFIA audit not found"));
        if (!"FLOW".equals(audit.getType()) || actor.equals(audit.getApplyNodeId())) {
            throw new IllegalArgumentException("Only the invited peer may approve this flow");
        }
        requireProject(audit.getProjectId(), actor);
        if (!"UNAPPROVED".equals(audit.getStatus()) && !status.equals(audit.getStatus())) {
            throw new IllegalStateException("BFIA audit already decided");
        }
        if (propagate) client.postChecked(actor, audit.getApplyNodeId(), "/v1/interconn/flow/audit/confirm",
                Map.of("auditId", auditId, "status", status));
        audit.setStatus(status);
        audits.save(audit);
        return true;
    }

    public void requireExecutable(String projectId, String flowId) {
        requireProject(projectId, localNodeId);
        var graph = graph(projectId, flowId);
        var audit = latestAudit(projectId, flowId);
        if (!localNodeId.equals(graph.getOwnerId()) || !Integer.valueOf(1).equals(graph.getIsDraft())
                || audit == null || !"APPROVED".equals(audit.getStatus())) {
            throw new IllegalStateException("BFIA flow must be submitted and approved by its peer before execution");
        }
    }

    @Data
    public static class PeerApply {
        private String auditId;
        private String name;
        private FullUpdateGraphRequest graph;
    }
}
