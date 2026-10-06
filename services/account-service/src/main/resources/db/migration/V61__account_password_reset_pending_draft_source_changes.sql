-- Account password reset's pending owner journal and separate encrypted verifier row.
-- Existing V57/V60 histories and completed digest-only reset receipts remain untouched.

-- H2's jOOQ simulator gives these inline V57 checks different generated names.
-- Keep the exact PostgreSQL removals active and skip only these name-specific operations in simulation.
-- [jooq ignore start]
ALTER TABLE account_draft_authorization_source_changes
    DROP CONSTRAINT account_draft_authorization_source_changes_status_check;
ALTER TABLE account_draft_authorization_source_changes
    DROP CONSTRAINT account_draft_authorization_source_changes_check;
-- [jooq ignore stop]

ALTER TABLE account_draft_authorization_source_changes
    ADD COLUMN aborted_at TIMESTAMPTZ;
ALTER TABLE account_draft_authorization_source_changes
    ADD COLUMN abort_reason VARCHAR(24);
ALTER TABLE account_draft_authorization_source_changes
    ADD CONSTRAINT account_draft_authorization_source_changes_status_check
        CHECK (status IN ('WAITING', 'SOURCE_COMMITTED', 'SOURCE_ABORTED'));
ALTER TABLE account_draft_authorization_source_changes
    ADD CONSTRAINT account_draft_authorization_source_changes_terminal_check
        CHECK ((status = 'WAITING' AND committed_at IS NULL AND aborted_at IS NULL
                    AND abort_reason IS NULL)
            OR (status = 'SOURCE_COMMITTED' AND committed_at IS NOT NULL
                    AND aborted_at IS NULL AND abort_reason IS NULL)
            OR (status = 'SOURCE_ABORTED' AND committed_at IS NULL
                    AND aborted_at IS NOT NULL
                    AND abort_reason IS NOT NULL
                    AND abort_reason IN ('EXPIRED', 'DEFINITIVE_ABORT')));

CREATE TABLE account_password_reset_draft_source_changes (
    request_id VARCHAR(128) PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    token_hash BYTEA NOT NULL CHECK (octet_length(token_hash) = 32),
    request_digest_version INTEGER NOT NULL CHECK (request_digest_version = 1),
    request_digest BYTEA NOT NULL CHECK (octet_length(request_digest) = 32),
    token_expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    token_expires_at_text VARCHAR(64) NOT NULL,
    target_verifier_digest BYTEA NOT NULL CHECK (octet_length(target_verifier_digest) = 32),
    expected_generation BIGINT NOT NULL CHECK (expected_generation > 0),
    expected_source_version BIGINT NOT NULL CHECK (expected_source_version > 0),
    expected_issuance_fence BIGINT NOT NULL CHECK (expected_issuance_fence > 0),
    expected_issuance_fence_source_version BIGINT NOT NULL
        CHECK (expected_issuance_fence_source_version > 0),
    checkpoint_stream VARCHAR(2048) NOT NULL,
    checkpoint_sequence BIGINT NOT NULL CHECK (checkpoint_sequence >= 0),
    capture_evidence BYTEA NOT NULL CHECK (octet_length(capture_evidence) > 0),
    capture_evidence_digest BYTEA NOT NULL CHECK (octet_length(capture_evidence_digest) = 32),
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) > 0),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) > 0),
    source_change_id UUID NOT NULL UNIQUE
        REFERENCES account_draft_authorization_source_changes(change_id) ON DELETE RESTRICT,
    source_change_request BYTEA NOT NULL CHECK (octet_length(source_change_request) > 0),
    source_change_binding BYTEA NOT NULL CHECK (octet_length(source_change_binding) > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('WAITING', 'SOURCE_COMMITTED', 'SOURCE_ABORTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    aborted_at TIMESTAMPTZ,
    abort_reason VARCHAR(24),
    event_stream VARCHAR(2048),
    event_sequence BIGINT CHECK (event_sequence > 0),
    event_id VARCHAR(128),
    event_digest VARCHAR(71),
    event_payload BYTEA,
    CONSTRAINT account_password_reset_draft_request_identity_check CHECK (
        account_id > 0
        AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
        AND request_id = 'account-password-reset-request-v1:' || encode(token_hash, 'hex')
        AND token_expires_at_text::TIMESTAMP WITHOUT TIME ZONE = token_expires_at
        AND checkpoint_stream = 'account:auth-authority:v1:account/' || account_uuid::TEXT
    ),
    CONSTRAINT account_password_reset_draft_status_check CHECK (
        (status = 'WAITING' AND committed_at IS NULL AND aborted_at IS NULL
            AND abort_reason IS NULL AND event_stream IS NULL AND event_sequence IS NULL
            AND event_id IS NULL AND event_digest IS NULL AND event_payload IS NULL)
        OR (status = 'SOURCE_COMMITTED' AND committed_at IS NOT NULL AND aborted_at IS NULL
            AND abort_reason IS NULL AND event_stream IS NOT NULL AND event_sequence IS NOT NULL
            AND event_id IS NOT NULL AND event_digest ~ '^sha256:[0-9a-f]{64}$'
            AND event_payload IS NOT NULL AND octet_length(event_payload) > 0)
        OR (status = 'SOURCE_ABORTED' AND committed_at IS NULL AND aborted_at IS NOT NULL
            AND abort_reason IS NOT NULL
            AND abort_reason IN ('EXPIRED', 'DEFINITIVE_ABORT') AND event_stream IS NULL
            AND event_sequence IS NULL AND event_id IS NULL AND event_digest IS NULL
            AND event_payload IS NULL)
    ),
    CONSTRAINT account_password_reset_draft_event_uq UNIQUE (event_stream, event_id),
    CONSTRAINT account_password_reset_draft_event_fk
        FOREIGN KEY (event_stream, event_sequence)
        REFERENCES account_authority_outbox_events (outbox_stream_key, outbox_sequence)
        ON DELETE RESTRICT
);

CREATE INDEX account_password_reset_draft_expiry_idx
    ON account_password_reset_draft_source_changes (token_expires_at, request_id)
    WHERE status = 'WAITING';

-- jOOQ's H2 DDL interpreter maps BYTEA to BLOB, which cannot be indexed there.
-- PostgreSQL still enforces each reset-token identity once across the private journal.
-- [jooq ignore start]
CREATE UNIQUE INDEX account_password_reset_draft_token_hash_uq
    ON account_password_reset_draft_source_changes (token_hash);
-- [jooq ignore stop]

CREATE TABLE account_password_reset_pending_envelopes (
    request_id VARCHAR(128) PRIMARY KEY
        REFERENCES account_password_reset_draft_source_changes(request_id) ON DELETE RESTRICT,
    format_version SMALLINT NOT NULL CHECK (format_version = 1),
    key_id VARCHAR(64) NOT NULL CHECK (key_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    purpose VARCHAR(32) NOT NULL CHECK (purpose = 'pending-reset'),
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 12),
    ciphertext BYTEA NOT NULL
        CHECK (octet_length(ciphertext) BETWEEN 16 AND 65552),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_draft_authorization_source_change_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Draft source change cannot be deleted';
    END IF;
    IF OLD.status <> 'WAITING'
        OR NEW.status NOT IN ('SOURCE_COMMITTED', 'SOURCE_ABORTED')
        OR NEW.change_id IS DISTINCT FROM OLD.change_id
        OR NEW.binding IS DISTINCT FROM OLD.binding
        OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
        OR (NEW.status = 'SOURCE_COMMITTED'
            AND (NEW.committed_at IS NULL OR NEW.aborted_at IS NOT NULL
                OR NEW.abort_reason IS NOT NULL))
        OR (NEW.status = 'SOURCE_ABORTED'
            AND (NEW.committed_at IS NOT NULL OR NEW.aborted_at IS NULL
                OR NEW.abort_reason IS NULL
                OR NEW.abort_reason NOT IN ('EXPIRED', 'DEFINITIVE_ABORT'))) THEN
        RAISE EXCEPTION 'Draft source change result is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM account_draft_authorization_changed_scopes changed
        JOIN account_draft_authorization_sources source USING (source_key)
        JOIN account_draft_authorization_fences fence USING (operation_id)
        WHERE changed.change_id = OLD.change_id
          AND NOT ((fence.ordering = 'REVOKE_ORDER' AND (
              SELECT count(*) FROM account_draft_authorization_owner_readbacks readback
              WHERE readback.operation_id = fence.operation_id
                AND readback.outcome = 'DEFINITIVELY_ABORTED'
          ) = 2) OR (fence.ordering = 'COMMIT_ORDER' AND (
              SELECT count(*) FROM account_draft_authorization_owner_readbacks readback
              WHERE readback.operation_id = fence.operation_id
          ) = 2))
    ) THEN
        RAISE EXCEPTION 'Both definitive owner outcomes are required before source transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_password_reset_draft_frame_text(value TEXT)
RETURNS BYTEA LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT int4send(octet_length(convert_to(value, 'UTF8')))
        || convert_to(value, 'UTF8');
$$;

CREATE FUNCTION account_password_reset_draft_frame_bytes(value BYTEA)
RETURNS BYTEA LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT int4send(octet_length(value)) || value;
$$;

CREATE FUNCTION account_password_reset_draft_journal_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    expected_request_payload BYTEA;
    expected_source_evidence BYTEA;
    expected_source_change_request BYTEA;
    expected_source_change_binding BYTEA;
    expected_baseline BYTEA;
    expected_request_digest BYTEA;
    expected_source_key TEXT;
    persisted_uuid UUID;
    persisted_generation BIGINT;
    persisted_source_version BIGINT;
    persisted_fence BIGINT;
    persisted_fence_source_version BIGINT;
    persisted_sequence BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'WAITING'
            OR NEW.status NOT IN ('SOURCE_COMMITTED', 'SOURCE_ABORTED')
            OR NEW.request_id IS DISTINCT FROM OLD.request_id
            OR NEW.account_id IS DISTINCT FROM OLD.account_id
            OR NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
            OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
            OR NEW.request_digest_version IS DISTINCT FROM OLD.request_digest_version
            OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
            OR NEW.token_expires_at IS DISTINCT FROM OLD.token_expires_at
            OR NEW.token_expires_at_text IS DISTINCT FROM OLD.token_expires_at_text
            OR NEW.target_verifier_digest IS DISTINCT FROM OLD.target_verifier_digest
            OR NEW.expected_generation IS DISTINCT FROM OLD.expected_generation
            OR NEW.expected_source_version IS DISTINCT FROM OLD.expected_source_version
            OR NEW.expected_issuance_fence IS DISTINCT FROM OLD.expected_issuance_fence
            OR NEW.expected_issuance_fence_source_version
                IS DISTINCT FROM OLD.expected_issuance_fence_source_version
            OR NEW.checkpoint_stream IS DISTINCT FROM OLD.checkpoint_stream
            OR NEW.checkpoint_sequence IS DISTINCT FROM OLD.checkpoint_sequence
            OR NEW.capture_evidence IS DISTINCT FROM OLD.capture_evidence
            OR NEW.capture_evidence_digest IS DISTINCT FROM OLD.capture_evidence_digest
            OR NEW.request_payload IS DISTINCT FROM OLD.request_payload
            OR NEW.source_evidence IS DISTINCT FROM OLD.source_evidence
            OR NEW.source_change_id IS DISTINCT FROM OLD.source_change_id
            OR NEW.source_change_request IS DISTINCT FROM OLD.source_change_request
            OR NEW.source_change_binding IS DISTINCT FROM OLD.source_change_binding
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Password-reset pending request and original capture are immutable'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.status = 'SOURCE_COMMITTED' THEN
            IF NEW.committed_at IS NULL OR NEW.aborted_at IS NOT NULL
                OR NEW.abort_reason IS NOT NULL
                OR NEW.event_stream IS DISTINCT FROM OLD.checkpoint_stream
                OR NEW.event_sequence IS DISTINCT FROM OLD.checkpoint_sequence + 1
                OR NEW.event_id IS DISTINCT FROM
                    'account-password-reset-event-v1:' || encode(OLD.token_hash, 'hex')
                OR NEW.event_digest IS NULL
                OR NEW.event_digest !~ '^sha256:[0-9a-f]{64}$'
                OR NEW.event_payload IS NULL OR octet_length(NEW.event_payload) = 0
                OR OLD.token_expires_at <= LOCALTIMESTAMP THEN
                RAISE EXCEPTION 'Password-reset commit lacks live bound pending evidence'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            IF NEW.committed_at IS NOT NULL OR NEW.aborted_at IS NULL
                OR NEW.abort_reason IS NULL
                OR NEW.abort_reason NOT IN ('EXPIRED', 'DEFINITIVE_ABORT')
                OR NEW.event_stream IS NOT NULL OR NEW.event_sequence IS NOT NULL
                OR NEW.event_id IS NOT NULL OR NEW.event_digest IS NOT NULL
                OR NEW.event_payload IS NOT NULL
                OR (NEW.abort_reason = 'EXPIRED'
                    AND OLD.token_expires_at > LOCALTIMESTAMP) THEN
                RAISE EXCEPTION 'Password-reset cancellation is not a terminal no-mutation result'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    ELSE
        IF NEW.status <> 'WAITING' OR NEW.committed_at IS NOT NULL
            OR NEW.aborted_at IS NOT NULL OR NEW.abort_reason IS NOT NULL
            OR NEW.event_stream IS NOT NULL OR NEW.event_sequence IS NOT NULL
            OR NEW.event_id IS NOT NULL OR NEW.event_digest IS NOT NULL
            OR NEW.event_payload IS NOT NULL THEN
            RAISE EXCEPTION 'Password-reset source intent must begin WAITING'
                USING ERRCODE = '23514';
        END IF;
        SELECT account_uuid INTO persisted_uuid FROM accounts WHERE id = NEW.account_id;
        IF persisted_uuid IS DISTINCT FROM NEW.account_uuid THEN
            RAISE EXCEPTION 'Password-reset intent Account association does not match'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.request_id IS DISTINCT FROM
                'account-password-reset-request-v1:' || encode(NEW.token_hash, 'hex')
            OR NEW.capture_evidence_digest IS DISTINCT FROM sha256(NEW.capture_evidence)
            OR NEW.token_expires_at_text::TIMESTAMP WITHOUT TIME ZONE
                IS DISTINCT FROM NEW.token_expires_at THEN
            RAISE EXCEPTION 'Password-reset immutable request binding is malformed'
                USING ERRCODE = '23514';
        END IF;
        expected_request_digest := sha256(
            account_password_reset_draft_frame_text('account-password-reset-request/v1')
            || account_password_reset_draft_frame_text('PASSWORD_RESET')
            || account_password_reset_draft_frame_text(NEW.account_uuid::TEXT)
            || account_password_reset_draft_frame_text(encode(NEW.token_hash, 'hex'))
            || account_password_reset_draft_frame_text(NEW.token_expires_at_text)
            || account_password_reset_draft_frame_text(encode(NEW.target_verifier_digest, 'hex'))
        );
        expected_request_payload :=
            account_password_reset_draft_frame_text('account-password-reset-draft-source-request/v1')
            || account_password_reset_draft_frame_text(NEW.request_id)
            || account_password_reset_draft_frame_text(NEW.account_id::TEXT)
            || account_password_reset_draft_frame_text(NEW.account_uuid::TEXT)
            || account_password_reset_draft_frame_text(NEW.request_digest_version::TEXT)
            || account_password_reset_draft_frame_bytes(NEW.request_digest)
            || account_password_reset_draft_frame_bytes(NEW.token_hash)
            || account_password_reset_draft_frame_text(NEW.token_expires_at_text)
            || account_password_reset_draft_frame_bytes(NEW.target_verifier_digest);
        expected_source_evidence :=
            account_password_reset_draft_frame_text('account-draft-source-evidence/v1')
            || account_password_reset_draft_frame_text('ACCOUNT')
            || account_password_reset_draft_frame_text(NEW.account_uuid::TEXT)
            || account_password_reset_draft_frame_text('PRESENT')
            || account_password_reset_draft_frame_text(NEW.expected_generation::TEXT)
            || account_password_reset_draft_frame_text(NEW.expected_source_version::TEXT)
            || account_password_reset_draft_frame_text('PRESENT')
            || account_password_reset_draft_frame_text(NEW.checkpoint_stream)
            || account_password_reset_draft_frame_text(NEW.checkpoint_sequence::TEXT)
            || account_password_reset_draft_frame_bytes(NEW.capture_evidence);
        expected_source_change_request :=
            account_password_reset_draft_frame_text('account-password-reset-draft-source-mutation/v1')
            || account_password_reset_draft_frame_bytes(NEW.request_payload)
            || account_password_reset_draft_frame_text(NEW.expected_generation::TEXT)
            || account_password_reset_draft_frame_text(NEW.expected_source_version::TEXT)
            || account_password_reset_draft_frame_text(NEW.expected_issuance_fence::TEXT)
            || account_password_reset_draft_frame_text(NEW.expected_issuance_fence_source_version::TEXT)
            || account_password_reset_draft_frame_text(NEW.checkpoint_sequence::TEXT)
            || account_password_reset_draft_frame_bytes(NEW.source_evidence)
            || account_password_reset_draft_frame_bytes(NEW.capture_evidence_digest);
        expected_source_change_binding :=
            account_password_reset_draft_frame_text('account-draft-source-change/v1')
            || account_password_reset_draft_frame_text(NEW.source_change_id::TEXT)
            || account_password_reset_draft_frame_text('1')
            || account_password_reset_draft_frame_bytes(NEW.source_evidence)
            || account_password_reset_draft_frame_bytes(NEW.source_change_request);
        expected_source_key := 'ACCOUNT:' || NEW.account_uuid::TEXT;
        IF NEW.request_digest IS DISTINCT FROM expected_request_digest
            OR NEW.request_payload IS DISTINCT FROM expected_request_payload
            OR NEW.source_evidence IS DISTINCT FROM expected_source_evidence
            OR NEW.source_change_request IS DISTINCT FROM expected_source_change_request
            OR NEW.source_change_binding IS DISTINCT FROM expected_source_change_binding
            OR NOT EXISTS (SELECT 1 FROM account_draft_authorization_source_changes
                WHERE change_id = NEW.source_change_id AND status = 'WAITING'
                    AND binding = expected_source_change_binding)
            OR (SELECT count(*) FROM account_draft_authorization_changed_scopes
                WHERE change_id = NEW.source_change_id) <> 1
            OR NOT EXISTS (SELECT 1 FROM account_draft_authorization_changed_scopes
                WHERE change_id = NEW.source_change_id AND source_key = expected_source_key)
            OR NOT EXISTS (SELECT 1 FROM password_reset_token token
                WHERE token.account_id = NEW.account_id
                    AND token.expires_at = NEW.token_expires_at
                    AND sha256(convert_to(token.token, 'UTF8')) = NEW.token_hash) THEN
            RAISE EXCEPTION 'Password-reset journal does not bind its exact request and V57 Account source intent'
                USING ERRCODE = '23514';
        END IF;

        SELECT generation, source_version INTO persisted_generation, persisted_source_version
            FROM account_authority_generations
            WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid;
        SELECT issuance_fence, source_version INTO persisted_fence, persisted_fence_source_version
            FROM account_authority_issuance_fences WHERE account_uuid = NEW.account_uuid;
        IF persisted_generation IS DISTINCT FROM NEW.expected_generation
            OR persisted_source_version IS DISTINCT FROM NEW.expected_source_version
            OR persisted_fence IS DISTINCT FROM NEW.expected_issuance_fence
            OR persisted_fence_source_version
                IS DISTINCT FROM NEW.expected_issuance_fence_source_version THEN
            RAISE EXCEPTION 'Password-reset source capture differs from current Account counters'
                USING ERRCODE = '23514';
        END IF;
        SELECT last_sequence INTO persisted_sequence FROM account_authority_outbox_streams
            WHERE outbox_stream_key = NEW.checkpoint_stream;
        IF NEW.checkpoint_sequence = 0 THEN
            expected_baseline :=
                account_password_reset_draft_frame_text('account-draft-source-baseline/v1')
                || account_password_reset_draft_frame_text(NEW.account_uuid::TEXT)
                || account_password_reset_draft_frame_text('1')
                || account_password_reset_draft_frame_text('1')
                || account_password_reset_draft_frame_text(NEW.checkpoint_stream)
                || account_password_reset_draft_frame_text('0');
            IF NEW.expected_generation <> 1 OR NEW.expected_source_version <> 1
                OR NEW.capture_evidence IS DISTINCT FROM expected_baseline
                OR (persisted_sequence IS NOT NULL AND persisted_sequence <> 0)
                OR EXISTS (SELECT 1 FROM account_authority_outbox_events
                    WHERE outbox_stream_key = NEW.checkpoint_stream) THEN
                RAISE EXCEPTION 'Password-reset sequence-zero capture is not the pristine 1/1 baseline'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF persisted_sequence IS DISTINCT FROM NEW.checkpoint_sequence
            OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_events
                WHERE outbox_stream_key = NEW.checkpoint_stream
                    AND outbox_sequence = NEW.checkpoint_sequence
                    AND payload = NEW.capture_evidence) THEN
            RAISE EXCEPTION 'Password-reset capture does not bind the latest Account event'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_password_reset_pending_envelope_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal account_password_reset_draft_source_changes%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Pending password-reset ciphertext is immutable'
            USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        SELECT * INTO journal FROM account_password_reset_draft_source_changes
            WHERE request_id = NEW.request_id FOR UPDATE;
        IF NOT FOUND OR journal.status <> 'WAITING'
            OR journal.token_expires_at <= LOCALTIMESTAMP THEN
            RAISE EXCEPTION 'Pending password-reset ciphertext requires a live WAITING intent'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    ELSE
        SELECT * INTO journal FROM account_password_reset_draft_source_changes
            WHERE request_id = OLD.request_id FOR UPDATE;
        IF NOT FOUND OR NOT (journal.status IN ('SOURCE_COMMITTED', 'SOURCE_ABORTED')
            OR journal.token_expires_at <= LOCALTIMESTAMP) THEN
            RAISE EXCEPTION 'Pending password-reset ciphertext may be erased only after outcome or original expiry'
                USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
END;
$$;

CREATE FUNCTION account_password_reset_draft_link_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal account_password_reset_draft_source_changes%ROWTYPE;
    source_status TEXT;
    source_binding BYTEA;
    source_aborted_at TIMESTAMPTZ;
    source_abort_reason TEXT;
    selected_change UUID;
    selected_request TEXT;
    persisted_generation BIGINT;
    persisted_source_version BIGINT;
    persisted_fence BIGINT;
    persisted_fence_source_version BIGINT;
    persisted_sequence BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'account_password_reset_draft_source_changes' THEN
        selected_change := NEW.source_change_id;
        selected_request := NEW.request_id;
    ELSIF TG_TABLE_NAME = 'account_draft_authorization_source_changes' THEN
        selected_change := NEW.change_id;
    ELSE
        selected_request := CASE WHEN TG_OP = 'DELETE' THEN OLD.request_id ELSE NEW.request_id END;
    END IF;
    IF selected_change IS NOT NULL AND selected_request IS NULL THEN
        SELECT request_id INTO selected_request
            FROM account_password_reset_draft_source_changes
            WHERE source_change_id = selected_change;
    END IF;
    IF selected_request IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT * INTO journal FROM account_password_reset_draft_source_changes
        WHERE request_id = selected_request;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT status, binding, aborted_at, abort_reason
        INTO source_status, source_binding, source_aborted_at, source_abort_reason
        FROM account_draft_authorization_source_changes
        WHERE change_id = journal.source_change_id;
    IF source_status IS DISTINCT FROM journal.status
        OR source_binding IS DISTINCT FROM journal.source_change_binding
        OR (journal.status = 'SOURCE_ABORTED'
            AND (source_aborted_at IS DISTINCT FROM journal.aborted_at
                OR source_abort_reason IS DISTINCT FROM journal.abort_reason)) THEN
        RAISE EXCEPTION 'Password-reset journal and V57 source transition must commit together'
            USING ERRCODE = '23514';
    END IF;
    IF journal.status = 'WAITING' THEN
        SELECT generation, source_version INTO persisted_generation, persisted_source_version
            FROM account_authority_generations
            WHERE scope_kind = 'ACCOUNT' AND account_uuid = journal.account_uuid;
        SELECT issuance_fence, source_version INTO persisted_fence, persisted_fence_source_version
            FROM account_authority_issuance_fences WHERE account_uuid = journal.account_uuid;
        SELECT last_sequence INTO persisted_sequence FROM account_authority_outbox_streams
            WHERE outbox_stream_key = journal.checkpoint_stream;
        IF EXISTS (SELECT 1 FROM account_authority_outbox_events
            WHERE outbox_stream_key = journal.checkpoint_stream
                AND request_id = journal.request_id)
            OR EXISTS (SELECT 1 FROM account_password_reset_operation_receipts
                WHERE token_hash = journal.token_hash)
            OR EXISTS (SELECT 1 FROM accounts account
                WHERE account.id = journal.account_id
                    AND account.account_uuid = journal.account_uuid
                    AND sha256(convert_to(account.password_hash, 'UTF8'))
                        = journal.target_verifier_digest)
            OR (journal.token_expires_at > LOCALTIMESTAMP
                AND NOT EXISTS (SELECT 1 FROM account_password_reset_pending_envelopes
                    WHERE request_id = journal.request_id))
            OR (journal.token_expires_at > LOCALTIMESTAMP
                AND NOT EXISTS (SELECT 1 FROM password_reset_token token
                    WHERE token.account_id = journal.account_id
                        AND token.expires_at = journal.token_expires_at
                        AND sha256(convert_to(token.token, 'UTF8')) = journal.token_hash))
            OR persisted_generation IS DISTINCT FROM journal.expected_generation
            OR persisted_source_version IS DISTINCT FROM journal.expected_source_version
            OR persisted_fence IS DISTINCT FROM journal.expected_issuance_fence
            OR persisted_fence_source_version
                IS DISTINCT FROM journal.expected_issuance_fence_source_version
            OR (journal.checkpoint_sequence = 0
                AND persisted_sequence IS NOT NULL AND persisted_sequence <> 0)
            OR (journal.checkpoint_sequence > 0
                AND persisted_sequence IS DISTINCT FROM journal.checkpoint_sequence)
            OR (journal.checkpoint_sequence > 0 AND NOT EXISTS (
                SELECT 1 FROM account_authority_outbox_events event
                WHERE event.outbox_stream_key = journal.checkpoint_stream
                    AND event.outbox_sequence = journal.checkpoint_sequence
                    AND event.payload = journal.capture_evidence))
            OR (journal.checkpoint_sequence = 0 AND EXISTS (
                SELECT 1 FROM account_authority_outbox_events event
                WHERE event.outbox_stream_key = journal.checkpoint_stream)) THEN
            RAISE EXCEPTION 'WAITING password reset cannot mutate source or lose its live envelope'
                USING ERRCODE = '23514';
        END IF;
    ELSIF journal.status = 'SOURCE_COMMITTED' THEN
        IF journal.event_stream IS DISTINCT FROM journal.checkpoint_stream
            OR journal.event_id IS DISTINCT FROM
                'account-password-reset-event-v1:' || encode(journal.token_hash, 'hex')
            OR journal.event_sequence IS DISTINCT FROM journal.checkpoint_sequence + 1
            OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_events event
                WHERE event.outbox_stream_key = journal.event_stream
                    AND event.outbox_sequence = journal.event_sequence
                    AND event.request_id = journal.request_id
                    AND event.event_id = journal.event_id
                    AND event.event_digest = journal.event_digest
                    AND event.payload = journal.event_payload)
            OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_streams stream
                WHERE stream.outbox_stream_key = journal.event_stream
                    AND stream.last_sequence = journal.event_sequence)
            OR NOT EXISTS (SELECT 1 FROM account_password_reset_operation_receipts receipt
                WHERE receipt.token_hash = journal.token_hash
                    AND receipt.account_id = journal.account_id
                    AND receipt.account_uuid = journal.account_uuid
                    AND receipt.operation_kind = 'PASSWORD_RESET'
                    AND receipt.request_id = journal.request_id
                    AND receipt.request_digest_version = journal.request_digest_version
                    AND receipt.request_digest = journal.request_digest
                    AND receipt.token_expires_at = journal.token_expires_at
                    AND receipt.password_verifier_digest = journal.target_verifier_digest
                    AND receipt.outbox_stream_key = journal.event_stream
                    AND receipt.outbox_sequence = journal.event_sequence
                    AND receipt.event_id = journal.event_id
                    AND receipt.event_digest = journal.event_digest
                    AND receipt.account_authority_generation = journal.expected_generation + 1
                    AND receipt.account_source_version = journal.expected_source_version + 1
                    AND receipt.issuance_fence = journal.expected_issuance_fence + 1
                    AND receipt.issuance_fence_source_version
                        = journal.expected_issuance_fence_source_version + 1)
            OR EXISTS (SELECT 1 FROM account_password_reset_pending_envelopes
                WHERE request_id = journal.request_id)
            OR EXISTS (SELECT 1 FROM password_reset_token token
                WHERE token.account_id = journal.account_id
                    AND sha256(convert_to(token.token, 'UTF8')) = journal.token_hash) THEN
            RAISE EXCEPTION 'Committed password reset lacks exact source receipt/event or secret erasure'
                USING ERRCODE = '23514';
        END IF;
        SELECT generation, source_version INTO persisted_generation, persisted_source_version
            FROM account_authority_generations
            WHERE scope_kind = 'ACCOUNT' AND account_uuid = journal.account_uuid;
        SELECT issuance_fence, source_version INTO persisted_fence, persisted_fence_source_version
            FROM account_authority_issuance_fences WHERE account_uuid = journal.account_uuid;
        IF persisted_generation IS DISTINCT FROM journal.expected_generation + 1
            OR persisted_source_version IS DISTINCT FROM journal.expected_source_version + 1
            OR persisted_fence IS DISTINCT FROM journal.expected_issuance_fence + 1
            OR persisted_fence_source_version
                IS DISTINCT FROM journal.expected_issuance_fence_source_version + 1
            OR NOT EXISTS (SELECT 1 FROM accounts account
                WHERE account.id = journal.account_id
                    AND account.account_uuid = journal.account_uuid
                    AND sha256(convert_to(account.password_hash, 'UTF8'))
                        = journal.target_verifier_digest) THEN
            RAISE EXCEPTION 'Committed password reset did not install its exact verifier and counters'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF journal.status <> 'SOURCE_ABORTED'
            OR EXISTS (SELECT 1 FROM account_authority_outbox_events event
                WHERE event.outbox_stream_key = journal.checkpoint_stream
                    AND (event.request_id = journal.request_id
                        OR event.event_id = 'account-password-reset-event-v1:' || encode(journal.token_hash, 'hex')))
            OR EXISTS (SELECT 1 FROM account_password_reset_operation_receipts
                WHERE token_hash = journal.token_hash)
            OR EXISTS (SELECT 1 FROM account_password_reset_pending_envelopes
                WHERE request_id = journal.request_id) THEN
            RAISE EXCEPTION 'Aborted password reset must retain no source result or ciphertext'
                USING ERRCODE = '23514';
        END IF;
        SELECT generation, source_version INTO persisted_generation, persisted_source_version
            FROM account_authority_generations
            WHERE scope_kind = 'ACCOUNT' AND account_uuid = journal.account_uuid;
        SELECT issuance_fence, source_version INTO persisted_fence, persisted_fence_source_version
            FROM account_authority_issuance_fences WHERE account_uuid = journal.account_uuid;
        SELECT last_sequence INTO persisted_sequence FROM account_authority_outbox_streams
            WHERE outbox_stream_key = journal.checkpoint_stream;
        IF persisted_generation IS DISTINCT FROM journal.expected_generation
            OR persisted_source_version IS DISTINCT FROM journal.expected_source_version
            OR persisted_fence IS DISTINCT FROM journal.expected_issuance_fence
            OR persisted_fence_source_version
                IS DISTINCT FROM journal.expected_issuance_fence_source_version
            OR (journal.checkpoint_sequence = 0 AND persisted_sequence IS NOT NULL AND persisted_sequence <> 0)
            OR (journal.checkpoint_sequence > 0 AND persisted_sequence IS DISTINCT FROM journal.checkpoint_sequence)
            OR (journal.checkpoint_sequence > 0 AND NOT EXISTS (
                SELECT 1 FROM account_authority_outbox_events event
                WHERE event.outbox_stream_key = journal.checkpoint_stream
                    AND event.outbox_sequence = journal.checkpoint_sequence
                    AND event.payload = journal.capture_evidence))
            OR (journal.checkpoint_sequence = 0 AND EXISTS (
                SELECT 1 FROM account_authority_outbox_events event
                WHERE event.outbox_stream_key = journal.checkpoint_stream)) THEN
            RAISE EXCEPTION 'Aborted password reset changed its captured Account source'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_password_reset_draft_request_guard
    BEFORE INSERT OR UPDATE ON account_password_reset_draft_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_password_reset_draft_journal_guard();
CREATE TRIGGER account_password_reset_draft_no_delete_guard
    BEFORE DELETE ON account_password_reset_draft_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_password_reset_draft_no_truncate_guard
    BEFORE TRUNCATE ON account_password_reset_draft_source_changes
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_password_reset_pending_envelope_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_password_reset_pending_envelopes
    FOR EACH ROW EXECUTE FUNCTION account_password_reset_pending_envelope_guard();
CREATE TRIGGER account_password_reset_pending_envelope_no_truncate_guard
    BEFORE TRUNCATE ON account_password_reset_pending_envelopes
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();

CREATE CONSTRAINT TRIGGER account_password_reset_draft_link_guard
    AFTER INSERT OR UPDATE ON account_password_reset_draft_source_changes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_password_reset_draft_link_guard();
CREATE CONSTRAINT TRIGGER account_password_reset_draft_v57_link_guard
    AFTER UPDATE ON account_draft_authorization_source_changes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_password_reset_draft_link_guard();
CREATE CONSTRAINT TRIGGER account_password_reset_draft_envelope_link_guard
    AFTER INSERT OR DELETE ON account_password_reset_pending_envelopes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_password_reset_draft_link_guard();
-- [jooq ignore stop]
