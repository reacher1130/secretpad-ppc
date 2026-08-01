-- BFIA full isolation tables: node, graph, graph_node, job, result
-- Decoupled from native secretflow tables to physically isolate BFIA data.
-- Tables extending BaseAggregationRoot include id/is_deleted/gmt_create/gmt_modified.

CREATE TABLE IF NOT EXISTS bfia_node (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  node_id VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(256) NOT NULL,
  net_address VARCHAR(200),
  pad_address VARCHAR(200),
  inst_id VARCHAR(64),
  status VARCHAR(32),
  description TEXT,
  is_deleted TINYINT DEFAULT 0,
  gmt_create TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bfia_graph (
  id BIGINT AUTO_INCREMENT,
  project_id VARCHAR(64) NOT NULL,
  graph_id VARCHAR(64) NOT NULL,
  name VARCHAR(256),
  edges TEXT,
  owner_id VARCHAR(64),
  max_parallelism INT,
  is_deleted TINYINT DEFAULT 0,
  gmt_create TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (project_id, graph_id),
  UNIQUE (id)
);

CREATE TABLE IF NOT EXISTS bfia_graph_node (
  project_id VARCHAR(64) NOT NULL,
  graph_id VARCHAR(64) NOT NULL,
  graph_node_id VARCHAR(64) NOT NULL,
  graph_node_name VARCHAR(256),
  node_def_id VARCHAR(128),
  params TEXT,
  is_deleted TINYINT DEFAULT 0,
  PRIMARY KEY (project_id, graph_id, graph_node_id)
);

CREATE TABLE IF NOT EXISTS bfia_job (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id VARCHAR(64) NOT NULL UNIQUE,
  project_id VARCHAR(64) NOT NULL,
  graph_id VARCHAR(64) NOT NULL,
  initiator_id VARCHAR(64),
  status VARCHAR(32),
  job_params TEXT,
  is_deleted TINYINT DEFAULT 0,
  gmt_create TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bfia_result (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  result_id VARCHAR(64) NOT NULL UNIQUE,
  project_id VARCHAR(64) NOT NULL,
  job_id VARCHAR(64),
  task_id VARCHAR(64),
  node_id VARCHAR(64),
  kind VARCHAR(32),
  ref_id VARCHAR(64),
  output_uri TEXT,
  is_deleted TINYINT DEFAULT 0,
  gmt_create TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
