-- Preserve the distinct original Account publication order beside only a newly created World
-- freeze attempt. Historical attempts remain unqualified; their Account order is not inferred.
CREATE TABLE world_design_publication_account_binding (
    publication_fence UUID NOT NULL PRIMARY KEY
        REFERENCES world_design_publication_fence_attempt(publication_fence) ON DELETE RESTRICT,
    account_operation_id UUID NOT NULL,
    account_fence_id UUID NOT NULL,
    account_binding_bytes BYTEA NOT NULL CHECK (octet_length(account_binding_bytes) > 0),
    account_binding_digest VARCHAR(71) NOT NULL
        CHECK (account_binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT uq_world_publication_account_operation UNIQUE (account_operation_id),
    CONSTRAINT uq_world_publication_account_fence UNIQUE (account_fence_id),
    CONSTRAINT ck_world_publication_account_binding_ids CHECK (
        account_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND account_fence_id <> '00000000-0000-0000-0000-000000000000'::UUID
    )
);

-- A canonical binding may qualify only an attempt inserted by this same transaction. This makes
-- late attachment to an older unqualified FROZEN attempt impossible even for direct SQL writers.
-- The repository additionally decodes and exact-compares the complete Account binding.
-- [jooq ignore start]
-- Existing attempts retain NULL; only attempts inserted after this forward-only stamp receive
-- their full transaction identity. Do not backfill historical rows from their wrapping xmin.
ALTER TABLE world_design_publication_fence_attempt
    ADD COLUMN attempt_created_full_xid xid8;
ALTER TABLE world_design_publication_fence_attempt
    ALTER COLUMN attempt_created_full_xid SET DEFAULT pg_current_xact_id();

REVOKE ALL ON world_design_publication_account_binding FROM PUBLIC;

CREATE FUNCTION world_validate_selected_publication_account_binding()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    attempt "${serviceSchema}".world_design_publication_fence_attempt%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'World selected-publication Account binding is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'World selected-publication Account binding requires writable READ COMMITTED'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT attempt
    FROM "${serviceSchema}".world_design_publication_fence_attempt a
    WHERE a.publication_fence = NEW.publication_fence
        AND a.attempt_created_full_xid = pg_current_xact_id()
        AND a.xmin::TEXT = (txid_current() % 4294967296)::TEXT;
    SELECT * INTO STRICT owner_row
    FROM "${serviceSchema}".world_design_publication_fence_owner o
    WHERE o.target_namespace = attempt.target_namespace
        AND o.canonical_tenant_id = attempt.canonical_tenant_id
        AND o.local_tenant_key = attempt.local_tenant_key
        AND o.version_id = attempt.version_id
    FOR UPDATE;

    IF attempt.owner_binding_schema_version IS DISTINCT FROM 1
        OR owner_row.owner_freeze_phase IS DISTINCT FROM 'FROZEN'
        OR owner_row.current_publication_fence IS DISTINCT FROM NEW.publication_fence
        OR encode(sha256(NEW.account_binding_bytes), 'hex')
            IS DISTINCT FROM substring(NEW.account_binding_digest FROM 8)
        OR NEW.account_operation_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.account_fence_id = '00000000-0000-0000-0000-000000000000'::UUID THEN
        RAISE EXCEPTION 'World Account binding differs from a newly committed qualified freeze'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_world_publication_account_binding_validate
    BEFORE INSERT ON world_design_publication_account_binding FOR EACH ROW
    EXECUTE FUNCTION world_validate_selected_publication_account_binding();
CREATE TRIGGER trg_world_publication_account_binding_immutable
    BEFORE UPDATE OR DELETE ON world_design_publication_account_binding FOR EACH ROW
    EXECUTE FUNCTION world_reject_publication_attempt_history_mutation();
CREATE TRIGGER trg_world_publication_account_binding_no_truncate
    BEFORE TRUNCATE ON world_design_publication_account_binding FOR EACH STATEMENT
    EXECUTE FUNCTION world_reject_publication_attempt_history_mutation();
-- [jooq ignore stop]
