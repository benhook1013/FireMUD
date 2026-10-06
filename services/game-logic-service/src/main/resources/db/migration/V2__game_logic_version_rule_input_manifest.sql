CREATE TABLE game_logic_version_rule_input_manifest (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    source_proof_json TEXT NOT NULL,
    game_design_version_row_id BIGINT NOT NULL,
    game_design_version_tenant_key VARCHAR(36) NOT NULL,
    source_game_row_id BIGINT NOT NULL,
    source_game_tenant_key VARCHAR(36) NOT NULL,
    source_provenance_kind VARCHAR(32) NOT NULL,
    request_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    base_commit_id VARCHAR(256) NOT NULL,
    revision_id UUID NOT NULL,
    binding_json TEXT NOT NULL,
    binding_digest VARCHAR(71) NOT NULL,
    owner_intent_json TEXT NOT NULL,
    manifest_json TEXT NOT NULL,
    digest_schema_version INTEGER NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    ability_schema_version INTEGER NOT NULL,
    ability_schema_json TEXT NOT NULL,
    ability_schema_digest VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id VARCHAR(64) NOT NULL,
    expected_epoch BIGINT NOT NULL,
    aggregate_epoch_after BIGINT NOT NULL,
    scope_epoch_after BIGINT NOT NULL,
    owner_result_json TEXT NOT NULL,
    owner_result_digest VARCHAR(71) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_game_logic_version_rule_input_manifest
        PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    CONSTRAINT uq_game_logic_rule_manifest_request
        UNIQUE (canonical_tenant_id, request_id),
    CONSTRAINT uq_game_logic_rule_manifest_commit
        UNIQUE (canonical_tenant_id, commit_id),
    CONSTRAINT ck_game_logic_rule_manifest_identity CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND revision_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT ck_game_logic_rule_manifest_source CHECK (
        game_design_version_row_id > 0
        AND source_game_row_id > 0
        AND length(game_design_version_tenant_key) BETWEEN 1 AND 36
        AND length(source_game_tenant_key) BETWEEN 1 AND 36
        AND game_design_version_tenant_key = source_game_tenant_key
        AND source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V30')
    ),
    CONSTRAINT ck_game_logic_rule_manifest_binding CHECK (
        length(base_commit_id) BETWEEN 1 AND 256
        AND length(binding_json) > 0
        AND binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND length(source_proof_json) > 0
        AND length(owner_intent_json) > 0
        AND length(manifest_json) > 0
    ),
    CONSTRAINT ck_game_logic_rule_manifest_digest CHECK (
        digest_schema_version = 1
        AND ability_schema_version = 1
        AND content_digest ~ '^[0-9a-f]{64}$'
        AND ability_schema_digest ~ '^[0-9a-f]{64}$'
        AND length(ability_schema_json) > 0
    ),
    CONSTRAINT ck_game_logic_rule_manifest_epoch CHECK (
        aggregate_type = 'VERSION_RULE_MANIFEST'
        AND aggregate_id = canonical_version_id::TEXT
        AND scope_type = 'VERSION'
        AND scope_id = canonical_version_id::TEXT
        AND expected_epoch = 0
        AND aggregate_epoch_after = 1
        AND scope_epoch_after = 1
    ),
    CONSTRAINT ck_game_logic_rule_manifest_result CHECK (
        length(owner_result_json) > 0
        AND owner_result_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

-- [jooq ignore start]
CREATE FUNCTION game_logic_reject_rule_manifest_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Game Logic Version rule manifests are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER game_logic_version_rule_input_manifest_immutable
    BEFORE UPDATE OR DELETE ON game_logic_version_rule_input_manifest
    FOR EACH ROW EXECUTE FUNCTION game_logic_reject_rule_manifest_rewrite();
-- [jooq ignore stop]
