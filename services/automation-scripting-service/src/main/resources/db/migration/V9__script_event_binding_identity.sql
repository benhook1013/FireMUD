-- Add the stable binding identity without changing the applied V1 baseline checksum.
-- Existing rows are pre-v1 development data and receive the explicit empty legacy sentinel.
ALTER TABLE script_event_bindings
    ADD COLUMN binding_id VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE script_event_bindings
    DROP CONSTRAINT uq_script_event_binding;

ALTER TABLE script_event_bindings
    ADD CONSTRAINT uq_script_event_binding UNIQUE (
        tenant_id,
        script_patch_version,
        event_type,
        event_schema_version,
        script_id,
        binding_id,
        target_scope_type,
        target_scope_id
    );

DROP INDEX idx_script_event_bindings_resolution;

CREATE INDEX idx_script_event_bindings_resolution ON script_event_bindings(
    tenant_id,
    script_patch_version,
    event_type,
    event_schema_version,
    enabled,
    priority,
    script_id,
    binding_id,
    id
);
