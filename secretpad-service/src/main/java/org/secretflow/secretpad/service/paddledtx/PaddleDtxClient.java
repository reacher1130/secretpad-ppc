package org.secretflow.secretpad.service.paddledtx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern TASK_ID = Pattern.compile(UUID_PATTERN, Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLISHED_TASK_ID = Pattern.compile(
            "(?im)^TaskID:\\h*(" + UUID_PATTERN + ")\\h*$");
    private static final Set<String> TASK_STATES = Set.of("Publishing", "Confirming", "Ready", "ToProcess",
            "Processing", "Finished", "Failed", "Rejected");
    private static final Pattern STORAGE_NODE_ID = Pattern.compile("[0-9a-fA-F]{128}");

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
        List<String> errors = new ArrayList<>();
        if (!ready) errors.add("请求端未配置，或执行器链上查询不可用，请查看各节点状态");
        String storageType = properties.getPaddledtxModelStorageType();
        result.put("modelStorageType", storageType);
        List<Map<String, Object>> storage = new ArrayList<>();
        boolean storageReady = "Local".equals(storageType);
        if ("XuperDB".equals(storageType)) {
            storageReady = true;
            for (String address : new String[]{properties.getPaddledtxStorageHealthAddress(),
                    properties.getPaddledtxPeerStorageHealthAddress()}) {
                Map<String, Object> probe = storageReadiness(address);
                storage.add(probe);
                if (!Boolean.TRUE.equals(probe.get("ready"))) {
                    storageReady = false;
                    String error = String.valueOf(probe.get("error"));
                    if (!errors.contains(error)) errors.add(error);
                }
            }
        } else if (!storageReady) {
            errors.add("模型存储类型不受支持，仅允许 XuperDB 或 Local");
        }
        result.put("storage", storage);
        result.put("storageReady", storageReady);
        ready &= storageReady;
        result.put("ready", ready);
        if (!ready) result.put("error", String.join("；", errors));
        return result;
    }

    private Map<String, Object> storageReadiness(String address) {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("address", address);
        probe.put("namespace", "");
        probe.put("replica", 0);
        probe.put("eligibleNodes", 0);
        probe.put("totalNodes", 0);
        probe.put("checked", false);
        probe.put("ready", false);
        try {
            URI namespaceAddress = URI.create(address);
            JsonNode namespace = storageData(namespaceAddress).path("namespace");
            if (!namespace.isObject() || !namespace.path("name").isTextual()
                    || namespace.path("name").asText().isBlank() || !namespace.path("replica").isIntegralNumber()
                    || !namespace.path("replica").canConvertToInt() || namespace.path("replica").asInt() < 1) {
                throw new IllegalStateException("模型存储返回的命名空间信息无效");
            }
            int replica = namespace.path("replica").asInt();
            probe.put("namespace", namespace.path("name").asText());
            probe.put("replica", replica);
            JsonNode nodes = storageData(namespaceAddress.resolve("/v1/node/list"));
            if (!nodes.isArray()) throw new IllegalStateException("模型存储返回的节点列表无效");
            probe.put("totalNodes", nodes.size());
            int eligible = 0;
            Set<String> identities = new HashSet<>();
            for (JsonNode node : nodes) {
                if (!node.isObject() || !node.path("id").isTextual() || !node.path("online").isBoolean()) {
                    throw new IllegalStateException("模型存储返回的节点信息无效");
                }
                String id = new String(Base64.getDecoder().decode(node.path("id").asText()), StandardCharsets.US_ASCII);
                if (!STORAGE_NODE_ID.matcher(id).matches() || !identities.add(id.toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("模型存储返回的节点身份无效或重复");
                }
                if (!node.path("online").asBoolean()) continue;
                JsonNode health = storageData(namespaceAddress.resolve("/v1/node/health?id=" + id));
                if (!health.isTextual() || !Set.of("Green", "Yellow", "Red").contains(health.asText())) {
                    throw new IllegalStateException("模型存储返回的节点健康状态无效");
                }
                if (!"Red".equals(health.asText())) eligible++;
            }
            probe.put("eligibleNodes", eligible);
            probe.put("checked", true);
            boolean ready = eligible >= replica;
            probe.put("ready", ready);
            if (!ready) probe.put("error", "模型存储暂不可写：命名空间 " + namespace.path("name").asText()
                    + " 需要 " + replica + " 个在线健康节点，当前 " + eligible + " 个，请等待节点健康恢复");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            probe.put("checked", false);
            probe.put("error", "无法确认模型存储可写状态：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        return probe;
    }

    private JsonNode storageData(URI address) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode envelope = objectMapper.readTree(response.body());
        if (response.statusCode() != 200 || envelope == null || !envelope.isObject()
                || !"0".equals(envelope.path("code").asText())) {
            throw new IllegalStateException("存储查询失败（HTTP " + response.statusCode() + "，业务状态无效）");
        }
        return envelope.path("data");
    }

    private void requireReady() {
        Map<String, Object> status = readiness();
        if (!Boolean.TRUE.equals(status.get("ready"))) {
            throw new IllegalStateException(String.valueOf(status.get("error")));
        }
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
        if ("predict".equals(type)) requireTaskId(String.valueOf(request.get("modelTaskId")));
        requireReady();
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
        Matcher matcher = PUBLISHED_TASK_ID.matcher(String.valueOf(result.getOrDefault("output", "")));
        if (!matcher.find()) {
            throw new IllegalStateException("PaddleDTX publish did not return a task ID; check task history before retrying");
        }
        String taskId = requireTaskId(matcher.group(1));
        result.put("taskId", taskId);
        // The chain persists the published task even when approval or the next request fails.
        Map<String, Object> started = startTask(taskId);
        result.put("task", started.get("task"));
        if (started.containsKey("start")) result.put("start", started.get("start"));
        if (started.containsKey("error")) result.put("error", started.get("error"));
        return result;
    }

    public Map<String, Object> getTask(String taskId) {
        String id = requireTaskId(taskId);
        Map<String, Object> result = runCli(List.of("task", "getbyid", "--id", id), 30);
        List<Map<String, Object>> tasks = parseTasks(String.valueOf(result.getOrDefault("output", "")));
        if (tasks.size() != 1 || !id.equals(tasks.get(0).get("taskId"))) {
            throw new IllegalStateException("PaddleDTX query did not return the requested task");
        }
        result.put("taskId", id);
        result.put("task", tasks.get(0));
        return result;
    }

    public Map<String, Object> listTasks() {
        Map<String, Object> result = runCli(List.of("task", "list"), 30);
        result.put("tasks", parseTasks(String.valueOf(result.getOrDefault("output", ""))));
        return result;
    }

    public Map<String, Object> startTask(String taskId) {
        String id = requireTaskId(taskId);
        Map<String, Object> result;
        try {
            result = getTask(id);
        } catch (IllegalStateException e) {
            result = new LinkedHashMap<>();
            result.put("taskId", id);
            result.put("task", Map.of("taskId", id, "status", "Unknown"));
            result.put("error", e.getMessage());
            return result;
        }
        Map<?, ?> task = (Map<?, ?>) result.get("task");
        if (!"Ready".equals(task.get("status"))) return result;
        try {
            requireReady();
        } catch (IllegalStateException e) {
            result.put("error", e.getMessage());
            return result;
        }
        try {
            Map<String, Object> start = runCli(List.of("task", "start", "--id", id), 30);
            result.put("start", start);
            Map<String, Object> latest = getTask(id);
            latest.put("start", start);
            return latest;
        } catch (IllegalStateException e) {
            // A concurrent start may already have advanced the same chain task.
            try {
                Map<String, Object> latest = getTask(id);
                result.putAll(latest);
                Object status = ((Map<?, ?>) latest.get("task")).get("status");
                if (Set.of("ToProcess", "Processing", "Finished").contains(status)) return result;
            } catch (IllegalStateException ignored) {
                // Keep the last chain snapshot and task ID available for recovery.
            }
            result.put("error", e.getMessage());
            return result;
        }
    }

    private String requireTaskId(String taskId) {
        if (taskId == null || !TASK_ID.matcher(taskId).matches()) {
            throw new IllegalArgumentException("taskId must be a UUID");
        }
        return UUID.fromString(taskId).toString();
    }

    private List<Map<String, Object>> parseTasks(String output) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        Map<String, Object> task = null;
        for (String line : output.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("TaskID".equals(key)) {
                if (!TASK_ID.matcher(value).matches()) {
                    throw new IllegalStateException("PaddleDTX query returned an invalid task ID");
                }
                task = new LinkedHashMap<>();
                task.put("taskId", requireTaskId(value));
                task.put("status", "Unknown");
                tasks.add(task);
            } else if (task != null) {
                switch (key) {
                    case "TaskName" -> task.put("name", value);
                    case "TaskType" -> task.put("type", value);
                    case "Status", "TaskStatus" -> task.put("status", TASK_STATES.contains(value) ? value : "Unknown");
                    case "PublishTime" -> task.put("publishTime", value);
                    case "Algorithm" -> task.put("algorithm", value);
                    case "ModelTaskID" -> task.put("modelTaskId", value);
                    case "StartTime" -> task.put("startTime", value);
                    case "EndTime" -> task.put("endTime", value);
                    case "ErrMessage" -> task.put("errorMessage", value);
                    case "Result" -> task.put("result", value);
                    default -> { }
                }
            }
        }
        return tasks;
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
