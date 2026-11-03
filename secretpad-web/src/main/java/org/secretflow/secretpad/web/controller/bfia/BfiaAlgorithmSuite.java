/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.web.controller.bfia;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Component
class BfiaAlgorithmSuite {
    static final List<String> PYTHON = List.of("sgb", "he_woe");
    static final List<String> CPP = List.of("ecdh-psi", "ss-lr", "ss-xgb", "ss-pearson", "ss-vif", "ss-compare");
    static final List<String> ALGORITHMS = java.util.stream.Stream.concat(PYTHON.stream(), CPP.stream()).toList();
    private static final int MAX_REPORT_BYTES = 256 * 1024;
    private static final int MAX_LOG_BYTES = 512 * 1024;

    private final ObjectMapper json;
    private final ObjectReader strictJson;
    private final Path runner;
    private final Path root;
    private Process activeProcess;
    private String activeRunId;

    BfiaAlgorithmSuite(ObjectMapper json,
                       @Value("${PPCI_BFIA_SUITE_RUNNER:}") String runner,
                       @Value("${PPCI_BFIA_SUITE_ROOT:}") String root) {
        this.json = json;
        this.strictJson = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.runner = absolutePath(runner);
        this.root = absolutePath(root);
    }

    enum Status {
        QUEUED, RUNNING, PASSED, FAILED;

        boolean active() {
            return this == QUEUED || this == RUNNING;
        }
    }

    record State(String runId, Status status, String startedAt, String finishedAt,
                 Long pid, String processStart, String message) { }

    record Algorithm(String id, Status status, Boolean numerical, Boolean protocol,
                     JsonNode numericalDetails, JsonNode protocolDetails) { }

    record Run(String runId, Status status, String startedAt, String finishedAt,
               List<Algorithm> algorithms, String message) { }

    record Latest(boolean canStart, Run run) { }

    boolean configured() {
        return runner != null && root != null && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS);
    }

    synchronized Latest latest(boolean demo) throws IOException {
        State state = latestState();
        Run run = state == null ? null : view(state, false);
        return new Latest(canStart(run, demo), run);
    }

    synchronized Latest start(boolean demo) throws IOException {
        Latest previous = latest(demo);
        if (!previous.canStart()) {
            return previous;
        }
        Path runs = root.resolve("runs");
        if (!Files.exists(runs, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(runs);
        }
        if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("BFIA suite run directory is unavailable");
        }
        String runId = UUID.randomUUID().toString();
        Files.createDirectory(runDirectory(runId));
        State state = new State(runId, Status.QUEUED, null, null, null, null, null);
        save(state);
        writeAtomic(runs.resolve("latest-id"), runId.getBytes(StandardCharsets.US_ASCII));

        ProcessBuilder process = new ProcessBuilder(runner.toString(), runId)
                .redirectErrorStream(true)
                .redirectInput(Path.of("/dev/null").toFile())
                .redirectOutput(runDirectory(runId).resolve("runner.log").toFile());
        Map<String, String> environment = process.environment();
        environment.clear();
        environment.put("PATH", "/usr/sbin:/usr/bin:/sbin:/bin:/opt/ppci-tools/rg");
        environment.put("PPCI_BFIA_SUITE_ROOT", root.toString());
        try {
            activeProcess = process.start();
        } catch (IOException e) {
            state = finish(state, Status.FAILED, "无法启动服务器算法执行器。");
            Run run = view(state, false);
            return new Latest(canStart(run, demo), run);
        }
        activeRunId = runId;
        String startedAt = Instant.now().toString();
        state = new State(runId, Status.RUNNING, startedAt, null, activeProcess.pid(),
                activeProcess.info().startInstant().map(Instant::toString).orElse(null), null);
        save(state);
        Run run = view(state, false);
        return new Latest(canStart(run, demo), run);
    }

    synchronized Run report(String runId) throws IOException {
        return view(readState(runId), true);
    }

    synchronized String log(String runId, String algorithm, String party) throws IOException {
        readState(runId);
        if (!ALGORITHMS.contains(algorithm) || !Set.of("alice", "bob").contains(party)) {
            throw new IllegalArgumentException("无效的算法或日志参与方。");
        }
        Path path = logPath(runId, algorithm, party);
        if (!safeFile(path)) {
            throw new NoSuchElementException("本次运行暂未生成该日志。");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            int count = (int) Math.min(size, MAX_LOG_BYTES);
            channel.position(size - count);
            ByteBuffer buffer = ByteBuffer.allocate(count);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Read at most the original snapshot size even if the runner appends.
            }
            return (size > count ? "[仅显示日志末尾 512 KiB]\n" : "")
                    + new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        }
    }

    private Run view(State state, boolean includeDetails) throws IOException {
        String runId = state.runId();
        // Observe the worker exit before reading reports, so a report still being written
        // cannot be mistaken for a missing final report if the worker exits during this call.
        Boolean successfulExit = completedSuccessfully(state);
        JsonNode pythonNumerical = validation(runId, "python", "numerical", PYTHON);
        JsonNode pythonProtocol = validation(runId, "python", "protocol", PYTHON);
        JsonNode cppNumerical = validation(runId, "cpp", "numerical", CPP);
        JsonNode cppProtocol = validation(runId, "cpp", "protocol", CPP);
        boolean allPassed = ALGORITHMS.stream().allMatch(id -> {
            JsonNode numerical = PYTHON.contains(id) ? pythonNumerical : cppNumerical;
            JsonNode protocol = PYTHON.contains(id) ? pythonProtocol : cppProtocol;
            return Boolean.TRUE.equals(passed(numerical.path(id))) && Boolean.TRUE.equals(passed(protocol.path(id)));
        });
        if (successfulExit != null) {
            state = successfulExit && allPassed ? finish(state, Status.PASSED, null)
                    : finish(state, Status.FAILED, successfulExit ? "执行结束，但数值或协议校验未全部通过。"
                            : "服务器执行器未成功完成。可查看已生成的报告和日志。");
        }
        Status runStatus = state.status() == Status.PASSED && !allPassed ? Status.FAILED : state.status();
        String message = runStatus != state.status() ? "本次运行的校验报告不完整。" : state.message();
        List<Algorithm> algorithms = new ArrayList<>(ALGORITHMS.size());
        for (String id : ALGORITHMS) {
            JsonNode numerical = (PYTHON.contains(id) ? pythonNumerical : cppNumerical).get(id);
            JsonNode protocol = (PYTHON.contains(id) ? pythonProtocol : cppProtocol).get(id);
            Boolean n = passed(numerical);
            Boolean p = passed(protocol);
            Status status;
            if (Boolean.FALSE.equals(n) || Boolean.FALSE.equals(p)) {
                status = Status.FAILED;
            } else if (Boolean.TRUE.equals(n) && Boolean.TRUE.equals(p)) {
                status = Status.PASSED;
            } else if (!runStatus.active()) {
                status = Status.FAILED;
            } else if (n != null || p != null || safeFile(logPath(runId, id, "alice")) || safeFile(logPath(runId, id, "bob"))) {
                status = Status.RUNNING;
            } else {
                status = Status.QUEUED;
            }
            algorithms.add(new Algorithm(id, status, n, p, includeDetails ? numerical : null, includeDetails ? protocol : null));
        }
        return new Run(runId, runStatus, state.startedAt(), state.finishedAt(), algorithms, message);
    }

    private Boolean completedSuccessfully(State state) {
        if (!state.status().active()) {
            return null;
        }
        boolean sameProcess = state.runId().equals(activeRunId);
        if (sameProcess ? activeProcess.isAlive() : persistedProcessAlive(state)) {
            return null;
        }
        Integer code = exitCode(state.runId());
        boolean success = code != null && code == 0 && (!sameProcess || activeProcess.exitValue() == 0);
        if (sameProcess) {
            activeProcess = null;
            activeRunId = null;
        }
        return success;
    }

    private static boolean persistedProcessAlive(State state) {
        if (state.pid() == null || state.processStart() == null) {
            return false;
        }
        return ProcessHandle.of(state.pid()).filter(ProcessHandle::isAlive)
                .flatMap(handle -> handle.info().startInstant())
                .map(start -> start.toString().equals(state.processStart())).orElse(false);
    }

    private Integer exitCode(String runId) {
        Path path = runDirectory(runId).resolve("exit-code");
        try {
            String code = new String(readSmall(path, 16), StandardCharsets.US_ASCII).trim();
            return code.matches("0|[1-9][0-9]{0,2}") && Integer.parseInt(code) <= 255 ? Integer.valueOf(code) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private State finish(State state, Status status, String message) throws IOException {
        Path marker = runDirectory(state.runId()).resolve("exit-code");
        String finishedAt = safeFile(marker) ? Files.getLastModifiedTime(marker, LinkOption.NOFOLLOW_LINKS).toInstant().toString()
                : Instant.now().toString();
        State finished = new State(state.runId(), status, state.startedAt(), finishedAt, null, null, message);
        save(finished);
        return finished;
    }

    private JsonNode validation(String runId, String language, String kind, List<String> expected) {
        Path path = resultDirectory(runId, language).resolve(kind + "-validation.json");
        try {
            JsonNode report = strictJson.readTree(readSmall(path, MAX_REPORT_BYTES));
            if (report != null && report.isObject()) {
                Set<String> names = new HashSet<>();
                report.fieldNames().forEachRemaining(names::add);
                if (names.equals(Set.copyOf(expected))) {
                    return report;
                }
            }
        } catch (IOException ignored) {
            // During a run a validation file can be missing or still being written.
        }
        return json.createObjectNode();
    }

    private static Boolean passed(JsonNode row) {
        JsonNode pass = row == null || !row.isObject() ? null : row.get("pass");
        return pass != null && pass.isBoolean() ? pass.booleanValue() : null;
    }

    private State latestState() throws IOException {
        if (!configured() || !safeFile(root.resolve("runs/latest-id"))) {
            return null;
        }
        return readState(new String(readSmall(root.resolve("runs/latest-id"), 36), StandardCharsets.US_ASCII));
    }

    private State readState(String runId) throws IOException {
        if (!validRunId(runId)) {
            throw new IllegalArgumentException("无效的算法运行编号。");
        }
        Path path = runDirectory(runId).resolve("state.json");
        if (!safeFile(path)) {
            throw new NoSuchElementException("未找到该次算法运行。");
        }
        State state = json.readValue(readSmall(path, 8192), State.class);
        if (state == null || !runId.equals(state.runId()) || state.status() == null) {
            throw new IOException("Invalid BFIA suite state");
        }
        return state;
    }

    private void save(State state) throws IOException {
        writeAtomic(runDirectory(state.runId()).resolve("state.json"), json.writeValueAsBytes(state));
    }

    private static void writeAtomic(Path path, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "suite-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] readSmall(Path path, int max) throws IOException {
        if (!safeFile(path)) {
            throw new IOException("Missing BFIA suite artifact");
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(max + 1);
            if (bytes.length > max) {
                throw new IOException("BFIA suite artifact exceeds read limit");
            }
            return bytes;
        }
    }

    private boolean safeFile(Path path) {
        if (!path.startsWith(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path current = root;
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return false;
            }
        }
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private Path runDirectory(String runId) {
        return root.resolve("runs").resolve(runId);
    }

    private Path resultDirectory(String runId, String language) {
        return runDirectory(runId).resolve(language).resolve(runId);
    }

    private Path logPath(String runId, String algorithm, String party) {
        boolean python = PYTHON.contains(algorithm);
        return resultDirectory(runId, python ? "python" : "cpp").resolve(algorithm)
                .resolve(party + (python ? ".stdout.log" : ".log"));
    }

    private boolean runnerReady() {
        return configured() && Files.isWritable(root) && Files.isRegularFile(runner, LinkOption.NOFOLLOW_LINKS)
                && Files.isExecutable(runner);
    }

    private boolean canStart(Run run, boolean demo) {
        return runnerReady() && (run == null || (!run.status().active()
                && (!demo || !Instant.now().isBefore(Instant.parse(run.finishedAt()).plusSeconds(60)))));
    }

    private static boolean validRunId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static Path absolutePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path path = Path.of(value).normalize();
        return path.isAbsolute() && path.getParent() != null ? path : null;
    }
}
