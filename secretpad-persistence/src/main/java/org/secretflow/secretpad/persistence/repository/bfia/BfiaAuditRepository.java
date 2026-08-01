package org.secretflow.secretpad.persistence.repository.bfia;

import org.secretflow.secretpad.persistence.entity.bfia.BfiaAuditDO;
import org.secretflow.secretpad.persistence.repository.BaseRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BfiaAuditRepository extends BaseRepository<BfiaAuditDO, String> {

    @Query("from BfiaAuditDO ad where ad.status=:status")
    List<BfiaAuditDO> findByStatus(@Param("status") String status);

    @Query("from BfiaAuditDO ad where ad.contractId=:contractId")
    List<BfiaAuditDO> findByContractId(@Param("contractId") String contractId);

    @Query("from BfiaAuditDO ad where ad.projectId=:projectId")
    List<BfiaAuditDO> findByProjectId(@Param("projectId") String projectId);
}


