-- Preserve the original v1 inventory profile and admit only the explicit closure-qualified v2
-- profile. Publication-operation bytes and historical migration definitions remain immutable.
-- [jooq ignore start]
DO $migration$
DECLARE
    inventory_function REGPROCEDURE :=
        '"${serviceSchema}".require_selected_inventory_operation_v2(bytea)'::REGPROCEDURE;
    original_definition TEXT;
    model_shape_anchor TEXT := $anchor$        (model, ARRAY['modelId','graphSchemaVersion','graphDigest','topologyResultDigest','familyCounts','regionGeneratorInputs','generationRuleFields','regionFields','zoneFields','roomFields','roomExitFields','spawnBindingFields','spawnBindingInputs','generationRuleInputCount','spawnBindingCount','appliedEpochs'])$anchor$;
    model_shape_replacement TEXT := $replacement$        (model, CASE
            WHEN inventory->>'schema' = 'world-selected-publication-artifact-inventory/v2'
                AND inventory->'schemaVersion' = '2'::JSONB
            THEN ARRAY['modelId','graphSchemaVersion','graphDigest','topologyResultDigest','familyCounts','regionGeneratorInputs','generationRuleFields','regionFields','zoneFields','roomFields','roomExitFields','spawnBindingFields','spawnBindingInputs','generationRuleInputCount','spawnBindingCount','appliedEpochs','inboundSourceClosure']
            ELSE ARRAY['modelId','graphSchemaVersion','graphDigest','topologyResultDigest','familyCounts','regionGeneratorInputs','generationRuleFields','regionFields','zoneFields','roomFields','roomExitFields','spawnBindingFields','spawnBindingInputs','generationRuleInputCount','spawnBindingCount','appliedEpochs']
        END)$replacement$;
    profile_anchor TEXT := $anchor$    IF inventory->>'schema' IS DISTINCT FROM 'world-selected-publication-artifact-inventory/v1'
        OR inventory->'schemaVersion' IS DISTINCT FROM '1'::JSONB OR inventory->>'completeness' IS DISTINCT FROM 'COMPLETE'$anchor$;
    profile_replacement TEXT := $replacement$    IF ((
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
BEGIN
    SELECT pg_get_functiondef(inventory_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, model_shape_anchor, '')))
            / length(model_shape_anchor) <> 1
        OR (length(original_definition) - length(replace(original_definition, profile_anchor, '')))
            / length(profile_anchor) <> 1
        OR (length(original_definition) - length(replace(original_definition, legacy_model_anchor, '')))
            / length(legacy_model_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V56 selected inventory v1 profile guards';
    END IF;
    original_definition := replace(original_definition, model_shape_anchor, model_shape_replacement);
    original_definition := replace(original_definition, profile_anchor, profile_replacement);
    -- The paired profile guard above checks both model identifiers and graph schemas. Keep the
    -- generation-rule and artifact-decision restrictions below unchanged for both profiles.
    original_definition := replace(original_definition, legacy_model_anchor, '');
    EXECUTE original_definition;
END;
$migration$;
-- [jooq ignore stop]
