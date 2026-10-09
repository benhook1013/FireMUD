-- Logout-all has a richer immutable caller binding than the issuer/tenant V59 journal.
CREATE TABLE account_logout_all_draft_source_changes (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    request_digest_version INTEGER NOT NULL CHECK (request_digest_version = 1),
    request_digest BYTEA NOT NULL CHECK (octet_length(request_digest) = 32),
    token_profile VARCHAR(32) NOT NULL CHECK (token_profile IN ('control-ui', 'player-bootstrap')),
    presented_token_hash BYTEA NOT NULL CHECK (octet_length(presented_token_hash) = 32),
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
        CHECK (source_change_id <> '00000000-0000-0000-0000-000000000000'),
    source_change_request BYTEA NOT NULL CHECK (octet_length(source_change_request) > 0),
    source_change_binding BYTEA NOT NULL CHECK (octet_length(source_change_binding) > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('WAITING', 'SOURCE_COMMITTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    event_stream VARCHAR(2048),
    event_sequence BIGINT CHECK (event_sequence > 0),
    event_id VARCHAR(128),
    event_digest VARCHAR(71),
    event_payload BYTEA,
    CONSTRAINT account_logout_all_draft_scope_check
        CHECK (checkpoint_stream = 'account:auth-authority:v1:account/' || account_uuid::TEXT),
    CONSTRAINT account_logout_all_draft_status_check
        CHECK ((status = 'WAITING' AND committed_at IS NULL AND event_stream IS NULL
                AND event_sequence IS NULL AND event_id IS NULL AND event_digest IS NULL
                AND event_payload IS NULL)
            OR (status = 'SOURCE_COMMITTED' AND committed_at IS NOT NULL
                AND event_stream IS NOT NULL AND event_sequence IS NOT NULL
                AND event_id IS NOT NULL AND event_digest ~ '^sha256:[0-9a-f]{64}$'
                AND event_payload IS NOT NULL AND octet_length(event_payload) > 0))
);

-- [jooq ignore start]
-- jOOQ's H2 DDL interpreter maps BYTEA to BLOB, which cannot be indexed there.
-- PostgreSQL still enforces the exact token identity once across pending requests.
CREATE UNIQUE INDEX account_logout_all_draft_source_token_hash_unique
    ON account_logout_all_draft_source_changes (presented_token_hash);

CREATE FUNCTION account_logout_all_draft_frame(value BYTEA)
RETURNS BYTEA LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT int4send(octet_length(value)) || value;
$$;

CREATE FUNCTION account_logout_all_draft_journal_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    expected_request BYTEA;
    expected_mutation BYTEA;
    expected_evidence BYTEA;
    expected_baseline BYTEA;
    expected_change BYTEA;
    expected_key TEXT;
    persisted_uuid UUID;
    current_generation BIGINT;
    current_source_version BIGINT;
    current_fence BIGINT;
    current_fence_source_version BIGINT;
    current_sequence BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'WAITING' OR NEW.status <> 'SOURCE_COMMITTED'
            OR NEW.request_id IS DISTINCT FROM OLD.request_id
            OR NEW.account_id IS DISTINCT FROM OLD.account_id
            OR NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
            OR NEW.request_digest_version IS DISTINCT FROM OLD.request_digest_version
            OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
            OR NEW.token_profile IS DISTINCT FROM OLD.token_profile
            OR NEW.presented_token_hash IS DISTINCT FROM OLD.presented_token_hash
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
            RAISE EXCEPTION 'Logout-all Draft source intent and original capture are immutable'
                USING ERRCODE = '23514';
        END IF;
        NEW.committed_at = CURRENT_TIMESTAMP;
    ELSE
        IF NEW.status <> 'WAITING' THEN
            RAISE EXCEPTION 'Logout-all Draft source intent must begin WAITING'
                USING ERRCODE = '23514';
        END IF;
        SELECT account_uuid INTO persisted_uuid FROM accounts WHERE id = NEW.account_id;
        IF persisted_uuid IS DISTINCT FROM NEW.account_uuid THEN
            RAISE EXCEPTION 'Logout-all Draft intent Account association does not match'
                USING ERRCODE = '23514';
        END IF;

        expected_request := account_logout_all_draft_frame(convert_to('account-logout-all-draft-source-request/v1', 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.request_id::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.account_id::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.account_uuid::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.request_digest_version::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(NEW.request_digest)
            || account_logout_all_draft_frame(convert_to(NEW.token_profile, 'UTF8'))
            || account_logout_all_draft_frame(NEW.presented_token_hash);
        expected_evidence := account_logout_all_draft_frame(convert_to('account-draft-source-evidence/v1', 'UTF8'))
            || account_logout_all_draft_frame(convert_to('ACCOUNT', 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.account_uuid::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to('PRESENT', 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.expected_generation::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.expected_source_version::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to('PRESENT', 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.checkpoint_stream, 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.checkpoint_sequence::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(NEW.capture_evidence);
        expected_mutation := account_logout_all_draft_frame(convert_to('account-logout-all-draft-source-mutation/v1', 'UTF8'))
            || account_logout_all_draft_frame(NEW.request_payload)
            || account_logout_all_draft_frame(convert_to(NEW.expected_generation::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.expected_source_version::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.expected_issuance_fence::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.expected_issuance_fence_source_version::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.checkpoint_sequence::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(NEW.source_evidence);
        expected_change := account_logout_all_draft_frame(convert_to('account-draft-source-change/v1', 'UTF8'))
            || account_logout_all_draft_frame(convert_to(NEW.source_change_id::TEXT, 'UTF8'))
            || account_logout_all_draft_frame(convert_to('1', 'UTF8'))
            || account_logout_all_draft_frame(NEW.source_evidence)
            || account_logout_all_draft_frame(NEW.source_change_request);
        expected_key := 'ACCOUNT:' || NEW.account_uuid::TEXT;
        IF NEW.request_payload IS DISTINCT FROM expected_request
            OR NEW.source_evidence IS DISTINCT FROM expected_evidence
            OR NEW.source_change_request IS DISTINCT FROM expected_mutation
            OR NEW.source_change_binding IS DISTINCT FROM expected_change
            OR NOT EXISTS (SELECT 1 FROM account_draft_authorization_source_changes
                WHERE change_id = NEW.source_change_id AND status = 'WAITING'
                    AND binding = expected_change)
            OR (SELECT count(*) FROM account_draft_authorization_changed_scopes
                WHERE change_id = NEW.source_change_id) <> 1
            OR NOT EXISTS (SELECT 1 FROM account_draft_authorization_changed_scopes
                WHERE change_id = NEW.source_change_id AND source_key = expected_key) THEN
            RAISE EXCEPTION 'Logout-all request must bind its exact V75/V76 Account source intent'
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
            RAISE EXCEPTION 'Logout-all Draft capture differs from current Account source counters'
                USING ERRCODE = '23514';
        END IF;
        SELECT last_sequence INTO current_sequence FROM account_authority_outbox_streams
            WHERE outbox_stream_key = NEW.checkpoint_stream;
        IF NEW.checkpoint_sequence = 0 THEN
            expected_baseline := account_logout_all_draft_frame(convert_to('account-draft-source-baseline/v1', 'UTF8'))
                || account_logout_all_draft_frame(convert_to(NEW.account_uuid::TEXT, 'UTF8'))
                || account_logout_all_draft_frame(convert_to('1', 'UTF8'))
                || account_logout_all_draft_frame(convert_to('1', 'UTF8'))
                || account_logout_all_draft_frame(convert_to(NEW.checkpoint_stream, 'UTF8'))
                || account_logout_all_draft_frame(convert_to('0', 'UTF8'));
            IF NEW.expected_generation <> 1 OR NEW.expected_source_version <> 1
                OR NEW.capture_evidence IS DISTINCT FROM expected_baseline
                OR (current_sequence IS NOT NULL AND current_sequence <> 0)
                OR EXISTS (SELECT 1 FROM account_authority_outbox_events
                    WHERE outbox_stream_key = NEW.checkpoint_stream) THEN
                RAISE EXCEPTION 'Logout-all zero checkpoint lacks exact pristine source evidence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF current_sequence IS DISTINCT FROM NEW.checkpoint_sequence
            OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_events
                WHERE outbox_stream_key = NEW.checkpoint_stream
                    AND outbox_sequence = NEW.checkpoint_sequence
                    AND payload = NEW.capture_evidence) THEN
            RAISE EXCEPTION 'Logout-all capture does not bind the exact current Account event'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_logout_all_draft_link_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal account_logout_all_draft_source_changes%ROWTYPE;
    source_status TEXT;
    source_binding BYTEA;
    selected_change UUID;
    persisted_generation BIGINT;
    persisted_source_version BIGINT;
    persisted_fence BIGINT;
    persisted_fence_source_version BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'account_logout_all_draft_source_changes' THEN
        selected_change := NEW.source_change_id;
    ELSE
        selected_change := NEW.change_id;
    END IF;
    SELECT * INTO journal FROM account_logout_all_draft_source_changes
        WHERE source_change_id = selected_change;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT status, binding INTO source_status, source_binding
        FROM account_draft_authorization_source_changes WHERE change_id = selected_change;
    IF source_status IS DISTINCT FROM journal.status
        OR source_binding IS DISTINCT FROM journal.source_change_binding THEN
        RAISE EXCEPTION 'Logout-all journal and V75/V76 Account source transition must commit together'
            USING ERRCODE = '23514';
    END IF;
    IF journal.status = 'WAITING' AND EXISTS (SELECT 1 FROM account_authority_outbox_events
        WHERE outbox_stream_key = journal.checkpoint_stream
            AND request_id = journal.request_id::TEXT) THEN
        RAISE EXCEPTION 'A WAITING logout-all request cannot publish its source event'
            USING ERRCODE = '23514';
    END IF;
    IF journal.status = 'SOURCE_COMMITTED' AND (
        journal.event_stream IS DISTINCT FROM journal.checkpoint_stream
        OR journal.event_id IS DISTINCT FROM 'account-logout-all-event-v1:' || journal.request_id::TEXT
        OR journal.event_sequence IS DISTINCT FROM journal.checkpoint_sequence + 1
        OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_events
            WHERE outbox_stream_key = journal.event_stream AND outbox_sequence = journal.event_sequence
                AND request_id = journal.request_id::TEXT AND event_id = journal.event_id
                AND event_digest = journal.event_digest AND payload = journal.event_payload)
        OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_streams
            WHERE outbox_stream_key = journal.event_stream AND last_sequence = journal.event_sequence)
        OR NOT EXISTS (SELECT 1 FROM account_logout_all_operation_receipts
            WHERE request_id = journal.request_id AND account_id = journal.account_id
                AND account_uuid = journal.account_uuid
                AND request_digest_version = journal.request_digest_version
                AND request_digest = journal.request_digest AND token_profile = journal.token_profile
                AND presented_token_hash = journal.presented_token_hash
                AND outbox_stream_key = journal.event_stream
                AND outbox_sequence = journal.event_sequence
                AND event_id = journal.event_id AND event_digest = journal.event_digest
                AND account_authority_generation = journal.expected_generation + 1
                AND account_source_version = journal.expected_source_version + 1
                AND issuance_fence = journal.expected_issuance_fence + 1
                AND issuance_fence_source_version = journal.expected_issuance_fence_source_version + 1)) THEN
        RAISE EXCEPTION 'Committed logout-all intent lacks its exact receipt, event, or checkpoint'
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
            OR persisted_fence_source_version
                IS DISTINCT FROM journal.expected_issuance_fence_source_version + 1 THEN
            RAISE EXCEPTION 'Logout-all Account authority counters did not advance once from capture'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_logout_all_draft_source_request_guard
    BEFORE INSERT OR UPDATE ON account_logout_all_draft_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_logout_all_draft_journal_guard();
CREATE TRIGGER account_logout_all_draft_source_delete_guard
    BEFORE DELETE ON account_logout_all_draft_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_logout_all_draft_source_truncate_guard
    BEFORE TRUNCATE ON account_logout_all_draft_source_changes
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE CONSTRAINT TRIGGER account_logout_all_draft_source_complete_guard
    AFTER INSERT OR UPDATE ON account_logout_all_draft_source_changes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_logout_all_draft_link_guard();
CREATE CONSTRAINT TRIGGER account_logout_all_draft_source_v57_link_guard
    AFTER INSERT OR UPDATE ON account_draft_authorization_source_changes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_logout_all_draft_link_guard();
-- [jooq ignore stop]
