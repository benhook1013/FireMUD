-- Preserve the full source-key capacity of Account participation and accept explicit empty
-- selected-inventory input arrays while retaining canonical ordering checks for nonempty arrays.
ALTER TABLE account_selected_publication_sources
    ALTER COLUMN source_key TYPE VARCHAR(2048);

-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    region_anchor_count INTEGER;
    region_replacement_count INTEGER;
    spawn_anchor_count INTEGER;
    spawn_replacement_count INTEGER;
    region_anchor TEXT := $anchor$        OR (SELECT jsonb_agg(entry ORDER BY entry->>'regionTemplateId') FROM
            jsonb_array_elements(model->'regionGeneratorInputs') entry)
            IS DISTINCT FROM model->'regionGeneratorInputs'$anchor$;
    region_replacement TEXT := $replacement$        OR (SELECT coalesce(jsonb_agg(entry ORDER BY entry->>'regionTemplateId'), '[]'::JSONB) FROM
            jsonb_array_elements(model->'regionGeneratorInputs') entry)
            IS DISTINCT FROM model->'regionGeneratorInputs'$replacement$;
    spawn_anchor TEXT := $anchor$        OR (SELECT jsonb_agg(entry ORDER BY entry->>'bindingTemplateId') FROM
            jsonb_array_elements(model->'spawnBindingInputs') entry)
            IS DISTINCT FROM model->'spawnBindingInputs'$anchor$;
    spawn_replacement TEXT := $replacement$        OR (SELECT coalesce(jsonb_agg(entry ORDER BY entry->>'bindingTemplateId'), '[]'::JSONB) FROM
            jsonb_array_elements(model->'spawnBindingInputs') entry)
            IS DISTINCT FROM model->'spawnBindingInputs'$replacement$;
BEGIN
    SELECT pg_get_functiondef('require_selected_inventory_operation_v2(BYTEA)'::REGPROCEDURE)
        INTO STRICT original;
    region_anchor_count :=
        (length(original) - length(replace(original, region_anchor, ''))) / length(region_anchor);
    region_replacement_count :=
        (length(original) - length(replace(original, region_replacement, ''))) / length(region_replacement);
    spawn_anchor_count :=
        (length(original) - length(replace(original, spawn_anchor, ''))) / length(spawn_anchor);
    spawn_replacement_count :=
        (length(original) - length(replace(original, spawn_replacement, ''))) / length(spawn_replacement);
    IF region_anchor_count + region_replacement_count <> 1
        OR spawn_anchor_count + spawn_replacement_count <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V108 ordering guard for each selected inventory input array';
    END IF;
    IF region_anchor_count = 1 THEN
        original := replace(original, region_anchor, region_replacement);
    END IF;
    IF spawn_anchor_count = 1 THEN
        original := replace(original, spawn_anchor, spawn_replacement);
    END IF;
    IF (length(original) - length(replace(original, region_replacement, ''))) / length(region_replacement) <> 1
        OR (length(original) - length(replace(original, region_anchor, ''))) / length(region_anchor) <> 0
        OR (length(original) - length(replace(original, spawn_replacement, ''))) / length(spawn_replacement) <> 1
        OR (length(original) - length(replace(original, spawn_anchor, ''))) / length(spawn_anchor) <> 0 THEN
        RAISE EXCEPTION 'Expected exactly one canonical ordering guard for each selected inventory input array';
    END IF;
    EXECUTE original;
END;
$migration$;
-- [jooq ignore stop]
