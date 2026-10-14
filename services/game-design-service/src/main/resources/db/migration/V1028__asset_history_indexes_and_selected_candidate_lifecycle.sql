-- Support asset-row byte guards and foreign-key checks across retained source history.
CREATE INDEX idx_game_design_asset_source_revision_asset_id
    ON game_design_asset_source_revision (asset_id) WHERE asset_id IS NOT NULL;

CREATE INDEX idx_game_design_branding_source_revision_asset_id
    ON game_design_branding_source_revision (asset_id) WHERE asset_id IS NOT NULL;

-- Validate the original selected export workflow when evidence is first bound.
-- Later lifecycle workflows may change last_workflow_id while all candidate evidence stays frozen.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION guard_asset_export_candidate_evidence() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.manifest_schema_version IS NOT NULL THEN
            RAISE EXCEPTION 'ASSET_EXPORT_CANDIDATE_RETENTION_REQUIRED';
        END IF;
        RETURN OLD;
    END IF;

    IF NEW.manifest_schema_version IS NOT NULL
       AND NEW.selected_candidate_version_id IS NULL
       AND (TG_OP = 'INSERT' OR OLD.manifest_schema_version IS NULL)
       AND NEW.candidate_public_base_url IS NULL THEN
        RAISE EXCEPTION 'ASSET_EXPORT_CANDIDATE_ORIGIN_REQUIRED';
    END IF;

    IF NEW.selected_candidate_version_id IS NOT NULL
       AND (TG_OP = 'INSERT' OR OLD.selected_candidate_version_id IS NULL)
       AND NOT EXISTS (
        SELECT 1
        FROM version_asset_export_candidate c
        JOIN version v ON v.tenant_id = c.tenant_id AND v.id = c.version_id
        WHERE c.tenant_id = NEW.tenant_id
          AND c.version_id = NEW.selected_candidate_version_id
          AND c.request_digest = NEW.selected_candidate_request_digest
          AND c.workflow_id = NEW.last_workflow_id
          AND c.canonical_tenant_id = v.canonical_tenant_id
          AND c.canonical_version_id = v.canonical_version_id
          AND v.version_number = NEW.exported_version_number
          AND c.manifest_schema_version = NEW.manifest_schema_version
          AND c.manifest_hash = NEW.manifest_hash
          AND c.manifest_hash = 'sha256:' || encode(sha256(c.manifest_bytes), 'hex')
          AND c.artifact_digests_json::JSONB = NEW.artifact_digests_json::JSONB
          AND c.required_usage_keys_json::JSONB = NEW.exported_manifest_asset_keys_json::JSONB
          AND NEW.published_object_proofs_json::JSONB = (
              SELECT jsonb_agg(
                  jsonb_build_object('immutableObjectKey', proof.object_key,
                                     'contentDigest', proof.content_digest)
                  ORDER BY proof.object_key COLLATE "C", proof.content_digest COLLATE "C")
              FROM (
                  SELECT artifact->>'immutableObjectKey' AS object_key,
                         artifact->>'contentDigest' AS content_digest
                  FROM jsonb_array_elements(c.artifact_digests_json::JSONB) AS artifacts(artifact)
                  UNION
                  SELECT 'manifests/sha256/' || substring(c.manifest_hash FROM 8), c.manifest_hash
              ) proof
          )
    ) THEN
        RAISE EXCEPTION 'ASSET_EXPORT_SELECTED_CANDIDATE_CONFLICT' USING ERRCODE = 'check_violation';
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
        OR NEW.selected_candidate_version_id IS DISTINCT FROM OLD.selected_candidate_version_id
        OR NEW.selected_candidate_request_digest IS DISTINCT FROM OLD.selected_candidate_request_digest
        OR NEW.exported_manifest_asset_keys_json IS DISTINCT FROM OLD.exported_manifest_asset_keys_json
    ) THEN
        RAISE EXCEPTION 'ASSET_EXPORT_CANDIDATE_IMMUTABLE';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]
