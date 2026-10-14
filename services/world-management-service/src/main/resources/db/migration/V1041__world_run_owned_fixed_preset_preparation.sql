-- The run-owned local fixture is a separate V67 composition. Human frozen-capture preparation
-- retains its original capture identity, graph, and execution path without a preset exception.
ALTER TABLE world_canonical_instance_preparation
    ALTER COLUMN capture_id DROP NOT NULL;

ALTER TABLE world_canonical_instance_preparation
    ADD COLUMN fixed_preset_id VARCHAR(96);

ALTER TABLE world_canonical_instance_preparation
    ADD COLUMN fixed_preset_content_digest VARCHAR(71);

ALTER TABLE world_canonical_instance_preparation
    ADD COLUMN fixed_preset_binding_digest VARCHAR(71);

ALTER TABLE world_canonical_instance_preparation
    ADD CONSTRAINT ck_world_canonical_preparation_one_authority CHECK (
        (capture_id IS NOT NULL AND fixed_preset_id IS NULL
            AND fixed_preset_content_digest IS NULL AND fixed_preset_binding_digest IS NULL)
        OR (capture_id IS NULL AND fixed_preset_id IS NOT NULL
            AND fixed_preset_content_digest IS NOT NULL AND fixed_preset_binding_digest IS NOT NULL
            AND fixed_preset_id = 'firemud-demo-topology-v1'
            AND fixed_preset_content_digest =
                'sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc'
            AND fixed_preset_binding_digest = input_digest
            AND fixed_preset_binding_digest ~ '^sha256:[0-9a-f]{64}$')
    );

-- A fixture owner is the actual immutable V67 association, never an authored Draft commit.
ALTER TABLE world_canonical_instance_association
    ADD CONSTRAINT uq_world_fixed_preset_fixture_owner UNIQUE (
        canonical_authority_kind, fixture_provenance_digest, world_instance_id,
        control_plane_request_id);

-- Historical Human identities and the original Human row producer retain nullable metadata.
-- The closed Human arm still requires every original Draft identity and its existing FK.
ALTER TABLE world_authored_topology_identity
    ADD COLUMN preparation_authority_kind VARCHAR(32);

ALTER TABLE world_authored_topology_identity
    ADD COLUMN fixture_provenance_digest VARCHAR(71);

ALTER TABLE world_authored_topology_identity
    ADD COLUMN fixture_world_instance_id BIGINT;

ALTER TABLE world_authored_topology_identity
    ADD COLUMN fixture_operation_id VARCHAR(128);

ALTER TABLE world_authored_topology_identity
    ADD CONSTRAINT fk_topology_fixed_preset_fixture_owner FOREIGN KEY (
        preparation_authority_kind, fixture_provenance_digest, fixture_world_instance_id,
        fixture_operation_id)
    REFERENCES world_canonical_instance_association (
        canonical_authority_kind, fixture_provenance_digest, world_instance_id,
        control_plane_request_id) ON DELETE RESTRICT;

ALTER TABLE world_authored_topology_identity
    ALTER COLUMN request_id DROP NOT NULL;

ALTER TABLE world_authored_topology_identity
    ALTER COLUMN commit_id DROP NOT NULL;

ALTER TABLE world_authored_topology_identity
    ALTER COLUMN revision_id DROP NOT NULL;

ALTER TABLE world_authored_topology_identity
    ALTER COLUMN revision_order DROP NOT NULL;

ALTER TABLE world_authored_topology_identity
    ADD CONSTRAINT ck_topology_fixed_preset_authority CHECK (
        ((preparation_authority_kind IS NULL OR preparation_authority_kind = 'HUMAN')
            AND request_id IS NOT NULL AND commit_id IS NOT NULL
            AND revision_id IS NOT NULL AND revision_order IS NOT NULL AND fixture_provenance_digest IS NULL
            AND fixture_world_instance_id IS NULL AND fixture_operation_id IS NULL)
        OR (preparation_authority_kind IS NOT NULL
            AND preparation_authority_kind = 'RUN_OWNED_FIXTURE' AND request_id IS NULL AND commit_id IS NULL
            AND revision_id IS NULL AND revision_order IS NULL
            AND family IN ('REGION','ZONE','ROOM','ROOM_EXIT')
            AND fixture_provenance_digest IS NOT NULL
            AND fixture_provenance_digest ~ '^sha256:[0-9a-f]{64}$'
            AND fixture_world_instance_id IS NOT NULL AND fixture_world_instance_id > 0
            AND fixture_operation_id IS NOT NULL)
    );

ALTER TABLE world_region_draft_execution_manifest
    ADD COLUMN preparation_authority_kind VARCHAR(32) NOT NULL DEFAULT 'HUMAN';

ALTER TABLE world_region_draft_execution_manifest
    ADD COLUMN fixture_provenance_digest VARCHAR(71);

ALTER TABLE world_region_draft_execution_manifest
    ADD COLUMN fixture_world_instance_id BIGINT;

ALTER TABLE world_region_draft_execution_manifest
    ADD COLUMN fixture_operation_id VARCHAR(128);

ALTER TABLE world_region_draft_execution_manifest
    ADD CONSTRAINT fk_region_manifest_fixed_preset_fixture_owner FOREIGN KEY (
        preparation_authority_kind, fixture_provenance_digest, fixture_world_instance_id,
        fixture_operation_id)
    REFERENCES world_canonical_instance_association (
        canonical_authority_kind, fixture_provenance_digest, world_instance_id,
        control_plane_request_id) ON DELETE RESTRICT;

ALTER TABLE world_region_draft_execution_manifest
    ADD CONSTRAINT ck_region_manifest_fixed_preset_authority CHECK (
        (preparation_authority_kind = 'HUMAN' AND fixture_provenance_digest IS NULL
            AND fixture_world_instance_id IS NULL AND fixture_operation_id IS NULL)
        OR (preparation_authority_kind = 'RUN_OWNED_FIXTURE' AND row_operation = 'INSERT' AND expected_old IS NULL
            AND table_name IN ('region','zone','room','room_exit','world_authored_topology_identity') AND fixture_provenance_digest IS NOT NULL
            AND fixture_provenance_digest ~ '^sha256:[0-9a-f]{64}$'
            AND fixture_world_instance_id IS NOT NULL AND fixture_world_instance_id > 0
            AND fixture_operation_id IS NOT NULL)
    );

ALTER TABLE world_canonical_instance_preparation_execution_manifest
    ADD COLUMN preparation_authority_kind VARCHAR(32) NOT NULL DEFAULT 'HUMAN';

ALTER TABLE world_canonical_instance_preparation_execution_manifest
    ADD COLUMN fixture_provenance_digest VARCHAR(71);

ALTER TABLE world_canonical_instance_preparation_execution_manifest
    ADD COLUMN fixture_world_instance_id BIGINT;

ALTER TABLE world_canonical_instance_preparation_execution_manifest
    ADD COLUMN fixture_operation_id VARCHAR(128);

ALTER TABLE world_canonical_instance_preparation_execution_manifest
    ADD CONSTRAINT fk_prepare_manifest_fixed_preset_fixture_owner FOREIGN KEY (
        preparation_authority_kind, fixture_provenance_digest, fixture_world_instance_id,
        fixture_operation_id)
    REFERENCES world_canonical_instance_association (
        canonical_authority_kind, fixture_provenance_digest, world_instance_id,
        control_plane_request_id) ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE world_canonical_instance_preparation_execution_manifest
    ALTER COLUMN capture_id DROP NOT NULL;

ALTER TABLE world_canonical_instance_preparation_execution_manifest
    ADD COLUMN fixture_plan JSONB;

-- PostgreSQL JSON evidence predicates are independently exercised by native owner proof.
-- [jooq ignore start]
-- Replace only the original anonymous table-name CHECK, located by its expression rather
-- than a PostgreSQL/H2 generated name. Human authority keeps its original closed row families.
DO $migration$
DECLARE
    original_name TEXT;
BEGIN
    SELECT c.conname INTO STRICT original_name FROM pg_constraint c
    WHERE c.conrelid = '"${serviceSchema}".world_canonical_instance_preparation_execution_manifest'::REGCLASS
        AND c.contype = 'c' AND pg_get_constraintdef(c.oid) LIKE '%table_name%'
        AND pg_get_constraintdef(c.oid) NOT LIKE '%preparation_authority_kind%';
    EXECUTE format('ALTER TABLE "${serviceSchema}".world_canonical_instance_preparation_execution_manifest DROP CONSTRAINT %I', original_name);
END;
$migration$;
ALTER TABLE world_canonical_instance_preparation_execution_manifest
    ADD CONSTRAINT ck_prepare_manifest_table_name CHECK (
        table_name IN ('world_instance','region_instance','zone_instance','room_instance',
            'room_instance_exit','world_canonical_instance_topology_identity',
            'world_canonical_instance_preparation')
        OR (table_name = 'world_canonical_instance_association'
            AND preparation_authority_kind = 'RUN_OWNED_FIXTURE'));

ALTER TABLE world_canonical_instance_preparation_execution_manifest
    ADD CONSTRAINT ck_prepare_manifest_fixed_preset_authority CHECK (
        (preparation_authority_kind = 'HUMAN' AND capture_id IS NOT NULL
            AND table_name <> 'world_canonical_instance_association'
            AND (table_name <> 'world_canonical_instance_preparation' OR (
                expected_new->>'capture_id' = capture_id::TEXT
                AND expected_new->>'fixed_preset_id' IS NULL
                AND expected_new->>'fixed_preset_content_digest' IS NULL
                AND expected_new->>'fixed_preset_binding_digest' IS NULL))
            AND fixture_plan IS NULL AND fixture_provenance_digest IS NULL
            AND fixture_world_instance_id IS NULL AND fixture_operation_id IS NULL)
        OR (preparation_authority_kind = 'RUN_OWNED_FIXTURE' AND capture_id IS NULL AND fixture_plan IS NOT NULL
            AND jsonb_typeof(fixture_plan) = 'object'
            AND table_name IN ('world_instance','world_canonical_instance_association',
                'region_instance','zone_instance','room_instance','room_instance_exit',
                'world_canonical_instance_topology_identity','world_canonical_instance_preparation')
            AND fixture_provenance_digest IS NOT NULL
            AND fixture_provenance_digest ~ '^sha256:[0-9a-f]{64}$'
            AND fixture_world_instance_id IS NOT NULL AND fixture_world_instance_id > 0
            AND fixture_operation_id IS NOT NULL)
    );

-- [jooq ignore stop]

-- [jooq ignore start]
-- This owner emits two exact allocation manifests, consuming the first through the unchanged
-- tenant guard and the second through the closed fixture association execution arm. Exceptions roll back the whole SQL statement.
CREATE FUNCTION world_allocate_run_owned_fixture_instance(p_world JSONB, p_association JSONB)
RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    world_row "${serviceSchema}".world_instance%ROWTYPE;
    association_row "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    stored_world JSONB;
    stored_association JSONB;
    allocation_plan JSONB;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off'
        OR jsonb_typeof(p_world) IS DISTINCT FROM 'object'
        OR jsonb_typeof(p_association) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Fixture allocation requires its writable READ COMMITTED owner transaction';
    END IF;
    world_row := jsonb_populate_record(NULL::"${serviceSchema}".world_instance,
        p_world || jsonb_build_object('created_at', CURRENT_TIMESTAMP::TIMESTAMP,
            'updated_at', CURRENT_TIMESTAMP::TIMESTAMP, 'row_version', 0));
    association_row := jsonb_populate_record(
        NULL::"${serviceSchema}".world_canonical_instance_association, p_association);
    IF p_world IS DISTINCT FROM to_jsonb(world_row) - ARRAY[
            'created_at','updated_at','row_version','failure_reason',
            'termination_request_id','terminated_at']
        OR p_association IS DISTINCT FROM to_jsonb(association_row)
        OR world_row.id IS NULL OR world_row.id <= 0 THEN
        RAISE EXCEPTION 'Fixture allocation requires complete exact owner row values';
    END IF;
    PERFORM 1 FROM "${serviceSchema}".world_authored_source_intake
        WHERE operation_id = association_row.intake_operation_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Fixture allocation source owner is missing'; END IF;
    PERFORM 1 FROM "${serviceSchema}".world_authored_version_identity
        WHERE operation_id = association_row.version_identity_operation_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Fixture allocation Version owner is missing'; END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest
        WHERE transaction_id = txid_current()) THEN
        RAISE EXCEPTION 'Fixture allocation cannot adopt pending preparation manifests';
    END IF;
    allocation_plan := jsonb_build_object(
        'schema', 'firemud.run-owned-world-allocation.v1',
        'association', to_jsonb(association_row),
        'world', to_jsonb(world_row),
        'gameSessionResponseBytesDigest', 'sha256:' ||
            encode(sha256(association_row.game_session_association_response_bytes), 'hex'));
    -- Only this existing manifest-owner FK is deferred for the first-row statement. No
    -- association-to-World constraint or trigger is deferred; resources stay association-bound.
    SET CONSTRAINTS fk_prepare_manifest_fixed_preset_fixture_owner DEFERRED;
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest (
        transaction_id, table_name, row_id, canonical_game_instance_id, target_namespace,
        canonical_tenant_id, canonical_version_id, version_identity_operation_id, capture_id,
        local_tenant_key, input_digest, expected_new, preparation_authority_kind,
        fixture_provenance_digest, fixture_world_instance_id, fixture_operation_id, fixture_plan)
    VALUES (txid_current(), 'world_instance', world_row.id,
        association_row.canonical_game_instance_id, association_row.canonical_target_namespace,
        association_row.canonical_tenant_id, association_row.canonical_version_id,
        association_row.version_identity_operation_id, NULL, association_row.local_tenant_key,
        association_row.preparation_input_digest, to_jsonb(world_row), 'RUN_OWNED_FIXTURE',
        association_row.fixture_provenance_digest, association_row.world_instance_id,
        association_row.control_plane_request_id, allocation_plan);
    INSERT INTO "${serviceSchema}".world_instance SELECT (world_row).*;
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest (
        transaction_id, table_name, row_id, canonical_game_instance_id, target_namespace,
        canonical_tenant_id, canonical_version_id, version_identity_operation_id, capture_id,
        local_tenant_key, input_digest, expected_new, preparation_authority_kind,
        fixture_provenance_digest, fixture_world_instance_id, fixture_operation_id, fixture_plan)
    VALUES (txid_current(), 'world_canonical_instance_association', world_row.id,
        association_row.canonical_game_instance_id, association_row.canonical_target_namespace,
        association_row.canonical_tenant_id, association_row.canonical_version_id,
        association_row.version_identity_operation_id, NULL, association_row.local_tenant_key,
        association_row.preparation_input_digest, to_jsonb(association_row), 'RUN_OWNED_FIXTURE',
        association_row.fixture_provenance_digest, association_row.world_instance_id,
        association_row.control_plane_request_id, allocation_plan);
    INSERT INTO "${serviceSchema}".world_canonical_instance_association SELECT (association_row).*;
    SELECT to_jsonb(w) INTO STRICT stored_world FROM "${serviceSchema}".world_instance w
        WHERE id = world_row.id FOR UPDATE;
    SELECT to_jsonb(a) INTO STRICT stored_association
        FROM "${serviceSchema}".world_canonical_instance_association a
        WHERE canonical_game_instance_id = association_row.canonical_game_instance_id;
    IF stored_world IS DISTINCT FROM to_jsonb(world_row)
        OR stored_association IS DISTINCT FROM to_jsonb(association_row)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest
            WHERE transaction_id = txid_current()) THEN
        RAISE EXCEPTION 'Fixture allocation changed its exact rows or left an unconsumed manifest';
    END IF;
    SET CONSTRAINTS fk_prepare_manifest_fixed_preset_fixture_owner IMMEDIATE;
    RETURN world_row.id;
END;
$$;
REVOKE ALL ON FUNCTION world_allocate_run_owned_fixture_instance(JSONB, JSONB) FROM PUBLIC;

CREATE FUNCTION world_verify_run_owned_fixed_preset_plan(
    p JSONB, p_expected_content_digest TEXT, p_world_instance_id BIGINT,
    p_private_game_instance_key BIGINT
) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    association "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    instance "${serviceSchema}".world_instance%ROWTYPE;
    version_identity "${serviceSchema}".world_authored_version_identity%ROWTYPE;
    source_intake "${serviceSchema}".world_authored_source_intake%ROWTYPE;
    publication_owner "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    actual_keys TEXT[];
    seal_bytes BYTEA := ''::BYTEA;
    seal_value TEXT;
BEGIN
    IF p IS NULL OR jsonb_typeof(p) IS DISTINCT FROM 'object'
        OR p_expected_content_digest IS DISTINCT FROM
            'sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc'
        OR p->>'expectedContentDigest' IS DISTINCT FROM p_expected_content_digest
        OR p->>'schemaVersion' IS DISTINCT FROM '1'
        OR p->>'presetId' IS DISTINCT FROM 'firemud-demo-topology-v1'
        OR p->>'presetSealDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR p->>'lifecycleBindingDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR p->>'gameSessionResponseBytesDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR p->>'fixturePolicyBytesDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR p->>'fixturePolicyRequestDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR p->>'fixturePolicyEvidenceDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR p->>'claimDigest' !~ '^[0-9a-f]{64}$'
        OR p->>'playableStateScope' IS DISTINCT FROM 'SHARED'
        OR jsonb_typeof(p->'topology') IS DISTINCT FROM 'array'
        OR p->'topology' IS DISTINCT FROM jsonb_build_array(
            jsonb_build_object('family','REGION',
                'templateId','9affa11b-02f5-3cc9-a50e-6760083c71e9',
                'name','Demo Region','spacingMultiplier',1.0,'generationSeed',0),
            jsonb_build_object('family','ZONE',
                'templateId','936c4d65-c460-3b73-9f4e-6ee6295bca24',
                'parentRegionTemplateId','9affa11b-02f5-3cc9-a50e-6760083c71e9',
                'name','Demo Zone'),
            jsonb_build_object('family','ROOM',
                'templateId','c6058518-f9e0-3f26-9b05-2bc786095089',
                'parentZoneTemplateId','936c4d65-c460-3b73-9f4e-6ee6295bca24',
                'name','Candle-lit Antechamber',
                'description','Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.'),
            jsonb_build_object('family','ROOM',
                'templateId','e74cca03-3b53-3edc-84f5-890651f5fea8',
                'parentZoneTemplateId','936c4d65-c460-3b73-9f4e-6ee6295bca24',
                'name','Smith''s Annex',
                'description','An anvil, banked coals, and orderly tool racks mark this alcove as a working annex off the starter chamber.'),
            jsonb_build_object('family','ROOM_EXIT',
                'templateId','18c4b7a2-6498-3865-a464-814b56e4d4c2',
                'fromRoomTemplateId','c6058518-f9e0-3f26-9b05-2bc786095089',
                'toRoomTemplateId','e74cca03-3b53-3edc-84f5-890651f5fea8',
                'direction','NORTH','cost',1),
            jsonb_build_object('family','ROOM_EXIT',
                'templateId','ab46ef8f-e74f-3197-be62-9785ef63242e',
                'fromRoomTemplateId','e74cca03-3b53-3edc-84f5-890651f5fea8',
                'toRoomTemplateId','c6058518-f9e0-3f26-9b05-2bc786095089',
                'direction','SOUTH','cost',1)) THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_PLAN_INVALID: plan is not the closed canonical preset'
            USING ERRCODE = '23514';
    END IF;

    SELECT array_agg(k ORDER BY k) INTO actual_keys FROM jsonb_object_keys(p) AS keys(k);
    IF actual_keys IS DISTINCT FROM ARRAY[
        'canonicalRealmId','canonicalTenantId','canonicalVersionId','catalogRevision',
        'claimDigest','descriptorRequestDigest','descriptorResultDigest','descriptorRuntimeFlagsJson','expectedContentDigest',
        'fixtureOperationId','fixturePolicyBytesDigest','fixturePolicyEvidenceDigest',
        'fixturePolicyRequestDigest','gameDesignVersionId','gameSessionCanonicalTenantId',
        'gameSessionCanonicalVersionId','gameSessionGameInstanceId','gameSessionGameInstanceUuid',
        'gameSessionResponseBytesDigest','gameSessionTenantId','gameTemplateId',
        'generationConfigRevision','launchDescriptorId','launchGameDesignVersionId',
        'lifecycleBindingDigest','manifestHash','playableStateNamespaceId','playableStateScope',
        'presetId','presetSealDigest','publicProduction','publishedReleaseBundleRef','releaseBundleId',
        'schemaVersion','sourceEvidenceDigest','sourceOperationId','sourceReceiptDigest',
        'targetNamespace','topology','versionEvidenceDigest','versionStateEpoch','worldLocalTenantKey',
        'worldLocalVersionKey','worldSlug','worldSourceIntakeOperationId',
        'worldVersionIdentityOperationId'
    ]::TEXT[] THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_PLAN_INVALID: unknown or missing plan field'
            USING ERRCODE = '23514';
    END IF;
    -- Match RunOwnedWorldFixedPresetResources.add: big-endian int32 UTF-8 byte length,
    -- followed by the exact value bytes, in the Java binding-domain order.
    FOREACH seal_value IN ARRAY ARRAY[
        'firemud-world-run-owned-fixed-preset-binding/v1',
        p->>'presetId',
        p->>'expectedContentDigest',
        p->>'targetNamespace',
        p->>'fixtureOperationId',
        p->>'claimDigest',
        p->>'fixturePolicyRequestDigest',
        p->>'fixturePolicyEvidenceDigest',
        p->>'fixturePolicyBytesDigest',
        p->>'lifecycleBindingDigest',
        p->>'gameSessionResponseBytesDigest',
        (p->>'canonicalTenantId')::UUID::TEXT,
        p->>'worldSlug',
        (p->>'worldSourceIntakeOperationId')::UUID::TEXT,
        (p->>'sourceOperationId')::UUID::TEXT,
        p->>'sourceEvidenceDigest',
        p->>'sourceReceiptDigest',
        (p->>'worldLocalTenantKey')::BIGINT::TEXT,
        (p->>'worldVersionIdentityOperationId')::UUID::TEXT,
        (p->>'canonicalVersionId')::UUID::TEXT,
        (p->>'gameDesignVersionId')::BIGINT::TEXT,
        p->>'versionEvidenceDigest',
        (p->>'worldLocalVersionKey')::BIGINT::TEXT,
        p->>'launchDescriptorId',
        p->>'descriptorRequestDigest',
        p->>'descriptorResultDigest',
        p->>'descriptorRuntimeFlagsJson',
        (p->>'launchGameDesignVersionId')::BIGINT::TEXT,
        (p->>'releaseBundleId')::BIGINT::TEXT,
        p->>'publishedReleaseBundleRef',
        p->>'manifestHash',
        p->>'generationConfigRevision',
        (p->>'versionStateEpoch')::BIGINT::TEXT,
        (p->>'gameSessionTenantId')::BIGINT::TEXT,
        (p->>'gameSessionGameInstanceId')::BIGINT::TEXT,
        (p->>'gameSessionGameInstanceUuid')::UUID::TEXT,
        (p->>'gameSessionCanonicalTenantId')::UUID::TEXT,
        (p->>'gameSessionCanonicalVersionId')::UUID::TEXT,
        (p->>'playableStateNamespaceId')::UUID::TEXT,
        (p->>'canonicalRealmId')::UUID::TEXT,
        p->>'playableStateScope',
        (p->>'publicProduction')::BOOLEAN::TEXT,
        (p->>'catalogRevision')::BIGINT::TEXT,
        (p->>'gameTemplateId')::BIGINT::TEXT
    ]::TEXT[] LOOP
        IF seal_value IS NULL THEN
            RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_PLAN_INVALID: missing seal value'
                USING ERRCODE = '23514';
        END IF;
        seal_bytes := seal_bytes || int4send(octet_length(convert_to(seal_value, 'UTF8')))
            || convert_to(seal_value, 'UTF8');
    END LOOP;
    IF p->>'presetSealDigest' IS DISTINCT FROM
        'sha256:' || encode(sha256(seal_bytes), 'hex') THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_PLAN_INVALID: preset seal differs'
            USING ERRCODE = '23514';
    END IF;

    IF p_world_instance_id <= 0 OR p_private_game_instance_key <= 0 THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_ALLOCATION_INVALID' USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT association
    FROM "${serviceSchema}".world_canonical_instance_association
    WHERE world_instance_id = p_world_instance_id
        AND canonical_game_instance_id = (p->>'gameSessionGameInstanceUuid')::UUID;
    SELECT * INTO STRICT instance
    FROM "${serviceSchema}".world_instance
    WHERE id = association.world_instance_id;
    SELECT * INTO STRICT version_identity
    FROM "${serviceSchema}".world_authored_version_identity
    WHERE operation_id = association.version_identity_operation_id;
    SELECT * INTO STRICT source_intake
    FROM "${serviceSchema}".world_authored_source_intake
    WHERE operation_id = association.intake_operation_id;
    SELECT * INTO STRICT publication_owner
    FROM "${serviceSchema}".world_design_publication_fence_owner
    WHERE target_namespace = version_identity.target_namespace
        AND canonical_tenant_id = version_identity.canonical_tenant_id
        AND local_tenant_key = version_identity.local_tenant_key
        AND version_id = version_identity.local_version_key;

    IF association.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
        OR association.control_plane_request_id IS DISTINCT FROM p->>'fixtureOperationId'
        OR association.canonical_game_instance_id IS DISTINCT FROM (p->>'gameSessionGameInstanceUuid')::UUID
        OR association.canonical_target_namespace IS DISTINCT FROM p->>'targetNamespace'
        OR association.canonical_tenant_id IS DISTINCT FROM (p->>'canonicalTenantId')::UUID
        OR association.canonical_world_slug IS DISTINCT FROM p->>'worldSlug'
        OR association.playable_state_namespace_id IS DISTINCT FROM (p->>'playableStateNamespaceId')::UUID
        OR association.playable_state_scope IS DISTINCT FROM p->>'playableStateScope'
        OR association.public_production IS DISTINCT FROM (p->>'publicProduction')::BOOLEAN
        OR association.canonical_realm_id IS DISTINCT FROM (p->>'canonicalRealmId')::UUID
        OR association.catalog_revision IS DISTINCT FROM (p->>'catalogRevision')::BIGINT
        OR association.fixture_provenance_digest IS DISTINCT FROM p->>'fixturePolicyEvidenceDigest'
        OR association.preparation_input_digest IS DISTINCT FROM p->>'lifecycleBindingDigest'
        OR association.game_session_tenant_id IS DISTINCT FROM (p->>'gameSessionTenantId')::BIGINT
        OR association.game_session_game_instance_id IS DISTINCT FROM (p->>'gameSessionGameInstanceId')::BIGINT
        OR association.game_design_version_id IS DISTINCT FROM (p->>'gameDesignVersionId')::BIGINT
        OR association.version_identity_operation_id IS DISTINCT FROM (p->>'worldVersionIdentityOperationId')::UUID
        OR association.canonical_version_id IS DISTINCT FROM (p->>'canonicalVersionId')::UUID
        OR association.local_tenant_key IS DISTINCT FROM (p->>'worldLocalTenantKey')::BIGINT
        OR association.private_game_instance_key IS DISTINCT FROM p_private_game_instance_key
        OR association.local_version_key IS DISTINCT FROM (p->>'worldLocalVersionKey')::BIGINT
        OR association.game_template_id IS DISTINCT FROM (p->>'gameTemplateId')::BIGINT
        OR association.launch_descriptor_id IS DISTINCT FROM p->>'launchDescriptorId'
        OR association.script_patch_version IS NOT NULL
        OR association.runtime_flags_json IS DISTINCT FROM p->>'descriptorRuntimeFlagsJson'
        OR association.descriptor_request_digest IS DISTINCT FROM p->>'descriptorRequestDigest'
        OR association.descriptor_result_digest IS DISTINCT FROM p->>'descriptorResultDigest'
        OR association.release_bundle_id IS DISTINCT FROM (p->>'releaseBundleId')::BIGINT
        OR association.published_release_bundle_ref IS DISTINCT FROM p->>'publishedReleaseBundleRef'
        OR association.generation_config_revision IS DISTINCT FROM p->>'generationConfigRevision'
        OR association.version_state_epoch IS DISTINCT FROM (p->>'versionStateEpoch')::BIGINT
        OR association.remap_set_id IS NOT NULL
        OR association.intake_operation_id IS DISTINCT FROM (p->>'worldSourceIntakeOperationId')::UUID
        OR association.intake_request_id IS DISTINCT FROM source_intake.intake_request_id
        OR association.source_operation_id IS DISTINCT FROM (p->>'sourceOperationId')::UUID
        OR association.source_evidence_digest IS DISTINCT FROM p->>'sourceEvidenceDigest'
        OR association.intake_request_digest IS DISTINCT FROM source_intake.request_digest
        OR association.intake_receipt_digest IS DISTINCT FROM p->>'sourceReceiptDigest'
        OR association.game_session_association_response_bytes IS NULL
        OR 'sha256:' || encode(sha256(association.game_session_association_response_bytes), 'hex')
            IS DISTINCT FROM p->>'gameSessionResponseBytesDigest'
        OR instance.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
        OR instance.fixture_provenance_digest IS DISTINCT FROM association.fixture_provenance_digest
        OR instance.game_session_tenant_id IS DISTINCT FROM association.game_session_tenant_id
        OR instance.game_session_game_instance_id IS DISTINCT FROM association.game_session_game_instance_id
        OR instance.game_design_version_id IS DISTINCT FROM association.game_design_version_id
        OR (p->>'gameSessionCanonicalTenantId')::UUID IS DISTINCT FROM association.canonical_tenant_id
        OR (p->>'gameSessionCanonicalVersionId')::UUID IS DISTINCT FROM association.canonical_version_id
        OR (p->>'launchGameDesignVersionId')::BIGINT IS DISTINCT FROM association.game_design_version_id
        OR instance.tenant_id IS DISTINCT FROM association.local_tenant_key
        OR instance.game_instance_id IS DISTINCT FROM p_private_game_instance_key
        OR instance.version_id IS DISTINCT FROM association.local_version_key
        OR instance.game_template_id IS DISTINCT FROM (p->>'gameTemplateId')::BIGINT
        OR instance.control_plane_request_id IS DISTINCT FROM p->>'fixtureOperationId'
        OR instance.launch_descriptor_id IS DISTINCT FROM p->>'launchDescriptorId'
        OR instance.script_patch_version IS NOT NULL
        OR instance.runtime_flags_json IS DISTINCT FROM p->>'descriptorRuntimeFlagsJson'
        OR instance.generation_config_revision IS DISTINCT FROM p->>'generationConfigRevision'
        OR instance.release_bundle_id IS DISTINCT FROM (p->>'releaseBundleId')::BIGINT
        OR instance.published_release_bundle_ref IS DISTINCT FROM p->>'publishedReleaseBundleRef'
        OR instance.version_state_epoch IS DISTINCT FROM (p->>'versionStateEpoch')::BIGINT
        OR instance.remap_set_id IS NOT NULL
        OR instance.canonical_game_instance_id IS DISTINCT FROM association.canonical_game_instance_id
        OR instance.canonical_target_namespace IS DISTINCT FROM association.canonical_target_namespace
        OR instance.canonical_tenant_id IS DISTINCT FROM association.canonical_tenant_id
        OR instance.canonical_world_slug IS DISTINCT FROM association.canonical_world_slug
        OR instance.playable_state_namespace_id IS DISTINCT FROM association.playable_state_namespace_id
        OR instance.playable_state_scope IS DISTINCT FROM association.playable_state_scope
        OR instance.public_production IS DISTINCT FROM association.public_production
        OR instance.canonical_realm_id IS DISTINCT FROM association.canonical_realm_id
        OR instance.catalog_revision IS DISTINCT FROM association.catalog_revision
        OR instance.status IS DISTINCT FROM 'PREPARING'
        OR instance.lifecycle_epoch IS DISTINCT FROM association.preparing_lifecycle_epoch
        OR instance.run_owned_fixture_preparing_lifecycle_epoch IS DISTINCT FROM association.preparing_lifecycle_epoch
        OR 'sha256:' || instance.run_owned_fixture_binding_sha256 IS DISTINCT FROM association.preparation_input_digest
        OR version_identity.target_namespace IS DISTINCT FROM p->>'targetNamespace'
        OR version_identity.canonical_tenant_id IS DISTINCT FROM (p->>'canonicalTenantId')::UUID
        OR version_identity.world_slug IS DISTINCT FROM p->>'worldSlug'
        OR version_identity.canonical_version_id IS DISTINCT FROM (p->>'canonicalVersionId')::UUID
        OR version_identity.game_design_version_id IS DISTINCT FROM (p->>'gameDesignVersionId')::BIGINT
        OR version_identity.local_tenant_key IS DISTINCT FROM (p->>'worldLocalTenantKey')::BIGINT
        OR version_identity.local_version_key IS DISTINCT FROM (p->>'worldLocalVersionKey')::BIGINT
        OR version_identity.intake_operation_id IS DISTINCT FROM source_intake.operation_id
        OR version_identity.intake_request_id IS DISTINCT FROM source_intake.intake_request_id
        OR version_identity.intake_request_digest IS DISTINCT FROM source_intake.request_digest
        OR version_identity.version_state_evidence_digest IS DISTINCT FROM p->>'versionEvidenceDigest'
        OR version_identity.version_state_state NOT IN ('PUBLISHED','ACTIVE')
        OR version_identity.source_operation_id IS DISTINCT FROM (p->>'sourceOperationId')::UUID
        OR version_identity.source_evidence_digest IS DISTINCT FROM p->>'sourceEvidenceDigest'
        OR version_identity.intake_receipt_digest IS DISTINCT FROM p->>'sourceReceiptDigest'
        OR source_intake.target_namespace IS DISTINCT FROM p->>'targetNamespace'
        OR source_intake.operation_id IS DISTINCT FROM (p->>'worldSourceIntakeOperationId')::UUID
        OR source_intake.canonical_tenant_id IS DISTINCT FROM (p->>'canonicalTenantId')::UUID
        OR source_intake.world_slug IS DISTINCT FROM p->>'worldSlug'
        OR source_intake.local_tenant_key IS DISTINCT FROM (p->>'worldLocalTenantKey')::BIGINT
        OR association.intake_request_digest IS DISTINCT FROM source_intake.request_digest
        OR source_intake.source_operation_id IS DISTINCT FROM (p->>'sourceOperationId')::UUID
        OR source_intake.source_evidence_digest IS DISTINCT FROM p->>'sourceEvidenceDigest'
        OR source_intake.receipt_digest IS DISTINCT FROM p->>'sourceReceiptDigest'
        OR source_intake.source_provenance_kind IS DISTINCT FROM 'NEW_GAME_ROW'
        OR publication_owner.owner_freeze_phase IS DISTINCT FROM 'OPEN'
        OR publication_owner.current_publication_fence IS NOT NULL
        OR NOT EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_authored_source_tenant_key_reservation reservation
            WHERE reservation.tenant_key = association.local_tenant_key
                AND reservation.claim_kind = 'CANONICAL_AUTHORED_SOURCE') THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_ASSOCIATION_MISMATCH: retained owners or allocation differ'
            USING ERRCODE = '23514';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION world_verify_run_owned_fixed_preset_plan(JSONB, TEXT, BIGINT, BIGINT)
    FROM PUBLIC;

-- Fixture preparation retains no Human selected-start witness. Extend only the retainer's
-- fixture arm; the current Human schema/receipt/capture/mapping body remains unchanged.
DO $migration$
DECLARE
    original TEXT;
    declaration_anchor TEXT := $anchor$    frozen "${serviceSchema}".world_canonical_frozen_topology%ROWTYPE;$anchor$;
    begin_anchor TEXT := $anchor$BEGIN
    IF NEW.input_json::JSONB->>'schemaVersion' = '3' THEN RETURN NEW; END IF;$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_retain_preparation_start_location()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,declaration_anchor,'')))/length(declaration_anchor) <> 1
        OR (length(original)-length(replace(original,begin_anchor,'')))/length(begin_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact current Human preparation start-location retainer anchors';
    END IF;
    original := replace(original, declaration_anchor, $replacement$    frozen "${serviceSchema}".world_canonical_frozen_topology%ROWTYPE;
    fixture_owner "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    fixture_plan JSONB;$replacement$);
    original := replace(original, begin_anchor, $replacement$BEGIN
    IF TG_OP = 'INSERT' AND NEW.fixed_preset_id IS NOT NULL THEN
        SELECT a.* INTO STRICT fixture_owner
        FROM "${serviceSchema}".world_canonical_instance_association a
        WHERE a.world_instance_id = NEW.world_instance_id
            AND a.canonical_game_instance_id = NEW.canonical_game_instance_id;
        fixture_plan := NEW.input_json::JSONB;
        IF fixture_owner.schema_version IS DISTINCT FROM 3
            OR fixture_owner.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
            OR NEW.capture_id IS NOT NULL
            OR fixture_owner.canonical_launch_binding_operation_id IS NOT NULL
            OR fixture_owner.release_attestation_digest IS NOT NULL
            OR fixture_plan ?| ARRAY['worldStartLocationEvidenceBase64','worldStartLocationEvidence',
                'launchBinding','captureId','frozenTopology']
            OR NEW.fixed_preset_id IS DISTINCT FROM 'firemud-demo-topology-v1'
            OR NEW.fixed_preset_content_digest IS DISTINCT FROM
                'sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc'
            OR NEW.fixed_preset_binding_digest IS DISTINCT FROM fixture_plan->>'presetSealDigest'
            OR NEW.input_digest IS DISTINCT FROM fixture_plan->>'presetSealDigest'
            OR NEW.target_namespace IS DISTINCT FROM fixture_owner.canonical_target_namespace
            OR NEW.canonical_tenant_id IS DISTINCT FROM fixture_owner.canonical_tenant_id
            OR NEW.world_slug IS DISTINCT FROM fixture_owner.canonical_world_slug
            OR NEW.control_plane_request_id IS DISTINCT FROM fixture_owner.control_plane_request_id
            OR NEW.canonical_version_id IS DISTINCT FROM fixture_owner.canonical_version_id
            OR NEW.version_identity_operation_id IS DISTINCT FROM fixture_owner.version_identity_operation_id
            OR NEW.private_game_instance_key IS DISTINCT FROM fixture_owner.private_game_instance_key
            OR NEW.graph_bytes IS DISTINCT FROM convert_to(NEW.input_json, 'UTF8')
            OR NEW.graph_sha256 IS DISTINCT FROM encode(sha256(convert_to(NEW.input_json, 'UTF8')), 'hex')
            OR NEW.storage_status IS DISTINCT FROM 'MATERIALIZED_UNVERIFIED'
            OR NEW.region_count IS DISTINCT FROM 1 OR NEW.zone_count IS DISTINCT FROM 1
            OR NEW.room_count IS DISTINCT FROM 2 OR NEW.exit_count IS DISTINCT FROM 2
            OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_preparation_start_location selected
                WHERE selected.world_instance_id = NEW.world_instance_id
                    OR selected.canonical_game_instance_id = NEW.canonical_game_instance_id)
            OR NOT EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation retained
                WHERE retained.id = NEW.id AND to_jsonb(retained) = to_jsonb(NEW)
                    AND retained.xmin::TEXT = (txid_current() % 4294967296)::TEXT)
            OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest pending
                WHERE pending.transaction_id = txid_current()
                    AND pending.table_name = 'world_canonical_instance_preparation'
                    AND pending.row_id = NEW.id) THEN
            RAISE EXCEPTION 'Fixture preparation differs from its exact manifest-owned result or carries Human start-location authority'
                USING ERRCODE = '23514';
        END IF;
        -- The BEFORE preparation guard has already consumed this row's exact manifest. This
        -- AFTER retainer independently rechecks the actual fixture/source/Version owners and
        -- the original GS bytes, plan seal and complete preset before omitting Human metadata.
        PERFORM "${serviceSchema}".world_verify_run_owned_fixed_preset_plan(
            fixture_plan,
            'sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc',
            NEW.world_instance_id, NEW.private_game_instance_key);
        RETURN NEW;
    END IF;
    IF NEW.input_json::JSONB->>'schemaVersion' = '3' THEN RETURN NEW; END IF;$replacement$);
    EXECUTE original;
END;
$migration$;


-- V29's original Human owner/fence checks remain the first consumed-once branch.
-- The fixture branch rechecks the exact plan against V67 plus actual source/version evidence.
CREATE OR REPLACE FUNCTION world_consume_region_execution_manifest(
    actual_table TEXT, actual_operation TEXT, actual_old JSONB, actual_new JSONB
) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    consumed BIGINT;
    fixture_manifest "${serviceSchema}".world_region_draft_execution_manifest%ROWTYPE;
    association "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
BEGIN
    IF actual_operation NOT IN ('INSERT', 'UPDATE') OR actual_new IS NULL THEN
        RETURN FALSE;
    END IF;
    DELETE FROM "${serviceSchema}".world_region_draft_execution_manifest m
    USING "${serviceSchema}".world_authored_version_identity v,
        "${serviceSchema}".world_design_publication_fence_owner o
    WHERE m.preparation_authority_kind = 'HUMAN'
        AND (actual_table <> 'world_authored_topology_identity'
            OR actual_new->>'preparation_authority_kind' IS NULL
            OR actual_new->>'preparation_authority_kind' = 'HUMAN')
        AND m.transaction_id = txid_current()
        AND m.table_name = actual_table AND m.row_operation = actual_operation
        AND m.row_id = (actual_new->>'id')::BIGINT
        AND m.expected_old IS NOT DISTINCT FROM actual_old
        AND m.expected_new = actual_new
        AND m.local_tenant_key = (actual_new->>'tenant_id')::BIGINT
        AND m.local_version_key = (actual_new->>'version_id')::BIGINT
        AND v.operation_id = m.version_identity_operation_id
        AND v.local_tenant_key = m.local_tenant_key
        AND v.local_version_key = m.local_version_key
        AND v.operation_id::TEXT = m.owner_binding->>'versionIdentityOperationId'
        AND v.target_namespace = m.owner_binding->>'targetNamespace'
        AND v.canonical_tenant_id::TEXT = m.owner_binding->>'canonicalTenantId'
        AND v.canonical_version_id::TEXT = m.owner_binding->>'canonicalVersionId'
        AND v.intake_operation_id::TEXT = m.owner_binding->>'intakeOperationId'
        AND v.intake_request_id::TEXT = m.owner_binding->>'intakeRequestId'
        AND v.intake_request_digest = m.owner_binding->>'intakeRequestDigest'
        AND v.source_operation_id::TEXT = m.owner_binding->>'sourceOperationId'
        AND v.source_evidence_digest = m.owner_binding->>'sourceEvidenceDigest'
        AND v.intake_receipt_digest = m.owner_binding->>'intakeReceiptDigest'
        AND v.game_design_version_id = (m.owner_binding->>'gameDesignVersionId')::BIGINT
        AND v.canonical_tenant_id::TEXT = m.complete_binding->>'canonicalTenantId'
        AND v.canonical_version_id::TEXT = m.complete_binding->>'canonicalVersionId'
        AND m.operation_id::TEXT = m.complete_binding->>'commitId'
        AND o.target_namespace = v.target_namespace
        AND o.canonical_tenant_id = v.canonical_tenant_id
        AND o.local_tenant_key = v.local_tenant_key AND o.version_id = v.local_version_key
        AND o.owner_freeze_phase = 'OPEN' AND o.current_publication_fence IS NULL
    RETURNING m.row_id INTO consumed;
    IF consumed IS NOT NULL THEN RETURN TRUE; END IF;
    IF actual_operation <> 'INSERT' OR actual_old IS NOT NULL THEN RETURN FALSE; END IF;
    SELECT * INTO fixture_manifest
    FROM "${serviceSchema}".world_region_draft_execution_manifest m
    WHERE m.transaction_id = txid_current()
        AND m.preparation_authority_kind = 'RUN_OWNED_FIXTURE'
        AND m.table_name = actual_table AND m.row_operation = actual_operation
        AND m.row_id = (actual_new->>'id')::BIGINT
        AND m.expected_old IS NULL AND m.expected_new = actual_new;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO STRICT association
    FROM "${serviceSchema}".world_canonical_instance_association
    WHERE world_instance_id = fixture_manifest.fixture_world_instance_id;
    PERFORM "${serviceSchema}".world_verify_run_owned_fixed_preset_plan(
        fixture_manifest.complete_binding,
        'sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc',
        association.world_instance_id, association.private_game_instance_key);
    DELETE FROM "${serviceSchema}".world_region_draft_execution_manifest m
    USING "${serviceSchema}".world_authored_version_identity v,
        "${serviceSchema}".world_instance wi
    WHERE m.transaction_id = txid_current()
        AND m.preparation_authority_kind = 'RUN_OWNED_FIXTURE'
        AND m.table_name = actual_table AND m.row_operation = 'INSERT'
        AND m.row_id = (actual_new->>'id')::BIGINT
        AND m.expected_old IS NULL AND m.expected_new = actual_new
        AND m.fixture_world_instance_id = association.world_instance_id
        AND m.fixture_operation_id = association.control_plane_request_id
        AND m.operation_id::TEXT = association.control_plane_request_id
        AND m.fixture_provenance_digest = association.fixture_provenance_digest
        AND association.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        AND m.complete_binding = fixture_manifest.complete_binding
        AND v.operation_id = m.version_identity_operation_id
        AND v.operation_id = association.version_identity_operation_id
        AND m.local_tenant_key = association.local_tenant_key
        AND m.local_version_key = association.local_version_key
        AND m.local_tenant_key = (actual_new->>'tenant_id')::BIGINT
        AND m.local_version_key = (actual_new->>'version_id')::BIGINT
        AND m.owner_binding = jsonb_build_object(
            'targetNamespace',v.target_namespace,'canonicalTenantId',v.canonical_tenant_id,
            'canonicalVersionId',v.canonical_version_id,'versionIdentityOperationId',v.operation_id,
            'gameDesignVersionId',v.game_design_version_id,'intakeRequestId',v.intake_request_id,
            'intakeOperationId',v.intake_operation_id,'intakeRequestDigest',v.intake_request_digest,
            'sourceOperationId',v.source_operation_id,'sourceEvidenceDigest',v.source_evidence_digest,
            'intakeReceiptDigest',v.intake_receipt_digest)
        AND wi.id = association.world_instance_id
        AND wi.status = 'PREPARING' AND wi.lifecycle_epoch = association.preparing_lifecycle_epoch
        AND (actual_table <> 'world_authored_topology_identity' OR (
            actual_new->>'preparation_authority_kind' = 'RUN_OWNED_FIXTURE'
            AND actual_new->>'fixture_world_instance_id' = association.world_instance_id::TEXT
            AND actual_new->>'fixture_operation_id' = association.control_plane_request_id
            AND actual_new->>'fixture_provenance_digest' = association.fixture_provenance_digest
            AND actual_new->>'target_namespace' = association.canonical_target_namespace
            AND actual_new->>'canonical_tenant_id' = association.canonical_tenant_id::TEXT
            AND actual_new->>'canonical_version_id' = association.canonical_version_id::TEXT
            AND actual_new->>'version_identity_operation_id' = association.version_identity_operation_id::TEXT
            AND actual_new->>'request_id' IS NULL AND actual_new->>'commit_id' IS NULL
            AND actual_new->>'revision_id' IS NULL AND actual_new->>'revision_order' IS NULL))
    RETURNING m.row_id INTO consumed;
    RETURN consumed IS NOT NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_consume_region_execution_manifest(TEXT, TEXT, JSONB, JSONB) FROM PUBLIC;

-- Keep the original Human frozen-capture consumer byte-for-byte in its first DELETE. The second
-- one-use fixture branch accepts the atomic allocation image before its association, then
-- accepts runtime/preparation rows only against the retained V67 owner and lifecycle commitment.
CREATE OR REPLACE FUNCTION world_consume_canonical_prepare_execution_manifest(
    actual_table TEXT, actual_operation TEXT, actual_old JSONB, actual_new JSONB
) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    consumed BIGINT;
    allocation_world JSONB;
    fixture_manifest "${serviceSchema}".world_canonical_instance_preparation_execution_manifest%ROWTYPE;
    fixture_association "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
BEGIN
    IF actual_operation = 'UPDATE' THEN
        RETURN FALSE;
    END IF;
    IF actual_operation <> 'INSERT' OR actual_old IS NOT NULL OR actual_new IS NULL THEN
        RETURN FALSE;
    END IF;
    DELETE FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest m
    USING "${serviceSchema}".world_authored_version_identity v,
        "${serviceSchema}".world_canonical_frozen_topology f,
        "${serviceSchema}".world_authored_source_tenant_key_reservation r
    WHERE m.transaction_id = txid_current()
        AND m.table_name = actual_table
        AND m.row_id = (actual_new->>'id')::BIGINT
        AND m.expected_new = actual_new
        AND v.operation_id = m.version_identity_operation_id
        AND v.target_namespace = m.target_namespace
        AND v.canonical_tenant_id = m.canonical_tenant_id
        AND v.canonical_version_id = m.canonical_version_id
        AND v.local_tenant_key = m.local_tenant_key
        AND f.capture_id = m.capture_id
        AND f.version_identity_operation_id = v.operation_id
        AND f.capture_status = 'CAPTURED_UNVERIFIED'
        AND r.tenant_key = m.local_tenant_key
        AND r.claim_kind = 'CANONICAL_AUTHORED_SOURCE'
        AND (
            (actual_table = 'world_instance'
                AND actual_new->>'canonical_game_instance_id' = m.canonical_game_instance_id::TEXT
                AND actual_new->>'canonical_target_namespace' = m.target_namespace
                AND actual_new->>'canonical_tenant_id' = m.canonical_tenant_id::TEXT
                AND actual_new->>'tenant_id' = m.local_tenant_key::TEXT)
            OR (actual_table IN ('region_instance', 'zone_instance', 'room_instance', 'room_instance_exit')
                AND actual_new->>'tenant_id' = m.local_tenant_key::TEXT
                AND EXISTS (
                SELECT 1 FROM "${serviceSchema}".world_instance wi
                WHERE wi.canonical_game_instance_id = m.canonical_game_instance_id
                    AND wi.tenant_id = m.local_tenant_key
                    AND wi.game_instance_id = (actual_new->>'game_instance_id')::BIGINT
                    AND wi.canonical_target_namespace = m.target_namespace
                    AND wi.canonical_tenant_id = m.canonical_tenant_id))
            OR (actual_table IN ('world_canonical_instance_topology_identity', 'world_canonical_instance_preparation')
                AND actual_new->>'canonical_game_instance_id' = m.canonical_game_instance_id::TEXT
                AND EXISTS (
                SELECT 1 FROM "${serviceSchema}".world_instance wi
                WHERE wi.id = (actual_new->>'world_instance_id')::BIGINT
                    AND wi.canonical_game_instance_id = m.canonical_game_instance_id
                    AND wi.tenant_id = m.local_tenant_key
                    AND wi.canonical_target_namespace = m.target_namespace
                    AND wi.canonical_tenant_id = m.canonical_tenant_id))
        )
    RETURNING m.row_id INTO consumed;
    IF consumed IS NOT NULL THEN RETURN TRUE; END IF;

    SELECT * INTO fixture_manifest
    FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest m
    WHERE m.transaction_id = txid_current()
        AND m.preparation_authority_kind = 'RUN_OWNED_FIXTURE'
        AND m.table_name = actual_table AND m.row_id = CASE
            WHEN actual_table = 'world_canonical_instance_association'
                THEN (actual_new->>'world_instance_id')::BIGINT
            ELSE (actual_new->>'id')::BIGINT END
        AND m.expected_new = actual_new;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    -- The two allocation statements share one complete row/owner image and original GS bytes.
    -- The second consumes its own manifest only against the independently stored first row.
    IF actual_table IN ('world_instance','world_canonical_instance_association') THEN
        IF fixture_manifest.fixture_plan->>'schema' IS DISTINCT FROM
                'firemud.run-owned-world-allocation.v1'
            OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(fixture_manifest.fixture_plan) k)
                IS DISTINCT FROM ARRAY['association','gameSessionResponseBytesDigest','schema','world']::TEXT[] THEN
            RETURN FALSE;
        END IF;
        fixture_association := jsonb_populate_record(
            NULL::"${serviceSchema}".world_canonical_instance_association,
            fixture_manifest.fixture_plan->'association');
        IF actual_table = 'world_instance' THEN
            allocation_world := actual_new;
        ELSE
            SELECT to_jsonb(w) INTO allocation_world FROM "${serviceSchema}".world_instance w
                WHERE w.id = fixture_association.world_instance_id FOR KEY SHARE;
            IF NOT FOUND OR actual_new IS DISTINCT FROM to_jsonb(fixture_association) THEN
                RETURN FALSE;
            END IF;
        END IF;
        IF allocation_world IS DISTINCT FROM fixture_manifest.fixture_plan->'world'
            OR to_jsonb(fixture_association) IS DISTINCT FROM fixture_manifest.fixture_plan->'association'
            OR fixture_association.schema_version IS DISTINCT FROM 3
            OR fixture_association.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
            OR fixture_association.world_instance_id IS DISTINCT FROM fixture_manifest.row_id
            OR fixture_association.preparing_lifecycle_epoch IS DISTINCT FROM 1
            OR fixture_association.canonical_launch_binding_operation_id IS NOT NULL
            OR fixture_association.release_attestation_digest IS NOT NULL
            OR fixture_association.playtest_lifecycle_id IS NOT NULL
            OR fixture_association.playtest_state_generation IS NOT NULL
            OR fixture_association.playable_state_scope IS DISTINCT FROM 'SHARED'
            OR fixture_manifest.fixture_plan->>'gameSessionResponseBytesDigest' IS DISTINCT FROM
                'sha256:' || encode(sha256(fixture_association.game_session_association_response_bytes), 'hex')
            OR octet_length(fixture_association.game_session_association_response_bytes) IS NULL
            OR octet_length(fixture_association.game_session_association_response_bytes) = 0
            OR EXISTS (
                SELECT 1 FROM jsonb_each(to_jsonb(fixture_association)) e
                WHERE allocation_world ? e.key AND allocation_world->e.key IS DISTINCT FROM e.value)
            OR allocation_world->>'id' IS DISTINCT FROM fixture_association.world_instance_id::TEXT
            OR allocation_world->>'tenant_id' IS DISTINCT FROM fixture_association.local_tenant_key::TEXT
            OR allocation_world->>'game_instance_id' IS DISTINCT FROM fixture_association.private_game_instance_key::TEXT
            OR allocation_world->>'version_id' IS DISTINCT FROM fixture_association.local_version_key::TEXT
            OR allocation_world->>'status' IS DISTINCT FROM 'PREPARING'
            OR allocation_world->>'lifecycle_epoch' IS DISTINCT FROM '1'
            OR allocation_world->>'row_version' IS DISTINCT FROM '0'
            OR allocation_world->>'failure_reason' IS NOT NULL
            OR allocation_world->>'termination_request_id' IS NOT NULL
            OR allocation_world->>'terminated_at' IS NOT NULL
            OR allocation_world->>'run_owned_fixture_preparing_lifecycle_epoch' IS DISTINCT FROM '1'
            OR 'sha256:' || (allocation_world->>'run_owned_fixture_binding_sha256') IS DISTINCT FROM
                fixture_association.preparation_input_digest THEN
            RETURN FALSE;
        END IF;
        DELETE FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest m
        USING "${serviceSchema}".world_authored_version_identity v,
            "${serviceSchema}".world_authored_source_intake si,
            "${serviceSchema}".world_authored_source_tenant_key_reservation r
        WHERE m.transaction_id = txid_current()
            AND m.table_name = actual_table AND m.row_id = fixture_association.world_instance_id
            AND m.expected_new = actual_new AND m.fixture_plan = fixture_manifest.fixture_plan
            AND m.preparation_authority_kind = 'RUN_OWNED_FIXTURE' AND m.capture_id IS NULL
            AND m.fixture_world_instance_id = fixture_association.world_instance_id
            AND m.fixture_operation_id = fixture_association.control_plane_request_id
            AND m.fixture_provenance_digest = fixture_association.fixture_provenance_digest
            AND m.input_digest = fixture_association.preparation_input_digest
            AND m.canonical_game_instance_id = fixture_association.canonical_game_instance_id
            AND m.target_namespace = fixture_association.canonical_target_namespace
            AND m.canonical_tenant_id = fixture_association.canonical_tenant_id
            AND m.canonical_version_id = fixture_association.canonical_version_id
            AND m.version_identity_operation_id = fixture_association.version_identity_operation_id
            AND m.local_tenant_key = fixture_association.local_tenant_key
            AND v.operation_id = fixture_association.version_identity_operation_id
            AND v.target_namespace = fixture_association.canonical_target_namespace
            AND v.canonical_tenant_id = fixture_association.canonical_tenant_id
            AND v.world_slug = fixture_association.canonical_world_slug
            AND v.canonical_version_id = fixture_association.canonical_version_id
            AND v.local_tenant_key = fixture_association.local_tenant_key
            AND v.local_version_key = fixture_association.local_version_key
            AND v.game_design_version_id = fixture_association.game_design_version_id
            AND v.source_operation_id = fixture_association.source_operation_id
            AND v.source_evidence_digest = fixture_association.source_evidence_digest
            AND v.intake_operation_id = fixture_association.intake_operation_id
            AND v.intake_request_id = fixture_association.intake_request_id
            AND v.intake_request_digest = fixture_association.intake_request_digest
            AND v.intake_receipt_digest = fixture_association.intake_receipt_digest
            AND si.operation_id = v.intake_operation_id AND si.intake_request_id = v.intake_request_id
            AND si.local_tenant_key = v.local_tenant_key AND si.source_provenance_kind = 'NEW_GAME_ROW'
            AND si.source_operation_id = v.source_operation_id
            AND si.source_evidence_digest = v.source_evidence_digest
            AND si.request_digest = v.intake_request_digest AND si.receipt_digest = v.intake_receipt_digest
            AND r.tenant_key = v.local_tenant_key AND r.claim_kind = 'CANONICAL_AUTHORED_SOURCE'
        RETURNING m.row_id INTO consumed;
        RETURN consumed IS NOT NULL;
    END IF;
    SELECT * INTO STRICT fixture_association
    FROM "${serviceSchema}".world_canonical_instance_association
    WHERE world_instance_id = fixture_manifest.fixture_world_instance_id;
    PERFORM "${serviceSchema}".world_verify_run_owned_fixed_preset_plan(
        fixture_manifest.fixture_plan,
        'sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc',
        fixture_association.world_instance_id, fixture_association.private_game_instance_key);

    DELETE FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest m
    USING "${serviceSchema}".world_authored_version_identity v,
        "${serviceSchema}".world_canonical_instance_association a,
        "${serviceSchema}".world_instance wi,
        "${serviceSchema}".world_authored_source_tenant_key_reservation r
    WHERE m.transaction_id = txid_current()
        AND m.table_name = actual_table
        AND m.row_id = (actual_new->>'id')::BIGINT
        AND m.expected_new = actual_new
        AND m.preparation_authority_kind = 'RUN_OWNED_FIXTURE'
        AND m.capture_id IS NULL
        AND m.fixture_world_instance_id = a.world_instance_id
        AND m.fixture_operation_id = a.control_plane_request_id
        AND m.fixture_provenance_digest = a.fixture_provenance_digest
        AND m.fixture_plan = fixture_manifest.fixture_plan
        AND a.world_instance_id = fixture_association.world_instance_id
        AND m.input_digest = a.preparation_input_digest
        AND m.canonical_game_instance_id = a.canonical_game_instance_id
        AND m.target_namespace = a.canonical_target_namespace
        AND m.canonical_tenant_id = a.canonical_tenant_id
        AND m.canonical_version_id = a.canonical_version_id
        AND m.version_identity_operation_id = a.version_identity_operation_id
        AND m.local_tenant_key = a.local_tenant_key
        AND a.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        AND a.world_instance_id = wi.id
        AND wi.canonical_game_instance_id = a.canonical_game_instance_id
        AND wi.tenant_id = a.local_tenant_key
        AND wi.game_instance_id = a.private_game_instance_key
        AND wi.version_id = a.local_version_key
        AND wi.status = 'PREPARING'
        AND wi.lifecycle_epoch = a.preparing_lifecycle_epoch
        AND wi.run_owned_fixture_preparing_lifecycle_epoch = a.preparing_lifecycle_epoch
        AND 'sha256:' || wi.run_owned_fixture_binding_sha256 = a.preparation_input_digest
        AND v.operation_id = a.version_identity_operation_id
        AND v.target_namespace = a.canonical_target_namespace
        AND v.canonical_tenant_id = a.canonical_tenant_id
        AND v.canonical_version_id = a.canonical_version_id
        AND v.local_tenant_key = a.local_tenant_key
        AND v.local_version_key = a.local_version_key
        AND r.tenant_key = a.local_tenant_key
        AND r.claim_kind = 'CANONICAL_AUTHORED_SOURCE'
        AND (actual_table <> 'world_canonical_instance_preparation'
            OR actual_new->>'capture_id' IS NULL
                AND actual_new->>'fixed_preset_id' = 'firemud-demo-topology-v1'
                AND actual_new->>'fixed_preset_content_digest' =
                    'sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc'
                AND actual_new->>'fixed_preset_binding_digest' = actual_new->>'input_digest'
                AND actual_new->>'input_digest' = fixture_manifest.fixture_plan->>'presetSealDigest'
                AND (actual_new->>'input_json')::JSONB = fixture_manifest.fixture_plan
                AND actual_new->>'control_plane_request_id' = a.control_plane_request_id)
        AND (
            (actual_table IN ('region_instance','zone_instance','room_instance','room_instance_exit')
                AND actual_new->>'tenant_id' = a.local_tenant_key::TEXT
                AND actual_new->>'game_instance_id' = a.private_game_instance_key::TEXT)
            OR (actual_table IN ('world_canonical_instance_topology_identity','world_canonical_instance_preparation')
                AND actual_new->>'canonical_game_instance_id' = a.canonical_game_instance_id::TEXT
                AND actual_new->>'world_instance_id' = a.world_instance_id::TEXT)
        )
    RETURNING m.row_id INTO consumed;
    RETURN consumed IS NOT NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_consume_canonical_prepare_execution_manifest(TEXT, TEXT, JSONB, JSONB)
    FROM PUBLIC;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$BEGIN
    SELECT * INTO operation FROM "${serviceSchema}".world_canonical_instance_execution e$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_guard_canonical_instance_association_execution()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one original canonical association execution guard anchor';
    END IF;
    original := replace(original, anchor, $replacement$BEGIN
    IF TG_OP = 'INSERT' AND NEW.schema_version = 3
        AND NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        IF NOT "${serviceSchema}".world_consume_canonical_prepare_execution_manifest(
            'world_canonical_instance_association', 'INSERT', NULL, to_jsonb(NEW)) THEN
            RAISE EXCEPTION 'Fixture association requires its exact one-use transaction execution manifest'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO operation FROM "${serviceSchema}".world_canonical_instance_execution e$replacement$);
    EXECUTE original;
END;
$migration$;




CREATE FUNCTION world_read_run_owned_fixed_preset(
    p_plan_json TEXT, p_expected_content_digest TEXT, p_world_instance_id BIGINT,
    p_private_game_instance_key BIGINT
) RETURNS TABLE(
    world_instance_id BIGINT, tenant_id BIGINT, private_game_instance_key BIGINT,
    version_id BIGINT, preset_id TEXT, preset_content_digest TEXT,
    preset_binding_digest TEXT, graph_sha256 TEXT, region_count INTEGER,
    zone_count INTEGER, room_count INTEGER, exit_count INTEGER
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    p JSONB;
    retained "${serviceSchema}".world_canonical_instance_preparation%ROWTYPE;
    association "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    template_count BIGINT;
    region_runtime_count BIGINT;
    zone_runtime_count BIGINT;
    room_runtime_count BIGINT;
    exit_runtime_count BIGINT;
    topology_identity_count BIGINT;
BEGIN
    IF current_setting('transaction_read_only') <> 'on'
        AND current_setting('transaction_isolation') NOT IN ('read committed','repeatable read','serializable') THEN
        RAISE EXCEPTION 'Run-owned fixed-preset readback requires an owner read transaction'
            USING ERRCODE = '23514';
    END IF;
    IF p_plan_json IS NULL OR length(p_plan_json) = 0 THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_PLAN_INVALID' USING ERRCODE = '23514';
    END IF;
    p := p_plan_json::JSONB;
    PERFORM "${serviceSchema}".world_verify_run_owned_fixed_preset_plan(
        p, p_expected_content_digest, p_world_instance_id, p_private_game_instance_key);
    SELECT owner.* INTO STRICT association
    FROM "${serviceSchema}".world_canonical_instance_association owner
    WHERE owner.world_instance_id = p_world_instance_id
        AND owner.canonical_game_instance_id = (p->>'gameSessionGameInstanceUuid')::UUID;
    SELECT prepared.* INTO STRICT retained
    FROM "${serviceSchema}".world_canonical_instance_preparation prepared
    WHERE prepared.canonical_game_instance_id = association.canonical_game_instance_id;
    IF retained.world_instance_id IS DISTINCT FROM p_world_instance_id
        OR retained.private_game_instance_key IS DISTINCT FROM p_private_game_instance_key
        OR retained.capture_id IS NOT NULL
        OR retained.fixed_preset_id IS DISTINCT FROM 'firemud-demo-topology-v1'
        OR retained.fixed_preset_content_digest IS DISTINCT FROM p_expected_content_digest
        OR retained.fixed_preset_binding_digest IS DISTINCT FROM p->>'presetSealDigest'
        OR retained.input_digest IS DISTINCT FROM p->>'presetSealDigest'
        OR retained.input_json IS DISTINCT FROM p_plan_json
        OR retained.graph_bytes IS DISTINCT FROM convert_to(p_plan_json, 'UTF8')
        OR retained.graph_sha256 IS DISTINCT FROM
            encode(sha256(convert_to(p_plan_json, 'UTF8')), 'hex')
        OR retained.target_namespace IS DISTINCT FROM p->>'targetNamespace'
        OR retained.canonical_tenant_id IS DISTINCT FROM (p->>'canonicalTenantId')::UUID
        OR retained.world_slug IS DISTINCT FROM p->>'worldSlug'
        OR retained.control_plane_request_id IS DISTINCT FROM p->>'fixtureOperationId'
        OR retained.canonical_version_id IS DISTINCT FROM (p->>'canonicalVersionId')::UUID
        OR retained.version_identity_operation_id IS DISTINCT FROM
            (p->>'worldVersionIdentityOperationId')::UUID THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_RESULT_MISMATCH: retained preparation differs'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO template_count
    FROM "${serviceSchema}".world_authored_topology_identity mapping
    WHERE mapping.target_namespace = association.canonical_target_namespace
        AND mapping.canonical_tenant_id = association.canonical_tenant_id
        AND mapping.canonical_version_id = association.canonical_version_id
        AND mapping.preparation_authority_kind = 'RUN_OWNED_FIXTURE'
        AND mapping.fixture_world_instance_id = association.world_instance_id
        AND mapping.fixture_operation_id = association.control_plane_request_id
        AND mapping.fixture_provenance_digest = association.fixture_provenance_digest;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity m
        WHERE m.target_namespace = association.canonical_target_namespace
            AND m.canonical_tenant_id = association.canonical_tenant_id
            AND m.canonical_version_id = association.canonical_version_id
            AND (m.preparation_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
                OR m.fixture_world_instance_id IS DISTINCT FROM association.world_instance_id
                OR m.fixture_operation_id IS DISTINCT FROM association.control_plane_request_id
                OR m.fixture_provenance_digest IS DISTINCT FROM association.fixture_provenance_digest)) THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_TOPOLOGY_MISMATCH: mapping owner differs'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO region_runtime_count
    FROM "${serviceSchema}".region_instance region_runtime
    WHERE region_runtime.world_instance_id = p_world_instance_id
        AND region_runtime.tenant_id = association.local_tenant_key
        AND region_runtime.game_instance_id = p_private_game_instance_key;
    SELECT count(*) INTO zone_runtime_count
    FROM "${serviceSchema}".zone_instance z
    JOIN "${serviceSchema}".region_instance r ON r.id = z.region_instance_id
    WHERE r.world_instance_id = p_world_instance_id
        AND z.tenant_id = association.local_tenant_key
        AND z.game_instance_id = p_private_game_instance_key;
    SELECT count(*) INTO room_runtime_count
    FROM "${serviceSchema}".room_instance r
    JOIN "${serviceSchema}".region_instance ri ON ri.id = r.region_instance_id
    WHERE ri.world_instance_id = p_world_instance_id
        AND r.tenant_id = association.local_tenant_key
        AND r.game_instance_id = p_private_game_instance_key;
    SELECT count(*) INTO exit_runtime_count
    FROM "${serviceSchema}".room_instance_exit e
    WHERE e.tenant_id = association.local_tenant_key
        AND e.game_instance_id = p_private_game_instance_key;
    SELECT count(*) INTO topology_identity_count
    FROM "${serviceSchema}".world_canonical_instance_topology_identity topology
    WHERE topology.world_instance_id = p_world_instance_id
        AND topology.canonical_game_instance_id = association.canonical_game_instance_id;
    IF template_count <> 6 OR region_runtime_count <> 1 OR zone_runtime_count <> 1
        OR room_runtime_count <> 2 OR exit_runtime_count <> 2 OR topology_identity_count <> 6
        OR retained.region_count <> 1 OR retained.zone_count <> 1
        OR retained.room_count <> 2 OR retained.exit_count <> 2 THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_TOPOLOGY_MISMATCH: missing or extra rows'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".region r ON r.id = m.private_row_key
        WHERE m.target_namespace = association.canonical_target_namespace
            AND m.canonical_tenant_id = association.canonical_tenant_id
            AND m.canonical_version_id = association.canonical_version_id
            AND m.version_identity_operation_id = association.version_identity_operation_id
            AND m.family = 'REGION'
            AND m.template_id = '9affa11b-02f5-3cc9-a50e-6760083c71e9'::UUID
            AND r.tenant_id = association.local_tenant_key
            AND r.version_id = association.local_version_key
            AND r.name = 'Demo Region' AND r.shard_id = 0
            AND r.generation_seed = 0 AND r.spacing_multiplier = 1.0
            AND EXISTS (
                SELECT 1 FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
                JOIN "${serviceSchema}".region_instance ri ON ri.id = ti.runtime_row_id
                WHERE ti.world_instance_id = p_world_instance_id
                    AND ti.family = 'REGION' AND ti.template_id = m.template_id
                    AND ti.template_private_row_key = m.private_row_key
                    AND ri.world_instance_id = p_world_instance_id
                    AND ri.tenant_id = association.local_tenant_key
                    AND ri.game_instance_id = p_private_game_instance_key
                    AND ri.name = r.name AND ri.shard_id = r.shard_id
                    AND ri.generation_seed = r.generation_seed
                    AND ri.spacing_multiplier = r.spacing_multiplier))
        OR NOT EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_authored_topology_identity zm
        JOIN "${serviceSchema}".zone z ON z.id = zm.private_row_key
        JOIN "${serviceSchema}".world_authored_topology_identity rm
            ON rm.family = 'REGION' AND rm.target_namespace = zm.target_namespace
            AND rm.canonical_tenant_id = zm.canonical_tenant_id
            AND rm.canonical_version_id = zm.canonical_version_id
            AND rm.template_id = '9affa11b-02f5-3cc9-a50e-6760083c71e9'::UUID
        WHERE zm.target_namespace = association.canonical_target_namespace
            AND zm.canonical_tenant_id = association.canonical_tenant_id
            AND zm.canonical_version_id = association.canonical_version_id
            AND zm.version_identity_operation_id = association.version_identity_operation_id
            AND zm.family = 'ZONE'
            AND zm.template_id = '936c4d65-c460-3b73-9f4e-6ee6295bca24'::UUID
            AND z.tenant_id = association.local_tenant_key
            AND z.version_id = association.local_version_key
            AND z.region_id = rm.private_row_key AND z.name = 'Demo Zone'
            AND EXISTS (
                SELECT 1 FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
                JOIN "${serviceSchema}".zone_instance zi ON zi.id = ti.runtime_row_id
                JOIN "${serviceSchema}".world_canonical_instance_topology_identity ri
                    ON ri.world_instance_id = p_world_instance_id
                        AND ri.family = 'REGION'
                        AND ri.template_id = rm.template_id
                WHERE ti.world_instance_id = p_world_instance_id
                    AND ti.family = 'ZONE' AND ti.template_id = zm.template_id
                    AND ti.template_private_row_key = zm.private_row_key
                    AND zi.tenant_id = association.local_tenant_key
                    AND zi.game_instance_id = p_private_game_instance_key
                    AND zi.template_zone_id = z.id AND zi.name = z.name
                    AND zi.region_instance_id = ri.runtime_row_id))
        OR NOT EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".room r ON r.id = m.private_row_key
        JOIN "${serviceSchema}".world_authored_topology_identity zm
            ON zm.target_namespace = m.target_namespace
            AND zm.canonical_tenant_id = m.canonical_tenant_id
            AND zm.canonical_version_id = m.canonical_version_id
            AND zm.family = 'ZONE'
            AND zm.template_id = '936c4d65-c460-3b73-9f4e-6ee6295bca24'::UUID
        WHERE m.target_namespace = association.canonical_target_namespace
            AND m.canonical_tenant_id = association.canonical_tenant_id
            AND m.canonical_version_id = association.canonical_version_id
            AND m.version_identity_operation_id = association.version_identity_operation_id
            AND m.family = 'ROOM'
            AND ((m.template_id = 'c6058518-f9e0-3f26-9b05-2bc786095089'::UUID
                    AND r.name = 'Candle-lit Antechamber'
                    AND r.description = 'Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.')
                OR (m.template_id = 'e74cca03-3b53-3edc-84f5-890651f5fea8'::UUID
                    AND r.name = 'Smith''s Annex'
                    AND r.description = 'An anvil, banked coals, and orderly tool racks mark this alcove as a working annex off the starter chamber.'))
            AND r.tenant_id = association.local_tenant_key
            AND r.version_id = association.local_version_key
            AND r.zone_id = zm.private_row_key
            AND EXISTS (
                SELECT 1 FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
                JOIN "${serviceSchema}".room_instance ri ON ri.id = ti.runtime_row_id
                JOIN "${serviceSchema}".world_canonical_instance_topology_identity zi
                    ON zi.world_instance_id = p_world_instance_id
                        AND zi.family = 'ZONE'
                        AND zi.template_id = '936c4d65-c460-3b73-9f4e-6ee6295bca24'::UUID
                WHERE ti.world_instance_id = p_world_instance_id
                    AND ti.family = 'ROOM' AND ti.template_id = m.template_id
                    AND ti.template_private_row_key = m.private_row_key
                    AND ti.runtime_room_instance_id = ri.room_instance_row_id
                    AND ri.tenant_id = association.local_tenant_key
                    AND ri.game_instance_id = p_private_game_instance_key
                    AND ri.template_room_id = r.id
                    AND ri.name = r.name AND ri.description IS NOT DISTINCT FROM r.description
                    AND ri.zone_instance_id = zi.runtime_row_id))
        OR (SELECT count(*) FROM "${serviceSchema}".world_authored_topology_identity m
            JOIN "${serviceSchema}".room_exit e ON e.id = m.private_row_key
            WHERE m.target_namespace = association.canonical_target_namespace
                AND m.canonical_tenant_id = association.canonical_tenant_id
                AND m.canonical_version_id = association.canonical_version_id
                AND m.version_identity_operation_id = association.version_identity_operation_id
                AND m.family = 'ROOM_EXIT'
                AND e.tenant_id = association.local_tenant_key
                AND e.version_id = association.local_version_key
                AND e.cost = 1
                AND ((m.template_id = '18c4b7a2-6498-3865-a464-814b56e4d4c2'::UUID
                        AND e.direction = 'NORTH')
                    OR (m.template_id = 'ab46ef8f-e74f-3197-be62-9785ef63242e'::UUID
                        AND e.direction = 'SOUTH'))
                AND EXISTS (SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity f
                    WHERE f.target_namespace = m.target_namespace
                        AND f.canonical_tenant_id = m.canonical_tenant_id
                        AND f.canonical_version_id = m.canonical_version_id
                        AND f.family = 'ROOM'
                        AND f.template_id = CASE m.template_id
                            WHEN '18c4b7a2-6498-3865-a464-814b56e4d4c2'::UUID
                                THEN 'c6058518-f9e0-3f26-9b05-2bc786095089'::UUID
                            ELSE 'e74cca03-3b53-3edc-84f5-890651f5fea8'::UUID END
                        AND f.private_row_key = e.from_room_id)
                AND EXISTS (SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity t
                    WHERE t.target_namespace = m.target_namespace
                        AND t.canonical_tenant_id = m.canonical_tenant_id
                        AND t.canonical_version_id = m.canonical_version_id
                        AND t.family = 'ROOM'
                        AND t.template_id = CASE m.template_id
                            WHEN '18c4b7a2-6498-3865-a464-814b56e4d4c2'::UUID
                                THEN 'e74cca03-3b53-3edc-84f5-890651f5fea8'::UUID
                            ELSE 'c6058518-f9e0-3f26-9b05-2bc786095089'::UUID END
                        AND t.private_row_key = e.to_room_id)) <> 2
        OR EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity m
            WHERE m.target_namespace = association.canonical_target_namespace
                AND m.canonical_tenant_id = association.canonical_tenant_id
                AND m.canonical_version_id = association.canonical_version_id
                AND (m.family,m.template_id) NOT IN (
                    ('REGION','9affa11b-02f5-3cc9-a50e-6760083c71e9'::UUID),
                    ('ZONE','936c4d65-c460-3b73-9f4e-6ee6295bca24'::UUID),
                    ('ROOM','c6058518-f9e0-3f26-9b05-2bc786095089'::UUID),
                    ('ROOM','e74cca03-3b53-3edc-84f5-890651f5fea8'::UUID),
                    ('ROOM_EXIT','18c4b7a2-6498-3865-a464-814b56e4d4c2'::UUID),
                    ('ROOM_EXIT','ab46ef8f-e74f-3197-be62-9785ef63242e'::UUID))) THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_TOPOLOGY_MISMATCH: template graph or runtime readback differs'
            USING ERRCODE = '23514';
    END IF;

    IF (SELECT count(*) FROM "${serviceSchema}".room_instance_exit e
        JOIN "${serviceSchema}".world_canonical_instance_topology_identity xi
            ON xi.world_instance_id = p_world_instance_id AND xi.family = 'ROOM_EXIT'
                AND xi.runtime_row_id = e.id
        JOIN "${serviceSchema}".world_canonical_instance_topology_identity fi
            ON fi.world_instance_id = p_world_instance_id AND fi.family = 'ROOM'
                AND fi.template_id = CASE xi.template_id
                    WHEN '18c4b7a2-6498-3865-a464-814b56e4d4c2'::UUID
                        THEN 'c6058518-f9e0-3f26-9b05-2bc786095089'::UUID
                    ELSE 'e74cca03-3b53-3edc-84f5-890651f5fea8'::UUID END
        JOIN "${serviceSchema}".world_canonical_instance_topology_identity ti
            ON ti.world_instance_id = p_world_instance_id AND ti.family = 'ROOM'
                AND ti.template_id = CASE xi.template_id
                    WHEN '18c4b7a2-6498-3865-a464-814b56e4d4c2'::UUID
                        THEN 'e74cca03-3b53-3edc-84f5-890651f5fea8'::UUID
                    ELSE 'c6058518-f9e0-3f26-9b05-2bc786095089'::UUID END
        WHERE e.tenant_id = association.local_tenant_key
            AND e.game_instance_id = p_private_game_instance_key
            AND e.from_room_instance_record_id = fi.runtime_room_instance_id
            AND e.to_room_instance_record_id = ti.runtime_room_instance_id
            AND e.cost = 1
            AND e.direction = CASE xi.template_id
                WHEN '18c4b7a2-6498-3865-a464-814b56e4d4c2'::UUID THEN 'NORTH'
                ELSE 'SOUTH' END) <> 2 THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_CONNECTIVITY_MISMATCH: reciprocal exits differ'
            USING ERRCODE = '23514';
    END IF;

    world_instance_id := retained.world_instance_id;
    tenant_id := association.local_tenant_key;
    private_game_instance_key := retained.private_game_instance_key;
    version_id := association.local_version_key;
    preset_id := retained.fixed_preset_id;
    preset_content_digest := retained.fixed_preset_content_digest;
    preset_binding_digest := retained.fixed_preset_binding_digest;
    graph_sha256 := retained.graph_sha256;
    region_count := retained.region_count;
    zone_count := retained.zone_count;
    room_count := retained.room_count;
    exit_count := retained.exit_count;
    RETURN NEXT;
END;
$$;
REVOKE ALL ON FUNCTION world_read_run_owned_fixed_preset(TEXT, TEXT, BIGINT, BIGINT) FROM PUBLIC;

-- Fixture lifecycle transitions and admission remain closed by the existing World owners.
-- The PREPARING-only API writes resources and a retained result; it never updates world_instance.

CREATE FUNCTION world_materialize_run_owned_fixed_preset(
    p_plan_json TEXT, p_expected_content_digest TEXT, p_world_instance_id BIGINT,
    p_private_game_instance_key BIGINT
) RETURNS TABLE(
    world_instance_id BIGINT, tenant_id BIGINT, private_game_instance_key BIGINT,
    version_id BIGINT, preset_id TEXT, preset_content_digest TEXT,
    preset_binding_digest TEXT, graph_sha256 TEXT, region_count INTEGER,
    zone_count INTEGER, room_count INTEGER, exit_count INTEGER
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    p JSONB;
    a "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    wi "${serviceSchema}".world_instance%ROWTYPE;
    v "${serviceSchema}".world_authored_version_identity%ROWTYPE;
    intake "${serviceSchema}".world_authored_source_intake%ROWTYPE;
    publication_owner "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    owner_binding JSONB;
    complete_binding JSONB;
    operation UUID;
    result_id BIGINT;
    template_region_id BIGINT;
    template_zone_id BIGINT;
    template_room_one_id BIGINT;
    template_room_two_id BIGINT;
    template_exit_north_id BIGINT;
    template_exit_south_id BIGINT;
    runtime_region_id BIGINT;
    runtime_region_uuid UUID;
    runtime_zone_id BIGINT;
    runtime_zone_uuid UUID;
    runtime_room_one_id BIGINT;
    runtime_room_two_id BIGINT;
    runtime_exit_north_id BIGINT;
    runtime_exit_south_id BIGINT;
    row_json JSONB;
    region_template "${serviceSchema}".region%ROWTYPE;
    zone_template "${serviceSchema}".zone%ROWTYPE;
    room_template "${serviceSchema}".room%ROWTYPE;
    exit_template "${serviceSchema}".room_exit%ROWTYPE;
    runtime_region "${serviceSchema}".region_instance%ROWTYPE;
    runtime_zone "${serviceSchema}".zone_instance%ROWTYPE;
    runtime_room "${serviceSchema}".room_instance%ROWTYPE;
    runtime_exit "${serviceSchema}".room_instance_exit%ROWTYPE;
    topology_identity "${serviceSchema}".world_canonical_instance_topology_identity%ROWTYPE;
    preparation "${serviceSchema}".world_canonical_instance_preparation%ROWTYPE;
    mapping_input RECORD;
    mapping_row "${serviceSchema}".world_authored_topology_identity%ROWTYPE;
    topology_identity_json JSONB;
    graph_digest TEXT;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'Run-owned fixed-preset materialization requires writable READ COMMITTED'
            USING ERRCODE = '23514';
    END IF;
    IF p_plan_json IS NULL OR length(p_plan_json) = 0 THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_PLAN_INVALID' USING ERRCODE = '23514';
    END IF;
    p := p_plan_json::JSONB;
    PERFORM "${serviceSchema}".world_verify_run_owned_fixed_preset_plan(
        p, p_expected_content_digest, p_world_instance_id, p_private_game_instance_key);
    SELECT association.* INTO STRICT a FROM "${serviceSchema}".world_canonical_instance_association association
        WHERE association.world_instance_id = p_world_instance_id
            AND association.canonical_game_instance_id = (p->>'gameSessionGameInstanceUuid')::UUID FOR UPDATE;
    SELECT instance.* INTO STRICT wi FROM "${serviceSchema}".world_instance instance
        WHERE instance.id = p_world_instance_id FOR UPDATE;
    SELECT version_owner.* INTO STRICT v FROM "${serviceSchema}".world_authored_version_identity version_owner
        WHERE version_owner.operation_id = a.version_identity_operation_id FOR SHARE;
    SELECT source_owner.* INTO STRICT intake FROM "${serviceSchema}".world_authored_source_intake source_owner
        WHERE source_owner.operation_id = a.intake_operation_id FOR SHARE;
    SELECT fence_owner.* INTO STRICT publication_owner
    FROM "${serviceSchema}".world_design_publication_fence_owner fence_owner
    WHERE fence_owner.target_namespace = v.target_namespace
        AND fence_owner.canonical_tenant_id = v.canonical_tenant_id
        AND fence_owner.local_tenant_key = v.local_tenant_key AND fence_owner.version_id = v.local_version_key
    FOR UPDATE;
    PERFORM "${serviceSchema}".world_verify_run_owned_fixed_preset_plan(
        p, p_expected_content_digest, p_world_instance_id, p_private_game_instance_key);
    operation := (p->>'fixtureOperationId')::UUID;
    graph_digest := encode(sha256(convert_to(p_plan_json, 'UTF8')), 'hex');

    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation prepared
        WHERE prepared.canonical_game_instance_id = a.canonical_game_instance_id) THEN
        RETURN QUERY SELECT * FROM "${serviceSchema}".world_read_run_owned_fixed_preset(
            p_plan_json, p_expected_content_digest, p_world_instance_id,
            p_private_game_instance_key);
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM "${serviceSchema}".region region_template_owner
            WHERE region_template_owner.tenant_id = a.local_tenant_key AND region_template_owner.version_id = a.local_version_key)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".zone zone_template_owner
            WHERE zone_template_owner.tenant_id = a.local_tenant_key AND zone_template_owner.version_id = a.local_version_key)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".room room_template_owner
            WHERE room_template_owner.tenant_id = a.local_tenant_key AND room_template_owner.version_id = a.local_version_key)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".room_exit exit_template_owner
            WHERE exit_template_owner.tenant_id = a.local_tenant_key AND exit_template_owner.version_id = a.local_version_key)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".generation_rule generation_owner
            WHERE generation_owner.tenant_id = a.local_tenant_key AND generation_owner.version_id = a.local_version_key)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_entity_spawn_binding spawn_owner
            WHERE spawn_owner.tenant_id = a.local_tenant_key AND spawn_owner.version_id = a.local_version_key)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity mapping
            WHERE mapping.target_namespace = a.canonical_target_namespace
                AND mapping.canonical_tenant_id = a.canonical_tenant_id
                AND mapping.canonical_version_id = a.canonical_version_id)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".region_instance region_runtime
            WHERE region_runtime.world_instance_id = p_world_instance_id
                OR (region_runtime.tenant_id = a.local_tenant_key AND region_runtime.game_instance_id = p_private_game_instance_key))
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_topology_identity topology
            WHERE topology.world_instance_id = p_world_instance_id)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".zone_instance zone_runtime
            WHERE zone_runtime.tenant_id = a.local_tenant_key AND zone_runtime.game_instance_id = p_private_game_instance_key)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".room_instance room_runtime
            WHERE room_runtime.tenant_id = a.local_tenant_key AND room_runtime.game_instance_id = p_private_game_instance_key)
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".room_instance_exit exit_runtime
            WHERE exit_runtime.tenant_id = a.local_tenant_key AND exit_runtime.game_instance_id = p_private_game_instance_key) THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_PREEXISTING_RESOURCES: refusing unowned or partial topology'
            USING ERRCODE = '23514';
    END IF;

    owner_binding := jsonb_build_object(
        'targetNamespace', v.target_namespace,
        'canonicalTenantId', v.canonical_tenant_id,
        'canonicalVersionId', v.canonical_version_id,
        'versionIdentityOperationId', v.operation_id,
        'gameDesignVersionId', v.game_design_version_id,
        'intakeRequestId', v.intake_request_id,
        'intakeOperationId', v.intake_operation_id,
        'intakeRequestDigest', v.intake_request_digest,
        'sourceOperationId', v.source_operation_id,
        'sourceEvidenceDigest', v.source_evidence_digest,
        'intakeReceiptDigest', v.intake_receipt_digest);
    complete_binding := p;

    IF wi.status IS DISTINCT FROM 'PREPARING'
        OR wi.lifecycle_epoch IS DISTINCT FROM a.preparing_lifecycle_epoch THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_ALLOCATION_INVALID: no fresh active materialization'
            USING ERRCODE = '23514';
    END IF;

    template_region_id := nextval('"${serviceSchema}".region_id_seq');
    region_template := jsonb_populate_record(NULL::"${serviceSchema}".region,
        jsonb_build_object('id',template_region_id,'name','Demo Region',
            'tenant_id',a.local_tenant_key,'shard_id',0,'weather',NULL,
            'generation_seed',0,'generator_type',NULL,'generator_params',NULL,
            'spacing_multiplier',1.0,'version',0,'version_id',a.local_version_key));
    row_json := to_jsonb(region_template);
    INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
        txid_current(),operation,'region',template_region_id,'INSERT',v.operation_id,
        v.local_tenant_key,v.local_version_key,owner_binding,complete_binding,NULL,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id);
    INSERT INTO "${serviceSchema}".region SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".region,row_json)).*;

    template_zone_id := nextval('"${serviceSchema}".zone_id_seq');
    zone_template := jsonb_populate_record(NULL::"${serviceSchema}".zone,
        jsonb_build_object('id',template_zone_id,'region_id',template_region_id,
            'name','Demo Zone','tenant_id',a.local_tenant_key,
            'version_id',a.local_version_key,'version',0));
    row_json := to_jsonb(zone_template);
    INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
        txid_current(),operation,'zone',template_zone_id,'INSERT',v.operation_id,
        v.local_tenant_key,v.local_version_key,owner_binding,complete_binding,NULL,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id);
    INSERT INTO "${serviceSchema}".zone SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".zone,row_json)).*;

    template_room_one_id := nextval('"${serviceSchema}".room_id_seq');
    room_template := jsonb_populate_record(NULL::"${serviceSchema}".room,
        jsonb_build_object('id',template_room_one_id,'zone_id',template_zone_id,
            'name','Candle-lit Antechamber',
            'description','Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.',
            'tenant_id',a.local_tenant_key,'version_id',a.local_version_key,
            'version',0,'name_localized_variants_json',NULL,
            'description_localized_variants_json',NULL));
    row_json := to_jsonb(room_template);
    INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
        txid_current(),operation,'room',template_room_one_id,'INSERT',v.operation_id,
        v.local_tenant_key,v.local_version_key,owner_binding,complete_binding,NULL,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id);
    INSERT INTO "${serviceSchema}".room SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".room,row_json)).*;

    template_room_two_id := nextval('"${serviceSchema}".room_id_seq');
    room_template := jsonb_populate_record(NULL::"${serviceSchema}".room,
        jsonb_build_object('id',template_room_two_id,'zone_id',template_zone_id,
            'name','Smith''s Annex',
            'description','An anvil, banked coals, and orderly tool racks mark this alcove as a working annex off the starter chamber.',
            'tenant_id',a.local_tenant_key,'version_id',a.local_version_key,
            'version',0,'name_localized_variants_json',NULL,
            'description_localized_variants_json',NULL));
    row_json := to_jsonb(room_template);
    INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
        txid_current(),operation,'room',template_room_two_id,'INSERT',v.operation_id,
        v.local_tenant_key,v.local_version_key,owner_binding,complete_binding,NULL,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id);
    INSERT INTO "${serviceSchema}".room SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".room,row_json)).*;

    template_exit_north_id := nextval('"${serviceSchema}".room_exit_id_seq');
    exit_template := jsonb_populate_record(NULL::"${serviceSchema}".room_exit,
        jsonb_build_object('id',template_exit_north_id,'tenant_id',a.local_tenant_key,
            'version_id',a.local_version_key,'from_room_id',template_room_one_id,
            'to_room_id',template_room_two_id,'direction','NORTH','cost',1,'version',0));
    row_json := to_jsonb(exit_template);
    INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
        txid_current(),operation,'room_exit',template_exit_north_id,'INSERT',v.operation_id,
        v.local_tenant_key,v.local_version_key,owner_binding,complete_binding,NULL,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id);
    INSERT INTO "${serviceSchema}".room_exit SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".room_exit,row_json)).*;

    template_exit_south_id := nextval('"${serviceSchema}".room_exit_id_seq');
    exit_template := jsonb_populate_record(NULL::"${serviceSchema}".room_exit,
        jsonb_build_object('id',template_exit_south_id,'tenant_id',a.local_tenant_key,
            'version_id',a.local_version_key,'from_room_id',template_room_two_id,
            'to_room_id',template_room_one_id,'direction','SOUTH','cost',1,'version',0));
    row_json := to_jsonb(exit_template);
    INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
        txid_current(),operation,'room_exit',template_exit_south_id,'INSERT',v.operation_id,
        v.local_tenant_key,v.local_version_key,owner_binding,complete_binding,NULL,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id);
    INSERT INTO "${serviceSchema}".room_exit SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".room_exit,row_json)).*;

    FOR mapping_input IN
        SELECT * FROM (VALUES
            ('REGION','9affa11b-02f5-3cc9-a50e-6760083c71e9'::UUID,template_region_id),
            ('ZONE','936c4d65-c460-3b73-9f4e-6ee6295bca24'::UUID,template_zone_id),
            ('ROOM','c6058518-f9e0-3f26-9b05-2bc786095089'::UUID,template_room_one_id),
            ('ROOM','e74cca03-3b53-3edc-84f5-890651f5fea8'::UUID,template_room_two_id),
            ('ROOM_EXIT','18c4b7a2-6498-3865-a464-814b56e4d4c2'::UUID,template_exit_north_id),
            ('ROOM_EXIT','ab46ef8f-e74f-3197-be62-9785ef63242e'::UUID,template_exit_south_id)
        ) AS fixed(family,template_id,private_row_key)
    LOOP
        mapping_row := jsonb_populate_record(NULL::"${serviceSchema}".world_authored_topology_identity,
            jsonb_build_object('id',nextval('"${serviceSchema}".world_authored_topology_identity_id_seq'),
                'target_namespace',v.target_namespace,'canonical_tenant_id',v.canonical_tenant_id,
                'canonical_version_id',v.canonical_version_id,'family',mapping_input.family,
                'template_id',mapping_input.template_id,
                'private_row_key',mapping_input.private_row_key,'tenant_id',v.local_tenant_key,
                'version_id',v.local_version_key,'version_identity_operation_id',v.operation_id,
                'preparation_authority_kind','RUN_OWNED_FIXTURE',
                'fixture_provenance_digest',a.fixture_provenance_digest,
                'fixture_world_instance_id',a.world_instance_id,
                'fixture_operation_id',a.control_plane_request_id));
        row_json := to_jsonb(mapping_row);
        INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
            txid_current(),operation,'world_authored_topology_identity',mapping_row.id,'INSERT',
            v.operation_id,v.local_tenant_key,v.local_version_key,owner_binding,
            complete_binding,NULL,row_json,
            'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id);
        INSERT INTO "${serviceSchema}".world_authored_topology_identity SELECT
            (jsonb_populate_record(NULL::"${serviceSchema}".world_authored_topology_identity,row_json)).*;
    END LOOP;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_region_draft_execution_manifest template_manifest
        WHERE template_manifest.transaction_id = txid_current()) THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_TEMPLATE_MANIFEST_REMAINS'
            USING ERRCODE = '23514';
    END IF;

    runtime_region_id := nextval('"${serviceSchema}".region_instance_id_seq');
    runtime_region_uuid := gen_random_uuid();
    runtime_region := jsonb_populate_record(NULL::"${serviceSchema}".region_instance,
        jsonb_build_object('id',runtime_region_id,'tenant_id',a.local_tenant_key,
            'game_instance_id',p_private_game_instance_key,'world_instance_id',p_world_instance_id,
            'shard_id',0,'name','Demo Region','weather',NULL,'generation_seed',0,
            'generator_type',NULL,'generator_params',NULL,'spacing_multiplier',1.0,
            'version',0,'canonical_region_instance_id',runtime_region_uuid));
    row_json := to_jsonb(runtime_region);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'region_instance',runtime_region_id,a.canonical_game_instance_id,
        a.canonical_target_namespace,a.canonical_tenant_id,a.canonical_version_id,
        a.version_identity_operation_id,NULL,a.local_tenant_key,a.preparation_input_digest,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".region_instance SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".region_instance,row_json)).*;
    topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
        jsonb_build_object('id',nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
            'world_instance_id',p_world_instance_id,'canonical_game_instance_id',a.canonical_game_instance_id,
            'family','REGION','template_id','9affa11b-02f5-3cc9-a50e-6760083c71e9'::UUID,
            'template_private_row_key',template_region_id,'runtime_row_id',runtime_region_id,
            'runtime_identity',runtime_region_uuid,'runtime_room_instance_id',NULL));
    topology_identity_json := to_jsonb(topology_identity);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'world_canonical_instance_topology_identity',topology_identity.id,
        a.canonical_game_instance_id,a.canonical_target_namespace,a.canonical_tenant_id,
        a.canonical_version_id,a.version_identity_operation_id,NULL,a.local_tenant_key,
        a.preparation_input_digest,topology_identity_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".world_canonical_instance_topology_identity SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            topology_identity_json)).*;

    runtime_zone_id := nextval('"${serviceSchema}".zone_instance_id_seq');
    runtime_zone_uuid := gen_random_uuid();
    runtime_zone := jsonb_populate_record(NULL::"${serviceSchema}".zone_instance,
        jsonb_build_object('id',runtime_zone_id,'tenant_id',a.local_tenant_key,
            'game_instance_id',p_private_game_instance_key,'zone_instance_id',runtime_zone_id,
            'template_zone_id',template_zone_id,'region_instance_id',runtime_region_id,
            'name','Demo Zone','version',0,'canonical_zone_instance_id',runtime_zone_uuid));
    row_json := to_jsonb(runtime_zone);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'zone_instance',runtime_zone_id,a.canonical_game_instance_id,
        a.canonical_target_namespace,a.canonical_tenant_id,a.canonical_version_id,
        a.version_identity_operation_id,NULL,a.local_tenant_key,a.preparation_input_digest,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".zone_instance SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".zone_instance,row_json)).*;
    topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
        jsonb_build_object('id',nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
            'world_instance_id',p_world_instance_id,'canonical_game_instance_id',a.canonical_game_instance_id,
            'family','ZONE','template_id','936c4d65-c460-3b73-9f4e-6ee6295bca24'::UUID,
            'template_private_row_key',template_zone_id,'runtime_row_id',runtime_zone_id,
            'runtime_identity',runtime_zone_uuid,'runtime_room_instance_id',NULL));
    topology_identity_json := to_jsonb(topology_identity);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'world_canonical_instance_topology_identity',topology_identity.id,
        a.canonical_game_instance_id,a.canonical_target_namespace,a.canonical_tenant_id,
        a.canonical_version_id,a.version_identity_operation_id,NULL,a.local_tenant_key,
        a.preparation_input_digest,topology_identity_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".world_canonical_instance_topology_identity SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            topology_identity_json)).*;

    runtime_room_one_id := nextval('"${serviceSchema}".room_instance_id_seq');
    runtime_room := jsonb_populate_record(NULL::"${serviceSchema}".room_instance,
        jsonb_build_object('id',runtime_room_one_id,'tenant_id',a.local_tenant_key,
            'game_instance_id',p_private_game_instance_key,'room_instance_row_id',runtime_room_one_id,
            'template_room_id',template_room_one_id,'region_instance_id',runtime_region_id,
            'zone_instance_id',runtime_zone_id,'name','Candle-lit Antechamber',
            'description','Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.',
            'name_localized_variants_json',NULL,'description_localized_variants_json',NULL,'version',0));
    row_json := to_jsonb(runtime_room);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'room_instance',runtime_room_one_id,a.canonical_game_instance_id,
        a.canonical_target_namespace,a.canonical_tenant_id,a.canonical_version_id,
        a.version_identity_operation_id,NULL,a.local_tenant_key,a.preparation_input_digest,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".room_instance SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".room_instance,row_json)).*;
    topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
        jsonb_build_object('id',nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
            'world_instance_id',p_world_instance_id,'canonical_game_instance_id',a.canonical_game_instance_id,
            'family','ROOM','template_id','c6058518-f9e0-3f26-9b05-2bc786095089'::UUID,
            'template_private_row_key',template_room_one_id,'runtime_row_id',runtime_room_one_id,
            'runtime_identity',NULL,'runtime_room_instance_id',runtime_room_one_id));
    topology_identity_json := to_jsonb(topology_identity);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'world_canonical_instance_topology_identity',topology_identity.id,
        a.canonical_game_instance_id,a.canonical_target_namespace,a.canonical_tenant_id,
        a.canonical_version_id,a.version_identity_operation_id,NULL,a.local_tenant_key,
        a.preparation_input_digest,topology_identity_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".world_canonical_instance_topology_identity SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            topology_identity_json)).*;

    runtime_room_two_id := nextval('"${serviceSchema}".room_instance_id_seq');
    runtime_room := jsonb_populate_record(NULL::"${serviceSchema}".room_instance,
        jsonb_build_object('id',runtime_room_two_id,'tenant_id',a.local_tenant_key,
            'game_instance_id',p_private_game_instance_key,'room_instance_row_id',runtime_room_two_id,
            'template_room_id',template_room_two_id,'region_instance_id',runtime_region_id,
            'zone_instance_id',runtime_zone_id,'name','Smith''s Annex',
            'description','An anvil, banked coals, and orderly tool racks mark this alcove as a working annex off the starter chamber.',
            'name_localized_variants_json',NULL,'description_localized_variants_json',NULL,'version',0));
    row_json := to_jsonb(runtime_room);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'room_instance',runtime_room_two_id,a.canonical_game_instance_id,
        a.canonical_target_namespace,a.canonical_tenant_id,a.canonical_version_id,
        a.version_identity_operation_id,NULL,a.local_tenant_key,a.preparation_input_digest,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".room_instance SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".room_instance,row_json)).*;
    topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
        jsonb_build_object('id',nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
            'world_instance_id',p_world_instance_id,'canonical_game_instance_id',a.canonical_game_instance_id,
            'family','ROOM','template_id','e74cca03-3b53-3edc-84f5-890651f5fea8'::UUID,
            'template_private_row_key',template_room_two_id,'runtime_row_id',runtime_room_two_id,
            'runtime_identity',NULL,'runtime_room_instance_id',runtime_room_two_id));
    topology_identity_json := to_jsonb(topology_identity);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'world_canonical_instance_topology_identity',topology_identity.id,
        a.canonical_game_instance_id,a.canonical_target_namespace,a.canonical_tenant_id,
        a.canonical_version_id,a.version_identity_operation_id,NULL,a.local_tenant_key,
        a.preparation_input_digest,topology_identity_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".world_canonical_instance_topology_identity SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            topology_identity_json)).*;

    runtime_exit_north_id := nextval('"${serviceSchema}".room_instance_exit_id_seq');
    runtime_exit := jsonb_populate_record(NULL::"${serviceSchema}".room_instance_exit,
        jsonb_build_object('id',runtime_exit_north_id,'tenant_id',a.local_tenant_key,
            'game_instance_id',p_private_game_instance_key,
            'from_room_instance_record_id',runtime_room_one_id,
            'to_room_instance_record_id',runtime_room_two_id,'direction','NORTH','cost',1,'version',0));
    row_json := to_jsonb(runtime_exit);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'room_instance_exit',runtime_exit_north_id,a.canonical_game_instance_id,
        a.canonical_target_namespace,a.canonical_tenant_id,a.canonical_version_id,
        a.version_identity_operation_id,NULL,a.local_tenant_key,a.preparation_input_digest,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".room_instance_exit SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".room_instance_exit,row_json)).*;
    topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
        jsonb_build_object('id',nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
            'world_instance_id',p_world_instance_id,'canonical_game_instance_id',a.canonical_game_instance_id,
            'family','ROOM_EXIT','template_id','18c4b7a2-6498-3865-a464-814b56e4d4c2'::UUID,
            'template_private_row_key',template_exit_north_id,'runtime_row_id',runtime_exit_north_id,
            'runtime_identity',NULL,'runtime_room_instance_id',NULL));
    topology_identity_json := to_jsonb(topology_identity);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'world_canonical_instance_topology_identity',topology_identity.id,
        a.canonical_game_instance_id,a.canonical_target_namespace,a.canonical_tenant_id,
        a.canonical_version_id,a.version_identity_operation_id,NULL,a.local_tenant_key,
        a.preparation_input_digest,topology_identity_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".world_canonical_instance_topology_identity SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            topology_identity_json)).*;

    runtime_exit_south_id := nextval('"${serviceSchema}".room_instance_exit_id_seq');
    runtime_exit := jsonb_populate_record(NULL::"${serviceSchema}".room_instance_exit,
        jsonb_build_object('id',runtime_exit_south_id,'tenant_id',a.local_tenant_key,
            'game_instance_id',p_private_game_instance_key,
            'from_room_instance_record_id',runtime_room_two_id,
            'to_room_instance_record_id',runtime_room_one_id,'direction','SOUTH','cost',1,'version',0));
    row_json := to_jsonb(runtime_exit);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'room_instance_exit',runtime_exit_south_id,a.canonical_game_instance_id,
        a.canonical_target_namespace,a.canonical_tenant_id,a.canonical_version_id,
        a.version_identity_operation_id,NULL,a.local_tenant_key,a.preparation_input_digest,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".room_instance_exit SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".room_instance_exit,row_json)).*;
    topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
        jsonb_build_object('id',nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
            'world_instance_id',p_world_instance_id,'canonical_game_instance_id',a.canonical_game_instance_id,
            'family','ROOM_EXIT','template_id','ab46ef8f-e74f-3197-be62-9785ef63242e'::UUID,
            'template_private_row_key',template_exit_south_id,'runtime_row_id',runtime_exit_south_id,
            'runtime_identity',NULL,'runtime_room_instance_id',NULL));
    topology_identity_json := to_jsonb(topology_identity);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'world_canonical_instance_topology_identity',topology_identity.id,
        a.canonical_game_instance_id,a.canonical_target_namespace,a.canonical_tenant_id,
        a.canonical_version_id,a.version_identity_operation_id,NULL,a.local_tenant_key,
        a.preparation_input_digest,topology_identity_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".world_canonical_instance_topology_identity SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            topology_identity_json)).*;

    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest prepare_manifest
        WHERE prepare_manifest.transaction_id = txid_current()) THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_RUNTIME_MANIFEST_REMAINS'
            USING ERRCODE = '23514';
    END IF;
    result_id := nextval('"${serviceSchema}".world_canonical_instance_preparation_id_seq');
    preparation := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_preparation,
        jsonb_build_object('id',result_id,
            'canonical_game_instance_id',a.canonical_game_instance_id,
            'target_namespace',a.canonical_target_namespace,
            'canonical_tenant_id',a.canonical_tenant_id,
            'world_slug',a.canonical_world_slug,
            'control_plane_request_id',a.control_plane_request_id,
            'canonical_version_id',a.canonical_version_id,
            'version_identity_operation_id',a.version_identity_operation_id,
            'capture_id',NULL,'fixed_preset_id','firemud-demo-topology-v1',
            'fixed_preset_content_digest',p_expected_content_digest,
            'fixed_preset_binding_digest',p->>'presetSealDigest',
            'graph_bytes',convert_to(p_plan_json,'UTF8'),'graph_sha256',graph_digest,
            'input_digest',p->>'presetSealDigest','input_json',p_plan_json,
            'world_instance_id',p_world_instance_id,
            'private_game_instance_key',p_private_game_instance_key,
            'region_count',1,'zone_count',1,'room_count',2,'exit_count',2,
            'storage_status','MATERIALIZED_UNVERIFIED','prepared_at',CURRENT_TIMESTAMP));
    row_json := to_jsonb(preparation);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(),'world_canonical_instance_preparation',result_id,
        a.canonical_game_instance_id,a.canonical_target_namespace,a.canonical_tenant_id,
        a.canonical_version_id,a.version_identity_operation_id,NULL,a.local_tenant_key,
        a.preparation_input_digest,row_json,
        'RUN_OWNED_FIXTURE',a.fixture_provenance_digest,a.world_instance_id,a.control_plane_request_id,p);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation SELECT
        (jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_preparation,row_json)).*;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest prepare_manifest
        WHERE prepare_manifest.transaction_id = txid_current()) THEN
        RAISE EXCEPTION 'RUN_OWNED_WORLD_FIXED_PRESET_RESULT_MANIFEST_REMAINS'
            USING ERRCODE = '23514';
    END IF;
    RETURN QUERY SELECT * FROM "${serviceSchema}".world_read_run_owned_fixed_preset(
        p_plan_json,p_expected_content_digest,p_world_instance_id,p_private_game_instance_key);
END;
$$;
REVOKE ALL ON FUNCTION world_materialize_run_owned_fixed_preset(TEXT, TEXT, BIGINT, BIGINT)
    FROM PUBLIC;

-- Stable same-tenant legacy UPDATE behavior is preserved by the earlier own V1007.1 and
-- V1009.1 repairs; later manifest/activation/hold extensions retain that function body.

-- [jooq ignore stop]
