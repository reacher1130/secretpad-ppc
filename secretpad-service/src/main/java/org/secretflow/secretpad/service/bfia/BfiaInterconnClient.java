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

package org.secretflow.secretpad.service.bfia;

import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.common.util.RestTemplateUtil;
import org.secretflow.secretpad.kuscia.v1alpha1.model.DynamicKusciaGrpcConfig;
import org.secretflow.secretpad.kuscia.v1alpha1.model.KusciaGrpcConfig;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BFIA east-west (节点间) interconnect client.
 *
 * <p>Propagates management-layer resource-interop requests (node contract / dataset auth / project /
 * flow / job audit) from this PAD to a peer node's {@code /v1/interconn/*} endpoints, routed through
 * the local kuscia gateway. Assembles the InterOp 管理层 §2.2 common request headers
 * (x-source-node-id / x-target-node-id / x-nonce / x-trace-id / x-timestamp / x-csrf-protection).
 *
 * <p>Cross-node propagation is opt-in via {@code secretpad.bfia.crossnode.enabled} (default false) so
 * single-node / all-in-one deployments are unaffected. When disabled, callers fall back to local-only
 * (auto-approved) audit semantics.
 *
 * <p>NOTE: {@code x-auth-sign} (contract-keyed signature, required for authenticated endpoints per
 * §2.2) is currently a no-op placeholder pending contract-key negotiation; node-info / contract-apply
 * / contract-confirm endpoints do not require it per spec.
 *
 * @author gaofeng
 */
@Slf4j
@Component
public class BfiaInterconnClient {

    private final DynamicKusciaGrpcConfig dynamicKusciaGrpcConfig;

    @Value("${secretpad.bfia.crossnode.enabled:false}")
    private boolean crossNodeEnabled;

    /**
     * Optional explicit gateway base URL (e.g. http://root-kuscia-autonomy-alice:8084). When blank,
     * the first configured kuscia node's {@code host:httpPort} is used.
     */
    @Value("${secretpad.bfia.crossnode.gateway-url:}")
    private String gatewayUrl;

    /**
     * Shared secret key for HMAC-SHA256 signing of cross-node requests (x-auth-sign).
     * All nodes in the BFIA consortium must share the same key.
     */
    @Value("${secretpad.bfia.crossnode.sign-key:}")
    private String signKey;

    @Value("${KUSCIA_API_LITE_ALICE_ADDRESS:}")
    private String aliceKusciaHost;

    @Value("${KUSCIA_API_LITE_BOB_ADDRESS:}")
    private String bobKusciaHost;

    /**
     * SecretPad HTTP addresses for cross-node management-layer calls (project/flow/dataset audit).
     * These are the PAD console HTTP ports (e.g. 18080/28080), NOT the Kuscia interconn port 8084.
     */
    @Value("${SECRETPAD_ALICE_HTTP_ADDRESS:}")
    private String alicePadHttp;

    @Value("${SECRETPAD_BOB_HTTP_ADDRESS:}")
    private String bobPadHttp;

    public BfiaInterconnClient(DynamicKusciaGrpcConfig dynamicKusciaGrpcConfig) {
        this.dynamicKusciaGrpcConfig = dynamicKusciaGrpcConfig;
    }

    public boolean isEnabled() {
        return crossNodeEnabled;
    }

    /**
     * POST a JSON body to a peer node's interconn endpoint, best-effort.
     *
     * @param sourceNodeId this node id (x-source-node-id)
     * @param targetNodeId peer node id (x-target-node-id), used by the gateway to route
     * @param path         the interconn path, e.g. {@code /v1/interconn/project/audit/apply}
     * @param body         the request payload
     * @return the parsed response map, or {@code null} when disabled or on failure
     */
    public Map<String, Object> post(String sourceNodeId, String targetNodeId, String path, Object body) {
        if (!crossNodeEnabled) {
            log.debug("BFIA cross-node propagation disabled; skip POST {} -> {}", path, targetNodeId);
            return null;
        }
        // Management-layer interconn endpoints (/v1/interconn/project/*, /v1/interconn/node/*) are
        // served by the peer SecretPad HTTP port, NOT the Kuscia 8084 port. Resolve the target
        // node's PAD HTTP address accordingly.
        String base = resolvePadHttpBase(targetNodeId);
        if (base == null) {
            log.error("BFIA cross-node POST failed: cannot resolve SecretPad HTTP address for target node {}", targetNodeId);
            return null;
        }
        String url = base + path;
        Map<String, String> headers = buildHeaders(sourceNodeId, targetNodeId);
        try {
            org.springframework.http.ResponseEntity<String> respEntity =
                    RestTemplateUtil.sendPostJson(url, body, headers);
            String resp = respEntity.getBody();
            log.info("BFIA cross-node POST ok: url={}, target={}", url, targetNodeId);
            return StringUtils.isBlank(resp) ? new HashMap<>() : JsonUtils.toJavaMap(resp);
        } catch (Exception e) {
            log.error("BFIA cross-node POST failed: url={}, target={}, err={}", url, targetNodeId, e.getMessage());
            return null;
        }
    }

    /** Management mutations must not report success when the peer rejected or missed them. */
    public void postChecked(String sourceNodeId, String targetNodeId, String path, Object body) {
        Map<String, Object> response = post(sourceNodeId, targetNodeId, path, body);
        Object status = response == null ? null : response.get("status");
        if (!(status instanceof Map<?, ?> statusMap)
                || !(statusMap.get("code") instanceof Number code) || code.intValue() != 0) {
            throw new IllegalStateException("BFIA 对端 " + targetNodeId + " 未确认 " + path);
        }
    }

    /**
     * Query a peer node's BFIA datasets via the interconn data list endpoint (GET
     * {@code /v1/interconn/data/list}). Used by {@code buildProjectNodes()} so the local
     * SecretPad can surface the remote node's datatables in the DAG editor's data source
     * selector without requiring a full dataset replication.
     *
     * @param sourceNodeId this node id (sent as {@code kuscia-origin-source} header)
     * @param targetNodeId peer node id whose datasets should be listed
     * @param contractId   the BFIA contract id (passed as {@code contract_id} query param)
     * @return a list of maps each containing at least {@code datasetId} and {@code name};
     *         empty list when disabled, unresolvable, or on error
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> listRemoteDatasets(String sourceNodeId, String targetNodeId, String contractId) {
        if (!crossNodeEnabled) {
            log.debug("BFIA cross-node disabled; skip listRemoteDatasets -> {}", targetNodeId);
            return java.util.Collections.emptyList();
        }
        String base = resolvePadHttpBase(targetNodeId);
        if (base == null) {
            log.warn("listRemoteDatasets: cannot resolve PAD HTTP address for {}", targetNodeId);
            return java.util.Collections.emptyList();
        }
        String url = base + "/v1/interconn/data/list?contract_id=" + java.net.URLEncoder.encode(contractId, java.nio.charset.StandardCharsets.UTF_8)
                + "&page_num=1&page_size=200";
        Map<String, String> headers = buildHeaders(sourceNodeId, targetNodeId);
        try {
            org.springframework.http.HttpHeaders httpHeaders = new org.springframework.http.HttpHeaders();
            headers.forEach(httpHeaders::add);
            org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(httpHeaders);
            org.springframework.http.ResponseEntity<String> resp =
                    new org.springframework.web.client.RestTemplate().exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class);
            String body = resp.getBody();
            if (StringUtils.isBlank(body)) {
                return java.util.Collections.emptyList();
            }
            Map<String, Object> respMap = JsonUtils.toJavaMap(body);
            Object data = respMap.get("data");
            if (data instanceof Map) {
                Object list = ((Map<String, Object>) data).get("list");
                if (list instanceof java.util.List) {
                    return (java.util.List<Map<String, Object>>) list;
                }
            }
            return java.util.Collections.emptyList();
        } catch (Exception e) {
            log.error("listRemoteDatasets failed: url={}, target={}, err={}", url, targetNodeId, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Resolve one remote dataset with the metadata required by BFIA task private parameters.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> queryRemoteDataset(
            String sourceNodeId, String targetNodeId, String datasetId) {
        if (!crossNodeEnabled || StringUtils.isBlank(datasetId)) {
            return null;
        }
        String base = resolvePadHttpBase(targetNodeId);
        if (base == null) {
            return null;
        }
        String url = base + "/v1/interconn/data/query?contract_id=&dataset_id="
                + java.net.URLEncoder.encode(datasetId, java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> headers = buildHeaders(sourceNodeId, targetNodeId);
        try {
            org.springframework.http.HttpHeaders httpHeaders = new org.springframework.http.HttpHeaders();
            headers.forEach(httpHeaders::add);
            org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(httpHeaders);
            org.springframework.http.ResponseEntity<String> response =
                    new org.springframework.web.client.RestTemplate().exchange(
                            url, org.springframework.http.HttpMethod.GET, entity, String.class);
            if (StringUtils.isBlank(response.getBody())) {
                return null;
            }
            Map<String, Object> responseMap = JsonUtils.toJavaMap(response.getBody());
            Object data = responseMap.get("data");
            return data instanceof Map<?, ?> ? (Map<String, Object>) data : null;
        } catch (Exception e) {
            log.error("queryRemoteDataset failed: target={}, datasetId={}, err={}",
                    targetNodeId, datasetId, e.getMessage());
            return null;
        }
    }

    /**
     * Resolve the SecretPad HTTP base URL for a peer node (e.g. http://localhost:28080).
     * Returns null if the address is not configured.
     */
    private String resolvePadHttpBase(String targetNodeId) {
        if (targetNodeId == null) {
            return null;
        }
        String addr = null;
        if ("alice".equals(targetNodeId) && StringUtils.isNotBlank(alicePadHttp)) {
            addr = alicePadHttp;
        } else if ("bob".equals(targetNodeId) && StringUtils.isNotBlank(bobPadHttp)) {
            addr = bobPadHttp;
        }
        if (addr == null) {
            return null;
        }
        if (!addr.startsWith("http://") && !addr.startsWith("https://")) {
            addr = "http://" + addr;
        }
        return StringUtils.removeEnd(addr, "/");
    }

    /**
     * Query BFIA job status from the kuscia interconn control-plane HTTP API (port 8084).
     * <p>
     * POSTs {@code {"job_id":"<jobId>"}} to {@code /v1/interconn/schedule/job/status_all} on the
     * initiator node's kuscia HTTP endpoint and returns the parsed response map:
     * <pre>
     * {"code":0, "data":{"jobStatus":"RUNNING", "status":{"<taskId>":"<phase>"}}}
     * </pre>
     * The URL is resolved via {@link #resolveGatewayBase(String)} so that the optional
     * {@code secretpad.bfia.crossnode.gateway-url} override and the kuscia node lookup are reused.
     *
     * @param initiatorId the domain id of the job initiator (used to resolve the target node)
     * @param jobId       the BFIA job id
     * @return the parsed response map; on failure, a map with {@code code=-1} and {@code msg=errorMessage}
     */
    public Map<String, Object> queryJobStatus(String initiatorId, String jobId) {
        String url = resolveGatewayBase(initiatorId) + "/v1/interconn/schedule/job/status_all";
        Map<String, Object> body = new HashMap<>();
        body.put("job_id", jobId);
        try {
            // Use the 3-arg sendPostJson (returns ResponseEntity<String>) instead of the 4-arg
            // version: the 4-arg version calls JsonUtils.toJavaObject(body, String.class) which
            // fails when the response body is a JSON object (Jackson cannot extract a scalar
            // String from an object token).
            org.springframework.http.ResponseEntity<String> respEntity =
                    RestTemplateUtil.sendPostJson(url, body, new HashMap<>());
            String resp = respEntity.getBody();
            return StringUtils.isBlank(resp) ? new HashMap<>() : JsonUtils.toJavaMap(resp);
        } catch (Exception e) {
            log.warn("Failed to query BFIA job status: jobId={}, url={}", jobId, url, e);
            Map<String, Object> err = new HashMap<>();
            err.put("code", -1);
            err.put("msg", e.getMessage());
            return err;
        }
    }

    /**
     * Assemble the InterOp 管理层 §2.2 common request headers, including {@code x-auth-sign}
     * (HMAC-SHA256 of source+target+nonce+timestamp using the shared sign-key).
     * When sign-key is blank, x-auth-sign is omitted (backwards-compatible with unsigned mode).
     */
    private Map<String, String> buildHeaders(String sourceNodeId, String targetNodeId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-source-node-id", StringUtils.defaultString(sourceNodeId));
        headers.put("x-target-node-id", StringUtils.defaultString(targetNodeId));
        // BfiaSyncProjectController reads this header to identify the originating node.
        headers.put("kuscia-origin-source", StringUtils.defaultString(sourceNodeId));
        String nonce = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        String timestamp = String.valueOf(System.currentTimeMillis());
        headers.put("x-nonce", nonce);
        headers.put("x-trace-id", traceId);
        headers.put("x-timestamp", timestamp);
        headers.put("x-csrf-protection", "1");
        if (StringUtils.isNotBlank(signKey)) {
            String payload = sourceNodeId + targetNodeId + nonce + timestamp;
            headers.put("x-auth-sign", hmacSha256(signKey, payload));
        }
        return headers;
    }

    private static String hmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    private String resolveGatewayBase(String sourceNodeId) {
        if (StringUtils.isNotBlank(gatewayUrl)) {
            return StringUtils.removeEnd(gatewayUrl, "/");
        }
        // 1. Try environment-configured lite node addresses (authoritative for BFIA HTTP port).
        // Preserve an explicit port (e.g. localhost:18084) so that local proxies work;
        // fall back to the standard BFIA HTTP port 8084 when no port is given.
        if (sourceNodeId != null) {
            String host = null;
            if ("alice".equals(sourceNodeId) && StringUtils.isNotBlank(aliceKusciaHost)) {
                host = aliceKusciaHost;
            } else if ("bob".equals(sourceNodeId) && StringUtils.isNotBlank(bobKusciaHost)) {
                host = bobKusciaHost;
            }
            if (host != null) {
                return buildInterconnBase(host);
            }
        }
        // 2. Fall back to dynamic Kuscia gRPC config
        KusciaGrpcConfig target = null;
        if (dynamicKusciaGrpcConfig.getNodes() != null) {
            for (KusciaGrpcConfig node : dynamicKusciaGrpcConfig.getNodes()) {
                if (sourceNodeId != null && sourceNodeId.equals(node.getDomainId())) {
                    target = node;
                    break;
                }
                if (target == null) {
                    target = node;
                }
            }
        }
        if (target == null) {
            throw new IllegalStateException("No kuscia node configured for BFIA cross-node propagation");
        }
        return "http://" + target.getHost() + ":" + target.getHttpPort();
    }

    /**
     * Build BFIA interconn base URL from an address that may include scheme/port.
     * Preserves an explicit port (e.g. localhost:18084) so that local proxies work;
     * falls back to the standard BFIA HTTP port 8084 when no port is given.
     */
    private String buildInterconnBase(String address) {
        if (address == null) {
            return "";
        }
        String hostPort = address;
        int schemeEnd = hostPort.indexOf("://");
        if (schemeEnd >= 0) {
            hostPort = hostPort.substring(schemeEnd + 3);
        }
        if (!hostPort.contains(":")) {
            hostPort = hostPort + ":8084";
        }
        return "http://" + hostPort;
    }
}
