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

package org.secretflow.secretpad.web.controller.bfia;

import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.service.DatasourceService;
import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.model.datasource.DatasourceListRequest;
import org.secretflow.secretpad.service.model.datasource.DatasourceListVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * PAD-facing BFIA datasource controller (north-south).
 *
 * <p>Delegates to the native {@link DatasourceService} so the BFIA "新增数据" dialog
 * shows real Kuscia domain data sources instead of an empty list.
 *
 * @author gaofeng
 */
@Slf4j
@RestController
@RequestMapping("/api/v1bfia/dataSource")
@RequiredArgsConstructor
public class BfiaDataSourceController {

    private final DatasourceService datasourceService;

    /**
     * 数据源列表. Delegates to native DatasourceService to query Kuscia DomainDataSource.
     * Body: {nodeId, types, page, size, status, name}.
     */
    @PostMapping("list")
    public SecretPadResponse<DatasourceListVO> list(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Collections.emptyMap();
        }
        String ownerId = str(body, "nodeId");
        if (ownerId == null || ownerId.isBlank()) {
            ownerId = UserContext.getUser().getOwnerId();
        }
        DatasourceListRequest request = new DatasourceListRequest();
        request.setOwnerId(ownerId);
        request.setName(str(body, "name"));
        request.setStatus(str(body, "status"));
        Object typesVal = body.get("types");
        if (typesVal instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> types = (List<String>) typesVal;
            request.setTypes(types);
        }
        request.setPage(intVal(body, "page", 1));
        request.setSize(intVal(body, "size", 10));
        try {
            DatasourceListVO vo = datasourceService.listDatasource(request);
            return SecretPadResponse.success(vo);
        } catch (Exception e) {
            log.error("BFIA datasource list failed: ownerId={}, err={}", ownerId, e.getMessage());
            return SecretPadResponse.success(DatasourceListVO.newInstance(Collections.emptyList(), 1, 10, 0L));
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intVal(Map<String, Object> body, String key, int def) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
