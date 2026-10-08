-- Fresh canonical actor placement is World-owned and runtime-instance scoped (S3). The target
-- retains its typed V42 authored ROOM selector and mapped runtime room; this migration adds only
-- the actor's first placement and its exact operation result, with no retained-row backfill.
CREATE TABLE world_canonical_initial_player_location_operation (
    canonical_tenant_id UUID NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    canonical_game_instance_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    world_instance_id BIGINT NOT NULL REFERENCES world_instance(id) ON DELETE RESTRICT,
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_bytes BYTEA NOT NULL CHECK (octet_length(request_bytes) > 0),
    original_lifecycle_evidence_bytes BYTEA NOT NULL CHECK (octet_length(original_lifecycle_evidence_bytes) > 0),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('APPLIED', 'CONFLICT')),
    conflict_code VARCHAR(64),
    result_bytes BYTEA NOT NULL CHECK (octet_length(result_bytes) > 0),
    result_digest VARCHAR(71) NOT NULL CHECK (result_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation_id),
    CONSTRAINT fk_world_initial_location_operation_association
        FOREIGN KEY (canonical_game_instance_id)
        REFERENCES world_canonical_instance_association(canonical_game_instance_id) ON DELETE RESTRICT,
    CONSTRAINT ck_world_initial_location_operation_ids CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT ck_world_initial_location_operation_outcome CHECK (
        (outcome = 'APPLIED' AND conflict_code IS NULL)
        OR (outcome = 'CONFLICT' AND conflict_code IS NOT NULL
            AND conflict_code COLLATE "C" ~ '^[A-Z][A-Z0-9_]{0,63}$'
            AND conflict_code COLLATE "C" !~ '[^A-Z0-9_]')
    )
);

CREATE TABLE character_location (
    canonical_tenant_id UUID NOT NULL,
    realm_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    playable_state_scope VARCHAR(16) NOT NULL CHECK (playable_state_scope IN ('SHARED', 'ISOLATED')),
    canonical_game_instance_id UUID NOT NULL,
    world_instance_id BIGINT NOT NULL REFERENCES world_instance(id) ON DELETE RESTRICT,
    canonical_account_id UUID NOT NULL,
    character_id UUID NOT NULL,
    entity_assignment_operation_id UUID NOT NULL,
    entity_assignment_digest CHAR(64) NOT NULL CHECK (entity_assignment_digest ~ '^[0-9a-f]{64}$'),
    canonical_version_id UUID NOT NULL,
    active_lifecycle_epoch BIGINT NOT NULL CHECK (active_lifecycle_epoch > 0),
    initial_room_template_id UUID NOT NULL,
    initial_runtime_room_instance_id BIGINT NOT NULL CHECK (initial_runtime_room_instance_id > 0),
    runtime_room_instance_id BIGINT NOT NULL CHECK (runtime_room_instance_id > 0),
    initial_admission_hold_id UUID NOT NULL,
    initial_admission_hold_fence UUID NOT NULL,
    initial_admission_request_id VARCHAR(128) NOT NULL CHECK (btrim(initial_admission_request_id) <> ''),
    initial_admission_request_digest CHAR(64) NOT NULL CHECK (initial_admission_request_digest ~ '^[0-9a-f]{64}$'),
    catalog_revision BIGINT NOT NULL CHECK (catalog_revision > 0),
    initial_admission_owner_proof_id VARCHAR(128) NOT NULL CHECK (btrim(initial_admission_owner_proof_id) <> ''),
    initial_admission_owner_proof_digest CHAR(64) NOT NULL CHECK (initial_admission_owner_proof_digest ~ '^[0-9a-f]{64}$'),
    pointer_audit_id VARCHAR(128) NOT NULL CHECK (btrim(pointer_audit_id) <> ''),
    pointer_version BIGINT NOT NULL CHECK (pointer_version > 0),
    initial_location_operation_id UUID NOT NULL,
    initial_location_request_digest VARCHAR(71) NOT NULL CHECK (initial_location_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    initial_lifecycle_evidence_bytes BYTEA NOT NULL CHECK (octet_length(initial_lifecycle_evidence_bytes) > 0),
    initial_lifecycle_evidence_digest VARCHAR(71) NOT NULL CHECK (initial_lifecycle_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (canonical_tenant_id, playable_state_namespace_id, canonical_game_instance_id,
                 character_id),
    CONSTRAINT uq_world_character_assignment_operation
        UNIQUE (canonical_tenant_id, playable_state_namespace_id, canonical_game_instance_id,
                entity_assignment_operation_id),
    CONSTRAINT fk_world_character_location_association
        FOREIGN KEY (canonical_game_instance_id)
        REFERENCES world_canonical_instance_association(canonical_game_instance_id) ON DELETE RESTRICT,
    CONSTRAINT fk_world_character_location_operation
        FOREIGN KEY (initial_location_operation_id)
        REFERENCES world_canonical_initial_player_location_operation(operation_id) ON DELETE RESTRICT,
    CONSTRAINT fk_world_character_location_admission_hold
        FOREIGN KEY (initial_admission_hold_id)
        REFERENCES initial_admission_bind_hold(hold_id) ON DELETE RESTRICT,
    CONSTRAINT ck_world_character_location_ids CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_account_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND character_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND entity_assignment_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND initial_room_template_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND initial_admission_hold_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND initial_admission_hold_fence <> '00000000-0000-0000-0000-000000000000'::UUID
        AND initial_location_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT ck_world_character_location_slug CHECK (
        octet_length(world_slug) BETWEEN 1 AND 120 AND world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    )
);

CREATE INDEX idx_world_character_location_occupants
    ON character_location (canonical_tenant_id, playable_state_namespace_id,
                           canonical_game_instance_id, runtime_room_instance_id);

-- [jooq ignore start]
REVOKE ALL ON world_canonical_initial_player_location_operation FROM PUBLIC;
REVOKE ALL ON character_location FROM PUBLIC;

CREATE FUNCTION world_guard_initial_location_operation()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    request JSONB;
    result JSONB;
    canonical_result BYTEA;
    canonical_room TEXT;
    canonical_conflict_code TEXT;
    canonical_runtime_room TEXT;
    request_base64 TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'World initial-location operation evidence is immutable' USING ERRCODE = '55000';
    END IF;
    IF NEW.request_digest IS DISTINCT FROM ('sha256:' || encode(sha256(NEW.request_bytes), 'hex'))
        OR NEW.result_digest IS DISTINCT FROM ('sha256:' || encode(sha256(NEW.result_bytes), 'hex')) THEN
        RAISE EXCEPTION 'World initial-location operation digests do not match retained bytes' USING ERRCODE = '23514';
    END IF;
    request := convert_from(NEW.request_bytes, 'UTF8')::JSONB;
    result := convert_from(NEW.result_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(request) IS DISTINCT FROM 'object'
        OR request->>'schema' IS DISTINCT FROM 'world-canonical-initial-player-location-request/v1'
        OR request->>'operationId' IS DISTINCT FROM NEW.operation_id::TEXT
        OR request->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR request->>'playableStateNamespaceId' IS DISTINCT FROM NEW.playable_state_namespace_id::TEXT
        OR request->>'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
        OR request->>'initialAdmissionOrigin' IS DISTINCT FROM 'NO_PRIOR_POINTER'
        OR jsonb_typeof(request->'activeLifecycleEvidence') IS DISTINCT FROM 'object'
        OR (convert_from(NEW.original_lifecycle_evidence_bytes, 'UTF8')::JSONB #- '{request,readRequestId}')
            IS DISTINCT FROM (request->'activeLifecycleEvidence' #- '{request,readRequestId}')
        THEN
        RAISE EXCEPTION 'World initial-location operation differs from its canonical request/result' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_canonical_instance_association a
        WHERE a.canonical_game_instance_id = NEW.canonical_game_instance_id
            AND a.world_instance_id = NEW.world_instance_id
            AND a.canonical_tenant_id = NEW.canonical_tenant_id
            AND a.playable_state_namespace_id = NEW.playable_state_namespace_id
    ) THEN
        RAISE EXCEPTION 'World initial-location operation differs from its canonical association' USING ERRCODE = '23514';
    END IF;

    IF jsonb_typeof(result) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'World initial-location result must be a closed canonical object' USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(result)) <> 8
        OR NOT (result ?& ARRAY[
            'conflictCode', 'operationId', 'outcome', 'requestBytesBase64',
            'requestDigest', 'runtimeRoomInstanceId', 'schema', 'startLocation'
        ]) THEN
        RAISE EXCEPTION 'World initial-location result must have exactly eight fields' USING ERRCODE = '23514';
    END IF;
    IF jsonb_typeof(result->'schema') IS DISTINCT FROM 'string'
        OR jsonb_typeof(result->'operationId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(result->'requestDigest') IS DISTINCT FROM 'string'
        OR jsonb_typeof(result->'requestBytesBase64') IS DISTINCT FROM 'string'
        OR jsonb_typeof(result->'outcome') IS DISTINCT FROM 'string'
        OR result->>'schema' IS DISTINCT FROM 'world-canonical-initial-player-location-result/v1'
        OR result->>'operationId' IS DISTINCT FROM NEW.operation_id::TEXT
        OR result->>'requestDigest' IS DISTINCT FROM NEW.request_digest
        OR result->>'outcome' IS DISTINCT FROM NEW.outcome THEN
        RAISE EXCEPTION 'World initial-location operation differs from its canonical result identity' USING ERRCODE = '23514';
    END IF;

    request_base64 := replace(replace(encode(NEW.request_bytes, 'base64'), E'\n', ''), E'\r', '');
    IF result->>'requestBytesBase64' IS DISTINCT FROM request_base64
        OR decode(result->>'requestBytesBase64', 'base64') IS DISTINCT FROM NEW.request_bytes THEN
        RAISE EXCEPTION 'World initial-location result request bytes differ from its operation' USING ERRCODE = '23514';
    END IF;

    IF NEW.outcome = 'APPLIED' THEN
        IF jsonb_typeof(result->'conflictCode') IS DISTINCT FROM 'null'
            OR jsonb_typeof(result->'runtimeRoomInstanceId') IS DISTINCT FROM 'string'
            OR jsonb_typeof(result->'startLocation') IS DISTINCT FROM 'object' THEN
            RAISE EXCEPTION 'Applied World initial-location result has an invalid field shape' USING ERRCODE = '23514';
        END IF;
        IF (SELECT count(*) FROM jsonb_object_keys(result->'startLocation')) <> 3
            OR NOT ((result->'startLocation') ?& ARRAY['roomTemplateId', 'tenantId', 'versionId'])
            OR jsonb_typeof(result->'startLocation'->'roomTemplateId') IS DISTINCT FROM 'string'
            OR jsonb_typeof(result->'startLocation'->'tenantId') IS DISTINCT FROM 'string'
            OR jsonb_typeof(result->'startLocation'->'versionId') IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Applied World initial-location result room must have exactly three string fields' USING ERRCODE = '23514';
        END IF;
        IF (result->'startLocation'->>'roomTemplateId') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            OR (result->'startLocation'->>'tenantId') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            OR (result->'startLocation'->>'versionId') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            OR (result->'startLocation'->>'roomTemplateId') = '00000000-0000-0000-0000-000000000000'
            OR (result->'startLocation'->>'tenantId') = '00000000-0000-0000-0000-000000000000'
            OR (result->'startLocation'->>'versionId') = '00000000-0000-0000-0000-000000000000'
            OR (result->>'runtimeRoomInstanceId') !~ '^[1-9][0-9]*$' THEN
            RAISE EXCEPTION 'Applied World initial-location result contains a noncanonical room identity' USING ERRCODE = '23514';
        END IF;
        IF (result->>'runtimeRoomInstanceId')::NUMERIC > 9223372036854775807 THEN
            RAISE EXCEPTION 'Applied World initial-location runtime room id exceeds signed 64-bit range' USING ERRCODE = '23514';
        END IF;
        IF (result->'startLocation') IS DISTINCT FROM
                (request->'activeLifecycleEvidence'->'startLocation')
            OR result->>'runtimeRoomInstanceId' IS DISTINCT FROM
                request->'activeLifecycleEvidence'->>'runtimeRoomInstanceId' THEN
            RAISE EXCEPTION 'Applied World initial-location result differs from the exact V2 ROOM mapping' USING ERRCODE = '23514';
        END IF;
        canonical_room :=
            '{"roomTemplateId":' || to_jsonb(result->'startLocation'->>'roomTemplateId')::TEXT
            || ',"tenantId":' || to_jsonb(result->'startLocation'->>'tenantId')::TEXT
            || ',"versionId":' || to_jsonb(result->'startLocation'->>'versionId')::TEXT || '}';
        canonical_conflict_code := 'null';
        canonical_runtime_room := to_jsonb(result->>'runtimeRoomInstanceId')::TEXT;
    ELSE
        IF jsonb_typeof(result->'conflictCode') IS DISTINCT FROM 'string'
            OR jsonb_typeof(result->'runtimeRoomInstanceId') IS DISTINCT FROM 'null'
            OR jsonb_typeof(result->'startLocation') IS DISTINCT FROM 'null'
            OR result->>'conflictCode' IS DISTINCT FROM NEW.conflict_code THEN
            RAISE EXCEPTION 'Conflicting World initial-location result has an invalid field shape' USING ERRCODE = '23514';
        END IF;
        IF (result->>'conflictCode') COLLATE "C" !~ '^[A-Z][A-Z0-9_]{0,63}$'
            OR (result->>'conflictCode') COLLATE "C" ~ '[^A-Z0-9_]' THEN
            RAISE EXCEPTION 'Conflicting World initial-location result has an invalid machine code' USING ERRCODE = '23514';
        END IF;
        canonical_room := 'null';
        canonical_conflict_code := to_jsonb(result->>'conflictCode')::TEXT;
        canonical_runtime_room := 'null';
    END IF;

    canonical_result := convert_to(
        '{"conflictCode":' || canonical_conflict_code
        || ',"operationId":' || to_jsonb(NEW.operation_id::TEXT)::TEXT
        || ',"outcome":' || to_jsonb(NEW.outcome)::TEXT
        || ',"requestBytesBase64":' || to_jsonb(request_base64)::TEXT
        || ',"requestDigest":' || to_jsonb(NEW.request_digest)::TEXT
        || ',"runtimeRoomInstanceId":' || canonical_runtime_room
        || ',"schema":"world-canonical-initial-player-location-result/v1"'
        || ',"startLocation":' || canonical_room || '}',
        'UTF8');
    IF NEW.result_bytes IS DISTINCT FROM canonical_result THEN
        RAISE EXCEPTION 'World initial-location result bytes are not canonical' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_world_guard_initial_location_operation
    BEFORE INSERT OR UPDATE OR DELETE ON world_canonical_initial_player_location_operation
    FOR EACH ROW EXECUTE FUNCTION world_guard_initial_location_operation();
REVOKE ALL ON FUNCTION world_guard_initial_location_operation() FROM PUBLIC;

CREATE FUNCTION world_guard_initial_character_location()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    operation_row "${serviceSchema}".world_canonical_initial_player_location_operation%ROWTYPE;
    request JSONB;
    lifecycle JSONB;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Initial placement is immutable; a separate World movement owner must be implemented' USING ERRCODE = '55000';
    END IF;
    IF TG_OP <> 'INSERT' THEN RETURN NEW; END IF;

    SELECT * INTO STRICT operation_row
    FROM "${serviceSchema}".world_canonical_initial_player_location_operation
    WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND playable_state_namespace_id = NEW.playable_state_namespace_id
        AND canonical_game_instance_id = NEW.canonical_game_instance_id
        AND operation_id = NEW.initial_location_operation_id;
    request := convert_from(operation_row.request_bytes, 'UTF8')::JSONB;
    lifecycle := convert_from(NEW.initial_lifecycle_evidence_bytes, 'UTF8')::JSONB;

    IF operation_row.outcome IS DISTINCT FROM 'APPLIED'
        OR operation_row.request_digest IS DISTINCT FROM NEW.initial_location_request_digest
        OR request->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR request->>'playableStateNamespaceId' IS DISTINCT FROM NEW.playable_state_namespace_id::TEXT
        OR request->>'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
        OR request->>'playableStateScope' IS DISTINCT FROM NEW.playable_state_scope
        OR request->>'canonicalAccountId' IS DISTINCT FROM NEW.canonical_account_id::TEXT
        OR request->>'characterId' IS DISTINCT FROM NEW.character_id::TEXT
        OR request->>'entityAssignmentOperationId' IS DISTINCT FROM NEW.entity_assignment_operation_id::TEXT
        OR request->>'entityAssignmentDigest' IS DISTINCT FROM NEW.entity_assignment_digest
        OR request->>'realmId' IS DISTINCT FROM NEW.realm_id::TEXT
        OR request->>'worldSlug' IS DISTINCT FROM NEW.world_slug
        OR request->>'initialAdmissionHoldId' IS DISTINCT FROM NEW.initial_admission_hold_id::TEXT
        OR request->>'initialAdmissionHoldFence' IS DISTINCT FROM NEW.initial_admission_hold_fence::TEXT
        OR request->>'initialAdmissionOrigin' IS DISTINCT FROM 'NO_PRIOR_POINTER'
        OR request->>'initialAdmissionRequestId' IS DISTINCT FROM NEW.initial_admission_request_id
        OR request->>'initialAdmissionRequestDigest' IS DISTINCT FROM NEW.initial_admission_request_digest
        OR request->>'initialAdmissionOwnerProofId' IS DISTINCT FROM NEW.initial_admission_owner_proof_id
        OR request->>'initialAdmissionOwnerProofDigest' IS DISTINCT FROM NEW.initial_admission_owner_proof_digest
        OR request->>'pointerAuditId' IS DISTINCT FROM NEW.pointer_audit_id
        OR request->>'pointerVersion' IS DISTINCT FROM NEW.pointer_version::TEXT
        OR request->>'catalogRevision' IS DISTINCT FROM NEW.catalog_revision::TEXT
        OR request->'activeLifecycleEvidence' IS NULL
        OR (request->'activeLifecycleEvidence' #- '{request,readRequestId}')
            IS DISTINCT FROM (lifecycle #- '{request,readRequestId}')
        OR NEW.initial_lifecycle_evidence_digest IS DISTINCT FROM ('sha256:' || encode(sha256(NEW.initial_lifecycle_evidence_bytes), 'hex'))
        OR lifecycle->>'schema' IS DISTINCT FROM 'world-canonical-instance-lifecycle-evidence/v1'
        OR lifecycle->>'lifecycleStatus' IS DISTINCT FROM 'ACTIVE'
        OR lifecycle->>'lifecycleEpoch' IS DISTINCT FROM NEW.active_lifecycle_epoch::TEXT
        OR lifecycle->'request'->>'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
        OR lifecycle->'request'->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR lifecycle->'request'->>'playableStateNamespaceId' IS DISTINCT FROM NEW.playable_state_namespace_id::TEXT
        OR lifecycle->'request'->>'playableStateScope' IS DISTINCT FROM NEW.playable_state_scope
        OR lifecycle->'startLocation'->>'tenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR lifecycle->'startLocation'->>'versionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR lifecycle->'startLocation'->>'roomTemplateId' IS DISTINCT FROM NEW.initial_room_template_id::TEXT
        OR lifecycle->>'runtimeRoomInstanceId' IS DISTINCT FROM NEW.initial_runtime_room_instance_id::TEXT
        OR NEW.runtime_room_instance_id IS DISTINCT FROM NEW.initial_runtime_room_instance_id THEN
        RAISE EXCEPTION 'World initial placement differs from its retained operation or lifecycle proof' USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM "${serviceSchema}".world_canonical_instance_association a
        JOIN "${serviceSchema}".world_instance w ON w.id = a.world_instance_id
        JOIN "${serviceSchema}".world_canonical_preparation_start_location s
            ON s.canonical_game_instance_id = a.canonical_game_instance_id
            AND s.world_instance_id = a.world_instance_id
        JOIN "${serviceSchema}".world_canonical_instance_topology_identity m
            ON m.world_instance_id = a.world_instance_id
            AND m.canonical_game_instance_id = a.canonical_game_instance_id
            AND m.family = 'ROOM' AND m.template_id = s.room_template_id
            AND m.runtime_room_instance_id = s.runtime_room_instance_id
        JOIN "${serviceSchema}".room_instance r
            ON r.id = m.runtime_row_id AND r.room_instance_row_id = s.runtime_room_instance_id
        JOIN "${serviceSchema}".initial_admission_bind_hold h
            ON h.hold_id = NEW.initial_admission_hold_id
        WHERE a.canonical_game_instance_id = NEW.canonical_game_instance_id
            AND a.world_instance_id = NEW.world_instance_id
            AND a.canonical_tenant_id = NEW.canonical_tenant_id
            AND a.canonical_world_slug = NEW.world_slug
            AND a.playable_state_namespace_id = NEW.playable_state_namespace_id
            AND a.playable_state_scope = NEW.playable_state_scope
            AND a.canonical_version_id = NEW.canonical_version_id
            AND w.canonical_game_instance_id = a.canonical_game_instance_id
            AND w.canonical_tenant_id = NEW.canonical_tenant_id
            AND w.canonical_world_slug = NEW.world_slug
            AND w.playable_state_namespace_id = NEW.playable_state_namespace_id
            AND w.playable_state_scope = NEW.playable_state_scope
            AND w.status = 'ACTIVE' AND w.lifecycle_epoch = NEW.active_lifecycle_epoch
            AND s.canonical_tenant_id = NEW.canonical_tenant_id
            AND s.canonical_version_id = NEW.canonical_version_id
            AND s.room_template_id = NEW.initial_room_template_id
            AND s.runtime_room_instance_id = NEW.initial_runtime_room_instance_id
            AND r.tenant_id = w.tenant_id AND r.game_instance_id = w.game_instance_id
            AND h.tenant_id = w.tenant_id AND h.game_instance_id = w.game_instance_id
            AND h.realm_uuid = NEW.realm_id
            AND h.playable_state_namespace_uuid = NEW.playable_state_namespace_id
            AND h.playable_state_scope = NEW.playable_state_scope
            AND h.version_id = w.version_id AND h.active_lifecycle_epoch = w.lifecycle_epoch
            AND h.status = 'COMMITTED' AND h.expected_no_prior_pointer IS TRUE
            AND h.initial_admission_request_id = NEW.initial_admission_request_id
            AND h.request_digest = NEW.initial_admission_request_digest
            AND h.expected_catalog_revision = NEW.catalog_revision
            AND h.owner_proof_id = NEW.initial_admission_owner_proof_id
            AND h.owner_proof_digest = NEW.initial_admission_owner_proof_digest
            AND h.owner_pointer_audit_id = NEW.pointer_audit_id
            AND h.owner_pointer_version = NEW.pointer_version
            AND h.hold_fence = NEW.initial_admission_hold_fence
    ) THEN
        RAISE EXCEPTION 'World initial location lacks exact current association, V2 ROOM mapping, or committed first-open proof' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_world_guard_initial_character_location
    BEFORE INSERT OR UPDATE ON character_location
    FOR EACH ROW EXECUTE FUNCTION world_guard_initial_character_location();
REVOKE ALL ON FUNCTION world_guard_initial_character_location() FROM PUBLIC;

CREATE FUNCTION world_require_initial_location_operation_result()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    request JSONB;
    result JSONB;
    lifecycle JSONB;
BEGIN
    request := convert_from(NEW.request_bytes, 'UTF8')::JSONB;
    result := convert_from(NEW.result_bytes, 'UTF8')::JSONB;
    lifecycle := request->'activeLifecycleEvidence';
    IF NEW.outcome = 'APPLIED' AND NOT EXISTS (
        SELECT 1 FROM "${serviceSchema}".character_location l
        WHERE l.canonical_tenant_id = NEW.canonical_tenant_id
            AND l.playable_state_namespace_id = NEW.playable_state_namespace_id
            AND l.canonical_game_instance_id = NEW.canonical_game_instance_id
            AND l.canonical_account_id = (convert_from(NEW.request_bytes, 'UTF8')::JSONB->>'canonicalAccountId')::UUID
            AND l.character_id = (convert_from(NEW.request_bytes, 'UTF8')::JSONB->>'characterId')::UUID
            AND l.entity_assignment_operation_id = (convert_from(NEW.request_bytes, 'UTF8')::JSONB->>'entityAssignmentOperationId')::UUID
            AND l.entity_assignment_digest = convert_from(NEW.request_bytes, 'UTF8')::JSONB->>'entityAssignmentDigest'
            AND l.canonical_tenant_id = (result->'startLocation'->>'tenantId')::UUID
            AND l.canonical_version_id = (result->'startLocation'->>'versionId')::UUID
            AND l.initial_room_template_id = (result->'startLocation'->>'roomTemplateId')::UUID
            AND l.initial_runtime_room_instance_id = (result->>'runtimeRoomInstanceId')::BIGINT
            AND result->'startLocation' IS NOT DISTINCT FROM lifecycle->'startLocation'
            AND result->>'runtimeRoomInstanceId' IS NOT DISTINCT FROM lifecycle->>'runtimeRoomInstanceId'
            AND l.canonical_account_id = (request->>'canonicalAccountId')::UUID
            AND l.initial_admission_hold_id = (request->>'initialAdmissionHoldId')::UUID
            AND l.initial_admission_hold_fence = (request->>'initialAdmissionHoldFence')::UUID
            AND l.realm_id = (request->>'realmId')::UUID
            AND l.world_slug = request->>'worldSlug'
            AND l.playable_state_scope = request->>'playableStateScope'
            AND l.canonical_version_id = (lifecycle->'request'->>'canonicalVersionId')::UUID
            AND l.active_lifecycle_epoch = (lifecycle->>'lifecycleEpoch')::BIGINT
            AND l.initial_admission_request_id = request->>'initialAdmissionRequestId'
            AND l.initial_admission_request_digest = request->>'initialAdmissionRequestDigest'
            AND l.catalog_revision = (request->>'catalogRevision')::BIGINT
            AND l.initial_admission_owner_proof_id = request->>'initialAdmissionOwnerProofId'
            AND l.initial_admission_owner_proof_digest = request->>'initialAdmissionOwnerProofDigest'
            AND l.pointer_audit_id = request->>'pointerAuditId'
            AND l.pointer_version = (request->>'pointerVersion')::BIGINT
    ) THEN
        RAISE EXCEPTION 'Applied World initial-location operation has no atomic location row' USING ERRCODE = '23514';
    END IF;
    IF NEW.outcome = 'CONFLICT' AND EXISTS (
        SELECT 1 FROM "${serviceSchema}".character_location l
        WHERE l.canonical_tenant_id = NEW.canonical_tenant_id
            AND l.playable_state_namespace_id = NEW.playable_state_namespace_id
            AND l.canonical_game_instance_id = NEW.canonical_game_instance_id
            AND l.initial_location_operation_id = NEW.operation_id
    ) THEN
        RAISE EXCEPTION 'Conflicting World initial-location operation cannot own a location row' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_world_require_initial_location_operation_result
    AFTER INSERT ON world_canonical_initial_player_location_operation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION world_require_initial_location_operation_result();
REVOKE ALL ON FUNCTION world_require_initial_location_operation_result() FROM PUBLIC;

CREATE FUNCTION world_reject_initial_location_operation_truncate()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    RAISE EXCEPTION 'World initial-location operation evidence cannot be truncated' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_world_reject_initial_location_operation_truncate
    BEFORE TRUNCATE ON world_canonical_initial_player_location_operation
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_initial_location_operation_truncate();
REVOKE ALL ON FUNCTION world_reject_initial_location_operation_truncate() FROM PUBLIC;
-- [jooq ignore stop]
