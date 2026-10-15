-- Canonical creation identity is insert-born. Existing numeric lifecycle rows stay unmapped.
ALTER TABLE world_instance
    ADD COLUMN canonical_game_instance_id UUID,
    ADD COLUMN canonical_target_namespace VARCHAR(63),
    ADD COLUMN canonical_tenant_id UUID,
    ADD COLUMN canonical_world_slug VARCHAR(120),
    ADD COLUMN playable_state_namespace_id UUID,
    ADD COLUMN playable_state_scope VARCHAR(16),
    ADD COLUMN public_production BOOLEAN,
    ADD COLUMN playtest_lifecycle_id UUID,
    ADD COLUMN playtest_state_generation BIGINT,
    ADD COLUMN canonical_launch_binding_operation_id UUID;

ALTER TABLE world_instance
    ADD CONSTRAINT fk_world_instance_canonical_launch_binding
        FOREIGN KEY (canonical_launch_binding_operation_id)
        REFERENCES world_complete_launch_binding (binding_operation_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT uq_world_instance_canonical_game_instance
        UNIQUE (canonical_game_instance_id),
    ADD CONSTRAINT uq_world_instance_canonical_binding
        UNIQUE (canonical_launch_binding_operation_id),
    ADD CONSTRAINT uq_world_instance_canonical_association_fk
        UNIQUE (
            id,
            canonical_game_instance_id,
            canonical_target_namespace,
            canonical_tenant_id,
            canonical_world_slug,
            playable_state_namespace_id,
            playable_state_scope,
            public_production,
            control_plane_request_id,
            canonical_launch_binding_operation_id
        ),
    ADD CONSTRAINT ck_world_instance_canonical_creation_identity CHECK (
        (
            canonical_game_instance_id IS NULL
            AND canonical_target_namespace IS NULL
            AND canonical_tenant_id IS NULL
            AND canonical_world_slug IS NULL
            AND playable_state_namespace_id IS NULL
            AND playable_state_scope IS NULL
            AND public_production IS NULL
            AND playtest_lifecycle_id IS NULL
            AND playtest_state_generation IS NULL
            AND canonical_launch_binding_operation_id IS NULL
        )
        OR
        (
            canonical_game_instance_id IS NOT NULL
            AND canonical_target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND canonical_world_slug IS NOT NULL
            AND playable_state_namespace_id IS NOT NULL
            AND playable_state_scope IS NOT NULL
            AND public_production IS TRUE
            AND canonical_launch_binding_operation_id IS NOT NULL
            -- The current Game Session owner-read projection does not carry playtest identity.
            -- This foundation therefore admits only SHARED, non-playtest creation rows.
            AND playtest_lifecycle_id IS NULL
            AND playtest_state_generation IS NULL
            AND canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_target_namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
            AND octet_length(canonical_world_slug) BETWEEN 1 AND 120
            AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND playable_state_scope = 'SHARED'
            AND canonical_launch_binding_operation_id
                <> '00000000-0000-0000-0000-000000000000'::UUID
        )
    );

-- A canonical World version key must remain explicitly tied to its immutable UUID association.
ALTER TABLE world_authored_version_identity
    ADD CONSTRAINT uq_world_authored_version_identity_instance_binding
    UNIQUE (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        world_slug,
        canonical_version_id,
        local_tenant_key,
        local_version_key
    );

CREATE TABLE world_canonical_instance_association (
    schema_version SMALLINT NOT NULL,
    canonical_game_instance_id UUID NOT NULL,
    canonical_target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_world_slug VARCHAR(120) NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    playable_state_scope VARCHAR(16) NOT NULL,
    public_production BOOLEAN NOT NULL,
    playtest_lifecycle_id UUID,
    playtest_state_generation BIGINT,
    control_plane_request_id VARCHAR(128) NOT NULL,
    world_instance_id BIGINT NOT NULL,
    canonical_launch_binding_operation_id UUID NOT NULL,
    version_identity_operation_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    private_game_instance_key BIGINT NOT NULL,
    game_template_id BIGINT NOT NULL,
    launch_descriptor_id VARCHAR(64) NOT NULL,
    local_version_key BIGINT NOT NULL,
    script_patch_version VARCHAR(100),
    runtime_flags_json TEXT NOT NULL,
    generation_config_revision VARCHAR(128) NOT NULL,
    release_bundle_id BIGINT NOT NULL,
    published_release_bundle_ref VARCHAR(128) NOT NULL,
    version_state_epoch BIGINT NOT NULL,
    remap_set_id VARCHAR(64),
    intake_operation_id UUID NOT NULL,
    intake_request_id UUID NOT NULL,
    source_operation_id UUID NOT NULL,
    source_evidence_digest VARCHAR(71) NOT NULL,
    intake_request_digest VARCHAR(71) NOT NULL,
    intake_receipt_digest VARCHAR(71) NOT NULL,
    descriptor_request_digest VARCHAR(71) NOT NULL,
    descriptor_result_digest VARCHAR(71) NOT NULL,
    release_attestation_digest VARCHAR(71) NOT NULL,
    CONSTRAINT pk_world_canonical_instance_association
        PRIMARY KEY (canonical_game_instance_id),
    CONSTRAINT uq_world_canonical_instance_association_scoped
        UNIQUE (
            canonical_target_namespace,
            canonical_tenant_id,
            canonical_world_slug,
            playable_state_namespace_id,
            playable_state_scope,
            canonical_game_instance_id
        ),
    CONSTRAINT uq_world_canonical_instance_association_request
        UNIQUE (canonical_target_namespace, canonical_tenant_id, control_plane_request_id),
    CONSTRAINT uq_world_canonical_instance_association_row UNIQUE (world_instance_id),
    CONSTRAINT uq_world_canonical_instance_association_binding
        UNIQUE (canonical_launch_binding_operation_id),
    CONSTRAINT fk_world_canonical_instance_association_world_row
        FOREIGN KEY (
            world_instance_id,
            canonical_game_instance_id,
            canonical_target_namespace,
            canonical_tenant_id,
            canonical_world_slug,
            playable_state_namespace_id,
            playable_state_scope,
            public_production,
            control_plane_request_id,
            canonical_launch_binding_operation_id
        ) REFERENCES world_instance (
            id,
            canonical_game_instance_id,
            canonical_target_namespace,
            canonical_tenant_id,
            canonical_world_slug,
            playable_state_namespace_id,
            playable_state_scope,
            public_production,
            control_plane_request_id,
            canonical_launch_binding_operation_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_world_canonical_instance_association_launch_binding
        FOREIGN KEY (canonical_launch_binding_operation_id)
        REFERENCES world_complete_launch_binding (binding_operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_world_canonical_instance_association_version_identity
        FOREIGN KEY (
            version_identity_operation_id,
            canonical_target_namespace,
            canonical_tenant_id,
            canonical_world_slug,
            canonical_version_id,
            local_tenant_key,
            local_version_key
        ) REFERENCES world_authored_version_identity (
            operation_id,
            target_namespace,
            canonical_tenant_id,
            world_slug,
            canonical_version_id,
            local_tenant_key,
            local_version_key
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_world_canonical_instance_association_schema
        CHECK (schema_version = 1),
    CONSTRAINT ck_world_canonical_instance_association_ids CHECK (
        canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_launch_binding_operation_id
            <> '00000000-0000-0000-0000-000000000000'::UUID
        AND version_identity_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT ck_world_canonical_instance_association_scope CHECK (
        canonical_target_namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        AND canonical_world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        AND octet_length(canonical_world_slug) BETWEEN 1 AND 120
        AND playable_state_scope = 'SHARED'
        AND public_production IS TRUE
        AND length(btrim(control_plane_request_id)) BETWEEN 1 AND 128
        AND playtest_lifecycle_id IS NULL
        AND playtest_state_generation IS NULL
    ),
    CONSTRAINT ck_world_canonical_instance_association_owner_keys CHECK (
        world_instance_id > 0
        AND local_tenant_key > 0
        AND private_game_instance_key > 0
        AND game_template_id > 0
        AND local_version_key > 0
        AND release_bundle_id > 0
        AND version_state_epoch > 0
        AND length(btrim(launch_descriptor_id)) BETWEEN 1 AND 64
        AND length(btrim(runtime_flags_json)) > 0
        AND length(btrim(generation_config_revision)) BETWEEN 1 AND 128
        AND length(btrim(published_release_bundle_ref)) BETWEEN 1 AND 128
        AND (script_patch_version IS NULL OR length(btrim(script_patch_version)) BETWEEN 1 AND 100)
        AND (remap_set_id IS NULL OR length(btrim(remap_set_id)) BETWEEN 1 AND 64)
    ),
    CONSTRAINT ck_world_canonical_instance_association_digests CHECK (
        source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND intake_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND intake_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_result_digest ~ '^sha256:[0-9a-f]{64}$'
        AND release_attestation_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

-- [jooq ignore start]
CREATE FUNCTION world_validate_canonical_prepare_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    binding world_complete_launch_binding%ROWTYPE;
    version_identity world_authored_version_identity%ROWTYPE;
    descriptor JSONB;
    release_attestation JSONB;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.canonical_game_instance_id IS NOT NULL THEN
            RAISE EXCEPTION 'Canonical World instance creation identity is retained'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.canonical_game_instance_id IS NULL THEN
            IF NEW.canonical_game_instance_id IS NOT NULL
                OR NEW.canonical_target_namespace IS NOT NULL
                OR NEW.canonical_tenant_id IS NOT NULL
                OR NEW.canonical_world_slug IS NOT NULL
                OR NEW.playable_state_namespace_id IS NOT NULL
                OR NEW.playable_state_scope IS NOT NULL
                OR NEW.public_production IS NOT NULL
                OR NEW.playtest_lifecycle_id IS NOT NULL
                OR NEW.playtest_state_generation IS NOT NULL
                OR NEW.canonical_launch_binding_operation_id IS NOT NULL THEN
                RAISE EXCEPTION 'A retained World instance cannot acquire canonical creation identity'
                    USING ERRCODE = '55000';
            END IF;
            RETURN NEW;
        END IF;

        IF OLD.id IS DISTINCT FROM NEW.id
            OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
            OR OLD.game_instance_id IS DISTINCT FROM NEW.game_instance_id
            OR OLD.game_template_id IS DISTINCT FROM NEW.game_template_id
            OR OLD.control_plane_request_id IS DISTINCT FROM NEW.control_plane_request_id
            OR OLD.launch_descriptor_id IS DISTINCT FROM NEW.launch_descriptor_id
            OR OLD.version_id IS DISTINCT FROM NEW.version_id
            OR OLD.script_patch_version IS DISTINCT FROM NEW.script_patch_version
            OR OLD.runtime_flags_json IS DISTINCT FROM NEW.runtime_flags_json
            OR OLD.generation_config_revision IS DISTINCT FROM NEW.generation_config_revision
            OR OLD.release_bundle_id IS DISTINCT FROM NEW.release_bundle_id
            OR OLD.published_release_bundle_ref IS DISTINCT FROM NEW.published_release_bundle_ref
            OR OLD.version_state_epoch IS DISTINCT FROM NEW.version_state_epoch
            OR OLD.remap_set_id IS DISTINCT FROM NEW.remap_set_id
            OR OLD.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id
            OR OLD.canonical_target_namespace IS DISTINCT FROM NEW.canonical_target_namespace
            OR OLD.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
            OR OLD.canonical_world_slug IS DISTINCT FROM NEW.canonical_world_slug
            OR OLD.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
            OR OLD.playable_state_scope IS DISTINCT FROM NEW.playable_state_scope
            OR OLD.public_production IS DISTINCT FROM NEW.public_production
            OR OLD.playtest_lifecycle_id IS DISTINCT FROM NEW.playtest_lifecycle_id
            OR OLD.playtest_state_generation IS DISTINCT FROM NEW.playtest_state_generation
            OR OLD.canonical_launch_binding_operation_id
                IS DISTINCT FROM NEW.canonical_launch_binding_operation_id THEN
            RAISE EXCEPTION 'Canonical World instance creation and prepare inputs are immutable'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.canonical_game_instance_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT * INTO binding
    FROM world_complete_launch_binding
    WHERE binding_operation_id = NEW.canonical_launch_binding_operation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Canonical World instance requires its exact retained launch binding'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO version_identity
    FROM world_authored_version_identity
    WHERE target_namespace = NEW.canonical_target_namespace
        AND canonical_tenant_id = NEW.canonical_tenant_id
        AND world_slug = NEW.canonical_world_slug
        AND canonical_version_id = binding.canonical_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Canonical World instance requires its exact UUID-to-private-Version association'
            USING ERRCODE = '23514';
    END IF;

    descriptor := binding.descriptor_json::jsonb;
    release_attestation := binding.release_attestation_json::jsonb;
    IF binding.target_namespace IS DISTINCT FROM NEW.canonical_target_namespace
        OR binding.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR binding.world_slug IS DISTINCT FROM NEW.canonical_world_slug
        OR binding.control_plane_request_id IS DISTINCT FROM NEW.control_plane_request_id
        OR binding.local_tenant_key IS DISTINCT FROM NEW.tenant_id
        OR version_identity.intake_operation_id IS DISTINCT FROM binding.intake_operation_id
        OR version_identity.intake_request_id IS DISTINCT FROM binding.intake_request_id
        OR version_identity.intake_request_digest IS DISTINCT FROM (
            SELECT request_digest FROM world_authored_source_intake
            WHERE operation_id = binding.intake_operation_id
        )
        OR version_identity.source_operation_id IS DISTINCT FROM binding.source_operation_id
        OR version_identity.source_evidence_digest IS DISTINCT FROM binding.source_evidence_digest
        OR version_identity.intake_receipt_digest IS DISTINCT FROM binding.intake_receipt_digest
        OR version_identity.local_tenant_key IS DISTINCT FROM NEW.tenant_id
        OR version_identity.local_version_key IS DISTINCT FROM NEW.version_id
        OR version_identity.canonical_version_id IS DISTINCT FROM binding.canonical_version_id
        OR version_identity.game_design_version_id::text IS DISTINCT FROM descriptor->>'versionId'
        OR descriptor->>'targetNamespace' IS DISTINCT FROM NEW.canonical_target_namespace
        OR descriptor->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::text
        OR descriptor->>'worldSlug' IS DISTINCT FROM NEW.canonical_world_slug
        OR descriptor->>'controlPlaneRequestId' IS DISTINCT FROM NEW.control_plane_request_id
        OR descriptor->>'authoredWorldSourceOperationId'
            IS DISTINCT FROM binding.source_operation_id::text
        OR descriptor->>'authoredWorldSourceEvidenceDigest'
            IS DISTINCT FROM binding.source_evidence_digest
        OR descriptor->>'requestDigest' IS DISTINCT FROM binding.descriptor_request_digest
        OR descriptor->>'resultDigest' IS DISTINCT FROM binding.descriptor_result_digest
        OR descriptor->>'launchDescriptorId' IS DISTINCT FROM NEW.launch_descriptor_id
        OR descriptor->>'gameTemplateId' IS DISTINCT FROM NEW.game_template_id::text
        OR descriptor->>'runtimeFlagsJson' IS DISTINCT FROM NEW.runtime_flags_json
        OR descriptor->>'generationConfigRevision' IS DISTINCT FROM NEW.generation_config_revision
        OR descriptor->>'releaseBundleId' IS DISTINCT FROM NEW.release_bundle_id::text
        OR descriptor->>'publishedReleaseBundleRef' IS DISTINCT FROM NEW.published_release_bundle_ref
        OR descriptor->>'versionStateEpoch' IS DISTINCT FROM NEW.version_state_epoch::text
        OR (
            CASE WHEN descriptor->>'scriptPatchVersionPresent' = 'true'
                THEN descriptor->>'scriptPatchVersion'
                ELSE NULL
            END
        ) IS DISTINCT FROM NEW.script_patch_version
        OR (
            CASE WHEN descriptor->>'remapSetIdPresent' = 'true'
                THEN descriptor->>'remapSetId'
                ELSE NULL
            END
        ) IS DISTINCT FROM NEW.remap_set_id
        OR release_attestation->>'targetNamespace' IS DISTINCT FROM NEW.canonical_target_namespace
        OR release_attestation->>'descriptorResultDigest'
            IS DISTINCT FROM binding.descriptor_result_digest
        OR release_attestation->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::text
        OR release_attestation->>'canonicalVersionId' IS DISTINCT FROM binding.canonical_version_id::text
        OR release_attestation->>'worldSlug' IS DISTINCT FROM NEW.canonical_world_slug
        OR release_attestation->>'authoredWorldSourceOperationId'
            IS DISTINCT FROM binding.source_operation_id::text
        OR release_attestation->>'authoredWorldSourceEvidenceDigest'
            IS DISTINCT FROM binding.source_evidence_digest
        OR release_attestation->>'launchDescriptorId' IS DISTINCT FROM NEW.launch_descriptor_id
        OR release_attestation->>'publishedReleaseBundleRef'
            IS DISTINCT FROM NEW.published_release_bundle_ref
        OR release_attestation->>'versionStateEpoch' IS DISTINCT FROM NEW.version_state_epoch::text
        OR release_attestation->>'generationConfigRevision'
            IS DISTINCT FROM NEW.generation_config_revision
        OR release_attestation->>'evidenceDigest' IS DISTINCT FROM binding.release_attestation_digest THEN
        RAISE EXCEPTION 'Canonical World instance prepare fields differ from immutable launch/source history'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_world_instance_canonical_prepare_identity
    BEFORE INSERT OR UPDATE OR DELETE ON world_instance
    FOR EACH ROW EXECUTE FUNCTION world_validate_canonical_prepare_identity();

CREATE FUNCTION world_validate_canonical_instance_association()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    instance world_instance%ROWTYPE;
    binding world_complete_launch_binding%ROWTYPE;
    version_identity world_authored_version_identity%ROWTYPE;
BEGIN
    SELECT * INTO instance FROM world_instance WHERE id = NEW.world_instance_id FOR KEY SHARE;
    IF NOT FOUND OR instance.canonical_game_instance_id IS NULL THEN
        RAISE EXCEPTION 'Canonical instance association requires an insert-born canonical World row'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO binding
    FROM world_complete_launch_binding
    WHERE binding_operation_id = NEW.canonical_launch_binding_operation_id;
    SELECT * INTO version_identity
    FROM world_authored_version_identity
    WHERE operation_id = NEW.version_identity_operation_id;

    IF NOT FOUND
        OR instance.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id
        OR instance.canonical_target_namespace IS DISTINCT FROM NEW.canonical_target_namespace
        OR instance.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR instance.canonical_world_slug IS DISTINCT FROM NEW.canonical_world_slug
        OR instance.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
        OR instance.playable_state_scope IS DISTINCT FROM NEW.playable_state_scope
        OR instance.public_production IS DISTINCT FROM NEW.public_production
        OR instance.playtest_lifecycle_id IS DISTINCT FROM NEW.playtest_lifecycle_id
        OR instance.playtest_state_generation IS DISTINCT FROM NEW.playtest_state_generation
        OR instance.control_plane_request_id IS DISTINCT FROM NEW.control_plane_request_id
        OR instance.canonical_launch_binding_operation_id
            IS DISTINCT FROM NEW.canonical_launch_binding_operation_id
        OR instance.tenant_id IS DISTINCT FROM NEW.local_tenant_key
        OR instance.game_instance_id IS DISTINCT FROM NEW.private_game_instance_key
        OR instance.game_template_id IS DISTINCT FROM NEW.game_template_id
        OR instance.launch_descriptor_id IS DISTINCT FROM NEW.launch_descriptor_id
        OR instance.version_id IS DISTINCT FROM NEW.local_version_key
        OR instance.script_patch_version IS DISTINCT FROM NEW.script_patch_version
        OR instance.runtime_flags_json IS DISTINCT FROM NEW.runtime_flags_json
        OR instance.generation_config_revision IS DISTINCT FROM NEW.generation_config_revision
        OR instance.release_bundle_id IS DISTINCT FROM NEW.release_bundle_id
        OR instance.published_release_bundle_ref IS DISTINCT FROM NEW.published_release_bundle_ref
        OR instance.version_state_epoch IS DISTINCT FROM NEW.version_state_epoch
        OR instance.remap_set_id IS DISTINCT FROM NEW.remap_set_id
        OR binding.target_namespace IS DISTINCT FROM NEW.canonical_target_namespace
        OR binding.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR binding.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
        OR binding.world_slug IS DISTINCT FROM NEW.canonical_world_slug
        OR binding.control_plane_request_id IS DISTINCT FROM NEW.control_plane_request_id
        OR binding.intake_operation_id IS DISTINCT FROM NEW.intake_operation_id
        OR binding.intake_request_id IS DISTINCT FROM NEW.intake_request_id
        OR binding.local_tenant_key IS DISTINCT FROM NEW.local_tenant_key
        OR binding.source_operation_id IS DISTINCT FROM NEW.source_operation_id
        OR binding.source_evidence_digest IS DISTINCT FROM NEW.source_evidence_digest
        OR binding.intake_receipt_digest IS DISTINCT FROM NEW.intake_receipt_digest
        OR binding.descriptor_request_digest IS DISTINCT FROM NEW.descriptor_request_digest
        OR binding.descriptor_result_digest IS DISTINCT FROM NEW.descriptor_result_digest
        OR binding.release_attestation_digest IS DISTINCT FROM NEW.release_attestation_digest
        OR version_identity.target_namespace IS DISTINCT FROM NEW.canonical_target_namespace
        OR version_identity.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR version_identity.world_slug IS DISTINCT FROM NEW.canonical_world_slug
        OR version_identity.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
        OR version_identity.local_tenant_key IS DISTINCT FROM NEW.local_tenant_key
        OR version_identity.local_version_key IS DISTINCT FROM NEW.local_version_key
        OR version_identity.intake_operation_id IS DISTINCT FROM binding.intake_operation_id
        OR version_identity.intake_request_id IS DISTINCT FROM binding.intake_request_id
        OR version_identity.source_operation_id IS DISTINCT FROM binding.source_operation_id
        OR version_identity.source_evidence_digest IS DISTINCT FROM binding.source_evidence_digest
        OR version_identity.intake_receipt_digest IS DISTINCT FROM binding.intake_receipt_digest THEN
        RAISE EXCEPTION 'Canonical instance association differs from exact owner creation, source, Version, or release rows'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_world_canonical_instance_association_exact_insert
    BEFORE INSERT ON world_canonical_instance_association
    FOR EACH ROW EXECUTE FUNCTION world_validate_canonical_instance_association();

CREATE TRIGGER trg_world_canonical_instance_association_immutable
    BEFORE UPDATE OR DELETE ON world_canonical_instance_association
    FOR EACH ROW EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE TRIGGER trg_world_canonical_instance_association_no_truncate
    BEFORE TRUNCATE ON world_canonical_instance_association
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE FUNCTION world_reject_canonical_world_instance_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'World instance creation identity cannot be truncated'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_world_instance_canonical_no_truncate
    BEFORE TRUNCATE ON world_instance
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_canonical_world_instance_truncate();
-- [jooq ignore stop]
