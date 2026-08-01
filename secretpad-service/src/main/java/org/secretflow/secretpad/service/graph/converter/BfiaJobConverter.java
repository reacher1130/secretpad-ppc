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

        for (ProjectJob.JobTask task : job.getTasks()) {
            GraphNodeInfo node = task.getNode();
            BfiaAlgo algo = resolveAlgo(node.getCodeName());
            String compName = normalizeName(node.getCodeName());

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
            comp.put("input", buildInputs(node));
            comp.put("output", buildOutputs(node));
            components.add(comp);

            // task params: per-party data references + common algo params from nodeDef
            Map<String, Object> nodeAttrs = extractNodeAttrs(node);
            Map<String, Object> commonParams = new LinkedHashMap<>();
            commonParams.put("algo", algo.getAlgo());
            commonParams.put("protocol_families", algo.getProtocolFamilies());
            // Add protocol-family default params so that users only need to set
            // host_dataset_id / guest_dataset_id and algorithm-specific params in the DAG.
            // Any user-set attr with the same key will override these defaults below.
            commonParams.putAll(defaultCommonParams(algo.getProtocolFamilies()));
            commonParams.putAll(nodeAttrs);
            taskParamsCommon.put(compName, commonParams);

            // merge per-component params under the same party index "0" so multi-component
            // graphs do not overwrite previous task_params entries.
            @SuppressWarnings("unchecked")
            Map<String, Object> hostBucket = (Map<String, Object>) taskParamsHost.computeIfAbsent("0", k -> new LinkedHashMap<String, Object>());
            hostBucket.putAll(indexed(compName, hostDataRef(node)));
            @SuppressWarnings("unchecked")
            Map<String, Object> guestBucket = (Map<String, Object>) taskParamsGuest.computeIfAbsent("0", k -> new LinkedHashMap<String, Object>());
            guestBucket.putAll(indexed(compName, guestDataRef(node)));

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

    private List<Map<String, Object>> buildOutputs(GraphNodeInfo node) {
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
     * BFIA leaf components (ecdh-psi, ss-lr, ss-pearson, ...) read their datasets directly
     * from {@code task_params.{host,guest}.<index>.{name,namespace,field_names}}, NOT from
     * the dag input array. The kuscia BFIA task controller (task.go:makeComponentInputEnvs)
     * treats a non-empty input list as inter-task dependencies and requires each key to be
     * in "{predecessorAlias}.{outputKey}" format — raw dataset port names like "alice-data"
     * are rejected with "invalid input key". Since SecretPad BFIA jobs currently run
     * single-component leaf DAGs, emit an empty input list (matching the reference
     * job-ecdh-psi.yaml). When true multi-component DAGs are needed, format keys as
     * "{upstreamTaskAlias}.{upstreamOutputKey}".
     */
    private List<Map<String, Object>> buildInputs(GraphNodeInfo node) {
        return new ArrayList<>();
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
    private Map<String, Object> defaultCommonParams(String protocolFamilies) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        if ("ss".equals(protocolFamilies)) {
            defaults.put("protocol", "semi2k");
            defaults.put("field", 64);
            defaults.put("fxp_bits", 18);
            defaults.put("trunc_mode", "probabilistic");
            defaults.put("shard_serialize_format", "raw");
            defaults.put("use_ttp", false);
            defaults.put("skip_rows", 1);
            defaults.put("start_transport", false);
        } else if ("ecc".equals(protocolFamilies)) {
            defaults.put("start_transport", false);
        }
        return defaults;
    }

    private Map<String, Object> hostDataRef(GraphNodeInfo node) {
        String datasetId = extractAttr(node, "host_dataset_id");
        if (datasetId == null && !CollectionUtils.isEmpty(node.getInputs())) {
            datasetId = node.getInputs().get(0);
        }
        return buildDataRef(datasetId, extractKeys(node));
    }

    private Map<String, Object> guestDataRef(GraphNodeInfo node) {
        String datasetId = extractAttr(node, "guest_dataset_id");
        if (datasetId == null && !CollectionUtils.isEmpty(node.getInputs()) && node.getInputs().size() > 1) {
            datasetId = node.getInputs().get(1);
        }
        return buildDataRef(datasetId, extractKeys(node));
    }

    /**
     * Extract the user-configured key columns from the nodeDef.
     * Supports both AT_STRINGS (list) and AT_STRING (comma-separated) representations.
     */
    private List<String> extractKeys(GraphNodeInfo node) {
        Object raw = extractAttrRaw(node, "keys");
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
    private Map<String, Object> buildDataRef(String datasetId, List<String> keys) {
        Map<String, Object> ref = new LinkedHashMap<>();
        if (datasetId == null) {
            return ref;
        }
        BfiaDatasetRepository repo = datasetRepositoryProvider.getIfAvailable();
        if (repo == null) {
            return ref;
        }
        BfiaDatasetDO ds = repo.findById(datasetId).orElse(null);
        if (ds == null) {
            return ref;
        }
        ref.put("name", ds.getFileName() != null ? ds.getFileName() : ds.getName());
        ref.put("namespace", ds.getNamespace() != null ? ds.getNamespace() : "data");
        // Kuscia BFIA env injector only supports scalar string values for private
        // parameters, so join key columns into a single comma-separated string.
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
            // fall back to a generic AppImage so the request is still well-formed
            log.warn("BfiaJobConverter: unknown BFIA component '{}', falling back to ic-{}", codeName, name);
            algo = new BfiaAlgo("ic-" + name, name.replace('-', '_'), name.replace('-', '_'), "ss");
        }
        return algo;
    }

    /**
     * Normalize a graph node codeName (e.g. {@code 联邦学习/ss-lr:0.0.1} or {@code ss-lr}) to the
     * lower-case dash form used as the registry key.
     */
    private String normalizeName(String codeName) {
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
