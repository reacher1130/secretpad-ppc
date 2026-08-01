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

import org.secretflow.secretpad.persistence.entity.bfia.BfiaGraphDO;
import org.secretflow.secretpad.service.model.graph.*;

import java.util.List;

/**
 * BFIA independent graph service.
 *
 * <p>Provides CRUD operations for BFIA flows backed by the dedicated
 * {@code bfia_graph} / {@code bfia_graph_node} tables, decoupled from the
 * native {@code project_graph} persistence.
 *
 * @author gaofeng
 */
public interface BfiaGraphService {
    CreateGraphVO createGraph(CreateGraphRequest request);

    void fullUpdateGraph(FullUpdateGraphRequest request);

    GraphDetailVO getGraphDetail(GetGraphRequest request);

    List<GraphMetaVO> listGraph(ListGraphRequest request);

    void deleteGraph(DeleteGraphRequest request);

    void updateGraphNode(UpdateGraphNodeRequest request);

    BfiaGraphDO getGraphDO(String projectId, String graphId);
}
