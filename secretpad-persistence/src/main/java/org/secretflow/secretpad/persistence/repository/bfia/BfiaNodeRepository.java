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

import org.secretflow.secretpad.persistence.entity.bfia.BfiaNodeDO;
import org.secretflow.secretpad.persistence.repository.BaseRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link BfiaNodeDO}, backed by the {@code bfia_node} table.
 *
 * @author gaofeng
 */
@Repository
public interface BfiaNodeRepository extends BaseRepository<BfiaNodeDO, String> {
}
