-- Explicit birth-only CATEGORY evidence for genuinely new repository-created Accounts.
-- Historical birth generation/fence values are independent of current Account authority.
-- No retained enrollment, moderation mutation API, current-lock proof, or activation is added.
CREATE TABLE account_platform_restriction_births (
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid),
    category VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL,
    enforcement_epoch BIGINT NOT NULL,
    source_kind VARCHAR(16) NOT NULL,
    restriction_state VARCHAR(16) NOT NULL,
    operation_id UUID NOT NULL UNIQUE,
    result_id UUID NOT NULL UNIQUE,
    event_id UUID NOT NULL UNIQUE,
    payload_digest VARCHAR(64) NOT NULL,
    account_source_numeric_id BIGINT NOT NULL REFERENCES accounts(id),
    account_insert_transaction_id BIGINT NOT NULL,
    account_generation BIGINT NOT NULL,
    account_source_version BIGINT NOT NULL,
    issuance_fence BIGINT NOT NULL,
    fence_source_version BIGINT NOT NULL,
    CONSTRAINT account_restriction_birth_pk PRIMARY KEY (account_uuid, category),
    CONSTRAINT account_restriction_birth_category CHECK (
        category IN ('account_security_lock', 'platform_access_ban')),
    CONSTRAINT account_restriction_birth_baseline CHECK (
        revision = 1 AND enforcement_epoch = 1
        AND source_kind = 'CATEGORY' AND restriction_state = 'NONRESTRICTED'
        AND account_source_numeric_id > 0 AND account_insert_transaction_id > 0
        AND account_generation = 1 AND account_source_version = 1
        AND issuance_fence = 1 AND fence_source_version = 1),
    CONSTRAINT account_restriction_birth_digest_shape CHECK (
        payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_restriction_birth_uuid_shape CHECK (
        account_uuid::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND operation_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND result_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND event_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT account_restriction_birth_distinct_ids CHECK (
        operation_id <> result_id AND operation_id <> event_id AND result_id <> event_id),
    CONSTRAINT account_restriction_birth_projection_identity UNIQUE (
        account_uuid, category, revision, enforcement_epoch, result_id),
    CONSTRAINT account_restriction_birth_event_identity UNIQUE (
        account_uuid, category, revision, enforcement_epoch, operation_id,
        result_id, event_id, payload_digest, source_kind, restriction_state)
);

CREATE TABLE account_platform_restriction_projections (
    account_uuid UUID NOT NULL,
    category VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    enforcement_epoch BIGINT NOT NULL CHECK (enforcement_epoch > 0),
    result_id UUID NOT NULL,
    CONSTRAINT account_restriction_projection_pk PRIMARY KEY (account_uuid, category),
    CONSTRAINT account_restriction_projection_exact_result FOREIGN KEY (
        account_uuid, category, revision, enforcement_epoch, result_id)
        REFERENCES account_platform_restriction_births (
            account_uuid, category, revision, enforcement_epoch, result_id)
);

CREATE TABLE account_platform_restriction_birth_outbox (
    account_uuid UUID NOT NULL,
    category VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL,
    enforcement_epoch BIGINT NOT NULL,
    operation_id UUID NOT NULL,
    result_id UUID NOT NULL,
    event_id UUID NOT NULL UNIQUE,
    payload_digest VARCHAR(64) NOT NULL,
    outbox_stream_key VARCHAR(160) NOT NULL UNIQUE,
    outbox_sequence BIGINT NOT NULL,
    source_kind VARCHAR(16) NOT NULL,
    restriction_state VARCHAR(16) NOT NULL,
    CONSTRAINT account_restriction_birth_outbox_pk PRIMARY KEY (account_uuid, category),
    CONSTRAINT account_restriction_birth_outbox_stream CHECK (
        outbox_sequence = 1
        AND outbox_stream_key =
            'account:restriction-birth:v1:' || account_uuid::text || '/' || category),
    CONSTRAINT account_restriction_birth_outbox_exact_result FOREIGN KEY (
        account_uuid, category, revision, enforcement_epoch, operation_id,
        result_id, event_id, payload_digest, source_kind, restriction_state)
        REFERENCES account_platform_restriction_births (
            account_uuid, category, revision, enforcement_epoch, operation_id,
            result_id, event_id, payload_digest, source_kind, restriction_state)
);

-- Closed UTF-8 preimage matches AccountPlatformRestrictionBirthSource.birthDigest exactly.
-- PostgreSQL proof must verify these checks; jOOQ's schema simulation does not execute them.
-- [jooq ignore start]
CREATE FUNCTION account_restriction_birth_digest(
    birth account_platform_restriction_births)
RETURNS TEXT LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT encode(sha256(convert_to(
        concat_ws(E'\n',
            'account-restriction-birth/v1',
            birth.account_uuid::text,
            birth.account_source_numeric_id::text,
            'ACCOUNT_REPOSITORY_INSERT',
            birth.account_insert_transaction_id::text,
            birth.category,
            birth.source_kind,
            birth.restriction_state,
            birth.revision::text,
            birth.enforcement_epoch::text,
            birth.account_generation::text,
            birth.account_source_version::text,
            birth.issuance_fence::text,
            birth.fence_source_version::text,
            birth.operation_id::text,
            birth.result_id::text,
            birth.event_id::text,
            'account:restriction-birth:v1:' || birth.account_uuid::text || '/' || birth.category,
            '1') || E'\n', 'UTF8')), 'hex')
$$;

ALTER TABLE account_platform_restriction_births
    ADD CONSTRAINT account_restriction_birth_exact_digest
    CHECK (payload_digest = account_restriction_birth_digest(
        ROW(account_uuid, category, revision, enforcement_epoch, source_kind,
            restriction_state, operation_id, result_id, event_id, payload_digest,
            account_source_numeric_id, account_insert_transaction_id,
            account_generation, account_source_version, issuance_fence,
            fence_source_version)::account_platform_restriction_births));

CREATE FUNCTION account_restriction_birth_storage_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    account_row RECORD;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Restriction source evidence cannot be removed'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_restriction_birth_no_remove';
    END IF;

    IF TG_OP = 'UPDATE'
        AND TG_TABLE_NAME <> 'account_platform_restriction_projections' THEN
        RAISE EXCEPTION 'Restriction birth result and outbox are immutable'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_restriction_birth_immutable';
    END IF;

    SELECT * INTO account_row FROM accounts
        WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Restriction source requires exact Account'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_restriction_birth_account_required';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
            OR NEW.category IS DISTINCT FROM OLD.category
            OR NEW.revision <> OLD.revision + 1
            OR NEW.enforcement_epoch <> OLD.enforcement_epoch + 1
            OR NEW.result_id IS NOT DISTINCT FROM OLD.result_id THEN
            RAISE EXCEPTION 'Restriction projection requires next exact revision'
                USING ERRCODE = '23514',
                    CONSTRAINT = 'account_restriction_projection_monotonic';
        END IF;
        -- The exact-result FK remains birth-only, so no later revision is supported here.
    ELSIF TG_TABLE_NAME = 'account_platform_restriction_births' THEN
        IF account_row.account_uuid_provenance IS DISTINCT FROM 'ACCOUNT_REPOSITORY_INSERT'
            OR account_row.lifecycle_state IS DISTINCT FROM 'active'
            OR account_row.id IS DISTINCT FROM NEW.account_source_numeric_id
            OR account_row.account_uuid_source_numeric_id
                IS DISTINCT FROM NEW.account_source_numeric_id
            OR account_row.account_repository_insert_transaction_id
                IS DISTINCT FROM txid_current()
            OR NEW.account_insert_transaction_id IS DISTINCT FROM txid_current()
            OR NOT EXISTS (
                SELECT 1 FROM account_authority_generations
                WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid
                    AND generation = 1 AND source_version = 1
                    AND created_transaction_id = txid_current())
            OR NOT EXISTS (
                SELECT 1 FROM account_authority_issuance_fences
                WHERE account_uuid = NEW.account_uuid
                    AND issuance_fence = 1 AND source_version = 1)
            OR NOT EXISTS (
                SELECT 1 FROM account_authority_source_records
                WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid
                    AND initialization_provenance = 'ACCOUNT_REPOSITORY_INSERT'
                    AND initialization_transaction_id = txid_current()
                    AND account_repository_insert_transaction_id = txid_current()
                    AND account_source_numeric_id = NEW.account_source_numeric_id
                    AND current_generation = 1 AND current_source_version = 1
                    AND current_issuance_fence = 1
                    AND current_issuance_fence_source_version = 1
                    AND last_outbox_sequence = 0
                    AND last_event_id IS NULL AND last_event_digest IS NULL) THEN
            RAISE EXCEPTION 'Restriction birth requires genuine fresh Account source'
                USING ERRCODE = '23514',
                    CONSTRAINT = 'account_restriction_birth_fresh_provenance';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_restriction_birth_storage
    BEFORE INSERT OR UPDATE OR DELETE ON account_platform_restriction_births
    FOR EACH ROW EXECUTE FUNCTION account_restriction_birth_storage_guard();
CREATE TRIGGER account_restriction_projection_storage
    BEFORE INSERT OR UPDATE OR DELETE ON account_platform_restriction_projections
    FOR EACH ROW EXECUTE FUNCTION account_restriction_birth_storage_guard();
CREATE TRIGGER account_restriction_birth_outbox_storage
    BEFORE INSERT OR UPDATE OR DELETE ON account_platform_restriction_birth_outbox
    FOR EACH ROW EXECUTE FUNCTION account_restriction_birth_storage_guard();
CREATE TRIGGER account_restriction_birth_no_truncate
    BEFORE TRUNCATE ON account_platform_restriction_births
    FOR EACH STATEMENT EXECUTE FUNCTION account_restriction_birth_storage_guard();
CREATE TRIGGER account_restriction_projection_no_truncate
    BEFORE TRUNCATE ON account_platform_restriction_projections
    FOR EACH STATEMENT EXECUTE FUNCTION account_restriction_birth_storage_guard();
CREATE TRIGGER account_restriction_birth_outbox_no_truncate
    BEFORE TRUNCATE ON account_platform_restriction_birth_outbox
    FOR EACH STATEMENT EXECUTE FUNCTION account_restriction_birth_storage_guard();

CREATE FUNCTION account_restriction_birth_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- Existing/unmapped rows are not enrolled. This branch uses only the accounts row type.
    IF TG_TABLE_NAME = 'accounts' THEN
        IF NEW.account_uuid_provenance IS DISTINCT FROM 'ACCOUNT_REPOSITORY_INSERT'
            OR NEW.id IS DISTINCT FROM NEW.account_uuid_source_numeric_id
            OR NEW.account_repository_insert_transaction_id IS DISTINCT FROM txid_current() THEN
            RETURN NEW;
        END IF;
    END IF;

    IF (SELECT count(*)
        FROM account_platform_restriction_births b
        JOIN account_platform_restriction_projections p
            USING (account_uuid, category, revision, enforcement_epoch, result_id)
        JOIN account_platform_restriction_birth_outbox o
            USING (account_uuid, category, revision, enforcement_epoch,
                operation_id, result_id, event_id, payload_digest,
                source_kind, restriction_state)
        WHERE b.account_uuid = NEW.account_uuid) <> 2 THEN
        RAISE EXCEPTION 'Fresh Account restriction birth must be complete'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_restriction_birth_complete';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER account_restriction_account_birth_complete
    AFTER INSERT ON accounts DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_restriction_birth_complete_guard();
CREATE CONSTRAINT TRIGGER account_restriction_birth_complete
    AFTER INSERT ON account_platform_restriction_births DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_restriction_birth_complete_guard();
CREATE CONSTRAINT TRIGGER account_restriction_projection_complete
    AFTER INSERT OR UPDATE ON account_platform_restriction_projections
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_restriction_birth_complete_guard();
CREATE CONSTRAINT TRIGGER account_restriction_birth_outbox_complete
    AFTER INSERT ON account_platform_restriction_birth_outbox
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_restriction_birth_complete_guard();
-- [jooq ignore stop]
