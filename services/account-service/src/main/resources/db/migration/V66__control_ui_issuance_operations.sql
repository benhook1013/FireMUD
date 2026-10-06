-- Durable targetless control-UI issuance correlation and exact encrypted-result retention.
-- This is not authentication, active-token-registry proof, or authority-currentness proof.
CREATE TABLE account_control_ui_issuance_operations (
    operation_id UUID PRIMARY KEY CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'),
    request_id UUID NOT NULL UNIQUE CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    account_uuid UUID NOT NULL CHECK (account_uuid <> '00000000-0000-0000-0000-000000000000'),
    account_id BIGINT NOT NULL,
    account_provenance VARCHAR(40) NOT NULL,
    profile VARCHAR(32) NOT NULL CHECK (profile = 'control-ui'),
    audience VARCHAR(32) NOT NULL CHECK (audience = 'control-ui'),
    request_digest_version SMALLINT NOT NULL CHECK (request_digest_version = 1),
    request_digest BYTEA NOT NULL CHECK (octet_length(request_digest) = 32),
    authority_capture BYTEA NOT NULL CHECK (octet_length(authority_capture) BETWEEN 1 AND 1048576),
    authority_capture_digest BYTEA NOT NULL CHECK (octet_length(authority_capture_digest) = 32),
    issuance_fence_capture BYTEA NOT NULL CHECK (octet_length(issuance_fence_capture) BETWEEN 1 AND 1048576),
    issuance_fence_digest BYTEA NOT NULL CHECK (octet_length(issuance_fence_digest) = 32),
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING', 'COMMITTED')),
    token_hash VARCHAR(64),
    response_digest BYTEA,
    issued_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_control_ui_issuance_account_binding_fk
        FOREIGN KEY (account_uuid, account_id, account_provenance)
        REFERENCES accounts (account_uuid, account_uuid_source_numeric_id, account_uuid_provenance)
        ON DELETE RESTRICT,
    CONSTRAINT account_control_ui_issuance_result_shape CHECK (
        (status = 'PENDING'
            AND token_hash IS NULL AND response_digest IS NULL
            AND issued_at IS NULL AND expires_at IS NULL)
        OR (status = 'COMMITTED'
            AND token_hash IS NOT NULL AND token_hash ~ '^[0-9a-f]{64}$'
            AND response_digest IS NOT NULL
            AND octet_length(response_digest) = 32
            AND issued_at IS NOT NULL AND expires_at IS NOT NULL
            AND issued_at > CAST('1970-01-01 00:00:00+00' AS TIMESTAMP WITH TIME ZONE)
            AND expires_at > issued_at
            AND date_trunc('second', issued_at) = issued_at
            AND date_trunc('second', expires_at) = expires_at))
);

CREATE TABLE account_control_ui_issuance_response_envelopes (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    account_uuid UUID NOT NULL,
    account_id BIGINT NOT NULL,
    account_provenance VARCHAR(40) NOT NULL,
    profile VARCHAR(32) NOT NULL CHECK (profile = 'control-ui'),
    audience VARCHAR(32) NOT NULL CHECK (audience = 'control-ui'),
    request_digest_version SMALLINT NOT NULL CHECK (request_digest_version = 1),
    request_digest BYTEA NOT NULL CHECK (octet_length(request_digest) = 32),
    token_hash VARCHAR(64) NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    response_digest BYTEA NOT NULL CHECK (octet_length(response_digest) = 32),
    authority_capture_digest BYTEA NOT NULL CHECK (octet_length(authority_capture_digest) = 32),
    issuance_fence_digest BYTEA NOT NULL CHECK (octet_length(issuance_fence_digest) = 32),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    format_version SMALLINT NOT NULL CHECK (format_version = 1),
    key_id VARCHAR(64) NOT NULL CHECK (key_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    purpose VARCHAR(32) NOT NULL CHECK (purpose = 'CONTROL_UI_RESPONSE'),
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 12),
    ciphertext BYTEA NOT NULL CHECK (octet_length(ciphertext) BETWEEN 16 AND 65552),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_control_ui_issuance_envelope_operation_fk
        FOREIGN KEY (operation_id)
        REFERENCES account_control_ui_issuance_operations (operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_control_ui_issuance_envelope_identity_check CHECK (
        request_id <> '00000000-0000-0000-0000-000000000000'
        AND account_uuid <> '00000000-0000-0000-0000-000000000000'
        AND account_id > 0
        AND issued_at > CAST('1970-01-01 00:00:00+00' AS TIMESTAMP WITH TIME ZONE)
        AND expires_at > issued_at
        AND date_trunc('second', issued_at) = issued_at
        AND date_trunc('second', expires_at) = expires_at)
);

CREATE INDEX account_control_ui_issuance_account_idx
    ON account_control_ui_issuance_operations (account_uuid, created_at, operation_id);

-- [jooq ignore start]
CREATE FUNCTION account_control_ui_issuance_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status <> 'PENDING'
        OR NEW.token_hash IS NOT NULL OR NEW.response_digest IS NOT NULL
        OR NEW.issued_at IS NOT NULL OR NEW.expires_at IS NOT NULL THEN
        RAISE EXCEPTION 'Control-UI issuance must begin without a committed result'
            USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_issuance_initial_state';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_control_ui_issuance_update_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR NEW.status <> 'COMMITTED'
        OR (to_jsonb(NEW) - ARRAY['status','token_hash','response_digest','issued_at','expires_at','updated_at'])
            IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['status','token_hash','response_digest','issued_at','expires_at','updated_at']) THEN
        RAISE EXCEPTION 'Original control-UI issuance request and capture are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_issuance_identity_immutable';
    END IF;
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_control_ui_issuance_envelope_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    operation account_control_ui_issuance_operations%ROWTYPE;
BEGIN
    SELECT * INTO operation FROM account_control_ui_issuance_operations
        WHERE operation_id = NEW.operation_id;
    IF NOT FOUND OR operation.status <> 'COMMITTED'
        OR operation.request_id IS DISTINCT FROM NEW.request_id
        OR operation.account_uuid IS DISTINCT FROM NEW.account_uuid
        OR operation.account_id IS DISTINCT FROM NEW.account_id
        OR operation.account_provenance IS DISTINCT FROM NEW.account_provenance
        OR operation.profile IS DISTINCT FROM NEW.profile
        OR operation.audience IS DISTINCT FROM NEW.audience
        OR operation.request_digest_version IS DISTINCT FROM NEW.request_digest_version
        OR operation.request_digest IS DISTINCT FROM NEW.request_digest
        OR operation.token_hash IS DISTINCT FROM NEW.token_hash
        OR operation.response_digest IS DISTINCT FROM NEW.response_digest
        OR operation.authority_capture_digest IS DISTINCT FROM NEW.authority_capture_digest
        OR operation.issuance_fence_digest IS DISTINCT FROM NEW.issuance_fence_digest
        OR operation.issued_at IS DISTINCT FROM NEW.issued_at
        OR operation.expires_at IS DISTINCT FROM NEW.expires_at THEN
        RAISE EXCEPTION 'Control-UI envelope does not match its committed operation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_issuance_envelope_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_control_ui_issuance_envelope_update_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Control-UI response envelope is immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_issuance_envelope_immutable';
    RETURN OLD;
END;
$$;

CREATE FUNCTION account_control_ui_issuance_no_delete()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Control-UI issuance recovery evidence cannot be deleted'
        USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_issuance_no_delete';
    RETURN OLD;
END;
$$;

CREATE FUNCTION account_control_ui_issuance_no_truncate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Control-UI issuance recovery evidence cannot be truncated'
        USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_issuance_no_truncate';
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_control_ui_issuance_terminal_envelope_check()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'COMMITTED' AND NOT EXISTS (
        SELECT 1 FROM account_control_ui_issuance_response_envelopes envelope
        WHERE envelope.operation_id = NEW.operation_id
            AND envelope.request_id = NEW.request_id
            AND envelope.account_uuid = NEW.account_uuid
            AND envelope.account_id = NEW.account_id
            AND envelope.account_provenance = NEW.account_provenance
            AND envelope.profile = NEW.profile AND envelope.audience = NEW.audience
            AND envelope.request_digest_version = NEW.request_digest_version
            AND envelope.request_digest = NEW.request_digest
            AND envelope.token_hash = NEW.token_hash
            AND envelope.response_digest = NEW.response_digest
            AND envelope.authority_capture_digest = NEW.authority_capture_digest
            AND envelope.issuance_fence_digest = NEW.issuance_fence_digest
            AND envelope.issued_at = NEW.issued_at AND envelope.expires_at = NEW.expires_at
            AND envelope.purpose = 'CONTROL_UI_RESPONSE'
    ) THEN
        RAISE EXCEPTION 'Committed control-UI issuance requires its exact encrypted envelope'
            USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_issuance_envelope_required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_control_ui_issuance_insert_guard_trigger
    BEFORE INSERT ON account_control_ui_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_issuance_insert_guard();
CREATE TRIGGER account_control_ui_issuance_update_guard_trigger
    BEFORE UPDATE ON account_control_ui_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_issuance_update_guard();
CREATE TRIGGER account_control_ui_issuance_envelope_insert_guard_trigger
    BEFORE INSERT ON account_control_ui_issuance_response_envelopes
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_issuance_envelope_insert_guard();
CREATE TRIGGER account_control_ui_issuance_envelope_update_guard_trigger
    BEFORE UPDATE ON account_control_ui_issuance_response_envelopes
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_issuance_envelope_update_guard();
CREATE TRIGGER account_control_ui_issuance_operation_no_delete_trigger
    BEFORE DELETE ON account_control_ui_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_issuance_no_delete();
CREATE TRIGGER account_control_ui_issuance_envelope_no_delete_trigger
    BEFORE DELETE ON account_control_ui_issuance_response_envelopes
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_issuance_no_delete();
CREATE TRIGGER account_control_ui_issuance_operation_no_truncate_trigger
    BEFORE TRUNCATE ON account_control_ui_issuance_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_control_ui_issuance_no_truncate();
CREATE TRIGGER account_control_ui_issuance_envelope_no_truncate_trigger
    BEFORE TRUNCATE ON account_control_ui_issuance_response_envelopes
    FOR EACH STATEMENT EXECUTE FUNCTION account_control_ui_issuance_no_truncate();
CREATE CONSTRAINT TRIGGER account_control_ui_issuance_terminal_envelope_check_trigger
    AFTER INSERT OR UPDATE ON account_control_ui_issuance_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_issuance_terminal_envelope_check();
-- [jooq ignore stop]
