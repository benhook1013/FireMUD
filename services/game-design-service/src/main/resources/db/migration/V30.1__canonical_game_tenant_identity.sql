ALTER TABLE game
    ADD COLUMN canonical_tenant_id UUID,
    ADD COLUMN tenant_identity_provenance_kind VARCHAR(32),
    ADD COLUMN tenant_identity_source_game_id BIGINT,
    ADD COLUMN tenant_identity_source_legacy_tenant_id VARCHAR(36);

UPDATE game
SET canonical_tenant_id = gen_random_uuid(),
    tenant_identity_provenance_kind = 'RETAINED_GAME_V30',
    tenant_identity_source_game_id = id,
    tenant_identity_source_legacy_tenant_id = tenant_id;

ALTER TABLE game ALTER COLUMN canonical_tenant_id SET NOT NULL;
ALTER TABLE game ALTER COLUMN tenant_identity_provenance_kind SET NOT NULL;
ALTER TABLE game ALTER COLUMN tenant_identity_source_game_id SET NOT NULL;
ALTER TABLE game ALTER COLUMN tenant_identity_source_legacy_tenant_id SET NOT NULL;
ALTER TABLE game ADD CONSTRAINT uq_game_canonical_tenant_id UNIQUE (canonical_tenant_id);
ALTER TABLE game ADD CONSTRAINT chk_game_tenant_identity_provenance_kind
    CHECK (tenant_identity_provenance_kind IN ('RETAINED_GAME_V30', 'NEW_GAME_ROW'));
ALTER TABLE game ADD CONSTRAINT chk_game_tenant_identity_source_game
    CHECK (tenant_identity_source_game_id = id);
ALTER TABLE game ADD CONSTRAINT chk_game_tenant_identity_source_legacy_tenant
    CHECK (tenant_identity_source_legacy_tenant_id = tenant_id);

-- [jooq ignore start]
ALTER TABLE game
    ALTER COLUMN canonical_tenant_id SET DEFAULT gen_random_uuid();
-- [jooq ignore stop]

-- [jooq ignore start]
CREATE FUNCTION enforce_game_tenant_identity() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
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
