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

package org.secretflow.secretpad.persistence.repository.bfia;

import org.secretflow.secretpad.persistence.entity.bfia.BfiaJobDO;
import org.secretflow.secretpad.persistence.repository.BaseRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link BfiaJobDO}, backed by the {@code bfia_job} table.
 *
 * @author gaofeng
 */
@Repository
public interface BfiaJobRepository extends BaseRepository<BfiaJobDO, String> {
    // Project only IDs: historical DAG/config JSON need not be loaded every five seconds.
    @Query("select j.jobId from BfiaJobDO j where upper(coalesce(j.status, '')) not in ('SUCCESS', 'FAILED')")
    List<String> findUnfinishedJobIds();

    @Query("from BfiaJobDO j where j.projectId = :projectId order by j.gmtCreate desc")
    List<BfiaJobDO> findByProjectId(@Param("projectId") String projectId);
}
