ALTER TABLE published_release_bundle
    ADD COLUMN published_release_bundle_ref VARCHAR(128);

ALTER TABLE published_release_bundle
    ADD CONSTRAINT ck_published_release_bundle_ref_nonblank
    CHECK (published_release_bundle_ref IS NULL OR BTRIM(published_release_bundle_ref) <> '');

CREATE UNIQUE INDEX uq_published_release_bundle_opaque_ref
    ON published_release_bundle(published_release_bundle_ref)
    WHERE published_release_bundle_ref IS NOT NULL;

-- Retained bundles intentionally remain NULL. New owner inserts must supply an identity, and
-- once assigned the opaque reference cannot be changed independently of the bundle.
-- [jooq ignore start]
CREATE FUNCTION enforce_published_release_bundle_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.published_release_bundle_ref IS NULL
            OR BTRIM(NEW.published_release_bundle_ref) = '' THEN
            RAISE EXCEPTION 'new published release bundles require an opaque reference';
        END IF;
    ELSE
        IF NEW.published_release_bundle_ref IS DISTINCT FROM OLD.published_release_bundle_ref THEN
            RAISE EXCEPTION 'published release bundle reference is immutable';
        END IF;
        IF OLD.published_release_bundle_ref IS NOT NULL AND (
            NEW.id IS DISTINCT FROM OLD.id
            OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
            OR NEW.version_id IS DISTINCT FROM OLD.version_id
            OR NEW.version_number IS DISTINCT FROM OLD.version_number
            OR NEW.attestation_schema_version IS DISTINCT FROM OLD.attestation_schema_version
            OR NEW.publish_workflow_id IS DISTINCT FROM OLD.publish_workflow_id
            OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
            OR NEW.generation_config_revision IS DISTINCT FROM OLD.generation_config_revision
            OR NEW.required_manifest_asset_keys_json
                IS DISTINCT FROM OLD.required_manifest_asset_keys_json
            OR NEW.participant_digests_json IS DISTINCT FROM OLD.participant_digests_json
            OR NEW.command_definitions_json IS DISTINCT FROM OLD.command_definitions_json
            OR NEW.script_only IS DISTINCT FROM OLD.script_only
            OR NEW.script_patch_version IS DISTINCT FROM OLD.script_patch_version
            OR NEW.published_at IS DISTINCT FROM OLD.published_at) THEN
            RAISE EXCEPTION 'published release bundle attestation is immutable';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_published_release_bundle_reference
    BEFORE INSERT OR UPDATE ON published_release_bundle
    FOR EACH ROW
    EXECUTE FUNCTION enforce_published_release_bundle_reference();
-- [jooq ignore end]
