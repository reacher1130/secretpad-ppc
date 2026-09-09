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

package org.secretflow.secretpad.service.model.protocol;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ProtocolConfigVO {

    /**
     * Default protocol for this SecretPad instance.
     */
    private String defaultProtocol;

    /**
     * Whether the user is allowed to switch protocols in the UI.
     */
    private boolean canSwitch;

    /**
     * Protocol currently prepared by the backend.
     */
    private String activeProtocol;

    /** Protocols exposed by this build, in the order shown by the frontend. */
    private List<String> supportedProtocols;

    /** Human-readable engine labels used by the protocol switcher. */
    private Map<String, String> engineByProtocol;
}
