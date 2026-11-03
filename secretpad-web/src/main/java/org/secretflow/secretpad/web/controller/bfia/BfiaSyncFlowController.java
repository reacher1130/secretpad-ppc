package org.secretflow.secretpad.web.controller.bfia;

import lombok.RequiredArgsConstructor;
import org.secretflow.secretpad.service.bfia.BfiaFlowLifecycleService;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/v1/interconn/flow")
@RequiredArgsConstructor
public class BfiaSyncFlowController {
    private final BfiaFlowLifecycleService lifecycle;
    private final BfiaPeerPsiResult peerPsiResult;
    private final BfiaNodePeerChannel channel;

    @PostMapping("/audit/apply")
    public SecretPadResponse<Void> apply(@RequestHeader("kuscia-origin-source") String source,
            @RequestBody BfiaFlowLifecycleService.PeerApply request) {
        lifecycle.receive(source, request);
        return SecretPadResponse.success();
    }

    @PostMapping("/audit/confirm")
    public SecretPadResponse<Boolean> confirm(@RequestHeader("kuscia-origin-source") String source,
            @RequestBody Map<String, String> request) {
        return SecretPadResponse.success(lifecycle.confirm(request.get("auditId"), request.get("status"), source, false));
    }

    @PostMapping("/result/latest-psi-job")
    ResponseEntity<byte[]> latestPsiJob(@RequestHeader HttpHeaders headers, @RequestBody byte[] body) {
        try {
            byte[] reply = peerPsiResult.latest(headers, body);
            return ResponseEntity.ok().headers(channel.replyHeaders(BfiaPeerPsiResult.PATH, headers, reply)).body(reply);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException | IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
