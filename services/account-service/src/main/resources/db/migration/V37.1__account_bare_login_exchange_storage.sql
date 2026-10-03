-- Durable first-writer-wins storage for the Account-owned bare first-party LOGIN exchange.
-- The source connect-operation identity is consumed at most once. The response envelope is a
-- separate purpose-tagged encrypted record; neither table stores a raw delegation JWT.
CREATE TABLE account_bare_login_exchange_operations (
    operation_id UUID PRIMARY KEY,
    source_connect_operation_id UUID NOT NULL
        REFERENCES account_connect_token_issuance_operations(operation_id) ON DELETE RESTRICT,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    tenant_id BIGINT NOT NULL,
    connect_scope_hash VARCHAR(71) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    request_digest_version INTEGER NOT NULL,
    request_digest BYTEA NOT NULL,
    status VARCHAR(16) NOT NULL,
    outcome_code VARCHAR(64),
    token_identity VARCHAR(128),
    token_hash BYTEA,
    context_evidence_digest BYTEA,
    authority_tuple_digest BYTEA,
    issuance_fence_digest BYTEA,
    postcondition_digest BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_bare_login_exchange_source_uq
        UNIQUE (source_connect_operation_id),
    CONSTRAINT account_bare_login_exchange_request_uq
        UNIQUE (account_id, tenant_id, connect_scope_hash, request_id),
    CONSTRAINT account_bare_login_exchange_identity_check
        CHECK (account_id > 0
            AND tenant_id > 0
            AND length(btrim(request_id)) BETWEEN 1 AND 128
            AND length(connect_scope_hash) = 71
            AND connect_scope_hash ~ '^sha256:[0-9a-f]{64}$'
            AND request_digest_version = 1
            AND octet_length(request_digest) = 32),
    CONSTRAINT account_bare_login_exchange_status_check
        CHECK (status IN ('PENDING', 'COMMITTED')),
    CONSTRAINT account_bare_login_exchange_evidence_check
        CHECK (((token_identity IS NULL AND token_hash IS NULL)
                OR (token_identity IS NOT NULL
                    AND length(btrim(token_identity)) BETWEEN 1 AND 128
                    AND token_hash IS NOT NULL
                    AND octet_length(token_hash) = 32))
            AND (context_evidence_digest IS NULL OR octet_length(context_evidence_digest) = 32)
            AND (authority_tuple_digest IS NULL OR octet_length(authority_tuple_digest) = 32)
            AND (issuance_fence_digest IS NULL OR octet_length(issuance_fence_digest) = 32)
            AND (postcondition_digest IS NULL OR octet_length(postcondition_digest) = 32)),
    CONSTRAINT account_bare_login_exchange_outcome_check
        CHECK ((status = 'PENDING' AND outcome_code IS NULL)
            OR (status = 'COMMITTED'
                AND outcome_code = 'SUCCESS'
                AND token_identity IS NOT NULL
                AND token_hash IS NOT NULL
                AND context_evidence_digest IS NOT NULL
                AND authority_tuple_digest IS NOT NULL
                AND issuance_fence_digest IS NOT NULL
                AND postcondition_digest IS NOT NULL))
);

CREATE TABLE account_bare_login_response_envelopes (
    operation_id UUID PRIMARY KEY,
    operation_kind VARCHAR(32) NOT NULL,
    source_connect_operation_id UUID NOT NULL
        REFERENCES account_connect_token_issuance_operations(operation_id) ON DELETE RESTRICT,
    account_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    connect_scope_hash VARCHAR(71) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    request_digest_version INTEGER NOT NULL,
    request_digest BYTEA NOT NULL,
    format_version INTEGER NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    nonce BYTEA NOT NULL,
    ciphertext BYTEA NOT NULL,
    context_evidence_digest BYTEA NOT NULL,
    authority_tuple_digest BYTEA NOT NULL,
    issuance_fence_digest BYTEA NOT NULL,
    postcondition_digest BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_bare_login_response_envelope_operation_fk
        FOREIGN KEY (operation_id)
        REFERENCES account_bare_login_exchange_operations(operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_bare_login_response_envelope_identity_check
        CHECK (operation_kind = 'BARE_LOGIN_EXCHANGE'
            AND source_connect_operation_id IS NOT NULL
            AND account_id > 0
            AND tenant_id > 0
            AND length(connect_scope_hash) = 71
            AND connect_scope_hash ~ '^sha256:[0-9a-f]{64}$'
            AND length(btrim(request_id)) BETWEEN 1 AND 128
            AND request_digest_version = 1
            AND octet_length(request_digest) = 32),
    CONSTRAINT account_bare_login_response_envelope_crypto_check
        CHECK (format_version = 1
            AND length(btrim(key_id)) BETWEEN 1 AND 64
            AND key_id ~ '^[A-Za-z0-9_-]+$'
            AND purpose = 'BARE_LOGIN_RESPONSE'
            AND octet_length(nonce) = 12
            AND octet_length(ciphertext) BETWEEN 16 AND 65552
            AND octet_length(context_evidence_digest) = 32
            AND octet_length(authority_tuple_digest) = 32
            AND octet_length(issuance_fence_digest) = 32
            AND octet_length(postcondition_digest) = 32)
);

-- A request's first writer owns the source operation, exchange request ID, and digest. Only the
-- still-pending row may acquire evidence and reach the one immutable terminal success outcome.
-- This migration rejects deletion and deliberately defines no expiry or cleanup behavior.
-- [jooq ignore start]
CREATE FUNCTION account_bare_login_exchange_source_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_operation account_connect_token_issuance_operations%ROWTYPE;
BEGIN
    SELECT * INTO source_operation
        FROM account_connect_token_issuance_operations
        WHERE operation_id = NEW.source_connect_operation_id;
    IF NOT FOUND
        OR source_operation.status <> 'COMMITTED'
        OR source_operation.account_id IS DISTINCT FROM NEW.account_id
        OR source_operation.tenant_id IS DISTINCT FROM NEW.tenant_id
        OR source_operation.connect_scope_hash IS DISTINCT FROM NEW.connect_scope_hash THEN
        RAISE EXCEPTION 'Bare LOGIN exchange source must be committed and match its identity'
            USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_exchange_source_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_bare_login_exchange_source_insert_guard_trigger
    BEFORE INSERT ON account_bare_login_exchange_operations
    FOR EACH ROW
    EXECUTE FUNCTION account_bare_login_exchange_source_insert_guard();

CREATE FUNCTION account_bare_login_exchange_operation_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id
        OR NEW.source_connect_operation_id IS DISTINCT FROM OLD.source_connect_operation_id
        OR NEW.account_id IS DISTINCT FROM OLD.account_id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.connect_scope_hash IS DISTINCT FROM OLD.connect_scope_hash
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.request_digest_version IS DISTINCT FROM OLD.request_digest_version
        OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Bare LOGIN exchange identity and request digest are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_exchange_identity_immutable';
    END IF;
    IF (OLD.token_identity IS NOT NULL AND NEW.token_identity IS DISTINCT FROM OLD.token_identity)
        OR (OLD.token_hash IS NOT NULL AND NEW.token_hash IS DISTINCT FROM OLD.token_hash)
        OR (OLD.context_evidence_digest IS NOT NULL
            AND NEW.context_evidence_digest IS DISTINCT FROM OLD.context_evidence_digest)
        OR (OLD.authority_tuple_digest IS NOT NULL
            AND NEW.authority_tuple_digest IS DISTINCT FROM OLD.authority_tuple_digest)
        OR (OLD.issuance_fence_digest IS NOT NULL
            AND NEW.issuance_fence_digest IS DISTINCT FROM OLD.issuance_fence_digest)
        OR (OLD.postcondition_digest IS NOT NULL
            AND NEW.postcondition_digest IS DISTINCT FROM OLD.postcondition_digest) THEN
        RAISE EXCEPTION 'Bare LOGIN exchange evidence cannot be replaced'
            USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_exchange_evidence_immutable';
    END IF;
    IF OLD.status = 'COMMITTED' THEN
        RAISE EXCEPTION 'Terminal bare LOGIN exchange outcome is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_exchange_terminal_immutable';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
        AND NOT (OLD.status = 'PENDING' AND NEW.status = 'COMMITTED') THEN
        RAISE EXCEPTION 'Bare LOGIN exchange lifecycle transition is invalid'
            USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_exchange_lifecycle_transition';
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_bare_login_exchange_operation_update_guard_trigger
    BEFORE UPDATE ON account_bare_login_exchange_operations
    FOR EACH ROW
    EXECUTE FUNCTION account_bare_login_exchange_operation_update_guard();

CREATE FUNCTION account_bare_login_exchange_operation_delete_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Bare LOGIN exchange operation is immutable and cannot be deleted'
        USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_exchange_operation_no_delete';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_bare_login_exchange_operation_delete_guard_trigger
    BEFORE DELETE ON account_bare_login_exchange_operations
    FOR EACH ROW
    EXECUTE FUNCTION account_bare_login_exchange_operation_delete_guard();

CREATE FUNCTION account_bare_login_response_envelope_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Bare LOGIN response envelope is immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_response_envelope_immutable';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_bare_login_response_envelope_update_guard_trigger
    BEFORE UPDATE ON account_bare_login_response_envelopes
    FOR EACH ROW
    EXECUTE FUNCTION account_bare_login_response_envelope_update_guard();

CREATE FUNCTION account_bare_login_response_envelope_delete_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Bare LOGIN response envelope is immutable and cannot be deleted'
        USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_response_envelope_no_delete';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_bare_login_response_envelope_delete_guard_trigger
    BEFORE DELETE ON account_bare_login_response_envelopes
    FOR EACH ROW
    EXECUTE FUNCTION account_bare_login_response_envelope_delete_guard();

CREATE FUNCTION account_bare_login_pending_commit_check()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    final_status VARCHAR(16);
BEGIN
    SELECT status INTO final_status
        FROM account_bare_login_exchange_operations
        WHERE operation_id = NEW.operation_id;
    IF final_status = 'PENDING' THEN
        RAISE EXCEPTION 'Bare LOGIN exchange cannot commit while still pending'
            USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_pending_commit_required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER account_bare_login_pending_commit_check_trigger
    AFTER INSERT OR UPDATE ON account_bare_login_exchange_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION account_bare_login_pending_commit_check();

CREATE FUNCTION account_bare_login_response_envelope_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    operation account_bare_login_exchange_operations%ROWTYPE;
BEGIN
    SELECT * INTO operation
        FROM account_bare_login_exchange_operations
        WHERE operation_id = NEW.operation_id;
    IF NOT FOUND
        OR operation.status <> 'COMMITTED'
        OR operation.source_connect_operation_id IS DISTINCT FROM NEW.source_connect_operation_id
        OR operation.account_id IS DISTINCT FROM NEW.account_id
        OR operation.tenant_id IS DISTINCT FROM NEW.tenant_id
        OR operation.connect_scope_hash IS DISTINCT FROM NEW.connect_scope_hash
        OR operation.request_id IS DISTINCT FROM NEW.request_id
        OR operation.request_digest_version IS DISTINCT FROM NEW.request_digest_version
        OR operation.request_digest IS DISTINCT FROM NEW.request_digest
        OR operation.context_evidence_digest IS DISTINCT FROM NEW.context_evidence_digest
        OR operation.authority_tuple_digest IS DISTINCT FROM NEW.authority_tuple_digest
        OR operation.issuance_fence_digest IS DISTINCT FROM NEW.issuance_fence_digest
        OR operation.postcondition_digest IS DISTINCT FROM NEW.postcondition_digest THEN
        RAISE EXCEPTION 'Bare LOGIN response envelope does not match its terminal operation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_response_envelope_operation_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_bare_login_response_envelope_insert_guard_trigger
    BEFORE INSERT ON account_bare_login_response_envelopes
    FOR EACH ROW
    EXECUTE FUNCTION account_bare_login_response_envelope_insert_guard();

CREATE FUNCTION account_bare_login_terminal_envelope_check()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'COMMITTED' AND NOT EXISTS (
        SELECT 1
        FROM account_bare_login_response_envelopes envelope
        WHERE envelope.operation_id = NEW.operation_id
            AND envelope.source_connect_operation_id = NEW.source_connect_operation_id
            AND envelope.account_id = NEW.account_id
            AND envelope.tenant_id = NEW.tenant_id
            AND envelope.connect_scope_hash = NEW.connect_scope_hash
            AND envelope.request_id = NEW.request_id
            AND envelope.request_digest_version = NEW.request_digest_version
            AND envelope.request_digest = NEW.request_digest
            AND envelope.context_evidence_digest = NEW.context_evidence_digest
            AND envelope.authority_tuple_digest = NEW.authority_tuple_digest
            AND envelope.issuance_fence_digest = NEW.issuance_fence_digest
            AND envelope.postcondition_digest = NEW.postcondition_digest
    ) THEN
        RAISE EXCEPTION 'Terminal bare LOGIN exchange requires its exact response envelope'
            USING ERRCODE = '23514', CONSTRAINT = 'account_bare_login_terminal_envelope_required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER account_bare_login_terminal_envelope_check_trigger
    AFTER INSERT OR UPDATE ON account_bare_login_exchange_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION account_bare_login_terminal_envelope_check();
-- [jooq ignore end]
