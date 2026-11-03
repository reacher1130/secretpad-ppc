package org.secretflow.secretpad.web.controller.bfia;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaContractDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaNodeDO;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaContractRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaNodeRepository;
import org.secretflow.secretpad.service.bfia.BfiaInterconnClient;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BfiaNodeCooperationTest {
    private static final String TEST_KEY = "test-only-bfia-pair-key";
    private final Map<String, Site> sites = new HashMap<>();

    @Test
    void recipientDecidesAndOnlyAcknowledgedMutationsReportSuccess() {
        Site alice = new Site("alice", "bob", TEST_KEY);
        Site bob = new Site("bob", "alice", TEST_KEY);
        long expiry = Instant.now().plusSeconds(3600).getEpochSecond();

        assertThrows(IllegalArgumentException.class, () -> alice.service.apply("alice", "kimi", null, expiry));
        assertThrows(IllegalArgumentException.class, () -> alice.service.apply("alice", "bob", "outside.invalid", expiry));
        assertThrows(IllegalArgumentException.class, () -> alice.service.apply("alice", "bob", null, expiry - 7200));
        assertTrue(alice.contracts.isEmpty());

        BfiaContractDO legacy = BfiaContractDO.builder().contractId("legacy").nodeId("alice")
                .initiatorNodeId("bob").status("APPROVED").build();
        bob.contracts.put("legacy", legacy);
        BfiaNodeDO existingAlice = BfiaNodeDO.builder().nodeId("alice").name("Alice original").build();
        bob.nodes.put("alice", existingAlice);

        alice.dropAck = true;
        assertThrows(IllegalStateException.class, () -> alice.service.apply("alice", "bob", null, expiry));
        assertEquals(1, alice.contracts.size());
        String id = alice.contracts.keySet().iterator().next();
        assertEquals("UNAPPROVED", bob.contracts.get(id).getStatus());
        alice.dropAck = false;
        alice.service.apply("alice", "bob", null, expiry);
        assertEquals(2, bob.contracts.size());
        assertSame(legacy, bob.contracts.get("legacy"));
        assertTrue(bob.service.canConfirm(bob.contracts.get(id), "bob"));
        assertFalse(alice.service.canConfirm(alice.contracts.get(id), "alice"));
        assertThrows(IllegalArgumentException.class, () -> alice.service.confirm("alice", id, "APPROVED"));
        assertThrows(IllegalArgumentException.class, () -> bob.service.confirm("alice", id, "APPROVED"));
        assertThrows(IllegalArgumentException.class, () -> bob.service.confirm("bob", id, "whatever"));

        bob.dropAck = true;
        assertThrows(IllegalStateException.class, () -> bob.service.confirm("bob", id, "APPROVED"));
        assertEquals("UNAPPROVED", bob.contracts.get(id).getStatus());
        assertEquals("APPROVED", alice.contracts.get(id).getStatus());
        bob.dropAck = false;
        assertThrows(RuntimeException.class, () -> bob.service.confirm("bob", id, "REJECTED"));
        bob.service.confirm("bob", id, "APPROVED");
        bob.service.confirm("bob", id, "APPROVED");
        assertEquals("APPROVED", bob.contracts.get(id).getStatus());
        assertEquals("bob", alice.nodes.get("bob").getNodeId());
        assertSame(existingAlice, bob.nodes.get("alice"));
        assertFalse(bob.service.canConfirm(bob.contracts.get(id), "bob"));
    }

    @Test
    void signaturesBindExactBytesPathDirectionAndTime() throws IOException {
        Site alice = new Site("alice", "bob", TEST_KEY);
        Site bob = new Site("bob", "alice", TEST_KEY);
        long now = Instant.now().getEpochSecond();
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String path = BfiaNodePeerChannel.PATH + BfiaNodeCooperation.APPLY;
        HttpHeaders signed = alice.channel.requestHeaders(path, body, now, UUID.randomUUID().toString());
        assertNotNull(bob.channel.authenticate(path, signed, body, now));
        assertThrows(SecurityException.class, () -> bob.channel.authenticate(path, signed,
                "{\"a\":2}".getBytes(StandardCharsets.UTF_8), now));
        assertThrows(SecurityException.class, () -> bob.channel.authenticate(path + "/other", signed, body, now));
        assertThrows(SecurityException.class, () -> bob.channel.authenticate(path, signed, body, now + 121));
        assertThrows(SecurityException.class, () -> alice.channel.authenticate(path, signed, body, now));
        signed.set(BfiaNodePeerChannel.SOURCE, "kimi");
        assertEquals(HttpStatus.FORBIDDEN, bob.controller.apply(signed, body).getStatusCode());
        assertTrue(bob.contracts.isEmpty());
        Site noKey = new Site("bob", "alice", "");
        assertFalse(noKey.channel.ready());
        assertThrows(IllegalArgumentException.class, () -> noKey.service.apply("bob", "alice", null, now + 60));
    }

    private final class Site {
        final Map<String, BfiaContractDO> contracts = new HashMap<>();
        final Map<String, BfiaNodeDO> nodes = new HashMap<>();
        final BfiaNodePeerChannel channel;
        final BfiaNodeCooperation service;
        final BfiaSyncNodeController controller;
        boolean dropAck;

        Site(String local, String peer, String key) {
            var contractRepo = mock(BfiaContractRepository.class);
            when(contractRepo.findForDecision(anyString())).thenAnswer(i -> contracts.get(i.getArgument(0)));
            when(contractRepo.save(any(BfiaContractDO.class))).thenAnswer(i -> {
                BfiaContractDO row = i.getArgument(0);
                contracts.put(row.getContractId(), row);
                return row;
            });
            var nodeRepo = mock(BfiaNodeRepository.class);
            when(nodeRepo.existsById(anyString())).thenAnswer(i -> nodes.containsKey(i.getArgument(0)));
            when(nodeRepo.save(any(BfiaNodeDO.class))).thenAnswer(i -> {
                BfiaNodeDO row = i.getArgument(0);
                nodes.put(row.getNodeId(), row);
                return row;
            });
            var tx = mock(PlatformTransactionManager.class);
            when(tx.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
            var interconn = mock(BfiaInterconnClient.class);
            when(interconn.isEnabled()).thenReturn(true);
            when(interconn.configuredPadAddress(peer)).thenReturn("http://" + peer + ":8080");
            var builder = new RestTemplateBuilder().additionalInterceptors((request, bytes, execution) -> {
                Site remote = sites.get(request.getURI().getHost());
                var response = request.getURI().getPath().endsWith(BfiaNodeCooperation.APPLY)
                        ? remote.controller.apply(request.getHeaders(), bytes)
                        : remote.controller.confirm(request.getHeaders(), bytes);
                var received = new MockClientHttpResponse(new byte[0], response.getStatusCode());
                if (!dropAck) {
                    received.getHeaders().putAll(response.getHeaders());
                }
                return received;
            });
            channel = new BfiaNodePeerChannel(interconn, builder, local, peer, key);
            service = new BfiaNodeCooperation(contractRepo, nodeRepo, channel, new ObjectMapper(), tx);
            controller = new BfiaSyncNodeController(service);
            sites.put(local, this);
        }
    }
}
