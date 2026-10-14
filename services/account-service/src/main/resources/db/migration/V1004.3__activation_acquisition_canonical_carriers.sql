-- Require the existing protection fields to retain their canonical Base64 spellings at acquisition.
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
    IF regexp_replace(encode(tuple_bytes, 'base64'), E'[\n\r]', '', 'g')
            IS DISTINCT FROM request_value->>'originalPostAuthorizationTupleBytesBase64'
        OR regexp_replace(encode(projection_bytes, 'base64'), E'[\n\r]', '', 'g')
            IS DISTINCT FROM request_value->>'accountRedemptionProjectionBytesBase64'
        OR regexp_replace(encode(launch_bytes, 'base64'), E'[\n\r]', '', 'g')
            IS DISTINCT FROM request_value->>'gameSessionLaunchReadEvidenceBytesBase64'
        OR regexp_replace(encode(preparing_bytes, 'base64'), E'[\n\r]', '', 'g')
            IS DISTINCT FROM request_value->>'preparingEvidenceBytesBase64' THEN
        RAISE EXCEPTION 'Activation protection Base64 fields must use canonical encodings'
            USING ERRCODE = '23514';
    END IF;
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
