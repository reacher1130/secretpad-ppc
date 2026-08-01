package org.secretflow.secretpad.persistence.repository.bfia;

import org.secretflow.secretpad.persistence.entity.bfia.BfiaDatasetDO;
import org.secretflow.secretpad.persistence.repository.BaseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public interface BfiaDatasetRepository extends BaseRepository<BfiaDatasetDO, String> {
    Page<BfiaDatasetDO> findAllByContractId(String contractId, Pageable pageable);
}


