-- A launch association is durable Game Session owner evidence, not World lifecycle authority.
-- Historical instances are intentionally neither inferred nor backfilled.
ALTER TABLE game_session_fresh_tenant_association
    ADD CONSTRAINT uq_gs_fresh_tenant_association_launch_identity
        UNIQUE (target_namespace, association_operation_id, association_request_id,
                source_schema_version, source_target_namespace, source_request_id,
                source_canonical_tenant_id, canonical_tenant_id,
                legacy_game_session_tenant_id, source_game_row_id,
                source_game_tenant_key, provenance_kind, association_kind);

ALTER TABLE game_instances
    ADD CONSTRAINT uq_game_instances_tenant_id_id UNIQUE (tenant_id, id);

CREATE TABLE game_session_canonical_instance_launch (
    target_namespace VARCHAR(63) NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    game_session_tenant_id BIGINT NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    tenant_association_operation_id UUID NOT NULL,
    tenant_association_request_id UUID NOT NULL,
    tenant_source_schema_version INTEGER NOT NULL,
    tenant_source_target_namespace VARCHAR(63) NOT NULL,
    tenant_source_request_id UUID NOT NULL,
    tenant_source_canonical_tenant_id UUID NOT NULL,
    tenant_source_game_row_id BIGINT NOT NULL,
    tenant_source_game_tenant_key VARCHAR(72) NOT NULL,
    tenant_source_provenance_kind VARCHAR(32) NOT NULL,
    tenant_association_kind VARCHAR(32) NOT NULL,
    game_instance_id BIGINT NOT NULL,
    game_instance_uuid UUID NOT NULL,
    canonical_realm_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    playable_state_scope VARCHAR(16) NOT NULL,
    public_production BOOLEAN NOT NULL,
    captured_starting_row_version BIGINT NOT NULL,
    game_template_id BIGINT NOT NULL,
    launch_descriptor_id VARCHAR(128) NOT NULL,
    version_id BIGINT NOT NULL,
    release_bundle_id BIGINT NOT NULL,
    generation_config_revision VARCHAR(128) NOT NULL,
    version_state_epoch BIGINT NOT NULL,
    complete_launch_binding_evidence JSONB NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_game_session_canonical_instance_launch
        PRIMARY KEY (target_namespace, control_plane_request_id),
    CONSTRAINT uq_gs_canonical_instance_launch_uuid
        UNIQUE (target_namespace, game_instance_uuid),
    CONSTRAINT uq_gs_canonical_instance_launch_runtime
        UNIQUE (game_session_tenant_id, game_instance_id),
    CONSTRAINT fk_gs_canonical_instance_launch_fresh_identity
        FOREIGN KEY (target_namespace, tenant_association_operation_id,
                     tenant_association_request_id, tenant_source_schema_version,
                     tenant_source_target_namespace, tenant_source_request_id,
                     tenant_source_canonical_tenant_id, canonical_tenant_id,
                     game_session_tenant_id, tenant_source_game_row_id,
                     tenant_source_game_tenant_key, tenant_source_provenance_kind,
                     tenant_association_kind)
        REFERENCES game_session_fresh_tenant_association
            (target_namespace, association_operation_id, association_request_id,
             source_schema_version, source_target_namespace, source_request_id,
             source_canonical_tenant_id, canonical_tenant_id,
             legacy_game_session_tenant_id, source_game_row_id,
             source_game_tenant_key, provenance_kind, association_kind),
    CONSTRAINT fk_gs_canonical_instance_launch_claim
        FOREIGN KEY (target_namespace, canonical_tenant_id, game_session_tenant_id,
                     tenant_association_operation_id, tenant_association_kind)
        REFERENCES game_session_tenant_canonical_claim
            (target_namespace, canonical_tenant_id, legacy_game_session_tenant_id,
             association_operation_id, association_kind),
    CONSTRAINT fk_gs_canonical_instance_launch_runtime
        FOREIGN KEY (game_session_tenant_id, game_instance_id)
        REFERENCES game_instances (tenant_id, id),
    CONSTRAINT fk_gs_canonical_instance_launch_realm
        FOREIGN KEY (target_namespace, canonical_realm_id)
        REFERENCES game_session_canonical_realm_catalog (target_namespace, realm_id),
    CONSTRAINT chk_gs_canonical_instance_launch_namespace CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND tenant_source_target_namespace = target_namespace
    ),
    CONSTRAINT chk_gs_canonical_instance_launch_request CHECK (
        char_length(control_plane_request_id) BETWEEN 1 AND 128
        AND control_plane_request_id !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gs_canonical_instance_launch_source_identity CHECK (
        tenant_association_kind = 'FRESH_SOURCE_BOUND'
        AND tenant_source_schema_version = 1
        AND tenant_association_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND tenant_association_request_id = tenant_source_request_id
        AND canonical_tenant_id = tenant_source_canonical_tenant_id
        AND tenant_source_provenance_kind = 'NEW_GAME_ROW'
        AND tenant_source_game_row_id > 0
        AND char_length(tenant_source_game_tenant_key) BETWEEN 1 AND 72
        AND tenant_source_game_tenant_key !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gs_canonical_instance_launch_ids CHECK (
        game_session_tenant_id > 0
        AND game_instance_id > 0
        AND game_template_id > 0
        AND version_id > 0
        AND release_bundle_id > 0
        AND version_state_epoch > 0
        AND captured_starting_row_version >= 0
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND tenant_association_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND game_instance_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT chk_gs_canonical_instance_launch_world CHECK (
        octet_length(world_slug) BETWEEN 1 AND 120
        AND world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    CONSTRAINT chk_gs_canonical_instance_launch_owner_scope CHECK (
        playable_state_scope = 'SHARED' AND public_production
    ),
    CONSTRAINT chk_gs_canonical_instance_launch_binding CHECK (
        char_length(launch_descriptor_id) BETWEEN 1 AND 128
        AND launch_descriptor_id !~ '^[[:space:]]*$'
        AND char_length(generation_config_revision) BETWEEN 1 AND 128
        AND generation_config_revision !~ '^[[:space:]]*$'
        AND jsonb_typeof(complete_launch_binding_evidence) = 'object'
        AND jsonb_typeof(complete_launch_binding_evidence -> 'descriptor') = 'object'
        AND jsonb_typeof(complete_launch_binding_evidence -> 'releaseAttestation') = 'object'
    )
);

-- A fresh tenant key can own a GameInstance, but no other numeric-only tenant writer may claim it.
-- [jooq ignore start]
CREATE FUNCTION require_starting_game_instance_for_launch_association()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    runtime_row game_instances%ROWTYPE;
    descriptor JSONB;
BEGIN
    SELECT * INTO runtime_row
      FROM game_instances
     WHERE tenant_id = NEW.game_session_tenant_id
       AND id = NEW.game_instance_id
     FOR UPDATE;
    IF NOT FOUND OR runtime_row.status IS DISTINCT FROM 'STARTING'
       OR runtime_row.row_version IS DISTINCT FROM NEW.captured_starting_row_version
       OR runtime_row.game_instance_uuid IS DISTINCT FROM NEW.game_instance_uuid
       OR runtime_row.run_owned_start_request_id IS DISTINCT FROM NEW.control_plane_request_id
       OR runtime_row.run_owned_start_published_release_bundle_ref IS DISTINCT FROM
          (NEW.complete_launch_binding_evidence -> 'descriptor' ->> 'publishedReleaseBundleRef') THEN
        RAISE EXCEPTION 'Launch association requires the exact persisted run-owned STARTING row'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_launch_association_starting_row_required';
    END IF;

    descriptor := NEW.complete_launch_binding_evidence -> 'descriptor';
    IF descriptor ->> 'targetNamespace' IS DISTINCT FROM NEW.target_namespace
       OR descriptor ->> 'controlPlaneRequestId' IS DISTINCT FROM NEW.control_plane_request_id
       OR descriptor ->> 'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::text
       OR descriptor ->> 'worldSlug' IS DISTINCT FROM NEW.world_slug
       OR descriptor ->> 'gameTemplateId' IS DISTINCT FROM NEW.game_template_id::text
       OR descriptor ->> 'launchDescriptorId' IS DISTINCT FROM NEW.launch_descriptor_id
       OR descriptor ->> 'versionId' IS DISTINCT FROM NEW.version_id::text
       OR descriptor ->> 'releaseBundleId' IS DISTINCT FROM NEW.release_bundle_id::text
       OR descriptor ->> 'generationConfigRevision' IS DISTINCT FROM NEW.generation_config_revision
       OR descriptor ->> 'versionStateEpoch' IS DISTINCT FROM NEW.version_state_epoch::text
       OR runtime_row.game_template_id IS DISTINCT FROM NEW.game_template_id
       OR runtime_row.launch_descriptor_id IS DISTINCT FROM NEW.launch_descriptor_id
       OR runtime_row.version_id IS DISTINCT FROM NEW.version_id
       OR runtime_row.release_bundle_id IS DISTINCT FROM NEW.release_bundle_id
       OR runtime_row.generation_config_revision IS DISTINCT FROM NEW.generation_config_revision
       OR runtime_row.version_state_epoch IS DISTINCT FROM NEW.version_state_epoch THEN
        RAISE EXCEPTION 'Launch association differs from its complete descriptor binding'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_launch_association_descriptor_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_canonical_instance_launch_starting_owner
    BEFORE INSERT ON game_session_canonical_instance_launch
    FOR EACH ROW EXECUTE FUNCTION require_starting_game_instance_for_launch_association();

CREATE OR REPLACE FUNCTION reserve_game_session_tenant_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_kind VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.tenant_id IS NOT DISTINCT FROM OLD.tenant_id THEN
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.tenant_id <= 0 THEN
        RAISE EXCEPTION 'Game Session tenant scope must be positive'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_tenant_scope_reservation_positive';
    END IF;

    INSERT INTO game_session_tenant_scope_reservation
        (game_session_tenant_id, reservation_kind)
    VALUES (NEW.tenant_id, 'LEGACY_OCCUPIED')
    ON CONFLICT (game_session_tenant_id) DO NOTHING;

    SELECT reservation_kind INTO current_kind
      FROM game_session_tenant_scope_reservation
     WHERE game_session_tenant_id = NEW.tenant_id
     FOR KEY SHARE;

    IF current_kind = 'FRESH_SOURCE_BOUND' AND TG_TABLE_NAME = 'game_instances'
       AND EXISTS (
           SELECT 1
             FROM game_session_tenant_canonical_claim claim
             JOIN game_session_fresh_tenant_association association
               ON association.target_namespace = claim.target_namespace
              AND association.canonical_tenant_id = claim.canonical_tenant_id
              AND association.legacy_game_session_tenant_id = claim.legacy_game_session_tenant_id
              AND association.association_operation_id = claim.association_operation_id
              AND association.association_kind = claim.association_kind
            WHERE claim.legacy_game_session_tenant_id = NEW.tenant_id
              AND claim.association_kind = 'FRESH_SOURCE_BOUND'
              AND association.association_kind = 'FRESH_SOURCE_BOUND'
       ) THEN
        RETURN NEW;
    END IF;

    IF current_kind IS DISTINCT FROM 'LEGACY_OCCUPIED' THEN
        RAISE EXCEPTION 'Numeric-only tenant scope cannot use a fresh source-bound key'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_numeric_fresh_tenant_scope_denied';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_fresh_game_instance_owner_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_kind VARCHAR(32);
    previous_kind VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        SELECT reservation_kind INTO previous_kind
          FROM game_session_tenant_scope_reservation
         WHERE game_session_tenant_id = OLD.tenant_id;
        IF previous_kind = 'FRESH_SOURCE_BOUND' THEN
            RAISE EXCEPTION 'Fresh Game Session instance tenant scope is immutable'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'chk_gs_instance_fresh_tenant_scope_immutable';
        END IF;
    END IF;

    SELECT reservation_kind INTO current_kind
      FROM game_session_tenant_scope_reservation
     WHERE game_session_tenant_id = NEW.tenant_id
     FOR KEY SHARE;
    IF current_kind IS DISTINCT FROM 'FRESH_SOURCE_BOUND' THEN
        RETURN NEW;
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM game_session_tenant_canonical_claim claim
          JOIN game_session_fresh_tenant_association association
            ON association.target_namespace = claim.target_namespace
           AND association.canonical_tenant_id = claim.canonical_tenant_id
           AND association.legacy_game_session_tenant_id = claim.legacy_game_session_tenant_id
           AND association.association_operation_id = claim.association_operation_id
           AND association.association_kind = claim.association_kind
         WHERE claim.legacy_game_session_tenant_id = NEW.tenant_id
           AND claim.association_kind = 'FRESH_SOURCE_BOUND'
           AND association.association_kind = 'FRESH_SOURCE_BOUND'
    ) THEN
        RAISE EXCEPTION 'Fresh Game Session instance requires exact persisted source identity'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_instance_fresh_tenant_owner_missing';
    END IF;

    IF TG_OP = 'INSERT'
       AND (NEW.status IS DISTINCT FROM 'STARTING'
            OR NEW.run_owned_start_request_id IS NULL
            OR NEW.run_owned_start_published_release_bundle_ref IS NULL) THEN
        RAISE EXCEPTION 'Fresh Game Session runtime row must be owner-created in STARTING'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_instance_fresh_starting_owner_required';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_instances_fresh_canonical_owner_guard
    BEFORE INSERT OR UPDATE ON game_instances
    FOR EACH ROW EXECUTE FUNCTION guard_fresh_game_instance_owner_write();

CREATE FUNCTION require_complete_game_instance_launch_association()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    runtime_row game_instances%ROWTYPE;
    reservation_type VARCHAR(32);
    match_count BIGINT;
BEGIN
    SELECT * INTO runtime_row FROM game_instances WHERE id = NEW.id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT reservation_kind INTO reservation_type
      FROM game_session_tenant_scope_reservation
     WHERE game_session_tenant_id = runtime_row.tenant_id;
    IF reservation_type IS DISTINCT FROM 'FRESH_SOURCE_BOUND' THEN
        RETURN NULL;
    END IF;

    SELECT count(*) INTO match_count
      FROM game_session_canonical_instance_launch launch
      JOIN game_session_fresh_tenant_association fresh
        ON fresh.target_namespace = launch.target_namespace
       AND fresh.association_operation_id = launch.tenant_association_operation_id
       AND fresh.association_request_id = launch.tenant_association_request_id
       AND fresh.source_schema_version = launch.tenant_source_schema_version
       AND fresh.source_target_namespace = launch.tenant_source_target_namespace
       AND fresh.source_request_id = launch.tenant_source_request_id
       AND fresh.source_canonical_tenant_id = launch.tenant_source_canonical_tenant_id
       AND fresh.canonical_tenant_id = launch.canonical_tenant_id
       AND fresh.legacy_game_session_tenant_id = launch.game_session_tenant_id
       AND fresh.source_game_row_id = launch.tenant_source_game_row_id
       AND fresh.source_game_tenant_key = launch.tenant_source_game_tenant_key
       AND fresh.provenance_kind = launch.tenant_source_provenance_kind
       AND fresh.association_kind = launch.tenant_association_kind
      JOIN game_session_canonical_realm_catalog realm
        ON realm.target_namespace = launch.target_namespace
       AND realm.realm_id = launch.canonical_realm_id
     WHERE launch.target_namespace = realm.target_namespace
       AND launch.game_session_tenant_id = runtime_row.tenant_id
       AND launch.game_instance_id = runtime_row.id
       AND launch.game_instance_uuid = runtime_row.game_instance_uuid
       AND launch.canonical_tenant_id = fresh.canonical_tenant_id
       AND realm.canonical_tenant_id = launch.canonical_tenant_id
       AND launch.world_slug = realm.world_slug
       AND launch.playable_state_namespace_id = realm.playable_state_namespace_id
       AND realm.visible AND realm.public_production AND realm.state_scope = 'SHARED'
       AND launch.playable_state_scope = 'SHARED' AND launch.public_production
       AND launch.game_template_id = runtime_row.game_template_id
       AND launch.launch_descriptor_id = runtime_row.launch_descriptor_id
       AND launch.version_id = runtime_row.version_id
       AND launch.release_bundle_id = runtime_row.release_bundle_id
       AND launch.generation_config_revision = runtime_row.generation_config_revision
       AND launch.version_state_epoch = runtime_row.version_state_epoch
       AND launch.control_plane_request_id = runtime_row.run_owned_start_request_id
       AND launch.captured_starting_row_version <= runtime_row.row_version
       AND runtime_row.run_owned_start_published_release_bundle_ref =
           launch.complete_launch_binding_evidence -> 'descriptor' ->> 'publishedReleaseBundleRef'
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'targetNamespace' = launch.target_namespace
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'controlPlaneRequestId'
           = launch.control_plane_request_id
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'canonicalTenantId'
           = launch.canonical_tenant_id::text
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'worldSlug' = launch.world_slug
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'launchDescriptorId'
           = launch.launch_descriptor_id
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'gameTemplateId'
           = launch.game_template_id::text
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'versionId'
           = launch.version_id::text
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'releaseBundleId'
           = launch.release_bundle_id::text
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'generationConfigRevision'
           = launch.generation_config_revision
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'versionStateEpoch'
           = launch.version_state_epoch::text;

    IF match_count <> 1 THEN
        RAISE EXCEPTION 'Fresh Game Session instance requires one exact complete launch association'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'chk_gs_instance_complete_launch_association_required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER game_instances_complete_launch_association_required
    AFTER INSERT OR UPDATE ON game_instances
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_complete_game_instance_launch_association();

CREATE FUNCTION reject_game_session_canonical_instance_launch_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Game Session canonical instance launch evidence is immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'gs_canonical_instance_launch_immutable';
END;
$$;

CREATE TRIGGER game_session_canonical_instance_launch_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_instance_launch
    FOR EACH ROW EXECUTE FUNCTION reject_game_session_canonical_instance_launch_mutation();
CREATE TRIGGER game_session_canonical_instance_launch_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_instance_launch
    FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_instance_launch_mutation();
-- [jooq ignore stop]
