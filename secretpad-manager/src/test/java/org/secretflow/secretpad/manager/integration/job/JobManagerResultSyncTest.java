/*
 * Copyright 2023 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.manager.integration.job;

import org.secretflow.secretpad.kuscia.v1alpha1.service.impl.KusciaGrpcClientAdapter;
import org.secretflow.secretpad.manager.integration.datatable.DatatableManager;
import org.secretflow.secretpad.persistence.datasync.producer.p2p.P2pDataSyncProducerTemplate;
import org.secretflow.secretpad.persistence.entity.ProjectDatatableDO;
import org.secretflow.secretpad.persistence.entity.ProjectGraphNodeDO;
import org.secretflow.secretpad.persistence.entity.ProjectReportDO;
import org.secretflow.secretpad.persistence.entity.ProjectResultDO;
import org.secretflow.secretpad.persistence.entity.ProjectTaskDO;
import org.secretflow.secretpad.persistence.model.GraphNodeTaskStatus;
import org.secretflow.secretpad.persistence.model.ResultKind;
import org.secretflow.secretpad.persistence.repository.ProjectDatatableRepository;
import org.secretflow.secretpad.persistence.repository.ProjectFedTableRepository;
import org.secretflow.secretpad.persistence.repository.ProjectReportRepository;
import org.secretflow.secretpad.persistence.repository.ProjectResultRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.secretflow.v1alpha1.kusciaapi.Domaindata;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobManagerResultSyncTest {
    @Test
    void syncsMissingLocalFedTableEvenWhenFirstPartyIsRemote() {
        String tableId = "job-psi-table";
        String reportId = "job-psi-report";
        KusciaGrpcClientAdapter kuscia = mock(KusciaGrpcClientAdapter.class);
        DatatableManager datatables = new DatatableManager(kuscia, null);
        ReflectionTestUtils.setField(datatables, "plaformType", "AUTONOMY");
        ProjectResultRepository results = mock(ProjectResultRepository.class);
        ProjectDatatableRepository tables = mock(ProjectDatatableRepository.class);
        ProjectReportRepository reports = mock(ProjectReportRepository.class);
        JobManager jobs = new JobManager(null, datatables, results, mock(ProjectFedTableRepository.class),
                tables, null, null, reports, null, null, null);
        jobs.setPlaformType("AUTONOMY");
        jobs.setNodeId("kimi");
        ReflectionTestUtils.setField(jobs, "localNodeId", "alice");

        when(kuscia.batchQueryDomainData(any(), eq("kimi"))).thenReturn(response(
                output("kimi", "kimi", tableId, "table", ""),
                output("kimi", "kimi", reportId, "report", "original report")));
        when(kuscia.batchQueryDomainData(any(), eq("alice"))).thenReturn(response(
                output("alice", "alice", tableId, "table", ""),
                output("alice", "alice", reportId, "report", "do not replace the existing report"),
                output("kimi", "alice", "job-psi-report", "table", "foreign domain"),
                output("alice", "kimi", "job-psi-report", "table", "foreign author"),
                output("alice", "alice", "unexpected-id", "table", "not requested")));
        Set<String> original = P2pDataSyncProducerTemplate.nodeIds;
        try {
            P2pDataSyncProducerTemplate.nodeIds = new HashSet<>(Set.of("alice", "kimi"));
            jobs.syncResult(ProjectTaskDO.builder()
                    .upk(new ProjectTaskDO.UPK("project", "job", "job-psi"))
                    .status(GraphNodeTaskStatus.SUCCEED)
                    .parties(List.of("kimi", "alice"))
                    .graphNode(ProjectGraphNodeDO.builder().outputs(List.of("psi-table", "psi-report")).build())
                    .build());
        } finally {
            P2pDataSyncProducerTemplate.nodeIds = original;
        }

        var localRequest = ArgumentCaptor.forClass(Domaindata.BatchQueryDomainDataRequest.class);
        verify(kuscia).batchQueryDomainData(localRequest.capture(), eq("alice"));
        assertEquals(Set.of(tableId, reportId), localRequest.getValue().getDataList().stream()
                .map(Domaindata.QueryDomainDataRequestData::getDomaindataId).collect(Collectors.toSet()));
        assertEquals(2, localRequest.getValue().getDataCount());
        assertEquals(Set.of("alice"), localRequest.getValue().getDataList().stream()
                .map(Domaindata.QueryDomainDataRequestData::getDomainId).collect(Collectors.toSet()));
        verify(kuscia).batchQueryDomainData(any(), eq("kimi"));

        var savedResults = ArgumentCaptor.forClass(ProjectResultDO.class);
        verify(results, times(3)).save(savedResults.capture());
        assertEquals(Set.of(
                        new ProjectResultDO.UPK("project", ResultKind.FedTable, "alice", tableId),
                        new ProjectResultDO.UPK("project", ResultKind.FedTable, "kimi", tableId),
                        new ProjectResultDO.UPK("project", ResultKind.Report, "kimi", reportId)),
                savedResults.getAllValues().stream().map(ProjectResultDO::getUpk).collect(Collectors.toSet()));
        var savedTables = ArgumentCaptor.forClass(ProjectDatatableDO.class);
        verify(tables, times(2)).save(savedTables.capture());
        assertEquals(Set.of("alice", "kimi"), savedTables.getAllValues().stream()
                .map(table -> table.getUpk().getNodeId()).collect(Collectors.toSet()));
        var savedReport = ArgumentCaptor.forClass(ProjectReportDO.class);
        verify(reports).save(savedReport.capture());
        assertEquals("original report", savedReport.getValue().getContent());
    }

    private static Domaindata.DomainData output(String domain, String author, String id, String type, String content) {
        return Domaindata.DomainData.newBuilder().setDomainId(domain).setAuthor(author)
                .setDomaindataId(id).setType(type).putAttributes("dist_data", content).build();
    }

    private static Domaindata.BatchQueryDomainDataResponse response(Domaindata.DomainData... outputs) {
        return Domaindata.BatchQueryDomainDataResponse.newBuilder()
                .setData(Domaindata.DomainDataList.newBuilder().addAllDomaindataList(List.of(outputs))).build();
    }
}
