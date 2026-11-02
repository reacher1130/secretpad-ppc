package org.secretflow.secretpad.service.paddledtx;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.secretflow.secretpad.service.configuration.ProtocolConfigurationProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class PaddleDtxClientTest {
    private static final String TASK_ID = "7109ac57-fdaf-4a2e-a98a-5c09a97542b1";
    private static final String OTHER_TASK_ID = "977f0939-eafa-49de-8973-938c8b0f594f";
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
            props.setPaddledtxModelStorageType("Local");
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
        assertThrows(IllegalArgumentException.class, () -> client.submit(Map.of("type", "predict", "modelTaskId", "invalid")));
        Path script = temp.resolve("failure.sh");
        Files.writeString(script, "#!/bin/sh\necho 'Publish task failed: rejected'\nexit 0\n");
        script.toFile().setExecutable(true);
        props.setPaddledtxRequesterCommand(script.toString());
        assertThrows(IllegalStateException.class, () -> client.runCli(List.of(), 1));
    }

    @Test void publishedTaskRemainsRecoverableWhileAwaitingApproval() {
        List<List<String>> commands = new ArrayList<>();
        var client = client(args -> {
            commands.add(args);
            return "publish".equals(args.get(1)) ? "TaskID: " + TASK_ID : snapshot(TASK_ID, "Confirming");
        });
        Map<String, Object> result = client.submit(Map.of("name", "waiting for approval"));
        assertEquals(TASK_ID, result.get("taskId"));
        assertEquals("Confirming", task(result).get("status"));
        assertEquals(List.of("publish", "getbyid"), commands.stream().map(args -> args.get(1)).toList());
        assertTrue(String.valueOf(result.get("output")).contains("TaskID: " + TASK_ID));
    }

    @Test void publishKeepsItsIdWhenTheFollowUpQueryFails() {
        var client = client(args -> {
            if ("publish".equals(args.get(1))) return "TaskID: " + TASK_ID;
            throw new IllegalStateException("chain unavailable");
        });
        Map<String, Object> result = client.submit(Map.of());
        assertEquals(TASK_ID, result.get("taskId"));
        assertEquals("Unknown", task(result).get("status"));
        assertEquals("chain unavailable", result.get("error"));
    }

    @Test void readyTaskStartsOnceAndLaterRequestsDoNotRestartIt() {
        var state = new AtomicReference<>("Ready");
        var starts = new AtomicInteger();
        var client = client(args -> {
            if ("publish".equals(args.get(1))) return "TaskID: " + TASK_ID;
            if ("start".equals(args.get(1))) {
                assertEquals(TASK_ID, args.get(3));
                starts.incrementAndGet();
                state.set("Processing");
                return "OK";
            }
            return snapshot(TASK_ID, state.get());
        });
        Map<String, Object> result = client.submit(Map.of());
        assertEquals("Processing", task(result).get("status"));
        assertTrue(result.containsKey("start"));
        for (String status : List.of("Processing", "ToProcess", "Finished", "Failed", "Rejected", "Confirming", "Publishing")) {
            state.set(status);
            assertEquals(status, task(client.startTask(TASK_ID)).get("status"));
        }
        assertEquals(1, starts.get());
    }

    @Test void anApprovalCanBeResumedUsingTheExistingChainTask() {
        var state = new AtomicReference<>("Confirming");
        var publishes = new AtomicInteger();
        var starts = new AtomicInteger();
        Function<List<String>, String> requester = args -> {
            if ("publish".equals(args.get(1))) {
                publishes.incrementAndGet();
                return "TaskID: " + TASK_ID;
            }
            if ("start".equals(args.get(1))) {
                starts.incrementAndGet();
                state.set("ToProcess");
                return "OK";
            }
            return snapshot(TASK_ID, state.get());
        };
        client(requester).submit(Map.of());
        state.set("Ready");
        assertEquals("ToProcess", task(client(requester).startTask(TASK_ID)).get("status"));
        assertEquals(1, publishes.get());
        assertEquals(1, starts.get());
    }

    @Test void startFailurePreservesTheTaskAndConcurrentStartsAreIdempotent() {
        var state = new AtomicReference<>("Ready");
        var concurrent = new AtomicReference<>(false);
        var client = client(args -> {
            if ("start".equals(args.get(1))) {
                if (concurrent.get()) state.set("Processing");
                throw new IllegalStateException("StartTask failed: rejected");
            }
            return snapshot(TASK_ID, state.get());
        });
        Map<String, Object> failure = client.startTask(TASK_ID);
        assertEquals(TASK_ID, failure.get("taskId"));
        assertEquals("Ready", task(failure).get("status"));
        assertTrue(failure.containsKey("error"));
        concurrent.set(true);
        Map<String, Object> advanced = client.startTask(TASK_ID);
        assertEquals("Processing", task(advanced).get("status"));
        assertFalse(advanced.containsKey("error"));
    }

    @Test void chainHistoryAndDetailsExposeStructuredFieldsWithoutLosingRawOutput() {
        String output = "warning: no optional app.conf\n" + snapshot(TASK_ID, "Finished")
                + "\nTaskID: " + OTHER_TASK_ID + "\nTaskName: awaiting approval\nTaskType: predict\n"
                + "TaskStatus: Confirming\nPublishTime: 2026-09-14 11:20:00\n\ntaskNum : 2\n";
        var client = client(args -> output);
        Map<String, Object> history = client.listTasks();
        List<?> tasks = (List<?>) history.get("tasks");
        assertEquals(2, tasks.size());
        assertEquals("Finished", ((Map<?, ?>) tasks.get(0)).get("status"));
        assertEquals("Confirming", ((Map<?, ?>) tasks.get(1)).get("status"));
        assertEquals("awaiting approval", ((Map<?, ?>) tasks.get(1)).get("name"));
        assertEquals("2026-09-14 11:20:00", ((Map<?, ?>) tasks.get(1)).get("publishTime"));
        assertEquals(output, history.get("output"));
        assertThrows(IllegalStateException.class, () -> client.getTask(TASK_ID));
        Map<String, Object> detail = client(args -> snapshot(TASK_ID, "Finished")).getTask(TASK_ID);
        assertEquals(TASK_ID, detail.get("taskId"));
        assertEquals("Finished", task(detail).get("status"));
        assertEquals("sample task", task(detail).get("name"));
        assertEquals(0, detail.get("exitCode"));
    }

    @Test void malformedIdsAndUnknownStatusesCannotReachTaskStart() {
        var client = client(args -> { fail("invalid IDs must fail before starting the requester"); return ""; });
        for (String id : new String[]{null, "", "--help", "1-1-1-1-1", TASK_ID + "\n"}) {
            assertThrows(IllegalArgumentException.class, () -> client.getTask(id));
            assertThrows(IllegalArgumentException.class, () -> client.startTask(id));
        }
        var unknown = client(args -> {
            assertEquals("getbyid", args.get(1));
            return snapshot(TASK_ID, "Unexpected");
        });
        assertEquals("Unknown", task(unknown.startTask(TASK_ID)).get("status"));
        assertThrows(IllegalStateException.class,
                () -> client(args -> "warning contains " + TASK_ID).submit(Map.of()));
        Map<String, Object> recovery = client(args -> "publish".equals(args.get(1))
                ? "TaskID: " + TASK_ID : "TaskID: invalid").submit(Map.of());
        assertEquals(TASK_ID, recovery.get("taskId"));
        assertTrue(recovery.containsKey("error"));
    }

    private PaddleDtxClient client(Function<List<String>, String> output) {
        return new PaddleDtxClient(new ProtocolConfigurationProperties()) {
            @Override public Map<String, Object> readiness() {
                return Map.of("ready", true);
            }
            @Override Map<String, Object> runCli(List<String> args, long timeoutSeconds) {
                return new LinkedHashMap<>(Map.of("exitCode", 0, "output", output.apply(args)));
            }
        };
    }

    @Test void bothStorageNamespacesNeedEnoughOnlineHealthyNodes() throws Exception {
        try (var storage = new StorageServer()) {
            var client = new PaddleDtxClient(storage.properties);
            Map<String, Object> ready = client.readiness();
            assertEquals(true, ready.get("ready"));
            assertEquals("XuperDB", ready.get("modelStorageType"));
            assertEquals(true, ready.get("storageReady"));
            List<?> probes = (List<?>) ready.get("storage");
            assertEquals(2, probes.size());
            for (Object probe : probes) assertEquals(true, ((Map<?, ?>) probe).get("checked"));
            assertEquals(2, ((Map<?, ?>) probes.get(0)).get("eligibleNodes"));
            assertEquals(2, ((Map<?, ?>) probes.get(0)).get("replica"));
            storage.peerNamespace = namespace(3);
            Map<String, Object> insufficient = client.readiness();
            assertEquals(false, insufficient.get("ready"));
            for (Object probe : (List<?>) insufficient.get("storage")) assertEquals(true, ((Map<?, ?>) probe).get("checked"));
            assertTrue(String.valueOf(insufficient.get("error")).contains("需要 3"));
        }
    }

    @Test void offlineAndRedStorageNodesCannotSatisfyReplica() throws Exception {
        try (var storage = new StorageServer()) {
            storage.nodes = List.of(storageNode(StorageServer.FIRST, true),
                    storageNode(StorageServer.SECOND, false), storageNode(StorageServer.THIRD, true));
            var client = new PaddleDtxClient(storage.properties);
            Map<String, Object> result = client.readiness();
            Map<?, ?> probe = (Map<?, ?>) ((List<?>) result.get("storage")).get(0);
            assertEquals(false, result.get("ready"));
            assertEquals(true, probe.get("checked"));
            assertEquals(1, probe.get("eligibleNodes"));
            assertEquals(3, probe.get("totalNodes"));
            storage.health.replaceAll((id, status) -> "Red");
            result = client.readiness();
            probe = (Map<?, ?>) ((List<?>) result.get("storage")).get(0);
            assertEquals(true, probe.get("checked"));
            assertEquals(0, probe.get("eligibleNodes"));
            assertEquals(false, result.get("storageReady"));
        }
    }

    @Test void storageBusinessFailuresAndMalformedMetadataFailClosed() throws Exception {
        try (var storage = new StorageServer()) {
            var client = new PaddleDtxClient(storage.properties);
            for (String reply : List.of("{}", "{\"code\":7,\"data\":{}}", namespace(0),
                    "{\"code\":0,\"data\":{\"namespace\":{\"name\":\"paddlempc\",\"replica\":\"2\"}}}")) {
                storage.namespace = reply;
                assertStorageUnchecked(client);
            }
            storage.namespace = namespace(2);
            storage.nodeReply = "{\"code\":0,\"data\":{}}";
            assertStorageUnchecked(client);
            storage.nodeReply = null;
            for (List<Map<String, Object>> nodes : List.of(
                    List.of(storageNode(StorageServer.FIRST, true), storageNode(StorageServer.FIRST, true)),
                    List.of(Map.<String, Object>of("id", "not-base64", "online", true)),
                    List.of(Map.<String, Object>of("id", Base64.getEncoder().encodeToString(StorageServer.FIRST.getBytes(StandardCharsets.US_ASCII)))))) {
                storage.nodes = nodes;
                assertStorageUnchecked(client);
            }
            storage.nodes = List.of(storageNode(StorageServer.FIRST, true), storageNode(StorageServer.SECOND, true));
            storage.healthReply = "{\"code\":7,\"data\":\"Green\"}";
            assertStorageUnchecked(client);
            storage.healthReply = "{\"code\":0,\"data\":\"Unknown\"}";
            assertStorageUnchecked(client);
            storage.healthReply = null;
            storage.healthStatus = 503;
            assertStorageUnchecked(client);
            storage.healthStatus = 200;
            storage.properties.setPaddledtxStorageHealthAddress(null);
            assertStorageUnchecked(client);
        }
    }

    private void assertStorageUnchecked(PaddleDtxClient client) {
        Map<String, Object> result = client.readiness();
        assertEquals(false, result.get("ready"), "Invalid storage responses must keep the task gate closed");
        Map<?, ?> probe = (Map<?, ?>) ((List<?>) result.get("storage")).get(0);
        assertEquals(false, probe.get("checked"), "Failed storage queries must not become a healthy runtime wait");
    }

    @Test void explicitLocalStorageKeepsItsSemanticsAndUnknownModesAreRejected() throws Exception {
        try (var storage = new StorageServer()) {
            storage.properties.setPaddledtxModelStorageType("Local");
            storage.properties.setPaddledtxStorageHealthAddress("not-a-url");
            storage.properties.setPaddledtxPeerStorageHealthAddress("");
            var client = new PaddleDtxClient(storage.properties);
            Map<String, Object> local = client.readiness();
            assertEquals(true, local.get("ready"));
            assertEquals("Local", local.get("modelStorageType"));
            assertEquals(List.of(), local.get("storage"));
            assertEquals(0, storage.storageRequests.get());
            for (String mode : List.of("local", "Unknown", "")) {
                storage.properties.setPaddledtxModelStorageType(mode);
                assertFalse(client.isReady());
            }
            assertEquals(0, storage.storageRequests.get());
        }
    }

    @Test void unavailableStorageBlocksPublishingAndReadyStartWithoutLosingTheTask() throws Exception {
        try (var storage = new StorageServer()) {
            storage.health.replaceAll((id, status) -> "Red");
            var state = new AtomicReference<>("Ready");
            List<String> commands = new ArrayList<>();
            var client = cliClient(storage.properties, args -> {
                commands.add(args.get(1));
                assertEquals("getbyid", args.get(1));
                return snapshot(TASK_ID, state.get());
            });
            var failure = assertThrows(IllegalStateException.class, () -> client.submit(Map.of()));
            assertTrue(failure.getMessage().contains("模型存储"));
            assertEquals(List.of(), commands);
            Map<String, Object> pending = client.startTask(TASK_ID);
            assertEquals(TASK_ID, pending.get("taskId"));
            assertEquals("Ready", task(pending).get("status"));
            assertTrue(String.valueOf(pending.get("error")).contains("模型存储"));
            assertEquals(List.of("getbyid"), commands);
            int probes = storage.storageRequests.get();
            for (String status : List.of("Finished", "Processing")) {
                state.set(status);
                assertFalse(client.startTask(TASK_ID).containsKey("error"));
            }
            assertEquals(probes, storage.storageRequests.get(), "idempotent reads must not require writable storage");
        }
    }

    @Test void storageFailureAfterPublishStillReturnsThePublishedTaskId() throws Exception {
        try (var storage = new StorageServer()) {
            var publishes = new AtomicInteger();
            var client = cliClient(storage.properties, args -> {
                if ("publish".equals(args.get(1))) {
                    publishes.incrementAndGet();
                    storage.health.replaceAll((id, status) -> "Red");
                    return "TaskID: " + TASK_ID;
                }
                assertEquals("getbyid", args.get(1));
                return snapshot(TASK_ID, "Ready");
            });
            Map<String, Object> result = client.submit(Map.of());
            assertEquals(TASK_ID, result.get("taskId"));
            assertEquals("Ready", task(result).get("status"));
            assertTrue(result.containsKey("error"));
            assertEquals(1, publishes.get());
        }
    }

    @Test void healthyStorageAllowsPublishAndStart() throws Exception {
        try (var storage = new StorageServer()) {
            var state = new AtomicReference<>("Ready");
            List<String> commands = new ArrayList<>();
            var client = cliClient(storage.properties, args -> {
                commands.add(args.get(1));
                if ("publish".equals(args.get(1))) return "TaskID: " + TASK_ID;
                if ("start".equals(args.get(1))) {
                    state.set("Processing");
                    return "OK";
                }
                return snapshot(TASK_ID, state.get());
            });
            assertEquals("Processing", task(client.submit(Map.of())).get("status"));
            assertEquals(List.of("publish", "getbyid", "start", "getbyid"), commands);
        }
    }

    private PaddleDtxClient cliClient(ProtocolConfigurationProperties properties, Function<List<String>, String> output) {
        return new PaddleDtxClient(properties) {
            @Override Map<String, Object> runCli(List<String> args, long timeoutSeconds) {
                return new LinkedHashMap<>(Map.of("exitCode", 0, "output", output.apply(args)));
            }
        };
    }

    private static String namespace(int replica) {
        return "{\"code\":0,\"data\":{\"namespace\":{\"name\":\"paddlempc\",\"replica\":" + replica + "}}}";
    }

    private static Map<String, Object> storageNode(String id, boolean online) {
        return Map.of("id", Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.US_ASCII)), "online", online);
    }

    private static class StorageServer implements AutoCloseable {
        static final String FIRST = "a".repeat(128);
        static final String SECOND = "b".repeat(128);
        static final String THIRD = "c".repeat(128);
        final HttpServer server;
        final ProtocolConfigurationProperties properties = new ProtocolConfigurationProperties();
        final Map<String, String> health = new ConcurrentHashMap<>(Map.of(FIRST, "Green", SECOND, "Yellow", THIRD, "Red"));
        final AtomicInteger storageRequests = new AtomicInteger();
        volatile String namespace = namespace(2);
        volatile String peerNamespace = namespace(2);
        volatile String nodeReply;
        volatile String healthReply;
        volatile int healthStatus = 200;
        volatile List<Map<String, Object>> nodes = List.of(storageNode(FIRST, true), storageNode(SECOND, true));

        StorageServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var json = new ObjectMapper();
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String response;
                int status = 200;
                if ("/executor".equals(path)) {
                    response = "{\"code\":0,\"data\":[]}";
                } else {
                    storageRequests.incrementAndGet();
                    if ("/namespace".equals(path)) response = namespace;
                    else if ("/peer-namespace".equals(path)) response = peerNamespace;
                    else if ("/v1/node/list".equals(path)) {
                        response = nodeReply == null ? json.writeValueAsString(Map.of("code", 0, "data", nodes)) : nodeReply;
                    } else if ("/v1/node/health".equals(path)) {
                        status = healthStatus;
                        String id = exchange.getRequestURI().getQuery().substring(3);
                        response = healthReply == null
                                ? json.writeValueAsString(Map.of("code", 0, "data", health.getOrDefault(id, "Unknown"))) : healthReply;
                    } else {
                        status = 404;
                        response = "{}";
                    }
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            properties.setPaddledtxRequesterCommand("/bin/sh");
            properties.setPaddledtxHealthAddress(base + "/executor");
            properties.setPaddledtxPeerHealthAddress(base + "/executor");
            properties.setPaddledtxStorageHealthAddress(base + "/namespace");
            properties.setPaddledtxPeerStorageHealthAddress(base + "/peer-namespace");
        }

        @Override public void close() {
            server.stop(0);
        }
    }

    private Map<?, ?> task(Map<String, Object> result) {
        return (Map<?, ?>) result.get("task");
    }

    private String snapshot(String taskId, String status) {
        return "TaskID: " + taskId + "\nTaskName: sample task\nTaskType: train\nStatus: " + status
                + "\nPublishTime: 2026-09-14 11:10:00\n";
    }
}
