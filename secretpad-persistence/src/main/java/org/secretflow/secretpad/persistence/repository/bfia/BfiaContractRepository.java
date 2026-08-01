/*
 * Copyright 2025
 */

package org.secretflow.secretpad.persistence.repository.bfia;

import org.secretflow.secretpad.persistence.entity.bfia.BfiaContractDO;
import org.secretflow.secretpad.persistence.repository.BaseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BfiaContractRepository extends BaseRepository<BfiaContractDO, String> {

    @Query("from BfiaContractDO bds where bds.contractId=:contractId")
    BfiaContractDO findByContractId(@Param("contractId") String contractId);

    @Query("from BfiaContractDO bds where bds.nodeId=:nodeId")
    BfiaContractDO findByNodeId(@Param("nodeId") String nodeId);

    @Query("from BfiaContractDO cd order by cd.gmtCreate desc")
    Page<BfiaContractDO> findForPage(Pageable pageable);

    @Query("from BfiaContractDO cd where cd.status = 'APPROVED' and cd.expiredTime > :expiredTime")
    List<BfiaContractDO> findApproved(@Param("expiredTime") Long expiredTime);
}


