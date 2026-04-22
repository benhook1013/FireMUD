CREATE TABLE script_event_audit (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64),
    region_id VARCHAR(64),
    region_epoch BIGINT,
    entity_id VARCHAR(64),
    script_id VARCHAR(128),
    plugin_id VARCHAR(128),
    plugin_version_id VARCHAR(128),
    event_type VARCHAR(128) NOT NULL,
    event_schema_version VARCHAR(32) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    script_event_id VARCHAR(128) NOT NULL,
    source_service VARCHAR(128) NOT NULL,
    trigger_mode VARCHAR(64) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    read_snapshot_token VARCHAR(512),
    payload_json TEXT,
    admitted BOOLEAN NOT NULL,
    admission_outcome VARCHAR(128) NOT NULL,
    admission_reason VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_event_audit_trigger_identity UNIQUE (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        script_id,
        plugin_id,
        plugin_version_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run
    )
);

CREATE INDEX idx_script_event_audit_tenant_created ON script_event_audit(tenant_id, created_at);
CREATE INDEX idx_script_event_audit_event_type ON script_event_audit(event_type, event_schema_version);
