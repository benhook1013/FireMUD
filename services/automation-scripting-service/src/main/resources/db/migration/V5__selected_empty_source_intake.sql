-- This migration installs only the storage and writer guards for a fresh, explicitly empty
-- Automation source scope. Existing rows remain legacy/unqualified; no numeric ID is adopted.
CREATE TABLE automation_empty_source_numeric_key_reservation (
    key_kind VARCHAR(16) NOT NULL,
    numeric_key BIGINT NOT NULL,
    claim_kind VARCHAR(32) NOT NULL,
    CONSTRAINT pk_automation_empty_source_numeric_key_reservation
        PRIMARY KEY (key_kind, numeric_key),
    CONSTRAINT uq_automation_empty_source_numeric_key_kind_value
        UNIQUE (numeric_key, key_kind),
    CONSTRAINT uq_automation_empty_source_numeric_key_claim
        UNIQUE (numeric_key, key_kind, claim_kind),
    CONSTRAINT ck_automation_empty_source_numeric_key_kind
        CHECK (key_kind IN ('TENANT', 'VERSION')),
    CONSTRAINT ck_automation_empty_source_numeric_key_positive
        CHECK (numeric_key > 0),
    CONSTRAINT ck_automation_empty_source_numeric_key_claim
        CHECK (claim_kind IN ('LEGACY_NUMERIC', 'CANONICAL_EMPTY_SOURCE'))
);

CREATE TABLE automation_empty_selected_source_association (
    target_namespace VARCHAR(63) NOT NULL,
    operation_id UUID NOT NULL,
    fence_id UUID NOT NULL,
    intake_request_id UUID NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    selected_commit_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    source_revision_order VARCHAR(32) NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    local_tenant_key_kind VARCHAR(16) NOT NULL DEFAULT 'TENANT',
    local_tenant_key_claim_kind VARCHAR(32) NOT NULL DEFAULT 'CANONICAL_EMPTY_SOURCE',
    local_version_key BIGINT NOT NULL,
    local_version_key_kind VARCHAR(16) NOT NULL DEFAULT 'VERSION',
    local_version_key_claim_kind VARCHAR(32) NOT NULL DEFAULT 'CANONICAL_EMPTY_SOURCE',
    request_digest VARCHAR(71) NOT NULL,
    authorization_binding_digest VARCHAR(71) NOT NULL,
    receipt_digest VARCHAR(71) NOT NULL,
    associated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_automation_empty_selected_source_association
        PRIMARY KEY (target_namespace, canonical_tenant_id, canonical_version_id),
    CONSTRAINT uq_automation_empty_selected_source_request
        UNIQUE (target_namespace, intake_request_id),
    CONSTRAINT uq_automation_empty_selected_source_operation
        UNIQUE (target_namespace, operation_id),
    CONSTRAINT uq_automation_empty_selected_source_local_tenant
        UNIQUE (local_tenant_key),
    CONSTRAINT uq_automation_empty_selected_source_local_version
        UNIQUE (local_version_key),
    CONSTRAINT ck_automation_empty_selected_source_namespace
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT ck_automation_empty_selected_source_ids_nonzero
        CHECK (
            operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND fence_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND selected_commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND source_revision_id <> '00000000-0000-0000-0000-000000000000'::UUID
        ),
    CONSTRAINT ck_automation_empty_selected_source_local_keys
        CHECK (
            local_tenant_key > 0
            AND local_version_key > 0
            AND local_tenant_key <> local_version_key
            AND local_tenant_key_kind = 'TENANT'
            AND local_tenant_key_claim_kind = 'CANONICAL_EMPTY_SOURCE'
            AND local_version_key_kind = 'VERSION'
            AND local_version_key_claim_kind = 'CANONICAL_EMPTY_SOURCE'
        ),
    CONSTRAINT ck_automation_empty_selected_source_revision_order
        CHECK (source_revision_order ~ '^(0|[1-9][0-9]*)$'),
    CONSTRAINT ck_automation_empty_selected_source_digests
        CHECK (
            request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND authorization_binding_digest ~ '^sha256:[0-9a-f]{64}$'
            AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        ),
    CONSTRAINT fk_automation_empty_selected_source_tenant_key
        FOREIGN KEY (local_tenant_key, local_tenant_key_kind, local_tenant_key_claim_kind)
        REFERENCES automation_empty_source_numeric_key_reservation
            (numeric_key, key_kind, claim_kind),
    CONSTRAINT fk_automation_empty_selected_source_version_key
        FOREIGN KEY (local_version_key, local_version_key_kind, local_version_key_claim_kind)
        REFERENCES automation_empty_source_numeric_key_reservation
            (numeric_key, key_kind, claim_kind)
);

CREATE TABLE automation_empty_selected_source_receipt (
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    request_digest VARCHAR(71) NOT NULL,
    receipt_digest VARCHAR(71) NOT NULL,
    receipt_bytes BYTEA NOT NULL,
    retained_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_automation_empty_selected_source_receipt
        PRIMARY KEY (target_namespace, intake_request_id),
    CONSTRAINT fk_automation_empty_selected_source_receipt_association
        FOREIGN KEY (target_namespace, intake_request_id)
        REFERENCES automation_empty_selected_source_association (target_namespace, intake_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_automation_empty_selected_source_receipt_digests
        CHECK (
            request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'
            AND octet_length(receipt_bytes) BETWEEN 1 AND 58736640
        )
);

CREATE INDEX idx_automation_empty_source_key_claim_kind
    ON automation_empty_source_numeric_key_reservation (claim_kind, key_kind, numeric_key);

/* [jooq ignore start] */
CREATE FUNCTION automation_source_positive_numeric_key(value_text TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
IMMUTABLE
STRICT
SET search_path = pg_catalog
AS $$
DECLARE
    parsed_value NUMERIC;
BEGIN
    IF value_text !~ '^[0-9]+$' THEN
        RETURN NULL;
    END IF;
    parsed_value := value_text::NUMERIC;
    IF parsed_value <= 0 OR parsed_value > 9223372036854775807 THEN
        RETURN NULL;
    END IF;
    RETURN parsed_value::BIGINT;
END;
$$;
/* [jooq ignore stop] */

-- Lock allocator/association rows before source families, matching the owner allocation order.
-- Old writers can finish before the family locks and are included in this seed; later writes see
-- the reservation triggers installed below.
/* [jooq ignore start] */
LOCK TABLE automation_empty_source_numeric_key_reservation,
    automation_empty_selected_source_association,
    automation_empty_selected_source_receipt IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE script_event_bindings, script_patch_base_bindings, scripts IN SHARE MODE;
/* [jooq ignore stop] */

-- Data-only PostgreSQL seeding uses the procedural parser above and ON CONFLICT; the jOOQ H2
-- simulator cannot execute that seed. All table definitions remain in generation scope.
/* [jooq ignore start] */
INSERT INTO automation_empty_source_numeric_key_reservation (key_kind, numeric_key, claim_kind)
SELECT DISTINCT key_kind, numeric_key, 'LEGACY_NUMERIC'
FROM (
    SELECT 'TENANT'::VARCHAR(16) AS key_kind, tenant_id AS numeric_key
    FROM scripts WHERE tenant_id > 0
    UNION ALL
    SELECT 'TENANT', tenant_id
    FROM script_event_bindings WHERE tenant_id > 0
    UNION ALL
    SELECT 'TENANT', automation_source_positive_numeric_key(tenant_id)
    FROM script_patch_base_bindings
    WHERE automation_source_positive_numeric_key(tenant_id) IS NOT NULL
    UNION ALL
    SELECT 'VERSION', base_version_id
    FROM scripts WHERE base_version_id > 0
    UNION ALL
    SELECT 'VERSION', base_version_id
    FROM script_event_bindings WHERE base_version_id > 0
    UNION ALL
    SELECT 'VERSION', base_version_id
    FROM script_patch_base_bindings WHERE base_version_id > 0
) AS existing_source_keys
ON CONFLICT (key_kind, numeric_key) DO NOTHING;
/* [jooq ignore stop] */

/* [jooq ignore start] */
CREATE FUNCTION automation_require_source_numeric_key_unreserved(
    requested_kind TEXT, requested_key BIGINT
) RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    retained_claim TEXT;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Automation authored-source row writes require READ COMMITTED isolation';
    END IF;
    IF requested_key IS NULL OR requested_key <= 0 THEN
        RETURN;
    END IF;
    SELECT claim_kind INTO retained_claim
    FROM "${serviceSchema}".automation_empty_source_numeric_key_reservation
    WHERE key_kind = requested_kind AND numeric_key = requested_key;
    IF retained_claim = 'CANONICAL_EMPTY_SOURCE' THEN
        RAISE EXCEPTION 'Automation numeric source key is reserved by an immutable empty-source receipt';
    END IF;
END;
$$;

CREATE FUNCTION automation_guard_authored_source_row()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    old_value JSONB;
    new_value JSONB;
    old_tenant_key BIGINT;
    new_tenant_key BIGINT;
    old_version_key BIGINT;
    new_version_key BIGINT;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        old_value := to_jsonb(OLD);
        old_tenant_key := automation_source_positive_numeric_key(old_value->>'tenant_id');
        old_version_key := automation_source_positive_numeric_key(old_value->>'base_version_id');
        PERFORM automation_require_source_numeric_key_unreserved('TENANT', old_tenant_key);
        PERFORM automation_require_source_numeric_key_unreserved('VERSION', old_version_key);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        new_value := to_jsonb(NEW);
        new_tenant_key := automation_source_positive_numeric_key(new_value->>'tenant_id');
        new_version_key := automation_source_positive_numeric_key(new_value->>'base_version_id');
        -- Do not mutate the allocator table from a source-row trigger. PostgreSQL has already
        -- acquired the source table's ROW EXCLUSIVE lock before firing this trigger, while the
        -- founding path intentionally locks allocator tables first. A read-only reservation
        -- check keeps that global lock order acyclic; new allocations also census every source
        -- family while holding the source locks, so unreserved legacy keys cannot be missed.
        PERFORM automation_require_source_numeric_key_unreserved('TENANT', new_tenant_key);
        PERFORM automation_require_source_numeric_key_unreserved('VERSION', new_version_key);
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION automation_reject_authored_source_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Automation authored-source TRUNCATE requires READ COMMITTED isolation';
    END IF;
    IF EXISTS (
        SELECT 1 FROM "${serviceSchema}".automation_empty_source_numeric_key_reservation
        WHERE claim_kind = 'CANONICAL_EMPTY_SOURCE'
    ) THEN
        RAISE EXCEPTION 'Automation authored source TRUNCATE is forbidden after canonical intake';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION automation_reject_empty_source_intake_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    RAISE EXCEPTION 'Automation empty-source intake records are immutable';
END;
$$;

REVOKE ALL ON FUNCTION automation_require_source_numeric_key_unreserved(TEXT, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION automation_guard_authored_source_row() FROM PUBLIC;
REVOKE ALL ON FUNCTION automation_reject_authored_source_truncate() FROM PUBLIC;
REVOKE ALL ON FUNCTION automation_reject_empty_source_intake_mutation() FROM PUBLIC;

CREATE TRIGGER trg_automation_scripts_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON scripts
    FOR EACH ROW EXECUTE FUNCTION automation_guard_authored_source_row();
CREATE TRIGGER trg_automation_event_bindings_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON script_event_bindings
    FOR EACH ROW EXECUTE FUNCTION automation_guard_authored_source_row();
CREATE TRIGGER trg_automation_patch_base_bindings_source_reservation
    BEFORE INSERT OR UPDATE OR DELETE ON script_patch_base_bindings
    FOR EACH ROW EXECUTE FUNCTION automation_guard_authored_source_row();

CREATE TRIGGER trg_automation_scripts_source_no_truncate
    BEFORE TRUNCATE ON scripts
    FOR EACH STATEMENT EXECUTE FUNCTION automation_reject_authored_source_truncate();
CREATE TRIGGER trg_automation_event_bindings_source_no_truncate
    BEFORE TRUNCATE ON script_event_bindings
    FOR EACH STATEMENT EXECUTE FUNCTION automation_reject_authored_source_truncate();
CREATE TRIGGER trg_automation_patch_base_bindings_source_no_truncate
    BEFORE TRUNCATE ON script_patch_base_bindings
    FOR EACH STATEMENT EXECUTE FUNCTION automation_reject_authored_source_truncate();

CREATE TRIGGER trg_automation_empty_source_key_reservation_immutable
    BEFORE UPDATE OR DELETE ON automation_empty_source_numeric_key_reservation
    FOR EACH ROW EXECUTE FUNCTION automation_reject_empty_source_intake_mutation();
CREATE TRIGGER trg_automation_empty_source_key_reservation_no_truncate
    BEFORE TRUNCATE ON automation_empty_source_numeric_key_reservation
    FOR EACH STATEMENT EXECUTE FUNCTION automation_reject_empty_source_intake_mutation();
CREATE TRIGGER trg_automation_empty_source_association_immutable
    BEFORE UPDATE OR DELETE ON automation_empty_selected_source_association
    FOR EACH ROW EXECUTE FUNCTION automation_reject_empty_source_intake_mutation();
CREATE TRIGGER trg_automation_empty_source_association_no_truncate
    BEFORE TRUNCATE ON automation_empty_selected_source_association
    FOR EACH STATEMENT EXECUTE FUNCTION automation_reject_empty_source_intake_mutation();
CREATE TRIGGER trg_automation_empty_source_receipt_immutable
    BEFORE UPDATE OR DELETE ON automation_empty_selected_source_receipt
    FOR EACH ROW EXECUTE FUNCTION automation_reject_empty_source_intake_mutation();
CREATE TRIGGER trg_automation_empty_source_receipt_no_truncate
    BEFORE TRUNCATE ON automation_empty_selected_source_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION automation_reject_empty_source_intake_mutation();
/* [jooq ignore stop] */
