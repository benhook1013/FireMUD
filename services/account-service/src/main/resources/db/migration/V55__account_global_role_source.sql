ALTER TABLE accounts
    ADD CONSTRAINT accounts_global_role_source_identity_uq
        UNIQUE (account_uuid, account_uuid_source_numeric_id, account_uuid_provenance);

CREATE TABLE account_global_role_sources (
    account_uuid UUID PRIMARY KEY,
    account_uuid_source_numeric_id BIGINT NOT NULL,
    account_uuid_provenance VARCHAR(40) NOT NULL,
    global_roles TEXT[] NOT NULL,
    global_role_source_version BIGINT NOT NULL,
    CONSTRAINT account_global_role_sources_account_identity_fk
        FOREIGN KEY (
            account_uuid,
            account_uuid_source_numeric_id,
            account_uuid_provenance
        )
        REFERENCES accounts (
            account_uuid,
            account_uuid_source_numeric_id,
            account_uuid_provenance
        )
        ON DELETE RESTRICT,
    CONSTRAINT account_global_role_sources_account_numeric_id_check
        CHECK (account_uuid_source_numeric_id > 0),
    CONSTRAINT account_global_role_sources_account_uuid_check
        CHECK (account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT account_global_role_sources_fresh_provenance_check
        CHECK (account_uuid_provenance IN (
            'ACCOUNT_REPOSITORY_INSERT',
            'ACCOUNT_DATABASE_INSERT'
        )),
    CONSTRAINT account_global_role_sources_empty_roles_check
        CHECK (global_roles = ARRAY[]::TEXT[]),
    CONSTRAINT account_global_role_sources_positive_version_check
        CHECK (global_role_source_version > 0)
);

-- This prerequisite admits only the explicit-empty state. Role mutations need a
-- separately versioned, fenced producer before this immutable source can advance.
-- [jooq ignore start]
CREATE FUNCTION account_global_role_source_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Fresh Account global-role source is immutable until a versioned writer exists'
        USING ERRCODE = '23514', CONSTRAINT = 'account_global_role_source_immutable';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_global_role_source_immutable
    BEFORE UPDATE OR DELETE ON account_global_role_sources
    FOR EACH ROW EXECUTE FUNCTION account_global_role_source_immutable_guard();

CREATE FUNCTION account_global_role_source_truncate_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Fresh Account global-role source cannot be truncated'
        USING ERRCODE = '23514', CONSTRAINT = 'account_global_role_source_truncate_denied';
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_global_role_source_truncate_guard
    BEFORE TRUNCATE ON account_global_role_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_global_role_source_truncate_guard();

CREATE FUNCTION account_global_role_source_birth_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF pg_trigger_depth() <> 2
        OR NOT EXISTS (
            SELECT 1
            FROM accounts account_row
            WHERE account_row.id = NEW.account_uuid_source_numeric_id
                AND account_row.account_uuid = NEW.account_uuid
                AND account_row.account_uuid_source_numeric_id = NEW.account_uuid_source_numeric_id
                AND account_row.account_uuid_provenance = NEW.account_uuid_provenance
                AND account_row.account_uuid_provenance IN (
                    'ACCOUNT_REPOSITORY_INSERT',
                    'ACCOUNT_DATABASE_INSERT'
                )
                AND account_row.role IS NULL
        ) THEN
        RAISE EXCEPTION 'Account global-role source may only be inserted by its fresh Account birth path'
            USING ERRCODE = '23514', CONSTRAINT = 'account_global_role_source_birth_only';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_global_role_source_birth_insert_guard
    BEFORE INSERT ON account_global_role_sources
    FOR EACH ROW EXECUTE FUNCTION account_global_role_source_birth_insert_guard();

CREATE FUNCTION account_global_role_source_birth()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.account_uuid_provenance IN (
        'ACCOUNT_REPOSITORY_INSERT',
        'ACCOUNT_DATABASE_INSERT'
    ) AND NEW.role IS NULL THEN
        IF NEW.account_uuid IS NULL
            OR NEW.account_uuid = '00000000-0000-0000-0000-000000000000'::UUID
            OR NEW.account_uuid_source_numeric_id IS DISTINCT FROM NEW.id THEN
            RAISE EXCEPTION 'Fresh Account has no exact canonical UUID source identity'
                USING ERRCODE = '23514',
                    CONSTRAINT = 'account_global_role_source_birth_identity';
        END IF;

        INSERT INTO account_global_role_sources (
            account_uuid,
            account_uuid_source_numeric_id,
            account_uuid_provenance,
            global_roles,
            global_role_source_version
        ) VALUES (
            NEW.account_uuid,
            NEW.account_uuid_source_numeric_id,
            NEW.account_uuid_provenance,
            ARRAY[]::TEXT[],
            1
        );
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_global_role_source_birth
    AFTER INSERT ON accounts
    FOR EACH ROW EXECUTE FUNCTION account_global_role_source_birth();

CREATE FUNCTION account_global_role_source_legacy_scalar_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.role IS DISTINCT FROM OLD.role
        AND EXISTS (
            SELECT 1
            FROM account_global_role_sources source
            WHERE source.account_uuid = OLD.account_uuid
        ) THEN
        RAISE EXCEPTION 'Account role change requires a versioned global-role source writer'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_global_role_source_legacy_scalar_guard';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_global_role_source_legacy_scalar_guard
    BEFORE UPDATE OF role ON accounts
    FOR EACH ROW EXECUTE FUNCTION account_global_role_source_legacy_scalar_guard();
-- [jooq ignore stop]
