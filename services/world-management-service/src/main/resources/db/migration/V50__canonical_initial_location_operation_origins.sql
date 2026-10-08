-- Forward-align the placement operation guard with the two tagged first-open origins.
-- V46 and V49 remain unchanged; the typed hold and owner-proof checks stay in the V49 location guard.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION world_guard_initial_location_operation()
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
        OR request->>'initialAdmissionOrigin' IS NULL
        OR request->>'initialAdmissionOrigin' NOT IN ('NO_PRIOR_POINTER', 'EXPECT_CLOSED')
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
REVOKE ALL ON FUNCTION world_guard_initial_location_operation() FROM PUBLIC;
-- [jooq ignore stop]
