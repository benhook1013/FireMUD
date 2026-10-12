-- V37's export-item guard checked the mapped source row's type and size but
-- did not prove that content_hash described the retained source bytes.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION guard_version_asset_export_item() RETURNS trigger AS $$
DECLARE
    source_type VARCHAR(100);
    source_hash VARCHAR(71);
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

    SELECT a.content_type,
           'sha256:' || encode(sha256(a.data), 'hex'),
           octet_length(a.data)::BIGINT
      INTO source_type, source_hash, source_size
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
        OR source_hash IS DISTINCT FROM NEW.content_hash
        OR source_size IS DISTINCT FROM NEW.byte_size THEN
        RAISE EXCEPTION 'version asset export item source metadata does not match game_assets'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]
