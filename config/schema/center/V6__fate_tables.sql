/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

-- FATE 2.x management tables.
-- These tables are isolated from native and BFIA tables. They only store
-- metadata that SecretPad needs to manage FATE clusters, jobs, datasets and
-- models. Actual job execution is delegated to FATE-Flow via the Kuscia
-- fate2-adapter AppImage.

create table if not exists `fate_cluster`
(
    id           integer primary key autoincrement,
    cluster_id   varchar(64)                           not null,
    name         varchar(256)                          not null,
    node_id      varchar(64)                           not null,
    flow_address varchar(256)                          not null,
    version      varchar(32)  default '',
    description  text         default '',
    status       varchar(32)  default 'UNKNOWN',
    is_deleted   tinyint(1)   default '0'             not null,
    gmt_create   datetime     default CURRENT_TIMESTAMP not null,
    gmt_modified datetime     default CURRENT_TIMESTAMP not null
);
create unique index if not exists `upk_fate_cluster_id` on fate_cluster (`cluster_id`);
create index if not exists `key_fate_cluster_node_id` on fate_cluster (`node_id`);

create table if not exists `fate_job`
(
    id                integer primary key autoincrement,
    job_id            varchar(64)                           not null,
    cluster_id        varchar(64)                           not null,
    project_id        varchar(64)  default '',
    graph_id          varchar(64)  default '',
    name              varchar(256) default '',
    initiator_node_id varchar(64)  default '',
    status            varchar(32)  default 'PENDING',
    dag               text         default '',
    job_config        text         default '',
    err_msg           text         default '',
    is_deleted        tinyint(1)   default '0'             not null,
    gmt_create        datetime     default CURRENT_TIMESTAMP not null,
    gmt_modified      datetime     default CURRENT_TIMESTAMP not null
);
create unique index if not exists `upk_fate_job_id` on fate_job (`job_id`);
create index if not exists `key_fate_job_cluster_id` on fate_job (`cluster_id`);
create index if not exists `key_fate_job_project_id` on fate_job (`project_id`);

create table if not exists `fate_dataset`
(
    id           integer primary key autoincrement,
    dataset_id   varchar(64)                           not null,
    cluster_id   varchar(64)                           not null,
    node_id      varchar(64)                           not null,
    name         varchar(256)                          not null,
    namespace    varchar(64)  default '',
    table_name   varchar(256) default '',
    role         varchar(32)  default '',
    meta         text         default '',
    is_deleted   tinyint(1)   default '0'             not null,
    gmt_create   datetime     default CURRENT_TIMESTAMP not null,
    gmt_modified datetime     default CURRENT_TIMESTAMP not null
);
create unique index if not exists `upk_fate_dataset_id` on fate_dataset (`dataset_id`);
create index if not exists `key_fate_dataset_cluster_id` on fate_dataset (`cluster_id`);
create index if not exists `key_fate_dataset_node_id` on fate_dataset (`node_id`);

create table if not exists `fate_model`
(
    id              integer primary key autoincrement,
    model_id        varchar(64)                           not null,
    job_id          varchar(64)                           not null,
    cluster_id      varchar(64)                           not null,
    node_id         varchar(64)                           not null,
    name            varchar(256) default '',
    component_name  varchar(128) default '',
    model_type      varchar(64)  default '',
    metric          text         default '',
    is_deleted      tinyint(1)   default '0'             not null,
    gmt_create      datetime     default CURRENT_TIMESTAMP not null,
    gmt_modified    datetime     default CURRENT_TIMESTAMP not null
);
create unique index if not exists `upk_fate_model_id` on fate_model (`model_id`);
create index if not exists `key_fate_model_job_id` on fate_model (`job_id`);
