ALTER TABLE game
    ADD COLUMN canonical_tenant_id UUID,
    ADD COLUMN tenant_identity_provenance_kind VARCHAR(32),
    ADD COLUMN tenant_identity_source_game_id BIGINT,
    ADD COLUMN tenant_identity_source_legacy_tenant_id VARCHAR(36);

-- A UUID is issued only for an exact, nonblank, bounded owner key. Rows outside
-- that shape remain unproven and cannot be resolved by the runtime read.
UPDATE game
SET canonical_tenant_id = gen_random_uuid(),
    tenant_identity_provenance_kind = 'RETAINED_GAME_V29',
    tenant_identity_source_game_id = id,
    tenant_identity_source_legacy_tenant_id = tenant_id
WHERE char_length(tenant_id) BETWEEN 1 AND 36
  AND tenant_id ~ '[^[:space:]]';

ALTER TABLE game
    ADD CONSTRAINT uq_game_canonical_tenant_id UNIQUE (canonical_tenant_id),
    ADD CONSTRAINT chk_game_tenant_identity_provenance CHECK (
        (canonical_tenant_id IS NULL
            AND tenant_identity_provenance_kind IS NULL
            AND tenant_identity_source_game_id IS NULL
            AND tenant_identity_source_legacy_tenant_id IS NULL)
        OR
        (canonical_tenant_id IS NOT NULL
            AND tenant_identity_provenance_kind IS NOT NULL
            AND tenant_identity_provenance_kind IN ('RETAINED_GAME_V29', 'NEW_GAME_ROW')
            AND tenant_identity_source_game_id IS NOT NULL
            AND tenant_identity_source_game_id = id
            AND tenant_identity_source_legacy_tenant_id IS NOT NULL
            AND tenant_identity_source_legacy_tenant_id = tenant_id
            AND char_length(tenant_identity_source_legacy_tenant_id) BETWEEN 1 AND 36
            AND tenant_identity_source_legacy_tenant_id ~ '[^[:space:]]')
    );

-- [jooq ignore start]
CREATE FUNCTION enforce_game_tenant_identity() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.canonical_tenant_id := gen_random_uuid();
        NEW.tenant_identity_provenance_kind := 'NEW_GAME_ROW';
        NEW.tenant_identity_source_game_id := NEW.id;
        NEW.tenant_identity_source_legacy_tenant_id := NEW.tenant_id;
        RETURN NEW;
    END IF;

    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        RAISE EXCEPTION 'game legacy tenant_id is immutable after canonical tenant identity issuance'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
        OR NEW.tenant_identity_provenance_kind IS DISTINCT FROM OLD.tenant_identity_provenance_kind
        OR NEW.tenant_identity_source_game_id IS DISTINCT FROM OLD.tenant_identity_source_game_id
        OR NEW.tenant_identity_source_legacy_tenant_id IS DISTINCT FROM OLD.tenant_identity_source_legacy_tenant_id THEN
        RAISE EXCEPTION 'game canonical tenant identity and provenance are immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_game_tenant_identity_immutable
    BEFORE INSERT OR UPDATE ON game
    FOR EACH ROW
    EXECUTE FUNCTION enforce_game_tenant_identity();
-- [jooq ignore stop]
