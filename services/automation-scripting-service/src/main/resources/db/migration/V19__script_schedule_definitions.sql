CREATE TABLE script_schedule_definitions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    script_id VARCHAR(100) NOT NULL,
    plugin_id VARCHAR(128) NOT NULL DEFAULT '',
    plugin_version_id VARCHAR(128) NOT NULL DEFAULT '',
    event_type VARCHAR(64) NOT NULL,
    schedule_definition_id VARCHAR(160) NOT NULL,
    schedule_kind VARCHAR(32) NOT NULL,
    cadence_value BIGINT NOT NULL,
    cadence_unit VARCHAR(32) NOT NULL,
    priority_tag VARCHAR(32) NOT NULL DEFAULT 'normal',
    schedule_metadata_json TEXT NOT NULL,
    schedule_semantics_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_schedule_definition_scope UNIQUE (
        tenant_id,
        script_patch_version,
        plugin_id,
        plugin_version_id,
        schedule_definition_id
    )
);

CREATE INDEX idx_script_schedule_definition_patch
    ON script_schedule_definitions (tenant_id, script_patch_version, script_id);
