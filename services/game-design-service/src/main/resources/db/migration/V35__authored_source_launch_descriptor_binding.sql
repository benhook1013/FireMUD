ALTER TABLE launch_descriptor ADD COLUMN descriptor_schema_version SMALLINT;
ALTER TABLE launch_descriptor ADD COLUMN target_namespace VARCHAR(63);
ALTER TABLE launch_descriptor ADD COLUMN canonical_tenant_id UUID;
ALTER TABLE launch_descriptor ADD COLUMN authored_world_source_tenant_slug VARCHAR(120);
ALTER TABLE launch_descriptor ADD COLUMN world_slug VARCHAR(120);
ALTER TABLE launch_descriptor ADD COLUMN authored_world_source_operation_id UUID;
ALTER TABLE launch_descriptor ADD COLUMN authored_world_source_game_row_id BIGINT;
ALTER TABLE launch_descriptor ADD COLUMN authored_world_source_game_tenant_key VARCHAR(36);
ALTER TABLE launch_descriptor ADD COLUMN authored_world_source_provenance_kind VARCHAR(32);
ALTER TABLE launch_descriptor ADD COLUMN authored_world_source_evidence_digest VARCHAR(71);
ALTER TABLE launch_descriptor ADD COLUMN request_digest VARCHAR(71);
ALTER TABLE launch_descriptor ADD COLUMN result_digest VARCHAR(71);
ALTER TABLE launch_descriptor ADD COLUMN original_request_json TEXT;
ALTER TABLE launch_descriptor ADD COLUMN source_evidence_json TEXT;
ALTER TABLE launch_descriptor ADD COLUMN outcome_status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS';
ALTER TABLE launch_descriptor ADD COLUMN failure_code VARCHAR(64);
ALTER TABLE launch_descriptor ADD COLUMN failure_message TEXT;

ALTER TABLE game_design_authored_world_source_operations
    ADD CONSTRAINT uq_gd_authored_world_launch_source UNIQUE (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        tenant_slug,
        world_slug,
        source_game_row_id,
        source_game_tenant_key,
        provenance_kind,
        evidence_digest
    );

ALTER TABLE launch_descriptor
    ADD CONSTRAINT fk_launch_descriptor_authored_world_source
    FOREIGN KEY (
        authored_world_source_operation_id,
        target_namespace,
        canonical_tenant_id,
        authored_world_source_tenant_slug,
        world_slug,
        authored_world_source_game_row_id,
        authored_world_source_game_tenant_key,
        authored_world_source_provenance_kind,
        authored_world_source_evidence_digest
    ) REFERENCES game_design_authored_world_source_operations (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        tenant_slug,
        world_slug,
        source_game_row_id,
        source_game_tenant_key,
        provenance_kind,
        evidence_digest
    );

-- A deterministic denial is a request outcome, not a descriptor. Keep the successful tuple
-- nullable at the row level and require its complete shape below for SUCCESS outcomes.
ALTER TABLE launch_descriptor ALTER COLUMN launch_descriptor_id DROP NOT NULL;
ALTER TABLE launch_descriptor ALTER COLUMN game_template_id DROP NOT NULL;
ALTER TABLE launch_descriptor ALTER COLUMN version_id DROP NOT NULL;
ALTER TABLE launch_descriptor ALTER COLUMN runtime_flags_json DROP NOT NULL;
ALTER TABLE launch_descriptor ALTER COLUMN generation_config_revision DROP NOT NULL;
ALTER TABLE launch_descriptor ALTER COLUMN version_state_epoch DROP NOT NULL;
ALTER TABLE launch_descriptor ALTER COLUMN release_bundle_id DROP NOT NULL;
ALTER TABLE launch_descriptor ALTER COLUMN published_release_bundle_ref DROP NOT NULL;

ALTER TABLE launch_descriptor
    ADD CONSTRAINT ck_launch_descriptor_authored_binding_complete
    CHECK (
        (outcome_status = 'SUCCESS'
            AND descriptor_schema_version IS NULL
            AND target_namespace IS NULL
            AND canonical_tenant_id IS NULL
            AND authored_world_source_tenant_slug IS NULL
            AND world_slug IS NULL
            AND authored_world_source_operation_id IS NULL
            AND authored_world_source_game_row_id IS NULL
            AND authored_world_source_game_tenant_key IS NULL
            AND authored_world_source_provenance_kind IS NULL
            AND authored_world_source_evidence_digest IS NULL
            AND request_digest IS NULL
            AND result_digest IS NULL
            AND original_request_json IS NULL
            AND source_evidence_json IS NULL
            AND failure_code IS NULL
            AND failure_message IS NULL
            AND launch_descriptor_id IS NOT NULL
            AND tenant_id IS NOT NULL
            AND game_template_id IS NOT NULL
            AND control_plane_request_id IS NOT NULL
            AND request_hash IS NOT NULL
            AND version_id IS NOT NULL
            AND runtime_flags_json IS NOT NULL
            AND generation_config_revision IS NOT NULL
            AND version_state_epoch IS NOT NULL
            AND release_bundle_id IS NOT NULL
            AND published_release_bundle_ref IS NOT NULL)
        OR
        (outcome_status = 'SUCCESS'
            AND descriptor_schema_version IS NOT NULL
            AND descriptor_schema_version = 1
            AND target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND authored_world_source_tenant_slug IS NOT NULL
            AND world_slug IS NOT NULL
            AND authored_world_source_operation_id IS NOT NULL
            AND authored_world_source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND authored_world_source_game_row_id IS NOT NULL
            AND authored_world_source_game_row_id > 0
            AND authored_world_source_game_tenant_key IS NOT NULL
            AND char_length(authored_world_source_game_tenant_key) BETWEEN 1 AND 36
            AND authored_world_source_game_tenant_key !~ '^[[:space:]]*$'
            AND authored_world_source_provenance_kind IS NOT NULL
            AND authored_world_source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29')
            AND authored_world_source_evidence_digest IS NOT NULL
            AND request_digest IS NOT NULL
            AND result_digest IS NOT NULL
            AND original_request_json IS NOT NULL
            AND source_evidence_json IS NOT NULL
            AND failure_code IS NULL
            AND failure_message IS NULL
            AND launch_descriptor_id IS NOT NULL
            AND tenant_id IS NOT NULL
            AND game_template_id IS NOT NULL
            AND control_plane_request_id IS NOT NULL
            AND version_id IS NOT NULL
            AND runtime_flags_json IS NOT NULL
            AND generation_config_revision IS NOT NULL
            AND version_state_epoch IS NOT NULL
            AND release_bundle_id IS NOT NULL
            AND published_release_bundle_ref IS NOT NULL
            AND request_hash = request_digest)
        OR
        (outcome_status = 'FAILED'
            AND descriptor_schema_version IS NOT NULL
            AND descriptor_schema_version = 1
            AND target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND authored_world_source_tenant_slug IS NOT NULL
            AND world_slug IS NOT NULL
            AND authored_world_source_operation_id IS NOT NULL
            AND authored_world_source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND authored_world_source_game_row_id IS NOT NULL
            AND authored_world_source_game_row_id > 0
            AND authored_world_source_game_tenant_key IS NOT NULL
            AND char_length(authored_world_source_game_tenant_key) BETWEEN 1 AND 36
            AND authored_world_source_game_tenant_key !~ '^[[:space:]]*$'
            AND authored_world_source_provenance_kind IS NOT NULL
            AND authored_world_source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29')
            AND authored_world_source_evidence_digest IS NOT NULL
            AND request_digest IS NOT NULL
            AND result_digest IS NULL
            AND original_request_json IS NOT NULL
            AND source_evidence_json IS NOT NULL
            AND failure_code IS NOT NULL
            AND failure_code IN (
                'TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED',
                'INVALID_TEMPLATE_CONFIGURATION',
                'SCRIPT_PATCH_OVERRIDE_CONFLICT',
                'SCRIPT_PATCH_NOT_READY',
                'RELEASE_BUNDLE_NOT_FOUND',
                'RELEASE_ATTESTATION_MISMATCH',
                'VERSION_STATE_EPOCH_STALE',
                'LAUNCH_REMAP_REQUIRED',
                'SCHEMA_VERSION_UNSUPPORTED')
            AND failure_message IS NOT NULL
            AND CHAR_LENGTH(failure_message) > 0
            AND launch_descriptor_id IS NULL
            AND tenant_id IS NOT NULL
            AND game_template_id IS NULL
            AND control_plane_request_id IS NOT NULL
            AND request_hash = request_digest
            AND version_id IS NULL
            AND script_patch_version IS NULL
            AND runtime_flags_json IS NULL
            AND generation_config_revision IS NULL
            AND version_state_epoch IS NULL
            AND release_bundle_id IS NULL
            AND published_release_bundle_ref IS NULL
            AND remap_set_id IS NULL)
    );

ALTER TABLE launch_descriptor
    ADD CONSTRAINT ck_launch_descriptor_outcome_status
    CHECK (outcome_status IN ('SUCCESS', 'FAILED'));

ALTER TABLE launch_descriptor
    ADD CONSTRAINT ck_launch_descriptor_digest_shape
    CHECK (
        (descriptor_schema_version IS NULL
            AND authored_world_source_evidence_digest IS NULL
            AND request_digest IS NULL
            AND result_digest IS NULL)
        OR
        (descriptor_schema_version = 1
            AND authored_world_source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
            AND request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND (result_digest IS NULL OR result_digest ~ '^sha256:[0-9a-f]{64}$'))
    );

CREATE UNIQUE INDEX uq_launch_descriptor_bound_request
    ON launch_descriptor(target_namespace, canonical_tenant_id, control_plane_request_id)
    WHERE descriptor_schema_version IS NOT NULL;

-- PostgreSQL enforces the immutable history; the schema generator needs only the table shape.
-- [jooq ignore start]
CREATE FUNCTION reject_launch_descriptor_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'launch descriptors are immutable';
END;
$$;

CREATE TRIGGER trg_launch_descriptor_immutable
    BEFORE UPDATE OR DELETE ON launch_descriptor
    FOR EACH ROW
    EXECUTE FUNCTION reject_launch_descriptor_history_mutation();

CREATE FUNCTION reject_launch_descriptor_history_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'launch descriptors are immutable';
END;
$$;

CREATE TRIGGER trg_launch_descriptor_no_truncate
    BEFORE TRUNCATE ON launch_descriptor
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_launch_descriptor_history_truncate();
-- [jooq ignore stop]
