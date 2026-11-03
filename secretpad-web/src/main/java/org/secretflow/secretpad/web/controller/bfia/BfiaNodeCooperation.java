package org.secretflow.secretpad.web.controller.bfia;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaContractDO;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaNodeDO;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaContractRepository;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaNodeRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
class BfiaNodeCooperation {
    static final String APPLY = "/apply";
    static final String CONFIRM = "/confirm";
    private static final String PENDING = "UNAPPROVED";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";

    private final BfiaContractRepository contracts;
    private final BfiaNodeRepository nodes;
    private final BfiaNodePeerChannel channel;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    BfiaNodeCooperation(BfiaContractRepository contracts, BfiaNodeRepository nodes, BfiaNodePeerChannel channel,
                        ObjectMapper json, @Qualifier("bfiaTransactionManager") PlatformTransactionManager manager) {
        this.contracts = contracts;
        this.nodes = nodes;
        this.channel = channel;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    record Contract(String contractId, String initiator, String target, long expiresAt, String decision) { }

    void apply(String owner, String peer, String enteredAddress, long expiresAt) {
        requireOwner(owner);
        require(channel.peer().equals(peer), "只能申请已配置的 BFIA 对端");
        String address = channel.address();
        require(enteredAddress == null || enteredAddress.isBlank() || enteredAddress.equals(address)
                || ("http://" + enteredAddress).equals(address) || ("https://" + enteredAddress).equals(address),
                "通讯地址与已配置 BFIA 对端不一致");
        require(expiresAt > now(), "合作申请有效期必须晚于当前时间");
        Contract request = new Contract(id(owner, peer, expiresAt), owner, peer, expiresAt, null);
        ensurePending(request);
        send(APPLY, request);
    }

    void confirm(String owner, String contractId, String decision) {
        requireOwner(owner);
        requireDecision(decision);
        tx.executeWithoutResult(status -> {
            BfiaContractDO row = contracts.findForDecision(contractId);
            require(row != null && canOwnDecision(row, owner), "只有本申请的指定接收方可以审批");
            Contract request = new Contract(contractId, row.getInitiatorNodeId(), row.getNodeId(), row.getExpiredTime(), decision);
            if (!Objects.equals(row.getStatus(), decision)) {
                require(PENDING.equals(row.getStatus()) && request.expiresAt() > now(), "该合作申请已处理或过期");
                // Keep the local decision retryable until the originating site has committed and acknowledged it.
                send(CONFIRM, request);
                recordDecision(row, decision);
            }
        });
    }

    String receive(String action, HttpHeaders headers, byte[] body) throws IOException {
        String ack = channel.authenticate(BfiaNodePeerChannel.PATH + action, headers, body, now());
        Contract request = json.readValue(body, Contract.class);
        require(request != null && request.expiresAt() > 0
                && id(request.initiator(), request.target(), request.expiresAt()).equals(request.contractId()),
                "合作申请标识无效");
        if (APPLY.equals(action)) {
            require(channel.peer().equals(request.initiator()) && channel.local().equals(request.target())
                    && request.decision() == null, "合作申请方向无效");
            ensurePending(request);
        } else {
            require(channel.local().equals(request.initiator()) && channel.peer().equals(request.target()),
                    "合作审批方向无效");
            requireDecision(request.decision());
            tx.executeWithoutResult(status -> {
                BfiaContractDO row = contracts.findForDecision(request.contractId());
                require(row != null && sameRequest(row, request), "未找到原合作申请");
                if (!Objects.equals(row.getStatus(), request.decision())) {
                    require(PENDING.equals(row.getStatus()) && request.expiresAt() > now(), "该合作申请已处理或过期");
                    recordDecision(row, request.decision());
                }
            });
        }
        return ack;
    }

    boolean canConfirm(BfiaContractDO row, String owner) {
        return channel.ready() && canOwnDecision(row, owner) && PENDING.equals(row.getStatus()) && row.getExpiredTime() > now();
    }

    private boolean canOwnDecision(BfiaContractDO row, String owner) {
        Long expiry = row.getExpiredTime();
        return owner.equals(channel.local()) && owner.equals(row.getNodeId())
                && channel.peer().equals(row.getInitiatorNodeId()) && expiry != null
                && id(row.getInitiatorNodeId(), row.getNodeId(), expiry).equals(row.getContractId());
    }

    private synchronized void ensurePending(Contract request) {
        tx.executeWithoutResult(status -> {
            BfiaContractDO row = contracts.findForDecision(request.contractId());
            if (row != null) {
                require(sameRequest(row, request), "合作申请与原记录不一致");
                return;
            }
            require(request.expiresAt() > now(), "合作申请已过期");
            contracts.save(BfiaContractDO.builder().contractId(request.contractId())
                    .initiatorNodeId(request.initiator()).nodeId(request.target())
                    .padAddress(channel.address()).expiredTime(request.expiresAt())
                    .authType("signed-peer").status(PENDING).description("合作节点申请").build());
        });
    }

    private void recordDecision(BfiaContractDO row, String decision) {
        row.setStatus(decision);
        contracts.save(row);
        if (APPROVED.equals(decision) && !nodes.existsById(channel.peer())) {
            nodes.save(BfiaNodeDO.builder().nodeId(channel.peer()).name(channel.peer())
                    .netAddress(channel.address()).padAddress(channel.address()).status(APPROVED)
                    .description("合作节点").build());
        }
    }

    private void send(String action, Contract request) {
        try {
            channel.send(action, json.writeValueAsBytes(request));
        } catch (IOException e) {
            throw new IllegalStateException("无法发送 BFIA 合作申请", e);
        }
    }

    private void requireOwner(String owner) {
        require(channel.ready() && channel.local().equals(owner), "BFIA 对端认证通道未配置或本方身份不符");
    }

    private static boolean sameRequest(BfiaContractDO row, Contract request) {
        return request.initiator().equals(row.getInitiatorNodeId()) && request.target().equals(row.getNodeId())
                && Objects.equals(request.expiresAt(), row.getExpiredTime());
    }

    private static String id(String initiator, String target, long expiry) {
        return "node-coop-" + UUID.nameUUIDFromBytes((initiator + "\n" + target + "\n" + expiry).getBytes(StandardCharsets.UTF_8));
    }

    private static long now() { return Instant.now().getEpochSecond(); }

    private static void requireDecision(String decision) {
        require(APPROVED.equals(decision) || REJECTED.equals(decision), "审批决定无效");
    }

    private static void require(boolean valid, String error) {
        if (!valid) {
            throw new IllegalArgumentException(error);
        }
    }
}
