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
import org.secretflow.secretpad.service.model.protocol.ProtocolConfigVO;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProtocolConfigService {

    public static final List<String> SUPPORTED_PROTOCOLS =
            List.of("secretflow", "bfia", "fate", "paddledtx");

    private static final Map<String, String> ENGINE_BY_PROTOCOL = Map.of(
            "secretflow", "SecretFlow / Kuscia",
            "bfia", "BFIA interconnect adapter",
            "fate", "FATE-Flow / fate2-adapter",
            "paddledtx", "PaddleDTX / paddlempc");

    private final ProtocolConfigurationProperties properties;
    private final AtomicReference<String> activeProtocol = new AtomicReference<>("secretflow");

    @PostConstruct
    public void initialize() {
        String configuredDefault = normalize(properties.getDefaultProtocol());
        String initial = configuredDefault;
        Path statePath = statePath();
        if (properties.isCanSwitch() && Files.isRegularFile(statePath)) {
            try {
                String persisted = normalize(Files.readString(statePath, StandardCharsets.UTF_8));
                if (isSupported(persisted)) {
                    initial = persisted;
                } else {
                    log.warn("Ignoring unsupported persisted protocol '{}' in {}", persisted, statePath);
                }
            } catch (IOException e) {
                log.warn("Unable to read protocol state from {}, using configured default {}", statePath,
                        configuredDefault, e);
            }
        }
        activeProtocol.set(initial);
        log.info("Active privacy-computing protocol initialized to {}", initial);
    }

    public ProtocolConfigVO getProtocolConfig() {
        ProtocolConfigVO vo = new ProtocolConfigVO();
        vo.setDefaultProtocol(normalize(properties.getDefaultProtocol()));
        vo.setCanSwitch(properties.isCanSwitch());
        vo.setActiveProtocol(activeProtocol.get());
        vo.setSupportedProtocols(properties.isCanSwitch() ? SUPPORTED_PROTOCOLS
                : List.of(normalize(properties.getDefaultProtocol())));
        vo.setEngineByProtocol(ENGINE_BY_PROTOCOL);
        return vo;
    }

    public String getActiveProtocol() {
        return activeProtocol.get();
    }

    public boolean isActive(String protocol) {
        return normalize(protocol).equals(activeProtocol.get());
    }

    public String normalizeProtocol(String protocol) {
        return normalize(protocol);
    }

    public synchronized ProtocolConfigVO activateProtocol(String protocol) {
        String normalized = normalize(protocol);
        if (!isSupported(normalized)) {
            throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        }
        if (!properties.isCanSwitch() && !normalized.equals(normalize(properties.getDefaultProtocol()))) {
            throw new IllegalStateException("Protocol is disabled on this fixed-protocol node");
        }
        persist(normalized);
        activeProtocol.set(normalized);
        log.info("Active privacy-computing protocol changed to {}", normalized);
        return getProtocolConfig();
    }

    private void persist(String protocol) {
        Path target = statePath();
        Path parent = target.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            Files.writeString(temporary, protocol + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist active protocol to " + target, e);
        }
    }

    private Path statePath() {
        return Path.of(properties.getStateFile()).toAbsolutePath().normalize();
    }

    private String normalize(String protocol) {
        String normalized = protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "kuscia", "secretflow-native", "native" -> "secretflow";
            case "fate2", "fate-flow" -> "fate";
            case "paddle", "paddle-dtx", "chainpaddle", "chain-paddle" -> "paddledtx";
            default -> normalized;
        };
    }

    private boolean isSupported(String protocol) {
        return SUPPORTED_PROTOCOLS.contains(protocol);
    }
}
