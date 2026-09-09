package org.secretflow.secretpad.service.test;

import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.persistence.entity.bfia.*;
import org.secretflow.secretpad.persistence.repository.bfia.*;
import org.secretflow.secretpad.service.impl.bfia.BfiaGraphServiceImpl;
import org.secretflow.secretpad.service.model.graph.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BfiaGraphPersistenceTest {
    @Test void savingConfigUpdatesManagedNodeWithoutDeleteReinsert() {
        var graphs = mock(BfiaGraphRepository.class);
        var nodes = mock(BfiaGraphNodeRepository.class);
        var service = new BfiaGraphServiceImpl();
        ReflectionTestUtils.setField(service, "bfiaGraphRepository", graphs);
        ReflectionTestUtils.setField(service, "bfiaGraphNodeRepository", nodes);
        var graph = BfiaGraphDO.builder().upk(new BfiaGraphDO.UPK("p", "g")).build();
        var node = BfiaGraphNodeDO.builder().upk(new BfiaGraphNodeDO.UPK("p", "g", "n"))
                .nodeDefId("read_data/datatable").params("{}").build();
        when(graphs.findById(graph.getUpk())).thenReturn(Optional.of(graph));
        when(nodes.findById(node.getUpk())).thenReturn(Optional.of(node));
        when(nodes.findByProjectIdAndGraphId("p", "g")).thenReturn(List.of(node));
        var info = GraphNodeInfo.builder().graphNodeId("n").codeName("read_data/datatable")
                .nodeDef(Map.of("attrPaths", List.of("datatable_selected"), "attrs", List.of(Map.of("s", "data1"))))
                .build();
        var request = new UpdateGraphNodeRequest();
        request.setProjectId("p"); request.setGraphId("g"); request.setNode(info);
        service.updateGraphNode(request);
        assertTrue(node.getParams().contains("data1"));
        verify(nodes).save(same(node));
        verify(nodes, never()).deleteByProjectIdAndGraphId(anyString(), anyString());
        verify(nodes, never()).deleteRemovedNode(any());
        graph.setIsDraft(1);
        assertThrows(IllegalStateException.class, () -> service.updateGraphNode(request));
    }
}
