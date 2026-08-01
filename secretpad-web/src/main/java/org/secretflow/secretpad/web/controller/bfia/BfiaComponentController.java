package org.secretflow.secretpad.web.controller.bfia;

import lombok.RequiredArgsConstructor;
import org.secretflow.secretpad.service.bfia.BfiaComponentService;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.model.graph.CompListVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/interconn/components")
@RequiredArgsConstructor
public class BfiaComponentController {

    private final BfiaComponentService componentService;

    @GetMapping("/list")
    public SecretPadResponse<Map<String,Object>> list() {
        Map<String,Object> resp = new HashMap<>();
        resp.put("components", componentService.listComponents());
        return SecretPadResponse.success(resp);
    }

    @GetMapping("/listCompListVO")
    public SecretPadResponse<Map<String, CompListVO>> listCompListVO() {
        return SecretPadResponse.success(componentService.listCompListVO());
    }
}


