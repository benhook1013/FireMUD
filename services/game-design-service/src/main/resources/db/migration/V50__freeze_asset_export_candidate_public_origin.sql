ALTER TABLE version_asset_artifact
    ADD COLUMN candidate_public_base_url TEXT,
    ADD CONSTRAINT chk_asset_export_candidate_public_origin CHECK (
        candidate_public_base_url IS NULL
        OR (manifest_schema_version IS NOT NULL
            AND candidate_public_base_url ~ '^https://[^/?#@[:space:]]+/assets$')
    );

-- Retained V38 candidates have no proven origin and remain explicitly incomplete for replay.
-- New candidates must freeze the approved delivery origin in the same candidate transition.
-- Do not backfill from mutable configuration.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION guard_asset_export_candidate_evidence() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.manifest_schema_version IS NOT NULL THEN
            RAISE EXCEPTION 'ASSET_EXPORT_CANDIDATE_RETENTION_REQUIRED';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.manifest_schema_version IS NOT NULL
       AND NEW.candidate_public_base_url IS NULL THEN
        RAISE EXCEPTION 'ASSET_EXPORT_CANDIDATE_ORIGIN_REQUIRED';
    END IF;
    IF TG_OP = 'UPDATE'
       AND OLD.manifest_schema_version IS NULL AND NEW.manifest_schema_version IS NOT NULL
       AND NEW.candidate_public_base_url IS NULL THEN
        RAISE EXCEPTION 'ASSET_EXPORT_CANDIDATE_ORIGIN_REQUIRED';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.manifest_schema_version IS NOT NULL AND (
        NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.version_id IS DISTINCT FROM OLD.version_id
        OR NEW.exported_version_number IS DISTINCT FROM OLD.exported_version_number
        OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
        OR NEW.manifest_schema_version IS DISTINCT FROM OLD.manifest_schema_version
        OR NEW.artifact_digests_json IS DISTINCT FROM OLD.artifact_digests_json
        OR NEW.published_object_proofs_json IS DISTINCT FROM OLD.published_object_proofs_json
        OR NEW.candidate_snapshot_version_id IS DISTINCT FROM OLD.candidate_snapshot_version_id
        OR NEW.candidate_public_base_url IS DISTINCT FROM OLD.candidate_public_base_url
        OR NEW.exported_manifest_asset_keys_json IS DISTINCT FROM OLD.exported_manifest_asset_keys_json
    ) THEN
        RAISE EXCEPTION 'ASSET_EXPORT_CANDIDATE_IMMUTABLE';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_asset_export_candidate_origin_insert
    BEFORE INSERT ON version_asset_artifact
    FOR EACH ROW EXECUTE FUNCTION guard_asset_export_candidate_evidence();
-- [jooq ignore stop]
