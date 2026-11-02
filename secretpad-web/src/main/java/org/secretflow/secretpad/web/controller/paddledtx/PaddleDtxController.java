package org.secretflow.secretpad.web.controller.paddledtx;

import lombok.RequiredArgsConstructor;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.paddledtx.PaddleDtxClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** PAD-facing PaddleDTX / paddlempc adapter endpoints. */
@RestController
@RequestMapping("/api/v1alpha1/paddledtx")
@RequiredArgsConstructor
public class PaddleDtxController {

    private final PaddleDtxClient client;

    @GetMapping("/status")
    public SecretPadResponse<Map<String, Object>> status() {
        return SecretPadResponse.success(client.readiness());
    }

    @PostMapping("/task/submit")
    public SecretPadResponse<Map<String, Object>> submit(@RequestBody Map<String, Object> request) {
        return SecretPadResponse.success(client.submit(request));
    }

    @GetMapping("/task/get")
    public SecretPadResponse<Map<String, Object>> get(@RequestParam("taskId") String taskId) {
        return SecretPadResponse.success(client.getTask(taskId));
    }

    @PostMapping("/task/start")
    public SecretPadResponse<Map<String, Object>> start(@RequestBody Map<String, String> request) {
        return SecretPadResponse.success(client.startTask(request.get("taskId")));
    }

    @PostMapping("/task/list")
    public SecretPadResponse<Map<String, Object>> list() {
        return SecretPadResponse.success(client.listTasks());
    }
}
