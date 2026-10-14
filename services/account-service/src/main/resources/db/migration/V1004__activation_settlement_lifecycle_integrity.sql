-- Validate closed PREPARING lifecycle evidence before acquiring a source protection,
-- and closed ACTIVE evidence before accepting COMMITTED settlement. Preserve V1003's
-- source/owner lock order, completeness, terminal, outcome and immutable binding fences.
-- This integrity guard does not authenticate World or duplicate its nested launch-binding codec:
-- launch binding and all immutable lifecycle values remain exactly the retained owner values.
-- [jooq ignore start]
CREATE FUNCTION account_ss_activation_validate_lifecycle(
    lifecycle_bytes BYTEA,
    expected_status TEXT
) RETURNS JSONB LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    lifecycle JSONB;
    request_value JSONB;
    field_name TEXT;
    region RECORD;
    uuid_pattern CONSTANT TEXT := '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
    nil_uuid CONSTANT TEXT := '00000000-0000-0000-0000-000000000000';
BEGIN
    -- Check before JSONB normalizes duplicate keys, including duplicates in nested objects.
    IF NOT (convert_from(lifecycle_bytes, 'UTF8') IS JSON OBJECT WITH UNIQUE KEYS) THEN
        RAISE EXCEPTION 'Activation lifecycle requires unique closed JSON object keys'
            USING ERRCODE = '23514';
    END IF;
    -- JSON preserves the number spelling that JSONB may normalize (for example 1e0).
    IF (convert_from(lifecycle_bytes, 'UTF8')::JSON->'request'->'schemaVersion')::TEXT
        IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'Activation lifecycle schemaVersion must be the integral JSON number 1'
            USING ERRCODE = '23514';
    END IF;
    lifecycle := convert_from(lifecycle_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(lifecycle) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Activation lifecycle must be a closed object'
            USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(lifecycle)) <> 12
        OR NOT (lifecycle ?& ARRAY[
            'schema', 'request', 'launchBindingBytesBase64', 'startLocation',
            'runtimeRoomInstanceId', 'lifecycleStatus', 'lifecycleEpoch', 'rowVersion',
            'captureId', 'graphSha256', 'preparationInputDigest', 'operationalRegionAssignments'])
        OR lifecycle->>'schema' IS DISTINCT FROM 'world-canonical-instance-lifecycle-evidence/v1'
        OR lifecycle->>'lifecycleStatus' IS DISTINCT FROM expected_status
        OR jsonb_typeof(lifecycle->'request') IS DISTINCT FROM 'object'
        OR jsonb_typeof(lifecycle->'startLocation') IS DISTINCT FROM 'object'
        OR jsonb_typeof(lifecycle->'operationalRegionAssignments') IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Activation lifecycle must be closed owner evidence for the expected status'
            USING ERRCODE = '23514';
    END IF;
    FOREACH field_name IN ARRAY ARRAY[
        'schema', 'launchBindingBytesBase64', 'runtimeRoomInstanceId', 'lifecycleStatus',
        'lifecycleEpoch', 'rowVersion', 'captureId', 'graphSha256', 'preparationInputDigest'] LOOP
        IF jsonb_typeof(lifecycle->field_name) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Activation lifecycle scalar must be a string: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    FOREACH field_name IN ARRAY ARRAY['runtimeRoomInstanceId', 'lifecycleEpoch', 'rowVersion'] LOOP
        IF lifecycle->>field_name !~ '^(0|[1-9][0-9]*)$'
            OR (lifecycle->>field_name)::NUMERIC > 9223372036854775807
            OR (field_name <> 'rowVersion' AND (lifecycle->>field_name)::NUMERIC = 0) THEN
            RAISE EXCEPTION 'Activation lifecycle counter is invalid: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF lifecycle->>'captureId' !~ uuid_pattern OR lifecycle->>'captureId' = nil_uuid
        OR lifecycle->>'graphSha256' !~ '^[0-9a-f]{64}$'
        OR lifecycle->>'preparationInputDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR length(lifecycle->>'launchBindingBytesBase64') = 0
        OR regexp_replace(encode(decode(lifecycle->>'launchBindingBytesBase64', 'base64'),
            'base64'), E'[\n\r]', '', 'g')
            IS DISTINCT FROM lifecycle->>'launchBindingBytesBase64' THEN
        RAISE EXCEPTION 'Activation lifecycle identities or retained launch bytes are invalid'
            USING ERRCODE = '23514';
    END IF;
    request_value := lifecycle->'request';
    IF (SELECT count(*) FROM jsonb_object_keys(request_value)) <> 14
        OR NOT (request_value ?& ARRAY[
            'schemaVersion', 'readRequestId', 'targetNamespace', 'canonicalTenantId', 'worldSlug',
            'canonicalGameInstanceId', 'playableStateNamespaceId', 'playableStateScope',
            'publicProduction', 'controlPlaneRequestId', 'canonicalVersionId',
            'expectedDescriptorRequestDigest', 'expectedDescriptorResultDigest',
            'expectedReleaseAttestationDigest'])
        OR jsonb_typeof(request_value->'schemaVersion') IS DISTINCT FROM 'number'
        OR request_value->>'schemaVersion' IS DISTINCT FROM '1'
        OR request_value->'publicProduction' IS DISTINCT FROM 'true'::JSONB
        OR request_value->>'playableStateScope' IS DISTINCT FROM 'SHARED' THEN
        RAISE EXCEPTION 'Activation lifecycle request must have the complete closed shape'
            USING ERRCODE = '23514';
    END IF;
    FOREACH field_name IN ARRAY ARRAY[
        'readRequestId', 'targetNamespace', 'canonicalTenantId', 'worldSlug',
        'canonicalGameInstanceId', 'playableStateNamespaceId', 'playableStateScope',
        'controlPlaneRequestId', 'canonicalVersionId', 'expectedDescriptorRequestDigest',
        'expectedDescriptorResultDigest', 'expectedReleaseAttestationDigest'] LOOP
        IF jsonb_typeof(request_value->field_name) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Activation lifecycle request scalar must be a string: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    FOREACH field_name IN ARRAY ARRAY[
        'readRequestId', 'canonicalTenantId', 'canonicalGameInstanceId',
        'playableStateNamespaceId', 'canonicalVersionId'] LOOP
        IF request_value->>field_name !~ uuid_pattern OR request_value->>field_name = nil_uuid THEN
            RAISE EXCEPTION 'Activation lifecycle request UUID is invalid: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    FOREACH field_name IN ARRAY ARRAY[
        'expectedDescriptorRequestDigest', 'expectedDescriptorResultDigest',
        'expectedReleaseAttestationDigest'] LOOP
        IF request_value->>field_name !~ '^sha256:[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'Activation lifecycle request digest is invalid: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF length(request_value->>'targetNamespace') NOT BETWEEN 1 AND 63
        OR request_value->>'targetNamespace' !~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        OR octet_length(request_value->>'worldSlug') NOT BETWEEN 1 AND 120
        OR request_value->>'worldSlug' !~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        OR cardinality(account_publication_authorization_source_sort_key(
            request_value->>'controlPlaneRequestId')) NOT BETWEEN 1 AND 128
        OR request_value->>'controlPlaneRequestId' ~ '^[[:space:]]*$' THEN
        RAISE EXCEPTION 'Activation lifecycle request text is invalid'
            USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(lifecycle->'startLocation')) <> 3
        OR NOT (lifecycle->'startLocation' ?& ARRAY['tenantId', 'versionId', 'roomTemplateId']) THEN
        RAISE EXCEPTION 'Activation lifecycle start location must be closed'
            USING ERRCODE = '23514';
    END IF;
    FOREACH field_name IN ARRAY ARRAY['tenantId', 'versionId', 'roomTemplateId'] LOOP
        IF jsonb_typeof(lifecycle->'startLocation'->field_name) IS DISTINCT FROM 'string'
            OR lifecycle->'startLocation'->>field_name !~ uuid_pattern
            OR lifecycle->'startLocation'->>field_name = nil_uuid THEN
            RAISE EXCEPTION 'Activation lifecycle start location UUID is invalid: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM jsonb_object_keys(lifecycle->'operationalRegionAssignments')) = 0
        OR (SELECT count(*) FROM jsonb_each(lifecycle->'operationalRegionAssignments'))
            <> (SELECT count(DISTINCT value) FROM jsonb_each(lifecycle->'operationalRegionAssignments')) THEN
        RAISE EXCEPTION 'Activation lifecycle requires distinct operational region assignments'
            USING ERRCODE = '23514';
    END IF;
    FOR region IN SELECT key, value FROM jsonb_each(lifecycle->'operationalRegionAssignments') LOOP
        IF region.key !~ uuid_pattern OR region.key = nil_uuid
            OR jsonb_typeof(region.value) IS DISTINCT FROM 'string'
            OR region.value #>> '{}' !~ uuid_pattern OR region.value #>> '{}' = nil_uuid
            OR region.key = region.value #>> '{}' THEN
            RAISE EXCEPTION 'Activation lifecycle region assignment is invalid'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN lifecycle;
EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire OR numeric_value_out_of_range
    OR invalid_parameter_value THEN
    RAISE EXCEPTION 'Activation lifecycle encoding or scalar is invalid'
        USING ERRCODE = '23514';
END;
$$;

CREATE OR REPLACE FUNCTION account_ss_activation_assert_current(
    request_bytes BYTEA,
    operation_bytes BYTEA,
    operation_digest TEXT,
    require_complete BOOLEAN
) RETURNS VOID LANGUAGE plpgsql VOLATILE STRICT AS $$
DECLARE
    capture account_start_session_authority_captures%ROWTYPE;
    world_parent account_start_session_world_participations%ROWTYPE;
    observation account_start_session_world_original_attempt_evidence%ROWTYPE;
    authorization_row account_start_session_operator_authorizations%ROWTYPE;
    retained account_start_session_activation_protections%ROWTYPE;
    snapshot_value JSONB;
    request_value JSONB;
    tuple_value JSONB;
    projection_value JSONB;
    preparing_value JSONB;
    preparing_request JSONB;
    source_value JSONB;
    source_bytes BYTEA;
    source_key_value TEXT;
    tuple_bytes BYTEA;
    projection_bytes BYTEA;
    preparing_bytes BYTEA;
    control_request_id TEXT;
    requested_participation_id UUID;
    keys TEXT[];
    retained_found BOOLEAN := FALSE;
BEGIN
    IF octet_length(request_bytes) NOT BETWEEN 1 AND 150994944
        OR octet_length(operation_bytes) NOT BETWEEN 1 AND 150994944
        OR operation_digest IS DISTINCT FROM 'sha256:' || encode(sha256(operation_bytes), 'hex') THEN
        RAISE EXCEPTION 'Original StartSession activation request or operation identity is invalid'
            USING ERRCODE = '23514';
    END IF;
    request_value := convert_from(request_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(request_value) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(request_value)) <> 12
        OR NOT (request_value ?& ARRAY[
            'schema', 'originalPostAuthorizationTupleBytesBase64',
            'accountRedemptionProjectionBytesBase64', 'gameSessionOwnerMutationId',
            'gameSessionOwnerAttemptId', 'gameSessionOwnerFence', 'originalLeaseExpiresAt',
            'accountWorldParticipationId', 'accountWorldParticipationFence',
            'gameSessionLaunchReadEvidenceBytesBase64', 'worldActivationRequestId',
            'preparingEvidenceBytesBase64'])
        OR EXISTS (SELECT 1 FROM jsonb_each(request_value) field WHERE jsonb_typeof(field.value) <> 'string')
        OR request_value->>'schema' IS DISTINCT FROM 'account-start-session-activation-protection-request/v1' THEN
        RAISE EXCEPTION 'Original StartSession activation request has an unsupported shape'
            USING ERRCODE = '23514';
    END IF;
    tuple_bytes := decode(request_value->>'originalPostAuthorizationTupleBytesBase64', 'base64');
    projection_bytes := decode(request_value->>'accountRedemptionProjectionBytesBase64', 'base64');
    preparing_bytes := decode(request_value->>'preparingEvidenceBytesBase64', 'base64');
    PERFORM account_ss_activation_validate_lifecycle(preparing_bytes, 'PREPARING');
    tuple_value := convert_from(tuple_bytes, 'UTF8')::JSONB;
    control_request_id := tuple_value->>'controlPlaneRequestId';
    requested_participation_id := (request_value->>'accountWorldParticipationId')::UUID;

    -- Source-key locks are acquired in the canonical V129 order before any retained owner,
    -- World or activation-protection row is locked.
    SELECT * INTO STRICT capture FROM account_start_session_authority_captures
        WHERE control_plane_request_id = control_request_id;
    snapshot_value := convert_from(capture.canonical_snapshot_bytes, 'UTF8')::JSONB;
    IF snapshot_value->>'schema' IS DISTINCT FROM 'account-start-session-authority-snapshot/v1'
        OR snapshot_value->>'controlPlaneRequestId' IS DISTINCT FROM control_request_id THEN
        RAISE EXCEPTION 'Activation protection requires the exact original Account source snapshot'
            USING ERRCODE = '23514';
    END IF;
    keys := account_start_session_world_participation_lock_sources(snapshot_value);
    PERFORM account_start_session_world_participation_assert_exclusive(keys, snapshot_value);

    SELECT * INTO STRICT capture FROM account_start_session_authority_captures
        WHERE control_plane_request_id = control_request_id FOR UPDATE NOWAIT;
    SELECT * INTO STRICT world_parent FROM account_start_session_world_participations
        WHERE account_start_session_world_participations.participation_id = requested_participation_id
        FOR UPDATE NOWAIT;
    SELECT * INTO STRICT observation FROM account_start_session_world_original_attempt_evidence
        WHERE participation_id = world_parent.participation_id FOR UPDATE NOWAIT;
    SELECT * INTO STRICT authorization_row FROM account_start_session_operator_authorizations
        WHERE control_plane_request_id = control_request_id FOR UPDATE NOWAIT;
    SELECT * INTO retained FROM account_start_session_activation_protections
        WHERE account_start_session_activation_protections.control_plane_request_id = control_request_id
        FOR UPDATE NOWAIT;
    retained_found := FOUND;

    preparing_value := convert_from(preparing_bytes, 'UTF8')::JSONB;
    preparing_request := preparing_value->'request';
    projection_value := convert_from(projection_bytes, 'UTF8')::JSONB;
    IF request_value->>'gameSessionOwnerMutationId' IS DISTINCT FROM observation.game_session_owner_mutation_id::TEXT
        OR request_value->>'gameSessionOwnerAttemptId' IS DISTINCT FROM observation.game_session_owner_attempt_id::TEXT
        OR request_value->>'gameSessionOwnerFence' IS DISTINCT FROM observation.game_session_owner_fence::TEXT
        OR request_value->>'originalLeaseExpiresAt' IS DISTINCT FROM
            to_char(observation.original_lease_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
        OR request_value->>'accountWorldParticipationId' IS DISTINCT FROM world_parent.participation_id::TEXT
        OR request_value->>'accountWorldParticipationFence' IS DISTINCT FROM world_parent.participation_fence::TEXT
        OR world_parent.control_plane_request_id IS DISTINCT FROM control_request_id
        OR world_parent.original_post_authorization_tuple IS DISTINCT FROM tuple_bytes
        OR world_parent.game_session_owner_attempt_id IS DISTINCT FROM observation.game_session_owner_attempt_id
        OR world_parent.game_session_owner_fence IS DISTINCT FROM observation.game_session_owner_fence
        OR observation.target_namespace IS DISTINCT FROM world_parent.target_namespace
        OR NOT EXISTS (
            SELECT 1 FROM account_start_session_world_participation_settlements settlement
            WHERE settlement.participation_id = world_parent.participation_id
                AND settlement.outcome = 'COMMITTED')
        OR preparing_value->>'schema' IS DISTINCT FROM 'world-canonical-instance-lifecycle-evidence/v1'
        OR preparing_value->>'lifecycleStatus' IS DISTINCT FROM 'PREPARING'
        OR preparing_request->>'controlPlaneRequestId' IS DISTINCT FROM control_request_id
        OR preparing_request->>'targetNamespace' IS DISTINCT FROM world_parent.target_namespace
        OR preparing_request->>'canonicalTenantId' IS DISTINCT FROM world_parent.canonical_tenant_id::TEXT
        OR preparing_request->>'canonicalGameInstanceId' IS DISTINCT FROM world_parent.canonical_game_instance_id::TEXT
        OR capture.source_version <= 0 OR capture.source_fence <= 0 THEN
        RAISE EXCEPTION 'Activation protection differs from committed original World preparation or attempt'
            USING ERRCODE = '23514';
    END IF;

    -- Retain the original Account issuance/redemption tuple and complete projection exactly.
    IF capture.canonical_sha256 IS DISTINCT FROM encode(sha256(capture.canonical_capture_bytes), 'hex')
        OR capture.snapshot_sha256 IS DISTINCT FROM encode(sha256(capture.canonical_snapshot_bytes), 'hex')
        OR capture.pre_authorization_tuple IS DISTINCT FROM authorization_row.pre_authorization_tuple
        OR capture.issuance_fence IS DISTINCT FROM authorization_row.issuance_fence
        OR authorization_row.status IS DISTINCT FROM 'REDEEMED'
        OR authorization_row.redemption_owner_attempt_id IS DISTINCT FROM observation.game_session_owner_attempt_id
        OR authorization_row.redemption_owner_fence IS DISTINCT FROM observation.game_session_owner_fence
        OR authorization_row.redemption_reference_fingerprint IS DISTINCT FROM authorization_row.authorization_reference_fingerprint
        OR authorization_row.redemption_authority_evidence_bundle IS DISTINCT FROM authorization_row.authority_evidence_bundle
        OR tuple_value->'preAuthorizationReservationTuple' IS DISTINCT FROM convert_from(authorization_row.pre_authorization_tuple, 'UTF8')::JSONB
        OR tuple_value->>'authorizationReferenceFingerprint' IS DISTINCT FROM authorization_row.authorization_reference_fingerprint
        OR tuple_value->>'issuanceFence' IS DISTINCT FROM authorization_row.issuance_fence::TEXT
        OR tuple_value->'authorityEvidenceBundle' IS DISTINCT FROM convert_from(authorization_row.authority_evidence_bundle, 'UTF8')::JSONB
        OR projection_bytes IS DISTINCT FROM tuple_bytes
        OR projection_value #>> '{authorityEvidenceBundle,issuanceOperationIdentity,issuanceOperationId}'
            IS DISTINCT FROM authorization_row.issuance_operation_id::TEXT
        OR capture.canonical_sha256 IS DISTINCT FROM encode(sha256(capture.canonical_capture_bytes), 'hex') THEN
        RAISE EXCEPTION 'Activation protection differs from original Account redemption or capture'
            USING ERRCODE = '23514';
    END IF;

    IF retained_found AND (
        retained.operation_identity_bytes IS DISTINCT FROM operation_bytes
        OR retained.operation_identity_digest IS DISTINCT FROM operation_digest
        OR retained.account_world_participation_id IS DISTINCT FROM world_parent.participation_id
        OR retained.account_world_participation_fence IS DISTINCT FROM world_parent.participation_fence) THEN
        RAISE EXCEPTION 'Original StartSession activation protection conflicts with its immutable binding'
            USING ERRCODE = '23514';
    END IF;

    -- The typed Common carrier validates the closed Game Session STARTING result and exact
    -- World PREPARING evidence. SQL independently checks finite original authority only after
    -- all canonical source and owner/protection row locks have completed.
    IF observation.original_lease_expires_at IS DISTINCT FROM
            (request_value->>'originalLeaseExpiresAt')::TIMESTAMPTZ
        OR observation.original_lease_expires_at <= clock_timestamp()
        OR authorization_row.reference_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'Original StartSession activation protection lease is stale or expired'
            USING ERRCODE = '23514';
    END IF;

    PERFORM account_start_session_world_participation_assert_sources(world_parent.participation_id, snapshot_value);
    IF require_complete THEN
        IF (SELECT count(*) FROM account_start_session_activation_protection_sources
            WHERE activation_protection_id = retained.activation_protection_id)
            <> jsonb_array_length(snapshot_value->'sourceVector') THEN
            RAISE EXCEPTION 'Activation protection has incomplete or extra source children'
                USING ERRCODE = '23514';
        END IF;
        FOR source_value IN SELECT value FROM jsonb_array_elements(snapshot_value->'sourceVector') LOOP
            source_bytes := decode(source_value #>> '{}', 'base64');
            source_key_value := account_start_session_world_participation_source_key(source_bytes);
            IF NOT EXISTS (SELECT 1 FROM account_start_session_activation_protection_sources
                WHERE activation_protection_id = retained.activation_protection_id
                    AND source_key = source_key_value AND source_evidence = source_bytes) THEN
                RAISE EXCEPTION 'Activation protection source child differs from original capture'
                    USING ERRCODE = '23514';
            END IF;
        END LOOP;
    END IF;
END;
$$;

CREATE FUNCTION account_ss_activation_settlement_lifecycle_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_start_session_activation_protections%ROWTYPE;
    result_value JSONB;
    lifecycle_base64 TEXT;
    lifecycle_bytes BYTEA;
    lifecycle JSONB;
    preparing JSONB;
BEGIN
    IF NEW.outcome <> 'COMMITTED' THEN
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT original FROM account_start_session_activation_protections
        WHERE activation_protection_id = NEW.activation_protection_id;
    result_value := convert_from(decode(
        convert_from(NEW.terminal_bytes, 'UTF8')::JSONB->>'activationResultBytesBase64',
        'base64'), 'UTF8')::JSONB;
    IF jsonb_typeof(result_value->'lifecycleEvidenceBytesBase64') IS DISTINCT FROM 'string' THEN
        RAISE EXCEPTION 'Committed activation settlement requires ACTIVE lifecycle evidence'
            USING ERRCODE = '23514';
    END IF;
    lifecycle_base64 := result_value->>'lifecycleEvidenceBytesBase64';
    lifecycle_bytes := decode(lifecycle_base64, 'base64');
    IF regexp_replace(encode(lifecycle_bytes, 'base64'), E'[\n\r]', '', 'g')
        IS DISTINCT FROM lifecycle_base64 THEN
        RAISE EXCEPTION 'Committed activation lifecycle Base64 must be canonical'
            USING ERRCODE = '23514';
    END IF;
    lifecycle := account_ss_activation_validate_lifecycle(lifecycle_bytes, 'ACTIVE');
    preparing := convert_from(original.world_preparing_evidence, 'UTF8')::JSONB;
    IF preparing->>'lifecycleStatus' IS DISTINCT FROM 'PREPARING' THEN
        RAISE EXCEPTION 'Committed activation requires retained PREPARING owner evidence'
            USING ERRCODE = '23514';
    END IF;
    -- Only status, counters and the read observation identity may change during activation.
    IF lifecycle - ARRAY['request', 'lifecycleStatus', 'lifecycleEpoch', 'rowVersion']
            IS DISTINCT FROM preparing - ARRAY['request', 'lifecycleStatus', 'lifecycleEpoch', 'rowVersion']
        OR (lifecycle->'request') - 'readRequestId'
            IS DISTINCT FROM (preparing->'request') - 'readRequestId' THEN
        RAISE EXCEPTION 'Committed activation lifecycle differs from retained PREPARING binding'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire OR numeric_value_out_of_range
    OR invalid_parameter_value THEN
    RAISE EXCEPTION 'Committed activation lifecycle encoding or scalar is invalid'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_ss_activation_settlement_validate_lifecycle
    BEFORE INSERT ON account_start_session_activation_protection_settlements
    FOR EACH ROW EXECUTE FUNCTION account_ss_activation_settlement_lifecycle_guard();
-- [jooq ignore end]
