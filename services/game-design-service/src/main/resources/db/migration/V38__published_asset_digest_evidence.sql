ALTER TABLE published_release_bundle
    ADD COLUMN manifest_schema_version INTEGER,
    ADD COLUMN artifact_digests_json TEXT,
    ADD CONSTRAINT chk_published_asset_digest_evidence_shape CHECK (
        (manifest_schema_version IS NULL AND artifact_digests_json IS NULL)
        OR (manifest_schema_version IS NOT NULL AND manifest_schema_version = 1
            AND artifact_digests_json IS NOT NULL)
    );

-- Existing immutable release rows remain explicitly without complete asset proof.
-- V35.2's INSERT/UPDATE/DELETE guard also protects these added attestation fields.
-- Do not backfill retained evidence from mutable assets or object-store listings.

ALTER TABLE version_asset_artifact
    ADD COLUMN manifest_schema_version INTEGER,
    ADD COLUMN artifact_digests_json TEXT,
    ADD COLUMN published_object_proofs_json TEXT,
    ADD COLUMN candidate_snapshot_version_id BIGINT,
    ADD CONSTRAINT chk_asset_export_candidate_evidence CHECK (
        (manifest_schema_version IS NULL AND artifact_digests_json IS NULL
         AND published_object_proofs_json IS NULL AND candidate_snapshot_version_id IS NULL)
        OR (manifest_schema_version IS NOT NULL AND manifest_schema_version = 1
            AND artifact_digests_json IS NOT NULL
            AND published_object_proofs_json IS NOT NULL
            AND candidate_snapshot_version_id IS NOT NULL AND manifest_hash IS NOT NULL
            AND candidate_snapshot_version_id = version_id
            AND manifest_hash ~ '^sha256:[0-9a-f]{64}$')
    ),
    ADD CONSTRAINT fk_asset_export_candidate_snapshot
        FOREIGN KEY (tenant_id, candidate_snapshot_version_id)
        REFERENCES version_asset_export_snapshot (tenant_id, version_id);

-- [jooq ignore start]
CREATE FUNCTION guard_asset_export_candidate_evidence() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.manifest_schema_version IS NOT NULL THEN
            RAISE EXCEPTION 'ASSET_EXPORT_CANDIDATE_RETENTION_REQUIRED';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.manifest_schema_version IS NOT NULL AND (
        NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.version_id IS DISTINCT FROM OLD.version_id
        OR NEW.exported_version_number IS DISTINCT FROM OLD.exported_version_number
        OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
        OR NEW.manifest_schema_version IS DISTINCT FROM OLD.manifest_schema_version
        OR NEW.artifact_digests_json IS DISTINCT FROM OLD.artifact_digests_json
        OR NEW.published_object_proofs_json IS DISTINCT FROM OLD.published_object_proofs_json
        OR NEW.candidate_snapshot_version_id IS DISTINCT FROM OLD.candidate_snapshot_version_id
        OR NEW.exported_manifest_asset_keys_json IS DISTINCT FROM OLD.exported_manifest_asset_keys_json
    ) THEN
        RAISE EXCEPTION 'ASSET_EXPORT_CANDIDATE_IMMUTABLE';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_asset_export_candidate_immutable
    BEFORE UPDATE OR DELETE ON version_asset_artifact
    FOR EACH ROW EXECUTE FUNCTION guard_asset_export_candidate_evidence();

CREATE FUNCTION guard_asset_export_candidate_truncate() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM version_asset_artifact WHERE manifest_schema_version IS NOT NULL) THEN
        RAISE EXCEPTION 'ASSET_EXPORT_CANDIDATE_RETENTION_REQUIRED';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_asset_export_candidate_no_truncate
    BEFORE TRUNCATE ON version_asset_artifact
    FOR EACH STATEMENT EXECUTE FUNCTION guard_asset_export_candidate_truncate();
-- [jooq ignore stop]
