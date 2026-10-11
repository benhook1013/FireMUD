ALTER TABLE version
    ADD CONSTRAINT uq_version_release_canonical_identity
        UNIQUE (id, canonical_tenant_id, canonical_version_id);

ALTER TABLE published_release_bundle
    ADD COLUMN canonical_tenant_id UUID,
    ADD COLUMN canonical_version_id UUID,
    ADD CONSTRAINT chk_published_release_bundle_canonical_identity CHECK (
        (canonical_tenant_id IS NULL AND canonical_version_id IS NULL)
        OR
        (canonical_tenant_id IS NOT NULL
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_version_id IS NOT NULL
            AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID)
    ),
    ADD CONSTRAINT fk_published_release_bundle_canonical_version
        FOREIGN KEY (version_id, canonical_tenant_id, canonical_version_id)
        REFERENCES version (id, canonical_tenant_id, canonical_version_id);

-- [jooq ignore start]
CREATE FUNCTION enforce_published_release_bundle_immutability() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'published release bundle metadata is retained'
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.canonical_tenant_id IS NULL
            OR NEW.canonical_version_id IS NULL
            OR NEW.canonical_tenant_id = '00000000-0000-0000-0000-000000000000'::UUID
            OR NEW.canonical_version_id = '00000000-0000-0000-0000-000000000000'::UUID THEN
            RAISE EXCEPTION 'new published release bundles require a non-nil canonical identity'
                USING ERRCODE = 'check_violation';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM version v
            JOIN game g
              ON g.id = v.identity_source_game_row_id
             AND g.tenant_id = v.identity_source_game_tenant_key
            WHERE v.id = NEW.version_id
              AND v.tenant_id = NEW.tenant_id
              AND v.canonical_tenant_id = NEW.canonical_tenant_id
              AND v.canonical_version_id = NEW.canonical_version_id
              AND v.identity_source_game_row_id > 0
              AND v.identity_source_game_tenant_key = v.tenant_id
              AND v.identity_source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29')
              AND g.tenant_id = v.tenant_id
              AND g.canonical_tenant_id = v.canonical_tenant_id
              AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind
              AND g.tenant_identity_source_game_id = g.id
              AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
        ) THEN
            RAISE EXCEPTION 'published release bundle identity does not match its exact Version and Game source'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'published release bundle attestation is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_published_release_bundle_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON published_release_bundle
    FOR EACH ROW
    EXECUTE FUNCTION enforce_published_release_bundle_immutability();

CREATE FUNCTION reject_published_release_bundle_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM published_release_bundle) THEN
        RAISE EXCEPTION 'published release bundle metadata cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_published_release_bundle_no_truncate
    BEFORE TRUNCATE ON published_release_bundle
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_published_release_bundle_truncate();
-- [jooq ignore stop]
