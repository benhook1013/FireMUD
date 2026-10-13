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
    hold_json JSONB;
    hold_request_json JSONB;
    snapshot_json JSONB;
    evidence_bytes BYTEA;
    owner_proof_bytes BYTEA;
    canonical_outer TEXT;
    canonical_evidence TEXT;
    canonical_request TEXT;
    canonical_hold TEXT;
    canonical_hold_request TEXT;
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
    prior_pointer NUMERIC;
    original_request JSONB;
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

    -- Validate the exact retained World hold identity and request, including the original
    -- target and the pointer precondition used to derive a committed next pointer.
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
    original_request := hold_request_json->'request';
    IF proof_outcome = 'COMMITTED' THEN
        IF pointer_marker IS DISTINCT FROM 'PRESENT'
            OR pointer_text IS NULL OR pointer_text !~ '^[1-9][0-9]{0,18}$'
            OR audit_marker IS DISTINCT FROM 'PRESENT'
            OR audit_text IS NULL OR audit_text !~ '^[1-9][0-9]{0,18}$'
            OR abort_value IS DISTINCT FROM 'false' THEN
            RAISE EXCEPTION 'Committed Game Session terminal proof has invalid outcome fields'
                USING ERRCODE = '23514';
        END IF;
        IF original_request->>'initialAdmissionOrigin' = 'NO_PRIOR_POINTER'
            AND original_request->'expectedPriorPointerVersion' = 'null'::JSONB THEN
            expected_pointer := 1;
        ELSIF original_request->>'initialAdmissionOrigin' = 'EXPECT_CLOSED'
            AND jsonb_typeof(original_request->'expectedPriorPointerVersion') = 'string'
            AND original_request->>'expectedPriorPointerVersion' ~ '^[1-9][0-9]{0,18}$' THEN
            prior_pointer := (original_request->>'expectedPriorPointerVersion')::NUMERIC;
            IF prior_pointer >= 9223372036854775807 THEN
                RAISE EXCEPTION 'Original StartSession pointer version cannot advance without overflow'
                    USING ERRCODE = '23514';
            END IF;
            expected_pointer := prior_pointer + 1;
        ELSE
            RAISE EXCEPTION 'Original StartSession hold has an invalid pointer precondition'
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
