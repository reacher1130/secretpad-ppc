/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.service.bfia;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Reads BFIA algorithm output files produced inside Kuscia runtime namespaces.
 * <p>
 * The BFIA interconn control-plane exposes no output-content API; results are written
 * to files such as {@code /home/kuscia/var/storage/<namespace>/<name>} inside the Kuscia
 * container. This storage helper abstracts how SecretPad reaches those bytes:
 * <ul>
 *   <li>{@code local}: read directly from a host-mounted Kuscia storage path.</li>
 *   <li>{@code docker}: execute {@code docker exec <container> cat <path>} (dev/all-in-one).</li>
 * </ul>
 * Defaults are safe: if nothing is configured, read attempts return empty bytes.
 *
 * @author gaofeng
 */
@Slf4j
@Component
public class BfiaOutputStorage {

    @Value("${secretpad.bfia.output.storage-type:}")
    private String storageType;

    /**
     * Base path on the local filesystem where Kuscia storage is mounted.
     * For {@code local} mode, the requested namespace/name are resolved under this base.
     */
    @Value("${secretpad.bfia.output.base-path:}")
    private String basePath;

    /**
     * Docker container name that hosts the Kuscia storage (for {@code docker} mode).
     */
    @Value("${secretpad.bfia.output.docker-container:}")
    private String dockerContainer;

    /**
     * Read the whole file content as UTF-8 text.
     *
     * @param namespace Kuscia output namespace, e.g. {@code rkig-host-0}
     * @param name      output file name, e.g. {@code rkig-9b40e4e83f51-result}
     * @return file content, or empty string if not readable
     */
    public String readText(String namespace, String name) {
        byte[] bytes = readBytes(namespace, name);
        return bytes == null || bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Read the whole file content as raw bytes.
     */
    public byte[] readBytes(String namespace, String name) {
        if (StringUtils.isBlank(namespace) || StringUtils.isBlank(name)) {
            log.warn("BfiaOutputStorage.readBytes: namespace or name is blank");
            return new byte[0];
        }
        String type = StringUtils.defaultString(storageType).toLowerCase();
        if ("docker".equals(type)) {
            return readByDocker(namespace, name);
        }
        // default to local (direct path) if a base path is configured; otherwise try local absolute
        return readLocal(namespace, name);
    }

    private byte[] readLocal(String namespace, String name) {
        Path path;
        if (StringUtils.isNotBlank(basePath)) {
            path = Paths.get(basePath, namespace, name);
        } else {
            path = Paths.get(namespace, name);
        }
        try {
            if (!Files.exists(path)) {
                log.warn("BfiaOutputStorage local file not found: {}", path);
                return new byte[0];
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            log.warn("BfiaOutputStorage failed to read local file: {}", path, e);
            return new byte[0];
        }
    }

    private byte[] readByDocker(String namespace, String name) {
        String container = StringUtils.defaultString(dockerContainer);
        if (container.isBlank()) {
            log.warn("BfiaOutputStorage docker mode requested but docker-container is not configured");
            return new byte[0];
        }
        String remotePath = "/home/kuscia/var/storage/" + namespace + "/" + name;
        ProcessBuilder pb = new ProcessBuilder("docker", "exec", container, "cat", remotePath);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("BfiaOutputStorage docker exec timed out: container={}, path={}", container, remotePath);
                return new byte[0];
            }
            if (process.exitValue() != 0) {
                String err = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("BfiaOutputStorage docker exec failed: container={}, path={}, err={}", container, remotePath, err);
                return new byte[0];
            }
            return process.getInputStream().readAllBytes();
        } catch (IOException | InterruptedException e) {
            log.warn("BfiaOutputStorage docker exec error: container={}, path={}", container, remotePath, e);
            Thread.currentThread().interrupt();
            return new byte[0];
        }
    }
}
