package org.secretflow.secretpad.persistence.entity.bfia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;
import org.secretflow.secretpad.persistence.entity.BaseAggregationRoot;

/**
 * 合约表
 */
@Builder
@Entity
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Setter
@Getter
@Table(name = "bfia_contract")
@SQLDelete(sql = "update bfia_contract set is_deleted = 1 where id = ?")
@Where(clause = "is_deleted = 0")
public class BfiaContractDO extends BaseAggregationRoot<BfiaContractDO> {

    @Id
    @Column(name = "contract_id", unique = false, nullable = false, length = 64)
    private String contractId;

    @Column(name = "node_id", unique = false, length = 64)
    private String nodeId;

    @Column(name = "initiator_node_id", unique = false, length = 64)
    private String initiatorNodeId;

    @Column(name = "expired_time", unique = false)
    private Long expiredTime;

    @Column(name = "auth_credential", nullable = true, columnDefinition = "text")
    private String authCredential;

    @Column(name = "auth_type", unique = false, length = 64)
    private String authType;

    @Column(name = "address", unique = true, length = 64)
    private String address;

    @Column(name = "description", nullable = true, columnDefinition = "text")
    private String description;

    @Column(name = "inst_id", unique = false, length = 64)
    private String instId;

    @Column(name = "institution", unique = false, length = 64)
    private String institution;

    @Column(name = "name", unique = true, length = 64)
    private String name;

    @Column(name = "systems", unique = false, length = 64)
    private String system;

    @Column(name = "system_version", unique = false, length = 64)
    private String systemVersion;

    @Column(name = "status", unique = false, length = 64)
    private String status;

    @Column(name = "type", unique = false, length = 64)
    private String type;

    @Column(name = "pad_address", unique = false, length = 64)
    private String padAddress;
}


