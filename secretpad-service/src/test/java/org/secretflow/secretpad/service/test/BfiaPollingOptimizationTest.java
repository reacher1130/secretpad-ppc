package org.secretflow.secretpad.service.test;

import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaJobRepository;
import org.secretflow.secretpad.service.bfia.BfiaFlowService;
import org.secretflow.secretpad.service.impl.bfia.BfiaJobStatusPollingService;
import org.secretflow.secretpad.service.protocol.ProtocolConfigService;
import java.util.List;
import static org.mockito.Mockito.*;

class BfiaPollingOptimizationTest {
    @Test void failureDoesNotStarveLaterJobsAndHistoryIsNotLoaded() {
        var repository = mock(BfiaJobRepository.class);
        var flow = mock(BfiaFlowService.class);
        var protocol = mock(ProtocolConfigService.class);
        when(protocol.isActive("bfia")).thenReturn(true);
        when(repository.findUnfinishedJobIds()).thenReturn(List.of("first", "second"));
        when(flow.syncJobStatus("first")).thenThrow(new IllegalStateException("temporary network failure"));
        new BfiaJobStatusPollingService(repository, flow, protocol).poll();
        verify(flow).syncJobStatus("second");
        verify(repository, never()).findAll();
    }
    @Test void nativeNodeDoesNotQueryBfiaHistory() {
        var repository = mock(BfiaJobRepository.class);
        var flow = mock(BfiaFlowService.class);
        new BfiaJobStatusPollingService(repository, flow, mock(ProtocolConfigService.class)).poll();
        verifyNoInteractions(repository, flow);
    }
}
