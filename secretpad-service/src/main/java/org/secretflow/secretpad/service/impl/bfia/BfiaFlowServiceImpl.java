/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.service.impl.bfia;

import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.common.util.RestTemplateUtil;
import org.secretflow.secretpad.common.util.UUIDUtils;
import org.secretflow.secretpad.kuscia.v1alpha1.model.DynamicKusciaGrpcConfig;
import org.secretflow.secretpad.kuscia.v1alpha1.model.KusciaGrpcConfig;
import org.secretflow.secretpad.persistence.entity.NodeDO;
import org.secretflow.secretpad.persistence.model.GraphNodeTaskStatus;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaAuditDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaGraphDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaGraphNodeDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaJobDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaResultDO;
import org.secretflow.secretpad.persistence.repository.NodeRepository;
import org.secretflow.secretpad.persistence.repository.ProjectJobTaskRepository;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaAuditRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaDatasetRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaJobRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaResultRepository;
import org.secretflow.secretpad.service.bfia.BfiaComponentService;
import org.secretflow.secretpad.service.bfia.BfiaFlowService;
import org.secretflow.secretpad.service.bfia.BfiaGraphService;
import org.secretflow.secretpad.service.bfia.BfiaInterconnClient;
import org.secretflow.secretpad.service.bfia.BfiaOutputStorage;
import org.secretflow.secretpad.service.graph.GraphContext;
import org.secretflow.secretpad.service.graph.converter.BfiaJobConverter;
import org.secretflow.secretpad.service.model.graph.*;
import org.secretflow.secretpad.service.util.JobUtils;
import org.secretflow.v1alpha1.kusciaapi.Job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BFIA flow service implementation.
 * <p>
 * CRUD of a BFIA flow is backed by the dedicated {@code bfia_graph} / {@code bfia_graph_node}
 * tables via {@link BfiaGraphService}, fully decoupled from the standard project-graph
 * persistence. The {@code start} action manually builds a {@link ProjectJob} from the BFIA
 * graph nodes (without calling {@code GraphServiceImpl.buildProjectJob}), converts it to a
 * BFIA job/create payload and submits it to the kuscia interconn control-plane HTTP API on
 * port 8084.
 *
 * @author gaofeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BfiaFlowServiceImpl implements BfiaFlowService {

    private static final String JOB_CREATE_PATH = "/v1/interconn/schedule/job/create";

    private final BfiaGraphService bfiaGraphService;
    private final BfiaComponentService bfiaComponentService;
    private final BfiaJobConverter bfiaJobConverter;
    private final DynamicKusciaGrpcConfig dynamicKusciaGrpcConfig;
    private final NodeRepository nodeRepository;
    private final ProjectNodeRepository projectNodeRepository;
    private final BfiaJobRepository bfiaJobRepository;
    private final BfiaResultRepository bfiaResultRepository;
    private final BfiaInterconnClient bfiaInterconnClient;
    private final BfiaOutputStorage bfiaOutputStorage;
    private final org.secretflow.secretpad.kuscia.v1alpha1.service.KusciaJobService kusciaJobService;

    @Value("${KUSCIA_API_LITE_ALICE_ADDRESS:}")
    private String aliceKusciaHost;

    @Value("${KUSCIA_API_LITE_BOB_ADDRESS:}")
    private String bobKusciaHost;
    /**
     * Optional: only present when the BFIA datasource is configured. Used for flow audit persistence.
     * Wrapped in ObjectProvider so this always-on bean does not hard-depend on the BFIA datasource.
     */
    private final ObjectProvider<BfiaAuditRepository> auditRepositoryProvider;
    private final ObjectProvider<ProjectJobTaskRepository> taskRepositoryProvider;
    private final ObjectProvider<BfiaDatasetRepository> datasetRepositoryProvider;

    @Override
    public Map<String, CompListVO> listComponents() {
        return bfiaComponentService.listCompListVO();
    }

    @Override
    public List<Map<String, Object>> batchGetComponent(List<GetComponentRequest> request) {
        // BFIA component specs (with inputs/outputs/attrs) come from config/bfiacomponents,
        // not the standard SecretFlow registry. Return the full set; the frontend keys them
        // by domain/name when rendering ports and the config form.
        return bfiaComponentService.listComponents();
    }

    @Override
    public StartGraphVO startGraph(StartGraphRequest request) {
        try {
            // 从 bfia_graph 读取图节点（不再依赖标准 project_graph 表）
            BfiaGraphDO graphDO = bfiaGraphService.getGraphDO(request.getProjectId(), request.getGraphId());
            if (graphDO == null || CollectionUtils.isEmpty(graphDO.getNodes())) {
                throw new IllegalStateException("BFIA flow graph is empty or not found");
            }

            // 自动填充 nodes（如果请求中没有指定节点列表）
            if (CollectionUtils.isEmpty(request.getNodes())) {
                List<String> allNodeIds = new ArrayList<>();
                for (BfiaGraphNodeDO node : graphDO.getNodes()) {
                    allNodeIds.add(node.getUpk().getGraphNodeId());
                }
                request.setNodes(allNodeIds);
            }

            // 手动构建 ProjectJob（不再调用 GraphServiceImpl.buildProjectJob）
            String jobId = UUIDUtils.random(4);
            List<ProjectJob.JobTask> tasks = new ArrayList<>();
            for (BfiaGraphNodeDO nodeDO : graphDO.getNodes()) {
                String graphNodeId = nodeDO.getUpk().getGraphNodeId();
                if (!request.getNodes().contains(graphNodeId)) {
                    continue;
                }
                // 将 BfiaGraphNodeDO 的 params JSON 反序列化为 GraphNodeInfo，
                // 再提取其中真正的 nodeDef（画布算法参数），避免把 GraphNodeInfo 外壳当成 nodeDef。
                GraphNodeInfo storedInfo = null;
                String params = nodeDO.getParams();
                if (params != null && !params.isBlank()) {
                    try {
                        storedInfo = JsonUtils.toJavaObject(params, GraphNodeInfo.class);
                    } catch (Exception e) {
                        log.warn("BFIA startGraph: failed to deserialize node params, graphNodeId={}", graphNodeId, e);
                    }
                }
                GraphNodeInfo nodeInfo = GraphNodeInfo.builder()
                        .codeName(nodeDO.getNodeDefId())
                        .graphNodeId(graphNodeId)
                        .nodeDef(storedInfo != null ? storedInfo.getNodeDef() : null)
                        .inputs(storedInfo != null && storedInfo.getInputs() != null ? storedInfo.getInputs() : new ArrayList<>())
                        .outputs(storedInfo != null && storedInfo.getOutputs() != null ? storedInfo.getOutputs() : new ArrayList<>())
                        .build();
                ProjectJob.JobTask task = ProjectJob.JobTask.builder()
                        .taskId(JobUtils.genTaskId(jobId, graphNodeId))
                        .parties(new ArrayList<>())
                        .node(nodeInfo)
                        .build();
                tasks.add(task);
            }

            ProjectJob job = ProjectJob.builder()
                    .jobId(jobId)
                    .projectId(request.getProjectId())
                    .graphId(request.getGraphId())
                    .name(graphDO.getName())
                    .tasks(tasks)
                    .build();

            if (CollectionUtils.isEmpty(job.getTasks())) {
                throw new IllegalStateException("BFIA flow has no selected nodes to start");
            }
            ensureParties(job);
            validateNodeDatasets(job);
            String initiatorId = resolveInitiator(job);
            Map<String, Object> payload = bfiaJobConverter.convert(job, initiatorId);

            String url = resolveJobCreateUrl(initiatorId);
            log.info("Submit BFIA job to kuscia interconn API: url={}, jobId={}", url, job.getJobId());
            // Use the 3-arg sendPostJson (returns ResponseEntity<String>) instead of the 4-arg
            // version: the 4-arg version calls JsonUtils.toJavaObject(body, String.class) which
            // fails when the response body is a JSON object (Jackson cannot extract a scalar
            // String from an object token).
            org.springframework.http.ResponseEntity<String> respEntity =
                    RestTemplateUtil.sendPostJson(url, payload, new HashMap<>());
            String responseBody = respEntity.getBody();
            verifyResponse(responseBody);
            persistJobRecords(job, initiatorId);

            return new StartGraphVO(job.getJobId());
        } finally {
            if (!Boolean.TRUE.equals(GraphContext.isScheduled())) {
                GraphContext.remove();
            }
        }
    }

    /**
     * BFIA flows do not carry native SecretPad datatables, so the standard
     * {@code findParties} (which resolves parties from datatable→node associations)
     * returns empty. Fall back to the registered lite/autonomy nodes (mode=1)
     * so the BFIA job converter can build proper per-party params.
     */
    private void ensureParties(ProjectJob job) {
        boolean needsFallback = job.getTasks().stream()
                .anyMatch(t -> CollectionUtils.isEmpty(t.getParties()));
        if (!needsFallback) {
            return;
        }
        List<String> domainIds = new ArrayList<>();
        // 1) BFIA 项目的参与方记录在 project_node 表（BfiaProjectController.apply 已写入）。
        try {
            if (StringUtils.isNotBlank(job.getProjectId()) && projectNodeRepository != null) {
                List<org.secretflow.secretpad.persistence.entity.ProjectNodeDO> projectNodes =
                        projectNodeRepository.findByProjectId(job.getProjectId());
                for (org.secretflow.secretpad.persistence.entity.ProjectNodeDO pn : projectNodes) {
                    String nodeId = pn.getUpk().getNodeId();
                    if (StringUtils.isNotBlank(nodeId) && !domainIds.contains(nodeId)) {
                        domainIds.add(nodeId);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("BFIA ensureParties: failed to query project nodes", e);
        }
        // 2) Fall back to lite/autonomy node directory.
        if (domainIds.isEmpty()) {
            try {
                List<NodeDO> liteNodes = nodeRepository.findByModeIn(List.of(1));
                for (NodeDO node : liteNodes) {
                    if (node.getNodeId() != null && !domainIds.contains(node.getNodeId())) {
                        domainIds.add(node.getNodeId());
                    }
                }
            } catch (Exception e) {
                log.warn("BFIA ensureParties: failed to query lite nodes from nodeRepository", e);
            }
        }
        // 3) Last resort: dynamic kuscia gRPC config.
        if (domainIds.isEmpty() && dynamicKusciaGrpcConfig.getNodes() != null) {
            for (KusciaGrpcConfig node : dynamicKusciaGrpcConfig.getNodes()) {
                if (node.getDomainId() != null && !domainIds.contains(node.getDomainId())) {
                    domainIds.add(node.getDomainId());
                }
            }
        }
        if (domainIds.isEmpty()) {
            return;
        }
        log.info("BFIA parties fallback: using node IDs {}", domainIds);
        for (ProjectJob.JobTask task : job.getTasks()) {
            if (CollectionUtils.isEmpty(task.getParties())) {
                task.setParties(domainIds);
            }
        }
    }

    /**
     * Validate that every dataset referenced by a BFIA node (host_dataset_id / guest_dataset_id)
     * actually exists in the BFIA dataset registry. This prevents Kuscia from creating a job whose
     * task params miss the partner namespace/name and then hanging in PENDING forever.
     */
    private void validateNodeDatasets(ProjectJob job) {
        BfiaDatasetRepository datasetRepository = datasetRepositoryProvider.getIfAvailable();
        if (datasetRepository == null) {
            return;
        }
        for (ProjectJob.JobTask task : job.getTasks()) {
            GraphNodeInfo node = task.getNode();
            if (node == null || node.getNodeDef() == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> nodeDef = (Map<String, Object>) node.getNodeDef();
            List<String> datasetIds = new ArrayList<>();
            if (nodeDef.get("host_dataset_id") instanceof String hostId && StringUtils.isNotBlank(hostId)) {
                datasetIds.add(hostId);
            }
            if (nodeDef.get("guest_dataset_id") instanceof String guestId && StringUtils.isNotBlank(guestId)) {
                datasetIds.add(guestId);
            }
            for (String datasetId : datasetIds) {
                if (!datasetRepository.existsById(datasetId)) {
                    throw new IllegalArgumentException(
                            "BFIA node " + node.getGraphNodeId() + " references unknown dataset: " + datasetId);
                }
            }
        }
    }

    @Override
    public CreateGraphVO createFlow(CreateGraphRequest request) {
        return bfiaGraphService.createGraph(request);
    }

    @Override
    public List<GraphMetaVO> listFlow(String projectId) {
        ListGraphRequest req = new ListGraphRequest();
        req.setProjectId(projectId);
        return bfiaGraphService.listGraph(req);
    }

    @Override
    public Map<String, String> applyFlowAudit(String projectId, String flowId, String contractId, String applyNodeId) {
        BfiaAuditRepository auditRepository = auditRepositoryProvider.getIfAvailable();
        Map<String, String> result = new HashMap<>();
        String auditId = UUID.randomUUID().toString().replaceAll("-", "");
        if (auditRepository == null) {
            // BFIA datasource not configured: degrade gracefully to auto-approved single-node flow.
            result.put("audit_id", auditId);
            result.put("status", "APPROVED");
            return result;
        }
        BfiaAuditDO audit = BfiaAuditDO.builder()
                .auditId(auditId)
                .contractId(StringUtils.defaultIfBlank(contractId, ""))
                .applyNodeId(StringUtils.defaultIfBlank(applyNodeId, ""))
                .type("FLOW")
                .projectId(projectId)
                .flowId(flowId)
                .status("APPLIED")
                .build();
        auditRepository.save(audit);
        result.put("audit_id", auditId);
        result.put("status", "APPLIED");
        return result;
    }

    @Override
    public Boolean confirmFlowAudit(String auditId, String status) {
        BfiaAuditRepository auditRepository = auditRepositoryProvider.getIfAvailable();
        if (auditRepository == null) {
            return Boolean.TRUE;
        }
        BfiaAuditDO audit = auditRepository.findById(auditId).orElse(null);
        if (audit == null) {
            return Boolean.FALSE;
        }
        audit.setStatus(StringUtils.defaultIfBlank(status, "APPROVED"));
        auditRepository.save(audit);
        return Boolean.TRUE;
    }

    /**
     * Resolve the initiator domain id: the first party of the first task.
     */
    private String resolveInitiator(ProjectJob job) {
        for (ProjectJob.JobTask task : job.getTasks()) {
            List<String> parties = task.getParties();
            if (!CollectionUtils.isEmpty(parties)) {
                return parties.get(0);
            }
        }
        throw new IllegalStateException("Cannot resolve BFIA job initiator: no party found");
    }

    /**
     * Resolve the kuscia interconn HTTP url for the initiator node ({@code host:8084}).
     * Tries the environment-configured lite node addresses first (the authoritative source
     * for the BFIA HTTP port), then falls back to the dynamic Kuscia gRPC config.
     */
    private String resolveJobCreateUrl(String initiatorId) {
        // 1. Try environment-configured lite node addresses
        if (initiatorId != null) {
            String host = null;
            if ("alice".equals(initiatorId) && StringUtils.isNotBlank(aliceKusciaHost)) {
                host = aliceKusciaHost;
            } else if ("bob".equals(initiatorId) && StringUtils.isNotBlank(bobKusciaHost)) {
                host = bobKusciaHost;
            }
            if (host != null) {
                return buildInterconnUrl(host);
            }
        }
        // 2. Try NodeRepository netAddress (local proxy host)
        if (initiatorId != null) {
            try {
                NodeDO node = nodeRepository.findByNodeId(initiatorId);
                if (node != null && StringUtils.isNotBlank(node.getNetAddress())) {
                    return buildInterconnUrl(node.getNetAddress());
                }
            } catch (Exception e) {
                log.warn("BFIA resolveJobCreateUrl: failed to look up node '{}'", initiatorId, e);
            }
        }
        // 3. Fall back to dynamic Kuscia gRPC config
        KusciaGrpcConfig target = null;
        if (dynamicKusciaGrpcConfig.getNodes() != null) {
            for (KusciaGrpcConfig node : dynamicKusciaGrpcConfig.getNodes()) {
                if (initiatorId != null && initiatorId.equals(node.getDomainId())) {
                    target = node;
                    break;
                }
                if (target == null) {
                    target = node;
                }
            }
        }
        if (target == null) {
            throw new IllegalStateException("No kuscia node configured for BFIA job submission");
        }
        return "http://" + target.getHost() + ":" + target.getHttpPort() + JOB_CREATE_PATH;
    }

    /**
     * Build BFIA interconn URL from an address that may include scheme/port.
     * Preserves an explicit port (e.g. localhost:18084) so that local proxies work;
     * falls back to the standard BFIA HTTP port 8084 when no port is given.
     */
    private String buildInterconnUrl(String address) {
        if (address == null) {
            return "";
        }
        String hostPort = address;
        int schemeEnd = hostPort.indexOf("://");
        if (schemeEnd >= 0) {
            hostPort = hostPort.substring(schemeEnd + 3);
        }
        if (!hostPort.contains(":")) {
            hostPort = hostPort + ":8084";
        }
        return "http://" + hostPort + JOB_CREATE_PATH;
    }

    /**
     * @deprecated Use {@link #buildInterconnUrl(String)} to preserve explicit ports.
     */
    @SuppressWarnings("unused")
    private String extractHost(String address) {
        if (address == null) {
            return "";
        }
        String host = address;
        int schemeEnd = host.indexOf("://");
        if (schemeEnd >= 0) {
            host = host.substring(schemeEnd + 3);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host;
    }

    /**
     * Persist the BFIA job record into the dedicated {@code bfia_job} table after
     * successful submission. Job status is set to {@code SUBMITTED}; the BFIA runtime
     * will advance status via the interconn status-sync path.
     */
    private void persistJobRecords(ProjectJob job, String initiatorId) {
        try {
            BfiaJobDO jobDO = BfiaJobDO.builder()
                    .jobId(job.getJobId())
                    .projectId(job.getProjectId())
                    .graphId(job.getGraphId())
                    .initiatorId(initiatorId)
                    .status("SUBMITTED")
                    .build();
            bfiaJobRepository.save(jobDO);
            log.info("Persisted BFIA job record: projectId={}, jobId={}, graphId={}, initiatorId={}",
                    job.getProjectId(), job.getJobId(), job.getGraphId(), initiatorId);
        } catch (Exception e) {
            log.warn("Failed to persist BFIA job record for {}", job.getJobId(), e);
        }
        // Persist result placeholders for each task so the standalone result manager
        // can list them. outputUri is left null and filled when the job completes.
        try {
            for (ProjectJob.JobTask task : job.getTasks()) {
                String codeName = task.getNode() != null ? task.getNode().getCodeName() : "";
                String kind = inferResultKind(codeName);
                String graphNodeId = task.getNode() != null ? task.getNode().getGraphNodeId() : task.getTaskId();
                BfiaResultDO resultDO = BfiaResultDO.builder()
                        .resultId(UUID.randomUUID().toString().replace("-", ""))
                        .projectId(job.getProjectId())
                        .jobId(job.getJobId())
                        .taskId(task.getTaskId())
                        .nodeId(initiatorId)
                        .kind(kind)
                        .refId(graphNodeId)
                        .build();
                bfiaResultRepository.save(resultDO);
            }
            log.info("Persisted {} BFIA result records for job {}", job.getTasks().size(), job.getJobId());
        } catch (Exception e) {
            log.warn("Failed to persist BFIA result records for {}", job.getJobId(), e);
        }
    }

    /**
     * Infer the result kind from the component codeName.
     * e.g. "report" → report, "model" / "ss-xgb" → model, others → table
     */
    private String inferResultKind(String codeName) {
        if (codeName == null) {
            return "table";
        }
        String lower = codeName.toLowerCase();
        if (lower.contains("report")) {
            return "report";
        }
        if (lower.contains("model") || lower.contains("xgb") || lower.contains("lr") || lower.contains("sgb")) {
            return "model";
        }
        if (lower.contains("rule")) {
            return "rule";
        }
        return "table";
    }

    /**
     * BFIA control-plane returns HTTP 200 always; success is {@code code == 0} or {@code code == 200}.
     */
    private void verifyResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new IllegalStateException("Empty response from kuscia interconn job/create");
        }
        Map<String, Object> resp = JsonUtils.toJavaMap(responseBody);
        Object code = resp.get("code");
        String codeStr = code == null ? "" : String.valueOf(code);
        if (!"0".equals(codeStr) && !"200".equals(codeStr)) {
            Object msg = resp.getOrDefault("msg", resp.get("message"));
            throw new IllegalStateException("BFIA job/create failed: code=" + code + ", msg=" + msg);
        }
    }

    // ========================= BFIA status / output hooks =========================

    @Override
    public GraphStatus listGraphNodeStatus(ListGraphNodeStatusRequest request) {
        BfiaGraphDO graphDO = bfiaGraphService.getGraphDO(request.getProjectId(), request.getGraphId());
        if (graphDO == null) {
            throw new IllegalArgumentException("BFIA flow not found: " + request.getGraphId());
        }
        // Find the latest BFIA job for this graph and sync its status.
        List<BfiaJobDO> jobs = bfiaJobRepository.findByProjectId(request.getProjectId());
        BfiaJobDO latest = null;
        for (BfiaJobDO job : jobs) {
            if (request.getGraphId().equals(job.getGraphId())) {
                if (latest == null || job.getGmtCreate() == null ||
                        (latest.getGmtCreate() != null && job.getGmtCreate().isAfter(latest.getGmtCreate()))) {
                    latest = job;
                }
            }
        }
        if (latest != null && StringUtils.isNotBlank(latest.getJobId())) {
            latest = syncJobStatus(latest.getJobId());
        }

        GraphStatus graphStatus = new GraphStatus();
        List<GraphNodeStatusVO> nodeStatusList = new ArrayList<>();
        GraphNodeTaskStatus taskStatus = GraphNodeTaskStatus.STAGING;
        boolean finished = false;
        if (latest != null && StringUtils.isNotBlank(latest.getStatus())) {
            taskStatus = mapBfiaStatusToTaskStatus(latest.getStatus());
            finished = isTerminalStatus(latest.getStatus());
        }
        for (BfiaGraphNodeDO nodeDO : graphDO.getNodes()) {
            GraphNodeStatusVO vo = new GraphNodeStatusVO();
            vo.setGraphNodeId(nodeDO.getUpk().getGraphNodeId());
            vo.setStatus(taskStatus);
            vo.setJobId(latest != null ? latest.getJobId() : null);
            vo.setProgress(finished ? 1.0f : (latest != null ? 0.5f : 0.0f));
            nodeStatusList.add(vo);
        }
        graphStatus.setNodes(nodeStatusList);
        graphStatus.setFinished(finished);
        return graphStatus;
    }

    @Override
    public GraphNodeOutputVO getGraphNodeOutput(GraphNodeOutputRequest request) {
        String projectId = request.getProjectId();
        String graphId = request.getGraphId();
        String graphNodeId = request.getGraphNodeId();

        BfiaGraphDO graphDO = bfiaGraphService.getGraphDO(projectId, graphId);
        if (graphDO == null) {
            throw new IllegalArgumentException("BFIA flow not found: " + graphId);
        }

        // Find the latest job for this graph/node and ensure status is fresh.
        List<BfiaJobDO> jobs = bfiaJobRepository.findByProjectId(projectId);
        BfiaJobDO latest = null;
        for (BfiaJobDO job : jobs) {
            if (graphId.equals(job.getGraphId())) {
                if (latest == null || job.getGmtCreate() == null ||
                        (latest.getGmtCreate() != null && job.getGmtCreate().isAfter(latest.getGmtCreate()))) {
                    latest = job;
                }
            }
        }
        if (latest == null) {
            throw new IllegalStateException("BFIA job not found for graph: " + graphId);
        }
        latest = syncJobStatus(latest.getJobId());

        // Find the result record for this node.
        List<BfiaResultDO> results = bfiaResultRepository.findByProjectId(projectId);
        BfiaResultDO result = null;
        for (BfiaResultDO r : results) {
            if (latest.getJobId().equals(r.getJobId()) && graphNodeId.equals(r.getRefId())) {
                result = r;
                break;
            }
        }
        if (result == null || StringUtils.isBlank(result.getOutputUri())) {
            throw new IllegalStateException("BFIA output not available yet for node: " + graphNodeId);
        }

        // outputUri format: "<namespace>/<name>"
        String outputUri = result.getOutputUri();
        int slash = outputUri.indexOf('/');
        if (slash <= 0 || slash >= outputUri.length() - 1) {
            throw new IllegalStateException("Invalid BFIA output uri: " + outputUri);
        }
        String namespace = outputUri.substring(0, slash);
        String name = outputUri.substring(slash + 1);
        String content = bfiaOutputStorage.readText(namespace, name);
        if (content.isBlank()) {
            throw new IllegalStateException("BFIA output file is empty or unreadable: " + outputUri);
        }

        GraphNodeOutputVO outputVO = GraphNodeOutputVO.builder().build();
        outputVO.setType("table");
        outputVO.setJobId(latest.getJobId());
        outputVO.setGraphID(graphId);
        outputVO.setCodeName(graphDO.getNodes().stream()
                .filter(n -> graphNodeId.equals(n.getUpk().getGraphNodeId()))
                .findFirst().map(BfiaGraphNodeDO::getNodeDefId).orElse(null));

        List<Map<String, Object>> rows = parseCsvRows(content);
        List<Map<String, String>> headers = new ArrayList<>();
        if (!rows.isEmpty()) {
            for (String key : rows.get(0).keySet()) {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("name", key);
                h.put("type", "AT_STRING");
                headers.add(h);
            }
        }
        GraphNodeOutputVO.FileMeta meta = GraphNodeOutputVO.FileMeta.builder()
                .headers(headers)
                .rows(rows)
                .build();
        outputVO.setMeta(meta);
        return outputVO;
    }

    @Override
    public BfiaJobDO syncJobStatus(String jobId) {
        BfiaJobDO job = bfiaJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("BFIA syncJobStatus: job not found in bfia_job, jobId={}", jobId);
            return null;
        }
        String initiatorId = StringUtils.defaultIfBlank(job.getInitiatorId(), resolveInitiatorFromProject(job));
        if (StringUtils.isBlank(initiatorId)) {
            log.warn("BFIA syncJobStatus: cannot resolve initiator, jobId={}", jobId);
            return job;
        }
        try {
            Map<String, Object> resp = bfiaInterconnClient.queryJobStatus(initiatorId, jobId);
            Object codeObj = resp.get("code");
            if (codeObj != null && !"0".equals(String.valueOf(codeObj))) {
                log.warn("BFIA syncJobStatus: status query failed, jobId={}, resp={}", jobId, resp);
                return job;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data == null) {
                return job;
            }
            String jobStatus = (String) data.get("jobStatus");
            if (StringUtils.isBlank(jobStatus)) {
                return job;
            }
            String upper = jobStatus.toUpperCase();
            job.setStatus(upper);
            bfiaJobRepository.save(job);
            log.info("BFIA syncJobStatus: jobId={}, status={}", jobId, upper);

            if ("SUCCESS".equals(upper)) {
                syncResultOutputUri(job, data);
            }
            return job;
        } catch (Exception e) {
            log.warn("BFIA syncJobStatus: exception, jobId={}", jobId, e);
            return job;
        }
    }

    /**
     * Derive the initiator domain id from the project nodes when the persisted job record
     * does not carry one.
     */
    private String resolveInitiatorFromProject(BfiaJobDO job) {
        if (StringUtils.isBlank(job.getProjectId())) {
            return null;
        }
        try {
            List<org.secretflow.secretpad.persistence.entity.ProjectNodeDO> nodes =
                    projectNodeRepository.findByProjectId(job.getProjectId());
            if (!CollectionUtils.isEmpty(nodes)) {
                return nodes.get(0).getUpk().getNodeId();
            }
        } catch (Exception e) {
            log.warn("Failed to resolve initiator from project nodes: projectId={}", job.getProjectId(), e);
        }
        return null;
    }

    /**
     * Populate {@code bfia_result.outputUri} for all results of a succeeded job.
     * The URI format is {@code <namespace>/<name>} so that the download endpoint can
     * locate the physical file in Kuscia storage.
     * <p>
     * Kuscia generates random task ids, so we first query the kuscia job spec to map
     * component alias ({@code dag.components[].name}) to kuscia task id, then map the
     * alias to the graph node via the normalized component name. When the kuscia query
     * is unavailable or leaves gaps, we fall back to the status map returned by
     * {@code /v1/interconn/schedule/job/status_all}.
     */
    private void syncResultOutputUri(BfiaJobDO job, Map<String, Object> statusData) {
        List<BfiaResultDO> results = bfiaResultRepository.findByProjectId(job.getProjectId());
        List<BfiaResultDO> jobResults = new ArrayList<>();
        for (BfiaResultDO result : results) {
            if (job.getJobId().equals(result.getJobId()) && StringUtils.isBlank(result.getOutputUri())) {
                jobResults.add(result);
            }
        }
        if (jobResults.isEmpty()) {
            return;
        }

        // 1. Best-effort alias -> kuscia task id from the kuscia job spec.
        Map<String, String> aliasToTaskId = queryKusciaTaskAliases(job);
        if (aliasToTaskId != null && !aliasToTaskId.isEmpty()) {
            BfiaGraphDO graphDO = safeGetGraph(job.getProjectId(), job.getGraphId());
            for (BfiaResultDO result : jobResults) {
                if (StringUtils.isNotBlank(result.getOutputUri())) {
                    continue;
                }
                String alias = graphNodeToAlias(graphDO, result.getRefId());
                String taskId = alias == null ? null : aliasToTaskId.get(alias);
                if (taskId != null) {
                    setResultOutputUri(result, job.getJobId(), taskId);
                }
            }
        }

        // 2. Fallback: if exactly one unresolved result and the status data gives a single
        //    kuscia task id, assign it directly (common single-component BFIA DAG case).
        if (jobResults.size() == 1) {
            BfiaResultDO left = jobResults.get(0);
            if (StringUtils.isBlank(left.getOutputUri())) {
                String taskId = singleTaskIdFromStatus(statusData);
                if (taskId != null) {
                    setResultOutputUri(left, job.getJobId(), taskId);
                }
            }
        }
    }

    private void setResultOutputUri(BfiaResultDO result, String jobId, String taskId) {
        String namespace = jobId + "-host-0";
        String name = taskId + "-result";
        String uri = namespace + "/" + name;
        result.setOutputUri(uri);
        bfiaResultRepository.save(result);
        log.info("BFIA result output uri set: resultId={}, uri={}", result.getResultId(), uri);
    }

    private BfiaGraphDO safeGetGraph(String projectId, String graphId) {
        try {
            return bfiaGraphService.getGraphDO(projectId, graphId);
        } catch (Exception e) {
            log.warn("BFIA safeGetGraph failed: projectId={}, graphId={}", projectId, graphId, e);
            return null;
        }
    }

    /**
     * Map a graph node id to the BFIA component alias used in the kuscia job spec.
     * The alias equals the normalized component code name (see {@link BfiaJobConverter}).
     */
    private String graphNodeToAlias(BfiaGraphDO graphDO, String graphNodeId) {
        if (graphDO == null || graphDO.getNodes() == null || graphNodeId == null) {
            return null;
        }
        for (BfiaGraphNodeDO node : graphDO.getNodes()) {
            if (graphNodeId.equals(node.getUpk().getGraphNodeId())) {
                return normalizeComponentName(node.getNodeDefId());
            }
        }
        return null;
    }

    private String normalizeComponentName(String codeName) {
        if (codeName == null) {
            return "";
        }
        String name = codeName;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(0, colon);
        }
        return name.trim().toLowerCase().replace('_', '-');
    }

    /**
     * Query the kuscia job spec for component alias -> kuscia task id mapping.
     */
    private Map<String, String> queryKusciaTaskAliases(BfiaJobDO job) {
        String initiatorId = StringUtils.defaultIfBlank(job.getInitiatorId(), resolveInitiatorFromProject(job));
        if (StringUtils.isBlank(initiatorId) || kusciaJobService == null) {
            return Collections.emptyMap();
        }
        try {
            Job.QueryJobRequest request = Job.QueryJobRequest.newBuilder()
                    .setJobId(job.getJobId())
                    .build();
            Job.QueryJobResponse response = kusciaJobService.queryJob(request, initiatorId);
            if (response == null || response.getStatus() == null || response.getStatus().getCode() != 0) {
                log.warn("BFIA queryKusciaTaskAliases failed: jobId={}, status={}", job.getJobId(),
                        response == null ? "null" : response.getStatus());
                return Collections.emptyMap();
            }
            Map<String, String> aliasToTaskId = new HashMap<>();
            for (Job.TaskConfig task : response.getData().getTasksList()) {
                if (StringUtils.isNotBlank(task.getAlias()) && StringUtils.isNotBlank(task.getTaskId())) {
                    aliasToTaskId.put(task.getAlias(), task.getTaskId());
                }
            }
            log.info("BFIA queryKusciaTaskAliases: jobId={}, aliases={}", job.getJobId(), aliasToTaskId.keySet());
            return aliasToTaskId;
        } catch (Exception e) {
            log.warn("BFIA queryKusciaTaskAliases exception: jobId={}", job.getJobId(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * Extract the single kuscia task id from the {@code status_all} response data
     * ({@code data.status} map). Returns null when there is not exactly one task.
     */
    @SuppressWarnings("unchecked")
    private String singleTaskIdFromStatus(Map<String, Object> statusData) {
        if (statusData == null) {
            return null;
        }
        Object statusObj = statusData.get("status");
        if (!(statusObj instanceof Map)) {
            return null;
        }
        Map<String, Object> statusMap = (Map<String, Object>) statusObj;
        if (statusMap.size() != 1) {
            return null;
        }
        String taskId = statusMap.keySet().iterator().next();
        return StringUtils.isBlank(taskId) ? null : taskId;
    }

    private GraphNodeTaskStatus mapBfiaStatusToTaskStatus(String status) {
        return switch (StringUtils.defaultString(status).toUpperCase()) {
            case "RUNNING", "PENDING" -> GraphNodeTaskStatus.RUNNING;
            case "SUCCESS" -> GraphNodeTaskStatus.SUCCEED;
            case "FAILED" -> GraphNodeTaskStatus.FAILED;
            default -> GraphNodeTaskStatus.STAGING;
        };
    }

    private boolean isTerminalStatus(String status) {
        String upper = StringUtils.defaultString(status).toUpperCase();
        return "SUCCESS".equals(upper) || "FAILED".equals(upper);
    }

    /**
     * Parse a simple CSV (comma-separated, first line header) into a list of row maps.
     */
    private List<Map<String, Object>> parseCsvRows(String content) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String[] lines = content.split("\r?\n");
        if (lines.length < 2) {
            return rows;
        }
        String[] headers = lines[0].split(",");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] values = lines[i].split(",");
            Map<String, Object> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.length; j++) {
                row.put(headers[j].trim(), j < values.length ? values[j].trim() : "");
            }
            rows.add(row);
        }
        return rows;
    }
}
