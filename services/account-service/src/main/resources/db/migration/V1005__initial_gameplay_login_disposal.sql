-- Purpose-specific disposal is separate from V151 public logout/Gateway completion.
-- Immutable issuance remains retained; this guard denies every later original-request writer.
CREATE TABLE account_initial_gameplay_login_disposals (
    request_id UUID PRIMARY KEY,
    disposal_operation_id UUID NOT NULL UNIQUE,
    caller_workload VARCHAR(256) NOT NULL,
    caller_context_id UUID NOT NULL,
    disposal_digest_version SMALLINT NOT NULL DEFAULT 1 CHECK (disposal_digest_version = 1),
    disposal_digest VARCHAR(64) NOT NULL CHECK (disposal_digest ~ '^[0-9a-f]{64}$'),
    witness_canonical_bytes BYTEA NOT NULL CHECK (octet_length(witness_canonical_bytes) BETWEEN 1 AND 1024),
    account_uuid UUID REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    issuance_operation_id UUID REFERENCES account_gameplay_delegation_issuance_operations(operation_id) ON DELETE RESTRICT,
    original_token_hash VARCHAR(64) CHECK (original_token_hash ~ '^[0-9a-f]{64}$'),
    pending_record_bytes BYTEA,
    tombstone_bytes BYTEA,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'RETIRED', 'NO_TOKEN')),
    owner_proof_kind VARCHAR(32) NOT NULL DEFAULT 'NONE'
        CHECK (owner_proof_kind IN ('NONE', 'PHYSICAL_TOKEN_DENIAL', 'NO_ISSUANCE_BINDING')),
    owner_proof_canonical_bytes BYTEA,
    owner_proof_sha256 VARCHAR(64),
    local_aof_count BIGINT,
    replica_aof_count BIGINT,
    retention_hold BOOLEAN NOT NULL DEFAULT TRUE CHECK (retention_hold),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (request_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND disposal_operation_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND caller_context_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CHECK (caller_workload ~ '^spiffe://firemud/ns/[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?/sa/game-session-service$'),
    CHECK ((account_uuid IS NULL) = (issuance_operation_id IS NULL)),
    CHECK ((original_token_hash IS NULL AND pending_record_bytes IS NULL AND tombstone_bytes IS NULL)
        OR (original_token_hash IS NOT NULL AND pending_record_bytes IS NOT NULL AND tombstone_bytes IS NOT NULL
            AND octet_length(pending_record_bytes) BETWEEN 1 AND 16384
            AND octet_length(tombstone_bytes) BETWEEN 1 AND 4096)),
    CHECK ((state = 'PENDING' AND owner_proof_kind = 'NONE'
            AND owner_proof_canonical_bytes IS NULL AND owner_proof_sha256 IS NULL
            AND local_aof_count IS NULL AND replica_aof_count IS NULL)
        OR (state = 'RETIRED' AND original_token_hash IS NOT NULL
            AND owner_proof_kind = 'PHYSICAL_TOKEN_DENIAL'
            AND owner_proof_canonical_bytes IS NOT NULL AND owner_proof_sha256 IS NOT NULL
            AND local_aof_count IS NOT NULL AND replica_aof_count IS NOT NULL
            AND local_aof_count >= 1 AND replica_aof_count >= 1)
        OR (state = 'NO_TOKEN' AND original_token_hash IS NULL AND owner_proof_kind = 'NO_ISSUANCE_BINDING'
            AND owner_proof_canonical_bytes IS NOT NULL AND owner_proof_sha256 IS NOT NULL
            AND local_aof_count IS NULL AND replica_aof_count IS NULL)),
    CHECK (owner_proof_canonical_bytes IS NULL OR octet_length(owner_proof_canonical_bytes) BETWEEN 1 AND 4096),
    CHECK (owner_proof_sha256 IS NULL OR owner_proof_sha256 ~ '^[0-9a-f]{64}$')
);

-- [jooq ignore start]
CREATE FUNCTION account_initial_gameplay_login_disposal_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PENDING' OR NEW.owner_proof_kind <> 'NONE'
            OR NEW.owner_proof_canonical_bytes IS NOT NULL OR NEW.owner_proof_sha256 IS NOT NULL THEN
            RAISE EXCEPTION 'Initial LOGIN disposal must start with durable pending intent' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'Initial LOGIN disposal evidence is retained' USING ERRCODE = '23514';
    END IF;
    -- NO_TOKEN is a distinct closed shape, but no protected binding-history/restore
    -- qualification producer is currently present. Current-primary/null-field checks cannot
    -- supply that authority, so neither direct inserts nor updates can fabricate the variant.
    IF NEW.state = 'NO_TOKEN' THEN
        RAISE EXCEPTION 'Initial LOGIN intact binding-history qualification is unavailable' USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['state', 'local_aof_count', 'replica_aof_count', 'owner_proof_kind', 'owner_proof_canonical_bytes', 'owner_proof_sha256'])
        IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state', 'local_aof_count', 'replica_aof_count', 'owner_proof_kind', 'owner_proof_canonical_bytes', 'owner_proof_sha256'])
        OR OLD.state <> 'PENDING' OR NEW.state <> 'RETIRED' THEN
        RAISE EXCEPTION 'Initial LOGIN disposal identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER account_initial_gameplay_login_disposal_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON account_initial_gameplay_login_disposals
    FOR EACH ROW EXECUTE FUNCTION account_initial_gameplay_login_disposal_immutable();
CREATE TRIGGER account_initial_gameplay_login_disposal_no_truncate
    BEFORE TRUNCATE ON account_initial_gameplay_login_disposals
    FOR EACH STATEMENT EXECUTE FUNCTION account_initial_gameplay_login_disposal_immutable();

CREATE FUNCTION account_initial_gameplay_login_writer_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('account-gameplay-delegation-request/' || NEW.request_id::text, 0));
    IF EXISTS (SELECT 1 FROM account_initial_gameplay_login_disposals WHERE request_id = NEW.request_id) THEN
        RAISE EXCEPTION 'Original LOGIN is disposed or disposal pending' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER zz_account_initial_gameplay_login_writer_guard
    BEFORE INSERT OR UPDATE ON account_gameplay_delegation_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_initial_gameplay_login_writer_guard();
-- [jooq ignore stop]
