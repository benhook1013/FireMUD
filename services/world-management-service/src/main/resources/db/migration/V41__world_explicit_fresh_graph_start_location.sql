CREATE TABLE world_draft_start_location_receipt (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    commit_id UUID NOT NULL UNIQUE,
    authorization_fence_id UUID NOT NULL UNIQUE,
    target_namespace VARCHAR(128) NOT NULL,
    account_binding_bytes BYTEA NOT NULL CHECK (octet_length(account_binding_bytes) > 0),
    account_binding_digest VARCHAR(71) NOT NULL CHECK (account_binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    binding_digest VARCHAR(71) NOT NULL CHECK (binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    canonical_tenant_id UUID NOT NULL CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    canonical_version_id UUID NOT NULL CHECK (canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    room_template_id UUID NOT NULL CHECK (room_template_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    graph_digest VARCHAR(71) NOT NULL CHECK (graph_digest ~ '^sha256:[0-9a-f]{64}$'),
    receipt_digest VARCHAR(71) NOT NULL CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    receipt_bytes BYTEA NOT NULL CHECK (octet_length(receipt_bytes) > 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_world_draft_start_location_application
        FOREIGN KEY (operation_id) REFERENCES world_draft_graph_application(operation_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND authorization_fence_id <> '00000000-0000-0000-0000-000000000000'::UUID)
);

-- [jooq ignore start]
CREATE FUNCTION world_reject_draft_start_location_receipt_mutation() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    RAISE EXCEPTION 'World original start-location receipts are immutable and retained'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_world_start_location_receipt_immutable
    BEFORE UPDATE OR DELETE ON world_draft_start_location_receipt
    FOR EACH ROW EXECUTE FUNCTION world_reject_draft_start_location_receipt_mutation();
CREATE TRIGGER trg_world_start_location_receipt_no_truncate
    BEFORE TRUNCATE ON world_draft_start_location_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_draft_start_location_receipt_mutation();

CREATE FUNCTION world_guard_draft_start_location_receipt() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    graph_row "${serviceSchema}".world_topology_draft_commit%ROWTYPE;
    declaration JSONB;
    start_location JSONB;
    declaration_count BIGINT;
BEGIN
    SELECT * INTO graph_row FROM "${serviceSchema}".world_topology_draft_commit
        WHERE request_id = NEW.request_id AND commit_id = NEW.commit_id;
    IF NOT FOUND OR graph_row.application_transaction_id IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'World start-location receipt requires its exact fresh graph transaction'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO declaration_count
        FROM jsonb_array_elements(graph_row.binding_json::JSONB->'revisions') AS revision(value)
        WHERE value->>'owner' = 'WORLD_MANAGEMENT'
          AND ((value->>'payload')::JSONB ? 'freshGraphDeclaration');
    IF declaration_count <> 1 THEN
        RAISE EXCEPTION 'World start-location receipt requires exactly one original graph declaration'
            USING ERRCODE = '23514';
    END IF;
    SELECT ((value->>'payload')::JSONB)->'freshGraphDeclaration' INTO declaration
        FROM jsonb_array_elements(graph_row.binding_json::JSONB->'revisions') AS revision(value)
        WHERE value->>'owner' = 'WORLD_MANAGEMENT'
          AND ((value->>'payload')::JSONB ? 'freshGraphDeclaration');
    start_location := declaration->'startLocation';
    IF NEW.operation_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.request_id IS DISTINCT FROM graph_row.request_id
        OR NEW.commit_id IS DISTINCT FROM graph_row.commit_id
        OR NEW.target_namespace IS DISTINCT FROM graph_row.target_namespace
        OR NEW.binding_digest IS DISTINCT FROM graph_row.binding_digest
        OR NEW.canonical_tenant_id IS DISTINCT FROM graph_row.canonical_tenant_id
        OR NEW.canonical_version_id IS DISTINCT FROM graph_row.canonical_version_id
        OR declaration->>'tenantId' IS DISTINCT FROM graph_row.canonical_tenant_id::TEXT
        OR declaration->>'versionId' IS DISTINCT FROM graph_row.canonical_version_id::TEXT
        OR start_location->>'tenantId' IS DISTINCT FROM graph_row.canonical_tenant_id::TEXT
        OR start_location->>'versionId' IS DISTINCT FROM graph_row.canonical_version_id::TEXT
        OR start_location->>'roomTemplateId' IS NULL
        OR start_location->>'roomTemplateId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR NEW.room_template_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.room_template_id IS DISTINCT FROM (start_location->>'roomTemplateId')::UUID
        OR NEW.graph_digest IS DISTINCT FROM ('sha256:' || graph_row.graph_sha256)
        OR NOT EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity m
            JOIN "${serviceSchema}".room r ON r.id = m.private_row_key
            WHERE m.target_namespace = graph_row.target_namespace
              AND m.canonical_tenant_id = graph_row.canonical_tenant_id
              AND m.canonical_version_id = graph_row.canonical_version_id
              AND m.family = 'ROOM'
              AND m.template_id = NEW.room_template_id
              AND m.request_id = NEW.request_id
              AND m.commit_id = NEW.commit_id
              AND m.tenant_id = graph_row.local_tenant_key
              AND m.version_id = graph_row.local_version_key
              AND r.tenant_id = graph_row.local_tenant_key
              AND r.version_id = graph_row.local_version_key)
    THEN
        RAISE EXCEPTION 'World typed start selector differs from exact original graph/Version/ROOM'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_world_start_location_receipt_guard
    BEFORE INSERT ON world_draft_start_location_receipt
    FOR EACH ROW EXECUTE FUNCTION world_guard_draft_start_location_receipt();

-- V39 required six populated families. The Account-bound application now proves all six declared
-- counts, including explicit zero families, against the original binding and exact Version rows.
CREATE OR REPLACE FUNCTION world_guard_graph_application() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    graph_row "${serviceSchema}".world_topology_draft_commit%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    receipt "${serviceSchema}".world_draft_start_location_receipt%ROWTYPE;
    unit JSONB;
    declaration JSONB;
    start_location JSONB;
    family_entry JSONB;
    family_names TEXT[] := ARRAY[
        'REGION','ZONE','ROOM','ROOM_EXIT','GENERATION_RULE','WORLD_ENTITY_SPAWN_BINDING'];
    family_name TEXT;
    declared_count BIGINT;
    mapping_count BIGINT;
    content_count BIGINT;
    declaration_count BIGINT;
    receipt_count BIGINT;
    family_index INTEGER;
    mapped_key BIGINT;
    current_epoch BIGINT;
    result_json JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'World graph APPLIED results are immutable and retained' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO graph_row FROM "${serviceSchema}".world_topology_draft_commit
        WHERE request_id = NEW.request_id AND commit_id = NEW.commit_id;
    IF NOT FOUND OR graph_row.application_transaction_id IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'APPLIED requires a genuine fresh graph in the same transaction; old history cannot be promoted'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO owner_row FROM "${serviceSchema}".world_design_publication_fence_owner
        WHERE target_namespace = graph_row.target_namespace
          AND canonical_tenant_id = graph_row.canonical_tenant_id
          AND local_tenant_key = graph_row.local_tenant_key
          AND version_id = graph_row.local_version_key FOR UPDATE;
    IF NOT FOUND OR owner_row.owner_freeze_phase <> 'OPEN'
        OR owner_row.current_publication_fence IS NOT NULL THEN
        RAISE EXCEPTION 'Fresh graph APPLIED requires exact OPEN V25 owner' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_draft_terminal_outcome t
        WHERE t.operation_id = NEW.operation_id OR t.request_id = NEW.request_id
           OR t.commit_id = NEW.commit_id OR t.authorization_fence_id = NEW.authorization_fence_id) THEN
        RAISE EXCEPTION 'Definitively aborted World operation cannot apply' USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO declaration_count
        FROM jsonb_array_elements(graph_row.binding_json::JSONB->'revisions') AS revision(value)
        WHERE value->>'owner' = 'WORLD_MANAGEMENT'
          AND ((value->>'payload')::JSONB ? 'freshGraphDeclaration');
    IF declaration_count <> 1 THEN
        RAISE EXCEPTION 'Fresh graph APPLIED requires exactly one original complete declaration'
            USING ERRCODE = '23514';
    END IF;
    SELECT ((value->>'payload')::JSONB)->'freshGraphDeclaration' INTO declaration
        FROM jsonb_array_elements(graph_row.binding_json::JSONB->'revisions') AS revision(value)
        WHERE value->>'owner' = 'WORLD_MANAGEMENT'
          AND ((value->>'payload')::JSONB ? 'freshGraphDeclaration');
    start_location := declaration->'startLocation';
    IF declaration->>'tenantId' IS DISTINCT FROM graph_row.canonical_tenant_id::TEXT
        OR declaration->>'versionId' IS DISTINCT FROM graph_row.canonical_version_id::TEXT
        OR start_location->>'tenantId' IS DISTINCT FROM graph_row.canonical_tenant_id::TEXT
        OR start_location->>'versionId' IS DISTINCT FROM graph_row.canonical_version_id::TEXT
        OR jsonb_typeof(declaration->'familyCounts') IS DISTINCT FROM 'array'
        OR jsonb_array_length(declaration->'familyCounts') <> 6 THEN
        RAISE EXCEPTION 'Fresh graph APPLIED declaration has wrong canonical scope or family vector'
            USING ERRCODE = '23514';
    END IF;
    FOR family_index IN 1..6 LOOP
        family_entry := declaration->'familyCounts'->(family_index - 1);
        family_name := family_names[family_index];
        IF family_entry->>'family' IS DISTINCT FROM
                ('WORLD_DESIGN_AGGREGATE_TYPE_' || family_name)
            OR NOT (family_entry ? 'count')
            OR jsonb_typeof(family_entry->'count') IS DISTINCT FROM 'number'
            OR (family_entry->>'count') !~ '^(0|[1-9][0-9]*)$'
            OR (family_entry->>'count')::NUMERIC > 2147483647 THEN
            RAISE EXCEPTION 'Fresh graph APPLIED family declaration is absent, changed or noncanonical'
                USING ERRCODE = '23514';
        END IF;
        declared_count := (family_entry->>'count')::BIGINT;
        SELECT count(*) INTO mapping_count FROM "${serviceSchema}".world_authored_topology_identity
            WHERE request_id = NEW.request_id AND commit_id = NEW.commit_id AND family = family_name;
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id=$1 AND version_id=$2',
            '${serviceSchema}', lower(family_name))
            INTO content_count USING graph_row.local_tenant_key, graph_row.local_version_key;
        IF mapping_count IS DISTINCT FROM declared_count
            OR content_count IS DISTINCT FROM declared_count THEN
            RAISE EXCEPTION 'APPLIED requires every declared World family row and explicit empty family for exact Version'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF start_location->>'roomTemplateId' IS NULL
        OR start_location->>'roomTemplateId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR (start_location->>'roomTemplateId')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
        OR NOT EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity m
            JOIN "${serviceSchema}".room r ON r.id = m.private_row_key
            WHERE m.target_namespace = graph_row.target_namespace
              AND m.canonical_tenant_id = graph_row.canonical_tenant_id
              AND m.canonical_version_id = graph_row.canonical_version_id
              AND m.family = 'ROOM'
              AND m.template_id = (start_location->>'roomTemplateId')::UUID
              AND m.request_id = NEW.request_id AND m.commit_id = NEW.commit_id
              AND m.tenant_id = graph_row.local_tenant_key AND m.version_id = graph_row.local_version_key
              AND r.tenant_id = graph_row.local_tenant_key AND r.version_id = graph_row.local_version_key) THEN
        RAISE EXCEPTION 'APPLIED start selector must name one exact ROOM row in this graph and Version'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO receipt_count FROM "${serviceSchema}".world_draft_start_location_receipt
        WHERE operation_id = NEW.operation_id OR request_id = NEW.request_id
           OR commit_id = NEW.commit_id OR authorization_fence_id = NEW.authorization_fence_id;
    IF receipt_count <> 1 THEN
        RAISE EXCEPTION 'APPLIED requires one exact durable start-location receipt'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT receipt FROM "${serviceSchema}".world_draft_start_location_receipt
        WHERE operation_id = NEW.operation_id OR request_id = NEW.request_id
           OR commit_id = NEW.commit_id OR authorization_fence_id = NEW.authorization_fence_id;
    IF receipt.operation_id IS DISTINCT FROM NEW.operation_id
        OR receipt.request_id IS DISTINCT FROM NEW.request_id
        OR receipt.commit_id IS DISTINCT FROM NEW.commit_id
        OR receipt.authorization_fence_id IS DISTINCT FROM NEW.authorization_fence_id
        OR receipt.target_namespace IS DISTINCT FROM graph_row.target_namespace
        OR receipt.account_binding_bytes IS DISTINCT FROM NEW.account_binding_bytes
        OR receipt.account_binding_digest IS DISTINCT FROM NEW.account_binding_digest
        OR receipt.binding_digest IS DISTINCT FROM graph_row.binding_digest
        OR receipt.canonical_tenant_id IS DISTINCT FROM graph_row.canonical_tenant_id
        OR receipt.canonical_version_id IS DISTINCT FROM graph_row.canonical_version_id
        OR receipt.room_template_id IS DISTINCT FROM (start_location->>'roomTemplateId')::UUID
        OR receipt.graph_digest IS DISTINCT FROM ('sha256:' || graph_row.graph_sha256)
        OR receipt.receipt_digest !~ '^sha256:[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'APPLIED requires exact durable original Account-bound start selector receipt'
            USING ERRCODE = '23514';
    END IF;
    result_json := convert_from(NEW.result_bytes, 'UTF8')::JSONB;
    IF result_json->>'schema' IS DISTINCT FROM 'world-draft-graph-applied/v2'
        OR result_json->>'status' IS DISTINCT FROM 'APPLIED'
        OR result_json->>'graphDigest' IS DISTINCT FROM ('sha256:' || graph_row.graph_sha256)
        OR result_json->>'startLocationReceiptDigest' IS DISTINCT FROM receipt.receipt_digest
        OR decode(result_json->>'startLocationReceiptBase64', 'base64') IS DISTINCT FROM receipt.receipt_bytes
        OR decode(result_json->>'operationBytesBase64', 'base64') IS DISTINCT FROM NEW.operation_bytes
        OR decode(result_json->>'graphBytesBase64', 'base64') IS DISTINCT FROM graph_row.graph_bytes THEN
        RAISE EXCEPTION 'APPLIED result does not retain the exact graph, operation and start selector'
            USING ERRCODE = '23514';
    END IF;
    FOR unit IN SELECT value FROM jsonb_array_elements(graph_row.binding_json::JSONB->'affectedUnits')
        WHERE value->>'owner' = 'WORLD_MANAGEMENT' LOOP
        IF unit->>'expectedEpoch' <> '0' THEN
            RAISE EXCEPTION 'Fresh graph APPLIED requires original zero epochs' USING ERRCODE = '23514';
        END IF;
        SELECT private_row_key INTO mapped_key FROM "${serviceSchema}".world_authored_topology_identity
            WHERE request_id = NEW.request_id AND commit_id = NEW.commit_id
              AND family = unit->>'aggregateType' AND template_id = (unit->>'aggregateId')::UUID;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'APPLIED affected unit lacks exact fresh mapping' USING ERRCODE = '23514';
        END IF;
        IF unit->>'scopeType' = 'AGGREGATE' THEN
            SELECT draft_revision_epoch INTO current_epoch FROM "${serviceSchema}".world_design_aggregate_epoch
                WHERE tenant_id = graph_row.local_tenant_key AND version_id = graph_row.local_version_key
                  AND aggregate_type = unit->>'aggregateType' AND aggregate_id = mapped_key;
        ELSE
            SELECT draft_scope_revision_epoch INTO current_epoch FROM "${serviceSchema}".world_design_scope_epoch
                WHERE tenant_id = graph_row.local_tenant_key AND version_id = graph_row.local_version_key
                  AND scope_type = unit->>'scopeType' AND scope_id = unit->>'scopeId';
        END IF;
        IF NOT FOUND OR current_epoch IS DISTINCT FROM 1::BIGINT THEN
            RAISE EXCEPTION 'APPLIED lacks complete resulting World epochs' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    NEW.application_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
REVOKE ALL ON world_draft_start_location_receipt FROM PUBLIC;
REVOKE ALL ON FUNCTION world_reject_draft_start_location_receipt_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_guard_draft_start_location_receipt() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_guard_graph_application() FROM PUBLIC;
-- [jooq ignore stop]
