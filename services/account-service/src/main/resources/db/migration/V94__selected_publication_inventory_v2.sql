-- Mandatory v2 inventory applies to new settlements only; immutable retained rows are not upgraded.
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
    account_bytes BYTEA; input_bytes BYTEA; selection_bytes BYTEA;
    selector JSONB; inventory JSONB; selected JSONB; selected_binding JSONB;
    applied JSONB; applied_bytes BYTEA; applied_operation BYTEA; original_account BYTEA;
    owner JSONB; application JSONB; account_order JSONB; frozen JSONB; checkpoint JSONB;
    model JSONB; expected_epochs JSONB; expected_tuples JSONB; i INTEGER;
    shape RECORD; member RECORD; item JSONB; previous_key TEXT;
    counts INTEGER[] := ARRAY[]::INTEGER[];
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
    selection_bytes := selected_inventory_operation_nth(input_bytes, 2);
    IF NOT (convert_from(selection_bytes, 'UTF8') IS JSON OBJECT WITH UNIQUE KEYS) THEN
        RAISE EXCEPTION 'Original Account selection requires unique closed JSON members' USING ERRCODE = '23514'; END IF;
    selected := convert_from(selection_bytes, 'UTF8')::JSONB;
    IF NOT ((selected->>'selectedCommitBindingJson') IS JSON OBJECT WITH UNIQUE KEYS) THEN
        RAISE EXCEPTION 'Selected Account commit binding requires unique closed JSON members' USING ERRCODE = '23514'; END IF;
    selected_binding := (selected->>'selectedCommitBindingJson')::JSONB;
    IF octet_length(frames[3]) = 0 OR octet_length(frames[4]) = 0
        OR NOT (convert_from(frames[3], 'UTF8') IS JSON OBJECT WITH UNIQUE KEYS)
        OR NOT (convert_from(frames[4], 'UTF8') IS JSON OBJECT WITH UNIQUE KEYS) THEN
        RAISE EXCEPTION 'Complete unique World selector and public inventory required' USING ERRCODE = '23514'; END IF;
    selector := convert_from(frames[3], 'UTF8')::JSONB;
    inventory := convert_from(frames[4], 'UTF8')::JSONB;
    applied_bytes := decode(selector->>'appliedResultBytesBase64', 'base64');
    IF NOT (convert_from(applied_bytes, 'UTF8') IS JSON OBJECT WITH UNIQUE KEYS) THEN
        RAISE EXCEPTION 'APPLIED result requires unique closed JSON members' USING ERRCODE = '23514'; END IF;
    applied := convert_from(applied_bytes, 'UTF8')::JSONB;
    applied_operation := decode(applied->>'operationBytesBase64', 'base64');
    original_account := decode(selector->>'originalAccountBindingBytesBase64', 'base64');
    owner := inventory->'ownerScope'; application := inventory->'selectedApplication';
    account_order := inventory->'accountOrder'; frozen := inventory->'freeze';
    checkpoint := inventory->'checkpoint'; model := inventory->'sourceModel';
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
            OR EXISTS (SELECT 1 FROM jsonb_each(shape.value) entry WHERE entry.value = 'null'::JSONB) THEN
            RAISE EXCEPTION 'Incomplete or unknown public inventory members' USING ERRCODE = '23514'; END IF;
    END LOOP;
    FOR shape IN SELECT * FROM (VALUES (owner), (application), (account_order), (frozen), (checkpoint)) AS shapes(value) LOOP
        FOR member IN SELECT * FROM jsonb_each(shape.value) LOOP
            IF member.key <> 'digestSchemaVersion' AND jsonb_typeof(member.value) IS DISTINCT FROM 'string' THEN
                RAISE EXCEPTION 'Inventory binding members require exact string types' USING ERRCODE = '23514'; END IF;
            IF member.key IN ('canonicalTenantId','canonicalVersionId','versionIdentityOperationId','intakeRequestId','intakeOperationId','sourceOperationId','applicationOperationId','applicationRequestId','appliedCommitId','operationId','fenceId','actorAccountId','selectedCommitId','publicationFence')
                AND ((member.value #>> '{}') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                    OR (member.value #>> '{}') = '00000000-0000-0000-0000-000000000000') THEN
                RAISE EXCEPTION 'Canonical nonzero inventory UUID required' USING ERRCODE = '23514'; END IF;
            IF member.key IN ('intakeRequestDigest','sourceEvidenceDigest','intakeReceiptDigest','bindingDigest','appliedResultDigest','selectionDigest')
                AND (member.value #>> '{}') !~ '^sha256:[0-9a-f]{64}$' THEN
                RAISE EXCEPTION 'Canonical inventory digest required' USING ERRCODE = '23514'; END IF;
        END LOOP;
    END LOOP;
    IF owner->>'targetNamespace' !~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$' OR length(owner->>'targetNamespace') > 63
        OR frozen->>'versionStateEpoch' !~ '^[1-9][0-9]*$' OR (frozen->>'versionStateEpoch')::NUMERIC > 9223372036854775807
        OR frozen->>'requestDigest' !~ '^[0-9a-f]{64}$' OR checkpoint->>'contentDigest' !~ '^[0-9a-f]{64}$'
        OR jsonb_typeof(checkpoint->'digestSchemaVersion') IS DISTINCT FROM 'number'
        OR checkpoint->>'digestSchemaVersion' !~ '^[1-9][0-9]*$' OR (checkpoint->>'digestSchemaVersion')::NUMERIC > 2147483647
        OR btrim(account_order->>'publicationRequestId') = '' OR btrim(account_order->>'publicationRequestId') <> account_order->>'publicationRequestId'
        OR btrim(frozen->>'publicationRequestId') = '' OR btrim(frozen->>'publicationRequestId') <> frozen->>'publicationRequestId'
        OR btrim(frozen->>'publishWorkflowId') = '' OR btrim(frozen->>'publishWorkflowId') <> frozen->>'publishWorkflowId'
        OR model->>'graphDigest' !~ '^sha256:[0-9a-f]{64}$' OR model->>'topologyResultDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR jsonb_typeof(model->'modelId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(model->'graphSchemaVersion') IS DISTINCT FROM 'number'
        OR jsonb_typeof(model->'graphDigest') IS DISTINCT FROM 'string'
        OR jsonb_typeof(model->'topologyResultDigest') IS DISTINCT FROM 'string'
        OR model->'generationRuleFields' IS DISTINCT FROM '["id","name","scopeType","scopeId","value"]'::JSONB
        OR model->'regionFields' IS DISTINCT FROM '["id","shardId","name","weather","generationSeed","generatorType","generatorParams","spacingMultiplier"]'::JSONB
        OR model->'zoneFields' IS DISTINCT FROM '["id","regionId","name"]'::JSONB
        OR model->'roomFields' IS DISTINCT FROM '["id","zoneId","name","description","nameLocalizedVariantsJson","descriptionLocalizedVariantsJson"]'::JSONB
        OR model->'roomExitFields' IS DISTINCT FROM '["id","fromRoomId","toRoomId","direction","cost"]'::JSONB
        OR model->'spawnBindingFields' IS DISTINCT FROM '["id","roomId","entityTemplateType","entityReference.kind","entityReference.tenantId","entityReference.versionId","entityReference.templateId","spawnCount","respawnDelaySeconds"]'::JSONB
        OR jsonb_typeof(model->'familyCounts') IS DISTINCT FROM 'array' OR jsonb_array_length(model->'familyCounts') IS DISTINCT FROM 6
        OR jsonb_typeof(model->'regionGeneratorInputs') IS DISTINCT FROM 'array'
        OR jsonb_typeof(model->'spawnBindingInputs') IS DISTINCT FROM 'array'
        OR jsonb_typeof(model->'appliedEpochs') IS DISTINCT FROM 'array'
        OR jsonb_typeof(model->'generationRuleInputCount') IS DISTINCT FROM 'number'
        OR model->>'generationRuleInputCount' IS DISTINCT FROM '0'
        OR jsonb_typeof(model->'spawnBindingCount') IS DISTINCT FROM 'number'
        OR model->>'spawnBindingCount' !~ '^(0|[1-9][0-9]*)$'
        OR (model->>'spawnBindingCount')::NUMERIC > 2147483647 THEN
        RAISE EXCEPTION 'Unsupported public inventory source profile' USING ERRCODE = '23514'; END IF;
    FOR i IN 0..5 LOOP
        item := model->'familyCounts'->i;
        IF jsonb_typeof(item) IS DISTINCT FROM 'object' OR NOT item ?& ARRAY['family','rowCount']
            OR (SELECT count(*) FROM jsonb_object_keys(item)) <> 2
            OR item->'family' IS DISTINCT FROM to_jsonb((ARRAY['REGION','ZONE','ROOM','ROOM_EXIT','GENERATION_RULE','WORLD_ENTITY_SPAWN_BINDING'])[i+1])
            OR jsonb_typeof(item->'rowCount') IS DISTINCT FROM 'number' OR item->>'rowCount' !~ '^(0|[1-9][0-9]*)$'
            OR (item->>'rowCount')::NUMERIC > 2147483647 THEN
            RAISE EXCEPTION 'Incomplete source family enumeration' USING ERRCODE = '23514'; END IF;
        counts := array_append(counts, (item->>'rowCount')::INTEGER);
    END LOOP;
    IF counts[3] < 1 OR counts[5] <> 0
        OR counts[1] <> jsonb_array_length(model->'regionGeneratorInputs')
        OR counts[6] <> jsonb_array_length(model->'spawnBindingInputs')
        OR counts[6] <> (model->>'spawnBindingCount')::INTEGER THEN
        RAISE EXCEPTION 'Source inventory counts differ from explicit inputs' USING ERRCODE = '23514'; END IF;
    previous_key := NULL;
    FOR item IN SELECT * FROM jsonb_array_elements(model->'regionGeneratorInputs') LOOP
        IF jsonb_typeof(item) IS DISTINCT FROM 'object' OR NOT item ?& ARRAY['regionTemplateId','generatorType','generatorParams']
            OR (SELECT count(*) FROM jsonb_object_keys(item)) <> 3
            OR jsonb_typeof(item->'regionTemplateId') IS DISTINCT FROM 'string'
            OR item->>'regionTemplateId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            OR item->>'regionTemplateId' = '00000000-0000-0000-0000-000000000000'
            OR previous_key COLLATE "C" >= (item->>'regionTemplateId') COLLATE "C"
            OR item->'generatorType' IS DISTINCT FROM '""'::JSONB
            OR item->'generatorParams' IS DISTINCT FROM '""'::JSONB THEN
            RAISE EXCEPTION 'Unsupported or unordered region generator input' USING ERRCODE = '23514'; END IF;
        previous_key := item->>'regionTemplateId';
    END LOOP;
    previous_key := NULL;
    FOR item IN SELECT * FROM jsonb_array_elements(model->'spawnBindingInputs') LOOP
        IF jsonb_typeof(item) IS DISTINCT FROM 'object' OR NOT item ?& ARRAY['bindingTemplateId','roomTemplateId','entityTemplateType','entityReferenceKind','entityTenantId','entityVersionId','entityTemplateId','spawnCount','respawnDelaySeconds']
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
        IF item->>'entityReferenceKind' IS DISTINCT FROM 'ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID'
            OR item->>'entityTenantId' IS DISTINCT FROM owner->>'canonicalTenantId'
            OR item->>'entityVersionId' IS DISTINCT FROM owner->>'canonicalVersionId'
            OR item->>'entityTemplateType' NOT IN ('ITEM','NPC')
            OR jsonb_typeof(item->'spawnCount') IS DISTINCT FROM 'number' OR item->>'spawnCount' !~ '^[1-9][0-9]*$'
            OR (item->>'spawnCount')::NUMERIC > 2147483647
            OR jsonb_typeof(item->'respawnDelaySeconds') IS DISTINCT FROM 'number' OR item->>'respawnDelaySeconds' !~ '^(0|[1-9][0-9]*)$'
            OR (item->>'respawnDelaySeconds')::NUMERIC > 2147483647
            OR previous_key COLLATE "C" >= (item->>'bindingTemplateId') COLLATE "C" THEN
            RAISE EXCEPTION 'Unsupported or unordered scoped spawn input' USING ERRCODE = '23514'; END IF;
        previous_key := item->>'bindingTemplateId';
    END LOOP;
    SELECT coalesce(jsonb_agg(unit ORDER BY unit->>'owner', unit->>'aggregateType', unit->>'aggregateId',
        unit->>'scopeType', unit->>'scopeId', unit->>'expectedEpoch'), '[]'::JSONB)
        INTO expected_tuples FROM jsonb_array_elements(selected_binding->'affectedUnits') unit
        WHERE unit->>'owner' = 'WORLD_MANAGEMENT';
    SELECT coalesce(jsonb_agg((unit - 'owner') || jsonb_build_object('resultingEpoch', ((unit->>'expectedEpoch')::NUMERIC + 1)::TEXT)
        ORDER BY unit->>'aggregateType', unit->>'aggregateId', unit->>'scopeType', unit->>'scopeId'), '[]'::JSONB)
        INTO expected_epochs FROM jsonb_array_elements(selected_binding->'affectedUnits') unit
        WHERE unit->>'owner' = 'WORLD_MANAGEMENT';
    IF NOT (convert_from(frames[3], 'UTF8') IS JSON OBJECT WITH UNIQUE KEYS)
        OR NOT (convert_from(frames[4], 'UTF8') IS JSON OBJECT WITH UNIQUE KEYS)
        OR jsonb_typeof(selector) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(selector)) <> 5
        OR selector->>'schema' IS DISTINCT FROM 'world-published-start-location-evidence/v1'
        OR NOT (selector ?& ARRAY['schema', 'request', 'selectorReceiptBytesBase64',
            'originalAccountBindingBytesBase64', 'appliedResultBytesBase64'])
        OR jsonb_typeof(selector->'request') IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(selector->'request')) <> 13
        OR NOT ((selector->'request') ?& ARRAY['targetNamespace', 'canonicalTenantId',
            'canonicalVersionId', 'intakeRequestId', 'publicationFence', 'publicationRequestId',
            'requestDigest', 'versionStateEpoch', 'publishWorkflowId', 'appliedCommitId',
            'contentDigest', 'digestSchemaVersion', 'worldAffectedTuples'])
        OR jsonb_typeof(selector->'request'->'worldAffectedTuples') IS DISTINCT FROM 'array'
        OR jsonb_typeof(selector->'request'->'digestSchemaVersion') IS DISTINCT FROM 'number'
        OR EXISTS (SELECT 1 FROM jsonb_each(selector->'request') field
            WHERE field.key NOT IN ('digestSchemaVersion','worldAffectedTuples')
                AND jsonb_typeof(field.value) IS DISTINCT FROM 'string')
        OR EXISTS (SELECT 1 FROM jsonb_array_elements(selector->'request'->'worldAffectedTuples') tuple
            WHERE jsonb_typeof(tuple) IS DISTINCT FROM 'object'
                OR (SELECT count(*) FROM jsonb_object_keys(tuple)) <> 6
                OR NOT (tuple ?& ARRAY['owner','aggregateType','aggregateId','scopeType','scopeId','expectedEpoch'])
                OR EXISTS (SELECT 1 FROM jsonb_each(tuple) field WHERE jsonb_typeof(field.value) IS DISTINCT FROM 'string')
                OR tuple->>'owner' IS DISTINCT FROM 'WORLD_MANAGEMENT'
                OR tuple->>'expectedEpoch' !~ '^(0|[1-9][0-9]*)$')
        OR selected_inventory_operation_nth(account_bytes, 0) IS DISTINCT FROM convert_to('account-publication-authorization/v1', 'UTF8')
        OR selected_inventory_operation_nth(input_bytes, 0) IS DISTINCT FROM convert_to('account-publication-input/v1', 'UTF8')
        OR selected_inventory_operation_nth(input_bytes, 3) IS DISTINCT FROM
            convert_to('sha256:' || encode(sha256(selection_bytes), 'hex'), 'UTF8')
        OR selected->>'schemaVersion' IS DISTINCT FROM '1'
        OR (SELECT count(*) FROM jsonb_object_keys(selected)) <> 6
        OR NOT (selected ?& ARRAY['schemaVersion','intent','target','selectedCommitBindingJson','selectedCommitDigest','synchronizedFence'])
        OR jsonb_typeof(selected->'intent') IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(selected->'intent')) <> 8
        OR NOT (selected->'intent' ?& ARRAY['canonicalTenantId','canonicalVersionId','publishRequestId','expectedVersionStateEpoch','notes','selectedCommitRequestId','selectedCommitId','selectedCommitDigest'])
        OR jsonb_typeof(selected->'target') IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(selected->'target')) <> 7
        OR NOT (selected->'target' ?& ARRAY['canonicalTenantId','canonicalVersionId','gameDesignVersionRowId','gameDesignVersionTenantKey','sourceGameRowId','sourceGameTenantKey','sourceProvenanceKind'])
        OR jsonb_typeof(selected->'synchronizedFence') IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(selected->'synchronizedFence')) <> 5
        OR NOT (selected->'synchronizedFence' ?& ARRAY['requestId','commitId','inputDigest','resultVectorJson','createdAt'])
        OR applied->>'status' IS DISTINCT FROM 'APPLIED'
        OR jsonb_typeof(inventory) IS DISTINCT FROM 'object'
        OR inventory->>'schema' IS DISTINCT FROM 'world-selected-publication-artifact-inventory/v1'
        OR inventory->'schemaVersion' IS DISTINCT FROM '1'::JSONB OR inventory->>'completeness' IS DISTINCT FROM 'COMPLETE'
        OR (SELECT count(*) FROM jsonb_object_keys(inventory)) <> 10
        OR NOT (inventory ?& ARRAY['schema', 'schemaVersion', 'completeness', 'ownerScope',
            'selectedApplication', 'accountOrder', 'freeze', 'checkpoint', 'sourceModel', 'artifactDecisions'])
        OR jsonb_typeof(owner) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(owner)) <> 10
        OR NOT (owner ?& ARRAY['targetNamespace', 'canonicalTenantId', 'canonicalVersionId',
            'versionIdentityOperationId', 'intakeRequestId', 'intakeOperationId', 'intakeRequestDigest',
            'sourceOperationId', 'sourceEvidenceDigest', 'intakeReceiptDigest'])
        OR jsonb_typeof(application) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(application)) <> 5
        OR NOT (application ?& ARRAY['applicationOperationId', 'applicationRequestId', 'appliedCommitId',
            'bindingDigest', 'appliedResultDigest'])
        OR jsonb_typeof(account_order) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(account_order)) <> 7
        OR NOT (account_order ?& ARRAY['operationId', 'fenceId', 'actorAccountId',
            'publicationRequestId', 'selectionDigest', 'selectedCommitId', 'bindingDigest'])
        OR jsonb_typeof(frozen) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(frozen)) <> 5
        OR NOT (frozen ?& ARRAY['publicationFence', 'publicationRequestId', 'requestDigest',
            'versionStateEpoch', 'publishWorkflowId'])
        OR jsonb_typeof(checkpoint) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(checkpoint)) <> 3
        OR NOT (checkpoint ?& ARRAY['appliedCommitId', 'contentDigest', 'digestSchemaVersion'])
        OR jsonb_typeof(model) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(model)) <> 16
        OR NOT (model ?& ARRAY['modelId', 'graphSchemaVersion', 'graphDigest', 'topologyResultDigest',
            'familyCounts', 'regionGeneratorInputs', 'generationRuleFields', 'regionFields', 'zoneFields',
            'roomFields', 'roomExitFields', 'spawnBindingFields', 'spawnBindingInputs',
            'generationRuleInputCount', 'spawnBindingCount', 'appliedEpochs'])
        OR jsonb_typeof(model->'familyCounts') IS DISTINCT FROM 'array'
        OR jsonb_array_length(model->'familyCounts') IS DISTINCT FROM 6
        OR jsonb_typeof(model->'regionGeneratorInputs') IS DISTINCT FROM 'array'
        OR jsonb_typeof(model->'spawnBindingInputs') IS DISTINCT FROM 'array'
        OR jsonb_typeof(model->'appliedEpochs') IS DISTINCT FROM 'array'
        OR (SELECT jsonb_agg(entry->>'family' ORDER BY ordinal) FROM
            jsonb_array_elements(model->'familyCounts') WITH ORDINALITY AS entries(entry, ordinal))
            IS DISTINCT FROM '["REGION","ZONE","ROOM","ROOM_EXIT","GENERATION_RULE","WORLD_ENTITY_SPAWN_BINDING"]'::JSONB
        OR EXISTS (SELECT 1 FROM jsonb_array_elements(model->'familyCounts') entry
            WHERE jsonb_typeof(entry) IS DISTINCT FROM 'object'
                OR (SELECT count(*) FROM jsonb_object_keys(entry)) <> 2
                OR NOT (entry ?& ARRAY['family', 'rowCount'])
                OR jsonb_typeof(entry->'rowCount') IS DISTINCT FROM 'number'
                OR (entry->>'rowCount')::NUMERIC < 0)
        OR (SELECT jsonb_agg(entry ORDER BY entry->>'regionTemplateId') FROM
            jsonb_array_elements(model->'regionGeneratorInputs') entry)
            IS DISTINCT FROM model->'regionGeneratorInputs'
        OR EXISTS (SELECT 1 FROM jsonb_array_elements(model->'regionGeneratorInputs') entry
            WHERE jsonb_typeof(entry) IS DISTINCT FROM 'object'
                OR (SELECT count(*) FROM jsonb_object_keys(entry)) <> 3
                OR NOT (entry ?& ARRAY['regionTemplateId', 'generatorType', 'generatorParams'])
                OR entry->>'generatorType' IS DISTINCT FROM ''
                OR entry->>'generatorParams' IS DISTINCT FROM '')
        OR (SELECT jsonb_agg(entry ORDER BY entry->>'bindingTemplateId') FROM
            jsonb_array_elements(model->'spawnBindingInputs') entry)
            IS DISTINCT FROM model->'spawnBindingInputs'
        OR EXISTS (SELECT 1 FROM jsonb_array_elements(model->'spawnBindingInputs') entry
            WHERE jsonb_typeof(entry) IS DISTINCT FROM 'object'
                OR (SELECT count(*) FROM jsonb_object_keys(entry)) <> 9
                OR NOT (entry ?& ARRAY['bindingTemplateId', 'roomTemplateId', 'entityTemplateType',
                    'entityReferenceKind', 'entityTenantId', 'entityVersionId', 'entityTemplateId',
                    'spawnCount', 'respawnDelaySeconds'])
                OR entry->>'entityReferenceKind' IS DISTINCT FROM 'ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID'
                OR entry->>'entityTenantId' IS DISTINCT FROM owner->>'canonicalTenantId'
                OR entry->>'entityVersionId' IS DISTINCT FROM owner->>'canonicalVersionId'
                OR entry->>'entityTemplateType' NOT IN ('ITEM', 'NPC')
                OR (entry->>'spawnCount')::NUMERIC <= 0
                OR (entry->>'respawnDelaySeconds')::NUMERIC < 0)
        OR model->'generationRuleFields' IS DISTINCT FROM '["id","name","scopeType","scopeId","value"]'::JSONB
        OR model->'regionFields' IS DISTINCT FROM '["id","shardId","name","weather","generationSeed","generatorType","generatorParams","spacingMultiplier"]'::JSONB
        OR model->'zoneFields' IS DISTINCT FROM '["id","regionId","name"]'::JSONB
        OR model->'roomFields' IS DISTINCT FROM '["id","zoneId","name","description","nameLocalizedVariantsJson","descriptionLocalizedVariantsJson"]'::JSONB
        OR model->'roomExitFields' IS DISTINCT FROM '["id","fromRoomId","toRoomId","direction","cost"]'::JSONB
        OR model->'spawnBindingFields' IS DISTINCT FROM '["id","roomId","entityTemplateType","entityReference.kind","entityReference.tenantId","entityReference.versionId","entityReference.templateId","spawnCount","respawnDelaySeconds"]'::JSONB
        OR (SELECT count(*) FROM jsonb_array_elements(model->'regionGeneratorInputs')) IS DISTINCT FROM
            (SELECT (entry->>'rowCount')::INTEGER FROM jsonb_array_elements(model->'familyCounts') entry WHERE entry->>'family' = 'REGION')
        OR (SELECT count(*) FROM jsonb_array_elements(model->'spawnBindingInputs')) IS DISTINCT FROM
            (SELECT (entry->>'rowCount')::INTEGER FROM jsonb_array_elements(model->'familyCounts') entry WHERE entry->>'family' = 'WORLD_ENTITY_SPAWN_BINDING')
        OR (SELECT (entry->>'rowCount')::INTEGER FROM jsonb_array_elements(model->'familyCounts') entry WHERE entry->>'family' = 'ROOM') < 1
        OR (SELECT (entry->>'rowCount')::INTEGER FROM jsonb_array_elements(model->'familyCounts') entry WHERE entry->>'family' = 'GENERATION_RULE') <> 0
        OR model->'generationRuleInputCount' IS DISTINCT FROM '0'::JSONB
        OR model->'spawnBindingCount' IS DISTINCT FROM
            (SELECT entry->'rowCount' FROM jsonb_array_elements(model->'familyCounts') entry WHERE entry->>'family' = 'WORLD_ENTITY_SPAWN_BINDING')
        OR selector->'request'->'worldAffectedTuples' IS DISTINCT FROM expected_tuples
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
CREATE OR REPLACE FUNCTION account_selected_publication_settlement_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_selected_publication_authorizations%ROWTYPE;
    issuer account_control_ui_issuance_operations%ROWTYPE;
    source_key_value TEXT;
    p INTEGER;
    parsed BYTEA;
    world_bytes BYTEA;
    world JSONB;
    selection JSONB;
    input_bytes BYTEA;
    terminal_frames BYTEA[] := ARRAY[]::BYTEA[];
    receipt_frames BYTEA[] := ARRAY[]::BYTEA[];
    i INTEGER;
BEGIN
    -- Same canonical source-before-operation order as source writers. Never wait backwards on
    -- issuance, whose producers take it before sources. Nothing here performs a remote read.
    FOR source_key_value IN SELECT source_key FROM account_selected_publication_sources
        WHERE operation_id = NEW.operation_id
        ORDER BY account_publication_authorization_source_sort_key(source_key) LOOP
        PERFORM source_key FROM account_draft_authorization_source_locks
            WHERE source_key = source_key_value FOR UPDATE;
    END LOOP;
    SELECT * INTO STRICT original FROM account_selected_publication_authorizations
        WHERE operation_id = NEW.operation_id FOR UPDATE;
    SELECT * INTO STRICT issuer FROM account_control_ui_issuance_operations
        WHERE operation_id = original.issuance_operation_id FOR UPDATE NOWAIT;
    IF NEW.producer_xid <> txid_current() OR NEW.fence_id <> original.fence_id
        OR NEW.account_binding <> original.binding OR issuer.status <> 'COMMITTED'
        OR issuer.source_payload <> original.source_payload
        OR issuer.bundle_payload <> original.issuance_bundle THEN
        RAISE EXCEPTION 'Settlement differs from exact original committed Account order' USING ERRCODE = '23514';
    END IF;
    PERFORM require_selected_inventory_operation_v2(NEW.publication_operation);
    p := 1;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.publication_operation, p);
    IF parsed <> convert_to('game-design-publication-operation/v2', 'UTF8') THEN
        RAISE EXCEPTION 'Invalid publication operation schema' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.publication_operation, p);
    IF parsed <> original.binding THEN
        RAISE EXCEPTION 'Substituted original Account order' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO world_bytes, p FROM account_publication_authorization_read_frame(NEW.publication_operation, p);
    IF octet_length(world_bytes) = 0 THEN
        RAISE EXCEPTION 'Incomplete publication operation' USING ERRCODE = '23514';
    END IF;
    world := convert_from(world_bytes, 'UTF8')::JSONB;
    -- Retrieve the original immutable selection, without recapturing current authority.
    p := 1;
    FOR i IN 1..4 LOOP
        SELECT frame_value, next_position INTO input_bytes, p FROM account_publication_authorization_read_frame(original.binding, p);
    END LOOP;
    p := 1;
    FOR i IN 1..3 LOOP
        SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(input_bytes, p);
    END LOOP;
    selection := convert_from(parsed, 'UTF8')::JSONB;
    IF world->>'schema' IS DISTINCT FROM 'world-published-start-location-evidence/v1'
        OR world->'request'->>'canonicalTenantId' IS DISTINCT FROM original.tenant_uuid::TEXT
        OR world->'request'->>'canonicalVersionId' IS DISTINCT FROM selection->'intent'->>'canonicalVersionId'
        OR world->'request'->>'publicationRequestId' IS DISTINCT FROM original.publish_request_id
        OR world->'request'->>'versionStateEpoch' IS DISTINCT FROM selection->'intent'->>'expectedVersionStateEpoch'
        OR world->'request'->>'appliedCommitId' IS DISTINCT FROM selection->'intent'->>'selectedCommitId'
        OR 'sha256:' || (world->'request'->>'requestDigest') IS DISTINCT FROM
            'sha256:' || encode(sha256(parsed), 'hex')
        OR (original.world_evidence IS NOT NULL AND original.world_evidence <> world_bytes) THEN
        RAISE EXCEPTION 'Publication World evidence differs from original selected order' USING ERRCODE = '23514';
    END IF;
    p := 1;
    WHILE p <= octet_length(NEW.game_design_terminal) LOOP
        SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.game_design_terminal, p);
        terminal_frames := array_append(terminal_frames, parsed);
        IF cardinality(terminal_frames) > 5 THEN
            RAISE EXCEPTION 'Trailing publication terminal fields' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF terminal_frames[1] IS DISTINCT FROM convert_to('game-design-publication-terminal/v1', 'UTF8')
        OR terminal_frames[2] IS DISTINCT FROM NEW.publication_operation
        OR terminal_frames[3] IS DISTINCT FROM convert_to(NEW.game_design_outcome, 'UTF8')
        OR cardinality(terminal_frames) <> (CASE WHEN NEW.game_design_outcome = 'PUBLISHED' THEN 5 ELSE 3 END) THEN
        RAISE EXCEPTION 'Changed complete publication terminal relationship' USING ERRCODE = '23514';
    END IF;
    IF NEW.game_design_outcome = 'PUBLISHED' THEN
        IF convert_from(terminal_frames[5], 'UTF8') !~ '^[1-9][0-9]*$'
            OR convert_from(terminal_frames[5], 'UTF8')::BIGINT <>
                (world->'request'->>'versionStateEpoch')::BIGINT + 1 THEN
            RAISE EXCEPTION 'Changed committed publication epoch' USING ERRCODE = '23514';
        END IF;
        -- Game Design owns release semantics. Its canonical codec and the authenticated owner
        -- read validate the opaque complete content; Account binds all its exact bytes here.
        IF octet_length(terminal_frames[4]) = 0 THEN
            RAISE EXCEPTION 'Missing complete published release content' USING ERRCODE = '23514';
        END IF;
    END IF;
    p := 1;
    WHILE p <= octet_length(NEW.receipt) LOOP
        SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.receipt, p);
        receipt_frames := array_append(receipt_frames, parsed);
        IF cardinality(receipt_frames) > 6 THEN
            RAISE EXCEPTION 'Trailing settlement receipt' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF receipt_frames IS DISTINCT FROM ARRAY[convert_to('account-selected-publication-settlement/v1', 'UTF8'),
        NEW.account_binding, NEW.publication_operation, NEW.game_design_terminal,
        convert_to(NEW.world_outcome, 'UTF8'), NEW.world_terminal] THEN
        RAISE EXCEPTION 'Changed exact settlement receipt' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
