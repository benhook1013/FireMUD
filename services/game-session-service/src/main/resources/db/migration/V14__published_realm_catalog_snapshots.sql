CREATE TABLE gameplay_published_realm_catalog_snapshot (
    target_namespace VARCHAR(63) NOT NULL,
    tenant_id BIGINT NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    catalog_revision BIGINT NOT NULL,
    source_game_row_id BIGINT NOT NULL,
    source_game_tenant_key VARCHAR(36) NOT NULL,
    tenant_identity_provenance_kind VARCHAR(32) NOT NULL,
    version_id BIGINT NOT NULL,
    version_number INTEGER NOT NULL,
    release_bundle_identity VARCHAR(71) NOT NULL,
    publish_workflow_id VARCHAR(1024) NOT NULL,
    manifest_hash VARCHAR(128) NOT NULL,
    policy_set_digest VARCHAR(71) NOT NULL,
    policy_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_published_realm_catalog_snapshot
        PRIMARY KEY (target_namespace, tenant_id, catalog_revision),
    CONSTRAINT uq_gs_published_realm_catalog_snapshot_version
        UNIQUE (target_namespace, tenant_id, version_id),
    CONSTRAINT uq_gs_published_realm_catalog_snapshot_canonical
        UNIQUE (target_namespace, canonical_tenant_id, catalog_revision),
    CONSTRAINT uq_gs_published_realm_catalog_snapshot_owner_identity
        UNIQUE (target_namespace, tenant_id, catalog_revision, canonical_tenant_id),
    CONSTRAINT fk_gs_published_realm_catalog_snapshot_association
        FOREIGN KEY (target_namespace, canonical_tenant_id)
        REFERENCES game_session_retained_tenant_association
            (target_namespace, canonical_tenant_id),
    CONSTRAINT chk_gs_published_realm_catalog_snapshot_identity
        CHECK (tenant_id > 0 AND source_game_row_id > 0 AND version_id > 0
            AND version_number > 0 AND catalog_revision > 0),
    CONSTRAINT chk_gs_published_realm_catalog_snapshot_tenant
        CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_gs_published_realm_catalog_snapshot_provenance
        CHECK (tenant_identity_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29')),
    CONSTRAINT chk_gs_published_realm_catalog_snapshot_release_digest
        CHECK (release_bundle_identity ~ '^sha256:[0-9a-f]{64}$'
            AND policy_set_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_gs_published_realm_catalog_snapshot_policy_count
        CHECK (policy_count BETWEEN 1 AND 128),
    CONSTRAINT chk_gs_published_realm_catalog_snapshot_namespace
        CHECK (octet_length(target_namespace) BETWEEN 1 AND 63
            AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT chk_gs_published_realm_catalog_snapshot_source_key
        CHECK (char_length(source_game_tenant_key) BETWEEN 1 AND 36
            AND source_game_tenant_key !~ '^[[:space:]]*$'),
    CONSTRAINT chk_gs_published_realm_catalog_snapshot_labels
        CHECK (char_length(publish_workflow_id) BETWEEN 1 AND 1024
            AND char_length(manifest_hash) BETWEEN 1 AND 128)
);

CREATE TABLE gameplay_published_realm_catalog_identity (
    target_namespace VARCHAR(63) NOT NULL,
    tenant_id BIGINT NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    realm_slug VARCHAR(64) NOT NULL,
    initial_world_slug VARCHAR(64) NOT NULL,
    realm_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_published_realm_catalog_identity
        PRIMARY KEY (target_namespace, canonical_tenant_id, realm_slug),
    CONSTRAINT uq_gs_published_realm_catalog_identity_id UNIQUE (realm_id),
    CONSTRAINT uq_gs_published_realm_catalog_identity_realm_key
        UNIQUE (target_namespace, canonical_tenant_id, realm_slug, realm_id),
    CONSTRAINT fk_gs_published_realm_catalog_identity_association
        FOREIGN KEY (target_namespace, canonical_tenant_id)
        REFERENCES game_session_retained_tenant_association
            (target_namespace, canonical_tenant_id),
    CONSTRAINT chk_gs_published_realm_catalog_identity_tenant
        CHECK (tenant_id > 0
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_gs_published_realm_catalog_identity_realm_id
        CHECK (realm_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_gs_published_realm_catalog_identity_namespace
        CHECK (octet_length(target_namespace) BETWEEN 1 AND 63
            AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT chk_gs_published_realm_catalog_identity_selectors
        CHECK (realm_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
            AND initial_world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE gameplay_published_realm_catalog_entry (
    target_namespace VARCHAR(63) NOT NULL,
    tenant_id BIGINT NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    catalog_revision BIGINT NOT NULL,
    realm_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    source_revision_id BIGINT NOT NULL,
    world_slug VARCHAR(64) NOT NULL,
    world_display_name VARCHAR(128) NOT NULL,
    realm_slug VARCHAR(64) NOT NULL,
    realm_display_name VARCHAR(128) NOT NULL,
    visible BOOLEAN NOT NULL,
    public_production BOOLEAN NOT NULL,
    state_scope VARCHAR(16) NOT NULL,
    entry_policy VARCHAR(32) NOT NULL,
    policy_json VARCHAR(4096) NOT NULL,
    policy_digest VARCHAR(71) NOT NULL,
    playable_state_namespace_id UUID,
    namespace_resolution VARCHAR(32) NOT NULL,
    CONSTRAINT pk_gs_published_realm_catalog_entry
        PRIMARY KEY (target_namespace, tenant_id, catalog_revision, realm_id),
    CONSTRAINT uq_gs_published_realm_catalog_entry_selector
        UNIQUE (target_namespace, tenant_id, catalog_revision, world_slug, realm_slug),
    CONSTRAINT uq_gs_published_realm_catalog_entry_realm_slug
        UNIQUE (target_namespace, tenant_id, catalog_revision, realm_slug),
    CONSTRAINT fk_gs_published_realm_catalog_entry_snapshot
        FOREIGN KEY (target_namespace, tenant_id, catalog_revision, canonical_tenant_id)
        REFERENCES gameplay_published_realm_catalog_snapshot
            (target_namespace, tenant_id, catalog_revision, canonical_tenant_id),
    CONSTRAINT fk_gs_published_realm_catalog_entry_identity
        FOREIGN KEY (target_namespace, canonical_tenant_id, realm_slug, realm_id)
        REFERENCES gameplay_published_realm_catalog_identity
            (target_namespace, canonical_tenant_id, realm_slug, realm_id),
    CONSTRAINT chk_gs_published_realm_catalog_entry_identity
        CHECK (catalog_revision > 0 AND source_revision_id > 0
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND policy_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_gs_published_realm_catalog_entry_scope
        CHECK (state_scope IN ('SHARED', 'ISOLATED') AND entry_policy = 'PRESEEDED_ONLY'),
    CONSTRAINT chk_gs_published_realm_catalog_entry_namespace_resolution
        CHECK ((namespace_resolution = 'RESOLVED'
                    AND playable_state_namespace_id IS NOT NULL
                    AND playable_state_namespace_id <>
                        '00000000-0000-0000-0000-000000000000'::UUID)
            OR (namespace_resolution = 'AWAITING_LIFECYCLE_PROOF'
                    AND playable_state_namespace_id IS NULL)),
    CONSTRAINT chk_gs_published_realm_catalog_entry_isolated_lifecycle_gate
        CHECK (NOT (state_scope = 'ISOLATED' AND namespace_resolution = 'RESOLVED')),
    CONSTRAINT chk_gs_published_realm_catalog_entry_selectors
        CHECK (world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
            AND realm_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT chk_gs_published_realm_catalog_entry_digests
        CHECK (policy_digest ~ '^sha256:[0-9a-f]{64}$'
            AND octet_length(policy_json) BETWEEN 1 AND 4096),
    CONSTRAINT chk_gs_published_realm_catalog_entry_display
        CHECK (char_length(world_display_name) BETWEEN 1 AND 128
            AND char_length(realm_display_name) BETWEEN 1 AND 128)
);

CREATE TABLE gameplay_published_tenant_shared_playable_state_namespace (
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_published_tenant_shared_namespace
        PRIMARY KEY (target_namespace, canonical_tenant_id),
    CONSTRAINT uq_gs_published_tenant_shared_namespace_id
        UNIQUE (playable_state_namespace_id),
    CONSTRAINT fk_gs_published_tenant_shared_namespace_association
        FOREIGN KEY (target_namespace, canonical_tenant_id)
        REFERENCES game_session_retained_tenant_association
            (target_namespace, canonical_tenant_id),
    CONSTRAINT chk_gs_published_tenant_shared_namespace_identity
        CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND playable_state_namespace_id <>
                '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_gs_published_tenant_shared_namespace_namespace
        CHECK (octet_length(target_namespace) BETWEEN 1 AND 63
            AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$')
);

-- Completeness and tenant-wide realmSlug uniqueness are checked at commit after all rows in the
-- bounded policy set have been inserted. No truncated prefix can become a committed snapshot.
-- [jooq ignore start]
CREATE FUNCTION validate_gameplay_published_realm_catalog_set()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expected_count INTEGER;
    actual_count INTEGER;
    public_count INTEGER;
BEGIN
    SELECT snapshot.policy_count
      INTO expected_count
      FROM gameplay_published_realm_catalog_snapshot snapshot
     WHERE snapshot.target_namespace = NEW.target_namespace
       AND snapshot.tenant_id = NEW.tenant_id
       AND snapshot.catalog_revision = NEW.catalog_revision;

    SELECT COUNT(*)::INTEGER,
           COUNT(*) FILTER (WHERE entry.visible AND entry.public_production)::INTEGER
      INTO actual_count, public_count
      FROM gameplay_published_realm_catalog_entry entry
     WHERE entry.target_namespace = NEW.target_namespace
       AND entry.tenant_id = NEW.tenant_id
       AND entry.catalog_revision = NEW.catalog_revision;

    IF expected_count IS NULL OR actual_count <> expected_count OR public_count <> 1 THEN
        RAISE EXCEPTION 'published realm catalog snapshot is incomplete or ambiguous'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER gameplay_published_realm_catalog_complete_set
AFTER INSERT ON gameplay_published_realm_catalog_entry
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_gameplay_published_realm_catalog_set();

CREATE CONSTRAINT TRIGGER gameplay_published_realm_catalog_header_complete_set
AFTER INSERT ON gameplay_published_realm_catalog_snapshot
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_gameplay_published_realm_catalog_set();

CREATE FUNCTION reject_gameplay_published_realm_catalog_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Game Session published realm catalog evidence is immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER gameplay_published_realm_catalog_snapshot_immutable
BEFORE UPDATE OR DELETE ON gameplay_published_realm_catalog_snapshot
FOR EACH ROW EXECUTE FUNCTION reject_gameplay_published_realm_catalog_mutation();
CREATE TRIGGER gameplay_published_realm_catalog_snapshot_no_truncate
BEFORE TRUNCATE ON gameplay_published_realm_catalog_snapshot
FOR EACH STATEMENT EXECUTE FUNCTION reject_gameplay_published_realm_catalog_mutation();
CREATE TRIGGER gameplay_published_realm_catalog_identity_immutable
BEFORE UPDATE OR DELETE ON gameplay_published_realm_catalog_identity
FOR EACH ROW EXECUTE FUNCTION reject_gameplay_published_realm_catalog_mutation();
CREATE TRIGGER gameplay_published_realm_catalog_identity_no_truncate
BEFORE TRUNCATE ON gameplay_published_realm_catalog_identity
FOR EACH STATEMENT EXECUTE FUNCTION reject_gameplay_published_realm_catalog_mutation();
CREATE TRIGGER gameplay_published_realm_catalog_entry_immutable
BEFORE UPDATE OR DELETE ON gameplay_published_realm_catalog_entry
FOR EACH ROW EXECUTE FUNCTION reject_gameplay_published_realm_catalog_mutation();
CREATE TRIGGER gameplay_published_realm_catalog_entry_no_truncate
BEFORE TRUNCATE ON gameplay_published_realm_catalog_entry
FOR EACH STATEMENT EXECUTE FUNCTION reject_gameplay_published_realm_catalog_mutation();
CREATE TRIGGER gameplay_published_tenant_shared_namespace_immutable
BEFORE UPDATE OR DELETE ON gameplay_published_tenant_shared_playable_state_namespace
FOR EACH ROW EXECUTE FUNCTION reject_gameplay_published_realm_catalog_mutation();
CREATE TRIGGER gameplay_published_tenant_shared_namespace_no_truncate
BEFORE TRUNCATE ON gameplay_published_tenant_shared_playable_state_namespace
FOR EACH STATEMENT EXECUTE FUNCTION reject_gameplay_published_realm_catalog_mutation();
-- [jooq ignore stop]
