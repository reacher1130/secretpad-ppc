/*
 * Copyright 2023 Ant Group Co., Ltd.
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
package org.secretflow.secretpad.service.auth.impl;

import org.secretflow.secretpad.common.enums.ProjectStatusEnum;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.persistence.entity.ProjectDO;
import org.secretflow.secretpad.service.ProjectService;
import org.secretflow.secretpad.service.bfia.BfiaProjectService;
import org.secretflow.secretpad.service.model.bfia.BfiaProjectVO;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * project resource auth check
 *
 * @author yeuxie
 * @date 2023-10-20
 */
@Slf4j
@Component
public class DataResourceProjectAuth {

    @Autowired
    private ProjectService projectService;

    @Autowired(required = false)
    private ObjectProvider<BfiaProjectService> bfiaProjectServiceProvider;

    public boolean check(String resourceId) {
        boolean exist = projectService.checkOwnerInProject(resourceId, UserContext.getUser().getOwnerId());
        if (!exist) {
            try {
                ProjectDO project = projectService.openProject(resourceId);
                if (ProjectStatusEnum.ARCHIVED.getCode().equals(project.getStatus())) {
                    return projectService.checkNodeArchive(resourceId);
                }
            } catch (Exception e) {
                // BFIA fallback: the project may live in the BFIA datasource. If a BFIA
                // project with this id exists, treat the resource check as passed so the
                // frontend's /api/v1alpha1/project/get handler can run the BFIA fallback
                // in ProjectServiceImpl.getProject().
                BfiaProjectService bfiaService = bfiaProjectServiceProvider != null ? bfiaProjectServiceProvider.getIfAvailable() : null;
                if (bfiaService != null) {
                    try {
                        BfiaProjectVO bfiaVo = bfiaService.queryProject(null, resourceId);
                        if (bfiaVo != null) {
                            return true;
                        }
                    } catch (Exception bfiaEx) {
                        log.warn("BFIA fallback in DataResourceProjectAuth for {} failed: {}", resourceId, bfiaEx.getMessage());
                    }
                }
                throw e;
            }
        }
        return exist;
    }
}
