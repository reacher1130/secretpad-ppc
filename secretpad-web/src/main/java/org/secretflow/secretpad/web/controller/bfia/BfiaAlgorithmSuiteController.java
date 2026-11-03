/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.web.controller.bfia;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.secretflow.secretpad.common.constant.SystemConstants;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.protocol.ProtocolConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@RestController
@RequestMapping("/api/v1bfia/algorithm-run")
public class BfiaAlgorithmSuiteController {
    private final BfiaAlgorithmSuite suite;
    private final ProtocolConfigService protocols;
    private final EnvService environment;
    private final boolean authentication;
    private final boolean allowDemo;
    private final String officeOrigin;

    BfiaAlgorithmSuiteController(BfiaAlgorithmSuite suite, ProtocolConfigService protocols, EnvService environment,
                                 @Value("${secretpad.auth.enabled:true}") boolean authentication,
                                 @Value("${PPCI_BFIA_SUITE_ALLOW_DEMO:false}") boolean allowDemo,
                                 @Value("${PPCI_BFIA_SUITE_ORIGIN:}") String officeOrigin) {
        this.suite = suite;
        this.protocols = protocols;
        this.environment = environment;
        this.authentication = authentication;
        this.allowDemo = allowDemo;
        this.officeOrigin = officeOrigin;
    }

    @GetMapping("/latest")
    public ResponseEntity<SecretPadResponse<BfiaAlgorithmSuite.Latest>> latest(HttpServletRequest request) throws IOException {
        requireSameOrigin(request);
        return ok(allowed() ? suite.latest(!authentication) : new BfiaAlgorithmSuite.Latest(false, null));
    }

    @PostMapping("/start")
    public ResponseEntity<SecretPadResponse<BfiaAlgorithmSuite.Latest>> start(HttpServletRequest request) throws IOException {
        requireSameOrigin(request);
        requireAllowed();
        if (!"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请从 BFIA 算法页面发起执行。");
        }
        return ok(suite.start(!authentication));
    }

    @GetMapping("/{runId}/report")
    public ResponseEntity<SecretPadResponse<BfiaAlgorithmSuite.Run>> report(@PathVariable String runId,
                                                                            HttpServletRequest request) throws IOException {
        requireSameOrigin(request);
        requireAllowed();
        return ok(suite.report(runId));
    }

    @GetMapping("/{runId}/log")
    public ResponseEntity<SecretPadResponse<Map<String, String>>> log(@PathVariable String runId,
                                                                      @RequestParam String algorithm,
                                                                      @RequestParam String party,
                                                                      HttpServletRequest request) throws IOException {
        requireSameOrigin(request);
        requireAllowed();
        return ok(Map.of("content", suite.log(runId, algorithm, party)));
    }

    private boolean allowed() {
        if (!suite.configured() || !"alice".equals(environment.getPlatformNodeId())
                || !protocols.isActive("bfia") || (!authentication && !allowDemo)) {
            return false;
        }
        var user = UserContext.getUser();
        return "alice".equals(user.getPlatformNodeId()) && !Boolean.TRUE.equals(user.getVirtualUserForNode())
                && SystemConstants.USER_ADMIN.equals(user.getName());
    }

    private void requireAllowed() {
        if (!allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "本节点未启用 BFIA 公开样例执行。");
        }
    }

    private void requireSameOrigin(HttpServletRequest request) {
        // The global CORS policy is permissive. Fetch Metadata is browser controlled and
        // keeps a same-origin request usable behind a proxy that rewrites host and port.
        String site = request.getHeader("Sec-Fetch-Site");
        String origin = request.getHeader("Origin");
        if ("same-origin".equals(site) || ("none".equals(site) && origin == null && "GET".equals(request.getMethod()))) {
            return;
        }
        if (site == null) {
            URI target = URI.create(request.getRequestURL().toString());
            boolean matchesPublicOrigin = (officeOrigin.startsWith("http://") || officeOrigin.startsWith("https://"))
                    && officeOrigin.equals(origin);
            if (origin == null || origin.equals(target.getScheme() + "://" + target.getRawAuthority())
                    || matchesPublicOrigin) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不允许跨来源访问 BFIA 算法执行。");
    }

    private static <T> ResponseEntity<SecretPadResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SecretPadResponse.success(data));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<SecretPadResponse<Object>> responseError(ResponseStatusException e) {
        return error(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<SecretPadResponse<Object>> badInput(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<SecretPadResponse<Object>> missing(NoSuchElementException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<SecretPadResponse<Object>> storageError(IOException e) {
        log.warn("BFIA suite storage unavailable", e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法读取或保存算法运行记录。");
    }

    private static ResponseEntity<SecretPadResponse<Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new SecretPadResponse<>(new SecretPadResponse.SecretPadResponseStatus(status.value(), message), null));
    }
}
