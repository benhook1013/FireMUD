-- Preserve the full source-key capacity of Account participation and accept explicit empty
-- selected-inventory input arrays while retaining canonical ordering checks for nonempty arrays.
ALTER TABLE account_selected_publication_sources
    ALTER COLUMN source_key TYPE VARCHAR(2048);

-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
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
    IF (length(original) - length(replace(original, region_anchor, ''))) / length(region_anchor) <> 1
        OR (length(original) - length(replace(original, spawn_anchor, ''))) / length(spawn_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V108 ordering guard for each selected inventory input array';
    END IF;
    original := replace(original, region_anchor, region_replacement);
    original := replace(original, spawn_anchor, spawn_replacement);
    EXECUTE original;
END;
$migration$;
-- [jooq ignore stop]
