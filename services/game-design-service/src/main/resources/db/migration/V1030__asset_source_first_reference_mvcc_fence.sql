-- A row lock alone does not invalidate a pre-reference REPEATABLE READ snapshot.
-- Create a new asset row version in the same transaction that first retains its bytes.
-- All values remain identical, so existing identity and retained-byte guards still apply.
-- [jooq ignore start]
CREATE FUNCTION fence_asset_source_reference_mvcc() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.operation_kind = 'UPSERT' THEN
        UPDATE game_assets asset
           SET data = asset.data
          FROM version owner
         WHERE owner.canonical_tenant_id = NEW.canonical_tenant_id
           AND owner.canonical_version_id = NEW.canonical_version_id
           AND asset.id = NEW.asset_id
           AND asset.tenant_id = owner.tenant_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'asset source MVCC fence requires exact tenant-qualified asset row'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- PostgreSQL orders same-kind triggers by name: each fence follows its canonical revision guard,
-- which has already admitted and locked the exact asset and verified its immutable evidence.
CREATE TRIGGER ordinary_asset_revision_mvcc_fence
    BEFORE INSERT ON game_design_asset_source_revision
    FOR EACH ROW EXECUTE FUNCTION fence_asset_source_reference_mvcc();

CREATE TRIGGER branding_asset_revision_mvcc_fence
    BEFORE INSERT ON game_design_branding_source_revision
    FOR EACH ROW EXECUTE FUNCTION fence_asset_source_reference_mvcc();
-- [jooq ignore stop]
