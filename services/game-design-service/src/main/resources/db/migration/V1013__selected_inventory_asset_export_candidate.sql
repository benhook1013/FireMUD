CREATE TABLE version_asset_export_candidate (
    tenant_id VARCHAR(36) NOT NULL,
    version_id BIGINT NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    expected_version_state_epoch BIGINT NOT NULL,
    workflow_id VARCHAR(1024) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    request_preimage BYTEA NOT NULL,
    operation_digest VARCHAR(71) NOT NULL,
    selected_commit_digest VARCHAR(71) NOT NULL,
    inventory_schema VARCHAR(128) NOT NULL,
    inventory_digest VARCHAR(71) NOT NULL,
    inventory_bytes BYTEA NOT NULL,
    manifest_schema_version INTEGER NOT NULL,
    manifest_hash VARCHAR(71) NOT NULL,
    manifest_bytes BYTEA NOT NULL,
    artifact_digests_json TEXT NOT NULL,
    required_usage_keys_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_version_asset_export_candidate PRIMARY KEY (tenant_id, version_id),
    CONSTRAINT fk_version_asset_export_candidate_version
        FOREIGN KEY (tenant_id, version_id)
        REFERENCES version (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT chk_version_asset_export_candidate_scope CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND expected_version_state_epoch > 0
        AND char_length(workflow_id) > 0
        AND request_digest ~ '^[0-9a-f]{64}$'
        AND operation_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selected_commit_digest ~ '^sha256:[0-9a-f]{64}$'
        AND inventory_schema = 'game-design-selected-draft-asset-inventory/v1'
        AND inventory_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(request_preimage) > 0
        AND octet_length(inventory_bytes) > 0
        AND manifest_schema_version = 1
        AND manifest_hash ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(manifest_bytes) > 0
        AND artifact_digests_json::JSONB IS NOT NULL
        AND required_usage_keys_json::JSONB IS NOT NULL
    )
);

CREATE INDEX idx_version_asset_export_candidate_request
    ON version_asset_export_candidate (canonical_tenant_id, version_id, request_digest);

-- Candidate rows are the additive selected-inventory shape. V37 snapshot candidates remain
-- untouched and no retained V37 data is synthesized into this new evidence table.
-- [jooq ignore start]
CREATE FUNCTION guard_version_asset_export_candidate() RETURNS TRIGGER AS $$
DECLARE
    version_state_value VARCHAR(32);
    version_epoch_value BIGINT;
    version_canonical_tenant UUID;
    version_canonical_version UUID;
    source_game_id BIGINT;
    source_game_tenant VARCHAR(36);
    source_provenance VARCHAR(64);
    game_canonical_tenant UUID;
    game_provenance VARCHAR(64);
    game_source_id BIGINT;
    game_source_tenant VARCHAR(36);
BEGIN
    IF TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SELECTED_ASSET_EXPORT_CANDIDATE_IMMUTABLE';
    END IF;

    SELECT v.version_state, v.version_state_epoch, v.canonical_tenant_id,
           v.canonical_version_id, v.identity_source_game_row_id,
           v.identity_source_game_tenant_key, v.identity_source_provenance_kind,
           g.canonical_tenant_id, g.tenant_identity_provenance_kind,
           g.tenant_identity_source_game_id, g.tenant_identity_source_legacy_tenant_id
      INTO version_state_value, version_epoch_value, version_canonical_tenant,
           version_canonical_version, source_game_id, source_game_tenant,
           source_provenance, game_canonical_tenant, game_provenance,
           game_source_id, game_source_tenant
      FROM version v
      JOIN game g ON g.id = v.identity_source_game_row_id
       AND g.tenant_id = v.identity_source_game_tenant_key
       AND g.tenant_id = v.tenant_id
       AND g.canonical_tenant_id = v.canonical_tenant_id
       AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind
       AND g.tenant_identity_source_game_id = g.id
       AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
     WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.version_id
     FOR UPDATE OF v, g;

    IF NOT FOUND OR version_state_value <> 'DRAFT'
       OR version_epoch_value IS DISTINCT FROM NEW.expected_version_state_epoch
       OR version_canonical_tenant IS DISTINCT FROM NEW.canonical_tenant_id
       OR version_canonical_version IS DISTINCT FROM NEW.canonical_version_id
       OR source_game_id IS NULL OR source_game_tenant IS DISTINCT FROM NEW.tenant_id
       OR source_provenance IS NULL
       OR game_canonical_tenant IS DISTINCT FROM NEW.canonical_tenant_id
       OR game_provenance IS DISTINCT FROM source_provenance
       OR game_source_id IS DISTINCT FROM source_game_id
       OR game_source_tenant IS DISTINCT FROM source_game_tenant THEN
        RAISE EXCEPTION 'SELECTED_ASSET_EXPORT_CANDIDATE_SCOPE_CONFLICT';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_version_asset_export_candidate_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON version_asset_export_candidate
    FOR EACH ROW EXECUTE FUNCTION guard_version_asset_export_candidate();

CREATE FUNCTION guard_version_asset_export_candidate_truncate() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM version_asset_export_candidate) THEN
        RAISE EXCEPTION 'SELECTED_ASSET_EXPORT_CANDIDATE_RETENTION_REQUIRED';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_version_asset_export_candidate_no_truncate
    BEFORE TRUNCATE ON version_asset_export_candidate
    FOR EACH STATEMENT EXECUTE FUNCTION guard_version_asset_export_candidate_truncate();
-- [jooq ignore stop]
