CREATE TABLE IF NOT EXISTS bfia_dataset (
  dataset_id VARCHAR(64) PRIMARY KEY,
  contract_id VARCHAR(64) NOT NULL,
  name VARCHAR(256) NOT NULL,
  description TEXT,
  status VARCHAR(64),
  is_deleted TINYINT DEFAULT 0,
  gmt_create TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bfia_project_flow (
  project_id VARCHAR(64) NOT NULL,
  flow_id VARCHAR(64) NOT NULL,
  name VARCHAR(256),
  description TEXT,
  is_draft INT,
  edges TEXT,
  dag TEXT,
  config TEXT,
  owner_id VARCHAR(64),
  node_max_index INT,
  max_parallelism INT,
  is_deleted TINYINT DEFAULT 0,
  gmt_create TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (project_id, flow_id)
);

