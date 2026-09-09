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

package org.secretflow.secretpad.service.protocol;

import org.secretflow.secretpad.service.configuration.ProtocolConfigurationProperties;
import org.secretflow.secretpad.service.fate.FateFlowClient;
import org.secretflow.secretpad.service.paddledtx.PaddleDtxClient;
import org.secretflow.secretpad.service.model.protocol.ProtocolConfigVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class KusciaProtocolSwitchService {

    private final ProtocolConfigurationProperties properties;
    private final ProtocolConfigService protocolConfigService;
    private final FateFlowClient fateFlowClient;
    private final PaddleDtxClient paddleDtxClient;

    @Value("${secretpad.kuscia.switch-script:../scripts/switch_alice_protocol.sh}")
    private String switchScript;

    public synchronized ProtocolConfigVO restartKuscia(String protocol) {
        if (!properties.isCanSwitch()) {
            throw new IllegalStateException("Protocol switching is disabled on this node");
        }
        String normalized = protocolConfigService.normalizeProtocol(protocol);
        if (!ProtocolConfigService.SUPPORTED_PROTOCOLS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        }
        if (protocolConfigService.isActive(normalized)) {
            log.info("Protocol {} is already active; skipping environment preparation", normalized);
            return protocolConfigService.getProtocolConfig();
        }
        if ("fate".equals(normalized)) {
            ensureFateReady();
            return protocolConfigService.activateProtocol(normalized);
        }
        if ("paddledtx".equals(normalized)) {
            ensurePaddleDtxReady();
            return protocolConfigService.activateProtocol(normalized);
        }

        String target = "bfia".equals(normalized) ? "bfia" : "kuscia";
        try {
            prepareKusciaProtocol(target);
            ProtocolConfigVO result = protocolConfigService.activateProtocol(normalized);
            log.info("Kuscia protocol {} prepared and activated successfully", target);
            return result;
        } catch (Exception e) {
            log.error("Failed to prepare protocol {}", normalized, e);
            throw new RuntimeException("Protocol switch failed: " + e.getMessage(), e);
        }
    }

    private void ensureFateReady() {
        // Prepare only Alice's local adapter. Native FATE owns remote party routing.
        prepareKusciaProtocol("fate");
        String address = properties.getFateFlowAddress();
        if (!fateFlowClient.checkNativeConnection(address)) {
            throw new IllegalStateException("FATE-Flow is not reachable at " + address);
        }
        String peerAddress = properties.getFatePeerFlowAddress();
        if (!fateFlowClient.checkNativeConnection(peerAddress)) {
            throw new IllegalStateException("Native FATE peer is not reachable at " + peerAddress);
        }
        log.info("Native FATE guest and host are ready: {}, {}", address, peerAddress);
    }

    private void prepareKusciaProtocol(String target) {
        try {
            File script = new File(switchScript).getCanonicalFile();
            if (!script.isFile()) {
                throw new IllegalStateException("Protocol switch script not found: " + script);
            }
            log.info("Preparing Kuscia protocol {} via script {}", target, script);
            ProcessBuilder pb = new ProcessBuilder("bash", script.getAbsolutePath(), target);
            pb.directory(script.getParentFile());
            // The HTTP server handling this request must remain alive. Protocol state is
            // persisted by ProtocolConfigService only after the script verifies both peers.
            pb.environment().put("RESTART_PAD", "false");
            pb.inheritIO();
            Process process = pb.start();
            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Kuscia restart timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Kuscia restart failed with exit code: " + process.exitValue());
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("Kuscia protocol preparation failed: " + e.getMessage(), e);
        }
    }

    private void ensurePaddleDtxReady() {
        if (!paddleDtxClient.isReady()) {
            throw new IllegalStateException("PaddleDTX requester or one of the executor chain queries is unavailable");
        }
        log.info("PaddleDTX both executor chain queries passed");
    }
}
