package org.secretflow.secretpad.service.test;

import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaGraphDO;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaGraphRepository;
import org.secretflow.secretpad.service.impl.bfia.BfiaGraphServiceImpl;
import org.secretflow.secretpad.service.model.graph.GraphNodeMaxIndexRefreshRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BfiaGraphNodeIndexTest {
    @Test
    void allocatesMonotonicallyInBfiaGraphAndSkipsExistingNodeIds() {
        var repository = mock(BfiaGraphRepository.class);
        var service = new BfiaGraphServiceImpl();
        ReflectionTestUtils.setField(service, "bfiaGraphRepository", repository);
        var graph = BfiaGraphDO.builder().upk(new BfiaGraphDO.UPK("p", "g")).build();
        when(repository.findForIndexUpdate("p", "g")).thenReturn(Optional.of(graph));
        var request = new GraphNodeMaxIndexRefreshRequest();
        request.setProjectId("p");
        request.setGraphId("g");
        assertEquals(32, service.refreshNodeMaxIndex(request).getMaxIndex());
        assertEquals(33, service.refreshNodeMaxIndex(request).getMaxIndex());
        request.setCurrentIndex(80);
        assertEquals(81, service.refreshNodeMaxIndex(request).getMaxIndex());
        assertEquals(82, graph.getNodeMaxIndex());
        request.setGraphId("missing");
        assertThrows(IllegalArgumentException.class, () -> service.refreshNodeMaxIndex(request));
    }
}
