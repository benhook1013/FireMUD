-- Bind each newly prepared gameplay LOGIN operation to the exact transient credential
-- presentation using an Account-owned HMAC key. The key ID identifies real retained key
-- material; it is distinct from this digest schema version. Existing v1 rows remain unchanged
-- and non-authorizing because they have no credential binding.
ALTER TABLE account_gameplay_delegation_issuance_operations
    DROP CONSTRAINT account_gameplay_delegation_digest_check;

ALTER TABLE account_gameplay_delegation_issuance_operations
    ADD CONSTRAINT account_gameplay_delegation_digest_check
        CHECK (request_digest_version IN (1, 2) AND request_digest ~ '^[0-9a-f]{64}$');

ALTER TABLE account_gameplay_delegation_issuance_operations
    ADD COLUMN credential_request_digest_version SMALLINT;

ALTER TABLE account_gameplay_delegation_issuance_operations
    ADD COLUMN credential_digest_key_id VARCHAR(32);

ALTER TABLE account_gameplay_delegation_issuance_operations
    ADD COLUMN credential_request_digest VARCHAR(64);

ALTER TABLE account_gameplay_delegation_issuance_operations
    ADD CONSTRAINT account_gameplay_delegation_credential_request_binding_check
        CHECK ((credential_request_digest_version IS NULL
                AND credential_digest_key_id IS NULL
                AND credential_request_digest IS NULL)
            OR (credential_request_digest_version = 1
                AND credential_digest_key_id IS NOT NULL
                AND credential_digest_key_id ~ '^[A-Za-z0-9_-]{1,32}$'
                AND credential_request_digest IS NOT NULL
                AND credential_request_digest ~ '^[0-9a-f]{64}$'));

-- New operations must be keyed from inception. Historical unbound operations are retained, but
-- cannot bind a token candidate or advance to COMMITTED after this migration.
-- [jooq ignore start]
CREATE FUNCTION account_gameplay_delegation_credential_request_binding_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.request_digest_version IS DISTINCT FROM 2
            OR NEW.credential_request_digest_version IS DISTINCT FROM 1
            OR NEW.credential_digest_key_id IS NULL
            OR NEW.credential_request_digest IS NULL THEN
            RAISE EXCEPTION 'Gameplay LOGIN requires an immutable keyed credential request binding'
                USING ERRCODE = '23514',
                    CONSTRAINT = 'account_gameplay_delegation_credential_request_binding_required';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.credential_request_digest_version IS DISTINCT FROM OLD.credential_request_digest_version
        OR NEW.credential_digest_key_id IS DISTINCT FROM OLD.credential_digest_key_id
        OR NEW.credential_request_digest IS DISTINCT FROM OLD.credential_request_digest THEN
        RAISE EXCEPTION 'Gameplay LOGIN credential request binding is immutable'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_gameplay_delegation_credential_request_binding_immutable';
    END IF;

    IF OLD.credential_request_digest_version IS NULL
        AND (NEW.status IS DISTINCT FROM OLD.status
            OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
            OR NEW.signer_kid IS DISTINCT FROM OLD.signer_kid
            OR NEW.signer_generation IS DISTINCT FROM OLD.signer_generation
            OR NEW.pending_registry_candidate_bytes IS DISTINCT FROM OLD.pending_registry_candidate_bytes) THEN
        RAISE EXCEPTION 'Historical unbound gameplay LOGIN evidence cannot advance'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_gameplay_delegation_unbound_request_cannot_advance';
    END IF;

    IF NEW.status = 'COMMITTED'
        AND (NEW.request_digest_version IS DISTINCT FROM 2
            OR NEW.credential_request_digest_version IS DISTINCT FROM 1
            OR NEW.credential_digest_key_id IS NULL
            OR NEW.credential_request_digest IS NULL) THEN
        RAISE EXCEPTION 'Unbound gameplay LOGIN evidence cannot commit'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_gameplay_delegation_unbound_request_cannot_advance';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_gameplay_delegation_credential_request_binding_guard
    BEFORE INSERT OR UPDATE ON account_gameplay_delegation_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_gameplay_delegation_credential_request_binding_guard();
-- [jooq ignore stop]
