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
import org.secretflow.secretpad.common.util.UUIDUtils;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaGraphDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaGraphNodeDO;
import org.secretflow.secretpad.persistence.model.GraphNodeTaskStatus;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaGraphNodeRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaGraphRepository;
import org.secretflow.secretpad.service.bfia.BfiaGraphService;
import org.secretflow.secretpad.service.model.graph.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * BFIA independent graph service implementation.
 *
 * <p>Performs CRUD against the dedicated {@code bfia_graph} / {@code bfia_graph_node}
 * tables via {@link BfiaGraphRepository}, keeping BFIA flow data fully isolated from
 * the native {@code project_graph} persistence.
 *
 * <p>Node payloads are serialised to JSON and stored in {@code bfia_graph_node.params};
 * edges are serialised to JSON and stored in {@code bfia_graph.edges}.
 *
 * @author gaofeng
 */
@Slf4j
@Service
@Transactional(transactionManager = "bfiaTransactionManager", rollbackFor = Exception.class)
public class BfiaGraphServiceImpl implements BfiaGraphService {

    @Autowired
    private BfiaGraphRepository bfiaGraphRepository;

    @Autowired
    private BfiaGraphNodeRepository bfiaGraphNodeRepository;

    @org.springframework.beans.factory.annotation.Value("${secretpad.node-id}")
    private String localNodeId;

    @Override
    public GraphNodeMaxIndexRefreshVO refreshNodeMaxIndex(GraphNodeMaxIndexRefreshRequest request) {
        BfiaGraphDO graph = bfiaGraphRepository.findForIndexUpdate(request.getProjectId(), request.getGraphId())
                .orElseThrow(() -> new IllegalArgumentException("BFIA graph does not exist"));
        int next = Math.max(32, graph.getNodeMaxIndex() == null ? 32 : graph.getNodeMaxIndex());
        if (request.getCurrentIndex() != null) {
            next = Math.max(next, Math.addExact(request.getCurrentIndex(), 1));
        }
        graph.setNodeMaxIndex(Math.addExact(next, 1));
        bfiaGraphRepository.save(graph);
        return GraphNodeMaxIndexRefreshVO.builder().maxIndex(next).build();
    }

    @Override
    public CreateGraphVO createGraph(CreateGraphRequest request) {
        String projectId = request.getProjectId();
        String graphId = UUIDUtils.random(8);
        String ownerId = localNodeId;

        BfiaGraphDO graphDO = BfiaGraphDO.builder()
                .upk(new BfiaGraphDO.UPK(projectId, graphId))
                .name(request.getName())
                .ownerId(ownerId)
                .maxParallelism(1)
                .build();

        List<GraphEdge> edges = request.getEdges();
        if (!CollectionUtils.isEmpty(edges)) {
            graphDO.setEdges(JsonUtils.toJSONString(edges));
        }

        bfiaGraphRepository.save(graphDO);

        List<GraphNode> nodes = request.getNodes();
        if (!CollectionUtils.isEmpty(nodes)) {
            List<BfiaGraphNodeDO> nodeDOs = nodes.stream()
                    .map(node -> toBfiaGraphNodeDO(projectId, graphId, node))
                    .collect(Collectors.toList());
            bfiaGraphNodeRepository.saveAll(nodeDOs);
            graphDO.setNodes(nodeDOs);
        }

        return CreateGraphVO.builder().graphId(graphId).build();
    }

    @Override
    public void fullUpdateGraph(FullUpdateGraphRequest request) {
        String projectId = request.getProjectId();
        String graphId = request.getGraphId();

        Optional<BfiaGraphDO> graphOptional = bfiaGraphRepository.findById(new BfiaGraphDO.UPK(projectId, graphId));
        BfiaGraphDO graphDO;
        if (graphOptional.isEmpty()) {
            graphDO = BfiaGraphDO.builder()
                    .upk(new BfiaGraphDO.UPK(projectId, graphId))
                    .maxParallelism(request.getMaxParallelism() != null ? request.getMaxParallelism() : 1)
                    .build();
        } else {
            graphDO = graphOptional.get();
            if (Integer.valueOf(1).equals(graphDO.getIsDraft())
                    && (request.getNodes() != null || request.getEdges() != null)) {
                throw new IllegalStateException("Submitted BFIA flow cannot be edited");
            }
        }

        List<GraphEdge> edges = request.getEdges();
        if (edges != null) {
            graphDO.setEdges(JsonUtils.toJSONString(edges));
        }

        if (request.getMaxParallelism() != null) {
            graphDO.setMaxParallelism(request.getMaxParallelism());
        }

        bfiaGraphRepository.save(graphDO);

        List<GraphNodeInfo> nodes = request.getNodes();
        if (nodes != null) {
            replaceNodes(projectId, graphId, nodes, graphDO);
        }
    }

    private void replaceNodes(String projectId, String graphId, List<GraphNodeInfo> nodes, BfiaGraphDO graphDO) {
        // Never bulk-delete rows which are still managed by Hibernate and merge them again:
        // saving a config after reading the graph otherwise raises StaleObjectStateException.
        var existing = bfiaGraphNodeRepository.findByProjectIdAndGraphId(projectId, graphId).stream()
                .collect(Collectors.toMap(n -> n.getUpk().getGraphNodeId(), n -> n));
        List<BfiaGraphNodeDO> newNodes = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (GraphNodeInfo info : nodes) {
            if (info.getGraphNodeId() == null || !seen.add(info.getGraphNodeId())) {
                throw new IllegalArgumentException("Missing or duplicate BFIA graph node ID");
            }
            BfiaGraphNodeDO node = existing.remove(info.getGraphNodeId());
            BfiaGraphNodeDO incoming = toBfiaGraphNodeDO(projectId, graphId, info);
            if (node == null) {
                node = incoming;
            } else {
                node.setGraphNodeName(incoming.getGraphNodeName());
                node.setNodeDefId(incoming.getNodeDefId());
                node.setParams(incoming.getParams());
            }
            newNodes.add(node);
        }
        existing.values().forEach(n -> bfiaGraphNodeRepository.deleteRemovedNode(n.getUpk()));
        bfiaGraphNodeRepository.saveAll(newNodes);
        if (graphDO != null) {
            graphDO.setNodes(newNodes);
        }
    }

    @Override
    public GraphDetailVO getGraphDetail(GetGraphRequest request) {
        BfiaGraphDO graphDO = getGraphDO(request.getProjectId(), request.getGraphId());
        if (graphDO == null) {
            return null;
        }
        return toGraphDetailVO(graphDO);
    }

    @Override
    public List<GraphMetaVO> listGraph(ListGraphRequest request) {
        List<BfiaGraphDO> graphDOList = bfiaGraphRepository.findByProjectId(request.getProjectId());
        if (!CollectionUtils.isEmpty(graphDOList)) {
            return graphDOList.stream().map(this::toGraphMetaVO).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    @Override
    public void deleteGraph(DeleteGraphRequest request) {
        bfiaGraphNodeRepository.deleteByProjectIdAndGraphId(request.getProjectId(), request.getGraphId());
        bfiaGraphRepository.deleteById(new BfiaGraphDO.UPK(request.getProjectId(), request.getGraphId()));
    }

    @Override
    public void updateGraphNode(UpdateGraphNodeRequest request) {
        String projectId = request.getProjectId();
        String graphId = request.getGraphId();

        BfiaGraphDO graphDO = getGraphDO(projectId, graphId);
        if (graphDO == null) {
            return;
        }
        if (Integer.valueOf(1).equals(graphDO.getIsDraft())) {
            throw new IllegalStateException("Submitted BFIA flow cannot be edited");
        }

        var node = bfiaGraphNodeRepository.findById(new BfiaGraphNodeDO.UPK(projectId, graphId,
                request.getNode().getGraphNodeId())).orElseThrow(() -> new IllegalArgumentException("BFIA graph node not found"));
        var incoming = toBfiaGraphNodeDO(projectId, graphId, request.getNode());
        node.setGraphNodeName(incoming.getGraphNodeName());
        node.setNodeDefId(incoming.getNodeDefId());
        node.setParams(incoming.getParams());
        bfiaGraphNodeRepository.save(node);
    }

    @Override
    public BfiaGraphDO getGraphDO(String projectId, String graphId) {
        BfiaGraphDO graphDO = bfiaGraphRepository.findById(new BfiaGraphDO.UPK(projectId, graphId)).orElse(null);
        if (graphDO != null) {
            graphDO.setNodes(bfiaGraphNodeRepository.findByProjectIdAndGraphId(projectId, graphId));
        }
        return graphDO;
    }

    private BfiaGraphNodeDO toBfiaGraphNodeDO(String projectId, String graphId, GraphNode node) {
        return BfiaGraphNodeDO.builder()
                .upk(new BfiaGraphNodeDO.UPK(projectId, graphId, node.getGraphNodeId()))
                .graphNodeName(node.getCodeName())
                .nodeDefId(node.getCodeName())
                .params(JsonUtils.toJSONString(node))
                .build();
    }

    private BfiaGraphNodeDO toBfiaGraphNodeDO(String projectId, String graphId, GraphNodeInfo nodeInfo) {
        return BfiaGraphNodeDO.builder()
                .upk(new BfiaGraphNodeDO.UPK(projectId, graphId, nodeInfo.getGraphNodeId()))
                .graphNodeName(nodeInfo.getCodeName())
                .nodeDefId(nodeInfo.getCodeName())
                .params(JsonUtils.toJSONString(nodeInfo))
                .build();
    }

    private GraphNodeInfo toGraphNodeInfo(BfiaGraphNodeDO nodeDO) {
        GraphNodeInfo info;
        if (nodeDO.getParams() != null && !nodeDO.getParams().isEmpty()) {
            try {
                info = JsonUtils.toJavaObject(nodeDO.getParams(), GraphNodeInfo.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize bfia graph node params, nodeId={}, params={}",
                        nodeDO.getUpk().getGraphNodeId(), nodeDO.getParams(), e);
                info = new GraphNodeInfo();
            }
        } else {
            info = new GraphNodeInfo();
        }
        info.setGraphNodeId(nodeDO.getUpk().getGraphNodeId());
        return info;
    }

    private GraphDetailVO toGraphDetailVO(BfiaGraphDO graphDO) {
        List<GraphNodeDetail> nodeDetails = new ArrayList<>();
        if (!CollectionUtils.isEmpty(graphDO.getNodes())) {
            for (BfiaGraphNodeDO nodeDO : graphDO.getNodes()) {
                GraphNodeInfo info = toGraphNodeInfo(nodeDO);
                nodeDetails.add(GraphNodeDetail.builder()
                        .codeName(info.getCodeName())
                        .graphNodeId(info.getGraphNodeId())
                        .label(info.getLabel())
                        .x(info.getX())
                        .y(info.getY())
                        .inputs(info.getInputs())
                        .outputs(info.getOutputs())
                        .nodeDef(info.getNodeDef())
                        .status(GraphNodeTaskStatus.STAGING)
                        .build());
            }
        }

        List<GraphEdge> edges = new ArrayList<>();
        if (graphDO.getEdges() != null && !graphDO.getEdges().isEmpty()) {
            try {
                edges = JsonUtils.toJavaList(graphDO.getEdges(), GraphEdge.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize bfia graph edges, graphId={}, edges={}",
                        graphDO.getUpk().getGraphId(), graphDO.getEdges(), e);
            }
        }

        return GraphDetailVO.builder()
                .projectId(graphDO.getUpk().getProjectId())
                .graphId(graphDO.getUpk().getGraphId())
                .name(graphDO.getName())
                .nodes(nodeDetails)
                .edges(edges)
                .maxParallelism(graphDO.getMaxParallelism())
                .build();
    }

    private GraphMetaVO toGraphMetaVO(BfiaGraphDO graphDO) {
        return GraphMetaVO.builder()
                .projectId(graphDO.getUpk().getProjectId())
                .graphId(graphDO.getUpk().getGraphId())
                .name(graphDO.getName())
                .ownerId(graphDO.getOwnerId())
                .build();
    }
}
