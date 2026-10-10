-- Mandatory v2 public evidence, derived from unchanged private retained inventory.
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
    model JSONB; expected_epochs JSONB; i INTEGER;
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
    inventory := convert_from(frames[4], 'UTF8')::JSONB;
    applied_bytes := decode(selector->>'appliedResultBytesBase64', 'base64');
    applied := convert_from(applied_bytes, 'UTF8')::JSONB;
    applied_operation := decode(applied->>'operationBytesBase64', 'base64');
    original_account := decode(selector->>'originalAccountBindingBytesBase64', 'base64');
    owner := inventory->'ownerScope'; application := inventory->'selectedApplication';
    account_order := inventory->'accountOrder'; frozen := inventory->'freeze';
    checkpoint := inventory->'checkpoint'; model := inventory->'sourceModel';
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
CREATE OR REPLACE FUNCTION world_require_publication_operation_account_binding(
    p_operation_bytes BYTEA,
    p_publication_fence UUID,
    p_world_evidence_bytes BYTEA
)
RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    frame_offset INTEGER := 0;
    schema_bytes BYTEA;
    account_binding_bytes BYTEA;
    framed_world_evidence BYTEA;
    retained_account_binding BYTEA;
    retained_account_digest VARCHAR(71);
    public_inventory BYTEA; public_digest BYTEA; private_inventory JSONB; expected_public JSONB; model JSONB;
BEGIN
    IF p_operation_bytes IS NULL
        OR octet_length(p_operation_bytes) > 4194304
        OR octet_length(p_operation_bytes) < 4 THEN
        RAISE EXCEPTION 'World publication operation is empty or oversized'
            USING ERRCODE = '23514';
    END IF;

    schema_bytes := world_publication_operation_frame(p_operation_bytes, frame_offset);
    IF schema_bytes IS DISTINCT FROM convert_to('game-design-publication-operation/v2', 'UTF8') THEN
        RAISE EXCEPTION 'World publication operation has an unsupported schema frame'
            USING ERRCODE = '23514';
    END IF;
    frame_offset := frame_offset + 4 + octet_length(schema_bytes);

    account_binding_bytes := world_publication_operation_frame(p_operation_bytes, frame_offset);
    frame_offset := frame_offset + 4 + octet_length(account_binding_bytes);

    PERFORM require_selected_inventory_operation_v2(p_operation_bytes);
    framed_world_evidence := world_publication_operation_frame(p_operation_bytes, frame_offset);
    frame_offset := frame_offset + 4 + octet_length(framed_world_evidence);
    public_inventory := world_publication_operation_frame(p_operation_bytes, frame_offset);
    frame_offset := frame_offset + 4 + octet_length(public_inventory);
    public_digest := world_publication_operation_frame(p_operation_bytes, frame_offset);
    frame_offset := frame_offset + 4 + octet_length(public_digest);
    IF frame_offset <> octet_length(p_operation_bytes) THEN
        RAISE EXCEPTION 'World publication operation contains trailing frames or bytes'
            USING ERRCODE = '23514';
    END IF;
    IF framed_world_evidence IS DISTINCT FROM p_world_evidence_bytes THEN
        RAISE EXCEPTION 'World publication operation differs from its exact World evidence'
            USING ERRCODE = '23514';
    END IF;

    SELECT q.account_binding_bytes, q.account_binding_digest
        INTO STRICT retained_account_binding, retained_account_digest
    FROM "${serviceSchema}".world_design_publication_account_binding q
    WHERE q.publication_fence = p_publication_fence;
    IF encode(sha256(retained_account_binding), 'hex')
        IS DISTINCT FROM substring(retained_account_digest FROM 8) THEN
        RAISE EXCEPTION 'World publication Account qualification has a corrupt binding digest'
            USING ERRCODE = '23514';
    END IF;

    SELECT convert_from(i.inventory_bytes, 'UTF8')::JSONB INTO STRICT private_inventory
        FROM "${serviceSchema}".world_selected_publication_artifact_inventory i
        WHERE i.publication_fence = p_publication_fence
            AND i.inventory_digest = 'sha256:' || encode(sha256(i.inventory_bytes), 'hex');
    model := private_inventory->'sourceModel';
    model := jsonb_set(model, '{regionGeneratorInputs}',
        coalesce((SELECT jsonb_agg(x ORDER BY x->>'regionTemplateId') FROM jsonb_array_elements(model->'regionGeneratorInputs') x), '[]'::JSONB));
    model := jsonb_set(model, '{spawnBindingInputs}',
        coalesce((SELECT jsonb_agg(x ORDER BY x->>'bindingTemplateId') FROM jsonb_array_elements(model->'spawnBindingInputs') x), '[]'::JSONB));
    expected_public := jsonb_build_object(
        'schema', private_inventory->'schema', 'schemaVersion', private_inventory->'schemaVersion',
        'completeness', private_inventory->'completeness',
        'ownerScope', private_inventory->'ownerScope' - ARRAY['localTenantKey', 'localVersionKey', 'gameDesignVersionId'],
        'selectedApplication', private_inventory->'selectedApplication',
        'accountOrder', private_inventory->'accountOrder' - ARRAY['canonicalBindingBytes'],
        'freeze', private_inventory->'freeze', 'checkpoint', private_inventory->'checkpoint',
        'sourceModel', model, 'artifactDecisions', private_inventory->'artifactDecisions');
    IF jsonb_typeof(private_inventory) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(private_inventory)) <> 11
        OR NOT (private_inventory ?& ARRAY['schema', 'schemaVersion', 'completeness', 'ownerScope',
            'sourceIntake', 'selectedApplication', 'accountOrder', 'freeze', 'checkpoint',
            'sourceModel', 'artifactDecisions'])
        OR jsonb_typeof(private_inventory->'sourceIntake') IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(private_inventory->'sourceIntake')) <> 4
        OR NOT (private_inventory->'sourceIntake' ?& ARRAY['worldSlug', 'sourceGameRowId',
            'sourceGameTenantKey', 'sourceProvenanceKind'])
        OR NOT (convert_from(public_inventory, 'UTF8') IS JSON OBJECT WITH UNIQUE KEYS)
        OR convert_from(public_inventory, 'UTF8')::JSONB IS DISTINCT FROM expected_public
        OR public_digest IS DISTINCT FROM convert_to('sha256:' || encode(sha256(public_inventory), 'hex'), 'UTF8') THEN
        RAISE EXCEPTION 'World terminal inventory differs from complete retained public projection' USING ERRCODE = '23514'; END IF;
    RETURN account_binding_bytes IS NOT DISTINCT FROM retained_account_binding;
END;
$$;
-- [jooq ignore stop]
