CREATE TABLE script_work_items (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    region_id VARCHAR(64) NOT NULL,
    region_epoch BIGINT NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    script_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_schema_version VARCHAR(32) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    script_event_id VARCHAR(128) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    source_service VARCHAR(128) NOT NULL,
    trigger_mode VARCHAR(64) NOT NULL,
    read_snapshot_token VARCHAR(512),
    payload_json TEXT,
    status VARCHAR(64) NOT NULL DEFAULT 'PENDING_EVALUATION',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_work_item_trigger_identity UNIQUE (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        script_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run
    )
);

CREATE INDEX idx_script_work_items_status_created ON script_work_items(status, created_at);
CREATE INDEX idx_script_work_items_entity_status ON script_work_items(
    tenant_id,
    game_instance_id,
    entity_id,
    status
);
