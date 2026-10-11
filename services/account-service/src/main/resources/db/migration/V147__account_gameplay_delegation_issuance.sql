-- Account-owned non-authorizing LOGIN issuance intent. This deliberately has no COMMITTED state:
-- the Account signer-promotion and current-authority projection proof required for activation do
-- not yet exist. The only mutable transition binds an exact signed-token identity once.
CREATE TABLE account_gameplay_delegation_issuance_operations (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    account_uuid UUID NOT NULL REFERENCES accounts (account_uuid) ON DELETE RESTRICT,
    caller_workload VARCHAR(256) NOT NULL,
    caller_context_id UUID NOT NULL,
    request_digest_version SMALLINT NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    token_jti UUID NOT NULL UNIQUE,
    token_generation BIGINT NOT NULL DEFAULT 1,
    issued_at_epoch_second BIGINT NOT NULL,
    not_before_epoch_second BIGINT NOT NULL,
    expires_at_epoch_second BIGINT NOT NULL,
    authority_issuer_generation BIGINT NOT NULL,
    authority_issuer_source_version BIGINT NOT NULL,
    authority_account_generation BIGINT NOT NULL,
    authority_account_source_version BIGINT NOT NULL,
    issuance_fence BIGINT NOT NULL,
    issuance_fence_source_version BIGINT NOT NULL,
    authority_tuple_canonical_bytes BYTEA NOT NULL,
    membership_version_canonical_bytes BYTEA NOT NULL,
    authority_source_versions_canonical_bytes BYTEA NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    token_hash VARCHAR(64),
    signer_kid VARCHAR(64),
    signer_generation VARCHAR(19),
    pending_registry_candidate_bytes BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_gameplay_delegation_operation_ids_check
        CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND operation_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND request_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND request_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND caller_context_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND caller_context_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND token_jti <> '00000000-0000-0000-0000-000000000000'::UUID
            AND token_jti::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT account_gameplay_delegation_caller_check
        CHECK (caller_workload ~ '^spiffe://firemud/ns/[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?/sa/game-session-service$'),
    CONSTRAINT account_gameplay_delegation_digest_check
        CHECK (request_digest_version = 1 AND request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_gameplay_delegation_time_check
        CHECK (token_generation = 1 AND issued_at_epoch_second > 0
            AND not_before_epoch_second > 0 AND not_before_epoch_second <= issued_at_epoch_second
            AND expires_at_epoch_second > issued_at_epoch_second
            AND expires_at_epoch_second - issued_at_epoch_second <= 300),
    CONSTRAINT account_gameplay_delegation_authority_check
        CHECK (authority_issuer_generation > 0 AND authority_issuer_source_version > 0
            AND authority_account_generation > 0 AND authority_account_source_version > 0
            AND issuance_fence > 0 AND issuance_fence_source_version > 0),
    CONSTRAINT account_gameplay_delegation_profile_bytes_check
        CHECK (octet_length(authority_tuple_canonical_bytes) BETWEEN 1 AND 4096
            AND convert_from(membership_version_canonical_bytes, 'UTF8') = '{}'
            AND octet_length(authority_source_versions_canonical_bytes) BETWEEN 1 AND 4096),
    CONSTRAINT account_gameplay_delegation_status_check CHECK (status = 'PENDING'),
    CONSTRAINT account_gameplay_delegation_candidate_shape_check
        CHECK ((token_hash IS NULL AND signer_kid IS NULL AND signer_generation IS NULL
                AND pending_registry_candidate_bytes IS NULL)
            OR (token_hash IS NOT NULL AND token_hash ~ '^[0-9a-f]{64}$'
                AND signer_kid IS NOT NULL AND signer_kid ~ '^[A-Za-z0-9_-]{1,64}$'
                AND signer_generation IS NOT NULL AND signer_generation ~ '^[1-9][0-9]{0,18}$'
                AND pending_registry_candidate_bytes IS NOT NULL
                AND octet_length(pending_registry_candidate_bytes) BETWEEN 1 AND 16384))
);

-- Insert and post-sign bind both lock and compare the existing Account authority sources. This
-- is intentionally the same issuer/account/fence model used by AccountAuthorityGenerationRepository.
-- [jooq ignore start]
CREATE FUNCTION account_gameplay_delegation_authority_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_issuer_generation BIGINT;
    current_issuer_source_version BIGINT;
    source_issuer_generation BIGINT;
    source_issuer_source_version BIGINT;
    current_issuer_sequence BIGINT;
    current_issuer_stream VARCHAR(2048);
    current_account_generation BIGINT;
    current_account_source_version BIGINT;
    source_account_generation BIGINT;
    source_account_source_version BIGINT;
    current_account_sequence BIGINT;
    current_account_stream VARCHAR(2048);
    current_account_cutoff_generation BIGINT;
    current_account_cutoff_stream VARCHAR(2048);
    current_account_cutoff_sequence BIGINT;
    current_issuance_fence BIGINT;
    current_fence_source_version BIGINT;
    expected_authority_tuple TEXT;
BEGIN
    -- Keep the owner lock order: Account row, issuer/account generations and fence, then the
    -- source rows whose exact optional cutoff is carried by the immutable pending intent.
    PERFORM account_uuid FROM accounts WHERE account_uuid = NEW.account_uuid FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account gameplay delegation Account source is missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_authority_current';
    END IF;
    SELECT generation, source_version
      INTO current_issuer_generation, current_issuer_source_version
      FROM account_authority_generations
      WHERE scope_kind = 'ISSUER' AND issuer_id = 'firemud-account-service'
        AND account_uuid IS NULL AND tenant_uuid IS NULL
      FOR UPDATE;
    SELECT generation, source_version
      INTO current_account_generation, current_account_source_version
      FROM account_authority_generations
      WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid
        AND issuer_id IS NULL AND tenant_uuid IS NULL
      FOR UPDATE;
    SELECT issuance_fence, source_version
      INTO current_issuance_fence, current_fence_source_version
      FROM account_authority_issuance_fences
      WHERE account_uuid = NEW.account_uuid
      FOR UPDATE;
    SELECT outbox_stream_key, current_generation, current_source_version, last_outbox_sequence
      INTO current_issuer_stream, source_issuer_generation, source_issuer_source_version,
           current_issuer_sequence
      FROM account_authority_source_records
      WHERE scope_kind = 'ISSUER' AND issuer_id = 'firemud-account-service'
        AND account_uuid IS NULL
        AND outbox_stream_key = 'account:auth-authority:v1:issuer/firemud-account-service'
      FOR UPDATE;
    SELECT outbox_stream_key, current_generation, current_source_version, last_outbox_sequence,
           cutoff_generation, cutoff_stream_key, cutoff_sequence
      INTO current_account_stream, source_account_generation, source_account_source_version,
           current_account_sequence, current_account_cutoff_generation,
           current_account_cutoff_stream, current_account_cutoff_sequence
      FROM account_authority_source_records
      WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid
        AND issuer_id IS NULL
        AND outbox_stream_key = 'account:auth-authority:v1:account/' || NEW.account_uuid::TEXT
      FOR UPDATE;

    IF current_account_sequence = 0
        AND current_account_cutoff_generation IS NULL
        AND current_account_cutoff_stream IS NULL
        AND current_account_cutoff_sequence IS NULL THEN
        expected_authority_tuple :=
            '{"accountAuthorityGeneration":' || source_account_generation::TEXT
            || ',"issuerAuthGeneration":' || source_issuer_generation::TEXT
            || ',"membershipAuthorityGeneration":{},"privateRealmGrantVersions":[]'
            || ',"tenantAuthorityGeneration":{}}';
    ELSIF current_account_sequence > 0
        AND current_account_cutoff_generation = source_account_generation
        AND current_account_cutoff_stream = current_account_stream
        AND current_account_cutoff_sequence = current_account_sequence THEN
        expected_authority_tuple :=
            '{"accountAuthorityGeneration":' || source_account_generation::TEXT
            || ',"accountSecurityCutoff":{"accountAuthorityGeneration":"'
            || current_account_cutoff_generation::TEXT || '","outboxSequence":"'
            || current_account_cutoff_sequence::TEXT || '","outboxStreamKey":"'
            || current_account_cutoff_stream || '"},"issuerAuthGeneration":'
            || source_issuer_generation::TEXT
            || ',"membershipAuthorityGeneration":{},"privateRealmGrantVersions":[]'
            || ',"tenantAuthorityGeneration":{}}';
    END IF;

    IF current_issuer_generation IS NULL OR current_account_generation IS NULL
        OR current_issuance_fence IS NULL OR current_issuer_stream IS NULL
        OR current_account_stream IS NULL OR current_issuer_sequence IS NULL
        OR current_account_sequence IS NULL OR source_issuer_generation IS NULL
        OR source_issuer_source_version IS NULL OR source_account_generation IS NULL
        OR source_account_source_version IS NULL OR current_fence_source_version IS NULL
        OR expected_authority_tuple IS NULL
        OR current_issuer_stream <> 'account:auth-authority:v1:issuer/firemud-account-service'
        OR current_account_stream <> 'account:auth-authority:v1:account/' || NEW.account_uuid::TEXT
        OR source_issuer_generation <> current_issuer_generation
        OR source_issuer_source_version <> current_issuer_source_version
        OR source_account_generation <> current_account_generation
        OR source_account_source_version <> current_account_source_version
        OR source_issuer_generation <> source_issuer_source_version
        OR source_issuer_generation <> current_issuer_sequence + 1
        OR source_account_generation <> source_account_source_version
        OR source_account_generation <> current_account_sequence + 1
        OR current_issuance_fence <> current_account_sequence + 1
        OR current_fence_source_version <> current_account_sequence + 1
        OR NEW.authority_issuer_generation <> current_issuer_generation
        OR NEW.authority_issuer_source_version <> current_issuer_source_version
        OR NEW.authority_account_generation <> current_account_generation
        OR NEW.authority_account_source_version <> current_account_source_version
        OR NEW.issuance_fence <> current_issuance_fence
        OR NEW.issuance_fence_source_version <> current_fence_source_version
        OR convert_from(NEW.authority_tuple_canonical_bytes, 'UTF8') <>
            expected_authority_tuple
        OR convert_from(NEW.membership_version_canonical_bytes, 'UTF8') <> '{}'
        OR convert_from(NEW.authority_source_versions_canonical_bytes, 'UTF8') <>
            '{"accountSourceVersion":' || current_account_source_version::text
            || ',"issuanceFenceSourceVersion":' || current_fence_source_version::text
            || ',"issuerSourceVersion":' || current_issuer_source_version::text || '}' THEN
        RAISE EXCEPTION 'Account gameplay delegation authority snapshot is stale or missing'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_authority_current';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_gameplay_delegation_authority_guard
    BEFORE INSERT OR UPDATE ON account_gameplay_delegation_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_gameplay_delegation_authority_guard();

CREATE FUNCTION account_gameplay_delegation_operation_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Account gameplay delegation issuance evidence cannot be deleted'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_operation_immutable';
    END IF;
    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
        OR NEW.caller_workload IS DISTINCT FROM OLD.caller_workload
        OR NEW.caller_context_id IS DISTINCT FROM OLD.caller_context_id
        OR NEW.request_digest_version IS DISTINCT FROM OLD.request_digest_version
        OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
        OR NEW.token_jti IS DISTINCT FROM OLD.token_jti
        OR NEW.token_generation IS DISTINCT FROM OLD.token_generation
        OR NEW.issued_at_epoch_second IS DISTINCT FROM OLD.issued_at_epoch_second
        OR NEW.not_before_epoch_second IS DISTINCT FROM OLD.not_before_epoch_second
        OR NEW.expires_at_epoch_second IS DISTINCT FROM OLD.expires_at_epoch_second
        OR NEW.authority_issuer_generation IS DISTINCT FROM OLD.authority_issuer_generation
        OR NEW.authority_issuer_source_version IS DISTINCT FROM OLD.authority_issuer_source_version
        OR NEW.authority_account_generation IS DISTINCT FROM OLD.authority_account_generation
        OR NEW.authority_account_source_version IS DISTINCT FROM OLD.authority_account_source_version
        OR NEW.issuance_fence IS DISTINCT FROM OLD.issuance_fence
        OR NEW.issuance_fence_source_version IS DISTINCT FROM OLD.issuance_fence_source_version
        OR NEW.authority_tuple_canonical_bytes IS DISTINCT FROM OLD.authority_tuple_canonical_bytes
        OR NEW.membership_version_canonical_bytes IS DISTINCT FROM OLD.membership_version_canonical_bytes
        OR NEW.authority_source_versions_canonical_bytes IS DISTINCT FROM OLD.authority_source_versions_canonical_bytes
        OR NEW.status IS DISTINCT FROM OLD.status
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Account gameplay delegation intent is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_operation_immutable';
    END IF;
    IF OLD.token_hash IS NOT NULL OR OLD.signer_kid IS NOT NULL
        OR OLD.signer_generation IS NOT NULL OR OLD.pending_registry_candidate_bytes IS NOT NULL
        OR NEW.token_hash IS NULL OR NEW.signer_kid IS NULL
        OR NEW.signer_generation IS NULL OR NEW.pending_registry_candidate_bytes IS NULL THEN
        RAISE EXCEPTION 'Account gameplay delegation token identity may be bound exactly once'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_candidate_one_way';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_gameplay_delegation_operation_guard
    BEFORE UPDATE OR DELETE ON account_gameplay_delegation_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_gameplay_delegation_operation_guard();

CREATE FUNCTION account_gameplay_delegation_operation_no_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM account_gameplay_delegation_issuance_operations) THEN
        RAISE EXCEPTION 'Account gameplay delegation issuance evidence cannot be truncated'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_delegation_operation_immutable';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_gameplay_delegation_operation_no_truncate
    BEFORE TRUNCATE ON account_gameplay_delegation_issuance_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_gameplay_delegation_operation_no_truncate();
-- [jooq ignore stop]
