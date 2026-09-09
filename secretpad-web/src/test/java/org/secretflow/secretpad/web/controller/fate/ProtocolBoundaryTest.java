package org.secretflow.secretpad.web.controller.fate;

import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.web.configuration.ProtocolBoundaryConfiguration;
import org.secretflow.secretpad.service.configuration.ProtocolConfigurationProperties;
import org.secretflow.secretpad.service.protocol.ProtocolConfigService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolBoundaryTest {
    @Test void bobRejectsFatePaddleAndNativeRequestsButAllowsBfia() throws Exception {
        var properties = new ProtocolConfigurationProperties();
        properties.setCanSwitch(false);properties.setDefaultProtocol("bfia");
        var boundary = new ProtocolBoundaryConfiguration(new ProtocolConfigService(properties));
        for (String path : new String[]{"/api/v1alpha1/fate/job/submit", "/api/v1alpha1/paddledtx/task/submit", "/api/v1alpha1/graph/start"}) {
            var response = new MockHttpServletResponse();
            assertFalse(boundary.preHandle(new MockHttpServletRequest("POST", path), response, new Object()));
            assertEquals(403, response.getStatus());
        }
        assertTrue(boundary.preHandle(new MockHttpServletRequest("POST", "/api/v1bfia/flow/start"), new MockHttpServletResponse(), new Object()));
    }
    @Test void aliceCanAccessEveryProtocolWithoutChangingPeerCapabilities() throws Exception {
        var boundary = new ProtocolBoundaryConfiguration(new ProtocolConfigService(new ProtocolConfigurationProperties()));
        for (String path : new String[]{"/api/v1alpha1/fate/job/submit", "/api/v1alpha1/paddledtx/task/submit", "/api/v1alpha1/graph/start", "/api/v1bfia/flow/start"})
            assertTrue(boundary.preHandle(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), new Object()));
    }
}
