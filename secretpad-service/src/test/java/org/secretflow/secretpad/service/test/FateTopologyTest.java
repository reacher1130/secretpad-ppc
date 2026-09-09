package org.secretflow.secretpad.service.test;

import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.service.graph.converter.FateKusciaJobConverter;
import org.secretflow.secretpad.service.model.fate.FateJobRequest;
import org.secretflow.secretpad.persistence.entity.fate.FateClusterDO;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FateTopologyTest {
    private FateJobRequest request(String host) {
        FateJobRequest request = new FateJobRequest();
        request.setDag(Map.of("version", "2.0"));
        request.setConfig(Map.of("initiator", Map.of("node_id", "JG0100001100000010", "role", "guest"),
                "role", Map.of("guest", List.of("JG0100001100000010"), "host", List.of(host))));
        return request;
    }
    private org.secretflow.v1alpha1.kusciaapi.Job.CreateJobRequest convert(FateJobRequest request) {
        return new FateKusciaJobConverter().convert("fate-test", request,
                FateClusterDO.builder().flowAddress("http://host.docker.internal:19381").build(), "alice");
    }
    @Test void nativePartiesNeverBecomeKusciaDomains() {
        var job = convert(request("JG0100001100000000"));
        assertEquals("alice", job.getInitiator());
        assertEquals(1, job.getTasks(0).getPartiesCount());
        assertEquals("alice", job.getTasks(0).getParties(0).getDomainId());
        assertTrue(job.getTasks(0).getTaskInputConfig().contains("JG0100001100000000"));
    }
    @Test void rejectsPlatformDomainsAsNativeFateHosts() {
        for (String host : List.of("bob", "kimi", " Alice "))
            assertThrows(IllegalArgumentException.class, () -> convert(request(host)));
    }
    @Test void cannotScheduleTheAdapterOnBob() {
        var request = request("JG0100001100000000");request.setInitiatorNodeId("bob");
        assertThrows(IllegalArgumentException.class, () -> convert(request));
    }
}
