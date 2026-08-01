package org.secretflow.secretpad.web.controller.bfia;

import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BFIA health endpoint to verify routing and signature filter.
 */
@RestController
@RequestMapping("/v1/interconn")
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaHealthController {

    @GetMapping("/health")
    public SecretPadResponse<String> health() {
        return SecretPadResponse.success("bfia-ok");
    }
}


