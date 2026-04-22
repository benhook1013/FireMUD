ALTER TABLE script_work_items
    ADD COLUMN cancel_reason VARCHAR(256),
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE script_event_audit (
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
    work_item_id BIGINT REFERENCES script_work_items(id),
    final_stage VARCHAR(64) NOT NULL,
    final_outcome VARCHAR(128) NOT NULL,
    final_reason VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_event_audit_handler_identity UNIQUE (
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

CREATE INDEX idx_script_event_audit_script_created ON script_event_audit(tenant_id, script_id, created_at);
CREATE INDEX idx_script_event_audit_outcome ON script_event_audit(final_stage, final_outcome);
