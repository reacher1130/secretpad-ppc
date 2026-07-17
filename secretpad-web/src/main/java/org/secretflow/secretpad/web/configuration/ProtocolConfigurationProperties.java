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

package org.secretflow.secretpad.web.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Protocol switching configuration for P2P edge nodes.
 *
 * <p>Controls whether this SecretPad instance can switch between BFIA and native
 * Secretflow protocols, and which protocol is the default on first load.
 */
@Data
@Component
@ConfigurationProperties(prefix = "secretpad.protocol")
public class ProtocolConfigurationProperties {

    /**
     * Default protocol: "secretflow" (native) or "bfia".
     */
    private String defaultProtocol = "secretflow";

    /**
     * Whether the frontend is allowed to switch protocols at runtime.
     */
    private boolean canSwitch = true;
}
