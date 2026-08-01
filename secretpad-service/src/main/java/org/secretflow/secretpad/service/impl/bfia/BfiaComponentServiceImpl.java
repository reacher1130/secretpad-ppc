package org.secretflow.secretpad.service.impl.bfia;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.secretflow.secretpad.service.bfia.BfiaComponentService;
import org.secretflow.secretpad.service.model.graph.CompListVO;
import org.secretflow.secretpad.service.model.graph.ComponentSummaryDef;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BfiaComponentServiceImpl implements BfiaComponentService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<Map<String, Object>> listComponents() {
        try {
            // 1) Try aggregate from config/bfiacomponents/*.json on classpath or filesystem
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] candidates = concat(
                    safeResolve(resolver, "classpath*:config/bfiacomponents/*.json"),
                    safeResolve(resolver, "file:config/bfiacomponents/*.json")
            );

            List<Map<String, Object>> aggregated = new ArrayList<>();
            for (Resource res : candidates) {
                if (res == null || !res.exists()) {
                    continue;
                }
                List<Map<String, Object>> items = readComponentsFromResource(res);
                if (items != null && !items.isEmpty()) {
                    aggregated.addAll(items);
                }
            }
            if (!aggregated.isEmpty()) {
                return aggregated;
            }

            // 2) Fallback to bfia/components.json
            ClassPathResource fallback = new ClassPathResource("bfia/components.json");
            if (fallback.exists()) {
                List<Map<String, Object>> items = readComponentsFromResource(fallback);
                return items != null ? items : Collections.emptyList();
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
    }

    @Override
    public Map<String, CompListVO> listCompListVO() {
        Map<String, CompListVO> result = new HashMap<>();
        
        try {
            // 只读取 secretflow.json 文件
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] candidates = concat(
                    safeResolve(resolver, "classpath*:config/bfiacomponents/secretflow.json"),
                    safeResolve(resolver, "file:config/bfiacomponents/secretflow.json")
            );

            for (Resource res : candidates) {
                if (res == null || !res.exists()) {
                    continue;
                }
                
                try (InputStream in = res.getInputStream()) {
                    Map<String, Object> root = objectMapper.readValue(in, new TypeReference<Map<String, Object>>(){});
                    String name = (String) root.get("name");
                    String version = (String) root.get("version");
                    String desc = (String) root.get("desc");
                    
                    if (name != null) {
                        // 读取组件列表，参照 SecretFlow 的方式
                        List<ComponentSummaryDef> comps = new ArrayList<>();
                        Object compsObj = root.get("comps");
                        if (compsObj != null) {
                            List<Map<String, Object>> compsList = objectMapper.convertValue(compsObj, new TypeReference<List<Map<String, Object>>>(){});
                            if (compsList != null && !compsList.isEmpty()) {
                                comps = compsList.stream()
                                        .map(comp -> ComponentSummaryDef.builder()
                                                .domain((String) comp.get("domain"))
                                                .name((String) comp.get("name"))
                                                .version((String) comp.get("version"))
                                                .desc((String) comp.get("desc"))
                                                .build())
                                        .collect(Collectors.toList());
                            }
                        }
                        
                        CompListVO compListVO = CompListVO.builder()
                                .name(name)
                                .version(version != null ? version : "1.0")
                                .desc(desc != null ? desc : "BFIA Components")
                                .comps(comps)
                                .build();
                        // Key MUST be "secretflow": the connectivity component tree reads
                        // data['secretflow'] (MPC) / data['trustedflow'] (TEE). Keying by the
                        // display name (e.g. "BFIA协议") would leave the canvas tree empty.
                        result.put("secretflow", compListVO);
                    }
                }
            }
        } catch (Exception e) {
            // 如果读取失败，返回默认的BFIA组件
            result.put("bfia", CompListVO.builder()
                    .name("bfia")
                    .desc("BFIA Components")
                    .version("1.0")
                    .comps(Collections.emptyList())
                    .build());
        }
        
        return result;
    }

    private Resource[] safeResolve(PathMatchingResourcePatternResolver resolver, String pattern) {
        try {
            return resolver.getResources(pattern);
        } catch (Exception e) {
            return new Resource[0];
        }
    }

    private Resource[] concat(Resource[] a, Resource[] b) {
        Resource[] r = new Resource[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private List<Map<String, Object>> readComponentsFromResource(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(in, new TypeReference<Map<String, Object>>(){});
            Object components = root.get("components");
            if (components != null) {
                return objectMapper.convertValue(components, new TypeReference<List<Map<String, Object>>>(){});
            }
            Object comps = root.get("comps");
            if (comps != null) {
                return objectMapper.convertValue(comps, new TypeReference<List<Map<String, Object>>>(){});
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
    }
}


