-- Immutable lifecycle receipts bind one logout-all request and presented token to its source event.
CREATE TABLE account_logout_all_operation_receipts (
    request_id UUID NOT NULL,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL,
    operation_kind VARCHAR(32) NOT NULL DEFAULT 'ACCOUNT_LOGOUT_ALL',
    request_digest_version INTEGER NOT NULL,
    request_digest BYTEA NOT NULL,
    presented_token_hash BYTEA NOT NULL,
    token_profile VARCHAR(32) NOT NULL,
    outbox_stream_key VARCHAR(2048) NOT NULL,
    outbox_sequence BIGINT NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    event_digest VARCHAR(71) NOT NULL,
    account_authority_generation BIGINT NOT NULL,
    account_source_version BIGINT NOT NULL,
    issuance_fence BIGINT NOT NULL,
    issuance_fence_source_version BIGINT NOT NULL,
    lifecycle_result VARCHAR(32) NOT NULL DEFAULT 'LOGOUT_ALL_COMMITTED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_logout_all_operation_identity_check
        CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND operation_kind = 'ACCOUNT_LOGOUT_ALL'
            AND request_digest_version = 1
            AND octet_length(request_digest) = 32
            AND octet_length(presented_token_hash) = 32
            AND token_profile IN ('control-ui', 'player-bootstrap')
            AND lifecycle_result = 'LOGOUT_ALL_COMMITTED'),
    CONSTRAINT account_logout_all_operation_checkpoint_check
        CHECK (outbox_stream_key = 'account:auth-authority:v1:account/' || account_uuid::TEXT
            AND outbox_sequence > 0
            AND event_id = 'account-logout-all-event-v1:' || request_id::TEXT
            AND event_digest ~ '^sha256:[0-9a-f]{64}$'
            AND account_authority_generation > 0
            AND account_source_version > 0
            AND issuance_fence > 0
            AND issuance_fence_source_version > 0),
    CONSTRAINT account_logout_all_operation_event_uq
        UNIQUE (outbox_stream_key, event_id),
    CONSTRAINT account_logout_all_operation_outbox_fk
        FOREIGN KEY (outbox_stream_key, outbox_sequence)
        REFERENCES account_authority_outbox_events (outbox_stream_key, outbox_sequence)
        ON DELETE RESTRICT
);

-- [jooq ignore start]
ALTER TABLE account_logout_all_operation_receipts
    ADD CONSTRAINT account_logout_all_operation_receipts_pk PRIMARY KEY (request_id);

CREATE UNIQUE INDEX account_logout_all_operation_presented_token_uq
    ON account_logout_all_operation_receipts (presented_token_hash);

CREATE FUNCTION account_logout_all_operation_account_binding_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    persisted_uuid UUID;
BEGIN
    SELECT account_uuid INTO persisted_uuid
        FROM accounts
        WHERE id = NEW.account_id;
    IF persisted_uuid IS DISTINCT FROM NEW.account_uuid THEN
        RAISE EXCEPTION 'Logout-all receipt Account identity does not match its source row'
            USING ERRCODE = '23514', CONSTRAINT = 'account_logout_all_operation_account_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_logout_all_operation_account_binding
    BEFORE INSERT ON account_logout_all_operation_receipts
    FOR EACH ROW EXECUTE FUNCTION account_logout_all_operation_account_binding_guard();

CREATE FUNCTION account_logout_all_operation_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Logout-all operation receipts are immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'account_logout_all_operation_immutable';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_logout_all_operation_immutable
    BEFORE UPDATE OR DELETE ON account_logout_all_operation_receipts
    FOR EACH ROW EXECUTE FUNCTION account_logout_all_operation_immutable_guard();
-- [jooq ignore stop]
