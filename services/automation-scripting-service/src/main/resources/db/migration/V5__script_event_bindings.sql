CREATE TABLE script_event_bindings (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_schema_version VARCHAR(32) NOT NULL,
    script_id VARCHAR(128) NOT NULL,
    target_scope_type VARCHAR(32) NOT NULL,
    target_scope_id VARCHAR(128) NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    requires_exclusive_event BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_event_binding UNIQUE (
        tenant_id,
        script_patch_version,
        event_type,
        event_schema_version,
        script_id,
        target_scope_type,
        target_scope_id
    )
);

CREATE INDEX idx_script_event_bindings_resolution ON script_event_bindings(
    tenant_id,
    script_patch_version,
    event_type,
    event_schema_version,
    enabled,
    priority,
    script_id
);
