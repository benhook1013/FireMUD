-- Apply V1004's existing closed PREPARING lifecycle validator to retained protections before
-- comparing their counters, preserving V1004.1's ACTIVE and monotonic settlement checks.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_ss_activation_settlement_lifecycle_guard()
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
    preparing := account_ss_activation_validate_lifecycle(
        original.world_preparing_evidence, 'PREPARING');
    IF (lifecycle->>'lifecycleEpoch')::NUMERIC < (preparing->>'lifecycleEpoch')::NUMERIC
        OR (lifecycle->>'rowVersion')::NUMERIC < (preparing->>'rowVersion')::NUMERIC THEN
        RAISE EXCEPTION 'Committed activation lifecycle counters cannot regress'
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
-- [jooq ignore end]

-- Keep activation-request UUID spelling canonical at the existing Account protection boundary.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_ss_activation_insert_guard()
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
        OR (request_value->>'worldActivationRequestId') IS DISTINCT FROM NEW.world_activation_request_id::TEXT
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
-- [jooq ignore end]
