package org.secretflow.secretpad.web.controller.bfia;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.kuscia.v1alpha1.service.KusciaJobService;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaGraphDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaGraphNodeDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaJobDO;
import org.secretflow.secretpad.persistence.model.GraphNodeTaskStatus;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaJobRepository;
import org.secretflow.secretpad.service.bfia.BfiaFlowLifecycleService;
import org.secretflow.secretpad.service.bfia.BfiaGraphService;
import org.secretflow.secretpad.service.bfia.BfiaOutputStorage;
import org.secretflow.secretpad.service.graph.converter.BfiaJobConverter;
import org.secretflow.secretpad.service.model.graph.GraphNodeOutputRequest;
import org.secretflow.secretpad.service.model.graph.GraphNodeOutputVO;
import org.secretflow.secretpad.service.model.graph.GraphNodeStatusVO;
import org.secretflow.secretpad.service.model.graph.GraphStatus;
import org.secretflow.v1alpha1.kusciaapi.Job;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
class BfiaPeerPsiResult {
    static final String PATH = "/v1/interconn/flow/result/latest-psi-job";
    private static final String SUCCEEDED = "Succeeded";
    private final BfiaNodePeerChannel channel;
    private final BfiaFlowLifecycleService lifecycle;
    private final BfiaGraphService graphs;
    private final ProjectNodeRepository projectNodes;
    private final BfiaJobRepository jobs;
    private final KusciaJobService kuscia;
    private final BfiaOutputStorage storage;
    private final ObjectMapper json;

    record Lookup(String projectId, String graphId, String auditId) { }
    record Pointer(String jobId) { }

    byte[] latest(HttpHeaders headers, byte[] body) throws IOException {
        channel.authenticate(PATH, headers, body, Instant.now().getEpochSecond());
        Lookup request = json.readValue(body, Lookup.class);
        if (request == null || !validId(request.projectId()) || !validId(request.graphId())) {
            throw new IllegalArgumentException("Invalid BFIA flow lookup");
        }
        BfiaGraphDO graph = graphs.getGraphDO(request.projectId(), request.graphId());
        Lookup verified = approvedLookup(graph, request.projectId(), request.graphId(), channel.local());
        if (!Objects.equals(verified.auditId(), request.auditId())) {
            throw new IllegalArgumentException("BFIA flow approval does not match");
        }
        String jobId = jobs.findByProjectId(request.projectId()).stream()
                .filter(job -> request.graphId().equals(job.getGraphId()) && channel.local().equals(job.getInitiatorId()))
                .max(Comparator.comparing(BfiaJobDO::getGmtCreate, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(BfiaJobDO::getJobId))
                .map(BfiaJobDO::getJobId).orElse(null);
        return json.writeValueAsBytes(new Pointer(jobId));
    }

    GraphStatus status(String projectId, String graphId) {
        BfiaGraphDO graph = graphs.getGraphDO(projectId, graphId);
        if (!isPeerPsi(graph)) return null;
        var execution = execution(graph, projectId, graphId);
        if (execution == null) return null;
        var state = switch (execution.job().getStatus().getState()) {
            case SUCCEEDED -> GraphNodeTaskStatus.SUCCEED;
            case "Failed", "Cancelled", "ApprovalReject" -> GraphNodeTaskStatus.FAILED;
            default -> GraphNodeTaskStatus.RUNNING;
        };
        boolean finished = state == GraphNodeTaskStatus.SUCCEED || state == GraphNodeTaskStatus.FAILED;
        List<GraphNodeStatusVO> statuses = graph.getNodes().stream().map(node -> {
            var view = new GraphNodeStatusVO();
            view.setGraphNodeId(node.getUpk().getGraphNodeId());
            view.setJobId(execution.job().getJobId());
            if (node == execution.node()) view.setTaskId(execution.task().getTaskId());
            view.setStatus(state);
            view.setProgress(finished ? 1.0f : 0.5f);
            return view;
        }).toList();
        return new GraphStatus(finished, statuses);
    }

    GraphNodeOutputVO output(GraphNodeOutputRequest request) {
        BfiaGraphDO graph = graphs.getGraphDO(request.getProjectId(), request.getGraphId());
        if (!isPeerPsi(graph)) return null;
        var execution = execution(graph, request.getProjectId(), request.getGraphId());
        if (execution == null || !execution.node().getUpk().getGraphNodeId().equals(request.getGraphNodeId())
                || !hasOutput(execution.node(), request.getOutputId()) || !hasLocalResult(execution)) {
            throw new IllegalStateException("本方没有该 BFIA PSI 任务的可读取结果");
        }
        String jobId = execution.job().getJobId();
        String taskId = execution.task().getTaskId();
        GraphNodeOutputVO result = storage.readTable(jobId + "-guest-0", taskId + "-result", jobId,
                request.getGraphId(), execution.node().getNodeDefId());
        result.setTaskId(taskId);
        return result;
    }

    private record Execution(Job.QueryJobResponseData job, Job.TaskConfig task, BfiaGraphNodeDO node) { }

    private Execution execution(BfiaGraphDO graph, String projectId, String graphId) {
        var user = UserContext.getUser();
        if (Boolean.TRUE.equals(user.getVirtualUserForNode()) || !channel.local().equals(user.getPlatformNodeId())) {
            throw new SecurityException("BFIA result requires a local platform user");
        }
        Lookup request = approvedLookup(graph, projectId, graphId, channel.peer());
        String jobId;
        try {
            jobId = json.readValue(channel.read(PATH, json.writeValueAsBytes(request)), Pointer.class).jobId();
        } catch (IOException e) {
            throw new IllegalStateException("Invalid BFIA peer job pointer", e);
        }
        if (jobId == null) return null;
        if (!validId(jobId)) throw new IllegalArgumentException("Invalid BFIA job identifier");
        var response = kuscia.queryJob(Job.QueryJobRequest.newBuilder().setJobId(jobId).build(), channel.local());
        if (response == null || !response.hasStatus() || response.getStatus().getCode() != 0 || !response.hasData()) {
            return null;
        }
        Job.QueryJobResponseData local = response.getData();
        BfiaGraphNodeDO node = psiNodes(graph).get(0);
        if (!jobId.equals(local.getJobId()) || !channel.peer().equals(local.getInitiator()) || local.getTasksCount() != 1) {
            throw new SecurityException("BFIA local job does not match the approved flow");
        }
        Job.TaskConfig task = local.getTasks(0);
        if (!task.getAlias().equals(BfiaJobConverter.componentAlias(node.getNodeDefId(), node.getUpk().getGraphNodeId()))
                || !"ic-ecdh-psi".equals(task.getAppImage()) || task.getPartiesCount() != 2
                || !hasRole(task, channel.local(), "guest") || !hasRole(task, channel.peer(), "host")) {
            throw new SecurityException("BFIA PSI task or local participant does not match");
        }
        return new Execution(local, task, node);
    }

    private boolean hasLocalResult(Execution execution) {
        var job = execution.job();
        var task = execution.task();
        if (!validId(task.getTaskId()) || !task.getTaskId().startsWith(job.getJobId() + "-")
                || !SUCCEEDED.equals(job.getStatus().getState())) return false;
        if (job.getStatus().getTasksList().stream().noneMatch(s -> task.getTaskId().equals(s.getTaskId())
                && SUCCEEDED.equals(s.getState()) && s.getPartiesList().stream().noneMatch(p ->
                channel.local().equals(p.getDomainId()) && !SUCCEEDED.equals(p.getState())))) return false;
        try {
            var params = json.readTree(task.getTaskInputConfig());
            // The guest role is not an MPC rank; only an explicit broadcast permits this result path.
            return "ecdh_psi".equals(params.path("module_name").asText())
                    && "-1".equals(params.path("task_params").path("common").path("result_to_rank").asText());
        } catch (IOException e) {
            return false;
        }
    }

    private Lookup approvedLookup(BfiaGraphDO graph, String projectId, String graphId, String owner) {
        if (graph == null || !owner.equals(graph.getOwnerId()) || !Integer.valueOf(1).equals(graph.getIsDraft())
                || !isSimplePsi(graph)
                || !Set.copyOf(projectNodes.findProjectNodesByProjectId(projectId)).equals(Set.of(channel.local(), channel.peer()))) {
            throw new IllegalArgumentException("BFIA result requires an approved two-party PSI flow");
        }
        var approval = lifecycle.latestAudit(projectId, graphId);
        if (approval == null || !"APPROVED".equals(approval.getStatus()) || !owner.equals(approval.getApplyNodeId())) {
            throw new IllegalArgumentException("BFIA flow approval does not match");
        }
        return new Lookup(projectId, graphId, approval.getAuditId());
    }

    private boolean isPeerPsi(BfiaGraphDO graph) {
        return graph != null && !channel.peer().isBlank() && channel.peer().equals(graph.getOwnerId())
                && isSimplePsi(graph);
    }

    private static boolean isSimplePsi(BfiaGraphDO graph) {
        return psiNodes(graph).size() == 1 && graph.getNodes().size() == 3
                && graph.getNodes().stream().filter(n -> "read_data/datatable".equals(n.getNodeDefId())).count() == 2;
    }

    private static List<BfiaGraphNodeDO> psiNodes(BfiaGraphDO graph) {
        return graph.getNodes() == null ? List.of() : graph.getNodes().stream()
                .filter(node -> "隐私求交/ecdh-psi".equals(node.getNodeDefId())).toList();
    }

    private boolean hasOutput(BfiaGraphNodeDO node, String outputId) {
        if (outputId == null || !outputId.equals(node.getUpk().getGraphNodeId() + "-output-0")) return false;
        try {
            for (var port : json.readTree(node.getParams()).path("outputs")) {
                if (outputId.equals(port.asText())) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9-]{0,62}");
    }

    private static boolean hasRole(Job.TaskConfig task, String domain, String role) {
        return task.getPartiesList().stream().anyMatch(p -> domain.equals(p.getDomainId()) && role.equals(p.getRole()));
    }
}
