-- Preserve the original Account inventory/v1 profile and admit only the exact
-- closure-qualified inventory/v2 profile. Retained operation bytes stay immutable.
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
    inventory_profile_anchor TEXT := $anchor$        OR inventory->>'schema' IS DISTINCT FROM 'world-selected-publication-artifact-inventory/v1'
        OR inventory->'schemaVersion' IS DISTINCT FROM '1'::JSONB OR inventory->>'completeness' IS DISTINCT FROM 'COMPLETE'$anchor$;
    inventory_profile_replacement TEXT := $replacement$        OR NOT ((inventory->>'schema' = 'world-selected-publication-artifact-inventory/v1'
                AND inventory->'schemaVersion' = '1'::JSONB)
            OR (inventory->>'schema' = 'world-selected-publication-artifact-inventory/v2'
                AND inventory->'schemaVersion' = '2'::JSONB))
        OR inventory->>'completeness' IS DISTINCT FROM 'COMPLETE'$replacement$;
    model_count_anchor TEXT := $anchor$        OR (SELECT count(*) FROM jsonb_object_keys(model)) <> 16$anchor$;
    model_count_replacement TEXT := $replacement$        OR (SELECT count(*) FROM jsonb_object_keys(model)) <> (CASE
            WHEN inventory->>'schema' = 'world-selected-publication-artifact-inventory/v2'
                AND inventory->'schemaVersion' = '2'::JSONB THEN 17
            ELSE 16
        END)$replacement$;
    profile_anchor TEXT := $anchor$        OR model->>'modelId' IS DISTINCT FROM 'WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1'
        OR model->'graphSchemaVersion' IS DISTINCT FROM '2'::JSONB$anchor$;
    profile_replacement TEXT := $replacement$        OR ((inventory->>'schema' = 'world-selected-publication-artifact-inventory/v1'
                AND inventory->'schemaVersion' = '1'::JSONB
                AND model->>'modelId' = 'WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1'
                AND model->'graphSchemaVersion' = '2'::JSONB
                AND checkpoint->'digestSchemaVersion' = '3'::JSONB
                AND selector->'request'->'digestSchemaVersion' = '3'::JSONB
                AND NOT (model ? 'inboundSourceClosure'))
            OR (inventory->>'schema' = 'world-selected-publication-artifact-inventory/v2'
                AND inventory->'schemaVersion' = '2'::JSONB
                AND model->>'modelId' = 'WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_INBOUND_EMPTY_V1'
                AND model->'graphSchemaVersion' = '3'::JSONB
                AND checkpoint->'digestSchemaVersion' = '4'::JSONB
                AND selector->'request'->'digestSchemaVersion' = '4'::JSONB
                AND model ?& ARRAY['modelId', 'graphSchemaVersion', 'graphDigest',
                    'topologyResultDigest', 'familyCounts', 'regionGeneratorInputs',
                    'generationRuleFields', 'regionFields', 'zoneFields', 'roomFields',
                    'roomExitFields', 'spawnBindingFields', 'spawnBindingInputs',
                    'generationRuleInputCount', 'spawnBindingCount', 'appliedEpochs',
                    'inboundSourceClosure']
                AND model->'inboundSourceClosure' IS NOT DISTINCT FROM
                    '{"schemaVersion":1,"familyCounts":[{"family":"WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE","count":0},{"family":"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING","count":0}]}'::JSONB)) IS NOT TRUE$replacement$;
BEGIN
    SELECT pg_get_functiondef(inventory_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, model_shape_anchor, '')))
            / length(model_shape_anchor) <> 1
        OR (length(original_definition) - length(replace(original_definition, inventory_profile_anchor, '')))
            / length(inventory_profile_anchor) <> 1
        OR (length(original_definition) - length(replace(original_definition, model_count_anchor, '')))
            / length(model_count_anchor) <> 1
        OR (length(original_definition) - length(replace(original_definition, profile_anchor, '')))
            / length(profile_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V162 Account selected inventory profile guards';
    END IF;
    original_definition := replace(original_definition, model_shape_anchor, model_shape_replacement);
    original_definition := replace(original_definition, inventory_profile_anchor, inventory_profile_replacement);
    original_definition := replace(original_definition, model_count_anchor, model_count_replacement);
    original_definition := replace(original_definition, profile_anchor, profile_replacement);
    IF (length(original_definition) - length(replace(original_definition, model_shape_replacement, '')))
            / length(model_shape_replacement) <> 1
        OR (length(original_definition) - length(replace(original_definition, inventory_profile_replacement, '')))
            / length(inventory_profile_replacement) <> 1
        OR (length(original_definition) - length(replace(original_definition, model_count_replacement, '')))
            / length(model_count_replacement) <> 1
        OR (length(original_definition) - length(replace(original_definition, profile_replacement, '')))
            / length(profile_replacement) <> 1 THEN
        RAISE EXCEPTION 'Expected exact V163 Account selected inventory profile guards';
    END IF;
    EXECUTE original_definition;
END;
$migration$;
-- [jooq ignore stop]
