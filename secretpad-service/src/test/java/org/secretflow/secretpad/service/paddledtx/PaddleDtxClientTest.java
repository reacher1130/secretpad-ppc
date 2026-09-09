package org.secretflow.secretpad.service.paddledtx;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.secretflow.secretpad.service.configuration.ProtocolConfigurationProperties;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PaddleDtxClientTest {
    @TempDir Path temp;
    @Test void requiresBothExecutorBusinessQueriesAndRequester() throws Exception {
        var reply = new AtomicReference<>("{\"code\":\"0\",\"data\":[]}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            byte[] bytes = "{\"code\":\"0\",\"data\":[]}".getBytes();
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.createContext("/peer", ex -> {
            byte[] bytes = reply.get().getBytes();
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.start();
        try {
            var props = new ProtocolConfigurationProperties();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            props.setPaddledtxHealthAddress(base + "/ok");
            props.setPaddledtxPeerHealthAddress(base + "/peer");
            var client = new PaddleDtxClient(props);
            assertFalse(client.isReady());
            props.setPaddledtxRequesterCommand("/bin/sh");
            assertTrue(client.isReady());
            reply.set("{\"code\":\"10001\",\"message\":\"chain unavailable\"}");
            assertFalse(client.isReady(), "HTTP 200 must not hide a chain failure");
            reply.set("{}"); assertFalse(client.isReady());
        } finally { server.stop(0); }
    }
    @Test void timesOutAProcessThatKeepsStdoutOpen() throws Exception {
        Path script = temp.resolve("hang.sh");
        Files.writeString(script, "#!/bin/sh\nexec sleep 30\n");
        script.toFile().setExecutable(true);
        var props = new ProtocolConfigurationProperties();
        props.setPaddledtxRequesterCommand(script.toString());
        var client = new PaddleDtxClient(props);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var failure = assertThrows(IllegalStateException.class, () -> client.runCli(List.of(), 1));
            assertTrue(failure.getMessage().contains("timed out"));
        });
    }
    @Test void predictionRequiresTrainingModelAndCliErrorsAreFailures() throws Exception {
        var props = new ProtocolConfigurationProperties();
        var client = new PaddleDtxClient(props);
        assertThrows(IllegalArgumentException.class, () -> client.submit(Map.of("type", "predict")));
        Path script = temp.resolve("failure.sh");
        Files.writeString(script, "#!/bin/sh\necho 'Publish task failed: rejected'\nexit 0\n");
        script.toFile().setExecutable(true);
        props.setPaddledtxRequesterCommand(script.toString());
        assertThrows(IllegalStateException.class, () -> client.runCli(List.of(), 1));
    }
}
