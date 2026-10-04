CREATE TABLE published_realm_entry_policy (
    policy_id UUID PRIMARY KEY,
    canonical_tenant_id UUID NOT NULL REFERENCES game(canonical_tenant_id),
    tenant_identity_provenance_kind VARCHAR(32) NOT NULL
        CHECK (tenant_identity_provenance_kind IN ('RETAINED_GAME_V29', 'NEW_GAME_ROW')),
    source_game_row_id BIGINT NOT NULL REFERENCES game(id) CHECK (source_game_row_id > 0),
    source_game_tenant_key VARCHAR(36) NOT NULL
        CHECK (char_length(source_game_tenant_key) BETWEEN 1 AND 36
            AND source_game_tenant_key ~ '[^[:space:]]'),
    version_id BIGINT NOT NULL REFERENCES version(id) CHECK (version_id > 0),
    version_number INT NOT NULL CHECK (version_number > 0),
    release_bundle_id BIGINT NOT NULL REFERENCES published_release_bundle(id),
    source_revision_id BIGINT NOT NULL REFERENCES revision(id),
    release_bundle_identity VARCHAR(71) NOT NULL
        CHECK (release_bundle_identity ~ '^sha256:[0-9a-f]{64}$'),
    publish_workflow_id VARCHAR(64) NOT NULL CHECK (publish_workflow_id <> ''),
    manifest_hash VARCHAR(128) NOT NULL CHECK (manifest_hash <> ''),
    world_slug VARCHAR(64) NOT NULL
        CHECK (world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    world_display_name VARCHAR(512) NOT NULL
        CHECK (char_length(world_display_name) BETWEEN 1 AND 128
            AND btrim(world_display_name) = world_display_name),
    realm_slug VARCHAR(64) NOT NULL
        CHECK (realm_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    realm_display_name VARCHAR(512) NOT NULL
        CHECK (char_length(realm_display_name) BETWEEN 1 AND 128
            AND btrim(realm_display_name) = realm_display_name),
    visible BOOLEAN NOT NULL,
    public_production BOOLEAN NOT NULL,
    state_scope VARCHAR(16) NOT NULL CHECK (state_scope IN ('SHARED', 'ISOLATED')),
    entry_policy VARCHAR(32) NOT NULL CHECK (entry_policy = 'PRESEEDED_ONLY'),
    policy_json TEXT NOT NULL CHECK (octet_length(policy_json) BETWEEN 1 AND 4096),
    policy_digest VARCHAR(71) NOT NULL CHECK (policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT uq_published_realm_entry_policy_selector
        UNIQUE (canonical_tenant_id, version_id, world_slug, realm_slug)
);

-- [jooq ignore start]
CREATE FUNCTION reject_published_realm_entry_policy_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'published realm-entry policy evidence is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_published_realm_entry_policy_immutable
    BEFORE UPDATE OR DELETE ON published_realm_entry_policy
    FOR EACH ROW
    EXECUTE FUNCTION reject_published_realm_entry_policy_mutation();
-- [jooq ignore stop]
