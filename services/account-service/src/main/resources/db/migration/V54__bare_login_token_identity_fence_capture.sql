-- Immutable initial Account source evidence for one exact bare-LOGIN private delegation token.
-- This does not implement the later monotonic logout/rotation transitions or their registry proof.
CREATE TABLE account_bare_login_token_identity_fence_captures (
    token_hash VARCHAR(64) PRIMARY KEY,
    schema_name VARCHAR(64) NOT NULL,
    operation_id UUID NOT NULL UNIQUE
        REFERENCES account_bare_login_exchange_operations(operation_id) ON DELETE RESTRICT,
    source_connect_operation_id UUID NOT NULL
        REFERENCES account_connect_token_issuance_operations(operation_id) ON DELETE RESTRICT,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    tenant_id UUID NOT NULL,
    connect_scope_hash VARCHAR(71) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    request_digest_version INTEGER NOT NULL,
    request_digest BYTEA NOT NULL,
    token_identity VARCHAR(128) NOT NULL,
    profile VARCHAR(64) NOT NULL,
    token_identity_fence BIGINT NOT NULL,
    token_identity_fence_source_version BIGINT NOT NULL,
    account_issuance_fence BIGINT NOT NULL,
    account_issuance_fence_source_version BIGINT NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_bare_login_token_identity_fence_identity_check
        CHECK (account_id > 0
            AND schema_name = 'account-bare-login-token-identity-fence/v1'
            AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND length(connect_scope_hash) = 71
            AND connect_scope_hash ~ '^sha256:[0-9a-f]{64}$'
            AND length(btrim(request_id)) BETWEEN 1 AND 128
            AND request_digest_version = 1
            AND octet_length(request_digest) = 32
            AND length(btrim(token_identity)) BETWEEN 1 AND 128
            AND profile = 'game-session-account-delegation'
            AND length(token_hash) = 64
            AND token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_bare_login_token_identity_fence_initial_check
        CHECK (token_identity_fence = 1
            AND token_identity_fence_source_version = 1
            AND account_issuance_fence > 0
            AND account_issuance_fence_source_version > 0),
    CONSTRAINT account_bare_login_token_identity_fence_token_identity_uq
        UNIQUE (profile, token_identity)
);

-- The only producer in scope is bare first-party LOGIN. The row is initial capture evidence; later
-- logout/rotation work must add and prove its own complete monotonic transition protocol.
-- [jooq ignore start]
CREATE FUNCTION account_bare_login_token_identity_fence_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    account_row accounts%ROWTYPE;
    operation_row account_bare_login_exchange_operations%ROWTYPE;
    issuance_fence_row account_authority_issuance_fences%ROWTYPE;
BEGIN
    SELECT * INTO account_row
        FROM accounts
        WHERE account_uuid = NEW.account_uuid
        FOR UPDATE;
    IF NOT FOUND
        OR account_row.id IS DISTINCT FROM NEW.account_id
        OR account_row.account_uuid_source_numeric_id IS DISTINCT FROM account_row.id
        OR account_row.account_uuid_provenance IS NULL
        OR account_row.account_uuid_provenance NOT IN (
            'ACCOUNT_V29_MIGRATION', 'ACCOUNT_REPOSITORY_INSERT', 'ACCOUNT_DATABASE_INSERT') THEN
        RAISE EXCEPTION 'Bare LOGIN identity fence has no exact canonical Account provenance'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_bare_login_token_identity_fence_account_binding';
    END IF;

    SELECT * INTO operation_row
        FROM account_bare_login_exchange_operations
        WHERE operation_id = NEW.operation_id
        FOR UPDATE;
    IF NOT FOUND
        OR operation_row.status IS DISTINCT FROM 'PENDING'
        OR operation_row.operation_id IS DISTINCT FROM NEW.operation_id
        OR operation_row.source_connect_operation_id IS DISTINCT FROM NEW.source_connect_operation_id
        OR operation_row.account_id IS DISTINCT FROM NEW.account_id
        OR operation_row.tenant_id IS DISTINCT FROM NEW.tenant_id
        OR operation_row.connect_scope_hash IS DISTINCT FROM NEW.connect_scope_hash
        OR operation_row.request_id IS DISTINCT FROM NEW.request_id
        OR operation_row.request_digest_version IS DISTINCT FROM NEW.request_digest_version
        OR operation_row.request_digest IS DISTINCT FROM NEW.request_digest
        OR operation_row.token_identity IS DISTINCT FROM NEW.token_identity
        OR encode(operation_row.token_hash, 'hex') IS DISTINCT FROM NEW.token_hash
        OR NEW.schema_name IS DISTINCT FROM 'account-bare-login-token-identity-fence/v1'
        OR NEW.profile IS DISTINCT FROM 'game-session-account-delegation'
        OR NEW.token_identity_fence IS DISTINCT FROM 1
        OR NEW.token_identity_fence_source_version IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION 'Bare LOGIN identity fence does not match its exact pending operation'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_bare_login_token_identity_fence_operation_binding';
    END IF;

    SELECT * INTO issuance_fence_row
        FROM account_authority_issuance_fences
        WHERE account_uuid = NEW.account_uuid
        FOR UPDATE;
    IF NOT FOUND
        OR issuance_fence_row.issuance_fence IS DISTINCT FROM NEW.account_issuance_fence
        OR issuance_fence_row.source_version
            IS DISTINCT FROM NEW.account_issuance_fence_source_version THEN
        RAISE EXCEPTION 'Bare LOGIN identity fence lacks the exact current Account issuance fence'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_bare_login_token_identity_fence_account_issuance_binding';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER account_bare_login_token_identity_fence_insert_guard_trigger
    BEFORE INSERT ON account_bare_login_token_identity_fence_captures
    FOR EACH ROW
    EXECUTE FUNCTION account_bare_login_token_identity_fence_insert_guard();

CREATE FUNCTION account_bare_login_token_identity_fence_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Bare LOGIN token identity fence capture is immutable and retained'
        USING ERRCODE = '23514',
            CONSTRAINT = 'account_bare_login_token_identity_fence_immutable';
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_bare_login_token_identity_fence_immutable_trigger
    BEFORE UPDATE OR DELETE ON account_bare_login_token_identity_fence_captures
    FOR EACH ROW
    EXECUTE FUNCTION account_bare_login_token_identity_fence_immutable_guard();

CREATE TRIGGER account_bare_login_token_identity_fence_no_truncate_trigger
    BEFORE TRUNCATE ON account_bare_login_token_identity_fence_captures
    FOR EACH STATEMENT
    EXECUTE FUNCTION account_bare_login_token_identity_fence_immutable_guard();
-- [jooq ignore end]
