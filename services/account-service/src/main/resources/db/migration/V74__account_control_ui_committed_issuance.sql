CREATE SEQUENCE account_control_ui_source_fence START WITH 1;

CREATE TABLE account_control_ui_issuance_operations (
    request_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    token_jti UUID NOT NULL UNIQUE,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid),
    tenant_uuid UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id),
    caller_workload VARCHAR(256) NOT NULL,
    caller_context_id UUID NOT NULL,
    request_mac_key_id VARCHAR(32) NOT NULL,
    request_digest VARCHAR(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    claims_payload BYTEA NOT NULL CHECK (octet_length(claims_payload) BETWEEN 1 AND 16384),
    source_payload BYTEA NOT NULL CHECK (octet_length(source_payload) BETWEEN 1 AND 131072),
    bundle_payload BYTEA NOT NULL CHECK (octet_length(bundle_payload) BETWEEN 1 AND 131072),
    signer_receipt BYTEA NOT NULL CHECK (octet_length(signer_receipt) BETWEEN 1 AND 65536),
    issued_at_epoch_second BIGINT NOT NULL CHECK (issued_at_epoch_second > 0),
    expires_at_epoch_second BIGINT NOT NULL,
    recovery_expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PREPARED', 'CANDIDATE', 'COMMITTED', 'FAILED', 'REVOKING', 'REVOKED')),
    token_hash VARCHAR(64) UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    pending_registry BYTEA,
    active_registry BYTEA,
    pending_receipt BYTEA,
    committed_at TIMESTAMPTZ,
    recovery_failed_at TIMESTAMPTZ,
    revocation_receipt BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (expires_at_epoch_second > issued_at_epoch_second
        AND expires_at_epoch_second <= issued_at_epoch_second + 300),
    CHECK ((status IN ('PREPARED','FAILED') AND token_hash IS NULL AND pending_registry IS NULL
            AND active_registry IS NULL AND pending_receipt IS NULL AND committed_at IS NULL)
        OR (status = 'CANDIDATE' AND token_hash IS NOT NULL AND pending_registry IS NOT NULL
            AND active_registry IS NOT NULL AND pending_receipt IS NULL AND committed_at IS NULL)
        OR (status = 'COMMITTED' AND token_hash IS NOT NULL AND pending_registry IS NOT NULL
            AND active_registry IS NOT NULL AND pending_receipt IS NOT NULL AND committed_at IS NOT NULL)
        OR (status IN ('REVOKING','REVOKED') AND token_hash IS NOT NULL AND pending_registry IS NOT NULL
            AND active_registry IS NOT NULL
            AND ((pending_receipt IS NULL AND committed_at IS NULL)
                OR (pending_receipt IS NOT NULL AND committed_at IS NOT NULL)))),
    CHECK ((status IN ('FAILED','REVOKING','REVOKED')) = (recovery_failed_at IS NOT NULL)),
    CHECK ((status = 'REVOKED') = (revocation_receipt IS NOT NULL))
);

CREATE TABLE account_control_ui_response_envelopes (
    operation_id UUID PRIMARY KEY REFERENCES account_control_ui_issuance_operations(operation_id),
    encrypted_response BYTEA NOT NULL CHECK (octet_length(encrypted_response) BETWEEN 1 AND 65536),
    owner_binding BYTEA NOT NULL CHECK (octet_length(owner_binding) BETWEEN 1 AND 8192),
    recovery_expires_at TIMESTAMPTZ NOT NULL
);

-- [jooq ignore start]
ALTER TABLE account_control_ui_issuance_operations
    ADD CONSTRAINT account_control_ui_recovery_expiry_bound CHECK (
        recovery_expires_at > to_timestamp(issued_at_epoch_second)
        AND recovery_expires_at <= to_timestamp(issued_at_epoch_second + 60)
        AND recovery_expires_at <= to_timestamp(expires_at_epoch_second));

CREATE FUNCTION account_control_ui_issuance_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Control-ui issuance evidence cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status','token_hash','pending_registry','active_registry',
            'pending_receipt','committed_at','recovery_failed_at','revocation_receipt']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','token_hash','pending_registry','active_registry',
            'pending_receipt','committed_at','recovery_failed_at','revocation_receipt'])
        OR NOT ((OLD.status = 'PREPARED' AND NEW.status = 'CANDIDATE')
            OR (OLD.status = 'CANDIDATE' AND NEW.status = 'COMMITTED')
            OR (OLD.status = 'PREPARED' AND NEW.status = 'FAILED')
            OR (OLD.status IN ('CANDIDATE','COMMITTED') AND NEW.status = 'REVOKING')
            OR (OLD.status = 'REVOKING' AND NEW.status = 'REVOKED'))
        OR (OLD.status <> 'PREPARED' AND
            (NEW.token_hash IS DISTINCT FROM OLD.token_hash
            OR NEW.pending_registry IS DISTINCT FROM OLD.pending_registry
            OR NEW.active_registry IS DISTINCT FROM OLD.active_registry))
        OR (NEW.status IN ('REVOKING','REVOKED') AND
            (NEW.pending_receipt IS DISTINCT FROM OLD.pending_receipt
            OR NEW.committed_at IS DISTINCT FROM OLD.committed_at))
        OR (OLD.status = 'REVOKING' AND NEW.recovery_failed_at IS DISTINCT FROM OLD.recovery_failed_at) THEN
        RAISE EXCEPTION 'Control-ui original operation or candidate cannot change' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'COMMITTED' AND NOT EXISTS (
        SELECT 1 FROM account_control_ui_response_envelopes e
        WHERE e.operation_id = NEW.operation_id AND e.recovery_expires_at = NEW.recovery_expires_at) THEN
        RAISE EXCEPTION 'Protected original control-ui response is absent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_control_ui_issuance_no_truncate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Control-ui issuance evidence cannot be truncated' USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION account_control_ui_response_immutable_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Control-ui response evidence is immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_control_ui_issuance_immutable
    BEFORE UPDATE OR DELETE ON account_control_ui_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_issuance_guard();
CREATE TRIGGER account_control_ui_issuance_no_truncate
    BEFORE TRUNCATE ON account_control_ui_issuance_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_control_ui_issuance_no_truncate();
CREATE TRIGGER account_control_ui_response_immutable
    BEFORE UPDATE OR DELETE ON account_control_ui_response_envelopes
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_response_immutable_guard();
CREATE TRIGGER account_control_ui_response_no_truncate
    BEFORE TRUNCATE ON account_control_ui_response_envelopes
    FOR EACH STATEMENT EXECUTE FUNCTION account_control_ui_response_immutable_guard();
-- [jooq ignore stop]
