package org.secretflow.secretpad.web.controller.bfia;

import org.secretflow.secretpad.service.bfia.BfiaInterconnClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Dedicated authenticated peer channel. Other BFIA interconnect endpoints retain their wire format. */
@Component
class BfiaNodePeerChannel {
    static final String PATH = "/v1/interconn/node/contract";
    static final String SOURCE = "x-source-node-id";
    static final String TARGET = "x-target-node-id";
    static final String NONCE = "x-nonce";
    static final String TIMESTAMP = "x-timestamp";
    static final String SIGNATURE = "x-bfia-node-signature";
    static final String ACK = "x-bfia-node-ack";
    private static final String REPLY = "x-bfia-node-reply";

    private final BfiaInterconnClient interconn;
    private final RestTemplate http;
    private final String local;
    private final String peer;
    private final byte[] key;

    BfiaNodePeerChannel(BfiaInterconnClient interconn, RestTemplateBuilder builder,
                        @Value("${secretpad.node-id:}") String local,
                        @Value("${SECRETPAD_BFIA_PEER_NODE_ID:}") String peer,
                        @Value("${secretpad.bfia.crossnode.sign-key:${PPCI_BFIA_PEER_SIGN_KEY:}}") String key) {
        this.interconn = interconn;
        this.http = builder.setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(5)).build();
        this.local = local;
        this.peer = peer;
        this.key = key.isBlank() ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    String local() { return local; }
    String peer() { return peer; }
    String address() { return interconn.configuredPadAddress(peer); }

    boolean ready() {
        return interconn.isEnabled() && key.length != 0 && !local.isBlank() && !peer.isBlank()
                && !local.equals(peer) && address() != null;
    }

    void send(String action, byte[] body) {
        if (!ready()) {
            throw new IllegalStateException("BFIA 对端认证通道未配置");
        }
        String path = PATH + action;
        HttpHeaders headers = requestHeaders(path, body, Instant.now().getEpochSecond(), UUID.randomUUID().toString());
        var response = http.exchange(URI.create(address() + path), HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
        if (!response.getStatusCode().is2xxSuccessful()
                || !matches(signature("ACK", path, headers, body), response.getHeaders().getFirst(ACK))) {
            throw new IllegalStateException("BFIA 对端没有确认合作操作");
        }
    }

    byte[] read(String path, byte[] body) {
        if (!ready()) {
            throw new IllegalStateException("BFIA 对端认证通道未配置");
        }
        HttpHeaders headers = requestHeaders(path, body, Instant.now().getEpochSecond(), UUID.randomUUID().toString());
        var response = http.exchange(URI.create(address() + path), HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
        byte[] reply = response.getBody();
        if (reply == null || reply.length > 4096 || !response.getStatusCode().is2xxSuccessful()
                || !matches(signReply(path, headers, reply), response.getHeaders().getFirst(REPLY))) {
            throw new SecurityException("Invalid BFIA peer response");
        }
        return reply;
    }

    HttpHeaders replyHeaders(String path, HttpHeaders request, byte[] body) {
        HttpHeaders reply = new HttpHeaders();
        reply.setContentType(MediaType.APPLICATION_JSON);
        reply.set(REPLY, signReply(path, request, body));
        return reply;
    }

    private String signReply(String path, HttpHeaders request, byte[] reply) {
        return signature("REPLY:" + request.getFirst(SIGNATURE), path, request, reply);
    }

    HttpHeaders requestHeaders(String path, byte[] body, long seconds, String nonce) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SOURCE, local);
        headers.set(TARGET, peer);
        headers.set(NONCE, nonce);
        headers.set(TIMESTAMP, Long.toString(seconds));
        headers.set(SIGNATURE, signature("POST", path, headers, body));
        return headers;
    }

    String authenticate(String path, HttpHeaders headers, byte[] body, long now) {
        String timestamp = headers.getFirst(TIMESTAMP);
        String nonce = headers.getFirst(NONCE);
        if (!ready() || body.length > 4096 || !peer.equals(headers.getFirst(SOURCE))
                || !local.equals(headers.getFirst(TARGET)) || timestamp == null || !timestamp.matches("[0-9]{1,12}")
                || nonce == null || !nonce.matches("[0-9a-f-]{36}")) {
            throw new SecurityException("Invalid BFIA peer authentication");
        }
        long seconds = Long.parseLong(timestamp);
        if (seconds < now - 120 || seconds > now + 120
                || !matches(signature("POST", path, headers, body), headers.getFirst(SIGNATURE))) {
            throw new SecurityException("Invalid BFIA peer authentication");
        }
        return signature("ACK", path, headers, body);
    }

    private String signature(String method, String path, HttpHeaders h, byte[] body) {
        String prefix = String.join("\n", "bfia-node-contract-v1", method, path, h.getFirst(SOURCE),
                h.getFirst(TARGET), h.getFirst(TIMESTAMP), h.getFirst(NONCE), "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(prefix.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not authenticate BFIA peer request", e);
        }
    }

    private static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }
}
