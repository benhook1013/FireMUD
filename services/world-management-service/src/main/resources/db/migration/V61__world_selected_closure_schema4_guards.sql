-- A selected World digest/4 is valid only for a newly applied graph/3 carrying the exact original
-- seven-family empty declaration. Historical schema-2/3 rows and V27 bytes are not rewritten.
-- [jooq ignore start]
DROP TRIGGER trg_world_canonical_frozen_00_schema3 ON world_canonical_frozen_topology;

CREATE FUNCTION world_require_new_selected_digest_capture() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    request JSONB;
    attempt "${serviceSchema}".world_design_publication_fence_attempt%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    commit_row "${serviceSchema}".world_topology_draft_commit%ROWTYPE;
    graph_json JSONB;
    closure JSONB;
    declaration_count BIGINT;
    matching_declaration_count BIGINT;
    mapping_count BIGINT;
    applied_count BIGINT;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'Canonical frozen capture requires writable READ COMMITTED'
            USING ERRCODE = '23514';
    END IF;

    request := NEW.freeze_request_json::JSONB;
    IF request->>'digestSchemaVersion' IS DISTINCT FROM '4' THEN
        RAISE EXCEPTION 'New canonical graph capture requires selected closure schema 4'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT commit_row
    FROM "${serviceSchema}".world_topology_draft_commit c
    WHERE c.request_id = NEW.request_id AND c.commit_id = NEW.commit_id;
    SELECT * INTO STRICT attempt
    FROM "${serviceSchema}".world_design_publication_fence_attempt a
    WHERE a.publication_fence = NEW.publication_fence;
    SELECT * INTO STRICT owner_row
    FROM "${serviceSchema}".world_design_publication_fence_owner o
    WHERE o.target_namespace = commit_row.target_namespace
        AND o.canonical_tenant_id = commit_row.canonical_tenant_id
        AND o.local_tenant_key = commit_row.local_tenant_key
        AND o.version_id = commit_row.local_version_key
    FOR UPDATE;

    IF owner_row.owner_freeze_phase IS DISTINCT FROM 'FROZEN'
        OR owner_row.current_publication_fence IS DISTINCT FROM NEW.publication_fence
        OR attempt.owner_binding_schema_version IS DISTINCT FROM 1
        OR attempt.publication_fence IS DISTINCT FROM NEW.publication_fence
        OR attempt.target_namespace IS DISTINCT FROM request->>'targetNamespace'
        OR attempt.canonical_tenant_id::TEXT IS DISTINCT FROM request->>'canonicalTenantId'
        OR attempt.canonical_version_id::TEXT IS DISTINCT FROM request->>'canonicalVersionId'
        OR attempt.applied_commit_id IS DISTINCT FROM request->>'appliedCommitId'
        OR attempt.content_digest IS DISTINCT FROM request->>'contentDigest'
        OR attempt.digest_schema_version IS DISTINCT FROM 4
        OR commit_row.target_namespace IS DISTINCT FROM request->>'targetNamespace'
        OR commit_row.canonical_tenant_id::TEXT IS DISTINCT FROM request->>'canonicalTenantId'
        OR commit_row.canonical_version_id::TEXT IS DISTINCT FROM request->>'canonicalVersionId'
        OR commit_row.graph_bytes IS DISTINCT FROM NEW.graph_bytes
        OR commit_row.result_bytes IS DISTINCT FROM NEW.storage_result_bytes THEN
        RAISE EXCEPTION 'Selected graph/4 capture differs from exact FROZEN source and checkpoint'
            USING ERRCODE = '23514';
    END IF;

    closure := '{"schemaVersion":1,"familyCounts":[{"family":"WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING","count":0}]}'::JSONB;
    graph_json := convert_from(commit_row.graph_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(graph_json) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(
            CASE WHEN jsonb_typeof(graph_json) = 'object' THEN graph_json ELSE '{}'::JSONB END)) <> 5
        OR graph_json->>'schemaVersion' IS DISTINCT FROM '3'
        OR graph_json->>'canonicalTenantId' IS DISTINCT FROM commit_row.canonical_tenant_id::TEXT
        OR graph_json->>'canonicalVersionId' IS DISTINCT FROM commit_row.canonical_version_id::TEXT
        OR graph_json->'inboundSourceClosure' IS DISTINCT FROM closure
        OR jsonb_typeof(graph_json->'rows') IS DISTINCT FROM 'array'
        OR jsonb_array_length(graph_json->'rows') < 1 THEN
        RAISE EXCEPTION 'Selected digest/4 requires the exact original graph/3 closure declaration'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO declaration_count
    FROM jsonb_array_elements(commit_row.binding_json::JSONB->'revisions') AS revision(value)
    WHERE revision.value->>'owner' = 'WORLD_MANAGEMENT'
        AND (((revision.value->>'payload')::JSONB)->'freshGraphDeclaration') IS NOT NULL;
    SELECT count(*) INTO matching_declaration_count
    FROM jsonb_array_elements(commit_row.binding_json::JSONB->'revisions') AS revision(value)
    WHERE revision.value->>'owner' = 'WORLD_MANAGEMENT'
        AND (((revision.value->>'payload')::JSONB)->'freshGraphDeclaration'->'inboundSourceClosure')
            IS NOT DISTINCT FROM closure;
    IF declaration_count <> 1 OR matching_declaration_count <> 1 THEN
        RAISE EXCEPTION 'Selected digest/4 requires one exact original authored closure declaration'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO mapping_count
    FROM "${serviceSchema}".world_authored_topology_identity m
    WHERE m.request_id = commit_row.request_id AND m.commit_id = commit_row.commit_id
        AND m.tenant_id = commit_row.local_tenant_key
        AND m.version_id = commit_row.local_version_key;
    SELECT count(*) INTO applied_count
    FROM "${serviceSchema}".world_draft_graph_application a
    WHERE a.request_id = commit_row.request_id AND a.commit_id = commit_row.commit_id
        AND a.application_transaction_id = commit_row.application_transaction_id
        AND a.result_digest = 'sha256:' || encode(sha256(a.result_bytes), 'hex')
        AND convert_from(a.result_bytes, 'UTF8')::JSONB->>'status' = 'APPLIED'
        AND convert_from(a.result_bytes, 'UTF8')::JSONB->>'graphDigest'
            = 'sha256:' || encode(sha256(commit_row.graph_bytes), 'hex');
    IF mapping_count <> jsonb_array_length(graph_json->'rows') OR mapping_count = 0
        OR applied_count <> 1 THEN
        RAISE EXCEPTION 'Selected digest/4 requires complete same-scope fresh mappings and exact APPLIED result'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_world_canonical_frozen_00_selected_digest
    BEFORE INSERT ON world_canonical_frozen_topology
    FOR EACH ROW EXECUTE FUNCTION world_require_new_selected_digest_capture();
REVOKE ALL ON FUNCTION world_require_new_selected_digest_capture() FROM PUBLIC;

-- Advance the release join without changing its v1/v2 evidence or preparation behavior. Selected
-- attestation/2 remains World/3; only attestation/3 may carry World/4 and its exact selector.
DO $migration$
DECLARE
    preparation_function REGPROCEDURE :=
        '"${serviceSchema}".world_prepare_canonical_instance(text,text)'::REGPROCEDURE;
    original_definition TEXT;
    anchor TEXT := $anchor$                AND participant->>'digestSchemaVersion' = '3'$anchor$;
    replacement TEXT := $replacement$                AND binding_row.release_attestation_json::JSONB->>'schemaVersion' IN ('2', '3')
                AND participant->>'digestSchemaVersion' = CASE
                    binding_row.release_attestation_json::JSONB->>'schemaVersion'
                    WHEN '2' THEN '3' WHEN '3' THEN '4' ELSE NULL END
                AND binding_row.release_attestation_json::JSONB->'worldStartLocationEvidence'->'request'->>'digestSchemaVersion'
                    = capture_row.freeze_request_json::JSONB->>'digestSchemaVersion'$replacement$;
BEGIN
    SELECT pg_get_functiondef(preparation_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, anchor, '')))
            / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V40 World digest-3 participant guard';
    END IF;
    EXECUTE replace(original_definition, anchor, replacement);
END;
$migration$;

-- Keep preparation input schema 2 and all V1 retries exact. Its release selector pair is either
-- the retained attestation/2 + World/3 pair or new attestation/3 + World/4 pair.
DO $migration$
DECLARE
    selector_function REGPROCEDURE :=
        '"${serviceSchema}".world_require_preparation_start_location(jsonb,jsonb,uuid)'::REGPROCEDURE;
    original_definition TEXT;
    schema_anchor TEXT := $anchor$    IF input->>'schemaVersion' IS DISTINCT FROM '2' OR binding->>'schemaVersion' IS DISTINCT FROM '2'
        OR input->>'worldStartLocationEvidenceBase64' IS NULL$anchor$;
    schema_replacement TEXT := $replacement$    IF input->>'schemaVersion' IS DISTINCT FROM '2'
        OR binding->>'schemaVersion' IS NULL
        OR binding->>'schemaVersion' NOT IN ('2', '3')
        OR input->>'worldStartLocationEvidenceBase64' IS NULL$replacement$;
    digest_anchor TEXT := $anchor$        OR selected->>'digestSchemaVersion' IS DISTINCT FROM '3'$anchor$;
    -- Parentheses keep the CASE's THEN tokens inside the SQL expression rather than terminating
    -- PL/pgSQL's outer IF condition while the rewritten function is compiled.
    digest_replacement TEXT := $replacement$        OR selected->>'digestSchemaVersion' IS DISTINCT FROM (CASE binding->>'schemaVersion'
            WHEN '2' THEN '3' WHEN '3' THEN '4' ELSE NULL END)$replacement$;
BEGIN
    SELECT pg_get_functiondef(selector_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, schema_anchor, '')))
            / length(schema_anchor) <> 1
        OR (length(original_definition) - length(replace(original_definition, digest_anchor, '')))
            / length(digest_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V42 selected World schema-3 selector guards';
    END IF;
    original_definition := replace(original_definition, schema_anchor, schema_replacement);
    original_definition := replace(original_definition, digest_anchor, digest_replacement);
EXECUTE original_definition;
END;
$migration$;

-- The immutable operation/public projection accepts only legacy inventory/1 + World/3 or the
-- explicit closure inventory/2 + World/4 pair. It never promotes an absent declaration.
DO $migration$
DECLARE
    inventory_function REGPROCEDURE :=
        '"${serviceSchema}".require_selected_inventory_operation_v2(bytea)'::REGPROCEDURE;
    original_definition TEXT;
    anchor TEXT := $anchor$    IF inventory->>'schema' IS DISTINCT FROM 'world-selected-publication-artifact-inventory/v1'
        OR inventory->'schemaVersion' IS DISTINCT FROM '1'::JSONB OR inventory->>'completeness' IS DISTINCT FROM 'COMPLETE'$anchor$;
    replacement TEXT := $replacement$    IF ((
            (inventory->>'schema' = 'world-selected-publication-artifact-inventory/v1'
                AND inventory->'schemaVersion' = '1'::JSONB
                AND model->>'modelId' = 'WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1'
                AND model->'graphSchemaVersion' = '2'::JSONB
                AND checkpoint->'digestSchemaVersion' = '3'::JSONB
                AND selector->'request'->'digestSchemaVersion' = '3'::JSONB
                AND NOT (model ? 'inboundSourceClosure')
                AND (SELECT count(*) FROM jsonb_object_keys(
                    CASE WHEN jsonb_typeof(model) = 'object' THEN model ELSE '{}'::JSONB END)) = 16)
            OR
            (inventory->>'schema' = 'world-selected-publication-artifact-inventory/v2'
                AND inventory->'schemaVersion' = '2'::JSONB
                AND model->>'modelId' = 'WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_INBOUND_EMPTY_V1'
                AND model->'graphSchemaVersion' = '3'::JSONB
                AND checkpoint->'digestSchemaVersion' = '4'::JSONB
                AND selector->'request'->'digestSchemaVersion' = '4'::JSONB
                AND (SELECT count(*) FROM jsonb_object_keys(
                    CASE WHEN jsonb_typeof(model) = 'object' THEN model ELSE '{}'::JSONB END)) = 17
                AND model ?& ARRAY['modelId', 'graphSchemaVersion', 'graphDigest',
                    'topologyResultDigest', 'familyCounts', 'regionGeneratorInputs',
                    'generationRuleFields', 'regionFields', 'zoneFields', 'roomFields',
                    'roomExitFields', 'spawnBindingFields', 'spawnBindingInputs',
                    'generationRuleInputCount', 'spawnBindingCount', 'appliedEpochs',
                    'inboundSourceClosure']
                AND model->'inboundSourceClosure' IS NOT DISTINCT FROM
                    '{"schemaVersion":1,"familyCounts":[{"family":"WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING","count":0}]}'::JSONB)
        ) IS NOT TRUE) OR inventory->>'completeness' IS DISTINCT FROM 'COMPLETE'$replacement$;
    legacy_model_anchor TEXT := $anchor$        OR model->>'modelId' IS DISTINCT FROM 'WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1'
        OR model->'graphSchemaVersion' IS DISTINCT FROM '2'::JSONB$anchor$;
    legacy_model_replacement TEXT := '';
BEGIN
    SELECT pg_get_functiondef(inventory_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, anchor, '')))
            / length(anchor) <> 1
        OR (length(original_definition) - length(replace(original_definition, legacy_model_anchor, '')))
            / length(legacy_model_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V56 selected inventory schema-1 profile guard';
    END IF;
    original_definition := replace(original_definition, anchor, replacement);
    -- Model id and graph version are validated as exact paired profiles above; retain the
    -- generation-rule and artifact-decision restrictions below for both profiles.
    original_definition := replace(original_definition, legacy_model_anchor, legacy_model_replacement);
    EXECUTE original_definition;
END;
$migration$;

-- Correlate the additive database discriminator with immutable bytes on the SQL owner path too.
DO $migration$
DECLARE
    operation_function REGPROCEDURE :=
        '"${serviceSchema}".world_require_publication_operation_account_binding(bytea,uuid,bytea)'::REGPROCEDURE;
    original_definition TEXT;
    declaration_anchor TEXT := $anchor$    public_inventory BYTEA; public_digest BYTEA; private_inventory JSONB; expected_public JSONB; model JSONB;$anchor$;
    declaration_replacement TEXT := $replacement$    public_inventory BYTEA; public_digest BYTEA; private_inventory JSONB; expected_public JSONB; model JSONB;
    inventory_schema_version SMALLINT;$replacement$;
    select_anchor TEXT := $anchor$    SELECT convert_from(i.inventory_bytes, 'UTF8')::JSONB INTO STRICT private_inventory
        FROM "${serviceSchema}".world_selected_publication_artifact_inventory i$anchor$;
    select_replacement TEXT := $replacement$    SELECT i.inventory_schema_version, convert_from(i.inventory_bytes, 'UTF8')::JSONB
        INTO STRICT inventory_schema_version, private_inventory
        FROM "${serviceSchema}".world_selected_publication_artifact_inventory i$replacement$;
    projection_anchor TEXT := $anchor$    IF jsonb_typeof(private_inventory) IS DISTINCT FROM 'object'$anchor$;
    projection_replacement TEXT := $replacement$    IF private_inventory->'schemaVersion' IS DISTINCT FROM to_jsonb(inventory_schema_version)
        OR jsonb_typeof(private_inventory) IS DISTINCT FROM 'object'$replacement$;
BEGIN
    SELECT pg_get_functiondef(operation_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, declaration_anchor, '')))
            / length(declaration_anchor) <> 1
        OR (length(original_definition) - length(replace(original_definition, select_anchor, '')))
            / length(select_anchor) <> 1
        OR (length(original_definition) - length(replace(original_definition, projection_anchor, '')))
            / length(projection_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V56 World inventory discriminator/readback guards';
    END IF;
    original_definition := replace(original_definition, declaration_anchor, declaration_replacement);
    original_definition := replace(original_definition, select_anchor, select_replacement);
    original_definition := replace(original_definition, projection_anchor, projection_replacement);
    EXECUTE original_definition;
END;
$migration$;
-- [jooq ignore stop]
