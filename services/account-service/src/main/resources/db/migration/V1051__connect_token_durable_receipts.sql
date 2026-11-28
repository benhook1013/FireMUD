-- Dormant Account-owned storage. No issuer, registry writer, scheduler or cleanup is activated.
CREATE TABLE connect_token_issuance_operation (
    operation_id UUID PRIMARY KEY,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    tenant_uuid UUID NOT NULL,
    connect_scope_id VARCHAR(2048) NOT NULL CHECK (octet_length(connect_scope_id) BETWEEN 1 AND 2048),
    request_id VARCHAR(128) NOT NULL CHECK (octet_length(request_id) BETWEEN 1 AND 128),
    request_digest_version SMALLINT NOT NULL CHECK (request_digest_version = 1),
    request_digest TEXT NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'COMMITTED', 'FAILED', 'ABORTED')),
    token_jti UUID,
    token_hash TEXT CHECK (token_hash ~ '^sha256:[0-9a-f]{64}$'),
    replay_admission_fence BIGINT CHECK (replay_admission_fence > 0),
    issuance_evidence BYTEA,
    failure_code TEXT,
    reconciliation_blocked BOOLEAN NOT NULL DEFAULT TRUE,
    reconciliation_evidence BYTEA,
    response_recovery_expires_at TIMESTAMPTZ NOT NULL,
    receipt_retain_until TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    terminal_at TIMESTAMPTZ,
    UNIQUE (account_uuid, tenant_uuid, connect_scope_id, request_id),
    CHECK (response_recovery_expires_at > created_at
        AND receipt_retain_until >= response_recovery_expires_at),
    CHECK (issuance_evidence IS NULL OR octet_length(issuance_evidence) BETWEEN 1 AND 131072),
    CHECK (reconciliation_evidence IS NULL OR octet_length(reconciliation_evidence) BETWEEN 1 AND 131072),
    CHECK (reconciliation_blocked OR reconciliation_evidence IS NOT NULL),
    CHECK (
      (status = 'PENDING' AND terminal_at IS NULL AND token_jti IS NULL
        AND token_hash IS NULL AND replay_admission_fence IS NULL
        AND issuance_evidence IS NULL AND failure_code IS NULL)
      OR
      (status = 'COMMITTED' AND terminal_at IS NOT NULL AND token_jti IS NOT NULL
        AND token_hash IS NOT NULL AND replay_admission_fence IS NOT NULL
        AND issuance_evidence IS NOT NULL AND failure_code IS NULL)
      OR
      (status IN ('FAILED', 'ABORTED') AND terminal_at IS NOT NULL AND token_jti IS NULL
        AND token_hash IS NULL AND replay_admission_fence IS NULL AND issuance_evidence IS NULL
        AND failure_code IS NOT NULL AND octet_length(failure_code) BETWEEN 1 AND 128))
);

-- Ciphertext is separate from the compact receipt; plaintext tokens have no storage field.
-- Cryptographic authentication and semantic evidence validation remain issuer responsibilities.
CREATE TABLE connect_token_response_envelope (
    operation_id UUID PRIMARY KEY REFERENCES connect_token_issuance_operation(operation_id) ON DELETE RESTRICT,
    encryption_key_id TEXT NOT NULL CHECK (octet_length(encryption_key_id) BETWEEN 1 AND 256),
    encryption_version SMALLINT NOT NULL CHECK (encryption_version > 0),
    encrypted_response BYTEA NOT NULL CHECK (octet_length(encrypted_response) BETWEEN 1 AND 65536),
    authenticated_binding BYTEA NOT NULL CHECK (octet_length(authenticated_binding) BETWEEN 1 AND 131072)
);

-- [jooq ignore start]
CREATE FUNCTION connect_token_operation_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Connect compact receipt cleanup requires an owner safe-watermark implementation'
          USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NOT NEW.reconciliation_blocked
           OR NEW.reconciliation_evidence IS NOT NULL THEN
            RAISE EXCEPTION 'Connect operation must begin pending and unreconciled' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.operation_id, NEW.account_uuid, NEW.tenant_uuid, NEW.connect_scope_id,
        NEW.request_id, NEW.request_digest_version, NEW.request_digest, NEW.created_at,
        NEW.response_recovery_expires_at, NEW.receipt_retain_until)
       IS DISTINCT FROM
       (OLD.operation_id, OLD.account_uuid, OLD.tenant_uuid, OLD.connect_scope_id,
        OLD.request_id, OLD.request_digest_version, OLD.request_digest, OLD.created_at,
        OLD.response_recovery_expires_at, OLD.receipt_retain_until) THEN
        RAISE EXCEPTION 'Connect operation identity and retention are immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        -- Terminal outcome is permanent, including when current authority later denies replay.
        IF (NEW.status, NEW.token_jti, NEW.token_hash, NEW.replay_admission_fence,
            NEW.issuance_evidence, NEW.failure_code, NEW.terminal_at)
           IS DISTINCT FROM
           (OLD.status, OLD.token_jti, OLD.token_hash, OLD.replay_admission_fence,
            OLD.issuance_evidence, OLD.failure_code, OLD.terminal_at) THEN
            RAISE EXCEPTION 'Connect terminal outcome is immutable' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.status <> 'PENDING' THEN
        NEW.terminal_at = CURRENT_TIMESTAMP;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION connect_token_envelope_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    op connect_token_issuance_operation%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Connect response envelope is immutable' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT op FROM connect_token_issuance_operation
        WHERE operation_id = CASE WHEN TG_OP = 'DELETE' THEN OLD.operation_id ELSE NEW.operation_id END
        FOR UPDATE;
    IF TG_OP = 'INSERT' THEN
        IF op.status <> 'PENDING' THEN
            RAISE EXCEPTION 'Connect response cannot be replaced after completion' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF op.status NOT IN ('COMMITTED', 'FAILED') OR op.reconciliation_blocked
       OR clock_timestamp() < op.response_recovery_expires_at THEN
        RAISE EXCEPTION 'Connect response is held or within recovery horizon' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;

CREATE FUNCTION connect_token_completion_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    op connect_token_issuance_operation%ROWTYPE;
BEGIN
    SELECT * INTO STRICT op FROM connect_token_issuance_operation WHERE operation_id = NEW.operation_id;
    -- Only an outcome transition requires a retained response; later receipt-only reconciliation is valid.
    IF NEW.status IN ('COMMITTED', 'FAILED') AND OLD.status = 'PENDING'
       AND NOT EXISTS (SELECT 1 FROM connect_token_response_envelope WHERE operation_id = NEW.operation_id) THEN
        RAISE EXCEPTION 'Connect terminal response must be durably recorded before commit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER connect_token_operation_mutation
BEFORE INSERT OR UPDATE OR DELETE ON connect_token_issuance_operation
FOR EACH ROW EXECUTE FUNCTION connect_token_operation_guard();
CREATE TRIGGER connect_token_envelope_mutation
BEFORE INSERT OR UPDATE OR DELETE ON connect_token_response_envelope
FOR EACH ROW EXECUTE FUNCTION connect_token_envelope_guard();
CREATE CONSTRAINT TRIGGER connect_token_terminal_response
AFTER UPDATE ON connect_token_issuance_operation DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION connect_token_completion_guard();
-- [jooq ignore stop]
