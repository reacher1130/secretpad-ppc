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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.secretflow.secretpad.common.dto.UserContextDTO;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.protocol.ProtocolConfigService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BfiaAlgorithmSuiteTest {
    @TempDir
    Path root;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void requiresRealExitAndExactBooleanReportsAfterRestart() throws IOException {
        String completed = seed("0");
        String failedRunner = seed("7");
        String malformed = seed("0");
        String interrupted = seed(null);
        var bad = validation(BfiaAlgorithmSuite.PYTHON);
        bad.withObject("/sgb").put("pass", "true");
        json.writeValue(result(malformed, "python").resolve("numerical-validation.json").toFile(), bad);
        // A second pass key cannot replace an earlier fail by parser coercion.
        Files.writeString(result(malformed, "cpp").resolve("protocol-validation.json"),
                "{\"ecdh-psi\":{\"pass\":false,\"pass\":true},\"ss-lr\":{\"pass\":true},\"ss-xgb\":{\"pass\":true},"
                        + "\"ss-pearson\":{\"pass\":true},\"ss-vif\":{\"pass\":true},\"ss-compare\":{\"pass\":true}}");
        BfiaAlgorithmSuite restarted = suite();
        var success = restarted.report(completed);
        assertEquals(BfiaAlgorithmSuite.Status.PASSED, success.status());
        assertEquals(BfiaAlgorithmSuite.ALGORITHMS, success.algorithms().stream().map(BfiaAlgorithmSuite.Algorithm::id).toList());
        assertTrue(success.algorithms().get(0).numericalDetails().get("pass").booleanValue());
        assertEquals(BfiaAlgorithmSuite.Status.FAILED, restarted.report(failedRunner).status());
        var invalid = restarted.report(malformed);
        assertEquals(BfiaAlgorithmSuite.Status.FAILED, invalid.status());
        assertNull(invalid.algorithms().get(0).numerical());
        assertNull(invalid.algorithms().get(2).protocol());
        assertEquals(BfiaAlgorithmSuite.Status.FAILED, restarted.report(interrupted).status());

        Path latest = root.resolve("runs/latest-id");
        Files.writeString(latest, completed);
        assertEquals(completed, suite().latest(true).run().runId());
        assertFalse(suite().latest(true).canStart(), "demo cooldown survives a JVM restart");
        assertTrue(suite().latest(false).canStart());
    }

    @Test
    void readsOnlyAllowlistedLocalLogs() throws IOException {
        String id = seed("0");
        Path log = Files.createDirectory(result(id, "python").resolve("sgb")).resolve("alice.stdout.log");
        Files.writeString(log, "actual test stdout");
        assertEquals("actual test stdout", suite().log(id, "sgb", "alice"));
        assertThrows(IllegalArgumentException.class, () -> suite().log(id, "../python", "alice"));
        assertThrows(IllegalArgumentException.class, () -> suite().log(id, "sgb", "../runner"));
        Files.createSymbolicLink(log.resolveSibling("bob.stdout.log"), log);
        assertThrows(NoSuchElementException.class, () -> suite().log(id, "sgb", "bob"));
    }

    @Test
    void demoRequiresExplicitOptInAndSameOriginAjax() throws IOException {
        BfiaAlgorithmSuite suite = mock(BfiaAlgorithmSuite.class);
        EnvService environment = mock(EnvService.class);
        ProtocolConfigService protocols = mock(ProtocolConfigService.class);
        when(suite.configured()).thenReturn(true);
        when(environment.getPlatformNodeId()).thenReturn("alice");
        when(protocols.isActive("bfia")).thenReturn(true);
        when(suite.start(true)).thenReturn(new BfiaAlgorithmSuite.Latest(false, null));
        UserContext.setBaseUser(UserContextDTO.builder().name("admin").platformNodeId("alice").build());
        try {
            MockHttpServletRequest ajax = new MockHttpServletRequest("POST", "/api/v1bfia/algorithm-run/start");
            ajax.addHeader("X-Requested-With", "XMLHttpRequest");
            ajax.addHeader("Sec-Fetch-Site", "same-origin");
            String officeOrigin = "http://alice.example.test:18101";
            var disabled = new BfiaAlgorithmSuiteController(suite, protocols, environment, false, false, officeOrigin);
            var enabled = new BfiaAlgorithmSuiteController(suite, protocols, environment, false, true, officeOrigin);
            assertThrows(ResponseStatusException.class, () -> disabled.start(ajax));

            MockHttpServletRequest foreign = new MockHttpServletRequest("POST", "/api/v1bfia/algorithm-run/start");
            foreign.addHeader("X-Requested-With", "XMLHttpRequest");
            foreign.addHeader("Sec-Fetch-Site", "cross-site");
            foreign.addHeader("Origin", officeOrigin);
            assertThrows(ResponseStatusException.class, () -> enabled.start(foreign));
            assertThrows(ResponseStatusException.class, () -> enabled.start(new MockHttpServletRequest()));
            MockHttpServletRequest wrongPort = new MockHttpServletRequest("POST", "/api/v1bfia/algorithm-run/start");
            wrongPort.addHeader("X-Requested-With", "XMLHttpRequest");
            wrongPort.addHeader("Origin", "http://alice.example.test:18102");
            assertThrows(ResponseStatusException.class, () -> enabled.start(wrongPort));
            verify(suite, never()).start(true);

            assertEquals(200, enabled.start(ajax).getStatusCode().value());
            MockHttpServletRequest behindProxy = new MockHttpServletRequest("POST", "/api/v1bfia/algorithm-run/start");
            behindProxy.addHeader("X-Requested-With", "XMLHttpRequest");
            behindProxy.addHeader("Origin", officeOrigin);
            assertEquals(200, enabled.start(behindProxy).getStatusCode().value());
            verify(suite, times(2)).start(true);
        } finally {
            UserContext.remove();
        }
    }

    private BfiaAlgorithmSuite suite() throws IOException {
        return new BfiaAlgorithmSuite(json, Path.of("/bin/sh").toRealPath().toString(), root.toString());
    }

    private String seed(String exitCode) throws IOException {
        String id = UUID.randomUUID().toString();
        Path directory = Files.createDirectories(root.resolve("runs").resolve(id));
        json.writeValue(directory.resolve("state.json").toFile(),
                new BfiaAlgorithmSuite.State(id, BfiaAlgorithmSuite.Status.RUNNING,
                        "2026-11-03T08:00:00Z", null, null, null, null));
        for (String language : List.of("python", "cpp")) {
            Path result = Files.createDirectories(result(id, language));
            ObjectNode report = validation(language.equals("python") ? BfiaAlgorithmSuite.PYTHON : BfiaAlgorithmSuite.CPP);
            for (String kind : List.of("numerical", "protocol")) {
                json.writeValue(result.resolve(kind + "-validation.json").toFile(), report);
            }
        }
        if (exitCode != null) {
            Files.writeString(directory.resolve("exit-code"), exitCode + "\n");
        }
        return id;
    }

    private ObjectNode validation(List<String> names) {
        ObjectNode report = json.createObjectNode();
        names.forEach(id -> report.putObject(id).put("pass", true));
        return report;
    }

    private Path result(String id, String language) {
        return root.resolve("runs").resolve(id).resolve(language).resolve(id);
    }
}
