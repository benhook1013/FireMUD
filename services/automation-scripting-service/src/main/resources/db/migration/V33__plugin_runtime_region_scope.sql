ALTER TABLE plugin_runtime_states
    ADD COLUMN runtime_region_id VARCHAR(64),
    ADD COLUMN runtime_region_epoch BIGINT;

ALTER TABLE plugin_runtime_events
    ADD COLUMN runtime_region_id VARCHAR(64),
    ADD COLUMN runtime_region_epoch BIGINT;
