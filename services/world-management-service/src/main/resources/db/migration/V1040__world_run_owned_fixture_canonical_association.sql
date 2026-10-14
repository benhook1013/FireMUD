-- Retain run-owned fixture provenance on the existing canonical World row and association.
-- Human initial-admission hold owners remain unchanged; fixture holds remain unsupported.
ALTER TABLE world_instance
    ADD COLUMN canonical_authority_kind VARCHAR(32);

ALTER TABLE world_instance
    ADD COLUMN fixture_provenance_digest VARCHAR(71);

ALTER TABLE world_instance
    ADD COLUMN game_session_tenant_id BIGINT;

ALTER TABLE world_instance
    ADD COLUMN game_session_game_instance_id BIGINT;

ALTER TABLE world_instance
    ADD COLUMN game_design_version_id BIGINT;

ALTER TABLE world_instance
    DROP CONSTRAINT ck_world_instance_canonical_creation_identity;

ALTER TABLE world_instance
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
            AND canonical_authority_kind IS NULL
            AND fixture_provenance_digest IS NULL
            AND game_session_tenant_id IS NULL
            AND game_session_game_instance_id IS NULL
            AND game_design_version_id IS NULL
        )
        OR
        (
            canonical_game_instance_id IS NOT NULL
            AND canonical_target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND canonical_world_slug IS NOT NULL
            AND playable_state_namespace_id IS NOT NULL
            AND playable_state_scope IS NOT NULL
            AND playable_state_scope = 'SHARED'
            AND public_production IS TRUE
            AND playtest_lifecycle_id IS NULL
            AND playtest_state_generation IS NULL
            AND canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_target_namespace COLLATE "C" ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_world_slug COLLATE "C" ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
            AND octet_length(canonical_world_slug) BETWEEN 1 AND 120
            AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND (
                (
                    (canonical_authority_kind IS NULL OR canonical_authority_kind = 'HUMAN')
                    AND canonical_launch_binding_operation_id IS NOT NULL
                    AND canonical_launch_binding_operation_id
                        <> '00000000-0000-0000-0000-000000000000'::UUID
                    AND fixture_provenance_digest IS NULL
                    AND game_session_tenant_id IS NULL
                    AND game_session_game_instance_id IS NULL
                    AND game_design_version_id IS NULL
                )
                OR
                (
                    canonical_authority_kind IS NOT NULL
                    AND canonical_authority_kind = 'RUN_OWNED_FIXTURE'
                    AND canonical_launch_binding_operation_id IS NULL
                    AND fixture_provenance_digest IS NOT NULL
                    AND fixture_provenance_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
                    AND canonical_realm_id IS NOT NULL
                    AND canonical_realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
                    AND catalog_revision IS NOT NULL
                    AND catalog_revision > 0
                    AND game_session_tenant_id IS NOT NULL
                    AND game_session_tenant_id > 0
                    AND game_session_game_instance_id IS NOT NULL
                    AND game_session_game_instance_id > 0
                    AND game_design_version_id IS NOT NULL
                    AND game_design_version_id > 0
                    AND run_owned_fixture_binding_sha256 IS NOT NULL
                    AND run_owned_fixture_preparing_lifecycle_epoch IS NOT NULL
                    AND run_owned_fixture_preparing_lifecycle_epoch > 0
                )
            )
        )
    );

ALTER TABLE world_instance
    ADD CONSTRAINT ck_world_instance_fixture_gs_allocation CHECK (
        (canonical_authority_kind IS NOT NULL
            AND canonical_authority_kind = 'RUN_OWNED_FIXTURE'
            AND game_session_tenant_id > 0
            AND game_session_game_instance_id > 0
            AND game_design_version_id > 0)
        OR
        (canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
            AND game_session_tenant_id IS NULL
            AND game_session_game_instance_id IS NULL
            AND game_design_version_id IS NULL)
    );

ALTER TABLE world_canonical_instance_association
    ALTER COLUMN canonical_launch_binding_operation_id DROP NOT NULL;

ALTER TABLE world_canonical_instance_association
    ALTER COLUMN release_attestation_digest DROP NOT NULL;

ALTER TABLE world_canonical_instance_association
    ADD COLUMN canonical_authority_kind VARCHAR(32);

ALTER TABLE world_canonical_instance_association
    ADD COLUMN fixture_provenance_digest VARCHAR(71);

ALTER TABLE world_canonical_instance_association
    ADD COLUMN game_session_tenant_id BIGINT;

ALTER TABLE world_canonical_instance_association
    ADD COLUMN game_session_game_instance_id BIGINT;

ALTER TABLE world_canonical_instance_association
    ADD COLUMN game_design_version_id BIGINT;

ALTER TABLE world_canonical_instance_association
    ADD COLUMN preparation_input_digest VARCHAR(71);

ALTER TABLE world_canonical_instance_association
    ADD COLUMN preparing_lifecycle_epoch BIGINT;

ALTER TABLE world_canonical_instance_association
    ADD COLUMN game_session_association_response_bytes BYTEA;

ALTER TABLE world_canonical_instance_association
    DROP CONSTRAINT ck_world_canonical_instance_association_schema;

ALTER TABLE world_canonical_instance_association
    DROP CONSTRAINT ck_world_canonical_instance_association_ids;

ALTER TABLE world_canonical_instance_association
    DROP CONSTRAINT ck_world_canonical_instance_association_owner_keys;

ALTER TABLE world_canonical_instance_association
    DROP CONSTRAINT ck_world_canonical_instance_association_digests;

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT ck_world_canonical_instance_association_schema CHECK (
        (schema_version = 1
            AND (canonical_authority_kind IS NULL OR canonical_authority_kind = 'HUMAN')
            AND canonical_realm_id IS NULL
            AND catalog_revision IS NULL)
        OR
        (schema_version = 2
            AND (canonical_authority_kind IS NULL OR canonical_authority_kind = 'HUMAN')
            AND canonical_realm_id IS NOT NULL
            AND canonical_realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND catalog_revision > 0)
        OR
        (schema_version = 3
            AND canonical_authority_kind IS NOT NULL
            AND canonical_authority_kind = 'RUN_OWNED_FIXTURE'
            AND canonical_realm_id IS NOT NULL
            AND canonical_realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND catalog_revision > 0
            AND canonical_launch_binding_operation_id IS NULL
            AND release_attestation_digest IS NULL
            AND fixture_provenance_digest IS NOT NULL
            AND fixture_provenance_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
            AND game_session_tenant_id IS NOT NULL
            AND game_session_tenant_id > 0
            AND game_session_game_instance_id IS NOT NULL
            AND game_session_game_instance_id > 0
            AND game_design_version_id IS NOT NULL
            AND game_design_version_id > 0
            AND preparation_input_digest IS NOT NULL
            AND preparation_input_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
            AND preparing_lifecycle_epoch IS NOT NULL
            AND preparing_lifecycle_epoch > 0
            AND game_session_association_response_bytes IS NOT NULL
            AND octet_length(game_session_association_response_bytes) > 0)
    );

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT ck_world_canonical_instance_association_ids CHECK (
        canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND version_identity_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND (canonical_launch_binding_operation_id IS NULL
            OR canonical_launch_binding_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID)
    );

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT ck_world_canonical_instance_association_owner_keys CHECK (
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
    );

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT ck_world_canonical_instance_association_digests CHECK (
        source_evidence_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
        AND intake_request_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
        AND intake_receipt_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_request_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_result_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
        AND (
            (schema_version IN (1, 2)
                AND release_attestation_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$')
            OR (schema_version = 3 AND release_attestation_digest IS NULL)
        )
    );

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT ck_world_canonical_instance_association_fixture_metadata CHECK (
        (schema_version = 3
            AND fixture_provenance_digest IS NOT NULL
            AND fixture_provenance_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
            AND game_session_tenant_id IS NOT NULL
            AND game_session_tenant_id > 0
            AND game_session_game_instance_id IS NOT NULL
            AND game_session_game_instance_id > 0
            AND game_design_version_id IS NOT NULL
            AND game_design_version_id > 0
            AND preparation_input_digest IS NOT NULL
            AND preparation_input_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
            AND preparing_lifecycle_epoch IS NOT NULL
            AND preparing_lifecycle_epoch > 0
            AND game_session_association_response_bytes IS NOT NULL
            AND octet_length(game_session_association_response_bytes) > 0)
        OR
        (schema_version IN (1, 2)
            AND fixture_provenance_digest IS NULL
            AND game_session_tenant_id IS NULL
            AND game_session_game_instance_id IS NULL
            AND game_design_version_id IS NULL
            AND preparation_input_digest IS NULL
            AND preparing_lifecycle_epoch IS NULL
            AND game_session_association_response_bytes IS NULL)
    );

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT uq_world_canonical_instance_association_fixture_owner_fk
        UNIQUE (canonical_authority_kind, fixture_provenance_digest,
            canonical_game_instance_id, canonical_target_namespace, canonical_tenant_id,
            canonical_world_slug);

ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT fk_world_canonical_instance_association_fixture_world_row
        FOREIGN KEY (world_instance_id)
        REFERENCES world_instance (id)
        ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_world_canonical_instance_association_fixture_operation
    ON world_canonical_instance_association (control_plane_request_id)
    WHERE canonical_authority_kind = 'RUN_OWNED_FIXTURE';

-- Run-owned fixture lifecycle inserts use the same retained World row owner but a closed typed
-- branch. Preserve the existing Human validation below the fixture fast path.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    insert_anchor TEXT := $anchor$    IF NEW.canonical_game_instance_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.canonical_realm_id IS NULL$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_validate_canonical_prepare_identity()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,insert_anchor,'')))/length(insert_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V64 canonical World identity insert anchor';
    END IF;
    original := replace(original, insert_anchor,
        $replacement$    IF NEW.run_owned_fixture_binding_sha256 IS NOT NULL
        AND (NEW.status IS DISTINCT FROM 'PREPARING'
            OR NEW.run_owned_fixture_preparing_lifecycle_epoch IS DISTINCT FROM 1
            OR NEW.lifecycle_epoch IS DISTINCT FROM NEW.run_owned_fixture_preparing_lifecycle_epoch) THEN
        RAISE EXCEPTION 'New World rows carrying fixture binding evidence must begin PREPARING at epoch 1'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.canonical_game_instance_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        SELECT * INTO version_identity
        FROM world_authored_version_identity vi
        WHERE vi.target_namespace = NEW.canonical_target_namespace
            AND vi.canonical_tenant_id = NEW.canonical_tenant_id
            AND vi.world_slug = NEW.canonical_world_slug
            AND vi.game_design_version_id = NEW.game_design_version_id;
        IF NEW.canonical_launch_binding_operation_id IS NOT NULL
            OR NEW.fixture_provenance_digest IS NULL
            OR NEW.fixture_provenance_digest COLLATE "C" !~ '^sha256:[0-9a-f]{64}$'
            OR NEW.game_session_tenant_id IS NULL
            OR NEW.game_session_tenant_id <= 0
            OR NEW.game_session_game_instance_id IS NULL
            OR NEW.game_session_game_instance_id <= 0
            OR NEW.game_design_version_id IS NULL
            OR NEW.game_design_version_id <= 0
            OR NEW.run_owned_fixture_binding_sha256 COLLATE "C" !~ '^[0-9a-f]{64}$'
            OR NEW.run_owned_fixture_preparing_lifecycle_epoch IS NULL
            OR NEW.run_owned_fixture_preparing_lifecycle_epoch <= 0
            OR NEW.status IS DISTINCT FROM 'PREPARING'
            OR NEW.lifecycle_epoch IS DISTINCT FROM NEW.run_owned_fixture_preparing_lifecycle_epoch
            OR version_identity.operation_id IS NULL
            OR NEW.tenant_id IS DISTINCT FROM version_identity.local_tenant_key
            OR NEW.version_id IS DISTINCT FROM version_identity.local_version_key
            OR NOT EXISTS (
                SELECT 1
                FROM world_authored_source_intake si
                WHERE si.operation_id = version_identity.intake_operation_id
                    AND si.intake_request_id = version_identity.intake_request_id
                    AND si.local_tenant_key = NEW.tenant_id
                    AND si.source_provenance_kind = 'NEW_GAME_ROW'
            ) THEN
            RAISE EXCEPTION 'Run-owned fixture World row differs from actual World source and Version owners'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.canonical_authority_kind IS NOT NULL
        AND NEW.canonical_authority_kind IS DISTINCT FROM 'HUMAN' THEN
        RAISE EXCEPTION 'New canonical World row requires an explicit authority variant'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.canonical_realm_id IS NULL$replacement$);
    EXECUTE original;
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    authority_anchor TEXT := $anchor$    IF NEW.schema_version IS DISTINCT FROM 2
        OR NEW.canonical_realm_id IS NULL
        OR NEW.canonical_realm_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.catalog_revision IS NULL
        OR NEW.catalog_revision <= 0 THEN$anchor$;
    begin_anchor TEXT := $anchor$    SELECT * INTO instance FROM world_instance WHERE id = NEW.world_instance_id FOR KEY SHARE;$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_validate_canonical_instance_association()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,authority_anchor,'')))/length(authority_anchor) <> 1
        OR (length(original)-length(replace(original,begin_anchor,'')))/length(begin_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V64 canonical association authority guard anchor';
    END IF;
    original := replace(original, begin_anchor,
        $replacement$    SELECT * INTO instance FROM world_instance WHERE id = NEW.world_instance_id FOR KEY SHARE;
    IF NEW.schema_version = 3 AND NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        IF instance.id IS NULL THEN
            RAISE EXCEPTION 'Run-owned fixture association requires its allocated World row'
                USING ERRCODE = '23514';
        END IF;
        SELECT * INTO version_identity
        FROM world_authored_version_identity
        WHERE operation_id = NEW.version_identity_operation_id;
        IF NOT FOUND
            OR instance.id IS NULL
            OR instance.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
            OR instance.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id
            OR instance.canonical_target_namespace IS DISTINCT FROM NEW.canonical_target_namespace
            OR instance.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
            OR instance.canonical_world_slug IS DISTINCT FROM NEW.canonical_world_slug
            OR instance.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_id
            OR instance.playable_state_scope IS DISTINCT FROM NEW.playable_state_scope
            OR instance.public_production IS DISTINCT FROM NEW.public_production
            OR instance.control_plane_request_id IS DISTINCT FROM NEW.control_plane_request_id
            OR instance.canonical_launch_binding_operation_id IS NOT NULL
            OR instance.fixture_provenance_digest IS DISTINCT FROM NEW.fixture_provenance_digest
            OR instance.game_session_tenant_id IS DISTINCT FROM NEW.game_session_tenant_id
            OR instance.game_session_game_instance_id IS DISTINCT FROM NEW.game_session_game_instance_id
            OR instance.game_design_version_id IS DISTINCT FROM NEW.game_design_version_id
            OR instance.tenant_id IS DISTINCT FROM NEW.local_tenant_key
            OR instance.game_instance_id IS DISTINCT FROM NEW.private_game_instance_key
            OR instance.version_id IS DISTINCT FROM NEW.local_version_key
            OR instance.game_template_id IS DISTINCT FROM NEW.game_template_id
            OR instance.launch_descriptor_id IS DISTINCT FROM NEW.launch_descriptor_id
            OR instance.version_state_epoch IS DISTINCT FROM NEW.version_state_epoch
            OR instance.run_owned_fixture_preparing_lifecycle_epoch IS DISTINCT FROM NEW.preparing_lifecycle_epoch
            OR NEW.preparation_input_digest IS DISTINCT FROM
                ('sha256:' || instance.run_owned_fixture_binding_sha256)
            OR NEW.fixture_provenance_digest IS NULL
            OR NEW.fixture_provenance_digest COLLATE "C" !~ '^sha256:[0-9a-f]{64}$'
            OR octet_length(NEW.game_session_association_response_bytes) <= 0
            OR NEW.canonical_launch_binding_operation_id IS NOT NULL
            OR NEW.release_attestation_digest IS NOT NULL
            OR version_identity.target_namespace IS DISTINCT FROM NEW.canonical_target_namespace
            OR version_identity.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
            OR version_identity.world_slug IS DISTINCT FROM NEW.canonical_world_slug
            OR version_identity.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
            OR version_identity.local_tenant_key IS DISTINCT FROM NEW.local_tenant_key
            OR version_identity.local_version_key IS DISTINCT FROM NEW.local_version_key
            OR version_identity.game_design_version_id IS DISTINCT FROM NEW.game_design_version_id
            OR version_identity.source_operation_id IS DISTINCT FROM NEW.source_operation_id
            OR version_identity.source_evidence_digest IS DISTINCT FROM NEW.source_evidence_digest
            OR version_identity.intake_operation_id IS DISTINCT FROM NEW.intake_operation_id
            OR version_identity.intake_request_id IS DISTINCT FROM NEW.intake_request_id
            OR version_identity.intake_request_digest IS DISTINCT FROM NEW.intake_request_digest
            OR version_identity.intake_receipt_digest IS DISTINCT FROM NEW.intake_receipt_digest
            OR version_identity.intake_operation_id IS DISTINCT FROM (
                SELECT si.operation_id FROM world_authored_source_intake si
                WHERE si.operation_id = NEW.intake_operation_id
                    AND si.intake_request_id = NEW.intake_request_id
                    AND si.local_tenant_key = NEW.local_tenant_key
                    AND si.source_operation_id = NEW.source_operation_id
                    AND si.source_evidence_digest = NEW.source_evidence_digest
                    AND si.receipt_digest = NEW.intake_receipt_digest
            ) THEN
            RAISE EXCEPTION 'Run-owned fixture association differs from its exact World, source, Version, or GS owner readback'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    $replacement$);
    original := replace(original, authority_anchor,
        $replacement$    IF NOT (
        (NEW.schema_version IS NOT DISTINCT FROM 2
            AND (NEW.canonical_authority_kind IS NULL
                OR NEW.canonical_authority_kind IS NOT DISTINCT FROM 'HUMAN'))
        OR (NEW.schema_version IS NOT DISTINCT FROM 3
            AND NEW.canonical_authority_kind IS NOT DISTINCT FROM 'RUN_OWNED_FIXTURE'))
        OR NEW.canonical_realm_id IS NULL
        OR NEW.canonical_realm_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.catalog_revision IS NULL
        OR NEW.catalog_revision <= 0 THEN$replacement$);
    EXECUTE original;
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    immutable_anchor TEXT := $anchor$            OR OLD.canonical_launch_binding_operation_id
                IS DISTINCT FROM NEW.canonical_launch_binding_operation_id
            OR OLD.canonical_realm_id IS DISTINCT FROM NEW.canonical_realm_id
            OR OLD.catalog_revision IS DISTINCT FROM NEW.catalog_revision THEN$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_validate_canonical_prepare_identity()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,immutable_anchor,'')))/length(immutable_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V64 canonical World identity immutability anchor';
    END IF;
    original := replace(original, immutable_anchor,
        $replacement$            OR OLD.canonical_launch_binding_operation_id
                IS DISTINCT FROM NEW.canonical_launch_binding_operation_id
            OR OLD.canonical_realm_id IS DISTINCT FROM NEW.canonical_realm_id
            OR OLD.catalog_revision IS DISTINCT FROM NEW.catalog_revision
            OR OLD.canonical_authority_kind IS DISTINCT FROM NEW.canonical_authority_kind
            OR OLD.fixture_provenance_digest IS DISTINCT FROM NEW.fixture_provenance_digest
            OR OLD.game_session_tenant_id IS DISTINCT FROM NEW.game_session_tenant_id
            OR OLD.game_session_game_instance_id IS DISTINCT FROM NEW.game_session_game_instance_id
            OR OLD.game_design_version_id IS DISTINCT FROM NEW.game_design_version_id THEN$replacement$);
    EXECUTE original;
END;
$migration$;
-- [jooq ignore stop]


-- Existing World activation and first-admission owners remain Human-only. Fixture lifecycle
-- activation is closed at the World row trigger before any lifecycle or owner write.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$BEGIN
    IF OLD.canonical_game_instance_id IS NOT NULL AND NEW.status = 'ACTIVE'$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_canonical_activation_transition()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current canonical activation-transition guard anchor';
    END IF;
    original := replace(original, anchor, $replacement$BEGIN
    IF OLD.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        OR NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        RAISE EXCEPTION 'Run-owned fixture World lifecycle transitions are disabled'
            USING ERRCODE = '23514';
    END IF;
    IF (OLD.run_owned_fixture_binding_sha256 IS NOT NULL
            OR NEW.run_owned_fixture_binding_sha256 IS NOT NULL)
        AND (OLD.status IS DISTINCT FROM NEW.status
            OR OLD.lifecycle_epoch IS DISTINCT FROM NEW.lifecycle_epoch) THEN
        RAISE EXCEPTION 'World lifecycle transitions are disabled for rows carrying fixture binding evidence'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.canonical_game_instance_id IS NOT NULL AND NEW.status = 'ACTIVE'$replacement$);
    EXECUTE original;
END;
$migration$;

-- A fixture association cannot acquire a hold through either the typed owner or a legacy
-- numeric request. Human and legacy acquisitions continue through their existing validation.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$BEGIN
    IF TG_OP = 'DELETE' THEN$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_canonical_initial_admission_hold()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current initial-admission hold guard anchor';
    END IF;
    original := replace(original, anchor, $replacement$BEGIN
    IF TG_OP = 'INSERT' AND EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_canonical_instance_association a
        JOIN "${serviceSchema}".world_instance wi ON wi.id = a.world_instance_id
        WHERE a.schema_version = 3
            AND a.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
            AND (a.canonical_game_instance_id = NEW.canonical_game_instance_id
                OR (wi.tenant_id = NEW.tenant_id
                    AND wi.game_instance_id = NEW.game_instance_id
                    AND wi.version_id = NEW.version_id))) THEN
        RAISE EXCEPTION 'Run-owned fixture initial-admission holds are disabled'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN$replacement$);
    EXECUTE original;
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$        RAISE EXCEPTION 'Canonical initial-admission hold identity is missing or untyped'
            USING ERRCODE = '55000';
    END IF;

    SELECT a.* INTO association_row$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_finalize_canonical_initial_admission_hold(uuid,uuid,text,text,text,text,bigint,timestamptz,boolean,bytea,text)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current initial-admission finalizer candidate anchor';
    END IF;
    original := replace(original, anchor, $replacement$        RAISE EXCEPTION 'Canonical initial-admission hold identity is missing or untyped'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_canonical_instance_association a
        JOIN "${serviceSchema}".world_instance wi ON wi.id = a.world_instance_id
        WHERE a.schema_version = 3
            AND a.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
            AND (a.canonical_game_instance_id = candidate.canonical_game_instance_id
                OR (wi.tenant_id = candidate.tenant_id
                    AND wi.game_instance_id = candidate.game_instance_id
                    AND wi.version_id = candidate.version_id))) THEN
        RAISE EXCEPTION 'Run-owned fixture initial-admission hold finalization is disabled'
            USING ERRCODE = '23514';
    END IF;

    SELECT a.* INTO association_row$replacement$);
    EXECUTE original;
END;
$migration$;
-- [jooq ignore stop]

-- Placement and World-side player-admission hold owners stay closed for the fixture variant.
-- These exact owners reject direct SQL inserts before claiming or changing lifecycle rows.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$BEGIN
    IF TG_OP <> 'INSERT' THEN$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_initial_location_operation()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current initial-location operation owner anchor';
    END IF;
    original := replace(original, anchor, $replacement$BEGIN
    IF TG_OP = 'INSERT' AND EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_canonical_instance_association a
        WHERE (a.world_instance_id = NEW.world_instance_id
            OR a.canonical_game_instance_id = NEW.canonical_game_instance_id)
            AND a.schema_version = 3
            AND a.canonical_authority_kind = 'RUN_OWNED_FIXTURE') THEN
        RAISE EXCEPTION 'Run-owned fixture initial-location placement is disabled'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP <> 'INSERT' THEN$replacement$);
    EXECUTE original;
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$BEGIN
    IF TG_OP = 'UPDATE' THEN$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_initial_character_location()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current initial-character location owner anchor';
    END IF;
    original := replace(original, anchor, $replacement$BEGIN
    IF TG_OP = 'INSERT' AND EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_canonical_instance_association a
        WHERE (a.world_instance_id = NEW.world_instance_id
            OR a.canonical_game_instance_id = NEW.canonical_game_instance_id)
            AND a.schema_version = 3
            AND a.canonical_authority_kind = 'RUN_OWNED_FIXTURE') THEN
        RAISE EXCEPTION 'Run-owned fixture initial-character placement is disabled'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN$replacement$);
    EXECUTE original;
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$BEGIN
    IF TG_OP <> 'INSERT' THEN$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_validate_player_admission_hold()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current World player-admission hold owner anchor';
    END IF;
    original := replace(original, anchor, $replacement$BEGIN
    IF TG_OP = 'INSERT' AND EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_canonical_instance_association a
        WHERE (a.world_instance_id = NEW.world_instance_id
            OR a.canonical_game_instance_id = NEW.canonical_game_instance_id)
            AND a.schema_version = 3
            AND a.canonical_authority_kind = 'RUN_OWNED_FIXTURE') THEN
        RAISE EXCEPTION 'Run-owned fixture World player-admission holds are disabled'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP <> 'INSERT' THEN$replacement$);
    EXECUTE original;
END;
$migration$;
-- [jooq ignore stop]
