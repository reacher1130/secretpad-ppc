/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.secretflow.secretpad.service.bfia;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Publishes a BFIA upload into the Kuscia local file datasource.
 *
 * <p>SecretPad's normal upload service writes to {@code <data-dir>/<node>/}.
 * The BFIA C++ operators resolve {@code system.storage} inside the Kuscia pod
 * and read {@code /home/kuscia/var/storage/<namespace>/<name>}. In the local
 * all-in-one demo the two locations are separate Docker volumes, so an
 * explicit, opt-in copy is required after a successful upload. Deployments
 * without an input storage type configured keep the helper as a no-op.</p>
 */
@Slf4j
@Component
public class BfiaInputStorage {

    @Value("${secretpad.bfia.input.storage-type:}")
    private String storageType;

    @Value("${secretpad.data.dir-path:/app/data/}")
    private String dataDir;

    @Value("${secretpad.bfia.input.base-path:}")
    private String basePath;

    @Value("${secretpad.bfia.input.docker-container:}")
    private String dockerContainer;

    /** Make one uploaded CSV visible to the local BFIA Kuscia runtime. */
    public void publish(String nodeId, String realName) {
        if (StringUtils.isBlank(realName)) {
            throw new IllegalArgumentException("uploaded BFIA file name is empty");
        }
        String type = StringUtils.defaultString(storageType).trim().toLowerCase();
        if (type.isEmpty() || "none".equals(type)) {
            return;
        }
        if (!"docker".equals(type)) {
            throw new IllegalArgumentException("unsupported BFIA input storage type: " + type);
        }
        if (StringUtils.isBlank(dockerContainer)) {
            throw new IllegalStateException("BFIA docker input storage is enabled but no container is configured");
        }
        validateSegment(nodeId, "nodeId");
        validateSegment(realName, "realName");
        Path root = Paths.get(StringUtils.defaultIfBlank(basePath, dataDir)).toAbsolutePath().normalize();
        Path source = root.resolve(nodeId).resolve(realName).normalize();
        if (!source.startsWith(root) || !Files.isRegularFile(source)) {
            throw new IllegalStateException("uploaded BFIA file is not under the configured data directory");
        }

        String remoteDir = "/home/kuscia/var/storage/data";
        String remotePath = remoteDir + "/" + realName;
        runDocker("exec", dockerContainer, "mkdir", "-p", remoteDir);
        runDocker("cp", source.toString(), dockerContainer + ":" + remotePath);
        log.info("Published BFIA input file to Kuscia storage: node={}, file={}", nodeId, realName);
    }

    private void validateSegment(String value, String field) {
        if (StringUtils.isBlank(value) || value.contains("/") || value.contains("\\")
                || value.contains("..")) {
            throw new IllegalArgumentException(field + " contains an invalid path segment");
        }
    }

    private void runDocker(String... args) {
        ArrayList<String> command = new ArrayList<>();
        command.add("docker");
        Collections.addAll(command, args);
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("docker BFIA input publish timed out");
            }
            if (process.exitValue() != 0) {
                String detail = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("docker BFIA input publish failed: " + detail.trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("docker is unavailable for BFIA input publish", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker BFIA input publish interrupted", e);
        }
    }
}
