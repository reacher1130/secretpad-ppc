package org.secretflow.secretpad.service.test;

import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.persistence.entity.bfia.*;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.persistence.repository.bfia.*;
import org.secretflow.secretpad.service.bfia.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BfiaFlowLifecycleTest {
    private final BfiaGraphRepository graphs = mock(BfiaGraphRepository.class);
    private final BfiaAuditRepository audits = mock(BfiaAuditRepository.class);
    private final ProjectNodeRepository participants = mock(ProjectNodeRepository.class);
    private final BfiaInterconnClient client = mock(BfiaInterconnClient.class);
    private final BfiaFlowLifecycleService service = new BfiaFlowLifecycleService(graphs, audits, participants,
            mock(BfiaGraphService.class), client);
    private final BfiaAuditDO project = BfiaAuditDO.builder().type("PROJECT").status("APPROVED").build();
    private final BfiaAuditDO flow = BfiaAuditDO.builder().auditId("audit").type("FLOW").projectId("p")
            .flowId("g").applyNodeId("alice").status("UNAPPROVED").build();

    BfiaFlowLifecycleTest() {
        ReflectionTestUtils.setField(service, "localNodeId", "alice");
        when(participants.findProjectNodesByProjectId("p")).thenReturn(List.of("alice", "bob"));
        when(audits.findByProjectId("p")).thenReturn(List.of(project, flow));
        when(audits.findById("audit")).thenReturn(Optional.of(flow));
        when(graphs.findForIndexUpdate("p", "g")).thenReturn(Optional.of(BfiaGraphDO.builder()
                .upk(new BfiaGraphDO.UPK("p", "g")).ownerId("alice").isDraft(1).build()));
    }

    @Test void rejectsPendingUnsubmittedAndUnrelatedExecution() {
        assertThrows(IllegalStateException.class, () -> service.requireExecutable("p", "g"));
        flow.setStatus("APPROVED");
        assertDoesNotThrow(() -> service.requireExecutable("p", "g"));
        project.setStatus("UNAPPROVED");
        assertThrows(IllegalStateException.class, () -> service.requireExecutable("p", "g"));
        project.setStatus("APPROVED");
        ReflectionTestUtils.setField(service, "localNodeId", "kimi");
        assertThrows(IllegalArgumentException.class, () -> service.requireExecutable("p", "g"));
    }

    @Test void requiresPeerDecisionAndPreservesFinalDecision() {
        assertThrows(IllegalArgumentException.class, () -> service.confirm("audit", "APPROVED", "alice", false));
        assertThrows(IllegalArgumentException.class, () -> service.confirm("audit", "APPROVED", "kimi", false));
        assertTrue(service.confirm("audit", "APPROVED", "bob", false));
        assertThrows(IllegalStateException.class, () -> service.confirm("audit", "REJECTED", "bob", false));
        verify(audits, times(1)).save(flow);
    }

    @Test void failedPeerPropagationDoesNotApproveLocally() {
        doThrow(new IllegalStateException("peer unavailable")).when(client)
                .postChecked(anyString(), anyString(), anyString(), any());
        assertThrows(IllegalStateException.class, () -> service.confirm("audit", "APPROVED", "bob", true));
        assertEquals("UNAPPROVED", flow.getStatus());
        verify(audits, never()).save(any());
    }
}
