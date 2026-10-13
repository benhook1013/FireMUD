-- Additive receiving Account admission and activation source protections after the canonical
-- capture (V158), World participation (V159), original attempt (V160), and independent source
-- fence (V160.1) owners. No predecessor, retained row, checksum, or owner identity is rewritten.
-- Complete original tuple projection and the independent nested issuance-operation identity
-- are installed directly; this migration never installs the superseded scalar projection guard.
-- These storage/integrity boundaries do not register runtime authority or authenticate producers.

-- Distinct Account source protection through original Game Session admission. World
-- settlement and original-attempt observation expiry never settle this participation.
-- These integrity guards do not authenticate a producer or Game Session terminal RPC.
CREATE TABLE account_start_session_admission_protections (
    protection_id UUID PRIMARY KEY CHECK (protection_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    protection_fence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE CHECK (protection_fence > 0),
    control_plane_request_id VARCHAR(128) NOT NULL UNIQUE
        REFERENCES account_start_session_authority_captures(control_plane_request_id) ON DELETE RESTRICT,
    original_post_authorization_tuple BYTEA NOT NULL CHECK (octet_length(original_post_authorization_tuple) BETWEEN 1 AND 262144),
    account_redemption_projection BYTEA NOT NULL CHECK (octet_length(account_redemption_projection) BETWEEN 1 AND 262144),
    game_session_owner_mutation_id UUID NOT NULL UNIQUE CHECK (game_session_owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_attempt_id UUID NOT NULL CHECK (game_session_owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_fence BIGINT NOT NULL CHECK (game_session_owner_fence > 0),
    original_lease_expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(original_lease_expires_at)),
    account_world_participation_id UUID NOT NULL UNIQUE
        REFERENCES account_start_session_world_participations(participation_id) ON DELETE RESTRICT,
    account_world_participation_fence BIGINT NOT NULL CHECK (account_world_participation_fence > 0),
    target_namespace VARCHAR(63) NOT NULL CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    canonical_tenant_id UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id) ON DELETE RESTRICT,
    canonical_game_instance_id UUID NOT NULL CHECK (canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    capture_source_version BIGINT NOT NULL CHECK (capture_source_version > 0),
    capture_source_fence BIGINT NOT NULL CHECK (capture_source_fence > 0),
    capture_sha256 VARCHAR(64) NOT NULL CHECK (capture_sha256 ~ '^[0-9a-f]{64}$'),
    world_admission_hold_identity_bytes BYTEA NOT NULL CHECK (octet_length(world_admission_hold_identity_bytes) BETWEEN 1 AND 65536),
    request_binding_bytes BYTEA NOT NULL CHECK (octet_length(request_binding_bytes) BETWEEN 1 AND 786432),
    request_binding_digest VARCHAR(71) NOT NULL
        CHECK (request_binding_digest = 'sha256:' || encode(sha256(request_binding_bytes), 'hex')),
    producer_xid BIGINT NOT NULL DEFAULT txid_current(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE account_start_session_admission_protection_sources (
    protection_id UUID NOT NULL REFERENCES account_start_session_admission_protections(protection_id) ON DELETE RESTRICT,
    source_key VARCHAR(2048) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 131072),
    PRIMARY KEY (protection_id, source_key)
);
CREATE INDEX account_ss_admission_sources_by_key
    ON account_start_session_admission_protection_sources(source_key, protection_id);

CREATE TABLE account_start_session_admission_protection_settlements (
    protection_id UUID PRIMARY KEY REFERENCES account_start_session_admission_protections(protection_id) ON DELETE RESTRICT,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('COMMITTED', 'ABORTED')),
    terminal_bytes BYTEA NOT NULL CHECK (octet_length(terminal_bytes) BETWEEN 1 AND 25165824),
    terminal_digest VARCHAR(71) NOT NULL CHECK (terminal_digest = 'sha256:' || encode(sha256(terminal_bytes), 'hex')),
    settled_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- [jooq ignore start]
CREATE FUNCTION account_ss_admission_is_settled(protection_value UUID)
RETURNS BOOLEAN LANGUAGE SQL STABLE STRICT AS $$
    SELECT EXISTS (SELECT 1 FROM account_start_session_admission_protection_settlements
        WHERE protection_id = protection_value)
$$;

-- Called only after the owning writer has locked its canonical source-key set. This
-- shared addition preserves the independently assembled World and other owner guards.
CREATE FUNCTION account_ss_admission_assert_no_pending(source_keys TEXT[])
RETURNS VOID LANGUAGE plpgsql VOLATILE STRICT AS $$
BEGIN
    PERFORM protection.protection_id
        FROM account_start_session_admission_protections protection
        JOIN account_start_session_admission_protection_sources source
            ON source.protection_id = protection.protection_id
        WHERE source.source_key = ANY(source_keys)
            AND NOT account_ss_admission_is_settled(protection.protection_id)
        ORDER BY protection.protection_id FOR UPDATE OF protection NOWAIT;
    IF FOUND THEN
        RAISE EXCEPTION 'Original StartSession admission protection remains pending for a required source'
            USING ERRCODE = '55P03';
    END IF;
END;
$$;

-- Acquisition and settlement require the same exact World hold before a source
-- protection can become pending. Return the only valid committed next pointer.
CREATE FUNCTION account_ss_admission_canonical_hold_next_pointer(
    original account_start_session_admission_protections
) RETURNS NUMERIC LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    hold_json JSONB;
    hold_request_json JSONB;
    canonical_hold TEXT;
    canonical_hold_request TEXT;
    prior_pointer NUMERIC;
BEGIN
    hold_json := convert_from(original.world_admission_hold_identity_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(hold_json) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(hold_json)) <> 5
        OR NOT (hold_json ?& ARRAY['schema', 'holdId', 'holdFence', 'holdBindingDigest', 'requestBytesBase64'])
        OR EXISTS (SELECT 1 FROM jsonb_each(hold_json) field
            WHERE jsonb_typeof(field.value) IS DISTINCT FROM 'string')
        OR hold_json->>'schema' IS DISTINCT FROM 'world-canonical-initial-admission-hold-identity/v1' THEN
        RAISE EXCEPTION 'Original StartSession admission hold identity is malformed'
            USING ERRCODE = '23514';
    END IF;
    IF (hold_json->>'holdId')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
        OR (hold_json->>'holdFence')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
        OR hold_json->>'holdId' IS DISTINCT FROM (hold_json->>'holdId')::UUID::TEXT
        OR hold_json->>'holdFence' IS DISTINCT FROM (hold_json->>'holdFence')::UUID::TEXT THEN
        RAISE EXCEPTION 'Original StartSession admission hold identity is nil'
            USING ERRCODE = '23514';
    END IF;
    hold_request_json := convert_from(decode(hold_json->>'requestBytesBase64', 'base64'), 'UTF8')::JSONB;
    IF regexp_replace(encode(decode(hold_json->>'requestBytesBase64', 'base64'), 'base64'), E'[\n\r]', '', 'g')
            IS DISTINCT FROM hold_json->>'requestBytesBase64'
        OR hold_json->>'holdBindingDigest' IS DISTINCT FROM
            'sha256:' || encode(sha256(decode(hold_json->>'requestBytesBase64', 'base64')), 'hex')
        OR jsonb_typeof(hold_request_json) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(hold_request_json)) <> 2
        OR NOT (hold_request_json ?& ARRAY['schema', 'request'])
        OR hold_request_json->>'schema' IS DISTINCT FROM 'world-canonical-initial-admission-hold-request/v1'
        OR jsonb_typeof(hold_request_json->'request') IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(hold_request_json->'request')) <> 14
        OR NOT (hold_request_json->'request' ?& ARRAY[
            'activeLifecycleEpoch', 'canonicalGameInstanceId', 'canonicalTenantId',
            'canonicalVersionId', 'expectedCatalogRevision', 'expectedPriorPointerVersion',
            'initialAdmissionOrigin', 'initialAdmissionRequestDigest',
            'initialAdmissionRequestId', 'playableStateNamespaceId', 'playableStateScope',
            'realmId', 'targetNamespace', 'worldSlug'])
        OR hold_request_json->'request'->>'targetNamespace' IS DISTINCT FROM original.target_namespace
        OR hold_request_json->'request'->>'canonicalTenantId' IS DISTINCT FROM original.canonical_tenant_id::TEXT
        OR hold_request_json->'request'->>'canonicalGameInstanceId' IS DISTINCT FROM original.canonical_game_instance_id::TEXT THEN
        RAISE EXCEPTION 'Original StartSession admission hold request differs from retained protection'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM jsonb_each(hold_request_json->'request') field
        WHERE field.key <> 'expectedPriorPointerVersion'
            AND jsonb_typeof(field.value) IS DISTINCT FROM 'string')
        OR jsonb_typeof(hold_request_json->'request'->'expectedPriorPointerVersion') NOT IN ('string', 'null') THEN
        RAISE EXCEPTION 'Original StartSession hold request fields have invalid JSON types'
            USING ERRCODE = '23514';
    END IF;
    canonical_hold_request := '{"request":{"activeLifecycleEpoch":'
        || to_json(hold_request_json->'request'->>'activeLifecycleEpoch')::TEXT
        || ',"canonicalGameInstanceId":'
        || to_json(hold_request_json->'request'->>'canonicalGameInstanceId')::TEXT
        || ',"canonicalTenantId":'
        || to_json(hold_request_json->'request'->>'canonicalTenantId')::TEXT
        || ',"canonicalVersionId":'
        || to_json(hold_request_json->'request'->>'canonicalVersionId')::TEXT
        || ',"expectedCatalogRevision":'
        || to_json(hold_request_json->'request'->>'expectedCatalogRevision')::TEXT
        || ',"expectedPriorPointerVersion":'
        || CASE
            WHEN jsonb_typeof(hold_request_json->'request'->'expectedPriorPointerVersion') = 'null'
                THEN 'null'
            ELSE to_json(hold_request_json->'request'->>'expectedPriorPointerVersion')::TEXT
           END
        || ',"initialAdmissionOrigin":'
        || to_json(hold_request_json->'request'->>'initialAdmissionOrigin')::TEXT
        || ',"initialAdmissionRequestDigest":'
        || to_json(hold_request_json->'request'->>'initialAdmissionRequestDigest')::TEXT
        || ',"initialAdmissionRequestId":'
        || to_json(hold_request_json->'request'->>'initialAdmissionRequestId')::TEXT
        || ',"playableStateNamespaceId":'
        || to_json(hold_request_json->'request'->>'playableStateNamespaceId')::TEXT
        || ',"playableStateScope":'
        || to_json(hold_request_json->'request'->>'playableStateScope')::TEXT
        || ',"realmId":'
        || to_json(hold_request_json->'request'->>'realmId')::TEXT
        || ',"targetNamespace":'
        || to_json(hold_request_json->'request'->>'targetNamespace')::TEXT
        || ',"worldSlug":'
        || to_json(hold_request_json->'request'->>'worldSlug')::TEXT
        || '},"schema":"world-canonical-initial-admission-hold-request/v1"}';
    IF convert_to(canonical_hold_request, 'UTF8') IS DISTINCT FROM
        decode(hold_json->>'requestBytesBase64', 'base64') THEN
        RAISE EXCEPTION 'Original StartSession hold request is not canonical JSON'
            USING ERRCODE = '23514';
    END IF;
    canonical_hold := '{"holdBindingDigest":"' || (hold_json->>'holdBindingDigest')
        || '","holdFence":"' || (hold_json->>'holdFence')
        || '","holdId":"' || (hold_json->>'holdId')
        || '","requestBytesBase64":"' || (hold_json->>'requestBytesBase64')
        || '","schema":"world-canonical-initial-admission-hold-identity/v1"}';
    IF convert_to(canonical_hold, 'UTF8') IS DISTINCT FROM original.world_admission_hold_identity_bytes THEN
        RAISE EXCEPTION 'Original StartSession admission hold identity is not canonical'
            USING ERRCODE = '23514';
    END IF;

    IF hold_request_json->'request'->>'initialAdmissionOrigin' = 'NO_PRIOR_POINTER'
        AND hold_request_json->'request'->'expectedPriorPointerVersion' = 'null'::JSONB THEN
        RETURN 1;
    ELSIF hold_request_json->'request'->>'initialAdmissionOrigin' = 'EXPECT_CLOSED'
        AND jsonb_typeof(hold_request_json->'request'->'expectedPriorPointerVersion') = 'string'
        AND hold_request_json->'request'->>'expectedPriorPointerVersion' ~ '^[1-9][0-9]{0,18}$' THEN
        prior_pointer := (hold_request_json->'request'->>'expectedPriorPointerVersion')::NUMERIC;
        IF prior_pointer >= 9223372036854775807 THEN
            RAISE EXCEPTION 'Original StartSession pointer version cannot advance without overflow'
                USING ERRCODE = '23514';
        END IF;
        RETURN prior_pointer + 1;
    ELSE
        RAISE EXCEPTION 'Original StartSession hold has an invalid pointer precondition'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

-- Account currentness must independently reconstruct the exact live original actor,
-- registry, signer and source capture before invoking this integrity boundary. SQL
-- validates retained bindings; neither a capture nor a historical observation grants
-- admission, and a gap requires the producer's fresh unchanged-source validation.
CREATE FUNCTION account_ss_admission_assert_current(
    original account_start_session_admission_protections,
    require_complete BOOLEAN
) RETURNS VOID LANGUAGE plpgsql VOLATILE STRICT AS $$
DECLARE
    capture account_start_session_authority_captures%ROWTYPE;
    world_parent account_start_session_world_participations%ROWTYPE;
    observation account_start_session_world_original_attempt_evidence%ROWTYPE;
    authorization_row account_start_session_operator_authorizations%ROWTYPE;
    snapshot_value JSONB;
    tuple_value JSONB;
    request_value JSONB;
    projection_value JSONB;
    source_value JSONB;
    source_bytes BYTEA;
    source_key_value TEXT;
    keys TEXT[];
    previous_sort_key INTEGER[];
    current_sort_key INTEGER[];
    source_bytes_total BIGINT := 0;
    canonical_request TEXT;
BEGIN
    SELECT * INTO STRICT capture FROM account_start_session_authority_captures
        WHERE control_plane_request_id = original.control_plane_request_id;
    snapshot_value := convert_from(capture.canonical_snapshot_bytes, 'UTF8')::JSONB;
    IF snapshot_value->>'schema' IS DISTINCT FROM 'account-start-session-authority-snapshot/v1'
        OR snapshot_value->>'controlPlaneRequestId' IS DISTINCT FROM original.control_plane_request_id THEN
        RAISE EXCEPTION 'Admission protection requires the exact original source snapshot' USING ERRCODE = '23514';
    END IF;
    IF octet_length(capture.canonical_capture_bytes) NOT BETWEEN 1 AND 262144
        OR octet_length(capture.canonical_snapshot_bytes) NOT BETWEEN 1 AND 245760
        OR jsonb_typeof(snapshot_value->'sourceVector') IS DISTINCT FROM 'array'
        OR jsonb_array_length(snapshot_value->'sourceVector') NOT BETWEEN 1 AND 256 THEN
        RAISE EXCEPTION 'Admission protection source capture exceeds settlement bounds' USING ERRCODE = '23514';
    END IF;
    FOR source_value IN SELECT value FROM jsonb_array_elements(snapshot_value->'sourceVector') LOOP
        IF jsonb_typeof(source_value) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Admission protection source vector has a non-string member' USING ERRCODE = '23514';
        END IF;
        source_bytes := decode(source_value #>> '{}', 'base64');
        source_bytes_total := source_bytes_total + octet_length(source_bytes);
        IF octet_length(source_bytes) NOT BETWEEN 1 AND 131072
            OR source_bytes_total > 4194304
            OR regexp_replace(encode(source_bytes, 'base64'), E'[\n\r]', '', 'g')
                IS DISTINCT FROM source_value #>> '{}' THEN
            RAISE EXCEPTION 'Admission protection source vector exceeds canonical settlement bounds' USING ERRCODE = '23514';
        END IF;
        current_sort_key := account_publication_authorization_source_sort_key(
            account_start_session_world_participation_source_key(source_bytes));
        IF previous_sort_key IS NOT NULL AND current_sort_key <= previous_sort_key THEN
            RAISE EXCEPTION 'Admission protection source vector is duplicated or out of order' USING ERRCODE = '23514';
        END IF;
        previous_sort_key := current_sort_key;
    END LOOP;
    keys := account_start_session_world_participation_lock_sources(snapshot_value);
    PERFORM account_start_session_world_participation_assert_exclusive(keys, snapshot_value);
    SELECT * INTO STRICT capture FROM account_start_session_authority_captures
        WHERE control_plane_request_id = original.control_plane_request_id FOR UPDATE NOWAIT;
    SELECT * INTO STRICT world_parent FROM account_start_session_world_participations
        WHERE participation_id = original.account_world_participation_id FOR UPDATE NOWAIT;
    SELECT * INTO STRICT observation FROM account_start_session_world_original_attempt_evidence
        WHERE participation_id = original.account_world_participation_id FOR UPDATE NOWAIT;
    SELECT * INTO STRICT authorization_row FROM account_start_session_operator_authorizations
        WHERE control_plane_request_id = original.control_plane_request_id FOR UPDATE NOWAIT;
    PERFORM protection_id FROM account_start_session_admission_protections
        WHERE protection_id = original.protection_id FOR UPDATE NOWAIT;
    -- Always check actual database time after every lock, including exact recovery.
    IF original.original_lease_expires_at <= clock_timestamp()
        OR authorization_row.reference_expires_at <= clock_timestamp()
        OR account_ss_admission_is_settled(original.protection_id) THEN
        RAISE EXCEPTION 'Original StartSession admission protection is not current'
            USING ERRCODE = '23514';
    END IF;
    IF capture.source_version IS DISTINCT FROM original.capture_source_version
        OR capture.source_fence IS DISTINCT FROM original.capture_source_fence
        OR capture.canonical_sha256 IS DISTINCT FROM original.capture_sha256
        OR world_parent.participation_fence IS DISTINCT FROM original.account_world_participation_fence
        OR world_parent.control_plane_request_id IS DISTINCT FROM original.control_plane_request_id
        OR world_parent.original_post_authorization_tuple IS DISTINCT FROM original.original_post_authorization_tuple
        OR world_parent.game_session_owner_attempt_id IS DISTINCT FROM original.game_session_owner_attempt_id
        OR world_parent.game_session_owner_fence IS DISTINCT FROM original.game_session_owner_fence
        OR world_parent.target_namespace IS DISTINCT FROM original.target_namespace
        OR world_parent.canonical_tenant_id IS DISTINCT FROM original.canonical_tenant_id
        OR world_parent.canonical_game_instance_id IS DISTINCT FROM original.canonical_game_instance_id
        OR observation.target_namespace IS DISTINCT FROM original.target_namespace
        OR observation.game_session_owner_mutation_id IS DISTINCT FROM original.game_session_owner_mutation_id
        OR observation.game_session_owner_attempt_id IS DISTINCT FROM original.game_session_owner_attempt_id
        OR observation.game_session_owner_fence IS DISTINCT FROM original.game_session_owner_fence
        OR observation.original_lease_expires_at IS DISTINCT FROM original.original_lease_expires_at
        OR authorization_row.status IS DISTINCT FROM 'REDEEMED'
        OR authorization_row.redemption_owner_attempt_id IS DISTINCT FROM original.game_session_owner_attempt_id
        OR authorization_row.redemption_owner_fence IS DISTINCT FROM original.game_session_owner_fence
        OR authorization_row.redemption_reference_fingerprint IS DISTINCT FROM authorization_row.authorization_reference_fingerprint
        OR authorization_row.redemption_authority_evidence_bundle IS DISTINCT FROM authorization_row.authority_evidence_bundle
        OR capture.pre_authorization_tuple IS DISTINCT FROM authorization_row.pre_authorization_tuple
        OR capture.issuance_fence IS DISTINCT FROM authorization_row.issuance_fence THEN
        RAISE EXCEPTION 'Original StartSession admission protection differs from its retained owner bindings'
            USING ERRCODE = '23514';
    END IF;
    tuple_value := convert_from(original.original_post_authorization_tuple, 'UTF8')::JSONB;
    IF tuple_value->'preAuthorizationReservationTuple' IS DISTINCT FROM convert_from(authorization_row.pre_authorization_tuple, 'UTF8')::JSONB
        OR tuple_value->>'authorizationReferenceFingerprint' IS DISTINCT FROM authorization_row.authorization_reference_fingerprint
        OR tuple_value->>'issuanceFence' IS DISTINCT FROM authorization_row.issuance_fence::TEXT
        OR tuple_value->'authorityEvidenceBundle' IS DISTINCT FROM convert_from(authorization_row.authority_evidence_bundle, 'UTF8')::JSONB THEN
        RAISE EXCEPTION 'Admission tuple differs from original Account issuance' USING ERRCODE = '23514';
    END IF;
    projection_value := convert_from(original.account_redemption_projection, 'UTF8')::JSONB;
    IF original.account_redemption_projection IS DISTINCT FROM original.original_post_authorization_tuple
        OR projection_value #>> '{authorityEvidenceBundle,issuanceOperationIdentity,issuanceOperationId}'
            IS DISTINCT FROM authorization_row.issuance_operation_id::TEXT THEN
        RAISE EXCEPTION 'Admission projection differs from complete original Account redemption' USING ERRCODE = '23514';
    END IF;
    request_value := convert_from(original.request_binding_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(request_value) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Admission request binding is not an object' USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(request_value)) <> 10
        OR EXISTS (SELECT 1 FROM jsonb_each(request_value) field WHERE jsonb_typeof(field.value) <> 'string')
        OR request_value->>'schema' IS DISTINCT FROM 'account-start-session-admission-protection-request/v1'
        OR request_value->>'originalPostAuthorizationTupleBytesBase64' IS DISTINCT FROM regexp_replace(encode(original.original_post_authorization_tuple, 'base64'), E'[\n\r]', '', 'g')
        OR request_value->>'accountRedemptionProjectionBytesBase64' IS DISTINCT FROM regexp_replace(encode(original.account_redemption_projection, 'base64'), E'[\n\r]', '', 'g')
        OR request_value->>'gameSessionOwnerMutationId' IS DISTINCT FROM original.game_session_owner_mutation_id::TEXT
        OR request_value->>'gameSessionOwnerAttemptId' IS DISTINCT FROM original.game_session_owner_attempt_id::TEXT
        OR request_value->>'gameSessionOwnerFence' IS DISTINCT FROM original.game_session_owner_fence::TEXT
        OR request_value->>'originalLeaseExpiresAt' IS DISTINCT FROM
            to_char(original.original_lease_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
        OR request_value->>'accountWorldParticipationId' IS DISTINCT FROM original.account_world_participation_id::TEXT
        OR request_value->>'accountWorldParticipationFence' IS DISTINCT FROM original.account_world_participation_fence::TEXT
        OR request_value->>'worldAdmissionHoldIdentityBytesBase64' IS DISTINCT FROM regexp_replace(encode(original.world_admission_hold_identity_bytes, 'base64'), E'[\n\r]', '', 'g') THEN
        RAISE EXCEPTION 'Admission request binding differs from exact immutable selectors' USING ERRCODE = '23514';
    END IF;
    canonical_request := '{"accountRedemptionProjectionBytesBase64":"'
        || regexp_replace(encode(original.account_redemption_projection, 'base64'), E'[\n\r]', '', 'g')
        || '","accountWorldParticipationFence":"'
        || original.account_world_participation_fence::TEXT
        || '","accountWorldParticipationId":"'
        || original.account_world_participation_id::TEXT
        || '","gameSessionOwnerAttemptId":"'
        || original.game_session_owner_attempt_id::TEXT
        || '","gameSessionOwnerFence":"'
        || original.game_session_owner_fence::TEXT
        || '","gameSessionOwnerMutationId":"'
        || original.game_session_owner_mutation_id::TEXT
        || '","originalLeaseExpiresAt":"'
        || to_char(original.original_lease_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
        || '","originalPostAuthorizationTupleBytesBase64":"'
        || regexp_replace(encode(original.original_post_authorization_tuple, 'base64'), E'[\n\r]', '', 'g')
        || '","schema":"account-start-session-admission-protection-request/v1"'
        || ',"worldAdmissionHoldIdentityBytesBase64":"'
        || regexp_replace(encode(original.world_admission_hold_identity_bytes, 'base64'), E'[\n\r]', '', 'g')
        || '"}';
    IF octet_length(original.request_binding_bytes) NOT BETWEEN 1 AND 786432
        OR octet_length(original.world_admission_hold_identity_bytes) NOT BETWEEN 1 AND 65536
        OR convert_to(canonical_request, 'UTF8') IS DISTINCT FROM original.request_binding_bytes
        OR original.request_binding_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(original.request_binding_bytes), 'hex') THEN
        RAISE EXCEPTION 'Original StartSession admission request is not its exact canonical binding'
            USING ERRCODE = '23514';
    END IF;

    PERFORM account_ss_admission_canonical_hold_next_pointer(original);
    -- Original World source children remain immutable even when World has settled.
    PERFORM account_start_session_world_participation_assert_sources(world_parent.participation_id, snapshot_value);
    IF require_complete THEN
        IF (SELECT count(*) FROM account_start_session_admission_protection_sources
            WHERE protection_id = original.protection_id) <> jsonb_array_length(snapshot_value->'sourceVector') THEN
            RAISE EXCEPTION 'Admission protection has incomplete or extra source children' USING ERRCODE = '23514';
        END IF;
        FOR source_value IN SELECT value FROM jsonb_array_elements(snapshot_value->'sourceVector') LOOP
            source_bytes := decode(source_value #>> '{}', 'base64');
            source_key_value := account_start_session_world_participation_source_key(source_bytes);
            IF NOT EXISTS (SELECT 1 FROM account_start_session_admission_protection_sources
                WHERE protection_id = original.protection_id AND source_key = source_key_value
                    AND source_evidence = source_bytes) THEN
                RAISE EXCEPTION 'Admission source child differs from complete original capture' USING ERRCODE = '23514';
            END IF;
        END LOOP;
    END IF;
    IF original.original_lease_expires_at <= clock_timestamp()
        OR authorization_row.reference_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'Original StartSession admission expiry elapsed during integrity validation' USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION account_ss_admission_read_current_exact(protection_value UUID, fence_value BIGINT, request_value BYTEA)
RETURNS SETOF account_start_session_admission_protections LANGUAGE plpgsql VOLATILE STRICT AS $$
DECLARE original account_start_session_admission_protections%ROWTYPE;
BEGIN
    SELECT * INTO STRICT original FROM account_start_session_admission_protections
        WHERE protection_id = protection_value AND protection_fence = fence_value;
    IF original.request_binding_bytes IS DISTINCT FROM request_value THEN
        RAISE EXCEPTION 'Original admission recovery binding differs' USING ERRCODE = '23514';
    END IF;
    PERFORM account_ss_admission_assert_current(original, TRUE);
    RETURN NEXT original;
END;
$$;

CREATE FUNCTION account_ss_admission_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.producer_xid IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'Admission protection must be created by its producer transaction' USING ERRCODE = '23514';
    END IF;
    PERFORM account_ss_admission_assert_current(NEW::account_start_session_admission_protections, FALSE);
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_ss_admission_source_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE producer BIGINT;
BEGIN
    SELECT producer_xid INTO STRICT producer FROM account_start_session_admission_protections
        WHERE protection_id = NEW.protection_id;
    IF producer IS DISTINCT FROM txid_current()
        OR account_start_session_world_participation_source_key(NEW.source_evidence) IS DISTINCT FROM NEW.source_key THEN
        RAISE EXCEPTION 'Admission sources must be exact same-producer children' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_ss_admission_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original account_start_session_admission_protections%ROWTYPE;
BEGIN
    SELECT * INTO STRICT original FROM account_start_session_admission_protections
        WHERE protection_id = NEW.protection_id;
    PERFORM account_ss_admission_assert_current(original, TRUE);
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_ss_admission_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original StartSession admission evidence is append-only' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_ss_admission_validate BEFORE INSERT ON account_start_session_admission_protections
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_insert_guard();
CREATE TRIGGER account_ss_admission_immutable BEFORE UPDATE OR DELETE ON account_start_session_admission_protections
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_no_mutation();
CREATE TRIGGER account_ss_admission_no_truncate BEFORE TRUNCATE ON account_start_session_admission_protections
    FOR EACH STATEMENT EXECUTE FUNCTION account_ss_admission_no_mutation();
CREATE TRIGGER account_ss_admission_sources_validate BEFORE INSERT ON account_start_session_admission_protection_sources
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_source_insert_guard();
CREATE TRIGGER account_ss_admission_sources_immutable BEFORE UPDATE OR DELETE ON account_start_session_admission_protection_sources
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_no_mutation();
CREATE TRIGGER account_ss_admission_sources_no_truncate BEFORE TRUNCATE ON account_start_session_admission_protection_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_ss_admission_no_mutation();
CREATE CONSTRAINT TRIGGER account_ss_admission_complete AFTER INSERT ON account_start_session_admission_protections
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_ss_admission_complete_guard();
CREATE TRIGGER account_ss_admission_settlements_immutable BEFORE UPDATE OR DELETE ON account_start_session_admission_protection_settlements
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_no_mutation();
-- The authenticated exact Game Session terminal API is not yet composed. Reject every
-- settlement insertion until its sealed complete terminal binding guard replaces this
-- unpublished gate. An outcome assertion alone can never release source protection.
CREATE TRIGGER account_ss_admission_settlements_disabled BEFORE INSERT ON account_start_session_admission_protection_settlements
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_no_mutation();
CREATE TRIGGER account_ss_admission_settlements_no_truncate BEFORE TRUNCATE ON account_start_session_admission_protection_settlements
    FOR EACH STATEMENT EXECUTE FUNCTION account_ss_admission_no_mutation();

DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_draft_authorization_sources s$anchor$;
BEGIN
    SELECT pg_get_functiondef('account_control_ui_hold_required_sources(TEXT[])'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one assembled admission source-writer anchor';
    END IF;
    EXECUTE replace(original, anchor,
        E'    PERFORM account_ss_admission_assert_no_pending(source_keys);\n' || anchor);
END;
$migration$;

DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$    FOR operation IN SELECT DISTINCT source.operation_id
        FROM account_draft_authorization_changed_scopes changed$anchor$;
BEGIN
    SELECT pg_get_functiondef('account_draft_authorization_source_change_guard()'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one assembled admission source-change anchor';
    END IF;
    EXECUTE replace(original, anchor, $guard$    PERFORM account_ss_admission_assert_no_pending(
        ARRAY(SELECT source_key FROM account_draft_authorization_changed_scopes
            WHERE change_id = OLD.change_id));
$guard$ || anchor);
END;
$migration$;

DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$    IF OLD.status = 'PREPARED' AND NEW.status = 'DISPATCH_AUTHORIZED'$anchor$;
BEGIN
    SELECT pg_get_functiondef('account_hosted_terms_disclosure_handoff_guard()'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one assembled admission disclosure anchor';
    END IF;
    EXECUTE replace(original, anchor, $guard$    IF OLD.status IN ('PREPARED', 'AMBIGUOUS') AND NEW.status = 'DISPATCH_AUTHORIZED' THEN
        PERFORM source_lock.source_key FROM account_draft_authorization_source_locks source_lock
            JOIN (SELECT OLD.source_key AS source_key UNION
                SELECT source_key FROM account_hosted_terms_disclosure_sources
                    WHERE handoff_id = OLD.handoff_id) exact_source USING (source_key)
            ORDER BY account_publication_authorization_source_sort_key(source_lock.source_key)
            FOR UPDATE OF source_lock NOWAIT;
        PERFORM account_ss_admission_assert_no_pending(ARRAY(
            SELECT OLD.source_key UNION SELECT source_key FROM account_hosted_terms_disclosure_sources
                WHERE handoff_id = OLD.handoff_id));
    END IF;
$guard$ || anchor);
END;
$migration$;
-- [jooq ignore end]


-- Immutable Account settlement for the exact original Game Session admission proof.
-- This guard validates retained structure and integrity only. It does not authenticate
-- the remote producer; the internal Game Session client owns that provenance boundary.
-- [jooq ignore start]
CREATE FUNCTION account_ss_admission_settlement_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_start_session_admission_protections%ROWTYPE;
    capture account_start_session_authority_captures%ROWTYPE;
    existing account_start_session_admission_protection_settlements%ROWTYPE;
    terminal_json JSONB;
    evidence_json JSONB;
    snapshot_json JSONB;
    evidence_bytes BYTEA;
    owner_proof_bytes BYTEA;
    canonical_outer TEXT;
    canonical_evidence TEXT;
    canonical_request TEXT;
    source_vector TEXT;
    source_count BIGINT;
    source_bytes_total BIGINT;
    source_value JSONB;
    source_bytes BYTEA;
    source_key_value TEXT;
    previous_sort_key INTEGER[];
    current_sort_key INTEGER[];
    position_value INTEGER := 1;
    frame_next_position INTEGER;
    parsed BYTEA;
    marker TEXT;
    proof_schema TEXT;
    proof_hold_bytes BYTEA;
    proof_outcome TEXT;
    pointer_marker TEXT;
    pointer_text TEXT;
    audit_marker TEXT;
    audit_text TEXT;
    digest_marker TEXT;
    proof_digest TEXT;
    abort_value TEXT;
    terminal_marker TEXT;
    terminal_at_text TEXT;
    terminal_stem TEXT;
    expected_pointer NUMERIC;
BEGIN
    SELECT * INTO STRICT original
        FROM account_start_session_admission_protections
        WHERE protection_id = NEW.protection_id;

    IF NEW.outcome NOT IN ('COMMITTED', 'ABORTED')
        OR NEW.terminal_bytes IS NULL
        OR octet_length(NEW.terminal_bytes) NOT BETWEEN 1 AND 25165824
        OR NEW.terminal_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(NEW.terminal_bytes), 'hex') THEN
        RAISE EXCEPTION 'Malformed original StartSession admission settlement'
            USING ERRCODE = '23514';
    END IF;

    -- Reconstructing the complete outer object makes duplicate keys, alternate escaping,
    -- trailing input, and non-RFC8785 ordering fail byte-for-byte.
    terminal_json := convert_from(NEW.terminal_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(terminal_json) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(terminal_json)) <> 3
        OR NOT (terminal_json ?& ARRAY[
            'schema', 'canonicalAccountProtectionEvidenceBytesBase64',
            'canonicalGameSessionOwnerProofBytesBase64'])
        OR EXISTS (SELECT 1 FROM jsonb_each(terminal_json) field
            WHERE jsonb_typeof(field.value) IS DISTINCT FROM 'string')
        OR terminal_json->>'schema' IS DISTINCT FROM
            'account-start-session-admission-protection-settlement/v1' THEN
        RAISE EXCEPTION 'Original StartSession admission settlement is not the closed envelope'
            USING ERRCODE = '23514';
    END IF;
    evidence_bytes := decode(terminal_json->>'canonicalAccountProtectionEvidenceBytesBase64', 'base64');
    owner_proof_bytes := decode(terminal_json->>'canonicalGameSessionOwnerProofBytesBase64', 'base64');
    IF octet_length(evidence_bytes) NOT BETWEEN 1 AND 8388608
        OR octet_length(owner_proof_bytes) NOT BETWEEN 1 AND 66048
        OR regexp_replace(encode(evidence_bytes, 'base64'), E'[\n\r]', '', 'g')
            IS DISTINCT FROM terminal_json->>'canonicalAccountProtectionEvidenceBytesBase64'
        OR regexp_replace(encode(owner_proof_bytes, 'base64'), E'[\n\r]', '', 'g')
            IS DISTINCT FROM terminal_json->>'canonicalGameSessionOwnerProofBytesBase64' THEN
        RAISE EXCEPTION 'Original StartSession admission settlement has invalid canonical Base64'
            USING ERRCODE = '23514';
    END IF;
    canonical_outer := '{"canonicalAccountProtectionEvidenceBytesBase64":"'
        || (terminal_json->>'canonicalAccountProtectionEvidenceBytesBase64')
        || '","canonicalGameSessionOwnerProofBytesBase64":"'
        || (terminal_json->>'canonicalGameSessionOwnerProofBytesBase64')
        || '","schema":"account-start-session-admission-protection-settlement/v1"}';
    IF convert_to(canonical_outer, 'UTF8') IS DISTINCT FROM NEW.terminal_bytes THEN
        RAISE EXCEPTION 'Original StartSession admission settlement is not canonical JSON'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT capture
        FROM account_start_session_authority_captures
        WHERE control_plane_request_id = original.control_plane_request_id;
    IF capture.source_version IS DISTINCT FROM original.capture_source_version
        OR capture.source_fence IS DISTINCT FROM original.capture_source_fence
        OR capture.canonical_sha256 IS DISTINCT FROM original.capture_sha256
        OR octet_length(capture.canonical_capture_bytes) NOT BETWEEN 1 AND 262144
        OR octet_length(capture.canonical_snapshot_bytes) NOT BETWEEN 1 AND 245760
        OR capture.canonical_sha256 IS DISTINCT FROM
            encode(sha256(capture.canonical_capture_bytes), 'hex')
        OR capture.snapshot_sha256 IS DISTINCT FROM
            encode(sha256(capture.canonical_snapshot_bytes), 'hex') THEN
        RAISE EXCEPTION 'Original StartSession admission capture differs from retained protection'
            USING ERRCODE = '23514';
    END IF;

    -- The immutable Account request is a closed ten-string object. Rebuild its canonical
    -- representation from the retained parent so the settlement cannot bind a projection.
    canonical_request := '{"accountRedemptionProjectionBytesBase64":"'
        || regexp_replace(encode(original.account_redemption_projection, 'base64'), E'[\n\r]', '', 'g')
        || '","accountWorldParticipationFence":"'
        || original.account_world_participation_fence::TEXT
        || '","accountWorldParticipationId":"'
        || original.account_world_participation_id::TEXT
        || '","gameSessionOwnerAttemptId":"'
        || original.game_session_owner_attempt_id::TEXT
        || '","gameSessionOwnerFence":"'
        || original.game_session_owner_fence::TEXT
        || '","gameSessionOwnerMutationId":"'
        || original.game_session_owner_mutation_id::TEXT
        || '","originalLeaseExpiresAt":"'
        || to_char(original.original_lease_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
        || '","originalPostAuthorizationTupleBytesBase64":"'
        || regexp_replace(encode(original.original_post_authorization_tuple, 'base64'), E'[\n\r]', '', 'g')
        || '","schema":"account-start-session-admission-protection-request/v1"'
        || ',"worldAdmissionHoldIdentityBytesBase64":"'
        || regexp_replace(encode(original.world_admission_hold_identity_bytes, 'base64'), E'[\n\r]', '', 'g')
        || '"}';
    IF octet_length(original.request_binding_bytes) NOT BETWEEN 1 AND 786432
        OR octet_length(original.world_admission_hold_identity_bytes) NOT BETWEEN 1 AND 65536
        OR convert_to(canonical_request, 'UTF8') IS DISTINCT FROM original.request_binding_bytes
        OR original.request_binding_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(original.request_binding_bytes), 'hex') THEN
        RAISE EXCEPTION 'Original StartSession admission request is not its exact canonical binding'
            USING ERRCODE = '23514';
    END IF;

    snapshot_json := convert_from(capture.canonical_snapshot_bytes, 'UTF8')::JSONB;
    IF snapshot_json->>'schema' IS DISTINCT FROM 'account-start-session-authority-snapshot/v1'
        OR snapshot_json->>'controlPlaneRequestId' IS DISTINCT FROM original.control_plane_request_id
        OR jsonb_typeof(snapshot_json->'sourceVector') IS DISTINCT FROM 'array'
        OR jsonb_array_length(snapshot_json->'sourceVector') NOT BETWEEN 1 AND 256 THEN
        RAISE EXCEPTION 'Original StartSession admission source capture is incomplete'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO source_count
        FROM account_start_session_admission_protection_sources
        WHERE protection_id = original.protection_id;
    SELECT COALESCE(sum(octet_length(source_evidence)), 0) INTO source_bytes_total
        FROM account_start_session_admission_protection_sources
        WHERE protection_id = original.protection_id;
    IF source_count IS DISTINCT FROM jsonb_array_length(snapshot_json->'sourceVector')::BIGINT
        OR source_bytes_total > 4194304 THEN
        RAISE EXCEPTION 'Original StartSession admission source children are incomplete'
            USING ERRCODE = '23514';
    END IF;
    FOR source_value IN SELECT value FROM jsonb_array_elements(snapshot_json->'sourceVector') LOOP
        IF jsonb_typeof(source_value) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Original StartSession source vector has a non-string member'
                USING ERRCODE = '23514';
        END IF;
        source_bytes := decode(source_value #>> '{}', 'base64');
        IF octet_length(source_bytes) NOT BETWEEN 1 AND 131072
            OR regexp_replace(encode(source_bytes, 'base64'), E'[\n\r]', '', 'g')
                IS DISTINCT FROM source_value #>> '{}' THEN
            RAISE EXCEPTION 'Original StartSession source vector has noncanonical bytes'
                USING ERRCODE = '23514';
        END IF;
        source_key_value := account_start_session_world_participation_source_key(source_bytes);
        current_sort_key := account_publication_authorization_source_sort_key(source_key_value);
        IF previous_sort_key IS NOT NULL AND current_sort_key <= previous_sort_key THEN
            RAISE EXCEPTION 'Original StartSession source vector is duplicated or out of order'
                USING ERRCODE = '23514';
        END IF;
        previous_sort_key := current_sort_key;
        IF NOT EXISTS (
            SELECT 1 FROM account_start_session_admission_protection_sources source
            WHERE source.protection_id = original.protection_id
                AND source.source_key = source_key_value
                AND source.source_evidence = source_bytes) THEN
            RAISE EXCEPTION 'Original StartSession source child differs from its immutable capture'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;

    SELECT string_agg(
            '"' || regexp_replace(encode(source.source_evidence, 'base64'), E'[\n\r]', '', 'g') || '"',
            ',' ORDER BY account_publication_authorization_source_sort_key(source.source_key))
        INTO source_vector
        FROM account_start_session_admission_protection_sources source
        WHERE source.protection_id = original.protection_id;
    canonical_evidence := '{"accountProtectionFence":"' || original.protection_fence::TEXT
        || '","accountProtectionId":"' || original.protection_id::TEXT
        || '","originalSourceCaptureReferenceBytesBase64":"'
        || regexp_replace(encode(capture.canonical_capture_bytes, 'base64'), E'[\n\r]', '', 'g')
        || '","originalSourceCaptureReferenceSha256":"' || capture.canonical_sha256
        || '","requestBytesBase64":"'
        || regexp_replace(encode(original.request_binding_bytes, 'base64'), E'[\n\r]', '', 'g')
        || '","schema":"account-start-session-admission-protection-evidence/v1"'
        || ',"sourceEvidenceVector":[' || source_vector || ']}';
    IF convert_to(canonical_evidence, 'UTF8') IS DISTINCT FROM evidence_bytes THEN
        RAISE EXCEPTION 'Original StartSession admission evidence differs from its full retained binding'
            USING ERRCODE = '23514';
    END IF;

    expected_pointer := account_ss_admission_canonical_hold_next_pointer(original);

    -- The actual Game Session codec frames the hold and seven terminal fields. Do not treat
    -- its opaque terminal proof digest as permission; only the exact COMMITTED/ABORTED shape
    -- below may settle this immutable Account binding.
    SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
        FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
    proof_schema := convert_from(parsed, 'UTF8');
    position_value := frame_next_position;
    SELECT frame.frame_value, frame.next_position INTO proof_hold_bytes, frame_next_position
        FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
    position_value := frame_next_position;
    SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
        FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
    position_value := frame_next_position;
    proof_outcome := convert_from(parsed, 'UTF8');

    SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
        FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
    position_value := frame_next_position;
    pointer_marker := convert_from(parsed, 'UTF8');
    IF pointer_marker = 'PRESENT' THEN
        SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
            FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
        position_value := frame_next_position;
        pointer_text := convert_from(parsed, 'UTF8');
    ELSIF pointer_marker IS DISTINCT FROM 'ABSENT' THEN
        RAISE EXCEPTION 'Original StartSession terminal pointer frame is malformed' USING ERRCODE = '23514';
    END IF;
    SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
        FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
    position_value := frame_next_position;
    audit_marker := convert_from(parsed, 'UTF8');
    IF audit_marker = 'PRESENT' THEN
        SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
            FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
        position_value := frame_next_position;
        audit_text := convert_from(parsed, 'UTF8');
    ELSIF audit_marker IS DISTINCT FROM 'ABSENT' THEN
        RAISE EXCEPTION 'Original StartSession terminal audit frame is malformed' USING ERRCODE = '23514';
    END IF;
    SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
        FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
    position_value := frame_next_position;
    digest_marker := convert_from(parsed, 'UTF8');
    IF digest_marker = 'PRESENT' THEN
        SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
            FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
        position_value := frame_next_position;
        proof_digest := convert_from(parsed, 'UTF8');
    ELSIF digest_marker IS DISTINCT FROM 'ABSENT' THEN
        RAISE EXCEPTION 'Original StartSession terminal digest frame is malformed' USING ERRCODE = '23514';
    END IF;
    SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
        FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
    position_value := frame_next_position;
    abort_value := convert_from(parsed, 'UTF8');
    SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
        FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
    position_value := frame_next_position;
    terminal_marker := convert_from(parsed, 'UTF8');
    IF terminal_marker = 'PRESENT' THEN
        SELECT frame.frame_value, frame.next_position INTO parsed, frame_next_position
            FROM account_publication_authorization_read_frame(owner_proof_bytes, position_value) AS frame;
        position_value := frame_next_position;
        terminal_at_text := convert_from(parsed, 'UTF8');
    ELSIF terminal_marker IS DISTINCT FROM 'ABSENT' THEN
        RAISE EXCEPTION 'Original StartSession terminal timestamp frame is malformed' USING ERRCODE = '23514';
    END IF;
    IF position_value <> octet_length(owner_proof_bytes) + 1
        OR proof_schema IS DISTINCT FROM 'game-session-canonical-initial-admission-owner-proof/v1'
        OR proof_hold_bytes IS DISTINCT FROM original.world_admission_hold_identity_bytes
        OR proof_digest IS NULL OR proof_digest !~ '^sha256:[0-9a-f]{64}$'
        OR terminal_at_text IS NULL
        OR terminal_at_text !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3}|\.[0-9]{6}|\.[0-9]{9})?Z$'
        OR terminal_at_text ~ '\.000Z$'
        OR terminal_at_text ~ '\.[0-9]{3}000Z$'
        OR terminal_at_text ~ '\.[0-9]{6}000Z$'
        OR abort_value NOT IN ('true', 'false') THEN
        RAISE EXCEPTION 'Original StartSession owner proof is malformed or differs from its hold'
            USING ERRCODE = '23514';
    END IF;
    -- Validate the calendar/time stem separately. Casting the complete fraction to
    -- TIMESTAMPTZ would round nanoseconds to PostgreSQL microseconds and could silently
    -- accept noncanonical Java Instant text; the exact frame bytes remain authoritative.
    terminal_stem := to_char(
        substring(terminal_at_text FROM 1 FOR 19)::TIMESTAMP WITHOUT TIME ZONE,
        'YYYY-MM-DD"T"HH24:MI:SS');
    IF terminal_stem IS DISTINCT FROM substring(terminal_at_text FROM 1 FOR 19) THEN
        RAISE EXCEPTION 'Original StartSession owner proof timestamp stem is not normalized'
            USING ERRCODE = '23514';
    END IF;
    IF proof_outcome = 'COMMITTED' THEN
        IF pointer_marker IS DISTINCT FROM 'PRESENT'
            OR pointer_text IS NULL OR pointer_text !~ '^[1-9][0-9]{0,18}$'
            OR audit_marker IS DISTINCT FROM 'PRESENT'
            OR audit_text IS NULL OR audit_text !~ '^[1-9][0-9]{0,18}$'
            OR abort_value IS DISTINCT FROM 'false' THEN
            RAISE EXCEPTION 'Committed Game Session terminal proof has invalid outcome fields'
                USING ERRCODE = '23514';
        END IF;
        IF pointer_text::NUMERIC IS DISTINCT FROM expected_pointer THEN
            RAISE EXCEPTION 'Committed Game Session terminal proof does not name the exact next pointer'
                USING ERRCODE = '23514';
        END IF;
        PERFORM audit_text::BIGINT;
        IF NEW.outcome IS DISTINCT FROM 'COMMITTED' THEN
            RAISE EXCEPTION 'Account settlement outcome differs from Game Session owner proof'
                USING ERRCODE = '23514';
        END IF;
    ELSIF proof_outcome = 'ABORTED' THEN
        IF pointer_marker IS DISTINCT FROM 'ABSENT'
            OR audit_marker IS DISTINCT FROM 'ABSENT'
            OR abort_value IS DISTINCT FROM 'true'
            OR NEW.outcome IS DISTINCT FROM 'ABORTED' THEN
            RAISE EXCEPTION 'Aborted Game Session terminal proof lacks positive durable exclusion'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'Pending or unsupported Game Session owner proof cannot settle protection'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO existing
        FROM account_start_session_admission_protection_settlements
        WHERE protection_id = NEW.protection_id;
    IF FOUND AND (existing.outcome IS DISTINCT FROM NEW.outcome
        OR existing.terminal_bytes IS DISTINCT FROM NEW.terminal_bytes
        OR existing.terminal_digest IS DISTINCT FROM NEW.terminal_digest) THEN
        RAISE EXCEPTION 'Original StartSession admission terminal settlement conflicts with its first receipt'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER account_ss_admission_settlements_disabled
    ON account_start_session_admission_protection_settlements;
CREATE TRIGGER account_ss_admission_settlements_validate
    BEFORE INSERT ON account_start_session_admission_protection_settlements
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_settlement_insert_guard();
-- [jooq ignore end]


-- Distinct Account-owned source protection through the exact original World activation.
-- This is immutable storage only: it authenticates no owner and has no release or
-- settlement path. A future exact ACTIVE/ABORTED terminal contract must add that separately.
CREATE TABLE account_start_session_activation_protections (
    activation_protection_id UUID PRIMARY KEY DEFAULT gen_random_uuid()
        CHECK (activation_protection_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    activation_protection_fence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE
        CHECK (activation_protection_fence > 0),
    control_plane_request_id VARCHAR(128) NOT NULL UNIQUE
        REFERENCES account_start_session_authority_captures(control_plane_request_id) ON DELETE RESTRICT,
    account_world_participation_id UUID NOT NULL UNIQUE
        REFERENCES account_start_session_world_participations(participation_id) ON DELETE RESTRICT,
    account_world_participation_fence BIGINT NOT NULL CHECK (account_world_participation_fence > 0),
    original_post_authorization_tuple BYTEA NOT NULL
        CHECK (octet_length(original_post_authorization_tuple) BETWEEN 1 AND 262144),
    account_redemption_projection BYTEA NOT NULL
        CHECK (octet_length(account_redemption_projection) BETWEEN 1 AND 262144),
    game_session_owner_mutation_id UUID NOT NULL UNIQUE
        CHECK (game_session_owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_attempt_id UUID NOT NULL
        CHECK (game_session_owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_fence BIGINT NOT NULL CHECK (game_session_owner_fence > 0),
    original_lease_expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(original_lease_expires_at)),
    target_namespace VARCHAR(63) NOT NULL
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    canonical_tenant_id UUID NOT NULL
        REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id) ON DELETE RESTRICT,
    canonical_game_instance_id UUID NOT NULL
        CHECK (canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    world_activation_request_id UUID NOT NULL
        CHECK (world_activation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_launch_read_evidence BYTEA NOT NULL
        CHECK (octet_length(game_session_launch_read_evidence) BETWEEN 1 AND 42991616),
    world_preparing_evidence BYTEA NOT NULL
        CHECK (octet_length(world_preparing_evidence) BETWEEN 1 AND 67108864),
    capture_source_version BIGINT NOT NULL CHECK (capture_source_version > 0),
    capture_source_fence BIGINT NOT NULL CHECK (capture_source_fence > 0),
    capture_sha256 VARCHAR(64) NOT NULL CHECK (capture_sha256 ~ '^[0-9a-f]{64}$'),
    capture_reference_bytes BYTEA NOT NULL
        CHECK (octet_length(capture_reference_bytes) BETWEEN 1 AND 262144),
    capture_reference_sha256 VARCHAR(64) NOT NULL
        CHECK (capture_reference_sha256 ~ '^[0-9a-f]{64}$'),
    operation_identity_bytes BYTEA NOT NULL
        CHECK (octet_length(operation_identity_bytes) BETWEEN 1 AND 150994944),
    operation_identity_digest VARCHAR(71) NOT NULL UNIQUE
        CHECK (operation_identity_digest = 'sha256:' || encode(sha256(operation_identity_bytes), 'hex')),
    request_binding_bytes BYTEA NOT NULL
        CHECK (octet_length(request_binding_bytes) BETWEEN 1 AND 150994944),
    request_binding_digest VARCHAR(71) NOT NULL
        CHECK (request_binding_digest = 'sha256:' || encode(sha256(request_binding_bytes), 'hex')),
    producer_xid BIGINT NOT NULL DEFAULT txid_current(),
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (isfinite(acquired_at) AND acquired_at < original_lease_expires_at),
    CHECK (capture_sha256 = capture_reference_sha256)
);

CREATE TABLE account_start_session_activation_protection_sources (
    activation_protection_id UUID NOT NULL
        REFERENCES account_start_session_activation_protections(activation_protection_id) ON DELETE RESTRICT,
    source_key VARCHAR(2048) NOT NULL
        CHECK (length(source_key) > 0 AND octet_length(source_key) <= 2048)
        REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 131072),
    PRIMARY KEY (activation_protection_id, source_key)
);
CREATE INDEX account_ss_activation_sources_by_key
    ON account_start_session_activation_protection_sources(source_key, activation_protection_id);

-- [jooq ignore start]
CREATE FUNCTION account_ss_activation_assert_current(
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

CREATE FUNCTION account_ss_activation_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    request_value JSONB;
    tuple_bytes BYTEA;
    projection_bytes BYTEA;
    launch_bytes BYTEA;
    preparing_bytes BYTEA;
BEGIN
    IF NEW.producer_xid IS DISTINCT FROM txid_current()
        OR NEW.capture_sha256 IS DISTINCT FROM encode(sha256(NEW.capture_reference_bytes), 'hex') THEN
        RAISE EXCEPTION 'Activation protection must retain its producer capture transaction and digest'
            USING ERRCODE = '23514';
    END IF;
    request_value := convert_from(NEW.request_binding_bytes, 'UTF8')::JSONB;
    tuple_bytes := decode(request_value->>'originalPostAuthorizationTupleBytesBase64', 'base64');
    projection_bytes := decode(request_value->>'accountRedemptionProjectionBytesBase64', 'base64');
    launch_bytes := decode(request_value->>'gameSessionLaunchReadEvidenceBytesBase64', 'base64');
    preparing_bytes := decode(request_value->>'preparingEvidenceBytesBase64', 'base64');
    IF NEW.control_plane_request_id IS DISTINCT FROM
            (convert_from(tuple_bytes, 'UTF8')::JSONB->>'controlPlaneRequestId')
        OR NEW.original_post_authorization_tuple IS DISTINCT FROM tuple_bytes
        OR NEW.account_redemption_projection IS DISTINCT FROM projection_bytes
        OR NEW.game_session_owner_mutation_id IS DISTINCT FROM (request_value->>'gameSessionOwnerMutationId')::UUID
        OR NEW.game_session_owner_attempt_id IS DISTINCT FROM (request_value->>'gameSessionOwnerAttemptId')::UUID
        OR NEW.game_session_owner_fence IS DISTINCT FROM (request_value->>'gameSessionOwnerFence')::BIGINT
        OR NEW.original_lease_expires_at IS DISTINCT FROM (request_value->>'originalLeaseExpiresAt')::TIMESTAMPTZ
        OR NEW.account_world_participation_id IS DISTINCT FROM (request_value->>'accountWorldParticipationId')::UUID
        OR NEW.account_world_participation_fence IS DISTINCT FROM (request_value->>'accountWorldParticipationFence')::BIGINT
        OR NEW.world_activation_request_id IS DISTINCT FROM (request_value->>'worldActivationRequestId')::UUID
        OR NEW.game_session_launch_read_evidence IS DISTINCT FROM launch_bytes
        OR NEW.world_preparing_evidence IS DISTINCT FROM preparing_bytes
        OR NEW.request_binding_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.request_binding_bytes), 'hex')
        OR NEW.operation_identity_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.operation_identity_bytes), 'hex') THEN
        RAISE EXCEPTION 'Activation protection does not retain its exact canonical request fields'
            USING ERRCODE = '23514';
    END IF;
    PERFORM account_ss_activation_assert_current(
        NEW.request_binding_bytes, NEW.operation_identity_bytes, NEW.operation_identity_digest, FALSE);
    IF NOT EXISTS (
        SELECT 1 FROM account_start_session_authority_captures capture
        WHERE capture.control_plane_request_id = NEW.control_plane_request_id
            AND capture.source_version = NEW.capture_source_version
            AND capture.source_fence = NEW.capture_source_fence
            AND capture.canonical_sha256 = NEW.capture_sha256
            AND capture.canonical_capture_bytes = NEW.capture_reference_bytes
            AND NEW.target_namespace = (convert_from(tuple_bytes, 'UTF8')::JSONB
                ->'preAuthorizationReservationTuple'->'scope'->>'targetNamespace')
            AND NEW.canonical_tenant_id = (convert_from(tuple_bytes, 'UTF8')::JSONB
                ->'preAuthorizationReservationTuple'->'scope'->>'tenantId')::UUID
            AND NEW.canonical_game_instance_id = (convert_from(preparing_bytes, 'UTF8')::JSONB
                ->'request'->>'canonicalGameInstanceId')::UUID
            AND NEW.capture_reference_sha256 = encode(sha256(NEW.capture_reference_bytes), 'hex')) THEN
        RAISE EXCEPTION 'Activation protection capture or target differs from the exact current request'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_ss_activation_source_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE producer BIGINT;
BEGIN
    SELECT producer_xid INTO STRICT producer FROM account_start_session_activation_protections
        WHERE activation_protection_id = NEW.activation_protection_id;
    IF producer IS DISTINCT FROM txid_current()
        OR account_start_session_world_participation_source_key(NEW.source_evidence) IS DISTINCT FROM NEW.source_key THEN
        RAISE EXCEPTION 'Activation protection sources must be exact same-producer children'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_ss_activation_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original account_start_session_activation_protections%ROWTYPE;
BEGIN
    SELECT * INTO STRICT original FROM account_start_session_activation_protections
        WHERE activation_protection_id = NEW.activation_protection_id;
    PERFORM account_ss_activation_assert_current(
        original.request_binding_bytes, original.operation_identity_bytes,
        original.operation_identity_digest, TRUE);
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_ss_activation_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original StartSession activation protection is immutable and has no settlement path'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_ss_activation_validate BEFORE INSERT ON account_start_session_activation_protections
    FOR EACH ROW EXECUTE FUNCTION account_ss_activation_insert_guard();
CREATE TRIGGER account_ss_activation_immutable BEFORE UPDATE OR DELETE ON account_start_session_activation_protections
    FOR EACH ROW EXECUTE FUNCTION account_ss_activation_no_mutation();
CREATE TRIGGER account_ss_activation_no_truncate BEFORE TRUNCATE ON account_start_session_activation_protections
    FOR EACH STATEMENT EXECUTE FUNCTION account_ss_activation_no_mutation();
CREATE TRIGGER account_ss_activation_sources_validate BEFORE INSERT ON account_start_session_activation_protection_sources
    FOR EACH ROW EXECUTE FUNCTION account_ss_activation_source_insert_guard();
CREATE TRIGGER account_ss_activation_sources_immutable BEFORE UPDATE OR DELETE ON account_start_session_activation_protection_sources
    FOR EACH ROW EXECUTE FUNCTION account_ss_activation_no_mutation();
CREATE TRIGGER account_ss_activation_sources_no_truncate BEFORE TRUNCATE ON account_start_session_activation_protection_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_ss_activation_no_mutation();
CREATE CONSTRAINT TRIGGER account_ss_activation_complete AFTER INSERT ON account_start_session_activation_protections
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_ss_activation_complete_guard();

-- Existing assembled source-writer guards call this helper after locking their canonical
-- source keys. Keep every admission check installed above and add the permanently pending activation rows.
CREATE OR REPLACE FUNCTION account_ss_admission_assert_no_pending(source_keys TEXT[])
RETURNS VOID LANGUAGE plpgsql VOLATILE STRICT AS $$
BEGIN
    PERFORM protection.protection_id
        FROM account_start_session_admission_protections protection
        JOIN account_start_session_admission_protection_sources source
            ON source.protection_id = protection.protection_id
        WHERE source.source_key = ANY(source_keys)
            AND NOT account_ss_admission_is_settled(protection.protection_id)
        ORDER BY protection.protection_id FOR UPDATE OF protection NOWAIT;
    IF FOUND THEN
        RAISE EXCEPTION 'Original StartSession admission protection remains pending for a required source'
            USING ERRCODE = '55P03';
    END IF;
    PERFORM protection.activation_protection_id
        FROM account_start_session_activation_protections protection
        JOIN account_start_session_activation_protection_sources source
            ON source.activation_protection_id = protection.activation_protection_id
        WHERE source.source_key = ANY(source_keys)
        ORDER BY protection.activation_protection_id FOR UPDATE OF protection NOWAIT;
    IF FOUND THEN
        RAISE EXCEPTION 'Original StartSession activation protection remains pending for a required source'
            USING ERRCODE = '55P03';
    END IF;
END;
$$;
-- [jooq ignore end]


-- Append-only resolution of the distinct original StartSession activation source protection.
-- Only an exact authenticated World ACTIVE or fenced ABORTED terminal receipt may be recorded;
-- expiry, missing World state, timeout, generic close, and absence are not terminal outcomes.
CREATE TABLE account_start_session_activation_protection_settlements (
    activation_protection_id UUID PRIMARY KEY
        REFERENCES account_start_session_activation_protections(activation_protection_id) ON DELETE RESTRICT,
    activation_protection_fence BIGINT NOT NULL CHECK (activation_protection_fence > 0),
    world_activation_request_id UUID NOT NULL
        CHECK (world_activation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    world_activation_fence BIGINT NOT NULL CHECK (world_activation_fence > 0),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('COMMITTED', 'ABORTED')),
    account_protection_evidence_bytes BYTEA NOT NULL
        CHECK (octet_length(account_protection_evidence_bytes) BETWEEN 1 AND 209715264),
    terminal_bytes BYTEA NOT NULL
        CHECK (octet_length(terminal_bytes) BETWEEN 1 AND 805306368),
    terminal_digest VARCHAR(71) NOT NULL
        CHECK (terminal_digest = 'sha256:' || encode(sha256(terminal_bytes), 'hex')),
    settled_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
        CHECK (isfinite(settled_at))
);

-- [jooq ignore start]
CREATE FUNCTION account_ss_activation_settlement_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_start_session_activation_protections%ROWTYPE;
    terminal JSONB;
    protection_evidence JSONB;
    protection_request JSONB;
    protection_request_bytes BYTEA;
    result_value JSONB;
    result_bytes BYTEA;
    result_request_bytes BYTEA;
    preparing_bytes BYTEA;
    preparing_value JSONB;
    preparing_request JSONB;
    expected_preparing JSONB;
    expected_activation_request JSONB;
    expected_source_vector JSONB;
    source_count BIGINT;
    evidence_base64 TEXT;
BEGIN
    SELECT * INTO STRICT original FROM account_start_session_activation_protections
        WHERE activation_protection_id = NEW.activation_protection_id;
    terminal := convert_from(NEW.terminal_bytes, 'UTF8')::JSONB;
    evidence_base64 := replace(replace(encode(NEW.account_protection_evidence_bytes, 'base64'), E'\n', ''), E'\r', '');
    protection_evidence := convert_from(NEW.account_protection_evidence_bytes, 'UTF8')::JSONB;
    protection_request_bytes := decode(protection_evidence->>'requestBytesBase64', 'base64');
    protection_request := convert_from(protection_request_bytes, 'UTF8')::JSONB;
    preparing_bytes := decode(protection_request->>'preparingEvidenceBytesBase64', 'base64');
    preparing_value := convert_from(preparing_bytes, 'UTF8')::JSONB;
    preparing_request := preparing_value->'request';
    expected_preparing := jsonb_set(
        preparing_value, '{request}', preparing_request - 'readRequestId', FALSE);
    expected_activation_request := jsonb_build_object(
        'schema', 'world-canonical-instance-activation-request/v1',
        'activationRequestId', original.world_activation_request_id::TEXT,
        'preparingEvidence', expected_preparing);
    result_bytes := decode(terminal->>'activationResultBytesBase64', 'base64');
    result_value := convert_from(result_bytes, 'UTF8')::JSONB;
    result_request_bytes := decode(result_value->>'requestBytesBase64', 'base64');
    SELECT count(*) INTO source_count
        FROM account_start_session_activation_protection_sources
        WHERE activation_protection_id = original.activation_protection_id;
    SELECT COALESCE(
            jsonb_agg(
                to_jsonb(replace(replace(encode(source.source_evidence, 'base64'), E'\n', ''), E'\r', ''))
                ORDER BY account_publication_authorization_source_sort_key(source.source_key)),
            '[]'::JSONB)
        INTO expected_source_vector
        FROM account_start_session_activation_protection_sources source
        WHERE source.activation_protection_id = original.activation_protection_id;
    IF NEW.activation_protection_fence IS DISTINCT FROM original.activation_protection_fence
        OR NEW.world_activation_request_id IS DISTINCT FROM original.world_activation_request_id
        OR NEW.terminal_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.terminal_bytes), 'hex')
        OR jsonb_typeof(protection_evidence) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(protection_evidence)) <> 8
        OR NOT (protection_evidence ?& ARRAY[
            'schema', 'requestBytesBase64', 'accountProtectionId', 'accountProtectionFence',
            'acquiredAt', 'originalSourceCaptureReferenceBytesBase64',
            'originalSourceCaptureReferenceSha256', 'sourceEvidenceVector'])
        OR protection_evidence->>'schema' IS DISTINCT FROM 'account-start-session-activation-protection-evidence/v1'
        OR protection_evidence->>'accountProtectionId' IS DISTINCT FROM original.activation_protection_id::TEXT
        OR protection_evidence->>'accountProtectionFence' IS DISTINCT FROM original.activation_protection_fence::TEXT
        OR protection_evidence->>'acquiredAt' IS DISTINCT FROM
            to_char(original.acquired_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
        OR protection_evidence->>'requestBytesBase64' IS DISTINCT FROM
            replace(replace(encode(original.request_binding_bytes, 'base64'), E'\n', ''), E'\r', '')
        OR protection_request_bytes IS DISTINCT FROM original.request_binding_bytes
        OR jsonb_typeof(protection_request) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(protection_request)) <> 12
        OR NOT (protection_request ?& ARRAY[
            'schema', 'originalPostAuthorizationTupleBytesBase64',
            'accountRedemptionProjectionBytesBase64', 'gameSessionOwnerMutationId',
            'gameSessionOwnerAttemptId', 'gameSessionOwnerFence', 'originalLeaseExpiresAt',
            'accountWorldParticipationId', 'accountWorldParticipationFence',
            'gameSessionLaunchReadEvidenceBytesBase64', 'worldActivationRequestId',
            'preparingEvidenceBytesBase64'])
        OR protection_request->>'schema' IS DISTINCT FROM 'account-start-session-activation-protection-request/v1'
        OR decode(protection_request->>'originalPostAuthorizationTupleBytesBase64', 'base64')
            IS DISTINCT FROM original.original_post_authorization_tuple
        OR decode(protection_request->>'accountRedemptionProjectionBytesBase64', 'base64')
            IS DISTINCT FROM original.account_redemption_projection
        OR protection_request->>'gameSessionOwnerMutationId'
            IS DISTINCT FROM original.game_session_owner_mutation_id::TEXT
        OR protection_request->>'gameSessionOwnerAttemptId'
            IS DISTINCT FROM original.game_session_owner_attempt_id::TEXT
        OR protection_request->>'gameSessionOwnerFence'
            IS DISTINCT FROM original.game_session_owner_fence::TEXT
        OR protection_request->>'originalLeaseExpiresAt' IS DISTINCT FROM
            to_char(original.original_lease_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
        OR protection_request->>'accountWorldParticipationId'
            IS DISTINCT FROM original.account_world_participation_id::TEXT
        OR protection_request->>'accountWorldParticipationFence'
            IS DISTINCT FROM original.account_world_participation_fence::TEXT
        OR decode(protection_request->>'gameSessionLaunchReadEvidenceBytesBase64', 'base64')
            IS DISTINCT FROM original.game_session_launch_read_evidence
        OR protection_request->>'worldActivationRequestId' IS DISTINCT FROM original.world_activation_request_id::TEXT
        OR protection_request->>'preparingEvidenceBytesBase64' IS DISTINCT FROM
            replace(replace(encode(original.world_preparing_evidence, 'base64'), E'\n', ''), E'\r', '')
        OR protection_evidence->>'originalSourceCaptureReferenceBytesBase64' IS DISTINCT FROM
            replace(replace(encode(original.capture_reference_bytes, 'base64'), E'\n', ''), E'\r', '')
        OR protection_evidence->>'originalSourceCaptureReferenceSha256' IS DISTINCT FROM
            encode(sha256(original.capture_reference_bytes), 'hex')
        OR original.capture_reference_sha256 IS DISTINCT FROM
            encode(sha256(original.capture_reference_bytes), 'hex')
        OR jsonb_typeof(protection_evidence->'sourceEvidenceVector') IS DISTINCT FROM 'array'
        OR source_count <= 0
        OR jsonb_array_length(protection_evidence->'sourceEvidenceVector') IS DISTINCT FROM source_count
        OR protection_evidence->'sourceEvidenceVector' IS DISTINCT FROM expected_source_vector
        OR EXISTS (
            SELECT 1 FROM account_start_session_activation_protection_sources source
            WHERE source.activation_protection_id = original.activation_protection_id
                AND account_start_session_world_participation_source_key(source.source_evidence)
                    IS DISTINCT FROM source.source_key)
        OR jsonb_typeof(terminal) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(terminal)) <> 8
        OR NOT (terminal ?& ARRAY[
            'schema', 'activationRequestId', 'accountProtectionId', 'accountProtectionFence',
            'worldActivationFence', 'accountProtectionEvidenceBytesBase64',
            'activationResultBytesBase64', 'activationResultSha256'])
        OR terminal->>'schema' IS DISTINCT FROM 'world-original-start-session-activation-terminal-evidence/v1'
        OR terminal->>'activationRequestId' IS DISTINCT FROM NEW.world_activation_request_id::TEXT
        OR terminal->>'accountProtectionId' IS DISTINCT FROM NEW.activation_protection_id::TEXT
        OR terminal->>'accountProtectionFence' IS DISTINCT FROM NEW.activation_protection_fence::TEXT
        OR terminal->>'worldActivationFence' IS DISTINCT FROM NEW.world_activation_fence::TEXT
        OR terminal->>'accountProtectionEvidenceBytesBase64' IS DISTINCT FROM evidence_base64
        OR terminal->>'activationResultSha256' IS DISTINCT FROM encode(sha256(result_bytes), 'hex')
        OR result_value IS NULL
        OR jsonb_typeof(result_value) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(result_value)) <> 8
        OR NOT (result_value ?& ARRAY[
            'schema', 'activationRequestId', 'requestDigest', 'requestBytesBase64',
            'preparingEvidenceBytesBase64', 'outcome', 'terminalCode',
            'lifecycleEvidenceBytesBase64'])
        OR result_value->>'schema' IS DISTINCT FROM 'world-canonical-instance-activation-result/v1'
        OR result_value->>'activationRequestId' IS DISTINCT FROM NEW.world_activation_request_id::TEXT
        OR result_value->>'requestDigest' IS DISTINCT FROM
            'sha256:' || encode(sha256(result_request_bytes), 'hex')
        OR convert_from(result_request_bytes, 'UTF8')::JSONB IS DISTINCT FROM expected_activation_request
        OR result_value->>'requestBytesBase64' IS DISTINCT FROM
            replace(replace(encode(result_request_bytes, 'base64'), E'\n', ''), E'\r', '')
        OR result_value->>'preparingEvidenceBytesBase64' IS DISTINCT FROM
            replace(replace(encode(original.world_preparing_evidence, 'base64'), E'\n', ''), E'\r', '')
        OR result_value->>'outcome' IS DISTINCT FROM NEW.outcome
        OR (NEW.outcome = 'COMMITTED' AND result_value->'terminalCode' IS DISTINCT FROM 'null'::JSONB)
        OR (NEW.outcome = 'ABORTED'
            AND (jsonb_typeof(result_value->'terminalCode') IS DISTINCT FROM 'string'
                OR length(result_value->>'terminalCode') = 0)) THEN
        RAISE EXCEPTION 'Activation settlement must retain the exact terminal receipt for its protection'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_ss_activation_settlement_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original StartSession activation settlement is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_ss_activation_settlement_validate
    BEFORE INSERT ON account_start_session_activation_protection_settlements
    FOR EACH ROW EXECUTE FUNCTION account_ss_activation_settlement_insert_guard();
CREATE TRIGGER account_ss_activation_settlement_immutable
    BEFORE UPDATE OR DELETE ON account_start_session_activation_protection_settlements
    FOR EACH ROW EXECUTE FUNCTION account_ss_activation_settlement_no_mutation();
CREATE TRIGGER account_ss_activation_settlement_no_truncate
    BEFORE TRUNCATE ON account_start_session_activation_protection_settlements
    FOR EACH STATEMENT EXECUTE FUNCTION account_ss_activation_settlement_no_mutation();

CREATE OR REPLACE FUNCTION account_ss_activation_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original StartSession activation protection is immutable; terminal settlement is separate and append-only'
        USING ERRCODE = '23514';
END;
$$;

-- Preserve the admission-protection branch and settle activation source guards only when an
-- exact terminal receipt exists. Existing V129 source writers already call this shared function.
CREATE OR REPLACE FUNCTION account_ss_admission_assert_no_pending(source_keys TEXT[])
RETURNS VOID LANGUAGE plpgsql VOLATILE STRICT AS $$
BEGIN
    PERFORM protection.protection_id
        FROM account_start_session_admission_protections protection
        JOIN account_start_session_admission_protection_sources source
            ON source.protection_id = protection.protection_id
        WHERE source.source_key = ANY(source_keys)
            AND NOT account_ss_admission_is_settled(protection.protection_id)
        ORDER BY protection.protection_id FOR UPDATE OF protection NOWAIT;
    IF FOUND THEN
        RAISE EXCEPTION 'Original StartSession admission protection remains pending for a required source'
            USING ERRCODE = '55P03';
    END IF;

    PERFORM protection.activation_protection_id
        FROM account_start_session_activation_protections protection
        JOIN account_start_session_activation_protection_sources source
            ON source.activation_protection_id = protection.activation_protection_id
        WHERE source.source_key = ANY(source_keys)
            AND NOT EXISTS (
                SELECT 1 FROM account_start_session_activation_protection_settlements settlement
                WHERE settlement.activation_protection_id = protection.activation_protection_id)
        ORDER BY protection.activation_protection_id FOR UPDATE OF protection NOWAIT;
    IF FOUND THEN
        RAISE EXCEPTION 'Original StartSession activation protection remains pending for a required source'
            USING ERRCODE = '55P03';
    END IF;
END;
$$;
-- [jooq ignore end]
