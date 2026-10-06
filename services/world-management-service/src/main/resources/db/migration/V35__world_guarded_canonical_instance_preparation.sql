-- Canonical preparation is an insert-born, owner-private materialization boundary.  The existing
-- V24 reserved-tenant guard remains closed except for exact single-use rows emitted below.
ALTER TABLE region_instance ADD COLUMN canonical_region_instance_id UUID;
ALTER TABLE zone_instance ADD COLUMN canonical_zone_instance_id UUID;
CREATE UNIQUE INDEX uq_region_instance_canonical_runtime_id
    ON region_instance (canonical_region_instance_id)
    WHERE canonical_region_instance_id IS NOT NULL;
CREATE UNIQUE INDEX uq_zone_instance_canonical_runtime_id
    ON zone_instance (canonical_zone_instance_id)
    WHERE canonical_zone_instance_id IS NOT NULL;

CREATE SEQUENCE world_canonical_private_game_instance_key_seq AS BIGINT MINVALUE 1;
SELECT setval(
    'world_canonical_private_game_instance_key_seq'::REGCLASS,
    coalesce(max(game_instance_id), 0) + 1,
    FALSE)
FROM world_instance;

CREATE TABLE world_canonical_instance_preparation_execution_manifest (
    transaction_id BIGINT NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    row_id BIGINT NOT NULL,
    canonical_game_instance_id UUID NOT NULL,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    version_identity_operation_id UUID NOT NULL,
    capture_id UUID NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    input_digest VARCHAR(71) NOT NULL,
    expected_new JSONB NOT NULL,
    PRIMARY KEY (transaction_id, table_name, row_id),
    CHECK (table_name IN (
        'world_instance', 'region_instance', 'zone_instance', 'room_instance',
        'room_instance_exit', 'world_canonical_instance_topology_identity',
        'world_canonical_instance_preparation')),
    CHECK (canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (version_identity_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (capture_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (local_tenant_key > 0),
    CHECK (input_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE TABLE world_canonical_instance_topology_identity (
    id BIGSERIAL PRIMARY KEY,
    world_instance_id BIGINT NOT NULL REFERENCES world_instance(id) ON DELETE RESTRICT,
    canonical_game_instance_id UUID NOT NULL,
    family VARCHAR(32) NOT NULL CHECK (family IN ('REGION', 'ZONE', 'ROOM', 'ROOM_EXIT')),
    template_id UUID NOT NULL CHECK (template_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    template_private_row_key BIGINT NOT NULL CHECK (template_private_row_key > 0),
    runtime_row_id BIGINT NOT NULL CHECK (runtime_row_id > 0),
    runtime_identity UUID,
    runtime_room_instance_id BIGINT,
    UNIQUE (world_instance_id, family, template_id),
    UNIQUE (family, runtime_row_id),
    FOREIGN KEY (canonical_game_instance_id) REFERENCES world_instance(canonical_game_instance_id)
        ON DELETE RESTRICT,
    CHECK (
        (family = 'REGION' AND runtime_identity IS NOT NULL AND runtime_room_instance_id IS NULL)
        OR (family = 'ZONE' AND runtime_identity IS NOT NULL AND runtime_room_instance_id IS NULL)
        OR (family = 'ROOM' AND runtime_identity IS NULL AND runtime_room_instance_id > 0)
        OR (family = 'ROOM_EXIT' AND runtime_identity IS NULL AND runtime_room_instance_id IS NULL)
    )
);
CREATE INDEX idx_world_canonical_instance_topology_world
    ON world_canonical_instance_topology_identity(world_instance_id, family, id);

CREATE TABLE world_canonical_instance_preparation (
    id BIGSERIAL PRIMARY KEY,
    canonical_game_instance_id UUID NOT NULL UNIQUE,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    canonical_version_id UUID NOT NULL,
    version_identity_operation_id UUID NOT NULL,
    capture_id UUID NOT NULL REFERENCES world_canonical_frozen_topology(capture_id) ON DELETE RESTRICT,
    graph_bytes BYTEA NOT NULL CHECK (octet_length(graph_bytes) > 0),
    graph_sha256 VARCHAR(64) NOT NULL CHECK (graph_sha256 ~ '^[0-9a-f]{64}$'),
    input_digest VARCHAR(71) NOT NULL CHECK (input_digest ~ '^sha256:[0-9a-f]{64}$'),
    input_json TEXT NOT NULL CHECK (length(input_json) > 0),
    world_instance_id BIGINT NOT NULL UNIQUE REFERENCES world_instance(id) ON DELETE RESTRICT,
    private_game_instance_key BIGINT NOT NULL CHECK (private_game_instance_key > 0),
    region_count INTEGER NOT NULL CHECK (region_count > 0),
    zone_count INTEGER NOT NULL CHECK (zone_count >= 0),
    room_count INTEGER NOT NULL CHECK (room_count >= 0),
    exit_count INTEGER NOT NULL CHECK (exit_count >= 0),
    storage_status VARCHAR(32) NOT NULL CHECK (storage_status = 'MATERIALIZED_UNVERIFIED'),
    prepared_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (target_namespace, canonical_tenant_id, control_plane_request_id),
    FOREIGN KEY (version_identity_operation_id)
        REFERENCES world_authored_version_identity(operation_id) ON DELETE RESTRICT,
    FOREIGN KEY (canonical_game_instance_id)
        REFERENCES world_canonical_instance_association(canonical_game_instance_id)
        DEFERRABLE INITIALLY DEFERRED
);

-- [jooq ignore start]
REVOKE ALL ON world_canonical_instance_preparation_execution_manifest FROM PUBLIC;

CREATE FUNCTION world_consume_canonical_prepare_execution_manifest(
    actual_table TEXT, actual_operation TEXT, actual_old JSONB, actual_new JSONB
) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    consumed BIGINT;
BEGIN
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
    RETURN consumed IS NOT NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_consume_canonical_prepare_execution_manifest(TEXT, TEXT, JSONB, JSONB)
    FROM PUBLIC;

-- V24's reserved-source denial is preserved.  Only V29's existing exact draft manifest and this
-- V35 exact insert manifest may consume a canonical tenant mutation; legacy tenants retain the
-- original numeric reservation behavior.
CREATE OR REPLACE FUNCTION world_claim_legacy_numeric_tenant_key()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    claimed_key BIGINT;
    old_row JSONB;
    new_row JSONB;
    canonical_old BOOLEAN := FALSE;
    canonical_new BOOLEAN := FALSE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_row := to_jsonb(OLD);
        SELECT EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_authored_source_tenant_key_reservation
            WHERE tenant_key = OLD.tenant_id AND claim_kind = 'CANONICAL_AUTHORED_SOURCE')
        INTO canonical_old;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_row := to_jsonb(NEW);
        SELECT EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_authored_source_tenant_key_reservation
            WHERE tenant_key = NEW.tenant_id AND claim_kind = 'CANONICAL_AUTHORED_SOURCE')
        INTO canonical_new;
    END IF;
    IF canonical_old OR canonical_new THEN
        IF TG_TABLE_SCHEMA = '${serviceSchema}'
            AND "${serviceSchema}".world_consume_region_execution_manifest(
                TG_TABLE_NAME, TG_OP, old_row, new_row) THEN
            RETURN NEW;
        END IF;
        IF TG_TABLE_SCHEMA = '${serviceSchema}'
            AND "${serviceSchema}".world_consume_canonical_prepare_execution_manifest(
                TG_TABLE_NAME, TG_OP, old_row, new_row) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION
            'World tenant key is reserved for a canonical authored source; no exact transaction execution manifest'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    INSERT INTO "${serviceSchema}".world_authored_source_tenant_key_reservation AS reservation
        (tenant_key, claim_kind) VALUES (NEW.tenant_id, 'LEGACY_NUMERIC')
    ON CONFLICT (tenant_key) DO UPDATE SET tenant_key = EXCLUDED.tenant_key
        WHERE reservation.claim_kind = 'LEGACY_NUMERIC'
    RETURNING reservation.tenant_key INTO claimed_key;
    IF claimed_key IS NULL THEN
        RAISE EXCEPTION 'World tenant key % is reserved for a canonical authored source', NEW.tenant_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION world_validate_canonical_preparation_row()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    consumed BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        consumed := "${serviceSchema}".world_consume_canonical_prepare_execution_manifest(
            TG_TABLE_NAME, TG_OP, NULL, to_jsonb(NEW));
        IF consumed THEN RETURN NEW; END IF;
        RAISE EXCEPTION 'Canonical topology/preparation rows require the exact V35 owner operation'
            USING ERRCODE = '23514';
    END IF;
    RAISE EXCEPTION 'Canonical topology/preparation history is immutable'
        USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_world_canonical_instance_topology_identity_exact_insert
    BEFORE INSERT OR UPDATE OR DELETE ON world_canonical_instance_topology_identity
    FOR EACH ROW EXECUTE FUNCTION world_validate_canonical_preparation_row();
CREATE TRIGGER trg_world_canonical_instance_topology_identity_no_truncate
    BEFORE TRUNCATE ON world_canonical_instance_topology_identity
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();
CREATE TRIGGER trg_world_canonical_instance_preparation_exact_insert
    BEFORE INSERT OR UPDATE OR DELETE ON world_canonical_instance_preparation
    FOR EACH ROW EXECUTE FUNCTION world_validate_canonical_preparation_row();
CREATE TRIGGER trg_world_canonical_instance_preparation_no_truncate
    BEFORE TRUNCATE ON world_canonical_instance_preparation
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();

-- Exact insert-only materialization.  Configured generation and spawn intent are rejected before
-- any sequence allocation or write.  All parent and template selectors come from typed, exact
-- UUID-to-private-key mappings committed by V30; no numeric/UUID inference occurs here.
CREATE FUNCTION world_prepare_canonical_instance(p_input_json TEXT, p_input_digest TEXT)
RETURNS TABLE(world_instance_id BIGINT, private_game_instance_key BIGINT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    p JSONB;
    ident JSONB;
    gs_request JSONB;
    gs_evidence JSONB;
    launch JSONB;
    version_input JSONB;
    topology JSONB;
    source_input JSONB;
    source_evidence_input JSONB;
    identity_row "${serviceSchema}".world_authored_version_identity%ROWTYPE;
    binding_row "${serviceSchema}".world_complete_launch_binding%ROWTYPE;
    intake_row "${serviceSchema}".world_authored_source_intake%ROWTYPE;
    capture_row "${serviceSchema}".world_canonical_frozen_topology%ROWTYPE;
    publication_owner "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    prior "${serviceSchema}".world_canonical_instance_preparation%ROWTYPE;
    world_row "${serviceSchema}".world_instance%ROWTYPE;
    region_runtime "${serviceSchema}".region_instance%ROWTYPE;
    zone_runtime "${serviceSchema}".zone_instance%ROWTYPE;
    room_runtime "${serviceSchema}".room_instance%ROWTYPE;
    exit_runtime "${serviceSchema}".room_instance_exit%ROWTYPE;
    topology_identity "${serviceSchema}".world_canonical_instance_topology_identity%ROWTYPE;
    preparation_row "${serviceSchema}".world_canonical_instance_preparation%ROWTYPE;
    source_region RECORD;
    source_zone RECORD;
    source_room RECORD;
    source_exit RECORD;
    parent_row RECORD;
    other_row RECORD;
    full_row JSONB;
    template_count BIGINT;
    region_count BIGINT;
    zone_count BIGINT;
    room_count BIGINT;
    exit_count BIGINT;
    generation_count BIGINT;
    spawn_count BIGINT;
    local_game_instance_key BIGINT;
    runtime_id BIGINT;
    runtime_uuid UUID;
    new_world_instance_id BIGINT;
    new_result_id BIGINT;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'Canonical preparation requires writable READ COMMITTED'
            USING ERRCODE = '23514';
    END IF;
    IF p_input_json IS NULL OR length(p_input_json) = 0 OR p_input_digest IS NULL
        OR p_input_digest !~ '^sha256:[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Canonical preparation input/digest is malformed' USING ERRCODE = '23514';
    END IF;
    p := p_input_json::JSONB;
    IF p->>'schemaVersion' IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'Unsupported canonical preparation input schema' USING ERRCODE = '23514';
    END IF;
    ident := p->'identity';
    gs_request := p->'gameSessionReadRequest';
    gs_evidence := p->'gameSessionReadEvidence';
    launch := p->'launchBinding';
    version_input := p->'versionIdentity';
    topology := p->'topology';
    source_input := p->'sourceIntake';
    source_evidence_input := source_input->'sourceEvidence';
    IF ident IS NULL OR gs_request IS NULL OR gs_evidence IS NULL
        OR launch IS NULL OR version_input IS NULL OR topology IS NULL
        OR source_input IS NULL OR jsonb_typeof(source_input) IS DISTINCT FROM 'object'
        OR source_evidence_input IS NULL
        OR jsonb_typeof(source_evidence_input) IS DISTINCT FROM 'object'
        OR ident->>'publicProduction' IS DISTINCT FROM 'true'
        OR ident->>'playableStateScope' IS DISTINCT FROM 'SHARED'
        OR ident->>'canonicalGameInstanceId' IS DISTINCT FROM gs_request->>'canonicalGameInstanceId'
        OR ident->>'canonicalGameInstanceId' IS DISTINCT FROM gs_evidence->>'canonicalGameInstanceId'
        OR ident->>'targetNamespace' IS DISTINCT FROM gs_request->>'targetNamespace'
        OR ident->>'canonicalTenantId' IS DISTINCT FROM gs_request->>'canonicalTenantId'
        OR ident->>'worldSlug' IS DISTINCT FROM gs_request->>'worldSlug'
        OR ident->>'controlPlaneRequestId' IS DISTINCT FROM gs_request->>'controlPlaneRequestId'
        OR gs_request->>'launchDescriptorId' IS DISTINCT FROM gs_evidence->>'launchDescriptorId'
        OR gs_request->>'expectedDescriptorRequestDigest' IS DISTINCT FROM gs_evidence->>'descriptorRequestDigest'
        OR gs_request->>'expectedDescriptorResultDigest' IS DISTINCT FROM gs_evidence->>'descriptorResultDigest'
        OR gs_request->>'expectedReleaseAttestationEvidenceDigest'
            IS DISTINCT FROM gs_evidence->>'releaseAttestationEvidenceDigest'
        OR ident->>'playableStateNamespaceId' IS DISTINCT FROM gs_evidence->>'playableStateNamespaceId'
        OR ident->>'playableStateScope' IS DISTINCT FROM gs_evidence->>'playableStateScope'
        OR ident->>'publicProduction' IS DISTINCT FROM gs_evidence->>'publicProduction'
        OR ident->>'targetNamespace' IS DISTINCT FROM launch->>'targetNamespace'
        OR ident->>'canonicalTenantId' IS DISTINCT FROM launch->>'canonicalTenantId'
        OR ident->>'worldSlug' IS DISTINCT FROM launch->>'worldSlug'
        OR ident->>'controlPlaneRequestId' IS DISTINCT FROM launch->>'controlPlaneRequestId'
        OR ident->>'controlPlaneRequestId' IS DISTINCT FROM gs_request->>'controlPlaneRequestId'
        OR topology->>'captureId' IS NULL
        OR topology->>'planDigest' IS NULL
        OR topology->>'planDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR jsonb_typeof(topology->'regionCount') IS DISTINCT FROM 'number'
        OR jsonb_typeof(topology->'zoneCount') IS DISTINCT FROM 'number'
        OR jsonb_typeof(topology->'roomCount') IS DISTINCT FROM 'number'
        OR jsonb_typeof(topology->'exitCount') IS DISTINCT FROM 'number'
        OR jsonb_typeof(topology->'generationRuleCount') IS DISTINCT FROM 'number'
        OR jsonb_typeof(topology->'spawnBindingCount') IS DISTINCT FROM 'number'
        OR (topology->>'regionCount')::BIGINT < 1
        OR (topology->>'zoneCount')::BIGINT < 0
        OR (topology->>'roomCount')::BIGINT < 0
        OR (topology->>'exitCount')::BIGINT < 0
        OR (topology->>'generationRuleCount')::BIGINT < 0
        OR (topology->>'spawnBindingCount')::BIGINT < 0 THEN
        RAISE EXCEPTION 'Canonical preparation identity or exact Game Session echo differs'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT identity_row
    FROM "${serviceSchema}".world_authored_version_identity
    WHERE operation_id = (version_input->>'operationId')::UUID;
    SELECT * INTO STRICT binding_row
    FROM "${serviceSchema}".world_complete_launch_binding
    WHERE binding_operation_id = (launch->>'operationId')::UUID;
    SELECT * INTO STRICT intake_row
    FROM "${serviceSchema}".world_authored_source_intake
    WHERE operation_id = binding_row.intake_operation_id;
    SELECT * INTO STRICT capture_row
    FROM "${serviceSchema}".world_canonical_frozen_topology
    WHERE capture_id = (topology->>'captureId')::UUID;

    IF identity_row.operation_id::TEXT IS DISTINCT FROM topology->>'versionIdentityOperationId'
        OR identity_row.target_namespace IS DISTINCT FROM ident->>'targetNamespace'
        OR identity_row.canonical_tenant_id::TEXT IS DISTINCT FROM ident->>'canonicalTenantId'
        OR identity_row.world_slug IS DISTINCT FROM ident->>'worldSlug'
        OR identity_row.canonical_version_id::TEXT IS DISTINCT FROM version_input->>'canonicalVersionId'
        OR identity_row.canonical_version_id::TEXT IS DISTINCT FROM topology->>'canonicalVersionId'
        OR identity_row.local_tenant_key IS DISTINCT FROM (launch->>'localTenantKey')::BIGINT
        OR identity_row.local_version_key IS DISTINCT FROM (version_input->>'localVersionKey')::BIGINT
        OR identity_row.game_design_version_id IS DISTINCT FROM (version_input->>'gameDesignVersionId')::BIGINT
        OR identity_row.intake_operation_id IS DISTINCT FROM (launch->>'intakeOperationId')::UUID
        OR identity_row.intake_request_id IS DISTINCT FROM (launch->>'intakeRequestId')::UUID
        OR identity_row.source_operation_id IS DISTINCT FROM (launch->>'sourceOperationId')::UUID
        OR identity_row.source_evidence_digest IS DISTINCT FROM launch->>'sourceEvidenceDigest'
        OR identity_row.intake_request_digest IS DISTINCT FROM launch->>'intakeRequestDigest'
        OR identity_row.intake_receipt_digest IS DISTINCT FROM launch->>'intakeReceiptDigest'
        OR binding_row.target_namespace IS DISTINCT FROM ident->>'targetNamespace'
        OR binding_row.canonical_tenant_id::TEXT IS DISTINCT FROM ident->>'canonicalTenantId'
        OR binding_row.world_slug IS DISTINCT FROM ident->>'worldSlug'
        OR binding_row.control_plane_request_id IS DISTINCT FROM ident->>'controlPlaneRequestId'
        OR binding_row.descriptor_request_digest IS DISTINCT FROM gs_evidence->>'descriptorRequestDigest'
        OR binding_row.descriptor_result_digest IS DISTINCT FROM gs_evidence->>'descriptorResultDigest'
        OR binding_row.release_attestation_digest IS DISTINCT FROM gs_evidence->>'releaseAttestationEvidenceDigest'
        OR binding_row.canonical_version_id::TEXT IS DISTINCT FROM version_input->>'canonicalVersionId'
        OR binding_row.canonical_version_id::TEXT IS DISTINCT FROM launch->>'canonicalVersionId'
        OR capture_row.request_id IS DISTINCT FROM (topology->>'requestId')::UUID
        OR capture_row.commit_id IS DISTINCT FROM (topology->>'commitId')::UUID
        OR capture_row.version_identity_operation_id IS DISTINCT FROM identity_row.operation_id
        OR capture_row.binding_json IS DISTINCT FROM (
            SELECT binding_json FROM "${serviceSchema}".world_topology_draft_commit
            WHERE request_id = capture_row.request_id)
        OR capture_row.freeze_request_json::JSONB->>'publicationRequestId'
            IS DISTINCT FROM topology->>'freezeRequestId'
        OR capture_row.freeze_request_json::JSONB->>'publicationFence'
            IS DISTINCT FROM topology->>'publicationFence'
        OR capture_row.freeze_request_json::JSONB->>'requestDigest'
            IS DISTINCT FROM topology->>'publicationRequestDigest'
        OR capture_row.freeze_request_json::JSONB->>'appliedCommitId'
            IS DISTINCT FROM topology->>'appliedCommitId'
        OR capture_row.graph_sha256 !~ '^[0-9a-f]{64}$'
        OR intake_row.source_provenance_kind <> 'NEW_GAME_ROW'
        OR source_input->>'schemaVersion' IS DISTINCT FROM intake_row.schema_version::TEXT
        OR source_input->>'targetNamespace' IS DISTINCT FROM intake_row.target_namespace
        OR source_input->>'intakeRequestId' IS DISTINCT FROM intake_row.intake_request_id::TEXT
        OR source_input->>'operationId' IS DISTINCT FROM intake_row.operation_id::TEXT
        OR source_input->>'canonicalTenantId' IS DISTINCT FROM intake_row.canonical_tenant_id::TEXT
        OR source_input->>'worldSlug' IS DISTINCT FROM intake_row.world_slug
        OR source_input->>'sourceOperationId' IS DISTINCT FROM intake_row.source_operation_id::TEXT
        OR source_input->>'sourceEvidenceDigest' IS DISTINCT FROM intake_row.source_evidence_digest
        OR source_input->>'requestDigest' IS DISTINCT FROM intake_row.request_digest
        OR source_input->>'receiptDigest' IS DISTINCT FROM intake_row.receipt_digest
        OR source_input->>'localTenantKey' IS DISTINCT FROM intake_row.local_tenant_key::TEXT
        OR source_evidence_input->>'schemaVersion' IS DISTINCT FROM intake_row.source_schema_version::TEXT
        OR source_evidence_input->>'registrationRequestId' IS DISTINCT FROM intake_row.source_registration_request_id::TEXT
        OR source_evidence_input->>'sourceOperationId' IS DISTINCT FROM intake_row.source_operation_id::TEXT
        OR source_evidence_input->>'requestDigest' IS DISTINCT FROM intake_row.source_request_digest
        OR source_evidence_input->>'canonicalTenantId' IS DISTINCT FROM intake_row.canonical_tenant_id::TEXT
        OR source_evidence_input->>'tenantSlug' IS DISTINCT FROM intake_row.tenant_slug
        OR source_evidence_input->>'worldSlug' IS DISTINCT FROM intake_row.world_slug
        OR source_evidence_input->>'worldDisplayName' IS DISTINCT FROM intake_row.world_display_name
        OR source_evidence_input->>'sourceGameRowId' IS DISTINCT FROM intake_row.source_game_row_id::TEXT
        OR source_evidence_input->>'sourceGameTenantKey' IS DISTINCT FROM intake_row.source_game_tenant_key
        OR source_evidence_input->>'provenanceKind' IS DISTINCT FROM intake_row.source_provenance_kind
        OR source_evidence_input->>'evidenceDigest' IS DISTINCT FROM intake_row.source_evidence_digest
        OR capture_row.graph_bytes IS NULL OR octet_length(capture_row.graph_bytes) = 0
        OR capture_row.graph_sha256 IS NULL OR capture_row.graph_sha256 !~ '^[0-9a-f]{64}$'
        OR NOT EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_authored_source_tenant_key_reservation r
            WHERE r.tenant_key = identity_row.local_tenant_key
                AND r.claim_kind = 'CANONICAL_AUTHORED_SOURCE') THEN
        RAISE EXCEPTION 'Canonical preparation differs from exact V26/V27 source, version, or frozen capture'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) FILTER (WHERE family = 'REGION'),
        count(*) FILTER (WHERE family = 'ZONE'),
        count(*) FILTER (WHERE family = 'ROOM'),
        count(*) FILTER (WHERE family = 'ROOM_EXIT'),
        count(*) FILTER (WHERE family = 'GENERATION_RULE'),
        count(*) FILTER (WHERE family = 'WORLD_ENTITY_SPAWN_BINDING'),
        count(*)
    INTO region_count, zone_count, room_count, exit_count, generation_count, spawn_count, template_count
    FROM "${serviceSchema}".world_authored_topology_identity
    WHERE request_id = capture_row.request_id AND commit_id = capture_row.commit_id
        AND version_identity_operation_id = identity_row.operation_id;

    -- Required intent is a hard pre-allocation rejection; there is no skip/default path.
    IF generation_count <> 0 OR spawn_count <> 0
        OR generation_count <> (topology->>'generationRuleCount')::BIGINT
        OR spawn_count <> (topology->>'spawnBindingCount')::BIGINT THEN
        RAISE EXCEPTION 'Canonical generation-free preparation rejects configured generation/spawn intent'
            USING ERRCODE = '23514';
    END IF;
    IF region_count <> (topology->>'regionCount')::BIGINT
        OR zone_count <> (topology->>'zoneCount')::BIGINT
        OR room_count <> (topology->>'roomCount')::BIGINT
        OR exit_count <> (topology->>'exitCount')::BIGINT
        OR template_count <> region_count + zone_count + room_count + exit_count
        OR region_count = 0 THEN
        RAISE EXCEPTION 'Canonical preparation plan omits or adds a selected frozen graph row'
            USING ERRCODE = '23514';
    END IF;

    -- The capture's V25 version owner is the serialization fence shared with every V29 writer.
    -- Hold it through this transaction: a writer that began first completes before this row can be
    -- locked (and its phase is then rechecked); a later writer cannot acquire its required UPDATE
    -- lock until this exact immutable source graph has been materialized and committed.
    SELECT * INTO STRICT publication_owner
    FROM "${serviceSchema}".world_design_publication_fence_owner
    WHERE local_tenant_key = identity_row.local_tenant_key
        AND version_id = identity_row.local_version_key
    FOR SHARE;
    IF publication_owner.owner_freeze_phase IS DISTINCT FROM 'FROZEN'
        OR publication_owner.current_publication_fence IS DISTINCT FROM capture_row.publication_fence THEN
        RAISE EXCEPTION 'Canonical preparation requires the exact still-FROZEN V25 source owner'
            USING ERRCODE = '23514';
    END IF;

    -- Serialize exact canonical-id and request retries before checking prior identity or allocating
    -- any private key/row identifier. These locks are entered only after the external verifier ran.
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'canonical-game-instance:' || (ident->>'canonicalGameInstanceId'), 0));
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'canonical-prepare-request:' || (ident->>'targetNamespace') || ':'
            || (ident->>'canonicalTenantId') || ':' || (ident->>'controlPlaneRequestId'), 0));

    SELECT * INTO prior FROM "${serviceSchema}".world_canonical_instance_preparation
    WHERE canonical_game_instance_id = (ident->>'canonicalGameInstanceId')::UUID FOR UPDATE;
    IF FOUND THEN
        IF prior.input_json IS DISTINCT FROM p_input_json
            OR prior.input_digest IS DISTINCT FROM p_input_digest
            OR prior.capture_id IS DISTINCT FROM capture_row.capture_id
            OR prior.graph_bytes IS DISTINCT FROM capture_row.graph_bytes
            OR prior.graph_sha256 IS DISTINCT FROM capture_row.graph_sha256 THEN
            RAISE EXCEPTION 'Canonical gameInstanceId is already bound to a different preparation'
                USING ERRCODE = '23505';
        END IF;
        world_instance_id := prior.world_instance_id;
        private_game_instance_key := prior.private_game_instance_key;
        RETURN NEXT;
        RETURN;
    END IF;
    IF EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation
        WHERE target_namespace = ident->>'targetNamespace'
            AND canonical_tenant_id = (ident->>'canonicalTenantId')::UUID
            AND control_plane_request_id = ident->>'controlPlaneRequestId')
        OR EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_instance
            WHERE canonical_game_instance_id = (ident->>'canonicalGameInstanceId')::UUID)
        OR EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_instance
            WHERE tenant_id = identity_row.local_tenant_key
                AND control_plane_request_id = ident->>'controlPlaneRequestId') THEN
        RAISE EXCEPTION 'Canonical game instance or request identity is already reserved'
            USING ERRCODE = '23505';
    END IF;

    LOOP
        local_game_instance_key := nextval('"${serviceSchema}".world_canonical_private_game_instance_key_seq');
        EXIT WHEN NOT EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_instance
            WHERE tenant_id = identity_row.local_tenant_key
                AND game_instance_id = local_game_instance_key);
    END LOOP;
    new_world_instance_id := nextval('"${serviceSchema}".world_instance_id_seq');
    world_row := jsonb_populate_record(NULL::"${serviceSchema}".world_instance,
        jsonb_build_object(
            'id', new_world_instance_id,
            'tenant_id', identity_row.local_tenant_key,
            'game_instance_id', local_game_instance_key,
            'game_template_id', (binding_row.descriptor_json::JSONB->>'gameTemplateId')::BIGINT,
            'control_plane_request_id', ident->>'controlPlaneRequestId',
            'launch_descriptor_id', binding_row.descriptor_json::JSONB->>'launchDescriptorId',
            'version_id', identity_row.local_version_key,
            'script_patch_version', CASE WHEN binding_row.descriptor_json::JSONB->>'scriptPatchVersionPresent' = 'true'
                THEN binding_row.descriptor_json::JSONB->>'scriptPatchVersion' ELSE NULL END,
            'runtime_flags_json', binding_row.descriptor_json::JSONB->>'runtimeFlagsJson',
            'generation_config_revision', binding_row.descriptor_json::JSONB->>'generationConfigRevision',
            'release_bundle_id', (binding_row.descriptor_json::JSONB->>'releaseBundleId')::BIGINT,
            'published_release_bundle_ref', binding_row.descriptor_json::JSONB->>'publishedReleaseBundleRef',
            'version_state_epoch', (binding_row.descriptor_json::JSONB->>'versionStateEpoch')::BIGINT,
            'lifecycle_epoch', 1,
            'status', 'PREPARING',
            'failure_reason', NULL,
            'created_at', CURRENT_TIMESTAMP::TIMESTAMP,
            'updated_at', CURRENT_TIMESTAMP::TIMESTAMP,
            'row_version', 0,
            'termination_request_id', NULL,
            'terminated_at', NULL,
            'remap_set_id', CASE WHEN binding_row.descriptor_json::JSONB->>'remapSetIdPresent' = 'true'
                THEN binding_row.descriptor_json::JSONB->>'remapSetId' ELSE NULL END,
            'canonical_game_instance_id', (ident->>'canonicalGameInstanceId')::UUID,
            'canonical_target_namespace', ident->>'targetNamespace',
            'canonical_tenant_id', (ident->>'canonicalTenantId')::UUID,
            'canonical_world_slug', ident->>'worldSlug',
            'playable_state_namespace_id', (ident->>'playableStateNamespaceId')::UUID,
            'playable_state_scope', 'SHARED',
            'public_production', TRUE,
            'playtest_lifecycle_id', NULL,
            'playtest_state_generation', NULL,
            'canonical_launch_binding_operation_id', binding_row.binding_operation_id));
    full_row := to_jsonb(world_row);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(), 'world_instance', new_world_instance_id,
        (ident->>'canonicalGameInstanceId')::UUID, ident->>'targetNamespace',
        (ident->>'canonicalTenantId')::UUID, identity_row.canonical_version_id,
        identity_row.operation_id, capture_row.capture_id, identity_row.local_tenant_key,
        p_input_digest, full_row);
    INSERT INTO "${serviceSchema}".world_instance SELECT (jsonb_populate_record(
        NULL::"${serviceSchema}".world_instance, full_row)).*;

    FOR source_region IN
        SELECT m.template_id, m.private_row_key, r.shard_id, r.name, r.weather,
            r.generation_seed, r.generator_type, r.generator_params, r.spacing_multiplier
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".region r ON r.id = m.private_row_key
        WHERE m.request_id = capture_row.request_id AND m.commit_id = capture_row.commit_id
            AND m.version_identity_operation_id = identity_row.operation_id AND m.family = 'REGION'
            AND r.tenant_id = identity_row.local_tenant_key AND r.version_id = identity_row.local_version_key
        ORDER BY m.template_id
    LOOP
        runtime_id := nextval('"${serviceSchema}".region_instance_id_seq');
        runtime_uuid := gen_random_uuid();
        region_runtime := jsonb_populate_record(NULL::"${serviceSchema}".region_instance,
            jsonb_build_object(
                'id', runtime_id, 'tenant_id', identity_row.local_tenant_key,
                'game_instance_id', local_game_instance_key, 'world_instance_id', new_world_instance_id,
                'shard_id', source_region.shard_id, 'name', source_region.name,
                'weather', source_region.weather, 'generation_seed', source_region.generation_seed,
                'generator_type', source_region.generator_type,
                'generator_params', source_region.generator_params,
                'spacing_multiplier', source_region.spacing_multiplier, 'version', 0,
                'canonical_region_instance_id', runtime_uuid));
        full_row := to_jsonb(region_runtime);
        INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
            txid_current(), 'region_instance', runtime_id,
            (ident->>'canonicalGameInstanceId')::UUID, ident->>'targetNamespace',
            (ident->>'canonicalTenantId')::UUID, identity_row.canonical_version_id,
            identity_row.operation_id, capture_row.capture_id, identity_row.local_tenant_key,
            p_input_digest, full_row);
        INSERT INTO "${serviceSchema}".region_instance SELECT (jsonb_populate_record(
            NULL::"${serviceSchema}".region_instance, full_row)).*;

        topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            jsonb_build_object('id', nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
                'world_instance_id', new_world_instance_id,
                'canonical_game_instance_id', (ident->>'canonicalGameInstanceId')::UUID,
                'family', 'REGION', 'template_id', source_region.template_id,
                'template_private_row_key', source_region.private_row_key,
                'runtime_row_id', runtime_id, 'runtime_identity', runtime_uuid,
                'runtime_room_instance_id', NULL));
        full_row := to_jsonb(topology_identity);
        INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
            txid_current(), 'world_canonical_instance_topology_identity', topology_identity.id,
            (ident->>'canonicalGameInstanceId')::UUID, ident->>'targetNamespace',
            (ident->>'canonicalTenantId')::UUID, identity_row.canonical_version_id,
            identity_row.operation_id, capture_row.capture_id, identity_row.local_tenant_key,
            p_input_digest, full_row);
        INSERT INTO "${serviceSchema}".world_canonical_instance_topology_identity SELECT (jsonb_populate_record(
            NULL::"${serviceSchema}".world_canonical_instance_topology_identity, full_row)).*;
    END LOOP;

    FOR source_zone IN
        SELECT m.template_id, m.private_row_key, z.region_id, z.name
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".zone z ON z.id = m.private_row_key
        WHERE m.request_id = capture_row.request_id AND m.commit_id = capture_row.commit_id
            AND m.version_identity_operation_id = identity_row.operation_id AND m.family = 'ZONE'
            AND z.tenant_id = identity_row.local_tenant_key AND z.version_id = identity_row.local_version_key
        ORDER BY m.template_id
    LOOP
        SELECT ti.runtime_row_id INTO STRICT parent_row
        FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
        WHERE ti.world_instance_id = new_world_instance_id AND ti.family = 'REGION'
            AND ti.template_private_row_key = source_zone.region_id;
        runtime_id := nextval('"${serviceSchema}".zone_instance_id_seq');
        runtime_uuid := gen_random_uuid();
        zone_runtime := jsonb_populate_record(NULL::"${serviceSchema}".zone_instance,
            jsonb_build_object(
                'id', runtime_id, 'tenant_id', identity_row.local_tenant_key,
                'game_instance_id', local_game_instance_key, 'zone_instance_id', runtime_id,
                'template_zone_id', source_zone.private_row_key,
                'region_instance_id', parent_row.runtime_row_id,
                'name', source_zone.name, 'version', 0,
                'canonical_zone_instance_id', runtime_uuid));
        full_row := to_jsonb(zone_runtime);
        INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
            txid_current(), 'zone_instance', runtime_id,
            (ident->>'canonicalGameInstanceId')::UUID, ident->>'targetNamespace',
            (ident->>'canonicalTenantId')::UUID, identity_row.canonical_version_id,
            identity_row.operation_id, capture_row.capture_id, identity_row.local_tenant_key,
            p_input_digest, full_row);
        INSERT INTO "${serviceSchema}".zone_instance SELECT (jsonb_populate_record(
            NULL::"${serviceSchema}".zone_instance, full_row)).*;

        topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            jsonb_build_object('id', nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
                'world_instance_id', new_world_instance_id,
                'canonical_game_instance_id', (ident->>'canonicalGameInstanceId')::UUID,
                'family', 'ZONE', 'template_id', source_zone.template_id,
                'template_private_row_key', source_zone.private_row_key,
                'runtime_row_id', runtime_id, 'runtime_identity', runtime_uuid,
                'runtime_room_instance_id', NULL));
        full_row := to_jsonb(topology_identity);
        INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
            txid_current(), 'world_canonical_instance_topology_identity', topology_identity.id,
            (ident->>'canonicalGameInstanceId')::UUID, ident->>'targetNamespace',
            (ident->>'canonicalTenantId')::UUID, identity_row.canonical_version_id,
            identity_row.operation_id, capture_row.capture_id, identity_row.local_tenant_key,
            p_input_digest, full_row);
        INSERT INTO "${serviceSchema}".world_canonical_instance_topology_identity SELECT (jsonb_populate_record(
            NULL::"${serviceSchema}".world_canonical_instance_topology_identity, full_row)).*;
    END LOOP;

    FOR source_room IN
        SELECT m.template_id, m.private_row_key, r.zone_id, r.name, r.description,
            r.name_localized_variants_json, r.description_localized_variants_json
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".room r ON r.id = m.private_row_key
        WHERE m.request_id = capture_row.request_id AND m.commit_id = capture_row.commit_id
            AND m.version_identity_operation_id = identity_row.operation_id AND m.family = 'ROOM'
            AND r.tenant_id = identity_row.local_tenant_key AND r.version_id = identity_row.local_version_key
        ORDER BY m.template_id
    LOOP
        SELECT ti.runtime_row_id INTO STRICT parent_row
        FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
        WHERE ti.world_instance_id = new_world_instance_id AND ti.family = 'ZONE'
            AND ti.template_private_row_key = source_room.zone_id;
        SELECT ti.runtime_row_id INTO STRICT other_row
        FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
        JOIN "${serviceSchema}".zone z ON z.id = source_room.zone_id
        WHERE ti.world_instance_id = new_world_instance_id AND ti.family = 'REGION'
            AND ti.template_private_row_key = z.region_id;
        runtime_id := nextval('"${serviceSchema}".room_instance_id_seq');
        room_runtime := jsonb_populate_record(NULL::"${serviceSchema}".room_instance,
            jsonb_build_object(
                'id', runtime_id, 'tenant_id', identity_row.local_tenant_key,
                'game_instance_id', local_game_instance_key, 'room_instance_row_id', runtime_id,
                'template_room_id', source_room.private_row_key,
                'region_instance_id', other_row.runtime_row_id,
                'zone_instance_id', parent_row.runtime_row_id,
                'name', source_room.name, 'description', source_room.description,
                'name_localized_variants_json', source_room.name_localized_variants_json,
                'description_localized_variants_json', source_room.description_localized_variants_json,
                'version', 0));
        full_row := to_jsonb(room_runtime);
        INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
            txid_current(), 'room_instance', runtime_id,
            (ident->>'canonicalGameInstanceId')::UUID, ident->>'targetNamespace',
            (ident->>'canonicalTenantId')::UUID, identity_row.canonical_version_id,
            identity_row.operation_id, capture_row.capture_id, identity_row.local_tenant_key,
            p_input_digest, full_row);
        INSERT INTO "${serviceSchema}".room_instance SELECT (jsonb_populate_record(
            NULL::"${serviceSchema}".room_instance, full_row)).*;

        topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            jsonb_build_object('id', nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
                'world_instance_id', new_world_instance_id,
                'canonical_game_instance_id', (ident->>'canonicalGameInstanceId')::UUID,
                'family', 'ROOM', 'template_id', source_room.template_id,
                'template_private_row_key', source_room.private_row_key,
                'runtime_row_id', runtime_id, 'runtime_identity', NULL,
                'runtime_room_instance_id', runtime_id));
        full_row := to_jsonb(topology_identity);
        INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
            txid_current(), 'world_canonical_instance_topology_identity', topology_identity.id,
            (ident->>'canonicalGameInstanceId')::UUID, ident->>'targetNamespace',
            (ident->>'canonicalTenantId')::UUID, identity_row.canonical_version_id,
            identity_row.operation_id, capture_row.capture_id, identity_row.local_tenant_key,
            p_input_digest, full_row);
        INSERT INTO "${serviceSchema}".world_canonical_instance_topology_identity SELECT (jsonb_populate_record(
            NULL::"${serviceSchema}".world_canonical_instance_topology_identity, full_row)).*;
    END LOOP;

    FOR source_exit IN
        SELECT m.template_id, m.private_row_key, e.from_room_id, e.to_room_id, e.direction, e.cost
        FROM "${serviceSchema}".world_authored_topology_identity m
        JOIN "${serviceSchema}".room_exit e ON e.id = m.private_row_key
        WHERE m.request_id = capture_row.request_id AND m.commit_id = capture_row.commit_id
            AND m.version_identity_operation_id = identity_row.operation_id AND m.family = 'ROOM_EXIT'
            AND e.tenant_id = identity_row.local_tenant_key AND e.version_id = identity_row.local_version_key
        ORDER BY m.template_id
    LOOP
        SELECT ti.runtime_room_instance_id INTO STRICT parent_row
        FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
        WHERE ti.world_instance_id = new_world_instance_id AND ti.family = 'ROOM'
            AND ti.template_private_row_key = source_exit.from_room_id;
        SELECT ti.runtime_room_instance_id INTO STRICT other_row
        FROM "${serviceSchema}".world_canonical_instance_topology_identity ti
        WHERE ti.world_instance_id = new_world_instance_id AND ti.family = 'ROOM'
            AND ti.template_private_row_key = source_exit.to_room_id;
        runtime_id := nextval('"${serviceSchema}".room_instance_exit_id_seq');
        exit_runtime := jsonb_populate_record(NULL::"${serviceSchema}".room_instance_exit,
            jsonb_build_object(
                'id', runtime_id, 'tenant_id', identity_row.local_tenant_key,
                'game_instance_id', local_game_instance_key,
                'from_room_instance_record_id', parent_row.runtime_room_instance_id,
                'to_room_instance_record_id', other_row.runtime_room_instance_id,
                'direction', source_exit.direction, 'cost', source_exit.cost, 'version', 0));
        full_row := to_jsonb(exit_runtime);
        INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
            txid_current(), 'room_instance_exit', runtime_id,
            (ident->>'canonicalGameInstanceId')::UUID, ident->>'targetNamespace',
            (ident->>'canonicalTenantId')::UUID, identity_row.canonical_version_id,
            identity_row.operation_id, capture_row.capture_id, identity_row.local_tenant_key,
            p_input_digest, full_row);
        INSERT INTO "${serviceSchema}".room_instance_exit SELECT (jsonb_populate_record(
            NULL::"${serviceSchema}".room_instance_exit, full_row)).*;

        topology_identity := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_topology_identity,
            jsonb_build_object('id', nextval('"${serviceSchema}".world_canonical_instance_topology_identity_id_seq'),
                'world_instance_id', new_world_instance_id,
                'canonical_game_instance_id', (ident->>'canonicalGameInstanceId')::UUID,
                'family', 'ROOM_EXIT', 'template_id', source_exit.template_id,
                'template_private_row_key', source_exit.private_row_key,
                'runtime_row_id', runtime_id, 'runtime_identity', NULL,
                'runtime_room_instance_id', NULL));
        full_row := to_jsonb(topology_identity);
        INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
            txid_current(), 'world_canonical_instance_topology_identity', topology_identity.id,
            (ident->>'canonicalGameInstanceId')::UUID, ident->>'targetNamespace',
            (ident->>'canonicalTenantId')::UUID, identity_row.canonical_version_id,
            identity_row.operation_id, capture_row.capture_id, identity_row.local_tenant_key,
            p_input_digest, full_row);
        INSERT INTO "${serviceSchema}".world_canonical_instance_topology_identity SELECT (jsonb_populate_record(
            NULL::"${serviceSchema}".world_canonical_instance_topology_identity, full_row)).*;
    END LOOP;

    IF (SELECT count(*) FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest
        WHERE transaction_id = txid_current()) <> 0 THEN
        RAISE EXCEPTION 'Canonical preparation left an unconsumed exact execution manifest'
            USING ERRCODE = '23514';
    END IF;

    new_result_id := nextval('"${serviceSchema}".world_canonical_instance_preparation_id_seq');
    preparation_row := jsonb_populate_record(NULL::"${serviceSchema}".world_canonical_instance_preparation,
        jsonb_build_object(
            'id', new_result_id,
            'canonical_game_instance_id', (ident->>'canonicalGameInstanceId')::UUID,
            'target_namespace', ident->>'targetNamespace',
            'canonical_tenant_id', (ident->>'canonicalTenantId')::UUID,
            'world_slug', ident->>'worldSlug',
            'control_plane_request_id', ident->>'controlPlaneRequestId',
            'canonical_version_id', identity_row.canonical_version_id,
            'version_identity_operation_id', identity_row.operation_id,
            'capture_id', capture_row.capture_id,
            'graph_bytes', capture_row.graph_bytes,
            'graph_sha256', capture_row.graph_sha256,
            'input_digest', p_input_digest,
            'input_json', p_input_json,
            'world_instance_id', new_world_instance_id,
            'private_game_instance_key', local_game_instance_key,
            'region_count', region_count,
            'zone_count', zone_count,
            'room_count', room_count,
            'exit_count', exit_count,
            'storage_status', 'MATERIALIZED_UNVERIFIED',
            'prepared_at', CURRENT_TIMESTAMP));
    full_row := to_jsonb(preparation_row);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation_execution_manifest VALUES (
        txid_current(), 'world_canonical_instance_preparation', new_result_id,
        (ident->>'canonicalGameInstanceId')::UUID, ident->>'targetNamespace',
        (ident->>'canonicalTenantId')::UUID, identity_row.canonical_version_id,
        identity_row.operation_id, capture_row.capture_id, identity_row.local_tenant_key,
        p_input_digest, full_row);
    INSERT INTO "${serviceSchema}".world_canonical_instance_preparation SELECT (jsonb_populate_record(
        NULL::"${serviceSchema}".world_canonical_instance_preparation, full_row)).*;

    IF (SELECT count(*) FROM "${serviceSchema}".world_canonical_instance_preparation_execution_manifest
        WHERE transaction_id = txid_current()) <> 0 THEN
        RAISE EXCEPTION 'Canonical preparation left an unconsumed result manifest'
            USING ERRCODE = '23514';
    END IF;
    world_instance_id := new_world_instance_id;
    private_game_instance_key := local_game_instance_key;
    RETURN NEXT;
END;
$$;
REVOKE ALL ON FUNCTION world_prepare_canonical_instance(TEXT, TEXT) FROM PUBLIC;
-- [jooq ignore stop]
