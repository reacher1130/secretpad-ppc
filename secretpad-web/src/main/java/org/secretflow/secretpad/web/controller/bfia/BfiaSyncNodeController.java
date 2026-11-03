package org.secretflow.secretpad.web.controller.bfia;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping(BfiaNodePeerChannel.PATH)
class BfiaSyncNodeController {
    private final BfiaNodeCooperation cooperation;

    BfiaSyncNodeController(BfiaNodeCooperation cooperation) {
        this.cooperation = cooperation;
    }

    @PostMapping(BfiaNodeCooperation.APPLY)
    ResponseEntity<Void> apply(@RequestHeader HttpHeaders headers, @RequestBody byte[] body) {
        return receive(BfiaNodeCooperation.APPLY, headers, body);
    }

    @PostMapping(BfiaNodeCooperation.CONFIRM)
    ResponseEntity<Void> confirm(@RequestHeader HttpHeaders headers, @RequestBody byte[] body) {
        return receive(BfiaNodeCooperation.CONFIRM, headers, body);
    }

    private ResponseEntity<Void> receive(String action, HttpHeaders headers, byte[] body) {
        try {
            String ack = cooperation.receive(action, headers, body);
            return ResponseEntity.noContent().header(BfiaNodePeerChannel.ACK, ack).build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException | IOException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
}
