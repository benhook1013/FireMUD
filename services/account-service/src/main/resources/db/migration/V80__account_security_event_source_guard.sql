-- Extend the V40 source-head and Account security guards for the immutable V77/V78 operation
-- receipts and V79 security-state operations without changing historical migrations or treating
-- scalar Account role as authority.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_authority_source_record_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    event_row RECORD;
    event_payload JSONB;
    account_row RECORD;
    expected_modes JSONB;
    authority_row RECORD;
    fence_row RECORD;
    role_source_row RECORD;
    operation_row RECORD;
    reset_row RECORD;
    logout_row RECORD;
    account_state JSONB;
    security_request_id UUID;
    logout_request_id UUID;
BEGIN
    IF NEW.outbox_stream_key IS DISTINCT FROM OLD.outbox_stream_key
        OR NEW.scope_kind IS DISTINCT FROM OLD.scope_kind
        OR NEW.issuer_id IS DISTINCT FROM OLD.issuer_id
        OR NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
        OR NEW.initialization_transaction_id IS DISTINCT FROM OLD.initialization_transaction_id
        OR NEW.baseline_generation IS DISTINCT FROM OLD.baseline_generation
        OR NEW.baseline_source_version IS DISTINCT FROM OLD.baseline_source_version
        OR NEW.baseline_issuance_fence IS DISTINCT FROM OLD.baseline_issuance_fence
        OR NEW.initialization_provenance IS DISTINCT FROM OLD.initialization_provenance
        OR NEW.account_source_numeric_id IS DISTINCT FROM OLD.account_source_numeric_id
        OR NEW.account_uuid_provenance IS DISTINCT FROM OLD.account_uuid_provenance
        OR NEW.account_repository_insert_transaction_id
            IS DISTINCT FROM OLD.account_repository_insert_transaction_id
        OR NEW.last_outbox_sequence <> OLD.last_outbox_sequence + 1
        OR NEW.current_generation <> OLD.current_generation + 1
        OR NEW.current_source_version <> OLD.current_source_version + 1
        OR (NEW.scope_kind = 'ACCOUNT' AND (
            NEW.current_issuance_fence <> OLD.current_issuance_fence + 1
            OR NEW.current_issuance_fence_source_version <> OLD.current_issuance_fence_source_version + 1
            OR NEW.cutoff_generation IS DISTINCT FROM NEW.current_generation
            OR NEW.cutoff_stream_key IS DISTINCT FROM NEW.outbox_stream_key
            OR NEW.cutoff_sequence IS DISTINCT FROM NEW.last_outbox_sequence))
        OR NEW.last_event_id IS NULL OR NEW.last_event_digest IS NULL THEN
        RAISE EXCEPTION 'Account authority source record must advance one exact immutable event'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_record_monotonic';
    END IF;

    SELECT event_id, event_digest, payload INTO event_row
        FROM account_authority_outbox_events
        WHERE outbox_stream_key = NEW.outbox_stream_key
          AND outbox_sequence = NEW.last_outbox_sequence;
    IF event_row.event_id IS DISTINCT FROM NEW.last_event_id
        OR event_row.event_digest IS DISTINCT FROM NEW.last_event_digest THEN
        RAISE EXCEPTION 'Account authority source head does not match its exact outbox event'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_link';
    END IF;
    event_payload := convert_from(event_row.payload, 'UTF8')::JSONB;
    IF event_payload->>'outboxStreamKey' IS DISTINCT FROM NEW.outbox_stream_key
        OR event_payload->>'outboxSequence' IS DISTINCT FROM NEW.last_outbox_sequence::TEXT
        OR event_payload->>'eventId' IS DISTINCT FROM NEW.last_event_id
        OR event_payload->>'eventDigest' IS DISTINCT FROM NEW.last_event_digest THEN
        RAISE EXCEPTION 'Account authority source event does not match its exact outbox head'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_link';
    END IF;

    IF NEW.scope_kind = 'ISSUER' THEN
        IF event_payload->>'eventType' IS DISTINCT FROM 'ISSUER_AUTHORITY_CHANGED'
            OR event_payload->>'issuerId' IS DISTINCT FROM NEW.issuer_id
            OR event_payload->>'issuerAuthGeneration' IS DISTINCT FROM NEW.current_generation::TEXT
            OR event_payload->>'sourceVersion' IS DISTINCT FROM NEW.current_source_version::TEXT THEN
            RAISE EXCEPTION 'Issuer source event does not match exact advanced owner rows'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
        END IF;
    ELSIF NEW.scope_kind = 'ACCOUNT' THEN
        SELECT id, account_uuid_provenance, account_uuid_source_numeric_id, email,
               username, password_hash, email_verified, login_auth_modes, role, lifecycle_state
            INTO account_row FROM accounts WHERE account_uuid = NEW.account_uuid;
        SELECT COALESCE(jsonb_agg(to_jsonb(login_mode) ORDER BY login_mode), '[]'::JSONB)
            INTO expected_modes
            FROM unnest(string_to_array(account_row.login_auth_modes, ',')) AS modes(login_mode);
        IF account_row.id IS NULL
            OR account_row.id IS DISTINCT FROM NEW.account_source_numeric_id
            OR account_row.account_uuid_source_numeric_id IS DISTINCT FROM NEW.account_source_numeric_id
            OR account_row.account_uuid_provenance IS DISTINCT FROM NEW.account_uuid_provenance THEN
            RAISE EXCEPTION 'Account source event has no exact Account identity/provenance'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_account_state';
        END IF;
        SELECT issuance_fence, source_version INTO fence_row
            FROM account_authority_issuance_fences
            WHERE account_uuid = NEW.account_uuid;
        IF fence_row.issuance_fence IS DISTINCT FROM NEW.current_issuance_fence
            OR fence_row.source_version IS DISTINCT FROM NEW.current_issuance_fence_source_version THEN
            RAISE EXCEPTION 'Account source event does not match the exact current shared issuance fence'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_fence_consistent';
        END IF;

        IF event_payload->>'eventType' = 'ACCOUNT_AUTHORITY_CHANGED' THEN
            -- Preserve the pre-V78 Account source event contract exactly.
            IF event_payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
                OR event_payload->>'accountAuthorityGeneration' IS DISTINCT FROM NEW.current_generation::TEXT
                OR event_payload->>'sourceVersion' IS DISTINCT FROM NEW.current_source_version::TEXT
                OR event_payload->>'issuanceFence' IS DISTINCT FROM NEW.current_issuance_fence::TEXT
                OR event_payload->>'issuanceFenceSourceVersion' IS DISTINCT FROM NEW.current_issuance_fence_source_version::TEXT
                OR event_payload#>>'{accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM NEW.cutoff_generation::TEXT
                OR event_payload#>>'{accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM NEW.cutoff_stream_key
                OR event_payload#>>'{accountSecurityCutoff,outboxSequence}' IS DISTINCT FROM NEW.cutoff_sequence::TEXT
                OR event_payload#>>'{accountState,emailVerified}' IS DISTINCT FROM account_row.email_verified::TEXT
                OR event_payload#>'{accountState,loginAuthModes}' IS DISTINCT FROM expected_modes
                OR event_payload#>>'{accountState,globalRole}' IS DISTINCT FROM account_row.role
                OR event_payload#>>'{accountState,lifecycleState}' IS DISTINCT FROM upper(account_row.lifecycle_state) THEN
                RAISE EXCEPTION 'Account source event does not match exact current Account state'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_account_state';
            END IF;
        ELSIF event_payload->>'eventType' = 'ACCOUNT_SECURITY_STATE_CHANGED' THEN
            -- V79 deliberately omits issuance-fence fields from its canonical event. Bind that
            -- state to the actual Account authority/fence and global-role source owners instead.
            SELECT generation, source_version INTO authority_row
                FROM account_authority_generations
                WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid
                  AND issuer_id IS NULL AND tenant_uuid IS NULL;
            SELECT issuance_fence, source_version INTO fence_row
                FROM account_authority_issuance_fences
                WHERE account_uuid = NEW.account_uuid;
            SELECT account_uuid_source_numeric_id, account_uuid_provenance,
                   global_roles, global_role_source_version
                INTO role_source_row FROM account_global_role_sources
                WHERE account_uuid = NEW.account_uuid;

            account_state := event_payload->'accountState';
            IF event_payload->>'schemaVersion' IS DISTINCT FROM 'account-auth-account-security-state-event/v1'
                OR event_payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
                OR event_payload->>'sourceScope' IS DISTINCT FROM 'account/' || NEW.account_uuid::TEXT
                OR event_payload->>'accountAuthorityGeneration' IS DISTINCT FROM NEW.current_generation::TEXT
                OR event_payload->>'sourceVersion' IS DISTINCT FROM NEW.current_source_version::TEXT
                OR event_payload#>>'{accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM NEW.cutoff_generation::TEXT
                OR event_payload#>>'{accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM NEW.cutoff_stream_key
                OR event_payload#>>'{accountSecurityCutoff,outboxSequence}' IS DISTINCT FROM NEW.cutoff_sequence::TEXT
                OR authority_row.generation IS DISTINCT FROM NEW.current_generation
                OR authority_row.source_version IS DISTINCT FROM NEW.current_source_version
                OR fence_row.issuance_fence IS DISTINCT FROM NEW.current_issuance_fence
                OR fence_row.source_version IS DISTINCT FROM NEW.current_issuance_fence_source_version
                OR role_source_row.account_uuid_source_numeric_id IS DISTINCT FROM NEW.account_source_numeric_id
                OR role_source_row.account_uuid_provenance IS DISTINCT FROM NEW.account_uuid_provenance
                OR role_source_row.global_role_source_version IS NULL
                OR jsonb_typeof(account_state) IS DISTINCT FROM 'object'
                OR (account_state - ARRAY['emailVerified','globalRoles','lifecycleState','loginAuthModes']) <> '{}'::JSONB
                OR NOT (account_state ?& ARRAY['emailVerified','globalRoles','lifecycleState','loginAuthModes'])
                OR account_state->'emailVerified' IS DISTINCT FROM to_jsonb(account_row.email_verified)
                OR account_state->'loginAuthModes' IS DISTINCT FROM expected_modes
                OR account_state->'globalRoles' IS DISTINCT FROM to_jsonb(role_source_row.global_roles)
                OR account_state->>'lifecycleState' IS DISTINCT FROM upper(account_row.lifecycle_state)
                OR role_source_row.global_roles IS DISTINCT FROM
                    (SELECT COALESCE(array_agg(DISTINCT role_name ORDER BY role_name), ARRAY[]::TEXT[])
                        FROM unnest(role_source_row.global_roles) AS roles(role_name)) THEN
                RAISE EXCEPTION 'Security-state source event does not match exact Account authority/fence/role owners'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
            END IF;

            IF event_payload->>'requestId' IS NULL
                OR event_payload->>'requestId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
                RAISE EXCEPTION 'Security-state source event request identity is malformed'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
            END IF;
            security_request_id := (event_payload->>'requestId')::UUID;
            SELECT * INTO operation_row FROM account_security_state_operations
                WHERE request_id = security_request_id;
            IF operation_row.request_id IS NULL
                OR operation_row.status IS DISTINCT FROM 'COMMITTED'
                OR operation_row.account_uuid IS DISTINCT FROM NEW.account_uuid
                OR operation_row.account_id IS DISTINCT FROM account_row.id
                OR operation_row.account_provenance IS DISTINCT FROM NEW.account_uuid_provenance
                OR operation_row.event_sequence IS DISTINCT FROM NEW.last_outbox_sequence
                OR operation_row.event_id IS DISTINCT FROM NEW.last_event_id
                OR operation_row.event_digest IS DISTINCT FROM NEW.last_event_digest
                OR operation_row.event_payload IS DISTINCT FROM event_row.payload
                OR operation_row.result_generation IS DISTINCT FROM NEW.current_generation
                OR operation_row.result_source_version IS DISTINCT FROM NEW.current_source_version
                OR operation_row.result_fence IS DISTINCT FROM NEW.current_issuance_fence
                OR operation_row.result_fence_source_version IS DISTINCT FROM NEW.current_issuance_fence_source_version
                OR operation_row.result_global_role_source_version IS DISTINCT FROM role_source_row.global_role_source_version
                OR convert_from(operation_row.after_state, 'UTF8')::JSONB IS DISTINCT FROM account_state
                OR event_payload->>'eventId' IS DISTINCT FROM 'account-security-state-event-v1:' || security_request_id::TEXT THEN
                RAISE EXCEPTION 'Security-state source event has no exact committed owner receipt'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
            END IF;
        ELSIF event_payload->>'eventType' = 'PASSWORD_RESET_COMMITTED' THEN
            SELECT * INTO reset_row FROM account_password_reset_operation_receipts
                WHERE request_id = event_payload->>'requestId';
            IF event_payload->>'schemaVersion' IS DISTINCT FROM 'account-auth-password-reset-event/v1'
                OR event_payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
                OR event_payload->>'sourceScope' IS DISTINCT FROM 'account/' || NEW.account_uuid::TEXT
                OR event_payload->>'accountAuthorityGeneration' IS DISTINCT FROM NEW.current_generation::TEXT
                OR event_payload->>'sourceVersion' IS DISTINCT FROM NEW.current_source_version::TEXT
                OR event_payload#>>'{accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM NEW.cutoff_generation::TEXT
                OR event_payload#>>'{accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM NEW.cutoff_stream_key
                OR event_payload#>>'{accountSecurityCutoff,outboxSequence}' IS DISTINCT FROM NEW.cutoff_sequence::TEXT
                OR reset_row.token_hash IS NULL
                OR reset_row.account_id IS DISTINCT FROM account_row.id
                OR reset_row.account_uuid IS DISTINCT FROM NEW.account_uuid
                OR reset_row.operation_kind IS DISTINCT FROM 'PASSWORD_RESET'
                OR reset_row.request_digest_version IS DISTINCT FROM 1
                OR reset_row.request_id IS DISTINCT FROM 'account-password-reset-request-v1:' || encode(reset_row.token_hash, 'hex')
                OR reset_row.request_id IS DISTINCT FROM event_payload->>'requestId'
                OR reset_row.outbox_stream_key IS DISTINCT FROM NEW.outbox_stream_key
                OR reset_row.outbox_sequence IS DISTINCT FROM NEW.last_outbox_sequence
                OR reset_row.event_id IS DISTINCT FROM NEW.last_event_id
                OR reset_row.event_id IS DISTINCT FROM 'account-password-reset-event-v1:' || encode(reset_row.token_hash, 'hex')
                OR reset_row.event_digest IS DISTINCT FROM NEW.last_event_digest
                OR reset_row.event_digest IS DISTINCT FROM event_row.event_digest
                OR reset_row.account_authority_generation IS DISTINCT FROM NEW.current_generation
                OR reset_row.account_source_version IS DISTINCT FROM NEW.current_source_version
                OR reset_row.issuance_fence IS DISTINCT FROM NEW.current_issuance_fence
                OR reset_row.issuance_fence_source_version IS DISTINCT FROM NEW.current_issuance_fence_source_version
                OR reset_row.password_verifier_digest
                    IS DISTINCT FROM sha256(convert_to(account_row.password_hash, 'UTF8')) THEN
                RAISE EXCEPTION 'Password-reset source event has no exact immutable Account operation receipt'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
            END IF;
        ELSIF event_payload->>'eventType' = 'LOGOUT_ALL_COMMITTED' THEN
            IF event_payload->>'requestId' IS NULL
                OR event_payload->>'requestId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
                RAISE EXCEPTION 'Logout-all source event request identity is malformed'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
            END IF;
            logout_request_id := (event_payload->>'requestId')::UUID;
            SELECT * INTO logout_row FROM account_logout_all_operation_receipts
                WHERE request_id = logout_request_id;
            IF event_payload->>'schemaVersion' IS DISTINCT FROM 'account-auth-logout-all-event/v1'
                OR event_payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
                OR event_payload->>'sourceScope' IS DISTINCT FROM 'account/' || NEW.account_uuid::TEXT
                OR event_payload->>'accountAuthorityGeneration' IS DISTINCT FROM NEW.current_generation::TEXT
                OR event_payload->>'sourceVersion' IS DISTINCT FROM NEW.current_source_version::TEXT
                OR event_payload#>>'{accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM NEW.cutoff_generation::TEXT
                OR event_payload#>>'{accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM NEW.cutoff_stream_key
                OR event_payload#>>'{accountSecurityCutoff,outboxSequence}' IS DISTINCT FROM NEW.cutoff_sequence::TEXT
                OR logout_row.request_id IS DISTINCT FROM logout_request_id
                OR logout_row.account_id IS DISTINCT FROM account_row.id
                OR logout_row.account_uuid IS DISTINCT FROM NEW.account_uuid
                OR logout_row.operation_kind IS DISTINCT FROM 'ACCOUNT_LOGOUT_ALL'
                OR logout_row.request_digest_version IS DISTINCT FROM 1
                OR logout_row.lifecycle_result IS DISTINCT FROM 'LOGOUT_ALL_COMMITTED'
                OR event_payload->>'eventId' IS DISTINCT FROM 'account-logout-all-event-v1:' || logout_request_id::TEXT
                OR logout_row.event_id IS DISTINCT FROM event_payload->>'eventId'
                OR logout_row.outbox_stream_key IS DISTINCT FROM NEW.outbox_stream_key
                OR logout_row.outbox_sequence IS DISTINCT FROM NEW.last_outbox_sequence
                OR logout_row.event_digest IS DISTINCT FROM NEW.last_event_digest
                OR logout_row.event_digest IS DISTINCT FROM event_row.event_digest
                OR logout_row.account_authority_generation IS DISTINCT FROM NEW.current_generation
                OR logout_row.account_source_version IS DISTINCT FROM NEW.current_source_version
                OR logout_row.issuance_fence IS DISTINCT FROM NEW.current_issuance_fence
                OR logout_row.issuance_fence_source_version IS DISTINCT FROM NEW.current_issuance_fence_source_version THEN
                RAISE EXCEPTION 'Logout-all source event has no exact immutable Account operation receipt'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
            END IF;
        ELSE
            RAISE EXCEPTION 'Unsupported Account source event type'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
        END IF;
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION account_authority_account_security_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_row RECORD;
    event_row RECORD;
    event_payload JSONB;
    expected_modes JSONB;
    expected_old_modes JSONB;
    authority_row RECORD;
    fence_row RECORD;
    role_source_row RECORD;
    operation_row RECORD;
    reset_row RECORD;
    logout_row RECORD;
    before_state JSONB;
    after_state JSONB;
    expected_before_state JSONB;
    expected_after_state JSONB;
    changed_kinds TEXT[] := ARRAY[]::TEXT[];
    security_request_id UUID;
    logout_request_id UUID;
BEGIN
    IF OLD.password_hash IS NOT DISTINCT FROM NEW.password_hash
        AND OLD.email_verified IS NOT DISTINCT FROM NEW.email_verified
        AND OLD.login_auth_modes IS NOT DISTINCT FROM NEW.login_auth_modes
        AND OLD.role IS NOT DISTINCT FROM NEW.role
        AND OLD.lifecycle_state IS NOT DISTINCT FROM NEW.lifecycle_state THEN
        RETURN NULL;
    END IF;

    SELECT source_record.* INTO source_row
        FROM account_authority_source_records source_record
        WHERE source_record.scope_kind = 'ACCOUNT'
          AND source_record.account_uuid = NEW.account_uuid;
    IF source_row.outbox_stream_key IS NULL OR source_row.last_outbox_sequence < 1
        OR source_row.last_event_id IS NULL OR source_row.last_event_digest IS NULL THEN
        RAISE EXCEPTION 'Account security mutation requires an appended Account authority source event'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_required';
    END IF;

    SELECT event_id, event_digest, payload INTO event_row
        FROM account_authority_outbox_events
        WHERE outbox_stream_key = source_row.outbox_stream_key
          AND outbox_sequence = source_row.last_outbox_sequence
          AND event_id = source_row.last_event_id
          AND event_digest = source_row.last_event_digest;
    event_payload := convert_from(event_row.payload, 'UTF8')::JSONB;
    SELECT COALESCE(jsonb_agg(to_jsonb(login_mode) ORDER BY login_mode), '[]'::JSONB)
        INTO expected_modes
    FROM unnest(string_to_array(NEW.login_auth_modes, ',')) AS modes(login_mode);

    IF event_payload->>'eventType' = 'PASSWORD_RESET_COMMITTED' THEN
        SELECT * INTO reset_row FROM account_password_reset_operation_receipts
            WHERE request_id = event_payload->>'requestId';
        IF OLD.password_hash IS NOT DISTINCT FROM NEW.password_hash
            OR (to_jsonb(OLD) - 'password_hash') IS DISTINCT FROM
                (to_jsonb(NEW) - 'password_hash')
            OR event_payload->>'schemaVersion' IS DISTINCT FROM 'account-auth-password-reset-event/v1'
            OR event_payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
            OR event_payload->>'sourceScope' IS DISTINCT FROM 'account/' || NEW.account_uuid::TEXT
            OR event_payload->>'outboxStreamKey' IS DISTINCT FROM source_row.outbox_stream_key
            OR event_payload->>'outboxSequence' IS DISTINCT FROM source_row.last_outbox_sequence::TEXT
            OR event_payload->>'eventId' IS DISTINCT FROM source_row.last_event_id
            OR event_payload->>'eventDigest' IS DISTINCT FROM source_row.last_event_digest
            OR event_payload->>'accountAuthorityGeneration' IS DISTINCT FROM source_row.current_generation::TEXT
            OR event_payload->>'sourceVersion' IS DISTINCT FROM source_row.current_source_version::TEXT
            OR event_payload#>>'{accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM source_row.cutoff_generation::TEXT
            OR event_payload#>>'{accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM source_row.cutoff_stream_key
            OR event_payload#>>'{accountSecurityCutoff,outboxSequence}' IS DISTINCT FROM source_row.cutoff_sequence::TEXT
            OR reset_row.token_hash IS NULL
            OR reset_row.account_id IS DISTINCT FROM NEW.id
            OR reset_row.account_uuid IS DISTINCT FROM NEW.account_uuid
            OR reset_row.operation_kind IS DISTINCT FROM 'PASSWORD_RESET'
            OR reset_row.request_digest_version IS DISTINCT FROM 1
            OR reset_row.request_id IS DISTINCT FROM 'account-password-reset-request-v1:' || encode(reset_row.token_hash, 'hex')
            OR reset_row.request_id IS DISTINCT FROM event_payload->>'requestId'
            OR reset_row.outbox_stream_key IS DISTINCT FROM source_row.outbox_stream_key
            OR reset_row.outbox_sequence IS DISTINCT FROM source_row.last_outbox_sequence
            OR reset_row.event_id IS DISTINCT FROM source_row.last_event_id
            OR reset_row.event_id IS DISTINCT FROM 'account-password-reset-event-v1:' || encode(reset_row.token_hash, 'hex')
            OR reset_row.event_digest IS DISTINCT FROM source_row.last_event_digest
            OR reset_row.event_digest IS DISTINCT FROM event_row.event_digest
            OR reset_row.account_authority_generation IS DISTINCT FROM source_row.current_generation
            OR reset_row.account_source_version IS DISTINCT FROM source_row.current_source_version
            OR reset_row.issuance_fence IS DISTINCT FROM source_row.current_issuance_fence
            OR reset_row.issuance_fence_source_version IS DISTINCT FROM source_row.current_issuance_fence_source_version
            OR reset_row.password_verifier_digest IS DISTINCT FROM sha256(convert_to(NEW.password_hash, 'UTF8')) THEN
            RAISE EXCEPTION 'Password-reset source event does not authorize the exact Account password update'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
        END IF;
        RETURN NULL;
    END IF;

    IF event_payload->>'eventType' = 'LOGOUT_ALL_COMMITTED' THEN
        IF event_payload->>'requestId' IS NULL
            OR event_payload->>'requestId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
            RAISE EXCEPTION 'Logout-all source event request identity is malformed'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
        END IF;
        logout_request_id := (event_payload->>'requestId')::UUID;
        SELECT * INTO logout_row FROM account_logout_all_operation_receipts
            WHERE request_id = logout_request_id;
        IF event_payload->>'schemaVersion' IS DISTINCT FROM 'account-auth-logout-all-event/v1'
            OR event_payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
            OR event_payload->>'sourceScope' IS DISTINCT FROM 'account/' || NEW.account_uuid::TEXT
            OR event_payload->>'outboxStreamKey' IS DISTINCT FROM source_row.outbox_stream_key
            OR event_payload->>'outboxSequence' IS DISTINCT FROM source_row.last_outbox_sequence::TEXT
            OR event_payload->>'eventId' IS DISTINCT FROM source_row.last_event_id
            OR event_payload->>'eventDigest' IS DISTINCT FROM source_row.last_event_digest
            OR event_payload->>'accountAuthorityGeneration' IS DISTINCT FROM source_row.current_generation::TEXT
            OR event_payload->>'sourceVersion' IS DISTINCT FROM source_row.current_source_version::TEXT
            OR event_payload#>>'{accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM source_row.cutoff_generation::TEXT
            OR event_payload#>>'{accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM source_row.cutoff_stream_key
            OR event_payload#>>'{accountSecurityCutoff,outboxSequence}' IS DISTINCT FROM source_row.cutoff_sequence::TEXT
            OR logout_row.request_id IS DISTINCT FROM logout_request_id
            OR logout_row.account_id IS DISTINCT FROM NEW.id
            OR logout_row.account_uuid IS DISTINCT FROM NEW.account_uuid
            OR logout_row.operation_kind IS DISTINCT FROM 'ACCOUNT_LOGOUT_ALL'
            OR logout_row.request_digest_version IS DISTINCT FROM 1
            OR logout_row.lifecycle_result IS DISTINCT FROM 'LOGOUT_ALL_COMMITTED'
            OR event_payload->>'eventId' IS DISTINCT FROM 'account-logout-all-event-v1:' || logout_request_id::TEXT
            OR logout_row.event_id IS DISTINCT FROM source_row.last_event_id
            OR logout_row.outbox_stream_key IS DISTINCT FROM source_row.outbox_stream_key
            OR logout_row.outbox_sequence IS DISTINCT FROM source_row.last_outbox_sequence
            OR logout_row.event_digest IS DISTINCT FROM source_row.last_event_digest
            OR logout_row.event_digest IS DISTINCT FROM event_row.event_digest
            OR logout_row.account_authority_generation IS DISTINCT FROM source_row.current_generation
            OR logout_row.account_source_version IS DISTINCT FROM source_row.current_source_version
            OR logout_row.issuance_fence IS DISTINCT FROM source_row.current_issuance_fence
            OR logout_row.issuance_fence_source_version IS DISTINCT FROM source_row.current_issuance_fence_source_version THEN
            RAISE EXCEPTION 'Logout-all source event has no exact immutable Account operation receipt'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
        END IF;
        RAISE EXCEPTION 'Logout-all source events cannot authorize Account security state changes'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
    END IF;

    IF event_payload->>'eventType' = 'ACCOUNT_SECURITY_STATE_CHANGED' THEN
        SELECT generation, source_version INTO authority_row
            FROM account_authority_generations
            WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid
              AND issuer_id IS NULL AND tenant_uuid IS NULL;
        SELECT issuance_fence, source_version INTO fence_row
            FROM account_authority_issuance_fences
            WHERE account_uuid = NEW.account_uuid;
        SELECT account_uuid_source_numeric_id, account_uuid_provenance,
               global_roles, global_role_source_version
            INTO role_source_row FROM account_global_role_sources
            WHERE account_uuid = NEW.account_uuid;
        SELECT COALESCE(jsonb_agg(to_jsonb(login_mode) ORDER BY login_mode), '[]'::JSONB)
            INTO expected_old_modes
            FROM unnest(string_to_array(OLD.login_auth_modes, ',')) AS modes(login_mode);
        IF OLD.password_hash IS DISTINCT FROM NEW.password_hash
            OR OLD.role IS DISTINCT FROM NEW.role THEN
            RAISE EXCEPTION 'V79 security-state events cannot authorize password or scalar-role changes'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
        END IF;
        IF event_payload->>'schemaVersion' IS DISTINCT FROM 'account-auth-account-security-state-event/v1'
            OR event_payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
            OR event_payload->>'sourceScope' IS DISTINCT FROM 'account/' || NEW.account_uuid::TEXT
            OR event_payload->>'outboxStreamKey' IS DISTINCT FROM source_row.outbox_stream_key
            OR event_payload->>'outboxSequence' IS DISTINCT FROM source_row.last_outbox_sequence::TEXT
            OR event_payload->>'eventId' IS DISTINCT FROM source_row.last_event_id
            OR event_payload->>'eventDigest' IS DISTINCT FROM source_row.last_event_digest
            OR event_payload->>'accountAuthorityGeneration' IS DISTINCT FROM source_row.current_generation::TEXT
            OR event_payload->>'sourceVersion' IS DISTINCT FROM source_row.current_source_version::TEXT
            OR event_payload#>>'{accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM source_row.cutoff_generation::TEXT
            OR event_payload#>>'{accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM source_row.cutoff_stream_key
            OR event_payload#>>'{accountSecurityCutoff,outboxSequence}' IS DISTINCT FROM source_row.cutoff_sequence::TEXT
            OR authority_row.generation IS DISTINCT FROM source_row.current_generation
            OR authority_row.source_version IS DISTINCT FROM source_row.current_source_version
            OR fence_row.issuance_fence IS DISTINCT FROM source_row.current_issuance_fence
            OR fence_row.source_version IS DISTINCT FROM source_row.current_issuance_fence_source_version
            OR role_source_row.account_uuid_source_numeric_id IS DISTINCT FROM NEW.account_uuid_source_numeric_id
            OR role_source_row.account_uuid_provenance IS DISTINCT FROM NEW.account_uuid_provenance
            OR role_source_row.global_role_source_version IS NULL
            OR role_source_row.global_roles IS DISTINCT FROM
                (SELECT COALESCE(array_agg(DISTINCT role_name ORDER BY role_name), ARRAY[]::TEXT[])
                    FROM unnest(role_source_row.global_roles) AS roles(role_name)) THEN
            RAISE EXCEPTION 'V79 security-state event does not match current Account source owners'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
        END IF;

        IF event_payload->>'requestId' IS NULL
            OR event_payload->>'requestId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
            RAISE EXCEPTION 'V79 security-state event request identity is malformed'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
        END IF;
        security_request_id := (event_payload->>'requestId')::UUID;
        SELECT * INTO operation_row FROM account_security_state_operations
            WHERE request_id = security_request_id;
        IF operation_row.request_id IS NULL
            OR operation_row.status IS DISTINCT FROM 'COMMITTED'
            OR operation_row.account_uuid IS DISTINCT FROM NEW.account_uuid
            OR operation_row.account_id IS DISTINCT FROM NEW.id
            OR operation_row.account_provenance IS DISTINCT FROM NEW.account_uuid_provenance
            OR operation_row.event_sequence IS DISTINCT FROM source_row.last_outbox_sequence
            OR operation_row.event_id IS DISTINCT FROM source_row.last_event_id
            OR operation_row.event_digest IS DISTINCT FROM source_row.last_event_digest
            OR operation_row.event_payload IS DISTINCT FROM event_row.payload
            OR operation_row.expected_generation + 1 IS DISTINCT FROM source_row.current_generation
            OR operation_row.expected_source_version + 1 IS DISTINCT FROM source_row.current_source_version
            OR operation_row.expected_fence + 1 IS DISTINCT FROM source_row.current_issuance_fence
            OR operation_row.expected_fence_source_version + 1 IS DISTINCT FROM source_row.current_issuance_fence_source_version
            OR operation_row.checkpoint_sequence + 1 IS DISTINCT FROM source_row.last_outbox_sequence
            OR operation_row.result_generation IS DISTINCT FROM source_row.current_generation
            OR operation_row.result_source_version IS DISTINCT FROM source_row.current_source_version
            OR operation_row.result_fence IS DISTINCT FROM source_row.current_issuance_fence
            OR operation_row.result_fence_source_version IS DISTINCT FROM source_row.current_issuance_fence_source_version
            OR operation_row.result_global_role_source_version IS DISTINCT FROM role_source_row.global_role_source_version
            OR event_payload->>'eventId' IS DISTINCT FROM 'account-security-state-event-v1:' || security_request_id::TEXT THEN
            RAISE EXCEPTION 'V79 security-state event has no exact committed Account operation receipt'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
        END IF;

        before_state := convert_from(operation_row.before_state, 'UTF8')::JSONB;
        after_state := convert_from(operation_row.after_state, 'UTF8')::JSONB;
        expected_before_state := jsonb_build_object(
            'emailVerified', OLD.email_verified,
            'globalRoles', to_jsonb(role_source_row.global_roles),
            'lifecycleState', upper(OLD.lifecycle_state),
            'loginAuthModes', expected_old_modes);
        expected_after_state := jsonb_build_object(
            'emailVerified', NEW.email_verified,
            'globalRoles', to_jsonb(role_source_row.global_roles),
            'lifecycleState', upper(NEW.lifecycle_state),
            'loginAuthModes', expected_modes);
        IF before_state IS DISTINCT FROM expected_before_state
            OR after_state IS DISTINCT FROM expected_after_state
            OR event_payload->'accountState' IS DISTINCT FROM after_state THEN
            RAISE EXCEPTION 'V79 security-state receipt does not bind the exact original and current Account states'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
        END IF;

        IF before_state->'emailVerified' IS DISTINCT FROM after_state->'emailVerified' THEN
            changed_kinds := array_append(changed_kinds, 'EMAIL_LOGIN_ELIGIBILITY_CHANGED');
        END IF;
        IF before_state->'globalRoles' IS DISTINCT FROM after_state->'globalRoles' THEN
            changed_kinds := array_append(changed_kinds, 'GLOBAL_ROLE_CHANGED');
        END IF;
        IF before_state->'lifecycleState' IS DISTINCT FROM after_state->'lifecycleState' THEN
            changed_kinds := array_append(changed_kinds, 'LIFECYCLE_STATE_CHANGED');
        END IF;
        IF before_state->'loginAuthModes' IS DISTINCT FROM after_state->'loginAuthModes' THEN
            changed_kinds := array_append(changed_kinds, 'LOGIN_AUTH_MODES_CHANGED');
        END IF;
        IF cardinality(changed_kinds) = 0
            OR changed_kinds IS DISTINCT FROM operation_row.mutation_kinds
            OR changed_kinds IS DISTINCT FROM ARRAY(
                SELECT jsonb_array_elements_text(event_payload->'mutationKinds')) THEN
            RAISE EXCEPTION 'V79 security-state receipt mutation families differ from the original states'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
        END IF;
        RETURN NULL;
    END IF;

    -- Keep the legacy Account-auth event checks and mutation-family interpretation unchanged.
    SELECT source_record.* INTO source_row
        FROM account_authority_source_records source_record
        WHERE source_record.scope_kind = 'ACCOUNT'
          AND source_record.account_uuid = NEW.account_uuid;
    IF event_payload IS NULL
        OR event_payload->>'eventType' IS DISTINCT FROM 'ACCOUNT_AUTHORITY_CHANGED'
        OR event_payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR event_payload->>'accountAuthorityGeneration' IS DISTINCT FROM source_row.current_generation::TEXT
        OR event_payload->>'sourceVersion' IS DISTINCT FROM source_row.current_source_version::TEXT
        OR event_payload->>'issuanceFence' IS DISTINCT FROM source_row.current_issuance_fence::TEXT
        OR event_payload->>'issuanceFenceSourceVersion' IS DISTINCT FROM source_row.current_issuance_fence_source_version::TEXT
        OR event_payload#>>'{accountState,emailVerified}' IS DISTINCT FROM NEW.email_verified::TEXT
        OR event_payload#>'{accountState,loginAuthModes}' IS DISTINCT FROM expected_modes
        OR event_payload#>>'{accountState,globalRole}' IS DISTINCT FROM NEW.role
        OR event_payload#>>'{accountState,lifecycleState}' IS DISTINCT FROM upper(NEW.lifecycle_state)
        OR (OLD.password_hash IS DISTINCT FROM NEW.password_hash
            AND NOT (event_payload->'mutationKinds' ? 'PASSWORD_RESET'))
        OR (OLD.password_hash IS NOT DISTINCT FROM NEW.password_hash
            AND (event_payload->'mutationKinds' ? 'PASSWORD_RESET'))
        OR (OLD.email_verified IS DISTINCT FROM NEW.email_verified
            AND NOT (event_payload->'mutationKinds' ? 'EMAIL_LOGIN_ELIGIBILITY_CHANGED'))
        OR (OLD.email_verified IS NOT DISTINCT FROM NEW.email_verified
            AND (event_payload->'mutationKinds' ? 'EMAIL_LOGIN_ELIGIBILITY_CHANGED'))
        OR (OLD.login_auth_modes IS DISTINCT FROM NEW.login_auth_modes
            AND NOT (event_payload->'mutationKinds' ? 'LOGIN_AUTH_MODES_CHANGED'))
        OR (OLD.login_auth_modes IS NOT DISTINCT FROM NEW.login_auth_modes
            AND (event_payload->'mutationKinds' ? 'LOGIN_AUTH_MODES_CHANGED'))
        OR (OLD.role IS DISTINCT FROM NEW.role
            AND NOT (event_payload->'mutationKinds' ? 'GLOBAL_ROLE_CHANGED'))
        OR (OLD.role IS NOT DISTINCT FROM NEW.role
            AND (event_payload->'mutationKinds' ? 'GLOBAL_ROLE_CHANGED'))
        OR (OLD.lifecycle_state IS DISTINCT FROM NEW.lifecycle_state
            AND NOT (event_payload->'mutationKinds' ? 'LIFECYCLE_STATE_CHANGED'))
        OR (OLD.lifecycle_state IS NOT DISTINCT FROM NEW.lifecycle_state
            AND (event_payload->'mutationKinds' ? 'LIFECYCLE_STATE_CHANGED')) THEN
        RAISE EXCEPTION 'Account security mutation differs from its exact current source event'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
    END IF;
    RETURN NULL;
END;
$$;
-- [jooq ignore stop]
