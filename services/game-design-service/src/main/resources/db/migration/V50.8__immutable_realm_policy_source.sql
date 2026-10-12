-- Explicit new-Draft genesis requires the Version's actual insertion transaction witness.
-- No retained backfill or inferred empty baseline is installed.
CREATE TABLE game_design_realm_policy_version_insert (
    version_id BIGINT PRIMARY KEY REFERENCES version (id),
    creation_transaction_id TEXT NOT NULL CHECK (creation_transaction_id ~ '^[1-9][0-9]*$')
);

CREATE TABLE game_design_realm_policy_genesis (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    receipt_id UUID NOT NULL UNIQUE,
    version_id BIGINT NOT NULL UNIQUE REFERENCES game_design_realm_policy_version_insert (version_id),
    creation_transaction_id TEXT NOT NULL,
    receipt_json TEXT NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (version_id, canonical_tenant_id, canonical_version_id)
        REFERENCES version (id, canonical_tenant_id, canonical_version_id),
    CHECK (receipt_id <> '00000000-0000-0000-0000-000000000000')
);

CREATE TABLE game_design_realm_policy_snapshot (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    request_id UUID NOT NULL,
    inherited_commit_id UUID,
    snapshot_json TEXT NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit_visibility_fence
            (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, inherited_commit_id)
        REFERENCES game_design_realm_policy_snapshot (canonical_tenant_id, canonical_version_id, commit_id)
);

-- PostgreSQL JSON extraction is unsupported by the jOOQ DDL simulation; retain the exact owner checks.
-- [jooq ignore start]
ALTER TABLE game_design_realm_policy_snapshot
    ADD CONSTRAINT realm_policy_snapshot_schema CHECK (snapshot_json::JSONB->>'schema' = 'game-design-realm-policy-snapshot/v1'),
    ADD CONSTRAINT realm_policy_snapshot_epoch CHECK (snapshot_json::JSONB->>'sourceEpoch' ~ '^(0|[1-9][0-9]*)$'),
    ADD CONSTRAINT realm_policy_snapshot_set_shape CHECK (jsonb_typeof(snapshot_json::JSONB->'policies') = 'array'
        AND jsonb_array_length(snapshot_json::JSONB->'policies') BETWEEN 0 AND 128);
-- [jooq ignore stop]

CREATE TABLE game_design_realm_policy_source (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    source_epoch TEXT NOT NULL CHECK (source_epoch ~ '^(0|[1-9][0-9]*)$'),
    visible_commit_id UUID,
    genesis_receipt_id UUID REFERENCES game_design_realm_policy_genesis (receipt_id),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, visible_commit_id)
        REFERENCES game_design_realm_policy_snapshot (canonical_tenant_id, canonical_version_id, commit_id),
    CHECK (visible_commit_id IS NOT NULL OR genesis_receipt_id IS NOT NULL)
);

CREATE TABLE game_design_realm_policy_application (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    request_id UUID NOT NULL,
    inherited_commit_id UUID,
    genesis_receipt_id UUID REFERENCES game_design_realm_policy_genesis (receipt_id),
    expected_epoch TEXT NOT NULL CHECK (expected_epoch ~ '^(0|[1-9][0-9]*)$'),
    snapshot_json TEXT NOT NULL,
    result_bytes BYTEA NOT NULL CHECK (octet_length(result_bytes) > 0),
    owner_result_bytes BYTEA,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, request_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, inherited_commit_id)
        REFERENCES game_design_realm_policy_snapshot (canonical_tenant_id, canonical_version_id, commit_id),
    CHECK (inherited_commit_id IS NOT NULL OR genesis_receipt_id IS NOT NULL)
);

-- [jooq ignore start]
ALTER TABLE game_design_realm_policy_application
    ADD CONSTRAINT realm_policy_application_schema CHECK (snapshot_json::JSONB->>'schema' = 'game-design-realm-policy-snapshot/v1'),
    ADD CONSTRAINT realm_policy_application_set_shape CHECK (jsonb_typeof(snapshot_json::JSONB->'policies') = 'array'
        AND jsonb_array_length(snapshot_json::JSONB->'policies') BETWEEN 0 AND 128);
-- [jooq ignore stop]

CREATE TABLE game_design_realm_policy_capture (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    publish_workflow_id VARCHAR(1024) NOT NULL UNIQUE REFERENCES game_design_publication_operation (publish_workflow_id),
    commit_id UUID NOT NULL,
    operation_bytes BYTEA NOT NULL CHECK (octet_length(operation_bytes) > 0),
    capture_bytes BYTEA NOT NULL CHECK (octet_length(capture_bytes) > 0),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, commit_id)
        REFERENCES game_design_realm_policy_snapshot (canonical_tenant_id, canonical_version_id, commit_id)
);

-- [jooq ignore start]
CREATE FUNCTION witness_realm_policy_version_insert() RETURNS trigger AS $$
BEGIN
    IF NEW.canonical_tenant_id IS NOT NULL AND NEW.canonical_version_id IS NOT NULL
        AND NEW.version_state = 'DRAFT' AND NOT NEW.is_script_only
        AND NEW.script_patch_version IS NULL AND NEW.base_version_id IS NULL THEN
        INSERT INTO game_design_realm_policy_version_insert (version_id, creation_transaction_id)
            VALUES (NEW.id, pg_current_xact_id()::TEXT);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER realm_policy_version_insert_witness AFTER INSERT ON version
    FOR EACH ROW EXECUTE FUNCTION witness_realm_policy_version_insert();

CREATE FUNCTION guard_realm_policy_genesis() RETURNS trigger AS $$
DECLARE owner_version version%ROWTYPE; witness game_design_realm_policy_version_insert%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'new-Draft insertion and genesis evidence is immutable' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_TABLE_NAME = 'game_design_realm_policy_version_insert' THEN
        IF pg_trigger_depth() < 2 OR NEW.creation_transaction_id <> pg_current_xact_id()::TEXT THEN
            RAISE EXCEPTION 'new-Draft witness requires the actual Version insertion trigger' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT owner_version FROM version WHERE id = NEW.version_id FOR UPDATE;
    SELECT * INTO STRICT witness FROM game_design_realm_policy_version_insert WHERE version_id = NEW.version_id;
    IF witness.creation_transaction_id <> pg_current_xact_id()::TEXT
        OR NEW.creation_transaction_id <> witness.creation_transaction_id
        OR NEW.canonical_tenant_id <> owner_version.canonical_tenant_id
        OR NEW.canonical_version_id <> owner_version.canonical_version_id
        OR owner_version.version_state <> 'DRAFT' OR owner_version.is_script_only
        OR owner_version.script_patch_version IS NOT NULL OR owner_version.base_version_id IS NOT NULL
        OR EXISTS (SELECT 1 FROM game_design_draft_commit WHERE canonical_tenant_id = NEW.canonical_tenant_id
            AND canonical_version_id = NEW.canonical_version_id)
        OR NEW.receipt_json::JSONB->>'schema' IS DISTINCT FROM 'game-design-realm-policy-genesis/v1'
        OR NEW.receipt_json::JSONB->>'receiptId' IS DISTINCT FROM NEW.receipt_id::TEXT
        OR NEW.receipt_json::JSONB->>'creationTransactionId' IS DISTINCT FROM witness.creation_transaction_id
        OR NEW.receipt_json::JSONB->>'canonicalTenantId' IS DISTINCT FROM owner_version.canonical_tenant_id::TEXT
        OR NEW.receipt_json::JSONB->>'canonicalVersionId' IS DISTINCT FROM owner_version.canonical_version_id::TEXT
        OR NEW.receipt_json::JSONB->>'gameDesignVersionRowId' IS DISTINCT FROM owner_version.id::TEXT
        OR NEW.receipt_json::JSONB->>'gameDesignVersionTenantKey' IS DISTINCT FROM owner_version.tenant_id
        OR NEW.receipt_json::JSONB->>'sourceGameRowId' IS DISTINCT FROM owner_version.identity_source_game_row_id::TEXT
        OR NEW.receipt_json::JSONB->>'sourceGameTenantKey' IS DISTINCT FROM owner_version.identity_source_game_tenant_key
        OR NEW.receipt_json::JSONB->>'sourceProvenanceKind' IS DISTINCT FROM owner_version.identity_source_provenance_kind THEN
        RAISE EXCEPTION 'realm policy genesis lacks the exact fresh owner insertion' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER realm_policy_version_insert_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_realm_policy_version_insert
    FOR EACH ROW EXECUTE FUNCTION guard_realm_policy_genesis();
CREATE TRIGGER realm_policy_genesis_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_realm_policy_genesis
    FOR EACH ROW EXECUTE FUNCTION guard_realm_policy_genesis();

CREATE FUNCTION guard_realm_policy_source_write() RETURNS trigger AS $$
DECLARE owner_version version%ROWTYPE; operation game_design_publication_operation%ROWTYPE;
    snapshot game_design_realm_policy_snapshot%ROWTYPE; selection game_design_authored_draft_publish_selection%ROWTYPE;
    expected BYTEA; payload BYTEA; policies JSONB; source_binding game_design_draft_commit%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'realm policy source history cannot be deleted' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO STRICT owner_version FROM version
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id FOR UPDATE;
    IF owner_version.version_state <> 'DRAFT' OR owner_version.is_script_only
        OR owner_version.script_patch_version IS NOT NULL OR owner_version.base_version_id IS NOT NULL THEN
        RAISE EXCEPTION 'realm policy source requires canonical full Draft owner' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF TG_TABLE_NAME = 'game_design_realm_policy_application' THEN
            IF OLD.owner_result_bytes IS NOT NULL OR NEW.owner_result_bytes IS NULL
                OR (to_jsonb(NEW) - 'owner_result_bytes') IS DISTINCT FROM (to_jsonb(OLD) - 'owner_result_bytes') THEN
                RAISE EXCEPTION 'realm policy application permits only its one atomic complete owner result' USING ERRCODE = 'check_violation';
            END IF;
        ELSIF TG_TABLE_NAME <> 'game_design_realm_policy_source' THEN
            RAISE EXCEPTION 'realm policy history is immutable' USING ERRCODE = 'check_violation';
        ELSIF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
            OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id
            OR NEW.genesis_receipt_id IS DISTINCT FROM OLD.genesis_receipt_id THEN
            RAISE EXCEPTION 'realm policy history is immutable' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM game_design_realm_policy_capture WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id) THEN
        RAISE EXCEPTION 'realm policy source is frozen' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_TABLE_NAME = 'game_design_realm_policy_source' THEN
      IF TG_OP = 'INSERT' AND NEW.genesis_receipt_id IS NOT NULL THEN
        IF NEW.source_epoch <> '0' OR NEW.visible_commit_id IS NOT NULL
            OR NOT EXISTS (SELECT 1 FROM game_design_realm_policy_genesis g WHERE g.receipt_id = NEW.genesis_receipt_id
                AND g.canonical_tenant_id = NEW.canonical_tenant_id AND g.canonical_version_id = NEW.canonical_version_id) THEN
            RAISE EXCEPTION 'genesis source must begin empty at its exact owner epoch zero' USING ERRCODE = 'check_violation';
        END IF;
      END IF;
    END IF;
    IF TG_TABLE_NAME IN ('game_design_realm_policy_snapshot', 'game_design_realm_policy_application') THEN
        SELECT * INTO STRICT source_binding FROM game_design_draft_commit
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
        policies := NEW.snapshot_json::JSONB->'policies';
        IF NEW.snapshot_json::JSONB->>'bindingJson' IS DISTINCT FROM source_binding.binding_json
            OR NEW.snapshot_json::JSONB->>'bindingDigest' IS DISTINCT FROM source_binding.input_digest
            OR jsonb_typeof(policies) IS DISTINCT FROM 'array'
            OR jsonb_array_length(policies) NOT BETWEEN 0 AND 128
            OR (SELECT count(DISTINCT p->'policy'->>'realmSlug') FROM jsonb_array_elements(policies) p) <> jsonb_array_length(policies) THEN
            RAISE EXCEPTION 'realm policy snapshot lacks exact source binding or unique bounded selectors' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF TG_TABLE_NAME = 'game_design_realm_policy_capture' THEN
        SELECT * INTO STRICT operation FROM game_design_publication_operation WHERE publish_workflow_id = NEW.publish_workflow_id FOR UPDATE;
        SELECT * INTO STRICT selection FROM game_design_authored_draft_publish_selection
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
        SELECT * INTO STRICT snapshot FROM game_design_realm_policy_snapshot
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
        policies := snapshot.snapshot_json::JSONB->'policies';
        IF jsonb_array_length(policies) NOT BETWEEN 1 AND 128
            OR (SELECT count(*) FROM jsonb_array_elements(policies) p WHERE p->'policy'->'visible' = 'true'::JSONB
                AND p->'policy'->'publicProduction' = 'true'::JSONB) <> 1
            OR operation.outcome <> 'PENDING' OR operation.request_bytes IS DISTINCT FROM NEW.operation_bytes
            OR operation.version_id <> owner_version.id OR operation.tenant_id <> owner_version.tenant_id
            OR operation.selection_digest <> selection.selection_digest OR selection.selected_commit_id <> NEW.commit_id
            OR NOT EXISTS (SELECT 1 FROM game_design_realm_policy_source s WHERE s.canonical_tenant_id = NEW.canonical_tenant_id
                AND s.canonical_version_id = NEW.canonical_version_id AND s.visible_commit_id = NEW.commit_id
                AND s.source_epoch = snapshot.snapshot_json::JSONB->>'sourceEpoch')
            OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_visibility v WHERE v.canonical_tenant_id = NEW.canonical_tenant_id
                AND v.canonical_version_id = NEW.canonical_version_id AND v.commit_id = NEW.commit_id) THEN
            RAISE EXCEPTION 'realm policy capture differs from original synchronized publication' USING ERRCODE = 'check_violation';
        END IF;
        payload := convert_to('game-design-realm-policy-source-capture/v1', 'UTF8');
        expected := int4send(octet_length(payload)) || payload
            || int4send(octet_length(NEW.operation_bytes)) || NEW.operation_bytes;
        payload := convert_to(snapshot.snapshot_json, 'UTF8');
        expected := expected || int4send(octet_length(payload)) || payload;
        IF expected IS DISTINCT FROM NEW.capture_bytes THEN
            RAISE EXCEPTION 'realm policy capture bytes differ from immutable source' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER realm_policy_snapshot_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_realm_policy_snapshot
    FOR EACH ROW EXECUTE FUNCTION guard_realm_policy_source_write();
CREATE TRIGGER realm_policy_source_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_realm_policy_source
    FOR EACH ROW EXECUTE FUNCTION guard_realm_policy_source_write();
CREATE TRIGGER realm_policy_application_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_realm_policy_application
    FOR EACH ROW EXECUTE FUNCTION guard_realm_policy_source_write();
CREATE TRIGGER realm_policy_capture_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_realm_policy_capture
    FOR EACH ROW EXECUTE FUNCTION guard_realm_policy_source_write();

CREATE FUNCTION verify_realm_policy_application_commit() RETURNS trigger AS $$
DECLARE binding game_design_draft_commit%ROWTYPE; result game_design_draft_commit_owner_result%ROWTYPE;
    expected BYTEA; payload BYTEA; application game_design_realm_policy_application%ROWTYPE; genesis game_design_realm_policy_genesis%ROWTYPE;
BEGIN
    SELECT * INTO STRICT application FROM game_design_realm_policy_application WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT binding FROM game_design_draft_commit WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT result FROM game_design_draft_commit_owner_result WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND owner = 'GAME_DESIGN_CONTROL_PLANE';
    payload := convert_to('game-design-realm-policy-application/v1', 'UTF8');
    expected := int4send(octet_length(payload)) || payload;
    payload := convert_to(CASE WHEN NEW.genesis_receipt_id IS NULL THEN 'false' ELSE 'true' END, 'UTF8');
    expected := expected || int4send(octet_length(payload)) || payload;
    payload := ''::BYTEA;
    IF NEW.genesis_receipt_id IS NOT NULL THEN
        SELECT * INTO STRICT genesis FROM game_design_realm_policy_genesis WHERE receipt_id = NEW.genesis_receipt_id;
        IF genesis.canonical_tenant_id <> NEW.canonical_tenant_id OR genesis.canonical_version_id <> NEW.canonical_version_id THEN
            RAISE EXCEPTION 'policy application changed genesis target' USING ERRCODE = 'check_violation';
        END IF;
        payload := convert_to(genesis.receipt_json, 'UTF8');
    END IF;
    expected := expected || int4send(octet_length(payload)) || payload;
    payload := convert_to(CASE WHEN NEW.inherited_commit_id IS NULL THEN 'false' ELSE 'true' END, 'UTF8');
    expected := expected || int4send(octet_length(payload)) || payload;
    payload := convert_to(COALESCE(NEW.inherited_commit_id::TEXT, ''), 'UTF8');
    expected := expected || int4send(octet_length(payload)) || payload;
    payload := convert_to(NEW.expected_epoch, 'UTF8');
    expected := expected || int4send(octet_length(payload)) || payload;
    payload := convert_to(NEW.snapshot_json, 'UTF8');
    expected := expected || int4send(octet_length(payload)) || payload;
    IF result.status <> 'APPLIED' OR application.owner_result_bytes IS NULL
        OR result.result_bytes IS DISTINCT FROM application.owner_result_bytes OR NEW.result_bytes IS DISTINCT FROM expected
        OR result.result_commit_id <> NEW.commit_id OR result.result_binding_digest <> binding.input_digest
        OR NEW.snapshot_json::JSONB->>'bindingJson' IS DISTINCT FROM binding.binding_json
        OR NEW.snapshot_json::JSONB->>'bindingDigest' IS DISTINCT FROM binding.input_digest
        OR (NEW.snapshot_json::JSONB->>'sourceEpoch')::NUMERIC <> NEW.expected_epoch::NUMERIC + 1 THEN
        RAISE EXCEPTION 'realm policy application lacks atomic exact coordinator result' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER realm_policy_application_commit_guard AFTER INSERT ON game_design_realm_policy_application
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_realm_policy_application_commit();

CREATE FUNCTION deny_realm_policy_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'realm policy owner evidence cannot be truncated' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER realm_policy_snapshot_no_truncate BEFORE TRUNCATE ON game_design_realm_policy_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION deny_realm_policy_truncate();
CREATE TRIGGER realm_policy_source_no_truncate BEFORE TRUNCATE ON game_design_realm_policy_source
    FOR EACH STATEMENT EXECUTE FUNCTION deny_realm_policy_truncate();
CREATE TRIGGER realm_policy_application_no_truncate BEFORE TRUNCATE ON game_design_realm_policy_application
    FOR EACH STATEMENT EXECUTE FUNCTION deny_realm_policy_truncate();
CREATE TRIGGER realm_policy_capture_no_truncate BEFORE TRUNCATE ON game_design_realm_policy_capture
    FOR EACH STATEMENT EXECUTE FUNCTION deny_realm_policy_truncate();
CREATE TRIGGER realm_policy_version_insert_no_truncate BEFORE TRUNCATE ON game_design_realm_policy_version_insert
    FOR EACH STATEMENT EXECUTE FUNCTION deny_realm_policy_truncate();
CREATE TRIGGER realm_policy_genesis_no_truncate BEFORE TRUNCATE ON game_design_realm_policy_genesis
    FOR EACH STATEMENT EXECUTE FUNCTION deny_realm_policy_truncate();
-- [jooq ignore stop]
