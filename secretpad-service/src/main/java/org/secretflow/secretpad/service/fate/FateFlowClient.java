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

package org.secretflow.secretpad.service.fate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.secretflow.secretpad.common.errorcode.SystemErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP client for FATE-Flow 2.x BFIA scheduler APIs.
 *
 * <p>Communicates with FATE-Flow on port 9380 and exposes the minimal set of
 * endpoints needed by SecretPad: create_all, start, stop_all and status_all.
 *
 * <p>Every successful response is validated for both HTTP status and FATE
 * retcode. A non-zero {@code retcode} is converted into a
 * {@link SecretpadException} so callers do not need to inspect the raw map.
 */
@Slf4j
@Component
public class FateFlowClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${secretpad.fate.flow.token:}")
    private String flowToken;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
        // We handle HTTP errors explicitly so that we can map them to
        // SecretpadException with the original response body.
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
    }

    /**
     * Best-effort connectivity check. Returns true if FATE-Flow answers with a
     * non-empty body, ignoring the retcode (a probe job id is expected to be
     * missing).
     */
    public boolean checkConnection(String baseUrl) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/schedule/job/status_all")
                    .queryParam("job_id", "probe-" + System.currentTimeMillis())
                    .build().toUriString();
            // Raw request: we only care whether the HTTP layer answers, not the
            // FATE retcode (a probe job id is expected to be unknown).
            HttpHeaders headers = buildHeaders();
            RequestEntity<Void> request = new RequestEntity<>(headers, HttpMethod.GET, URI.create(url));
            ResponseEntity<Map> response = restTemplate.exchange(request, Map.class);
            return response.getStatusCode().is2xxSuccessful()
                    && response.getBody() != null
                    && !response.getBody().isEmpty();
        } catch (Exception e) {
            log.debug("FATE-Flow connectivity check failed for {}: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    /**
     * POST /schedule/job/create_all
     */
    public Map<String, Object> createJobAll(String baseUrl, Map<String, Object> body) {
        return checkedPost(baseUrl + "/schedule/job/create_all", body);
    }

    /**
     * POST /schedule/job/start
     */
    public Map<String, Object> startJob(String baseUrl, String jobId) {
        Map<String, Object> body = new HashMap<>();
        body.put("job_id", jobId);
        return checkedPost(baseUrl + "/schedule/job/start", body);
    }

    /**
     * POST /schedule/job/stop_all
     */
    public Map<String, Object> stopJobAll(String baseUrl, String jobId) {
        Map<String, Object> body = new HashMap<>();
        body.put("job_id", jobId);
        return checkedPost(baseUrl + "/schedule/job/stop_all", body);
    }

    /**
     * GET /schedule/job/status_all?job_id=...
     */
    public Map<String, Object> getJobStatus(String baseUrl, String jobId) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/schedule/job/status_all")
                .queryParam("job_id", jobId)
                .build().toUri();
        return checkedGet(uri.toString());
    }

    private Map<String, Object> checkedPost(String url, Map<String, Object> body) {
        return checkAndReturn(post(url, body));
    }

    private Map<String, Object> checkedGet(String url) {
        return checkAndReturn(get(url));
    }

    private ResponseEntity<Map> post(String url, Map<String, Object> body) {
        try {
            HttpHeaders headers = buildHeaders();
            String json = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            return restTemplate.postForEntity(url, entity, Map.class);
        } catch (JsonProcessingException e) {
            throw SecretpadException.of(SystemErrorCode.ENCODE_ERROR, e, "Failed to serialize FATE request");
        }
    }

    private ResponseEntity<Map> get(String url) {
        HttpHeaders headers = buildHeaders();
        RequestEntity<Void> request = new RequestEntity<>(headers, HttpMethod.GET, URI.create(url));
        return restTemplate.exchange(request, Map.class);
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (flowToken != null && !flowToken.isBlank()) {
            headers.setBearerAuth(flowToken);
        }
        return headers;
    }

    private Map<String, Object> checkAndReturn(ResponseEntity<Map> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw SecretpadException.of(SystemErrorCode.HTTP_5XX_ERROR,
                    "FATE-Flow returned HTTP " + response.getStatusCode().value());
        }
        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw SecretpadException.of(SystemErrorCode.REMOTE_CALL_ERROR, "FATE-Flow returned empty body");
        }
        int retcode = parseRetcode(body.get("retcode"));
        if (retcode == 0) {
            retcode = parseRetcode(body.get("code"));
        }
        if (retcode != 0) {
            Object retmsg = body.get("retmsg");
            if (retmsg == null) {
                retmsg = body.get("msg");
            }
            throw SecretpadException.of(SystemErrorCode.REMOTE_CALL_ERROR,
                    "FATE-Flow error: " + (retmsg == null ? "retcode/code=" + retcode : retmsg));
        }
        return body;
    }

    private int parseRetcode(Object retcodeObj) {
        if (retcodeObj == null) {
            return 0;
        }
        if (retcodeObj instanceof Number) {
            return ((Number) retcodeObj).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(retcodeObj));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
