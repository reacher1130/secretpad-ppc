package org.secretflow.secretpad.service.impl.bfia;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.secretflow.secretpad.service.bfia.BfiaComponentService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BfiaComponentOfflineServiceImpl implements org.secretflow.secretpad.service.bfia.offline.BfiaComponentOfflineService {

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

            List<Map<String, Object>> aggregated = new java.util.ArrayList<>();
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


