-- Immutable source receipts make a consumed Account-issued reset token an exact retry identity.
CREATE TABLE account_password_reset_operation_receipts (
    token_hash BYTEA NOT NULL,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL,
    operation_kind VARCHAR(32) NOT NULL DEFAULT 'PASSWORD_RESET',
    request_id VARCHAR(128) NOT NULL UNIQUE,
    request_digest_version INTEGER NOT NULL,
    request_digest BYTEA NOT NULL,
    token_expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    password_verifier_digest BYTEA NOT NULL,
    outbox_stream_key VARCHAR(2048) NOT NULL,
    outbox_sequence BIGINT NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    event_digest VARCHAR(71) NOT NULL,
    account_authority_generation BIGINT NOT NULL,
    account_source_version BIGINT NOT NULL,
    issuance_fence BIGINT NOT NULL,
    issuance_fence_source_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_password_reset_operation_token_hash_check
        CHECK (octet_length(token_hash) = 32),
    CONSTRAINT account_password_reset_operation_identity_check
        CHECK (account_id > 0
            AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND operation_kind = 'PASSWORD_RESET'
            AND length(btrim(request_id)) BETWEEN 1 AND 128
            AND request_id = 'account-password-reset-request-v1:' || encode(token_hash, 'hex')
            AND request_digest_version = 1
            AND octet_length(request_digest) = 32
            AND octet_length(password_verifier_digest) = 32),
    CONSTRAINT account_password_reset_operation_checkpoint_check
        CHECK (outbox_stream_key = 'account:auth-authority:v1:account/' || account_uuid::TEXT
            AND outbox_sequence > 0
            AND length(btrim(event_id)) BETWEEN 1 AND 128
            AND event_id = 'account-password-reset-event-v1:' || encode(token_hash, 'hex')
            AND event_digest ~ '^sha256:[0-9a-f]{64}$'
            AND account_authority_generation > 0
            AND account_source_version > 0
            AND issuance_fence > 0
            AND issuance_fence_source_version > 0),
    CONSTRAINT account_password_reset_operation_event_uq
        UNIQUE (outbox_stream_key, event_id),
    CONSTRAINT account_password_reset_operation_outbox_fk
        FOREIGN KEY (outbox_stream_key, outbox_sequence)
        REFERENCES account_authority_outbox_events (outbox_stream_key, outbox_sequence)
        ON DELETE RESTRICT
);

-- Bind the private numeric association and canonical Account UUID at the immutable insert point.
-- [jooq ignore start]
ALTER TABLE account_password_reset_operation_receipts
    ADD CONSTRAINT account_password_reset_operation_receipts_pk PRIMARY KEY (token_hash);

CREATE FUNCTION account_password_reset_operation_account_binding_guard()
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
        RAISE EXCEPTION 'Password-reset receipt Account identity does not match its source row'
            USING ERRCODE = '23514', CONSTRAINT = 'account_password_reset_operation_account_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_password_reset_operation_account_binding
    BEFORE INSERT ON account_password_reset_operation_receipts
    FOR EACH ROW EXECUTE FUNCTION account_password_reset_operation_account_binding_guard();

CREATE FUNCTION account_password_reset_operation_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Password-reset operation receipts are immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'account_password_reset_operation_immutable';
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_password_reset_operation_immutable
    BEFORE UPDATE OR DELETE ON account_password_reset_operation_receipts
    FOR EACH ROW EXECUTE FUNCTION account_password_reset_operation_immutable_guard();
CREATE TRIGGER account_password_reset_operation_no_truncate
    BEFORE TRUNCATE ON account_password_reset_operation_receipts
    FOR EACH STATEMENT EXECUTE FUNCTION account_password_reset_operation_immutable_guard();
-- [jooq ignore stop]
