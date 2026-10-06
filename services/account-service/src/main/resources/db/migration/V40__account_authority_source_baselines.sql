-- Explicit generation-one/sequence-zero provenance for fresh issuer and Account scopes.
-- Retained generations are deliberately not backfilled into this table.
ALTER TABLE account_authority_generations
    ADD COLUMN created_transaction_id BIGINT NOT NULL DEFAULT txid_current();
ALTER TABLE accounts
    ADD COLUMN account_repository_insert_transaction_id BIGINT;

CREATE TABLE account_authority_source_records (
    outbox_stream_key VARCHAR(2048) PRIMARY KEY
        REFERENCES account_authority_outbox_streams (outbox_stream_key) ON DELETE RESTRICT,
    scope_kind VARCHAR(16) NOT NULL,
    issuer_id VARCHAR(512),
    account_uuid UUID,
    initialization_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    baseline_generation BIGINT NOT NULL DEFAULT 1,
    baseline_source_version BIGINT NOT NULL DEFAULT 1,
    baseline_issuance_fence BIGINT,
    initialization_provenance VARCHAR(48) NOT NULL,
    account_source_numeric_id BIGINT,
    account_uuid_provenance VARCHAR(40),
    account_repository_insert_transaction_id BIGINT,
    current_generation BIGINT NOT NULL,
    current_source_version BIGINT NOT NULL,
    current_issuance_fence BIGINT,
    current_issuance_fence_source_version BIGINT,
    last_outbox_sequence BIGINT NOT NULL DEFAULT 0,
    last_event_id VARCHAR(512),
    last_event_digest VARCHAR(512),
    cutoff_generation BIGINT,
    cutoff_stream_key VARCHAR(2048),
    cutoff_sequence BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_authority_source_scope_check CHECK (
        (scope_kind = 'ISSUER'
            AND issuer_id IS NOT NULL AND issuer_id <> ''
            AND account_uuid IS NULL
            AND baseline_issuance_fence IS NULL
            AND current_issuance_fence IS NULL
            AND current_issuance_fence_source_version IS NULL
            AND account_source_numeric_id IS NULL
            AND account_uuid_provenance IS NULL
            AND account_repository_insert_transaction_id IS NULL
            AND initialization_provenance = 'ISSUER_SCOPE_INSERT'
            AND cutoff_generation IS NULL AND cutoff_stream_key IS NULL AND cutoff_sequence IS NULL
            AND outbox_stream_key = 'account:auth-authority:v1:issuer/' || issuer_id)
        OR (scope_kind = 'ACCOUNT'
            AND issuer_id IS NULL AND account_uuid IS NOT NULL
            AND baseline_issuance_fence = 1
            AND current_issuance_fence IS NOT NULL
            AND current_issuance_fence_source_version IS NOT NULL
            AND account_source_numeric_id > 0
            AND account_uuid_provenance = 'ACCOUNT_REPOSITORY_INSERT'
            AND account_repository_insert_transaction_id > 0
            AND initialization_provenance = 'ACCOUNT_REPOSITORY_INSERT'
            AND outbox_stream_key = 'account:auth-authority:v1:account/' || account_uuid::TEXT)
    ),
    CONSTRAINT account_authority_source_baseline_check CHECK (
        baseline_generation = 1 AND baseline_source_version = 1
        AND (scope_kind <> 'ACCOUNT' OR account_source_numeric_id > 0)
    ),
    CONSTRAINT account_authority_source_current_check CHECK (
        current_generation > 0 AND current_source_version > 0
        AND last_outbox_sequence >= 0
        AND current_generation = baseline_generation + last_outbox_sequence
        AND current_source_version = baseline_source_version + last_outbox_sequence
        AND (scope_kind <> 'ACCOUNT' OR (
            current_issuance_fence > 0
            AND current_issuance_fence_source_version > 0
            AND current_issuance_fence = baseline_issuance_fence + last_outbox_sequence
            AND current_issuance_fence_source_version = baseline_issuance_fence + last_outbox_sequence))
    ),
    CONSTRAINT account_authority_source_head_check CHECK (
        (last_outbox_sequence = 0 AND last_event_id IS NULL AND last_event_digest IS NULL)
        OR (last_outbox_sequence > 0
            AND last_event_id IS NOT NULL AND length(btrim(last_event_id)) > 0
            AND last_event_digest IS NOT NULL AND last_event_digest ~ '^sha256:[0-9a-f]{64}$')
    ),
    CONSTRAINT account_authority_source_cutoff_check CHECK (
        (scope_kind = 'ISSUER'
            AND cutoff_generation IS NULL AND cutoff_stream_key IS NULL AND cutoff_sequence IS NULL)
        OR (scope_kind = 'ACCOUNT'
            AND ((last_outbox_sequence = 0
                    AND cutoff_generation IS NULL AND cutoff_stream_key IS NULL AND cutoff_sequence IS NULL)
                OR (last_outbox_sequence > 0
                    AND cutoff_generation = current_generation
                    AND cutoff_stream_key = outbox_stream_key
                    AND cutoff_sequence = last_outbox_sequence)))
    ),
    CONSTRAINT account_authority_source_account_fk
        FOREIGN KEY (account_uuid) REFERENCES accounts (account_uuid) ON DELETE RESTRICT
);

-- [jooq ignore start]
CREATE FUNCTION account_authority_source_record_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    generation_row RECORD;
    fence_row RECORD;
    account_row RECORD;
    stream_sequence BIGINT;
BEGIN
    SELECT generation, source_version, created_transaction_id INTO generation_row
        FROM account_authority_generations
        WHERE scope_kind = NEW.scope_kind
          AND issuer_id IS NOT DISTINCT FROM NEW.issuer_id
          AND account_uuid IS NOT DISTINCT FROM NEW.account_uuid
          AND tenant_uuid IS NULL;
    SELECT last_sequence INTO stream_sequence
        FROM account_authority_outbox_streams
        WHERE outbox_stream_key = NEW.outbox_stream_key;
    IF generation_row.generation IS DISTINCT FROM 1
        OR generation_row.source_version IS DISTINCT FROM 1
        OR generation_row.created_transaction_id IS DISTINCT FROM txid_current()
        OR NEW.initialization_transaction_id IS DISTINCT FROM generation_row.created_transaction_id
        OR stream_sequence IS DISTINCT FROM 0
        OR EXISTS (SELECT 1 FROM account_authority_outbox_events
                   WHERE outbox_stream_key = NEW.outbox_stream_key)
        OR NEW.current_generation IS DISTINCT FROM 1
        OR NEW.current_source_version IS DISTINCT FROM 1
        OR NEW.last_outbox_sequence IS DISTINCT FROM 0
        OR NEW.last_event_id IS NOT NULL OR NEW.last_event_digest IS NOT NULL
        OR NEW.cutoff_generation IS NOT NULL OR NEW.cutoff_stream_key IS NOT NULL
        OR NEW.cutoff_sequence IS NOT NULL THEN
        RAISE EXCEPTION 'Account authority source baseline is not a fresh exact sequence-zero scope'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_baseline_fresh';
    END IF;
    IF NEW.scope_kind = 'ACCOUNT' THEN
        SELECT id, account_uuid_source_numeric_id, account_uuid_provenance,
               account_repository_insert_transaction_id
            INTO account_row FROM accounts WHERE account_uuid = NEW.account_uuid;
        SELECT issuance_fence, source_version INTO fence_row
            FROM account_authority_issuance_fences WHERE account_uuid = NEW.account_uuid;
        IF fence_row.issuance_fence IS DISTINCT FROM 1
            OR fence_row.source_version IS DISTINCT FROM 1
            OR account_row.id IS DISTINCT FROM NEW.account_source_numeric_id
            OR account_row.account_uuid_source_numeric_id IS DISTINCT FROM NEW.account_source_numeric_id
            OR account_row.account_uuid_provenance IS DISTINCT FROM 'ACCOUNT_REPOSITORY_INSERT'
            OR account_row.account_uuid_provenance IS DISTINCT FROM NEW.account_uuid_provenance
            OR account_row.account_repository_insert_transaction_id IS DISTINCT FROM txid_current()
            OR NEW.account_repository_insert_transaction_id
                IS DISTINCT FROM account_row.account_repository_insert_transaction_id THEN
            RAISE EXCEPTION 'Account source baseline is not bound to a fresh repository-created Account'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_account_identity';
        END IF;
        IF NEW.current_issuance_fence IS DISTINCT FROM 1
            OR NEW.current_issuance_fence_source_version IS DISTINCT FROM 1 THEN
            RAISE EXCEPTION 'Fresh Account source baseline requires the exact initial issuance fence'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_fence_baseline';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_authority_account_insert_transaction_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.account_uuid_provenance = 'ACCOUNT_REPOSITORY_INSERT' THEN
        NEW.account_repository_insert_transaction_id := txid_current();
    ELSE
        NEW.account_repository_insert_transaction_id := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_authority_account_insert_transaction
    BEFORE INSERT ON accounts
    FOR EACH ROW EXECUTE FUNCTION account_authority_account_insert_transaction_guard();

CREATE FUNCTION account_authority_account_transaction_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.account_repository_insert_transaction_id
        IS DISTINCT FROM OLD.account_repository_insert_transaction_id THEN
        RAISE EXCEPTION 'Account identity source transaction is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_transaction_immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_authority_account_transaction_immutable
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION account_authority_account_transaction_immutable_guard();

CREATE FUNCTION account_authority_generation_creation_transaction_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.created_transaction_id IS DISTINCT FROM OLD.created_transaction_id THEN
        RAISE EXCEPTION 'Account authority source birth transaction is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_generation_birth_immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_authority_generation_creation_transaction_immutable
    BEFORE UPDATE ON account_authority_generations
    FOR EACH ROW EXECUTE FUNCTION account_authority_generation_creation_transaction_immutable_guard();

CREATE FUNCTION account_authority_source_record_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    event_row RECORD;
    event_payload JSONB;
    account_row RECORD;
    expected_modes JSONB;
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
        OR event_payload->>'eventDigest' IS DISTINCT FROM NEW.last_event_digest
        OR (NEW.scope_kind = 'ISSUER' AND (
            event_payload->>'eventType' IS DISTINCT FROM 'ISSUER_AUTHORITY_CHANGED'
            OR event_payload->>'issuerId' IS DISTINCT FROM NEW.issuer_id
            OR event_payload->>'issuerAuthGeneration' IS DISTINCT FROM NEW.current_generation::TEXT
            OR event_payload->>'sourceVersion' IS DISTINCT FROM NEW.current_source_version::TEXT))
        OR (NEW.scope_kind = 'ACCOUNT' AND (
            event_payload->>'eventType' IS DISTINCT FROM 'ACCOUNT_AUTHORITY_CHANGED'
            OR event_payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
            OR event_payload->>'accountAuthorityGeneration' IS DISTINCT FROM NEW.current_generation::TEXT
            OR event_payload->>'sourceVersion' IS DISTINCT FROM NEW.current_source_version::TEXT
            OR event_payload->>'issuanceFence' IS DISTINCT FROM NEW.current_issuance_fence::TEXT
            OR event_payload->>'issuanceFenceSourceVersion' IS DISTINCT FROM NEW.current_issuance_fence_source_version::TEXT
            OR event_payload#>>'{accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM NEW.cutoff_generation::TEXT
            OR event_payload#>>'{accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM NEW.cutoff_stream_key
            OR event_payload#>>'{accountSecurityCutoff,outboxSequence}' IS DISTINCT FROM NEW.cutoff_sequence::TEXT)) THEN
        RAISE EXCEPTION 'Account authority source event does not match the exact advanced owner rows'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
    END IF;
    IF NEW.scope_kind = 'ACCOUNT' THEN
        SELECT id, account_uuid_provenance, account_uuid_source_numeric_id, email_verified,
               login_auth_modes, role, lifecycle_state
            INTO account_row FROM accounts WHERE account_uuid = NEW.account_uuid;
        SELECT COALESCE(jsonb_agg(to_jsonb(login_mode) ORDER BY login_mode), '[]'::JSONB)
            INTO expected_modes FROM unnest(string_to_array(account_row.login_auth_modes, ',')) AS modes(login_mode);
        IF account_row.id IS DISTINCT FROM NEW.account_source_numeric_id
            OR account_row.account_uuid_source_numeric_id IS DISTINCT FROM NEW.account_source_numeric_id
            OR account_row.account_uuid_provenance IS DISTINCT FROM NEW.account_uuid_provenance
            OR event_payload#>>'{accountState,emailVerified}' IS DISTINCT FROM account_row.email_verified::TEXT
            OR event_payload#>'{accountState,loginAuthModes}' IS DISTINCT FROM expected_modes
            OR event_payload#>>'{accountState,globalRole}' IS DISTINCT FROM account_row.role
            OR event_payload#>>'{accountState,lifecycleState}' IS DISTINCT FROM upper(account_row.lifecycle_state) THEN
            RAISE EXCEPTION 'Account source event does not match exact current Account state'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_account_state';
        END IF;
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_authority_source_record_delete_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account authority source provenance is retained and cannot be deleted'
        USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_record_no_delete';
    RETURN OLD;
END;
$$;

CREATE FUNCTION account_authority_source_record_truncate_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account authority source provenance cannot be truncated'
        USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_record_no_truncate';
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_authority_source_record_consistency_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    generation_row RECORD;
    fence_row RECORD;
    stream_sequence BIGINT;
    event_count BIGINT;
BEGIN
    SELECT generation, source_version INTO generation_row
        FROM account_authority_generations
        WHERE scope_kind = NEW.scope_kind
          AND issuer_id IS NOT DISTINCT FROM NEW.issuer_id
          AND account_uuid IS NOT DISTINCT FROM NEW.account_uuid
          AND tenant_uuid IS NULL;
    SELECT last_sequence INTO stream_sequence
        FROM account_authority_outbox_streams
        WHERE outbox_stream_key = NEW.outbox_stream_key;
    SELECT COUNT(*) INTO event_count FROM account_authority_outbox_events
        WHERE outbox_stream_key = NEW.outbox_stream_key;
    IF generation_row.generation IS DISTINCT FROM NEW.current_generation
        OR generation_row.source_version IS DISTINCT FROM NEW.current_source_version
        OR stream_sequence IS DISTINCT FROM NEW.last_outbox_sequence
        OR event_count IS DISTINCT FROM NEW.last_outbox_sequence THEN
        RAISE EXCEPTION 'Account authority source record does not match committed generation and outbox history'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_current_consistent';
    END IF;
    IF NEW.scope_kind = 'ACCOUNT' THEN
        SELECT issuance_fence, source_version INTO fence_row
            FROM account_authority_issuance_fences WHERE account_uuid = NEW.account_uuid;
        IF fence_row.issuance_fence IS DISTINCT FROM NEW.current_issuance_fence
            OR fence_row.source_version IS DISTINCT FROM NEW.current_issuance_fence_source_version THEN
            RAISE EXCEPTION 'Account authority source record does not match the current issuance fence'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_fence_consistent';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_authority_source_record_insert
    BEFORE INSERT ON account_authority_source_records
    FOR EACH ROW EXECUTE FUNCTION account_authority_source_record_insert_guard();
CREATE TRIGGER account_authority_source_record_update
    BEFORE UPDATE ON account_authority_source_records
    FOR EACH ROW EXECUTE FUNCTION account_authority_source_record_update_guard();
CREATE TRIGGER account_authority_source_record_delete
    BEFORE DELETE ON account_authority_source_records
    FOR EACH ROW EXECUTE FUNCTION account_authority_source_record_delete_guard();
CREATE TRIGGER account_authority_source_record_truncate
    BEFORE TRUNCATE ON account_authority_source_records
    FOR EACH STATEMENT EXECUTE FUNCTION account_authority_source_record_truncate_guard();
CREATE CONSTRAINT TRIGGER account_authority_source_record_consistent
    AFTER INSERT OR UPDATE ON account_authority_source_records
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_authority_source_record_consistency_guard();

CREATE FUNCTION account_authority_account_security_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_row RECORD;
    event_payload JSONB;
    expected_modes JSONB;
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

    SELECT convert_from(payload, 'UTF8')::JSONB INTO event_payload
        FROM account_authority_outbox_events
        WHERE outbox_stream_key = source_row.outbox_stream_key
          AND outbox_sequence = source_row.last_outbox_sequence
          AND event_id = source_row.last_event_id
          AND event_digest = source_row.last_event_digest;
    SELECT COALESCE(jsonb_agg(to_jsonb(login_mode) ORDER BY login_mode), '[]'::JSONB)
        INTO expected_modes FROM unnest(string_to_array(NEW.login_auth_modes, ',')) AS modes(login_mode);

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

CREATE CONSTRAINT TRIGGER account_authority_account_security_update_consistent
    AFTER UPDATE ON accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_authority_account_security_update_guard();

CREATE FUNCTION account_authority_source_generation_consistency_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_row RECORD;
    expected_stream_key TEXT;
BEGIN
    IF NEW.scope_kind NOT IN ('ISSUER', 'ACCOUNT') THEN
        RETURN NULL;
    END IF;
    expected_stream_key := CASE NEW.scope_kind
        WHEN 'ISSUER' THEN 'account:auth-authority:v1:issuer/' || NEW.issuer_id
        ELSE 'account:auth-authority:v1:account/' || NEW.account_uuid::TEXT
    END;
    SELECT * INTO source_row FROM account_authority_source_records
        WHERE outbox_stream_key = expected_stream_key;
    IF source_row.outbox_stream_key IS NULL
        OR source_row.scope_kind IS DISTINCT FROM NEW.scope_kind
        OR source_row.current_generation IS DISTINCT FROM NEW.generation
        OR source_row.current_source_version IS DISTINCT FROM NEW.source_version THEN
        RAISE EXCEPTION 'Issuer or Account generation advance lacks exact owner source evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_generation_required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER account_authority_source_generation_consistent
    AFTER INSERT OR UPDATE ON account_authority_generations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_authority_source_generation_consistency_guard();

CREATE FUNCTION account_authority_source_fence_consistency_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_row RECORD;
    expected_stream_key TEXT;
BEGIN
    expected_stream_key := 'account:auth-authority:v1:account/' || NEW.account_uuid::TEXT;
    SELECT * INTO source_row FROM account_authority_source_records
        WHERE outbox_stream_key = expected_stream_key;
    IF source_row.outbox_stream_key IS NULL
        OR source_row.scope_kind IS DISTINCT FROM 'ACCOUNT'
        OR source_row.current_issuance_fence IS DISTINCT FROM NEW.issuance_fence
        OR source_row.current_issuance_fence_source_version IS DISTINCT FROM NEW.source_version THEN
        RAISE EXCEPTION 'Account issuance fence advance lacks exact owner source evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_fence_required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER account_authority_source_fence_consistent
    AFTER INSERT OR UPDATE ON account_authority_issuance_fences
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_authority_source_fence_consistency_guard();

-- Preserve positive-stream rules. Sequence zero is valid only for an exact Account/issuer
-- source row whose immutable gen-1/source-1 baseline and absence of events are committed.
CREATE OR REPLACE FUNCTION account_authority_outbox_stream_consistency_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    stream_sequence BIGINT;
    event_sequence BIGINT;
    source_row RECORD;
BEGIN
    SELECT last_sequence INTO stream_sequence
        FROM account_authority_outbox_streams
        WHERE outbox_stream_key = NEW.outbox_stream_key;
    SELECT COALESCE(MAX(outbox_sequence), 0) INTO event_sequence
        FROM account_authority_outbox_events
        WHERE outbox_stream_key = NEW.outbox_stream_key;
    IF stream_sequence IS NULL OR stream_sequence <> event_sequence THEN
        RAISE EXCEPTION 'Account authority outbox stream head must match committed event history'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_stream_consistent';
    END IF;
    IF stream_sequence = 0 THEN
        SELECT * INTO source_row FROM account_authority_source_records
            WHERE outbox_stream_key = NEW.outbox_stream_key;
        IF source_row.outbox_stream_key IS NULL
            OR source_row.scope_kind NOT IN ('ISSUER', 'ACCOUNT')
            OR source_row.baseline_generation <> 1
            OR source_row.baseline_source_version <> 1
            OR source_row.current_generation <> 1
            OR source_row.current_source_version <> 1
            OR source_row.last_outbox_sequence <> 0
            OR source_row.last_event_id IS NOT NULL
            OR source_row.last_event_digest IS NOT NULL
            OR source_row.cutoff_generation IS NOT NULL
            OR source_row.cutoff_stream_key IS NOT NULL
            OR source_row.cutoff_sequence IS NOT NULL THEN
            RAISE EXCEPTION 'Empty authority outbox stream lacks exact fresh source-baseline provenance'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_zero_baseline';
        END IF;
    ELSE
        IF stream_sequence < 1 THEN
            RAISE EXCEPTION 'Account authority outbox sequence is invalid'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_stream_consistent';
        END IF;
        SELECT * INTO source_row FROM account_authority_source_records
            WHERE outbox_stream_key = NEW.outbox_stream_key;
        IF (left(NEW.outbox_stream_key, length('account:auth-authority:v1:issuer/'))
                = 'account:auth-authority:v1:issuer/'
            OR left(NEW.outbox_stream_key, length('account:auth-authority:v1:account/'))
                = 'account:auth-authority:v1:account/')
            AND source_row.outbox_stream_key IS NULL THEN
            RAISE EXCEPTION 'Issuer or Account authority event stream lacks owner source provenance'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_source_history';
        END IF;
        IF source_row.outbox_stream_key IS NOT NULL
            AND (source_row.last_outbox_sequence <> stream_sequence
                OR source_row.current_generation <> source_row.baseline_generation + stream_sequence
                OR source_row.current_source_version <> source_row.baseline_source_version + stream_sequence) THEN
            RAISE EXCEPTION 'Account authority source event history differs from its owner record'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_source_history';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
-- [jooq ignore stop]
