package org.secretflow.secretpad.persistence.repository.bfia;

import org.secretflow.secretpad.persistence.entity.bfia.BfiaResultDO;
import org.secretflow.secretpad.persistence.repository.BaseRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BfiaResultRepository extends BaseRepository<BfiaResultDO, String> {
    @Query("from BfiaResultDO r where r.projectId = :projectId order by r.gmtCreate desc")
    List<BfiaResultDO> findByProjectId(@Param("projectId") String projectId);

    @Query("from BfiaResultDO r where r.nodeId = :nodeId order by r.gmtCreate desc")
    List<BfiaResultDO> findByNodeId(@Param("nodeId") String nodeId);
}
