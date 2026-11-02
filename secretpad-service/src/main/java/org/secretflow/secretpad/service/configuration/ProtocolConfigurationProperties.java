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

package org.secretflow.secretpad.service.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Protocol switching configuration for P2P edge nodes.
 */
@Data
@ConfigurationProperties(prefix = "secretpad.protocol")
public class ProtocolConfigurationProperties {

    /**
     * Default protocol. Supported values are secretflow, bfia, fate and paddledtx.
     */
    private String defaultProtocol = "secretflow";

    /**
     * Whether the frontend is allowed to switch protocols at runtime.
     */
    private boolean canSwitch = true;

    /**
     * Persistent runtime protocol state. The file is deliberately independent from the
     * protocol-specific business databases so a successful switch survives a process restart.
     */
    private String stateFile = "./data/active-protocol";

    /**
     * FATE-Flow endpoint used when the FATE protocol is selected. A registered
     * FATE cluster can still override this value for job submission.
     */
    private String fateFlowAddress = "http://127.0.0.1:19381";
    private String fatePeerFlowAddress = "http://127.0.0.1:19382";

    /**
     * PaddleDTX requester CLI. Leave empty to expose the protocol and readiness
     * check without enabling task submission; deployments should set this to the
     * linux/amd64 requester binary.
     */
    private String paddledtxRequesterCommand = "";

    /**
     * PaddleDTX requester configuration file.
     */
    private String paddledtxRequesterConfig = "";

    /**
     * Lightweight executor endpoint used to verify that the local PaddleDTX
     * deployment is reachable.
     */
    private String paddledtxHealthAddress = "http://127.0.0.1:8511/v1/task/list";
    private String paddledtxPeerHealthAddress = "http://127.0.0.1:8512/v1/task/list";
    private String paddledtxModelStorageType = "XuperDB";
    private String paddledtxStorageHealthAddress = "http://127.0.0.1:8441/v1/file/getns?name=paddlempc";
    private String paddledtxPeerStorageHealthAddress = "http://127.0.0.1:8442/v1/file/getns?name=paddlempc";
}
