-- V89 persists exact password-reset source intent while V76 owner outcomes settle.
CREATE TABLE account_password_reset_draft_source_changes (
    token_hash BYTEA NOT NULL CHECK (octet_length(token_hash) = 32),
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    account_uuid_provenance VARCHAR(40) NOT NULL,
    account_uuid_source_numeric_id BIGINT NOT NULL CHECK (account_uuid_source_numeric_id > 0),
    token_id BIGINT NOT NULL CHECK (token_id > 0),
    request_id VARCHAR(128) NOT NULL UNIQUE,
    event_id VARCHAR(128) NOT NULL UNIQUE,
    request_digest_version INTEGER NOT NULL CHECK (request_digest_version = 1),
    request_digest BYTEA NOT NULL CHECK (octet_length(request_digest) = 32),
    password_verifier VARCHAR(255) CHECK (password_verifier IS NULL OR length(btrim(password_verifier)) > 0),
    password_verifier_digest BYTEA NOT NULL CHECK (octet_length(password_verifier_digest) = 32),
    token_expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    token_expires_epoch_nanos BIGINT NOT NULL CHECK (token_expires_epoch_nanos > 0),
    expected_generation BIGINT NOT NULL CHECK (expected_generation > 0),
    expected_source_version BIGINT NOT NULL CHECK (expected_source_version > 0),
    expected_issuance_fence BIGINT NOT NULL CHECK (expected_issuance_fence > 0),
    expected_issuance_fence_source_version BIGINT NOT NULL
        CHECK (expected_issuance_fence_source_version > 0),
    checkpoint_stream VARCHAR(2048) NOT NULL,
    checkpoint_sequence BIGINT NOT NULL CHECK (checkpoint_sequence >= 0),
    capture_evidence BYTEA NOT NULL CHECK (octet_length(capture_evidence) > 0),
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) > 0),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) > 0),
    source_change_id UUID NOT NULL UNIQUE
        REFERENCES account_draft_authorization_source_changes(change_id)
        CHECK (source_change_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    source_change_request BYTEA NOT NULL CHECK (octet_length(source_change_request) > 0),
    source_change_binding BYTEA NOT NULL CHECK (octet_length(source_change_binding) > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('WAITING', 'SOURCE_COMMITTED', 'SOURCE_ABORTED')),
    abort_reason VARCHAR(32) CHECK (abort_reason IN ('EXPIRED', 'DEFINITIVE_ABORT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    aborted_at TIMESTAMPTZ,
    event_stream VARCHAR(2048),
    event_sequence BIGINT CHECK (event_sequence > 0),
    event_digest VARCHAR(71),
    event_payload BYTEA,
    CONSTRAINT account_password_reset_draft_identity_check
        CHECK (account_id > 0 AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND account_uuid_source_numeric_id = account_id
            AND request_id = 'account-password-reset-request-v1:' || encode(token_hash, 'hex')
            AND event_id = 'account-password-reset-event-v1:' || encode(token_hash, 'hex')),
    CONSTRAINT account_password_reset_draft_scope_check
        CHECK (checkpoint_stream = 'account:auth-authority:v1:account/' || account_uuid::TEXT),
    CONSTRAINT account_password_reset_draft_status_check
        CHECK ((status = 'WAITING' AND abort_reason IS NULL AND committed_at IS NULL
                AND aborted_at IS NULL AND event_stream IS NULL AND event_sequence IS NULL
                AND event_digest IS NULL AND event_payload IS NULL AND password_verifier IS NOT NULL)
            OR (status = 'SOURCE_COMMITTED' AND abort_reason IS NULL AND committed_at IS NOT NULL
                AND aborted_at IS NULL AND event_stream IS NOT NULL AND event_sequence IS NOT NULL
                AND event_digest ~ '^sha256:[0-9a-f]{64}$'
                AND event_payload IS NOT NULL AND octet_length(event_payload) > 0
                AND password_verifier IS NULL)
            OR (status = 'SOURCE_ABORTED' AND abort_reason IS NOT NULL AND committed_at IS NULL
                AND aborted_at IS NOT NULL AND event_stream IS NULL AND event_sequence IS NULL
                AND event_digest IS NULL AND event_payload IS NULL AND password_verifier IS NULL))
);

-- [jooq ignore start]
-- PostgreSQL supports the BYTEA key; jOOQ's H2 DDL interpreter cannot index its BLOB mapping.
ALTER TABLE account_password_reset_draft_source_changes
    ADD CONSTRAINT account_password_reset_draft_source_changes_pk PRIMARY KEY (token_hash);

CREATE FUNCTION account_password_reset_draft_frame(value BYTEA)
RETURNS BYTEA LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT int4send(octet_length(value)) || value;
$$;

CREATE FUNCTION account_password_reset_draft_journal_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    expected_request BYTEA;
    expected_mutation BYTEA;
    expected_evidence BYTEA;
    expected_baseline BYTEA;
    expected_change BYTEA;
    expected_key TEXT;
    persisted_uuid UUID;
    persisted_provenance TEXT;
    persisted_source_id BIGINT;
    token_account BIGINT;
    token_deadline TIMESTAMP WITHOUT TIME ZONE;
    current_generation BIGINT;
    current_source_version BIGINT;
    current_fence BIGINT;
    current_fence_source_version BIGINT;
    current_sequence BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'WAITING' OR NEW.status NOT IN ('SOURCE_COMMITTED', 'SOURCE_ABORTED')
            OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
            OR NEW.account_id IS DISTINCT FROM OLD.account_id
            OR NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
            OR NEW.account_uuid_provenance IS DISTINCT FROM OLD.account_uuid_provenance
            OR NEW.account_uuid_source_numeric_id IS DISTINCT FROM OLD.account_uuid_source_numeric_id
            OR NEW.token_id IS DISTINCT FROM OLD.token_id
            OR NEW.request_id IS DISTINCT FROM OLD.request_id
            OR NEW.event_id IS DISTINCT FROM OLD.event_id
            OR NEW.request_digest_version IS DISTINCT FROM OLD.request_digest_version
            OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
            OR NEW.password_verifier IS DISTINCT FROM OLD.password_verifier
            OR NEW.password_verifier_digest IS DISTINCT FROM OLD.password_verifier_digest
            OR NEW.token_expires_at IS DISTINCT FROM OLD.token_expires_at
            OR NEW.token_expires_epoch_nanos IS DISTINCT FROM OLD.token_expires_epoch_nanos
            OR NEW.expected_generation IS DISTINCT FROM OLD.expected_generation
            OR NEW.expected_source_version IS DISTINCT FROM OLD.expected_source_version
            OR NEW.expected_issuance_fence IS DISTINCT FROM OLD.expected_issuance_fence
            OR NEW.expected_issuance_fence_source_version IS DISTINCT FROM OLD.expected_issuance_fence_source_version
            OR NEW.checkpoint_stream IS DISTINCT FROM OLD.checkpoint_stream
            OR NEW.checkpoint_sequence IS DISTINCT FROM OLD.checkpoint_sequence
            OR NEW.capture_evidence IS DISTINCT FROM OLD.capture_evidence
            OR NEW.request_payload IS DISTINCT FROM OLD.request_payload
            OR NEW.source_evidence IS DISTINCT FROM OLD.source_evidence
            OR NEW.source_change_id IS DISTINCT FROM OLD.source_change_id
            OR NEW.source_change_request IS DISTINCT FROM OLD.source_change_request
            OR NEW.source_change_binding IS DISTINCT FROM OLD.source_change_binding
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Password-reset Draft source intent and original capture are immutable'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.status = 'SOURCE_COMMITTED' THEN
            IF NEW.abort_reason IS NOT NULL OR NEW.aborted_at IS NOT NULL
                OR NEW.event_stream IS NULL OR NEW.event_sequence IS NULL
                OR NEW.event_id IS DISTINCT FROM OLD.event_id
                OR NEW.event_digest IS NULL OR NEW.event_payload IS NULL THEN
                RAISE EXCEPTION 'Committed password-reset source result is incomplete'
                    USING ERRCODE = '23514';
            END IF;
            NEW.committed_at = CURRENT_TIMESTAMP;
        ELSE
            IF NEW.abort_reason NOT IN ('EXPIRED', 'DEFINITIVE_ABORT')
                OR NEW.event_stream IS NOT NULL OR NEW.event_sequence IS NOT NULL
                OR NEW.event_digest IS NOT NULL OR NEW.event_payload IS NOT NULL THEN
                RAISE EXCEPTION 'Aborted password-reset source result must contain no mutation'
                    USING ERRCODE = '23514';
            END IF;
            NEW.aborted_at = CURRENT_TIMESTAMP;
        END IF;
        NEW.password_verifier = NULL;
        RETURN NEW;
    END IF;

    IF NEW.status <> 'WAITING' OR NEW.abort_reason IS NOT NULL
        OR NEW.committed_at IS NOT NULL OR NEW.aborted_at IS NOT NULL
        OR NEW.event_stream IS NOT NULL OR NEW.event_sequence IS NOT NULL
        OR NEW.event_digest IS NOT NULL OR NEW.event_payload IS NOT NULL THEN
        RAISE EXCEPTION 'Password-reset Draft source intent must begin WAITING'
            USING ERRCODE = '23514';
    END IF;

    SELECT account_uuid, account_uuid_provenance, account_uuid_source_numeric_id
        INTO persisted_uuid, persisted_provenance, persisted_source_id
        FROM accounts WHERE id = NEW.account_id;
    IF persisted_uuid IS DISTINCT FROM NEW.account_uuid
        OR persisted_provenance IS DISTINCT FROM NEW.account_uuid_provenance
        OR persisted_source_id IS DISTINCT FROM NEW.account_uuid_source_numeric_id
        OR NEW.account_uuid_source_numeric_id IS DISTINCT FROM NEW.account_id THEN
        RAISE EXCEPTION 'Password-reset Draft intent Account identity differs from persisted source'
            USING ERRCODE = '23514';
    END IF;
    SELECT account_id, expires_at INTO token_account, token_deadline
        FROM password_reset_token WHERE id = NEW.token_id;
    IF token_account IS DISTINCT FROM NEW.account_id OR token_deadline IS DISTINCT FROM NEW.token_expires_at
        OR NEW.token_expires_epoch_nanos IS DISTINCT FROM
            ((extract(epoch FROM (NEW.token_expires_at AT TIME ZONE 'UTC')) * 1000000000)::numeric)::bigint THEN
        RAISE EXCEPTION 'Password-reset Draft intent does not bind its exact token row and deadline'
            USING ERRCODE = '23514';
    END IF;

    expected_request := account_password_reset_draft_frame(convert_to('account-password-reset-draft-source-request/v1', 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.request_id, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.account_id::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.account_uuid::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.token_id::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(NEW.token_hash)
        || account_password_reset_draft_frame(convert_to(NEW.token_expires_epoch_nanos::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to('1', 'UTF8'))
        || account_password_reset_draft_frame(NEW.request_digest)
        || account_password_reset_draft_frame(NEW.password_verifier_digest)
        || account_password_reset_draft_frame(convert_to(NEW.account_uuid_provenance, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.account_uuid_source_numeric_id::TEXT, 'UTF8'));
    expected_evidence := account_password_reset_draft_frame(convert_to('account-draft-source-evidence/v1', 'UTF8'))
        || account_password_reset_draft_frame(convert_to('ACCOUNT', 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.account_uuid::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to('PRESENT', 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.expected_generation::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.expected_source_version::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to('PRESENT', 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.checkpoint_stream, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.checkpoint_sequence::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(NEW.capture_evidence);
    expected_mutation := account_password_reset_draft_frame(convert_to('account-password-reset-draft-source-mutation/v1', 'UTF8'))
        || account_password_reset_draft_frame(NEW.request_payload)
        || account_password_reset_draft_frame(convert_to(NEW.expected_generation::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.expected_source_version::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.expected_issuance_fence::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.expected_issuance_fence_source_version::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.checkpoint_sequence::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(NEW.source_evidence)
        || account_password_reset_draft_frame(convert_to((NEW.expected_generation + 1)::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to((NEW.expected_source_version + 1)::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to((NEW.expected_issuance_fence + 1)::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to((NEW.expected_issuance_fence_source_version + 1)::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to((NEW.checkpoint_sequence + 1)::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.request_id, 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.event_id, 'UTF8'));
    expected_change := account_password_reset_draft_frame(convert_to('account-draft-source-change/v1', 'UTF8'))
        || account_password_reset_draft_frame(convert_to(NEW.source_change_id::TEXT, 'UTF8'))
        || account_password_reset_draft_frame(convert_to('1', 'UTF8'))
        || account_password_reset_draft_frame(NEW.source_evidence)
        || account_password_reset_draft_frame(NEW.source_change_request);
    expected_key := 'ACCOUNT:' || NEW.account_uuid::TEXT;
    IF NEW.request_id IS DISTINCT FROM 'account-password-reset-request-v1:' || encode(NEW.token_hash, 'hex')
        OR NEW.event_id IS DISTINCT FROM 'account-password-reset-event-v1:' || encode(NEW.token_hash, 'hex')
        OR NEW.checkpoint_stream IS DISTINCT FROM 'account:auth-authority:v1:account/' || NEW.account_uuid::TEXT
        OR NEW.request_payload IS DISTINCT FROM expected_request
        OR NEW.source_evidence IS DISTINCT FROM expected_evidence
        OR NEW.source_change_request IS DISTINCT FROM expected_mutation
        OR NEW.source_change_binding IS DISTINCT FROM expected_change
        OR NOT EXISTS (SELECT 1 FROM account_draft_authorization_source_changes
            WHERE change_id = NEW.source_change_id AND status = 'WAITING' AND binding = expected_change)
        OR (SELECT count(*) FROM account_draft_authorization_changed_scopes
            WHERE change_id = NEW.source_change_id) <> 1
        OR NOT EXISTS (SELECT 1 FROM account_draft_authorization_changed_scopes
            WHERE change_id = NEW.source_change_id AND source_key = expected_key) THEN
        RAISE EXCEPTION 'Password-reset request must bind its exact V76 Account source intent'
            USING ERRCODE = '23514';
    END IF;

    SELECT generation, source_version INTO current_generation, current_source_version
        FROM account_authority_generations
        WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid;
    SELECT issuance_fence, source_version INTO current_fence, current_fence_source_version
        FROM account_authority_issuance_fences WHERE account_uuid = NEW.account_uuid;
    IF current_generation IS DISTINCT FROM NEW.expected_generation
        OR current_source_version IS DISTINCT FROM NEW.expected_source_version
        OR current_fence IS DISTINCT FROM NEW.expected_issuance_fence
        OR current_fence_source_version IS DISTINCT FROM NEW.expected_issuance_fence_source_version THEN
        RAISE EXCEPTION 'Password-reset Draft capture differs from current Account source counters'
            USING ERRCODE = '23514';
    END IF;
    SELECT last_sequence INTO current_sequence FROM account_authority_outbox_streams
        WHERE outbox_stream_key = NEW.checkpoint_stream;
    IF NEW.checkpoint_sequence = 0 THEN
        expected_baseline := account_password_reset_draft_frame(convert_to('account-draft-source-baseline/v1', 'UTF8'))
            || account_password_reset_draft_frame(convert_to(NEW.account_uuid::TEXT, 'UTF8'))
            || account_password_reset_draft_frame(convert_to('1', 'UTF8'))
            || account_password_reset_draft_frame(convert_to('1', 'UTF8'))
            || account_password_reset_draft_frame(convert_to(NEW.checkpoint_stream, 'UTF8'))
            || account_password_reset_draft_frame(convert_to('0', 'UTF8'));
        IF NEW.expected_generation <> 1 OR NEW.expected_source_version <> 1
            OR NEW.capture_evidence IS DISTINCT FROM expected_baseline
            OR (current_sequence IS NOT NULL AND current_sequence <> 0)
            OR EXISTS (SELECT 1 FROM account_authority_outbox_events
                WHERE outbox_stream_key = NEW.checkpoint_stream) THEN
            RAISE EXCEPTION 'Password-reset zero checkpoint lacks exact pristine source evidence'
                USING ERRCODE = '23514';
        END IF;
    ELSIF current_sequence IS DISTINCT FROM NEW.checkpoint_sequence
        OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_events
            WHERE outbox_stream_key = NEW.checkpoint_stream
                AND outbox_sequence = NEW.checkpoint_sequence
                AND payload = NEW.capture_evidence) THEN
        RAISE EXCEPTION 'Password-reset capture does not bind the exact current Account event'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_password_reset_draft_link_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal account_password_reset_draft_source_changes%ROWTYPE;
    source_status TEXT;
    source_binding BYTEA;
    selected_change UUID;
    persisted_generation BIGINT;
    persisted_source_version BIGINT;
    persisted_fence BIGINT;
    persisted_fence_source_version BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'account_password_reset_draft_source_changes' THEN
        selected_change := NEW.source_change_id;
    ELSE
        selected_change := NEW.change_id;
    END IF;
    SELECT * INTO journal FROM account_password_reset_draft_source_changes
        WHERE source_change_id = selected_change;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT status, binding INTO source_status, source_binding
        FROM account_draft_authorization_source_changes WHERE change_id = selected_change;
    IF source_status IS DISTINCT FROM journal.status OR source_binding IS DISTINCT FROM journal.source_change_binding THEN
        RAISE EXCEPTION 'Password-reset journal and V76 source transition must commit together'
            USING ERRCODE = '23514';
    END IF;
    IF journal.status = 'WAITING' AND (
        EXISTS (SELECT 1 FROM account_password_reset_operation_receipts WHERE token_hash = journal.token_hash)
        OR EXISTS (SELECT 1 FROM account_authority_outbox_events
            WHERE outbox_stream_key = journal.checkpoint_stream AND request_id = journal.request_id)) THEN
        RAISE EXCEPTION 'WAITING password-reset intent cannot publish or complete its receipt'
            USING ERRCODE = '23514';
    END IF;
    IF journal.status = 'SOURCE_ABORTED' AND (
        EXISTS (SELECT 1 FROM account_password_reset_operation_receipts WHERE token_hash = journal.token_hash)
        OR EXISTS (SELECT 1 FROM account_authority_outbox_events
            WHERE outbox_stream_key = journal.checkpoint_stream AND request_id = journal.request_id)) THEN
        RAISE EXCEPTION 'Aborted password-reset intent cannot contain a source mutation'
            USING ERRCODE = '23514';
    END IF;
    IF journal.status = 'SOURCE_COMMITTED' AND (
        journal.event_stream IS DISTINCT FROM journal.checkpoint_stream
        OR journal.event_id IS DISTINCT FROM 'account-password-reset-event-v1:' || encode(journal.token_hash, 'hex')
        OR journal.event_sequence IS DISTINCT FROM journal.checkpoint_sequence + 1
        OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_events
            WHERE outbox_stream_key = journal.event_stream AND outbox_sequence = journal.event_sequence
                AND request_id = journal.request_id AND event_id = journal.event_id
                AND event_digest = journal.event_digest AND payload = journal.event_payload)
        OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_streams
            WHERE outbox_stream_key = journal.event_stream AND last_sequence = journal.event_sequence)
        OR NOT EXISTS (SELECT 1 FROM account_password_reset_operation_receipts
            WHERE token_hash = journal.token_hash AND account_id = journal.account_id
                AND account_uuid = journal.account_uuid AND request_id = journal.request_id
                AND request_digest_version = journal.request_digest_version
                AND request_digest = journal.request_digest
                AND token_expires_at = journal.token_expires_at
                AND password_verifier_digest = journal.password_verifier_digest
                AND outbox_stream_key = journal.event_stream AND outbox_sequence = journal.event_sequence
                AND event_id = journal.event_id AND event_digest = journal.event_digest
                AND account_authority_generation = journal.expected_generation + 1
                AND account_source_version = journal.expected_source_version + 1
                AND issuance_fence = journal.expected_issuance_fence + 1
                AND issuance_fence_source_version = journal.expected_issuance_fence_source_version + 1)
        OR EXISTS (SELECT 1 FROM password_reset_token WHERE id = journal.token_id)
        OR NOT EXISTS (SELECT 1 FROM accounts WHERE id = journal.account_id
            AND account_uuid = journal.account_uuid
            AND sha256(convert_to(password_hash, 'UTF8')) = journal.password_verifier_digest)) THEN
        RAISE EXCEPTION 'Committed password-reset intent lacks exact receipt, event, token, or Account readback'
            USING ERRCODE = '23514';
    END IF;
    IF journal.status = 'SOURCE_COMMITTED' THEN
        SELECT generation, source_version INTO persisted_generation, persisted_source_version
            FROM account_authority_generations
            WHERE scope_kind = 'ACCOUNT' AND account_uuid = journal.account_uuid;
        SELECT issuance_fence, source_version INTO persisted_fence, persisted_fence_source_version
            FROM account_authority_issuance_fences WHERE account_uuid = journal.account_uuid;
        IF persisted_generation IS DISTINCT FROM journal.expected_generation + 1
            OR persisted_source_version IS DISTINCT FROM journal.expected_source_version + 1
            OR persisted_fence IS DISTINCT FROM journal.expected_issuance_fence + 1
            OR persisted_fence_source_version IS DISTINCT FROM journal.expected_issuance_fence_source_version + 1 THEN
            RAISE EXCEPTION 'Password-reset Account authority counters did not advance once from capture'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_password_reset_draft_journal_guard
    BEFORE INSERT OR UPDATE ON account_password_reset_draft_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_password_reset_draft_journal_guard();
CREATE TRIGGER account_password_reset_draft_delete_guard
    BEFORE DELETE ON account_password_reset_draft_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_password_reset_draft_truncate_guard
    BEFORE TRUNCATE ON account_password_reset_draft_source_changes
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE CONSTRAINT TRIGGER account_password_reset_draft_complete_guard
    AFTER INSERT OR UPDATE ON account_password_reset_draft_source_changes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_password_reset_draft_link_guard();
CREATE CONSTRAINT TRIGGER account_password_reset_draft_v76_link_guard
    AFTER INSERT OR UPDATE ON account_draft_authorization_source_changes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_password_reset_draft_link_guard();
-- [jooq ignore stop]
