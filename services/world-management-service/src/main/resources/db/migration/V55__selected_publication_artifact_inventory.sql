CREATE TABLE world_selected_publication_artifact_inventory (
    publication_fence UUID NOT NULL PRIMARY KEY
        REFERENCES world_design_publication_fence_attempt(publication_fence) ON DELETE RESTRICT,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    local_version_key BIGINT NOT NULL,
    applied_commit_id VARCHAR(256) NOT NULL,
    account_operation_id UUID NOT NULL,
    account_fence_id UUID NOT NULL,
    inventory_schema_version SMALLINT NOT NULL CHECK (inventory_schema_version = 1),
    inventory_bytes BYTEA NOT NULL CHECK (octet_length(inventory_bytes) > 0),
    inventory_digest VARCHAR(71) NOT NULL
        CHECK (inventory_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT fk_world_selected_publication_inventory_account
        FOREIGN KEY (publication_fence)
        REFERENCES world_design_publication_account_binding(publication_fence)
        ON DELETE RESTRICT,
    CONSTRAINT uq_world_selected_publication_inventory_account_operation
        UNIQUE (account_operation_id),
    CONSTRAINT uq_world_selected_publication_inventory_account_fence
        UNIQUE (account_fence_id),
    CONSTRAINT ck_world_selected_publication_inventory_scope
        CHECK (local_tenant_key > 0 AND local_version_key > 0),
    CONSTRAINT ck_world_selected_publication_inventory_ids
        CHECK (
            publication_fence <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND account_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND account_fence_id <> '00000000-0000-0000-0000-000000000000'::UUID
        ),
    CONSTRAINT ck_world_selected_publication_inventory_namespace
        CHECK (
            octet_length(target_namespace) BETWEEN 1 AND 63
            AND target_namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        ),
    CONSTRAINT ck_world_selected_publication_inventory_commit
        CHECK (octet_length(applied_commit_id) BETWEEN 1 AND 256)
);

-- [jooq ignore start]
REVOKE ALL ON world_selected_publication_artifact_inventory FROM PUBLIC;

CREATE FUNCTION world_validate_selected_publication_artifact_inventory()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    attempt "${serviceSchema}".world_design_publication_fence_attempt%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    account_binding "${serviceSchema}".world_design_publication_account_binding%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'World selected-publication artifact inventory is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'World selected-publication artifact inventory requires writable READ COMMITTED'
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
    SELECT * INTO STRICT account_binding
    FROM "${serviceSchema}".world_design_publication_account_binding b
    WHERE b.publication_fence = NEW.publication_fence;

    IF attempt.owner_binding_schema_version IS DISTINCT FROM 1
        OR attempt.target_namespace IS DISTINCT FROM NEW.target_namespace
        OR attempt.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR attempt.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
        OR attempt.version_id IS DISTINCT FROM NEW.local_version_key
        OR attempt.local_tenant_key IS DISTINCT FROM NEW.local_tenant_key
        OR attempt.applied_commit_id IS DISTINCT FROM NEW.applied_commit_id
        OR account_binding.account_operation_id IS DISTINCT FROM NEW.account_operation_id
        OR account_binding.account_fence_id IS DISTINCT FROM NEW.account_fence_id
        OR encode(sha256(account_binding.account_binding_bytes), 'hex')
            IS DISTINCT FROM substring(account_binding.account_binding_digest FROM 8)
        OR encode(sha256(NEW.inventory_bytes), 'hex')
            IS DISTINCT FROM substring(NEW.inventory_digest FROM 8)
        OR owner_row.owner_freeze_phase IS DISTINCT FROM 'FROZEN'
        OR owner_row.current_publication_fence IS DISTINCT FROM NEW.publication_fence THEN
        RAISE EXCEPTION 'World artifact inventory differs from the newly qualified selected freeze'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_world_selected_publication_artifact_inventory_validate
    BEFORE INSERT ON world_selected_publication_artifact_inventory FOR EACH ROW
    EXECUTE FUNCTION world_validate_selected_publication_artifact_inventory();
CREATE TRIGGER trg_world_selected_publication_artifact_inventory_immutable
    BEFORE UPDATE OR DELETE ON world_selected_publication_artifact_inventory FOR EACH ROW
    EXECUTE FUNCTION world_reject_publication_attempt_history_mutation();
CREATE TRIGGER trg_world_selected_publication_artifact_inventory_no_truncate
    BEFORE TRUNCATE ON world_selected_publication_artifact_inventory FOR EACH STATEMENT
    EXECUTE FUNCTION world_reject_publication_attempt_history_mutation();
-- [jooq ignore stop]
