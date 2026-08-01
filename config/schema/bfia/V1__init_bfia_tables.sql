-- Minimal BFIA tables to allow startup; extend as needed
CREATE TABLE IF NOT EXISTS bfia_contract (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_id VARCHAR(64) NOT NULL UNIQUE,
  node_id VARCHAR(64),
  initiator_node_id VARCHAR(64),
  expired_time BIGINT,
  auth_credential TEXT,
  auth_type VARCHAR(64),
  address VARCHAR(64),
  description TEXT,
  inst_id VARCHAR(64),
  institution VARCHAR(64),
  name VARCHAR(64),
  systems VARCHAR(64),
  system_version VARCHAR(64),
  status VARCHAR(64),
  type VARCHAR(64),
  pad_address VARCHAR(64),
  is_deleted TINYINT DEFAULT 0,
  gmt_create TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

