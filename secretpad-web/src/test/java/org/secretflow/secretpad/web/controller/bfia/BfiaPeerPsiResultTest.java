package org.secretflow.secretpad.web.controller.bfia;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.common.dto.UserContextDTO;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.kuscia.v1alpha1.service.KusciaJobService;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaAuditDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaGraphDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaGraphNodeDO;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaJobRepository;
import org.secretflow.secretpad.service.bfia.BfiaFlowLifecycleService;
import org.secretflow.secretpad.service.bfia.BfiaGraphService;
import org.secretflow.secretpad.service.bfia.BfiaInterconnClient;
import org.secretflow.secretpad.service.bfia.BfiaOutputStorage;
import org.secretflow.secretpad.service.graph.converter.BfiaJobConverter;
import org.secretflow.secretpad.service.model.graph.GraphNodeOutputRequest;
import org.secretflow.secretpad.service.model.graph.GraphNodeOutputVO;
import org.secretflow.v1alpha1.kusciaapi.Job;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BfiaPeerPsiResultTest {
    private static final String CODE = "隐私求交/ecdh-psi";
    private static final String ALIAS = BfiaJobConverter.componentAlias(CODE, "psi");
    private final BfiaNodePeerChannel channel = mock(BfiaNodePeerChannel.class);
    private final BfiaFlowLifecycleService lifecycle = mock(BfiaFlowLifecycleService.class);
    private final BfiaGraphService graphs = mock(BfiaGraphService.class);
    private final ProjectNodeRepository participants = mock(ProjectNodeRepository.class);
    private final BfiaJobRepository jobs = mock(BfiaJobRepository.class);
    private final KusciaJobService kuscia = mock(KusciaJobService.class);
    private final BfiaOutputStorage storage = mock(BfiaOutputStorage.class);
    private final BfiaPeerPsiResult service = new BfiaPeerPsiResult(channel, lifecycle, graphs, participants,
            jobs, kuscia, storage, new ObjectMapper());

    @BeforeEach
    void approvedPeerFlow() {
        UserContext.setBaseUser(UserContextDTO.builder().platformNodeId("bob").ownerId("bob-inst").build());
        when(channel.local()).thenReturn("bob");
        when(channel.peer()).thenReturn("alice");
        when(channel.read(eq(BfiaPeerPsiResult.PATH), any())).thenReturn(bytes("{\"jobId\":\"job1\"}"));
        when(participants.findProjectNodesByProjectId("project")).thenReturn(List.of("alice", "bob"));
        when(lifecycle.latestAudit("project", "flow")).thenReturn(BfiaAuditDO.builder()
                .auditId("audit").status("APPROVED").applyNodeId("alice").build());
        when(graphs.getGraphDO("project", "flow")).thenReturn(BfiaGraphDO.builder().ownerId("alice").isDraft(1)
                .nodes(List.of(node("a", "read_data/datatable"), node("b", "read_data/datatable"), node("psi", CODE))).build());
    }

    @AfterEach
    void resetIdentity() { UserContext.remove(); }

    @Test
    void approvedRecipientReadsOnlyLocalGuestArtifactFromSameEngineTask() {
        when(kuscia.queryJob(any(), eq("bob"))).thenReturn(job("job1", ALIAS, "-1", "bob"));
        var output = GraphNodeOutputVO.builder().type("table").jobId("job1").build();
        when(storage.readTable("job1-guest-0", "job1-real-task-result", "job1", "flow", CODE)).thenReturn(output);
        assertTrue(service.status("project", "flow").getFinished());
        assertEquals("job1-real-task", service.output(outputRequest()).getTaskId());
        verify(storage).readTable("job1-guest-0", "job1-real-task-result", "job1", "flow", CODE);
    }

    @Test
    void missingWrongRoleWrongTaskOrSingleRecipientNeverReadsAFile() {
        when(kuscia.queryJob(any(), eq("bob"))).thenReturn(Job.QueryJobResponse.getDefaultInstance(),
                job("forged", ALIAS, "-1", "bob"), job("job1", "other-psi", "-1", "bob"),
                job("job1", ALIAS, "-1", "third"), job("job1", ALIAS, "0", "bob"), job("job1", ALIAS, "null", "bob"));
        assertThrows(IllegalStateException.class, () -> service.output(outputRequest()));
        assertThrows(SecurityException.class, () -> service.output(outputRequest()));
        assertThrows(SecurityException.class, () -> service.output(outputRequest()));
        assertThrows(SecurityException.class, () -> service.output(outputRequest()));
        assertThrows(IllegalStateException.class, () -> service.output(outputRequest()));
        assertThrows(IllegalStateException.class, () -> service.output(outputRequest()));
        verifyNoInteractions(storage);
    }

    @Test
    void wrongAuditOrMissingMemberCannotRevealPeerJobPointer() throws Exception {
        when(channel.local()).thenReturn("alice");
        when(channel.peer()).thenReturn("bob");
        assertThrows(IllegalArgumentException.class, () -> service.latest(null,
                bytes("{\"projectId\":\"project\",\"graphId\":\"flow\",\"auditId\":\"forged\"}")));
        when(participants.findProjectNodesByProjectId("project")).thenReturn(List.of("alice", "third"));
        assertThrows(IllegalArgumentException.class, () -> service.latest(null,
                bytes("{\"projectId\":\"project\",\"graphId\":\"flow\",\"auditId\":\"audit\"}")));
        verifyNoInteractions(jobs);
    }

    @Test
    void tamperedPeerReplyCannotSubstituteTheJobId() {
        String key = "test-only-key";
        byte[] genuine = bytes("{\"jobId\":\"job1\"}");
        var tampered = new AtomicBoolean();
        var alice = new BfiaNodePeerChannel(configured("bob"), new RestTemplateBuilder(), "alice", "bob", key);
        var bob = new BfiaNodePeerChannel(configured("alice"), new RestTemplateBuilder()
                .additionalInterceptors((request, payload, ignored) -> {
                    String path = request.getURI().getPath();
                    alice.authenticate(path, request.getHeaders(), payload, Instant.now().getEpochSecond());
                    var response = new MockClientHttpResponse(tampered.get() ? bytes("{\"jobId\":\"forged\"}") : genuine,
                            HttpStatus.OK);
                    response.getHeaders().putAll(alice.replyHeaders(path, request.getHeaders(), genuine));
                    return response;
                }), "bob", "alice", key);
        assertArrayEquals(genuine, bob.read(BfiaPeerPsiResult.PATH, bytes("{}")));
        tampered.set(true);
        assertThrows(SecurityException.class, () -> bob.read(BfiaPeerPsiResult.PATH, bytes("{}")));
    }

    private static BfiaInterconnClient configured(String peer) {
        var interconn = mock(BfiaInterconnClient.class);
        when(interconn.isEnabled()).thenReturn(true);
        when(interconn.configuredPadAddress(peer)).thenReturn("http://" + peer);
        return interconn;
    }

    private static Job.QueryJobResponse job(String id, String alias, String recipient, String guest) {
        var response = Job.QueryJobResponse.newBuilder();
        response.getStatusBuilder().setCode(0);
        var job = response.getDataBuilder().setJobId(id).setInitiator("alice");
        job.addTasks(Job.TaskConfig.newBuilder().setAlias(alias).setTaskId("job1-real-task").setAppImage("ic-ecdh-psi")
                .addParties(Job.Party.newBuilder().setDomainId("alice").setRole("host"))
                .addParties(Job.Party.newBuilder().setDomainId(guest).setRole("guest"))
                .setTaskInputConfig("{\"module_name\":\"ecdh_psi\",\"task_params\":{\"common\":{\"result_to_rank\":" + recipient + "}}}"));
        job.getStatusBuilder().setState("Succeeded").addTasks(Job.TaskStatus.newBuilder()
                .setTaskId("job1-real-task").setState("Succeeded"));
        return response.build();
    }

    private static BfiaGraphNodeDO node(String id, String code) {
        return BfiaGraphNodeDO.builder().upk(new BfiaGraphNodeDO.UPK("project", "flow", id))
                .nodeDefId(code).params("{\"outputs\":[\"" + id + "-output-0\"]}").build();
    }

    private static GraphNodeOutputRequest outputRequest() {
        var request = new GraphNodeOutputRequest();
        request.setProjectId("project");
        request.setGraphId("flow");
        request.setGraphNodeId("psi");
        request.setOutputId("psi-output-0");
        return request;
    }

    private static byte[] bytes(String input) { return input.getBytes(StandardCharsets.UTF_8); }
}
