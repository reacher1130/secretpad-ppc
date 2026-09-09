package org.secretflow.secretpad.web.controller.fate;

import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.service.fate.FateJobService;
import org.secretflow.secretpad.service.model.fate.FateJobRequest;
import org.secretflow.secretpad.service.model.fate.FateJobVO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FateJobControllerTest {
    @Test
    void legacyAdapterExitCannotProveNativeAlgorithmSuccess() {
        var repository = mock(org.secretflow.secretpad.persistence.repository.fate.FateJobRepository.class);
        var kuscia = mock(org.secretflow.secretpad.kuscia.v1alpha1.service.KusciaJobService.class);
        var old = org.secretflow.secretpad.persistence.entity.fate.FateJobDO.builder()
                .jobId("legacy").initiatorNodeId("alice").status("SUCCESS").jobConfig("{}").build();
        when(repository.findById("legacy")).thenReturn(java.util.Optional.of(old));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        var status = org.secretflow.v1alpha1.kusciaapi.Job.JobStatusDetail.newBuilder().setState("Succeeded").build();
        var response = org.secretflow.v1alpha1.kusciaapi.Job.QueryJobResponse.newBuilder();
        response.getDataBuilder().setStatus(status);
        when(kuscia.queryJob(any(), eq("alice"))).thenReturn(response.build());
        var service = new org.secretflow.secretpad.service.impl.fate.FateJobServiceImpl(repository,null,null,null,null,kuscia);
        assertEquals("UNVERIFIED", service.get("legacy").getStatus());
    }

    @Test
    void missingKusciaJobPreservesSubmissionError() {
        var repository = mock(org.secretflow.secretpad.persistence.repository.fate.FateJobRepository.class);
        var kuscia = mock(org.secretflow.secretpad.kuscia.v1alpha1.service.KusciaJobService.class);
        var failed = org.secretflow.secretpad.persistence.entity.fate.FateJobDO.builder()
                .jobId("failed-job").initiatorNodeId("alice").status("FAILED")
                .errMsg("Kuscia deadline exceeded").build();
        when(repository.findById("failed-job")).thenReturn(java.util.Optional.of(failed));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(kuscia.queryJob(any(), eq("alice")))
                .thenReturn(org.secretflow.v1alpha1.kusciaapi.Job.QueryJobResponse.getDefaultInstance());
        var service = new org.secretflow.secretpad.service.impl.fate.FateJobServiceImpl(
                repository, null, null, null, null, kuscia);
        assertEquals("Kuscia deadline exceeded", service.get("failed-job").getErrMsg());
    }

    @Test
    void failedSubmissionMustNotBecomeSuccessfulApiResponse() {
        FateJobService service = mock(FateJobService.class);
        FateJobRequest request = new FateJobRequest();
        FateJobVO failed = FateJobVO.builder().jobId("failed-job").status("FAILED")
                .errMsg("Kuscia deadline exceeded").build();
        when(service.submit(request)).thenReturn(failed);
        var response = new FateJobController(service).submit(request);
        assertNotEquals(0, response.getStatus().getCode());
        assertTrue(response.getStatus().getMsg().contains("deadline exceeded"));
        assertEquals("failed-job", response.getData().getJobId());
    }

    @Test
    void acceptedJobRemainsSuccessfulApiResponse() {
        FateJobService service = mock(FateJobService.class);
        FateJobRequest request = new FateJobRequest();
        when(service.submit(request)).thenReturn(FateJobVO.builder().status("RUNNING").build());
        assertEquals(0, new FateJobController(service).submit(request).getStatus().getCode());
    }
}
