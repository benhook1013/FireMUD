-- Keep the numeric primary key as an Account-local storage detail while adding
-- the canonical, durable UUID identity required at cross-service boundaries.
-- Each retained UUID is assigned to its exact source row in place; the numeric
-- source is copied from that row's unchanged primary key.
ALTER TABLE accounts
    ADD COLUMN account_uuid UUID,
    ADD COLUMN account_uuid_provenance VARCHAR(40),
    ADD COLUMN account_uuid_source_numeric_id BIGINT;

UPDATE accounts
SET account_uuid = gen_random_uuid(),
    account_uuid_provenance = 'ACCOUNT_V29_MIGRATION',
    account_uuid_source_numeric_id = id;

-- jOOQ's open-source DDL parser cannot model the PostgreSQL UUID generator default.
-- [jooq ignore start]
ALTER TABLE accounts ALTER COLUMN account_uuid SET DEFAULT gen_random_uuid();
ALTER TABLE accounts
    ALTER COLUMN account_uuid_provenance SET DEFAULT 'ACCOUNT_DATABASE_INSERT';
-- [jooq ignore stop]

ALTER TABLE accounts ALTER COLUMN account_uuid SET NOT NULL;
ALTER TABLE accounts ALTER COLUMN account_uuid_provenance SET NOT NULL;
ALTER TABLE accounts ALTER COLUMN account_uuid_source_numeric_id SET NOT NULL;
ALTER TABLE accounts
    ADD CONSTRAINT accounts_account_uuid_unique UNIQUE (account_uuid);
ALTER TABLE accounts
    ADD CONSTRAINT accounts_account_uuid_source_numeric_id_unique
        UNIQUE (account_uuid_source_numeric_id);
ALTER TABLE accounts
    ADD CONSTRAINT accounts_account_uuid_source_numeric_id_check
        CHECK (account_uuid_source_numeric_id = id);
ALTER TABLE accounts
    ADD CONSTRAINT accounts_account_uuid_provenance_check
        CHECK (account_uuid_provenance IN (
            'ACCOUNT_V29_MIGRATION',
            'ACCOUNT_REPOSITORY_INSERT',
            'ACCOUNT_DATABASE_INSERT'
        ));

-- The UUID and its source record are immutable identity evidence. The numeric
-- primary key remains unchanged and cannot be reassigned after UUID backfill.
-- [jooq ignore start]
CREATE FUNCTION account_identity_source_row_on_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.account_uuid_source_numeric_id := NEW.id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_identity_source_row_on_insert
    BEFORE INSERT ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION account_identity_source_row_on_insert();

CREATE FUNCTION account_identity_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
        OR NEW.account_uuid_provenance IS DISTINCT FROM OLD.account_uuid_provenance
        OR NEW.account_uuid_source_numeric_id IS DISTINCT FROM OLD.account_uuid_source_numeric_id THEN
        RAISE EXCEPTION 'Account identity cannot be reassigned'
            USING ERRCODE = '23514', CONSTRAINT = 'accounts_identity_immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_identity_immutable
    BEFORE UPDATE OF id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id
    ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION account_identity_immutable_guard();
-- [jooq ignore stop]
