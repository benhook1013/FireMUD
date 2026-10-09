-- Additive guard for the versioned selected-inbound source declaration. Existing V24/V30/V35
-- canonical-key, mapped-identity and execution-manifest guards remain authoritative; this guard
-- binds the explicit empty-only closure to the exact original graph written under the OPEN owner.
-- [jooq ignore start]
CREATE FUNCTION world_guard_selected_inbound_closure() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    graph_row "${serviceSchema}".world_topology_draft_commit%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    declaration JSONB;
    closure JSONB;
    graph_json JSONB;
    family_entry JSONB;
    family_names TEXT[] := ARRAY[
        'WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT',
        'WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT',
        'WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION',
        'WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING',
        'WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK',
        'WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE',
        'WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING'];
    declaration_count BIGINT;
    mapping_count BIGINT;
    family_index INTEGER;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'World selected-inbound APPLIED evidence is immutable and retained'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO graph_row FROM "${serviceSchema}".world_topology_draft_commit
        WHERE request_id = NEW.request_id AND commit_id = NEW.commit_id;
    IF NOT FOUND OR graph_row.application_transaction_id IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'Selected-inbound closure requires a genuinely fresh graph in this transaction'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO owner_row FROM "${serviceSchema}".world_design_publication_fence_owner
        WHERE target_namespace = graph_row.target_namespace
          AND canonical_tenant_id = graph_row.canonical_tenant_id
          AND local_tenant_key = graph_row.local_tenant_key
          AND version_id = graph_row.local_version_key FOR UPDATE;
    IF NOT FOUND OR owner_row.owner_freeze_phase <> 'OPEN'
        OR owner_row.current_publication_fence IS NOT NULL THEN
        RAISE EXCEPTION 'Selected-inbound closure requires the exact OPEN World owner lock'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO declaration_count
        FROM jsonb_array_elements(graph_row.binding_json::JSONB->'revisions') AS revision(value)
        WHERE value->>'owner' = 'WORLD_MANAGEMENT'
          AND (((value->>'payload')::JSONB)->'freshGraphDeclaration') IS NOT NULL;
    IF declaration_count <> 1 THEN
        RAISE EXCEPTION 'Selected-inbound closure requires one exact original World declaration'
            USING ERRCODE = '23514';
    END IF;
    SELECT ((value->>'payload')::JSONB)->'freshGraphDeclaration' INTO declaration
        FROM jsonb_array_elements(graph_row.binding_json::JSONB->'revisions') AS revision(value)
        WHERE value->>'owner' = 'WORLD_MANAGEMENT'
          AND (((value->>'payload')::JSONB)->'freshGraphDeclaration') ? 'inboundSourceClosure';
    closure := declaration->'inboundSourceClosure';
    IF jsonb_typeof(closure) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(
            CASE WHEN jsonb_typeof(closure) = 'object' THEN closure ELSE '{}'::JSONB END)) <> 2
        OR NOT (closure ? 'schemaVersion')
        OR jsonb_typeof(closure->'schemaVersion') IS DISTINCT FROM 'number'
        OR closure->>'schemaVersion' IS DISTINCT FROM '1'
        OR jsonb_typeof(closure->'familyCounts') IS DISTINCT FROM 'array'
        OR jsonb_array_length(closure->'familyCounts') <> array_length(family_names, 1) THEN
        RAISE EXCEPTION 'Selected-inbound source closure is unknown, incomplete or unversioned'
            USING ERRCODE = '23514';
    END IF;

    graph_json := convert_from(graph_row.graph_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(graph_json) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(
            CASE WHEN jsonb_typeof(graph_json) = 'object' THEN graph_json ELSE '{}'::JSONB END)) <> 5
        OR graph_json->>'schemaVersion' IS DISTINCT FROM '3'
        OR graph_json->>'canonicalTenantId' IS DISTINCT FROM graph_row.canonical_tenant_id::TEXT
        OR graph_json->>'canonicalVersionId' IS DISTINCT FROM graph_row.canonical_version_id::TEXT
        OR graph_json->'inboundSourceClosure' IS DISTINCT FROM closure
        OR jsonb_typeof(graph_json->'rows') IS DISTINCT FROM 'array'
        OR jsonb_array_length(graph_json->'rows') < 1 THEN
        RAISE EXCEPTION 'Canonical graph does not retain the exact original inbound closure'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO mapping_count
        FROM "${serviceSchema}".world_authored_topology_identity
        WHERE request_id = NEW.request_id AND commit_id = NEW.commit_id
          AND tenant_id = graph_row.local_tenant_key AND version_id = graph_row.local_version_key;
    IF mapping_count <> jsonb_array_length(graph_json->'rows') THEN
        RAISE EXCEPTION 'Selected-inbound closure lacks complete same-scope fresh mapped World rows'
            USING ERRCODE = '23514';
    END IF;

    FOR family_index IN 1..array_length(family_names, 1) LOOP
        family_entry := closure->'familyCounts'->(family_index - 1);
        IF jsonb_typeof(family_entry) IS DISTINCT FROM 'object'
            OR (SELECT count(*) FROM jsonb_object_keys(
                CASE WHEN jsonb_typeof(family_entry) = 'object' THEN family_entry ELSE '{}'::JSONB END)) <> 2
            OR family_entry->>'family' IS DISTINCT FROM family_names[family_index]
            OR NOT (family_entry ? 'count')
            OR jsonb_typeof(family_entry->'count') IS DISTINCT FROM 'number'
            OR family_entry->>'count' IS DISTINCT FROM '0' THEN
            RAISE EXCEPTION 'Selected-inbound family vector is noncanonical or outside the empty-only subset'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_world_graph_inbound_closure_guard BEFORE INSERT
    ON world_draft_graph_application FOR EACH ROW
    EXECUTE FUNCTION world_guard_selected_inbound_closure();
REVOKE ALL ON FUNCTION world_guard_selected_inbound_closure() FROM PUBLIC;
-- [jooq ignore stop]
