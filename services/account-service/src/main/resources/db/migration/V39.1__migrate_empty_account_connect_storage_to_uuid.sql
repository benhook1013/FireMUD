-- V35 and V37 are already published with numeric tenant IDs. Preserve those
-- migration definitions and move only the provably empty storage shape forward.
-- No retained operation or encrypted response envelope may be reinterpreted.
-- [jooq ignore start]
LOCK TABLE account_connect_token_issuance_operations,
    account_connect_token_response_envelopes,
    account_bare_login_exchange_operations,
    account_bare_login_response_envelopes
    IN ACCESS EXCLUSIVE MODE;
-- [jooq ignore stop]

-- [jooq ignore start]
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM account_connect_token_issuance_operations)
        OR EXISTS (SELECT 1 FROM account_connect_token_response_envelopes)
        OR EXISTS (SELECT 1 FROM account_bare_login_exchange_operations)
        OR EXISTS (SELECT 1 FROM account_bare_login_response_envelopes) THEN
        RAISE EXCEPTION 'Account connect storage UUID migration requires all four operation and envelope tables to be empty'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_connect_storage_uuid_requires_empty_tables';
    END IF;
END;
$$;
-- [jooq ignore stop]

ALTER TABLE account_connect_token_issuance_operations
    DROP CONSTRAINT account_connect_token_issuance_identity_check;
ALTER TABLE account_connect_token_response_envelopes
    DROP CONSTRAINT account_connect_token_response_envelope_identity_check;
ALTER TABLE account_bare_login_exchange_operations
    DROP CONSTRAINT account_bare_login_exchange_identity_check;
ALTER TABLE account_bare_login_response_envelopes
    DROP CONSTRAINT account_bare_login_response_envelope_identity_check;

-- The locked empty-table precondition makes NULL a non-persisted cast expression;
-- no tenant identity, provenance, ciphertext, or replay evidence is synthesized.
-- jOOQ's DDL parser does not support PostgreSQL's USING clause. Keep the real
-- conversion below and mirror its resulting type with simple no-op declarations.
-- [jooq ignore start]
ALTER TABLE account_connect_token_issuance_operations
    ALTER COLUMN tenant_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE account_connect_token_response_envelopes
    ALTER COLUMN tenant_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE account_bare_login_exchange_operations
    ALTER COLUMN tenant_id TYPE UUID USING CAST(NULL AS UUID);
ALTER TABLE account_bare_login_response_envelopes
    ALTER COLUMN tenant_id TYPE UUID USING CAST(NULL AS UUID);
-- [jooq ignore stop]

ALTER TABLE account_connect_token_issuance_operations
    ALTER COLUMN tenant_id TYPE UUID;
ALTER TABLE account_connect_token_response_envelopes
    ALTER COLUMN tenant_id TYPE UUID;
ALTER TABLE account_bare_login_exchange_operations
    ALTER COLUMN tenant_id TYPE UUID;
ALTER TABLE account_bare_login_response_envelopes
    ALTER COLUMN tenant_id TYPE UUID;

ALTER TABLE account_connect_token_issuance_operations
    ADD CONSTRAINT account_connect_token_issuance_identity_check
        CHECK (account_id > 0
            AND tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND length(btrim(request_id)) BETWEEN 1 AND 128
            AND length(connect_scope_hash) = 71
            AND connect_scope_hash ~ '^sha256:[0-9a-f]{64}$'
            AND request_digest_version = 1
            AND octet_length(request_digest) = 32);

ALTER TABLE account_connect_token_response_envelopes
    ADD CONSTRAINT account_connect_token_response_envelope_identity_check
        CHECK (operation_kind = 'CONNECT_TOKEN_ISSUANCE'
            AND account_id > 0
            AND tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND length(connect_scope_hash) = 71
            AND connect_scope_hash ~ '^sha256:[0-9a-f]{64}$'
            AND length(btrim(request_id)) BETWEEN 1 AND 128
            AND request_digest_version = 1
            AND octet_length(request_digest) = 32);

ALTER TABLE account_bare_login_exchange_operations
    ADD CONSTRAINT account_bare_login_exchange_identity_check
        CHECK (account_id > 0
            AND tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND length(btrim(request_id)) BETWEEN 1 AND 128
            AND length(connect_scope_hash) = 71
            AND connect_scope_hash ~ '^sha256:[0-9a-f]{64}$'
            AND request_digest_version = 1
            AND octet_length(request_digest) = 32);

ALTER TABLE account_bare_login_response_envelopes
    ADD CONSTRAINT account_bare_login_response_envelope_identity_check
        CHECK (operation_kind = 'BARE_LOGIN_EXCHANGE'
            AND source_connect_operation_id IS NOT NULL
            AND account_id > 0
            AND tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND length(connect_scope_hash) = 71
            AND connect_scope_hash ~ '^sha256:[0-9a-f]{64}$'
            AND length(btrim(request_id)) BETWEEN 1 AND 128
            AND request_digest_version = 1
            AND octet_length(request_digest) = 32);
