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

package org.secretflow.secretpad.web.controller;

import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.model.protocol.KusciaRestartRequest;
import org.secretflow.secretpad.service.model.protocol.ProtocolConfigVO;
import org.secretflow.secretpad.service.protocol.KusciaProtocolSwitchService;
import org.secretflow.secretpad.service.protocol.ProtocolConfigService;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1alpha1")
@RequiredArgsConstructor
public class ProtocolConfigController {

    private final ProtocolConfigService protocolConfigService;
    private final KusciaProtocolSwitchService kusciaProtocolSwitchService;

    @GetMapping("/config/protocol")
    public SecretPadResponse<ProtocolConfigVO> getProtocolConfig() {
        return SecretPadResponse.success(protocolConfigService.getProtocolConfig());
    }

    @PostMapping("/node/kuscia/restart")
    public SecretPadResponse<ProtocolConfigVO> restartKuscia(@RequestBody KusciaRestartRequest request) {
        return SecretPadResponse.success(kusciaProtocolSwitchService.restartKuscia(request.getProtocol()));
    }
}
