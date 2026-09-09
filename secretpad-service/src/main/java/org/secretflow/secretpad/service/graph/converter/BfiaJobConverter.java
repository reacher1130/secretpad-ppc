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

package org.secretflow.secretpad.service.graph.converter;

import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.persistence.entity.bfia.BfiaDatasetDO;
import org.secretflow.secretpad.persistence.repository.bfia.BfiaDatasetRepository;
import org.secretflow.secretpad.service.bfia.BfiaInterconnClient;
import org.secretflow.secretpad.service.model.graph.GraphNodeInfo;
import org.secretflow.secretpad.service.model.graph.ProjectJob;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts a standard SecretPad {@link ProjectJob} (built from a project graph) into the
 * BFIA interconn control-plane request body for
 * {@code POST http://{kuscia-host}:8084/v1/interconn/schedule/job/create}.
 * <p>
 * The produced map is serialized as the documented BFIA JSON
 * (see kuscia proto/api/v1/interconn/{job,common,task}.proto and scripts/user/bfia/job-*.yaml):
 * <pre>
 * { job_id, flow_id, dag:{version, components:[...]}, config:{role, initiator, job_params, task_params, version} }
 * </pre>
 * Per-algorithm parameters in {@code config.task_params.common} are driven by each graph node's
 * {@code nodeDef} so that the canvas/component definition controls the algorithm details, while this
 * converter only assembles the BFIA envelope and resolves the AppImage / module name / parties.
 *
 * @author gaofeng
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BfiaJobConverter {

    private static final String DAG_VERSION = "1.0.0";

    /**
     * Optional: only present when the BFIA datasource is configured. Used to resolve dataset
     * metadata (name/namespace/field_names/has_label) referenced by graph nodes.
     * Wrapped in ObjectProvider so this always-on bean does not hard-depend on the BFIA datasource.
     */
    private final ObjectProvider<BfiaDatasetRepository> datasetRepositoryProvider;
    private final BfiaInterconnClient bfiaInterconnClient;

    /**
     * Component provider (InterOp 控制层 §3.2 / §6.7 dag.components[].provider, required).
     * Identifies the algorithm provider; the kuscia interconn Component proto field {@code provider}.
     */
    private static final String COMPONENT_PROVIDER = "secretflow";

    /**
     * Registry mapping a BFIA algorithm (component name, normalized to lower-case with dashes)
     * to its kuscia AppImage name, BFIA module_name and algo identifier.
     */
    private static final Map<String, BfiaAlgo> ALGO_REGISTRY = new HashMap<>();

    static {
        ALGO_REGISTRY.put("ecdh-psi", new BfiaAlgo("ic-ecdh-psi", "ecdh_psi", "ecdh_psi", "ecc"));
        ALGO_REGISTRY.put("psi", new BfiaAlgo("ic-ecdh-psi", "ecdh_psi", "ecdh_psi", "ecc"));
        ALGO_REGISTRY.put("ss-lr", new BfiaAlgo("ic-ss-lr", "ss_lr", "ss_lr", "ss"));
        ALGO_REGISTRY.put("ss-sgd-train", new BfiaAlgo("ic-ss-lr", "ss_lr", "ss_lr", "ss"));
        ALGO_REGISTRY.put("ss-xgb", new BfiaAlgo("ic-ss-xgb", "ss_xgb", "ss_xgb", "ss"));
        ALGO_REGISTRY.put("ss-xgb-train", new BfiaAlgo("ic-ss-xgb", "ss_xgb", "ss_xgb", "ss"));
        ALGO_REGISTRY.put("secureboot", new BfiaAlgo("ic-ss-xgb", "ss_xgb", "ss_xgb", "ss"));
        ALGO_REGISTRY.put("sgb", new BfiaAlgo("ic-ss-xgb", "ss_xgb", "ss_xgb", "ss"));
        ALGO_REGISTRY.put("ss-vif", new BfiaAlgo("ic-ss-vif", "ss_vif", "ss_vif", "ss"));
        ALGO_REGISTRY.put("ss-pearson", new BfiaAlgo("ic-ss-pearson", "ss_pearsonr", "ss_pearsonr", "ss"));
        ALGO_REGISTRY.put("ss-pearsonr", new BfiaAlgo("ic-ss-pearson", "ss_pearsonr", "ss_pearsonr", "ss"));
        ALGO_REGISTRY.put("ss-compare", new BfiaAlgo("ic-ss-compare", "ss_compare", "ss_compare", "ss"));
    }

    /**
     * Build the BFIA job/create request body from a project job.
     *
     * @param job          the assembled project job (graph + selected tasks + parties)
     * @param initiatorId  the domain id of the initiator (the requesting node)
     * @return a map ready to be serialized as the BFIA job/create JSON
     */
    public Map<String, Object> convert(ProjectJob job, String initiatorId) {
        if (CollectionUtils.isEmpty(job.getTasks())) {
            throw new IllegalArgumentException("BFIA job has no tasks to submit");
        }

        List<Map<String, Object>> components = new ArrayList<>();
        Map<String, Object> taskParamsHost = new LinkedHashMap<>();
        Map<String, Object> taskParamsGuest = new LinkedHashMap<>();
        Map<String, Object> taskParamsCommon = new LinkedHashMap<>();
        Map<String, Object> jobParamsHost = new LinkedHashMap<>();
        Map<String, Object> jobParamsGuest = new LinkedHashMap<>();

        List<String> hostParties = new ArrayList<>();
        List<String> guestParties = new ArrayList<>();

        Map<String, String> aliasesByNodeId = new LinkedHashMap<>();
        Map<String, OutputReference> outputReferences = new HashMap<>();
        Map<String, String> aliasOwners = new HashMap<>();
        for (ProjectJob.JobTask task : job.getTasks()) {
            GraphNodeInfo node = task.getNode();
            if (node == null) {
                throw new IllegalArgumentException("BFIA task has no graph node: " + task.getTaskId());
            }
            String alias = componentAlias(node.getCodeName(), node.getGraphNodeId());
            String previousOwner = aliasOwners.put(alias, node.getGraphNodeId());
            if (previousOwner != null) {
                throw new IllegalArgumentException(
                        "BFIA component alias collision between " + previousOwner + " and "
                                + node.getGraphNodeId());
            }
            aliasesByNodeId.put(node.getGraphNodeId(), alias);
            for (String output : node.getOutputs() == null ? List.<String>of() : node.getOutputs()) {
                outputReferences.put(output, new OutputReference(alias, "result"));
            }
        }

        for (ProjectJob.JobTask task : job.getTasks()) {
            GraphNodeInfo node = task.getNode();
            BfiaAlgo algo = resolveAlgo(node.getCodeName());
            String compName = aliasesByNodeId.get(node.getGraphNodeId());

            // resolve parties: first party -> host, second -> guest (2-party BFIA algorithms)
            List<String> parties = task.getParties() == null ? new ArrayList<>() : task.getParties();
            String host = parties.isEmpty() ? initiatorId : parties.get(0);
            String guest = parties.size() > 1 ? parties.get(1) : host;
            if (!hostParties.contains(host)) {
                hostParties.add(host);
            }
            if (!guestParties.contains(guest)) {
                guestParties.add(guest);
            }

            // dag component
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("code", algo.getAppImage());
            comp.put("name", compName);
            comp.put("module_name", algo.getModuleName());
            comp.put("componentName", algo.getAppImage());
            comp.put("provider", COMPONENT_PROVIDER);
            comp.put("version", DAG_VERSION);
            comp.put("input", buildInputs(node, outputReferences));
            comp.put("output", buildOutputs());
            components.add(comp);

            // task params: per-party data references + common algo params from nodeDef
            Map<String, Object> nodeAttrs = extractNodeAttrs(node);
            Map<String, Object> commonParams = new LinkedHashMap<>();
            commonParams.put("algo", algo.getAlgo());
            commonParams.put("protocol_families", algo.getProtocolFamilies());
            // Add protocol-family default params so that users only need to set
            // host_dataset_id / guest_dataset_id and algorithm-specific params in the DAG.
            // Any user-set attr with the same key will override these defaults below.
            commonParams.putAll(defaultCommonParams(algo));
            Object configuredLabel = extractAttrRaw(node, "input/host_dataset/label");
            if (configuredLabel instanceof List<?> labels && !labels.isEmpty()) {
                commonParams.put("label_name", String.valueOf(labels.get(0)));
            } else if (configuredLabel != null) {
                commonParams.put("label_name", String.valueOf(configuredLabel));
            }
            nodeAttrs.entrySet().stream()
                    .filter(entry -> !isCanvasOnlyAttr(entry.getKey()))
                    .forEach(entry -> commonParams.put(entry.getKey(), entry.getValue()));
            taskParamsCommon.put(compName, commonParams);

            // merge per-component params under the same party index "0" so multi-component
            // graphs do not overwrite previous task_params entries.
            Map<String, Object> hostPrivateParams = hostDataRef(node);
            Map<String, Object> guestPrivateParams = guestDataRef(node, host, guest);
            if ("ss".equals(algo.getProtocolFamilies())) {
                // SS runtimes still need the label-owner flag when their dataset comes from
                // an upstream task and therefore has no leaf dataset metadata.
                hostPrivateParams.putIfAbsent("has_label", true);
                guestPrivateParams.putIfAbsent("has_label", false);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> hostBucket = (Map<String, Object>) taskParamsHost.computeIfAbsent("0", k -> new LinkedHashMap<String, Object>());
            hostBucket.putAll(indexed(compName, hostPrivateParams));
            @SuppressWarnings("unchecked")
            Map<String, Object> guestBucket = (Map<String, Object>) taskParamsGuest.computeIfAbsent("0", k -> new LinkedHashMap<String, Object>());
            guestBucket.putAll(indexed(compName, guestPrivateParams));

            jobParamsHost.put("0", resources());
            jobParamsGuest.put("0", resources());
        }

        Map<String, Object> role = new LinkedHashMap<>();
        role.put("host", hostParties);
        role.put("guest", guestParties);
        role.put("arbiter", new ArrayList<>());

        Map<String, Object> initiator = new LinkedHashMap<>();
        initiator.put("role", "host");
        initiator.put("node_id", initiatorId);

        Map<String, Object> jobParams = new LinkedHashMap<>();
        jobParams.put("common", Map.of("sync_type", "poll"));
        jobParams.put("host", jobParamsHost);
        jobParams.put("guest", jobParamsGuest);
        jobParams.put("arbiter", new LinkedHashMap<>());

        Map<String, Object> taskParams = new LinkedHashMap<>();
        taskParams.put("host", taskParamsHost);
        taskParams.put("guest", taskParamsGuest);
        taskParams.put("common", taskParamsCommon);
        taskParams.put("arbiter", new LinkedHashMap<>());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("role", role);
        config.put("initiator", initiator);
        config.put("job_params", jobParams);
        config.put("task_params", taskParams);
        config.put("version", DAG_VERSION);

        Map<String, Object> dag = new LinkedHashMap<>();
        dag.put("version", DAG_VERSION);
        dag.put("components", components);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", job.getJobId());
        body.put("flow_id", job.getGraphId());
        body.put("dag", dag);
        body.put("config", config);
        return body;
    }

    private List<Map<String, Object>> buildOutputs() {
        List<Map<String, Object>> outputs = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "dataset");
        out.put("key", "result");
        outputs.add(out);
        return outputs;
    }

    /**
     * Build the {@code dag.components[].input} list.
     * <p>
     * Leaf components read datasets from party-private task params. Non-leaf components use
     * the BFIA dependency syntax {@code predecessorAlias.outputKey}; Kuscia resolves that to
     * the predecessor task's per-party storage object.
     */
    private List<Map<String, Object>> buildInputs(
            GraphNodeInfo node, Map<String, OutputReference> outputReferences) {
        List<Map<String, Object>> inputs = new ArrayList<>();
        if (node.getInputs() == null) {
            return inputs;
        }
        for (String inputPort : node.getInputs()) {
            OutputReference reference = outputReferences.get(inputPort);
            if (reference == null) {
                throw new IllegalArgumentException(
                        "BFIA component input does not reference an executable upstream output: "
                                + inputPort);
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("type", "dataset");
            input.put("key", reference.alias() + "." + reference.outputKey());
            inputs.add(input);
        }
        return inputs;
    }

    private Map<String, Object> indexed(String compName, Map<String, Object> params) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put(compName, params);
        return wrapper;
    }

    /**
     * Return protocol-family default common params that the BFIA algorithm runtime expects
     * but the user should not have to set manually in the DAG. These are applied before
     * user-set node attrs, so any user-set value with the same key takes precedence.
     *
     * <p>Defaults are derived from the reference KusciaJob YAMLs in
     * {@code kuscia/scripts/user/bfia/job-*.yaml}.
     */
    private Map<String, Object> defaultCommonParams(BfiaAlgo algo) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        String protocolFamilies = algo.getProtocolFamilies();
        if ("ss".equals(protocolFamilies)) {
            defaults.put("protocol", "semi2k");
            defaults.put("field", 64);
            defaults.put("fxp_bits", 18);
            defaults.put("trunc_mode", "probabilistic");
            defaults.put("shard_serialize_format", "raw");
            defaults.put("use_ttp", false);
            defaults.put("skip_rows", 1);
            defaults.put("start_transport", false);
            defaults.put("label_owner", "host.0");
            if ("ss_lr".equals(algo.getAlgo())) {
                defaults.put("optimizer", "sgd");
                defaults.put("learning_rate", 0.0001);
                defaults.put("num_epoch", 10);
                defaults.put("batch_size", 32);
                defaults.put("last_batch_policy", "discard");
                defaults.put("l0_norm", 0);
                defaults.put("l1_norm", 0);
                defaults.put("l2_norm", 0.5);
                defaults.put("sigmoid_mode", "minimax_1");
            } else if ("ss_xgb".equals(algo.getAlgo())) {
                defaults.put("label_name", "y");
                defaults.put("num_boost_round", 1);
                defaults.put("max_depth", 2);
                defaults.put("learning_rate", 0.1);
                defaults.put("reg_lambda", 1.0);
                defaults.put("gamma", 0.0);
                defaults.put("base_score", 0.5);
                defaults.put("seed", 42);
                defaults.put("objective", 1);
                defaults.put("num_bins", 8);
                defaults.put("colsample_by_tree", 0.3);
                defaults.put("ssxgb_sync_fixed_splits", true);
                defaults.put("ssxgb_num_bins", 8);
            }
        } else if ("ecc".equals(protocolFamilies)) {
            defaults.put("start_transport", false);
            defaults.put("curve_type", "curve25519");
            defaults.put("hash_type", "sha_256");
            defaults.put("hash2curve_strategy", "direct_hash_as_point_x");
            defaults.put("point_octet_format", "uncompressed");
            defaults.put("bit_length_after_truncated", -1);
            defaults.put("result_to_rank", -1);
        }
        return defaults;
    }

    private Map<String, Object> hostDataRef(GraphNodeInfo node) {
        String datasetId = extractAttr(node, "host_dataset_id");
        return buildDataRef(datasetId,
                extractKeys(node, "input/receiver_input/key", "input/host_dataset/feature_selects"),
                null);
    }

    private Map<String, Object> guestDataRef(GraphNodeInfo node, String sourceNodeId, String targetNodeId) {
        String datasetId = extractAttr(node, "guest_dataset_id");
        return buildDataRef(datasetId,
                extractKeys(node, "input/sender_input/key", "input/guest_dataset/feature_selects"),
                new RemoteDatasetTarget(sourceNodeId, targetNodeId));
    }

    /**
     * Extract the user-configured key columns from the nodeDef.
     * Supports both AT_STRINGS (list) and AT_STRING (comma-separated) representations.
     */
    private List<String> extractKeys(GraphNodeInfo node, String... attrNames) {
        Object raw = extractAttrRaw(node, "keys");
        if (raw == null) {
            for (String attrName : attrNames) {
                raw = extractAttrRaw(node, attrName);
                if (raw != null) {
                    break;
                }
            }
        }
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        String s = String.valueOf(raw);
        if (s.isBlank()) {
            return null;
        }
        return Arrays.stream(s.split(",")).map(String::trim).filter(t -> !t.isBlank()).toList();
    }

    private Object extractAttrRaw(GraphNodeInfo node, String attrName) {
        if (node == null || node.getNodeDef() == null) {
            return null;
        }
        try {
            Map<String, Object> def = JsonUtils.toJavaMap(JsonUtils.toJSONString(node.getNodeDef()), Object.class);
            Object attrPaths = def.get("attrPaths");
            Object attrs = def.get("attrs");
            if (attrPaths instanceof List<?> paths && attrs instanceof List<?> values && paths.size() == values.size()) {
                for (int i = 0; i < paths.size(); i++) {
                    if (attrName.equals(String.valueOf(paths.get(i)))) {
                        return unwrapAttr(values.get(i));
                    }
                }
            }
            Object v = def.get(attrName);
            if (v != null) {
                return unwrapAttr(v);
            }
        } catch (Exception e) {
            log.warn("BfiaJobConverter: failed to extract raw attr '{}' from nodeDef, codeName={}", attrName, node.getCodeName(), e);
        }
        return null;
    }

    /**
     * Look up a single string attribute from the node's nodeDef by its attrPath key.
     * The nodeDef is stored as a free-form Object (see {@link GraphNodeInfo}); it is
     * serialized back to JSON and inspected for parallel {@code attrPaths} / {@code attrs}
     * lists, mirroring {@link #extractNodeAttrs(GraphNodeInfo)}. As a fallback, simple
     * key-value maps (e.g. curl submissions) are also supported.
     */
    private String extractAttr(GraphNodeInfo node, String attrName) {
        if (node == null || node.getNodeDef() == null) {
            return null;
        }
        try {
            Map<String, Object> def = JsonUtils.toJavaMap(JsonUtils.toJSONString(node.getNodeDef()), Object.class);
            Object attrPaths = def.get("attrPaths");
            Object attrs = def.get("attrs");
            if (attrPaths instanceof List<?> paths && attrs instanceof List<?> values && paths.size() == values.size()) {
                for (int i = 0; i < paths.size(); i++) {
                    if (attrName.equals(String.valueOf(paths.get(i)))) {
                        Object v = unwrapAttr(values.get(i));
                        return v == null ? null : String.valueOf(v);
                    }
                }
            }
            // Fallback: direct key-value map
            Object v = def.get(attrName);
            if (v != null) {
                return String.valueOf(unwrapAttr(v));
            }
        } catch (Exception e) {
            log.warn("BfiaJobConverter: failed to extract attr '{}' from nodeDef, codeName={}", attrName, node.getCodeName(), e);
        }
        return null;
    }

    /**
     * Resolve a dataset id to the BFIA {@code {name, namespace, field_names, has_label}} data
     * reference structure expected by kuscia task_params. Returns an empty map when the
     * dataset cannot be resolved so the rest of the request stays well-formed.
     */
    private Map<String, Object> buildDataRef(
            String datasetId, List<String> keys, RemoteDatasetTarget remoteTarget) {
        Map<String, Object> ref = new LinkedHashMap<>();
        if (datasetId == null) {
            return ref;
        }
        BfiaDatasetRepository repo = datasetRepositoryProvider == null
                ? null : datasetRepositoryProvider.getIfAvailable();
        BfiaDatasetDO ds = repo == null ? null : repo.findById(datasetId).orElse(null);
        if (ds != null) {
            ref.put("name", ds.getFileName() != null ? ds.getFileName() : ds.getName());
            ref.put("namespace", ds.getNamespace() != null ? ds.getNamespace() : "data");
            if (keys != null && !keys.isEmpty()) {
                ref.put("field_names", String.join(",", keys));
            } else if (ds.getFieldNames() != null && !ds.getFieldNames().isEmpty()) {
                ref.put("field_names", String.join(",", ds.getFieldNames()));
            }
            if (ds.getHasLabel() != null) {
                ref.put("has_label", ds.getHasLabel());
            }
            return ref;
        }

        Map<String, Object> remote = remoteTarget == null || bfiaInterconnClient == null
                ? null
                : bfiaInterconnClient.queryRemoteDataset(
                        remoteTarget.sourceNodeId(), remoteTarget.targetNodeId(), datasetId);
        if (remote == null) {
            throw new IllegalArgumentException("Unable to resolve BFIA dataset metadata: " + datasetId);
        }
        Object fileName = remote.get("fileName");
        Object name = fileName != null ? fileName : remote.get("name");
        if (name == null) {
            throw new IllegalArgumentException("BFIA dataset has no file name: " + datasetId);
        }
        ref.put("name", String.valueOf(name));
        Object namespace = remote.get("namespace");
        ref.put("namespace", namespace == null ? "data" : String.valueOf(namespace));
        if (keys != null && !keys.isEmpty()) {
            ref.put("field_names", String.join(",", keys));
        } else if (remote.get("fieldNames") != null) {
            ref.put("field_names", String.valueOf(remote.get("fieldNames")));
        }
        if (remote.get("hasLabel") != null) {
            ref.put("has_label", remote.get("hasLabel"));
        }
        return ref;
    }

    private Map<String, Object> resources() {
        Map<String, Object> res = new LinkedHashMap<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cpu", -1);
        r.put("memory", -1);
        r.put("disk", -1);
        res.put("resources", r);
        return res;
    }

    /**
     * Best-effort flatten of the node's nodeDef into a flat parameter map.
     * The nodeDef carries the algorithm parameters configured on the canvas.
     * Supports both the standard SecretPad {@code attrPaths/attrs} format and
     * simple key-value maps submitted directly via curl / frontend forms.
     */
    private Map<String, Object> extractNodeAttrs(GraphNodeInfo node) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object nodeDef = node.getNodeDef();
        if (nodeDef == null) {
            return result;
        }
        try {
            Map<String, Object> def = JsonUtils.toJavaMap(JsonUtils.toJSONString(nodeDef), Object.class);
            Object attrs = def.get("attrs");
            Object attrPaths = def.get("attrPaths");
            if (attrPaths instanceof List<?> paths && attrs instanceof List<?> values && paths.size() == values.size()) {
                for (int i = 0; i < paths.size(); i++) {
                    result.put(String.valueOf(paths.get(i)), unwrapAttr(values.get(i)));
                }
            } else {
                // Fallback: direct key-value map
                for (Map.Entry<String, Object> entry : def.entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                    }
                    result.put(entry.getKey(), unwrapAttr(entry.getValue()));
                }
            }
        } catch (Exception e) {
            log.warn("BfiaJobConverter: failed to extract nodeDef attrs, codeName={}", node.getCodeName(), e);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object unwrapAttr(Object attr) {
        if (attr instanceof Map<?, ?> m) {
            for (String key : new String[]{"s", "i64", "f", "b", "ss", "i64s", "fs", "bs"}) {
                if (((Map<String, Object>) m).get(key) != null) {
                    return ((Map<String, Object>) m).get(key);
                }
            }
        }
        return attr;
    }

    private BfiaAlgo resolveAlgo(String codeName) {
        String name = normalizeName(codeName);
        BfiaAlgo algo = ALGO_REGISTRY.get(name);
        if (algo == null) {
            throw new IllegalArgumentException("Unsupported BFIA component: " + codeName);
        }
        return algo;
    }

    /**
     * Normalize a graph node codeName (e.g. {@code 联邦学习/ss-lr:0.0.1} or {@code ss-lr}) to the
     * lower-case dash form used as the registry key.
     */
    public static String normalizeName(String codeName) {
        if (codeName == null) {
            return "";
        }
        String name = codeName;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(0, colon);
        }
        return name.trim().toLowerCase().replace('_', '-');
    }

    /**
     * Stable, unique BFIA component alias used by both job submission and result mapping.
     */
    public static String componentAlias(String codeName, String graphNodeId) {
        String base = normalizeName(codeName).replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-").replaceAll("^-|-$", "");
        String id = graphNodeId == null ? "node" : graphNodeId.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (base.isEmpty()) {
            base = "component";
        }
        if (id.isEmpty()) {
            id = "node";
        }
        String alias = base + "-" + id;
        if (alias.length() <= 63) {
            return alias;
        }
        String hash = Integer.toUnsignedString(Objects.hashCode(graphNodeId), 36);
        int prefixLength = Math.max(1, 63 - hash.length() - 1);
        return alias.substring(0, prefixLength).replaceAll("-$", "") + "-" + hash;
    }

    private boolean isCanvasOnlyAttr(String name) {
        return "host_dataset_id".equals(name)
                || "guest_dataset_id".equals(name)
                || "datatable_selected".equals(name)
                || name.startsWith("input/");
    }

    private record OutputReference(String alias, String outputKey) {
    }

    private record RemoteDatasetTarget(String sourceNodeId, String targetNodeId) {
    }

    @Getter
    private static final class BfiaAlgo {
        private final String appImage;
        private final String moduleName;
        private final String algo;
        private final String protocolFamilies;

        BfiaAlgo(String appImage, String moduleName, String algo, String protocolFamilies) {
            this.appImage = appImage;
            this.moduleName = moduleName;
            this.algo = algo;
            this.protocolFamilies = protocolFamilies;
        }
    }
}
