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
                ORDER BY source.source_key),
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

-- Preserve the V133 admission-protection branch and settle activation source guards only when an
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
