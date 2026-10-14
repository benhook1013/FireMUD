-- Mandatory original World public inventory; retained operation/capture bytes are never rewritten.
-- [jooq ignore start]
CREATE FUNCTION selected_inventory_operation_frame(operation_bytes BYTEA, frame_offset INTEGER) RETURNS BYTEA AS $$
DECLARE length_bytes BIGINT;
BEGIN
    IF frame_offset < 0 OR frame_offset > octet_length(operation_bytes) - 4 THEN
        RAISE EXCEPTION 'Truncated publication inventory frame' USING ERRCODE = '23514'; END IF;
    length_bytes := get_byte(operation_bytes, frame_offset)::BIGINT * 16777216
        + get_byte(operation_bytes, frame_offset + 1)::BIGINT * 65536
        + get_byte(operation_bytes, frame_offset + 2)::BIGINT * 256
        + get_byte(operation_bytes, frame_offset + 3)::BIGINT;
    IF length_bytes > 4194304 OR length_bytes > octet_length(operation_bytes) - frame_offset - 4 THEN
        RAISE EXCEPTION 'Oversized publication inventory frame' USING ERRCODE = '23514'; END IF;
    RETURN substring(operation_bytes FROM frame_offset + 5 FOR length_bytes::INTEGER);
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;
CREATE FUNCTION selected_inventory_operation_nth(operation_bytes BYTEA, frame_index INTEGER) RETURNS BYTEA AS $$
DECLARE offset_bytes INTEGER := 0; value BYTEA; i INTEGER;
BEGIN
    IF frame_index < 0 THEN RAISE EXCEPTION 'Negative frame index' USING ERRCODE = '23514'; END IF;
    FOR i IN 0..frame_index LOOP
        value := selected_inventory_operation_frame(operation_bytes, offset_bytes);
        offset_bytes := offset_bytes + 4 + octet_length(value);
    END LOOP;
    RETURN value;
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;
CREATE FUNCTION require_selected_inventory_operation_v2(operation_bytes BYTEA) RETURNS VOID AS $$
DECLARE frames BYTEA[] := ARRAY[]::BYTEA[]; value BYTEA; offset_bytes INTEGER := 0;
    account_bytes BYTEA; input_bytes BYTEA; selector JSONB; inventory JSONB; selected JSONB;
    applied JSONB; applied_bytes BYTEA; applied_operation BYTEA; original_account BYTEA;
    owner JSONB; application JSONB; account_order JSONB; frozen JSONB; checkpoint JSONB;
    model JSONB; expected_epochs JSONB; i INTEGER; shape RECORD; member RECORD;
    item JSONB; previous_key TEXT; counts INTEGER[] := ARRAY[]::INTEGER[];
BEGIN
    IF operation_bytes IS NULL OR octet_length(operation_bytes) NOT BETWEEN 1 AND 4194304 THEN
        RAISE EXCEPTION 'Complete bounded v2 publication operation required' USING ERRCODE = '23514'; END IF;
    FOR i IN 0..4 LOOP
        value := selected_inventory_operation_frame(operation_bytes, offset_bytes);
        offset_bytes := offset_bytes + 4 + octet_length(value);
        frames := array_append(frames, value);
    END LOOP;
    IF offset_bytes <> octet_length(operation_bytes) OR frames[1] <> convert_to('game-design-publication-operation/v2', 'UTF8')
        OR frames[5] IS DISTINCT FROM convert_to('sha256:' || encode(sha256(frames[4]), 'hex'), 'UTF8') THEN
        RAISE EXCEPTION 'Publication v2 schema, exact frame count or inventory digest invalid' USING ERRCODE = '23514'; END IF;
    account_bytes := frames[2];
    input_bytes := selected_inventory_operation_nth(account_bytes, 3);
    selected := convert_from(selected_inventory_operation_nth(input_bytes, 2), 'UTF8')::JSONB;
    selector := convert_from(frames[3], 'UTF8')::JSONB;
    IF NOT (convert_from(frames[4], 'UTF8') IS JSON OBJECT WITH UNIQUE KEYS) THEN
        RAISE EXCEPTION 'Inventory requires unique closed JSON members' USING ERRCODE = '23514'; END IF;
    inventory := convert_from(frames[4], 'UTF8')::JSONB;
    applied_bytes := decode(selector->>'appliedResultBytesBase64', 'base64');
    applied := convert_from(applied_bytes, 'UTF8')::JSONB;
    applied_operation := decode(applied->>'operationBytesBase64', 'base64');
    original_account := decode(selector->>'originalAccountBindingBytesBase64', 'base64');
    owner := inventory->'ownerScope'; application := inventory->'selectedApplication';
    account_order := inventory->'accountOrder'; frozen := inventory->'freeze';
    checkpoint := inventory->'checkpoint'; model := inventory->'sourceModel';
    -- Closed public structure is mandatory even for direct SQL callers. This checks structure,
    -- not producer authentication; GD cannot independently reconstruct World's private inputs.
    FOR shape IN SELECT * FROM (VALUES
        (inventory, ARRAY['schema','schemaVersion','completeness','ownerScope','selectedApplication','accountOrder','freeze','checkpoint','sourceModel','artifactDecisions']),
        (owner, ARRAY['targetNamespace','canonicalTenantId','canonicalVersionId','versionIdentityOperationId','intakeRequestId','intakeOperationId','intakeRequestDigest','sourceOperationId','sourceEvidenceDigest','intakeReceiptDigest']),
        (application, ARRAY['applicationOperationId','applicationRequestId','appliedCommitId','bindingDigest','appliedResultDigest']),
        (account_order, ARRAY['operationId','fenceId','actorAccountId','publicationRequestId','selectionDigest','selectedCommitId','bindingDigest']),
        (frozen, ARRAY['publicationFence','publicationRequestId','requestDigest','versionStateEpoch','publishWorkflowId']),
        (checkpoint, ARRAY['appliedCommitId','contentDigest','digestSchemaVersion']),
        (model, ARRAY['modelId','graphSchemaVersion','graphDigest','topologyResultDigest','familyCounts','regionGeneratorInputs','generationRuleFields','regionFields','zoneFields','roomFields','roomExitFields','spawnBindingFields','spawnBindingInputs','generationRuleInputCount','spawnBindingCount','appliedEpochs'])
        ) AS shapes(value, keys) LOOP
        IF jsonb_typeof(shape.value) IS DISTINCT FROM 'object'
            OR NOT shape.value ?& shape.keys
            OR (SELECT count(*) FROM jsonb_object_keys(shape.value)) <> cardinality(shape.keys)
            OR EXISTS (SELECT 1 FROM jsonb_each(shape.value) e WHERE e.value = 'null'::JSONB) THEN
            RAISE EXCEPTION 'Incomplete or unknown public inventory members' USING ERRCODE = '23514'; END IF;
    END LOOP;
    FOR shape IN SELECT * FROM (VALUES (owner), (application), (account_order), (frozen), (checkpoint)) AS shapes(value) LOOP
        FOR member IN SELECT * FROM jsonb_each(shape.value) LOOP
            IF member.key <> 'digestSchemaVersion' AND jsonb_typeof(member.value) IS DISTINCT FROM 'string' THEN
                RAISE EXCEPTION 'Inventory binding members require exact string types' USING ERRCODE = '23514'; END IF;
            IF member.key IN ('canonicalTenantId','canonicalVersionId','versionIdentityOperationId','intakeRequestId','intakeOperationId','sourceOperationId','applicationOperationId','applicationRequestId','appliedCommitId','operationId','fenceId','actorAccountId','selectedCommitId','publicationFence')
                AND (member.value #>> '{}') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                OR member.key IN ('canonicalTenantId','canonicalVersionId','versionIdentityOperationId','intakeRequestId','intakeOperationId','sourceOperationId','applicationOperationId','applicationRequestId','appliedCommitId','operationId','fenceId','actorAccountId','selectedCommitId','publicationFence')
                AND (member.value #>> '{}') = '00000000-0000-0000-0000-000000000000' THEN
                RAISE EXCEPTION 'Canonical nonzero inventory UUID required' USING ERRCODE = '23514'; END IF;
            IF member.key IN ('intakeRequestDigest','sourceEvidenceDigest','intakeReceiptDigest','bindingDigest','appliedResultDigest','selectionDigest')
                AND (member.value #>> '{}') !~ '^sha256:[0-9a-f]{64}$' THEN
                RAISE EXCEPTION 'Canonical inventory digest required' USING ERRCODE = '23514'; END IF;
        END LOOP;
    END LOOP;
    IF owner->>'targetNamespace' !~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$' OR length(owner->>'targetNamespace') > 63
        OR frozen->>'versionStateEpoch' !~ '^[1-9][0-9]*$' OR (frozen->>'versionStateEpoch')::NUMERIC > 9223372036854775807
        OR frozen->>'requestDigest' !~ '^[0-9a-f]{64}$' OR checkpoint->>'contentDigest' !~ '^[0-9a-f]{64}$'
        OR jsonb_typeof(checkpoint->'digestSchemaVersion') <> 'number' OR checkpoint->>'digestSchemaVersion' !~ '^[1-9][0-9]*$'
        OR (checkpoint->>'digestSchemaVersion')::NUMERIC > 2147483647
        OR btrim(account_order->>'publicationRequestId') = '' OR btrim(account_order->>'publicationRequestId') <> account_order->>'publicationRequestId'
        OR btrim(frozen->>'publicationRequestId') = '' OR btrim(frozen->>'publicationRequestId') <> frozen->>'publicationRequestId'
        OR btrim(frozen->>'publishWorkflowId') = '' OR btrim(frozen->>'publishWorkflowId') <> frozen->>'publishWorkflowId'
        OR model->>'graphDigest' !~ '^sha256:[0-9a-f]{64}$' OR model->>'topologyResultDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR jsonb_typeof(model->'modelId') <> 'string' OR jsonb_typeof(model->'graphDigest') <> 'string'
        OR jsonb_typeof(model->'topologyResultDigest') <> 'string'
        OR model->'generationRuleFields' <> '["id","name","scopeType","scopeId","value"]'::JSONB
        OR model->'regionFields' <> '["id","shardId","name","weather","generationSeed","generatorType","generatorParams","spacingMultiplier"]'::JSONB
        OR model->'zoneFields' <> '["id","regionId","name"]'::JSONB
        OR model->'roomFields' <> '["id","zoneId","name","description","nameLocalizedVariantsJson","descriptionLocalizedVariantsJson"]'::JSONB
        OR model->'roomExitFields' <> '["id","fromRoomId","toRoomId","direction","cost"]'::JSONB
        OR model->'spawnBindingFields' <> '["id","roomId","entityTemplateType","entityReference.kind","entityReference.tenantId","entityReference.versionId","entityReference.templateId","spawnCount","respawnDelaySeconds"]'::JSONB
        OR jsonb_typeof(model->'familyCounts') <> 'array' OR jsonb_array_length(model->'familyCounts') <> 6
        OR jsonb_typeof(model->'regionGeneratorInputs') <> 'array' OR jsonb_typeof(model->'spawnBindingInputs') <> 'array'
        OR jsonb_typeof(model->'spawnBindingCount') <> 'number' OR model->>'spawnBindingCount' !~ '^(0|[1-9][0-9]*)$'
        OR (model->>'spawnBindingCount')::NUMERIC > 2147483647 THEN
        RAISE EXCEPTION 'Unsupported public inventory source profile' USING ERRCODE = '23514'; END IF;
    FOR i IN 0..5 LOOP
        item := model->'familyCounts'->i;
        IF jsonb_typeof(item) <> 'object' OR NOT item ?& ARRAY['family','rowCount']
            OR (SELECT count(*) FROM jsonb_object_keys(item)) <> 2
            OR item->'family' IS DISTINCT FROM to_jsonb((ARRAY['REGION','ZONE','ROOM','ROOM_EXIT','GENERATION_RULE','WORLD_ENTITY_SPAWN_BINDING'])[i+1])
            OR jsonb_typeof(item->'rowCount') IS DISTINCT FROM 'number' OR item->>'rowCount' !~ '^(0|[1-9][0-9]*)$'
            OR (item->>'rowCount')::NUMERIC > 2147483647 THEN
            RAISE EXCEPTION 'Incomplete source family enumeration' USING ERRCODE = '23514'; END IF;
        counts := array_append(counts, (item->>'rowCount')::INTEGER);
    END LOOP;
    IF counts[3] < 1 OR counts[5] <> 0 OR counts[1] <> jsonb_array_length(model->'regionGeneratorInputs')
        OR counts[6] <> jsonb_array_length(model->'spawnBindingInputs') OR counts[6] <> (model->>'spawnBindingCount')::INTEGER THEN
        RAISE EXCEPTION 'Source inventory counts differ from explicit inputs' USING ERRCODE = '23514'; END IF;
    previous_key := NULL;
    FOR item IN SELECT * FROM jsonb_array_elements(model->'regionGeneratorInputs') LOOP
        IF jsonb_typeof(item) <> 'object' OR NOT item ?& ARRAY['regionTemplateId','generatorType','generatorParams']
            OR (SELECT count(*) FROM jsonb_object_keys(item)) <> 3
            OR jsonb_typeof(item->'regionTemplateId') IS DISTINCT FROM 'string'
            OR item->>'regionTemplateId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            OR item->>'regionTemplateId' = '00000000-0000-0000-0000-000000000000'
            OR previous_key COLLATE "C" >= (item->>'regionTemplateId') COLLATE "C"
            OR item->'generatorType' IS DISTINCT FROM '""'::JSONB OR item->'generatorParams' IS DISTINCT FROM '""'::JSONB THEN
            RAISE EXCEPTION 'Unsupported or unordered region generator input' USING ERRCODE = '23514'; END IF;
        previous_key := item->>'regionTemplateId';
    END LOOP;
    previous_key := NULL;
    FOR item IN SELECT * FROM jsonb_array_elements(model->'spawnBindingInputs') LOOP
        IF jsonb_typeof(item) <> 'object' OR NOT item ?& ARRAY['bindingTemplateId','roomTemplateId','entityTemplateType','entityReferenceKind','entityTenantId','entityVersionId','entityTemplateId','spawnCount','respawnDelaySeconds']
            OR (SELECT count(*) FROM jsonb_object_keys(item)) <> 9 THEN
            RAISE EXCEPTION 'Incomplete spawn input' USING ERRCODE = '23514'; END IF;
        FOR member IN SELECT * FROM jsonb_each(item) LOOP
            IF member.key NOT IN ('spawnCount','respawnDelaySeconds') AND jsonb_typeof(member.value) IS DISTINCT FROM 'string' THEN
                RAISE EXCEPTION 'Spawn reference exact string required' USING ERRCODE = '23514'; END IF;
            IF member.key IN ('bindingTemplateId','roomTemplateId','entityTenantId','entityVersionId','entityTemplateId')
                AND ((member.value #>> '{}') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                    OR (member.value #>> '{}') = '00000000-0000-0000-0000-000000000000') THEN
                RAISE EXCEPTION 'Canonical spawn UUID required' USING ERRCODE = '23514'; END IF;
        END LOOP;
        IF item->>'entityReferenceKind' <> 'ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID'
            OR item->>'entityTenantId' <> owner->>'canonicalTenantId' OR item->>'entityVersionId' <> owner->>'canonicalVersionId'
            OR item->>'entityTemplateType' NOT IN ('ITEM','NPC')
            OR jsonb_typeof(item->'spawnCount') IS DISTINCT FROM 'number' OR item->>'spawnCount' !~ '^[1-9][0-9]*$'
            OR (item->>'spawnCount')::NUMERIC > 2147483647
            OR jsonb_typeof(item->'respawnDelaySeconds') IS DISTINCT FROM 'number' OR item->>'respawnDelaySeconds' !~ '^(0|[1-9][0-9]*)$'
            OR (item->>'respawnDelaySeconds')::NUMERIC > 2147483647
            OR previous_key COLLATE "C" >= (item->>'bindingTemplateId') COLLATE "C" THEN
            RAISE EXCEPTION 'Unsupported or unordered scoped spawn input' USING ERRCODE = '23514'; END IF;
        previous_key := item->>'bindingTemplateId';
    END LOOP;
    SELECT coalesce(jsonb_agg((unit - 'owner') || jsonb_build_object('resultingEpoch', ((unit->>'expectedEpoch')::NUMERIC + 1)::TEXT)
        ORDER BY unit->>'aggregateType', unit->>'aggregateId', unit->>'scopeType', unit->>'scopeId'), '[]'::JSONB)
        INTO expected_epochs FROM jsonb_array_elements((selected->>'selectedCommitBindingJson')::JSONB->'affectedUnits') unit
        WHERE unit->>'owner' = 'WORLD_MANAGEMENT';
    IF inventory->>'schema' IS DISTINCT FROM 'world-selected-publication-artifact-inventory/v1'
        OR inventory->'schemaVersion' IS DISTINCT FROM '1'::JSONB OR inventory->>'completeness' IS DISTINCT FROM 'COMPLETE'
        OR (SELECT count(*) FROM jsonb_object_keys(inventory)) <> 10
        OR owner->>'targetNamespace' IS DISTINCT FROM selector->'request'->>'targetNamespace'
        OR owner->>'canonicalTenantId' IS DISTINCT FROM selector->'request'->>'canonicalTenantId'
        OR owner->>'canonicalVersionId' IS DISTINCT FROM selector->'request'->>'canonicalVersionId'
        OR owner->>'intakeRequestId' IS DISTINCT FROM selector->'request'->>'intakeRequestId'
        OR application->>'applicationOperationId' IS DISTINCT FROM convert_from(selected_inventory_operation_nth(applied_operation, 1), 'UTF8')
        OR application->>'applicationOperationId' IS DISTINCT FROM convert_from(selected_inventory_operation_nth(original_account, 1), 'UTF8')
        OR application->>'applicationRequestId' IS DISTINCT FROM selected->'intent'->>'selectedCommitRequestId'
        OR application->>'appliedCommitId' IS DISTINCT FROM selector->'request'->>'appliedCommitId'
        OR application->>'bindingDigest' IS DISTINCT FROM selected->'intent'->>'selectedCommitDigest'
        OR application->>'appliedResultDigest' IS DISTINCT FROM 'sha256:' || encode(sha256(applied_bytes), 'hex')
        OR account_order->>'operationId' IS DISTINCT FROM convert_from(selected_inventory_operation_nth(account_bytes, 1), 'UTF8')
        OR account_order->>'fenceId' IS DISTINCT FROM convert_from(selected_inventory_operation_nth(account_bytes, 2), 'UTF8')
        OR account_order->>'actorAccountId' IS DISTINCT FROM convert_from(selected_inventory_operation_nth(input_bytes, 1), 'UTF8')
        OR account_order->>'publicationRequestId' IS DISTINCT FROM selected->'intent'->>'publishRequestId'
        OR account_order->>'selectionDigest' IS DISTINCT FROM convert_from(selected_inventory_operation_nth(input_bytes, 3), 'UTF8')
        OR account_order->>'selectedCommitId' IS DISTINCT FROM selected->'intent'->>'selectedCommitId'
        OR account_order->>'bindingDigest' IS DISTINCT FROM 'sha256:' || encode(sha256(account_bytes), 'hex')
        OR frozen->>'publicationFence' IS DISTINCT FROM selector->'request'->>'publicationFence'
        OR frozen->>'publicationRequestId' IS DISTINCT FROM selector->'request'->>'publicationRequestId'
        OR frozen->>'requestDigest' IS DISTINCT FROM selector->'request'->>'requestDigest'
        OR frozen->>'versionStateEpoch' IS DISTINCT FROM selector->'request'->>'versionStateEpoch'
        OR frozen->>'publishWorkflowId' IS DISTINCT FROM selector->'request'->>'publishWorkflowId'
        OR checkpoint->>'appliedCommitId' IS DISTINCT FROM selector->'request'->>'appliedCommitId'
        OR checkpoint->>'contentDigest' IS DISTINCT FROM selector->'request'->>'contentDigest'
        OR checkpoint->'digestSchemaVersion' IS DISTINCT FROM selector->'request'->'digestSchemaVersion'
        OR model->>'graphDigest' IS DISTINCT FROM applied->>'graphDigest'
        OR model->'appliedEpochs' IS DISTINCT FROM expected_epochs OR expected_epochs = '[]'::JSONB
        OR model->>'modelId' IS DISTINCT FROM 'WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1'
        OR model->'graphSchemaVersion' IS DISTINCT FROM '2'::JSONB
        OR model->'generationRuleInputCount' IS DISTINCT FROM '0'::JSONB
        OR inventory->'artifactDecisions' IS DISTINCT FROM
            '[{"artifactKind":"NAVMESH","state":"NOT_REQUIRED","rule":"ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT"},{"artifactKind":"PATH_GRAPH","state":"NOT_REQUIRED","rule":"AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH"}]'::JSONB THEN
        RAISE EXCEPTION 'Inventory differs from exact original selected Account, APPLIED graph or freeze' USING ERRCODE = '23514'; END IF;
END;
$$ LANGUAGE plpgsql;
CREATE OR REPLACE FUNCTION guard_game_design_publication_operation() RETURNS trigger AS $$
DECLARE attempt publish_attempt%ROWTYPE; selected game_design_authored_draft_publish_selection%ROWTYPE;
    account_bytes BYTEA; input_bytes BYTEA; world_json JSONB;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'publication operation history is immutable' USING ERRCODE = 'check_violation';
    END IF;
    PERFORM 1 FROM game WHERE tenant_id = NEW.tenant_id FOR UPDATE;
    SELECT * INTO STRICT attempt FROM publish_attempt
        WHERE publish_workflow_id = NEW.publish_workflow_id FOR UPDATE;
    PERFORM 1 FROM version WHERE id = NEW.version_id AND tenant_id = NEW.tenant_id FOR UPDATE;
    SELECT * INTO STRICT selected FROM game_design_authored_draft_publish_selection
        WHERE game_design_version_tenant_key = NEW.tenant_id AND game_design_version_row_id = NEW.version_id;
    PERFORM require_selected_inventory_operation_v2(NEW.request_bytes);
    account_bytes := published_world_selector_frame(NEW.request_bytes, 1);
    input_bytes := published_world_selector_frame(account_bytes, 3);
    world_json := convert_from(published_world_selector_frame(NEW.request_bytes, 2), 'UTF8')::JSONB;
    IF convert_from(published_world_selector_frame(NEW.request_bytes, 0), 'UTF8') <> 'game-design-publication-operation/v2'
        OR convert_from(published_world_selector_frame(account_bytes, 0), 'UTF8') <> 'account-publication-authorization/v1'
        OR convert_from(published_world_selector_frame(input_bytes, 0), 'UTF8') <> 'account-publication-input/v1'
        OR published_world_selector_frame(input_bytes, 2) IS DISTINCT FROM convert_to(selected.selection_json, 'UTF8')
        OR convert_from(published_world_selector_frame(input_bytes, 3), 'UTF8') IS DISTINCT FROM selected.selection_digest
        OR world_json->'request'->>'requestDigest' IS DISTINCT FROM substring(selected.selection_digest FROM 8)
        OR world_json->'request'->>'canonicalTenantId' IS DISTINCT FROM selected.canonical_tenant_id::TEXT
        OR world_json->'request'->>'canonicalVersionId' IS DISTINCT FROM selected.canonical_version_id::TEXT
        OR world_json->'request'->>'publishWorkflowId' IS DISTINCT FROM NEW.publish_workflow_id
        OR world_json->'request'->>'publicationRequestId' IS DISTINCT FROM selected.publish_request_id
        OR world_json->'request'->>'versionStateEpoch' IS DISTINCT FROM selected.version_state_epoch::TEXT THEN
        RAISE EXCEPTION 'publication operation bytes differ from exact owner selection' USING ERRCODE = 'check_violation';
    END IF;
    IF attempt.tenant_id <> NEW.tenant_id OR attempt.version_id <> NEW.version_id
        OR attempt.publish_type <> 'FULL_VERSION' OR attempt.request_digest IS DISTINCT FROM NEW.selection_digest
        OR NOT EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection s
            WHERE s.game_design_version_tenant_key = NEW.tenant_id
                AND s.game_design_version_row_id = NEW.version_id AND s.selection_digest = NEW.selection_digest) THEN
        RAISE EXCEPTION 'publication operation does not bind exact selected attempt' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.outcome <> 'PENDING' OR NEW.revision <> 1 OR attempt.status <> 'PENDING'
            OR EXISTS (SELECT 1 FROM published_release_bundle WHERE tenant_id = NEW.tenant_id AND version_id = NEW.version_id) THEN
            RAISE EXCEPTION 'historical attempts cannot acquire publication authority' USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        IF OLD.outcome <> 'PENDING' OR NEW.outcome = 'PENDING'
            OR NEW.revision <> OLD.revision + 1
            OR (NEW.publish_workflow_id, NEW.tenant_id, NEW.version_id, NEW.selection_digest, NEW.request_bytes)
                IS DISTINCT FROM (OLD.publish_workflow_id, OLD.tenant_id, OLD.version_id, OLD.selection_digest, OLD.request_bytes) THEN
            RAISE EXCEPTION 'publication operation identity or terminal outcome is immutable' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]

-- A retained v1 operation stays immutable, but cannot acquire a new source capture.
-- [jooq ignore start]
CREATE FUNCTION guard_selected_inventory_capture_v2() RETURNS trigger AS $$
BEGIN
    PERFORM require_selected_inventory_operation_v2(NEW.operation_bytes);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER command_capture_inventory_v2 BEFORE INSERT ON game_design_command_source_capture
    FOR EACH ROW EXECUTE FUNCTION guard_selected_inventory_capture_v2();
CREATE TRIGGER policy_capture_inventory_v2 BEFORE INSERT ON game_design_realm_policy_capture
    FOR EACH ROW EXECUTE FUNCTION guard_selected_inventory_capture_v2();
CREATE TRIGGER asset_capture_inventory_v2 BEFORE INSERT ON game_design_asset_source_capture
    FOR EACH ROW EXECUTE FUNCTION guard_selected_inventory_capture_v2();

-- Historical v1 publication bytes remain readable history, never new derived policy authority.
-- Run before the original policy-set guard; all of its lifecycle/immutability checks still run
-- for a structurally valid v2 operation, including the one permitted sealing UPDATE.
CREATE FUNCTION guard_published_policy_inventory_v2() RETURNS trigger AS $$
BEGIN
    IF selected_inventory_operation_nth(NEW.operation_bytes, 0)
        IS DISTINCT FROM convert_to('game-design-publication-operation/v2', 'UTF8') THEN
        RAISE EXCEPTION 'Published realm policy evidence requires immutable v2 operation'
            USING ERRCODE = '23514';
    END IF;
    PERFORM require_selected_inventory_operation_v2(NEW.operation_bytes);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER a_published_policy_inventory_v2 BEFORE INSERT OR UPDATE
    ON game_design_published_realm_policy_set
    FOR EACH ROW EXECUTE FUNCTION guard_published_policy_inventory_v2();
-- [jooq ignore stop]
