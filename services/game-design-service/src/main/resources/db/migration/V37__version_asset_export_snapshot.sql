ALTER TABLE game
    ADD CONSTRAINT uq_game_tenant_id_id UNIQUE (tenant_id, id);

ALTER TABLE version
    ADD CONSTRAINT uq_version_tenant_id_id UNIQUE (tenant_id, id);

ALTER TABLE game_assets
    ADD CONSTRAINT uq_game_assets_tenant_id_id UNIQUE (tenant_id, id),
    ADD CONSTRAINT uq_game_assets_tenant_id_name UNIQUE (tenant_id, id, file_name);

CREATE TABLE version_asset (
    tenant_id VARCHAR(36) NOT NULL,
    version_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    usage_key VARCHAR(255) NOT NULL,
    usage_type VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_version_asset PRIMARY KEY (tenant_id, version_id, asset_id),
    CONSTRAINT uq_version_asset_usage UNIQUE (tenant_id, version_id, usage_key),
    CONSTRAINT uq_version_asset_exact UNIQUE (tenant_id, version_id, asset_id, usage_key),
    CONSTRAINT fk_version_asset_version FOREIGN KEY (tenant_id, version_id)
        REFERENCES version (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_version_asset_asset FOREIGN KEY (tenant_id, asset_id)
        REFERENCES game_assets (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_version_asset_asset_name FOREIGN KEY (tenant_id, asset_id, usage_key)
        REFERENCES game_assets (tenant_id, id, file_name) ON DELETE RESTRICT,
    CONSTRAINT chk_version_asset_usage_key CHECK (char_length(usage_key) > 0)
);

CREATE TABLE version_asset_export_snapshot (
    tenant_id VARCHAR(36) NOT NULL,
    version_id BIGINT NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    captured_version_state_epoch BIGINT NOT NULL,
    item_count INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_version_asset_export_snapshot PRIMARY KEY (tenant_id, version_id),
    CONSTRAINT fk_version_asset_snapshot_version FOREIGN KEY (tenant_id, version_id)
        REFERENCES version (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT chk_version_asset_snapshot_values CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND version_number > 0
        AND captured_version_state_epoch > 0
        AND item_count >= 0
    )
);

CREATE TABLE version_asset_export_item (
    tenant_id VARCHAR(36) NOT NULL,
    version_id BIGINT NOT NULL,
    usage_key VARCHAR(255) NOT NULL,
    asset_id BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    content_hash VARCHAR(71) NOT NULL,
    byte_size BIGINT NOT NULL,
    CONSTRAINT pk_version_asset_export_item PRIMARY KEY (tenant_id, version_id, usage_key),
    CONSTRAINT uq_version_asset_export_item_asset UNIQUE (tenant_id, version_id, asset_id),
    CONSTRAINT fk_version_asset_export_item_snapshot FOREIGN KEY (tenant_id, version_id)
        REFERENCES version_asset_export_snapshot (tenant_id, version_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_version_asset_export_item_mapping FOREIGN KEY
        (tenant_id, version_id, asset_id, usage_key)
        REFERENCES version_asset (tenant_id, version_id, asset_id, usage_key)
        ON DELETE RESTRICT,
    CONSTRAINT chk_version_asset_export_item_hash CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_version_asset_export_item_size CHECK (byte_size >= 0)
);

-- [jooq ignore start]
CREATE FUNCTION guard_version_asset_mapping() RETURNS trigger AS $$
DECLARE
    target_tenant_id VARCHAR(36);
    target_version_id BIGINT;
    target_state VARCHAR(32);
    target_epoch BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.version_id IS DISTINCT FROM OLD.version_id
        OR NEW.asset_id IS DISTINCT FROM OLD.asset_id
        OR NEW.usage_key IS DISTINCT FROM OLD.usage_key
    ) THEN
        RAISE EXCEPTION 'version asset association identity is immutable; create a new Draft mapping'
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        target_tenant_id := OLD.tenant_id;
        target_version_id := OLD.version_id;
    ELSE
        target_tenant_id := NEW.tenant_id;
        target_version_id := NEW.version_id;
    END IF;

    SELECT v.version_state, v.version_state_epoch
      INTO target_state, target_epoch
    FROM version v
    JOIN game g
      ON g.id = v.identity_source_game_row_id
     AND g.tenant_id = v.identity_source_game_tenant_key
     AND g.tenant_id = v.tenant_id
     AND g.canonical_tenant_id = v.canonical_tenant_id
     AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind
     AND g.tenant_identity_source_game_id = g.id
     AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
    WHERE v.tenant_id = target_tenant_id AND v.id = target_version_id
      AND v.canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
      AND v.canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
    FOR UPDATE OF v;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'version asset mapping requires the exact canonical tenant Version source'
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF target_state <> 'DRAFT' OR target_epoch IS NULL OR target_epoch <= 0 THEN
        RAISE EXCEPTION 'version asset mappings can change only while the Version is DRAFT'
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (
        SELECT 1 FROM version_asset_export_snapshot s
        WHERE s.tenant_id = target_tenant_id AND s.version_id = target_version_id
    ) THEN
        RAISE EXCEPTION 'version asset mappings are frozen by the durable export snapshot'
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP <> 'DELETE' AND NOT EXISTS (
        SELECT 1 FROM game_assets a
        WHERE a.tenant_id = NEW.tenant_id
          AND a.id = NEW.asset_id
          AND a.file_name = NEW.usage_key
    ) THEN
        RAISE EXCEPTION 'version asset usage key must equal the exact source file name'
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_version_asset_mapping_guard
    BEFORE INSERT OR UPDATE OR DELETE ON version_asset
    FOR EACH ROW
    EXECUTE FUNCTION guard_version_asset_mapping();

CREATE FUNCTION guard_version_asset_export_item() RETURNS trigger AS $$
DECLARE
    source_type VARCHAR(100);
    source_size BIGINT;
    target_state VARCHAR(32);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        IF EXISTS (
            SELECT 1 FROM version_asset_export_snapshot s
            WHERE s.tenant_id = OLD.tenant_id AND s.version_id = OLD.version_id
        ) THEN
            RAISE EXCEPTION 'version asset export snapshot items are immutable'
                USING ERRCODE = 'check_violation';
        END IF;
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    SELECT version_state INTO target_state
    FROM version
    WHERE tenant_id = NEW.tenant_id AND id = NEW.version_id
    FOR UPDATE;
    IF NOT FOUND OR target_state <> 'DRAFT' THEN
        RAISE EXCEPTION 'version asset export items require the exact Draft Version row'
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (
        SELECT 1 FROM version_asset_export_snapshot s
        WHERE s.tenant_id = NEW.tenant_id AND s.version_id = NEW.version_id
    ) THEN
        RAISE EXCEPTION 'version asset export snapshot items are immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT a.content_type, octet_length(a.data)::BIGINT
      INTO source_type, source_size
    FROM game_assets a
    JOIN version_asset va
      ON va.tenant_id = a.tenant_id
     AND va.asset_id = a.id
     AND va.tenant_id = NEW.tenant_id
     AND va.version_id = NEW.version_id
     AND va.usage_key = NEW.usage_key
     AND va.asset_id = NEW.asset_id
    WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.asset_id;

    IF NOT FOUND OR source_type IS DISTINCT FROM NEW.content_type
        OR source_size IS DISTINCT FROM NEW.byte_size THEN
        RAISE EXCEPTION 'version asset export item source metadata does not match game_assets'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_version_asset_export_item_guard
    BEFORE INSERT OR UPDATE OR DELETE ON version_asset_export_item
    FOR EACH ROW
    EXECUTE FUNCTION guard_version_asset_export_item();

CREATE FUNCTION guard_version_asset_export_snapshot() RETURNS trigger AS $$
DECLARE
    source_version_number INTEGER;
    source_epoch BIGINT;
    source_state VARCHAR(32);
    source_canonical_tenant_id UUID;
    source_canonical_version_id UUID;
    item_rows BIGINT;
    mapping_rows BIGINT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'version asset export snapshot headers are immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT v.version_number,
           v.version_state_epoch,
           v.version_state,
           v.canonical_tenant_id,
           v.canonical_version_id
      INTO source_version_number,
           source_epoch,
           source_state,
           source_canonical_tenant_id,
           source_canonical_version_id
    FROM version v
    JOIN game g
      ON g.id = v.identity_source_game_row_id
     AND g.tenant_id = v.identity_source_game_tenant_key
     AND g.tenant_id = v.tenant_id
     AND g.canonical_tenant_id = v.canonical_tenant_id
     AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind
     AND g.tenant_identity_source_game_id = g.id
     AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
    WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.version_id
    FOR UPDATE OF v;

    IF NOT FOUND
        OR source_state <> 'DRAFT'
        OR source_epoch IS NULL OR source_epoch <= 0
        OR source_version_number IS DISTINCT FROM NEW.version_number
        OR source_epoch IS DISTINCT FROM NEW.captured_version_state_epoch
        OR source_canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR source_canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
        OR source_canonical_tenant_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR source_canonical_version_id = '00000000-0000-0000-0000-000000000000'::UUID THEN
        RAISE EXCEPTION 'version asset export snapshot requires the exact canonical Draft Version source'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT count(*) INTO item_rows
    FROM version_asset_export_item i
    WHERE i.tenant_id = NEW.tenant_id AND i.version_id = NEW.version_id;
    SELECT count(*) INTO mapping_rows
    FROM version_asset va
    WHERE va.tenant_id = NEW.tenant_id AND va.version_id = NEW.version_id;
    IF item_rows <> NEW.item_count OR mapping_rows <> NEW.item_count THEN
        RAISE EXCEPTION 'version asset export snapshot item count does not prove the complete mapping set'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_version_asset_export_snapshot_guard
    BEFORE INSERT OR UPDATE OR DELETE ON version_asset_export_snapshot
    FOR EACH ROW
    EXECUTE FUNCTION guard_version_asset_export_snapshot();

CREATE FUNCTION guard_frozen_game_asset_source() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM version_asset_export_item i
        WHERE i.tenant_id = OLD.tenant_id AND i.asset_id = OLD.id
    ) THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'game asset source rows are immutable while a frozen version snapshot references them'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
            OR NEW.file_name IS DISTINCT FROM OLD.file_name
            OR NEW.content_type IS DISTINCT FROM OLD.content_type
            OR NEW.data IS DISTINCT FROM OLD.data THEN
            RAISE EXCEPTION 'game asset source rows are immutable while a frozen version snapshot references them'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_game_assets_frozen_source
    BEFORE UPDATE OR DELETE ON game_assets
    FOR EACH ROW
    EXECUTE FUNCTION guard_frozen_game_asset_source();

CREATE FUNCTION reject_frozen_asset_publication_truncate() RETURNS trigger AS $$
BEGIN
    IF TG_TABLE_NAME = 'game_assets' AND EXISTS (
        SELECT 1 FROM version_asset_export_item
    ) THEN
        RAISE EXCEPTION 'game asset source rows are retained by frozen version snapshots'
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_TABLE_NAME IN ('version_asset', 'version_asset_export_item', 'version_asset_export_snapshot')
        AND EXISTS (SELECT 1 FROM version_asset_export_snapshot) THEN
        RAISE EXCEPTION 'frozen version asset publication evidence cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_game_assets_frozen_source_no_truncate
    BEFORE TRUNCATE ON game_assets
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_frozen_asset_publication_truncate();

CREATE TRIGGER trg_version_asset_mapping_no_truncate
    BEFORE TRUNCATE ON version_asset
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_frozen_asset_publication_truncate();

CREATE TRIGGER trg_version_asset_export_item_no_truncate
    BEFORE TRUNCATE ON version_asset_export_item
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_frozen_asset_publication_truncate();

CREATE TRIGGER trg_version_asset_export_snapshot_no_truncate
    BEFORE TRUNCATE ON version_asset_export_snapshot
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_frozen_asset_publication_truncate();
-- [jooq ignore stop]
