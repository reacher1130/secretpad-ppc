package org.secretflow.secretpad.service.test;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.secretflow.secretpad.service.fate.FateFlowClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FateFlowClientTest {
    @Test
    void sendsFate22GetJsonBodyAndChecksEnvelope() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> reply = new AtomicReference<>("{\"code\":0,\"data\":{}}");
        server.createContext("/v1/interconn/schedule/job/status_all", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new FateFlowClient();
            client.init();
            String address = "http://127.0.0.1:" + server.getAddress().getPort();
            assertTrue(client.checkConnection(address));
            assertEquals("GET", method.get());
            assertTrue(body.get().contains("\"job_id\":\"probe-"));
            assertTrue(client.checkConnection(address + "/v1/interconn/"));
            reply.set("{\"code\":404,\"msg\":\"not found\"}");
            assertFalse(client.checkConnection(address));
            reply.set("{}");
            assertFalse(client.checkConnection(address));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FATE_FLOW_TEST_ADDRESS", matches = ".+")
    void actualFate22AcceptsReadOnlyProbe() {
        var client = new FateFlowClient();
        client.init();
        assertTrue(client.checkConnection(System.getenv("FATE_FLOW_TEST_ADDRESS")));
        assertTrue(client.checkNativeConnection(System.getenv("FATE_FLOW_TEST_ADDRESS")));
    }
}
