package org.secretflow.secretpad.service.paddledtx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.secretflow.secretpad.service.configuration.ProtocolConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, local PaddleDTX requester bridge used by the protocol-specific UI.
 *
 * <p>The DAI distribution ships a requester CLI rather than a stable HTTP
 * management API. This client therefore keeps the process boundary explicit,
 * validates the configured executable and returns the CLI output without
 * logging private keys or request arguments.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaddleDtxClient {

    private static final Pattern TASK_ID = Pattern.compile(
            "(?i)([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    private final ProtocolConfigurationProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public Map<String, Object> readiness() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("engine", "paddlempc");
        result.put("healthAddress", properties.getPaddledtxHealthAddress());
        result.put("requesterConfigured", configured());
        List<Map<String, Object>> executors = new ArrayList<>();
        boolean ready = configured();
        for (String address : List.of(properties.getPaddledtxHealthAddress(),
                properties.getPaddledtxPeerHealthAddress())) {
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("address", address);
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(address))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                var envelope = objectMapper.readTree(response.body());
                boolean success = response.statusCode() == 200
                        && "0".equals(envelope.path("code").asText());
                probe.put("httpStatus", response.statusCode());
                probe.put("ready", success);
                if (!success) probe.put("error", envelope.path("message").asText("Executor query failed"));
                ready &= success;
            } catch (Exception e) {
                probe.put("ready", false);
                probe.put("error", e.getMessage());
                ready = false;
            }
            executors.add(probe);
        }
        result.put("executors", executors);
        result.put("httpStatus", executors.get(0).get("httpStatus"));
        result.put("ready", ready);
        if (!ready) result.put("error", "请求端未配置，或执行器/链上查询不可用，请查看各节点状态");
        return result;
    }

    public boolean isReady() {
        Object ready = readiness().get("ready");
        return Boolean.TRUE.equals(ready);
    }

    public Map<String, Object> submit(Map<String, Object> request) {
        String type = String.valueOf(request.getOrDefault("type", "train"));
        if ("predict".equals(type) && String.valueOf(request.getOrDefault("modelTaskId", "")).isBlank()) {
            throw new IllegalArgumentException("predict requires modelTaskId");
        }
        List<String> args = new ArrayList<>();
        args.add("task");
        args.add("publish");
        addOption(args, "--name", request.get("name"));
        addOption(args, "--type", request.getOrDefault("type", "train"));
        addOption(args, "--algorithm", request.getOrDefault("algorithm", "linear-vl"));
        addOption(args, "--files", request.get("files"));
        addOption(args, "--executors", request.get("executors"));
        addOption(args, "--psiLabel", request.getOrDefault("psiLabel", "id,id"));
        addOption(args, "--label", request.get("label"));
        addOption(args, "--labelName", request.get("labelName"));
        addOption(args, "--amplitude", request.get("amplitude"));
        addOption(args, "--accuracy", request.get("accuracy"));
        addOption(args, "--alpha", request.get("alpha"));
        addOption(args, "--taskId", request.get("modelTaskId"));

        Map<String, Object> result = runCli(args, 120);
        Object raw = result.get("output");
        if (raw != null) {
            Matcher matcher = TASK_ID.matcher(String.valueOf(raw));
            if (matcher.find()) {
                String taskId = matcher.group(1);
                result.put("taskId", taskId);

                // requester-cli keeps publish and start as two explicit
                // lifecycle calls.  The web action is named "发布并执行", so
                // complete the same signed start step here and return the
                // chain task snapshot for the result panel.
                Map<String, Object> start = startAfterConfirmation(taskId);
                result.put("start", start);
                result.put("task", waitForTerminalTask(taskId));
            }
        }
        return result;
    }

    public Map<String, Object> getTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        return runCli(List.of("task", "getbyid", "--id", taskId), 30);
    }

    public Map<String, Object> listTasks() {
        return runCli(List.of("task", "list"), 30);
    }

    Map<String, Object> runCli(List<String> commandArgs, long timeoutSeconds) {
        if (!configured()) {
            throw new IllegalStateException(
                    "PaddleDTX requester is not configured; set PADDLEDTX_REQUESTER_COMMAND");
        }
        List<String> command = new ArrayList<>();
        command.add(properties.getPaddledtxRequesterCommand());
        if (properties.getPaddledtxRequesterConfig() != null
                && !properties.getPaddledtxRequesterConfig().isBlank()) {
            command.add("-c");
            command.add(properties.getPaddledtxRequesterConfig());
        }
        command.addAll(commandArgs);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        String configPath = properties.getPaddledtxRequesterConfig();
        if (configPath != null && !configPath.isBlank()) {
            Path parent = Path.of(configPath).toAbsolutePath().getParent();
            if (parent != null) {
                processBuilder.directory(parent.toFile());
            }
        }
        processBuilder.redirectErrorStream(true);
        Path outputFile = null;
        Process process = null;
        try {
            outputFile = Files.createTempFile("paddledtx-requester-", ".log");
            processBuilder.redirectOutput(outputFile.toFile());
            process = processBuilder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalStateException("PaddleDTX requester timed out");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exitCode", process.exitValue());
            result.put("output", output);
            // The legacy requester-cli prints application errors and still exits 0.
            // Treat those messages as failures so the web API cannot report a
            // task as published when the chain rejected it (for example, when a
            // training label is missing).
            if (process.exitValue() != 0 || containsCliFailure(output)) {
                throw new IllegalStateException("PaddleDTX requester failed: " + summarize(output));
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start PaddleDTX requester", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PaddleDTX requester interrupted", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (outputFile != null) {
                try { Files.deleteIfExists(outputFile); }
                catch (IOException e) { log.warn("Unable to remove requester temporary output"); }
            }
        }
    }

    private boolean configured() {
        String command = properties.getPaddledtxRequesterCommand();
        return command != null && !command.isBlank();
    }

    private void addOption(List<String> args, String name, Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return;
        }
        args.add(name);
        args.add(String.valueOf(value));
    }

    private String summarize(String output) {
        if (output == null) {
            return "unknown error";
        }
        String compact = output.replaceAll("\\s+", " ").trim();
        return compact.length() > 500 ? compact.substring(0, 500) : compact;
    }

    /**
     * XChain confirmation is asynchronous.  A publish can therefore return
     * before the task leaves Confirming, while requester-cli's start command
     * is only valid for Ready/Failed tasks.  Retry only that expected
     * transition; all other errors remain hard failures for the web request.
     */
    private Map<String, Object> startAfterConfirmation(String taskId) {
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                return runCli(List.of("task", "start", "--id", taskId), 120);
            } catch (IllegalStateException e) {
                String message = e.getMessage();
                boolean confirming = message != null
                        && (message.contains("task status is Confirming")
                        || message.contains("task status is Publishing")
                        || message.contains("not Ready or Failed"));
                if (!confirming || attempt == 29) {
                    throw e;
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("PaddleDTX start retry interrupted", interrupted);
                }
            }
        }
        throw new IllegalStateException("PaddleDTX task did not become startable");
    }

    /** Wait briefly for both DAI executors to finish the MPC task. */
    private Map<String, Object> waitForTerminalTask(String taskId) {
        Map<String, Object> latest = getTask(taskId);
        for (int attempt = 0; attempt < 30; attempt++) {
            String output = String.valueOf(latest.getOrDefault("output", ""));
            if (output.contains("Status: Finished") || output.contains("Status: Failed")) {
                return latest;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("PaddleDTX task polling interrupted", interrupted);
            }
            latest = getTask(taskId);
        }
        return latest;
    }

    private boolean containsCliFailure(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        return output.contains("GetRequestClient failed")
                || output.contains("Publish task failed")
                || output.contains("GetTaskById failed")
                || output.contains("ListTask failed")
                || output.contains("GetPredictResult failed")
                || output.contains("Read privateKey failed")
                || output.contains("Read publicKey failed")
                || output.contains("ParseInLocation failed")
                || output.contains("StartTask failed")
                || output.contains("failed to check task publish")
                || output.contains("invalid `");
    }
}
