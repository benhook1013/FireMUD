ALTER TABLE version
    ADD COLUMN canonical_version_id UUID,
    ADD COLUMN canonical_tenant_id UUID,
    ADD COLUMN identity_source_game_row_id BIGINT,
    ADD COLUMN identity_source_game_tenant_key VARCHAR(36),
    ADD COLUMN identity_source_provenance_kind VARCHAR(32);

ALTER TABLE version
    ADD CONSTRAINT uq_version_canonical_version_id UNIQUE (canonical_version_id),
    ADD CONSTRAINT fk_version_identity_source_game
        FOREIGN KEY (identity_source_game_row_id) REFERENCES game(id),
    ADD CONSTRAINT chk_version_canonical_identity_shape CHECK (
        (canonical_version_id IS NULL
            AND canonical_tenant_id IS NULL
            AND identity_source_game_row_id IS NULL
            AND identity_source_game_tenant_key IS NULL
            AND identity_source_provenance_kind IS NULL)
        OR
        (canonical_version_id IS NOT NULL
            AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_tenant_id IS NOT NULL
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND identity_source_game_row_id IS NOT NULL
            AND identity_source_game_row_id > 0
            AND identity_source_game_tenant_key IS NOT NULL
            AND char_length(identity_source_game_tenant_key) BETWEEN 1 AND 36
            AND identity_source_game_tenant_key !~ '^[[:space:]]*$'
            AND identity_source_provenance_kind IS NOT NULL
            AND identity_source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V30'))
    );

-- [jooq ignore start]
CREATE FUNCTION enforce_version_canonical_identity() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.canonical_version_id IS NOT NULL THEN
            RAISE EXCEPTION 'canonical Version identity is immutable'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.canonical_version_id IS NULL
            OR NEW.canonical_tenant_id IS NULL
            OR NEW.identity_source_game_row_id IS NULL
            OR NEW.identity_source_game_tenant_key IS NULL
            OR NEW.identity_source_provenance_kind IS NULL THEN
            RAISE EXCEPTION 'new Version rows require an owner-issued canonical identity'
                USING ERRCODE = 'check_violation';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM game g
            WHERE g.id = NEW.identity_source_game_row_id
              AND g.tenant_id = NEW.identity_source_game_tenant_key
              AND NEW.tenant_id = g.tenant_id
              AND g.canonical_tenant_id = NEW.canonical_tenant_id
              AND g.tenant_identity_provenance_kind = NEW.identity_source_provenance_kind
              AND g.tenant_identity_source_game_id = g.id
              AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
        ) THEN
            RAISE EXCEPTION 'canonical Version identity source does not match its exact game row'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.canonical_version_id IS NULL THEN
        IF NEW.canonical_version_id IS NOT NULL
            OR NEW.canonical_tenant_id IS NOT NULL
            OR NEW.identity_source_game_row_id IS NOT NULL
            OR NEW.identity_source_game_tenant_key IS NOT NULL
            OR NEW.identity_source_provenance_kind IS NOT NULL THEN
            RAISE EXCEPTION 'retained Version rows cannot be assigned a canonical identity'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id
        OR NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
        OR NEW.identity_source_game_row_id IS DISTINCT FROM OLD.identity_source_game_row_id
        OR NEW.identity_source_game_tenant_key IS DISTINCT FROM OLD.identity_source_game_tenant_key
        OR NEW.identity_source_provenance_kind IS DISTINCT FROM OLD.identity_source_provenance_kind THEN
        RAISE EXCEPTION 'canonical Version identity and its tenant source are immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_version_canonical_identity_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON version
    FOR EACH ROW
    EXECUTE FUNCTION enforce_version_canonical_identity();

CREATE FUNCTION reject_version_canonical_identity_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM version WHERE canonical_version_id IS NOT NULL) THEN
        RAISE EXCEPTION 'canonical Version identities cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_version_canonical_identity_no_truncate
    BEFORE TRUNCATE ON version
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_version_canonical_identity_truncate();
-- [jooq ignore stop]
