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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class KusciaProtocolSwitchService {

    private final ProtocolConfigurationProperties properties;

    @Value("${secretpad.kuscia.switch-script:./scripts/switch_alice_protocol.sh}")
    private String switchScript;

    public void restartKuscia(String protocol) {
        if (!properties.isCanSwitch()) {
            throw new IllegalStateException("Protocol switching is disabled on this node");
        }
        if (!"bfia".equals(protocol) && !"secretflow".equals(protocol)) {
            throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        }
        String target = "bfia".equals(protocol) ? "bfia" : "kuscia";
        try {
            log.info("Restarting Kuscia with protocol {} via script {}", target, switchScript);
            ProcessBuilder pb = new ProcessBuilder("bash", switchScript, target);
            pb.directory(new File("."));
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
            log.info("Kuscia restarted successfully with protocol {}", target);
        } catch (Exception e) {
            log.error("Failed to restart Kuscia with protocol {}", target, e);
            throw new RuntimeException("Kuscia restart failed: " + e.getMessage(), e);
        }
    }
}
