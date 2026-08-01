package org.secretflow.secretpad.persistence.repository.bfia;

import org.secretflow.secretpad.persistence.entity.bfia.BfiaProjectDO;
import org.secretflow.secretpad.persistence.repository.BaseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BfiaProjectRepository extends BaseRepository<BfiaProjectDO, String> {

    @Query("from BfiaProjectDO pj where (:contractId is null or pj.contractId = :contractId)")
    Page<BfiaProjectDO> findPageByContractId(@Param("contractId") String contractId, Pageable pageable);

    Page<BfiaProjectDO> findAllByContractId(String contractId, Pageable pageable);
}


