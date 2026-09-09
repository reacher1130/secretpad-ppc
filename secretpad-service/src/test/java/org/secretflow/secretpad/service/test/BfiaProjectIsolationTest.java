package org.secretflow.secretpad.service.test;

import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaAuditDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaProjectDO;
import org.secretflow.secretpad.persistence.repository.NodeRepository;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.persistence.repository.bfia.*;
import org.secretflow.secretpad.service.bfia.BfiaInterconnClient;
import org.secretflow.secretpad.service.impl.bfia.BfiaProjectServiceImpl;
import org.secretflow.secretpad.service.model.bfia.BfiaConfirmRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BfiaProjectIsolationTest {
    private final BfiaProjectRepositoryOffline projects = mock(BfiaProjectRepositoryOffline.class);
    private final BfiaAuditRepository audits = mock(BfiaAuditRepository.class);
    private final NodeRepository nativeNodes = mock(NodeRepository.class);
    private final ProjectNodeRepository participants = mock(ProjectNodeRepository.class);
    private final BfiaProjectServiceImpl service = new BfiaProjectServiceImpl(projects, audits,
            nativeNodes, participants, mock(BfiaDatasetRepository.class), mock(BfiaContractRepository.class),
            mock(BfiaInterconnClient.class));

    @Test
    void pendingProjectNeverBecomesApprovedOrIncludesUnrelatedNativeNodes() {
        when(projects.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                BfiaProjectDO.builder().projectId("p").contractId("bob").name("test").build())));
        when(audits.findByProjectId("p")).thenReturn(List.of(BfiaAuditDO.builder()
                .auditId("a").projectId("p").applyNodeId("alice").status("UNAPPROVED").build()));
        when(participants.findProjectNodesByProjectId("p")).thenReturn(List.of("alice", "bob"));
        var project = service.listProject(null, 1, 10).getList().get(0);
        assertEquals("UNAPPROVED", project.getStatus());
        assertEquals("alice", project.getInitiatorNodeId());
        assertEquals(List.of("alice", "bob"), project.getNodes().stream().map(n -> n.getNodeId()).toList());
        assertEquals("bob", project.getPartyVoteInfos().get(0).getNodeId());
        verifyNoInteractions(nativeNodes);
    }

    @Test
    void onlyInvitedParticipantMayApprove() {
        var audit = BfiaAuditDO.builder().auditId("a").projectId("p")
                .applyNodeId("alice").status("UNAPPROVED").build();
        when(audits.findById("a")).thenReturn(Optional.of(audit));
        when(participants.findProjectNodesByProjectId("p")).thenReturn(List.of("alice", "bob"));
        var request = BfiaConfirmRequest.builder().auditId("a").status("APPROVED").build();
        assertThrows(SecretpadException.class, () -> service.confirm(request, "alice"));
        assertThrows(SecretpadException.class, () -> service.confirm(request, "kimi"));
        assertTrue(service.confirm(request, "bob"));
        assertEquals("APPROVED", audit.getStatus());
        verify(audits, times(1)).save(audit);
    }
}
