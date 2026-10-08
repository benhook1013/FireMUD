-- Exact compact delegation candidates are separately Account-encrypted and remain PENDING.
-- This table stores no plaintext JWT and introduces no COMMITTED/ACTIVE state or recovery API.
CREATE TABLE account_gameplay_delegation_response_envelopes (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    token_hash VARCHAR(64) NOT NULL,
    signer_kid VARCHAR(64) NOT NULL,
    signer_generation VARCHAR(19) NOT NULL,
    authority_evidence_bundle_sha256 VARCHAR(64) NOT NULL,
    issuance_fence BIGINT NOT NULL,
    response_recovery_expiry_epoch_ms BIGINT NOT NULL,
    envelope_sha256 VARCHAR(64) NOT NULL,
    envelope_bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_gameplay_delegation_response_candidate_check
        CHECK (token_hash ~ '^[0-9a-f]{64}$'
            AND signer_kid ~ '^[A-Za-z0-9_-]{1,64}$'
            AND signer_generation ~ '^[1-9][0-9]{0,18}$'),
    CONSTRAINT account_gameplay_delegation_response_evidence_check
        CHECK (authority_evidence_bundle_sha256 ~ '^[0-9a-f]{64}$'
            AND issuance_fence > 0),
    CONSTRAINT account_gameplay_delegation_response_expiry_check
        CHECK (response_recovery_expiry_epoch_ms > 0
            AND response_recovery_expiry_epoch_ms % 1000 = 0),
    CONSTRAINT account_gameplay_delegation_response_hash_check
        CHECK (envelope_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_gameplay_delegation_response_size_check
        CHECK (octet_length(envelope_bytes) BETWEEN 1 AND 65610),
    CONSTRAINT account_gameplay_delegation_response_operation_fk
        FOREIGN KEY (operation_id)
        REFERENCES account_gameplay_delegation_issuance_operations(operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_gameplay_delegation_response_request_fk
        FOREIGN KEY (request_id)
        REFERENCES account_gameplay_delegation_issuance_operations(request_id)
        ON DELETE RESTRICT
);

-- The trigger binds only immutable ciphertext metadata to the exact PENDING operation and the
-- already-persisted owner bundle. Java additionally checks current locked generations, canonical
-- bytes, the transient JWT hash, and exact readback. Neither layer claims signature validity,
-- completed external postconditions, or permission to recover plaintext.
-- [jooq ignore start]
CREATE FUNCTION account_gameplay_delegation_response_envelope_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    operation_row RECORD;
    bundle_row RECORD;
    bundle JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Account gameplay delegation response envelope is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_response_immutable';
    END IF;

    SELECT * INTO operation_row
      FROM account_gameplay_delegation_issuance_operations
      WHERE operation_id = NEW.operation_id
      FOR UPDATE;
    IF operation_row.operation_id IS NULL
        OR operation_row.request_id <> NEW.request_id
        OR operation_row.status <> 'PENDING'
        OR operation_row.token_hash IS NULL
        OR operation_row.token_hash <> NEW.token_hash
        OR operation_row.signer_kid <> NEW.signer_kid
        OR operation_row.signer_generation <> NEW.signer_generation
        OR operation_row.issuance_fence <> NEW.issuance_fence
        OR NEW.response_recovery_expiry_epoch_ms / 1000 <> operation_row.expires_at_epoch_second
        OR NEW.response_recovery_expiry_epoch_ms % 1000 <> 0 THEN
        RAISE EXCEPTION 'Account gameplay delegation response has no exact PENDING candidate'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_response_operation_match';
    END IF;

    SELECT request_id, account_uuid, bundle_schema, issuance_fence, canonical_sha256,
        canonical_bundle_bytes
      INTO bundle_row
      FROM account_gameplay_delegation_auth_evidence_bundles
      WHERE operation_id = NEW.operation_id;
    IF bundle_row.request_id IS NULL
        OR bundle_row.request_id <> NEW.request_id
        OR bundle_row.account_uuid <> operation_row.account_uuid
        OR bundle_row.bundle_schema <> 'account-auth-evidence-bundle/v1'
        OR bundle_row.issuance_fence <> operation_row.issuance_fence::text
        OR bundle_row.canonical_sha256 <> NEW.authority_evidence_bundle_sha256
        OR octet_length(bundle_row.canonical_bundle_bytes) > 8192 THEN
        RAISE EXCEPTION 'Account gameplay delegation response has no exact owner evidence bundle'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_response_evidence_match';
    END IF;
    bundle := convert_from(bundle_row.canonical_bundle_bytes, 'UTF8')::jsonb;
    IF bundle->>'schema' IS DISTINCT FROM bundle_row.bundle_schema
        OR bundle#>>'{operation,operationId}' IS DISTINCT FROM NEW.operation_id::text
        OR bundle#>>'{operation,requestId}' IS DISTINCT FROM NEW.request_id::text
        OR bundle#>>'{tokenIdentity,jti}' IS DISTINCT FROM operation_row.token_jti::text
        OR bundle#>>'{operation,requestDigest}' IS DISTINCT FROM operation_row.request_digest
        OR bundle->>'issuanceFence' IS DISTINCT FROM operation_row.issuance_fence::text THEN
        RAISE EXCEPTION 'Account gameplay delegation response evidence does not match its operation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_response_evidence_match';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_gameplay_delegation_response_no_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM account_gameplay_delegation_response_envelopes) THEN
        RAISE EXCEPTION 'Account gameplay delegation response envelopes cannot be truncated'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_response_immutable';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_gameplay_delegation_response_envelope_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_gameplay_delegation_response_envelopes
    FOR EACH ROW EXECUTE FUNCTION account_gameplay_delegation_response_envelope_guard();
CREATE TRIGGER account_gameplay_delegation_response_no_truncate
    BEFORE TRUNCATE ON account_gameplay_delegation_response_envelopes
    FOR EACH STATEMENT EXECUTE FUNCTION account_gameplay_delegation_response_no_truncate();
-- [jooq ignore stop]
