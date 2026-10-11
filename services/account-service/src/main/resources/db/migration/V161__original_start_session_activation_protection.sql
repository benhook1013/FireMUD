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
        CHECK (octet_length(account_redemption_projection) BETWEEN 1 AND 135168),
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
        OR projection_value IS DISTINCT FROM jsonb_build_object(
            'projectionSchemaId', 'accountStartSessionRedemptionProjection',
            'projectionSchemaVersion', '1',
            'authorizationReferenceFingerprint', authorization_row.authorization_reference_fingerprint,
            'authorityEvidenceBundle', convert_from(authorization_row.authority_evidence_bundle, 'UTF8')::JSONB,
            'issuanceOperationId', authorization_row.issuance_operation_id::TEXT,
            'issuanceFence', authorization_row.issuance_fence)
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
-- source keys. Keep every V133 admission check and add the permanently pending activation rows.
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
