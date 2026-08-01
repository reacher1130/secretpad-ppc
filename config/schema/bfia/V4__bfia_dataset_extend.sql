ALTER TABLE bfia_dataset ADD COLUMN namespace VARCHAR(64) DEFAULT 'data';
ALTER TABLE bfia_dataset ADD COLUMN file_name VARCHAR(256);
ALTER TABLE bfia_dataset ADD COLUMN field_names VARCHAR(256);
ALTER TABLE bfia_dataset ADD COLUMN has_label BOOLEAN DEFAULT FALSE;
ALTER TABLE bfia_dataset ADD COLUMN row_count BIGINT DEFAULT 0;
