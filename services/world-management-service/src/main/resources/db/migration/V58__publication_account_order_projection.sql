-- Preserve the complete Account-order projection while binding JSONB extraction before `-`.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    projection_anchor TEXT := $anchor$        'accountOrder', private_inventory->'accountOrder' - ARRAY['canonicalBindingBytes'],$anchor$;
    corrected_projection TEXT := $replacement$        'accountOrder', (private_inventory->'accountOrder') - ARRAY['canonicalBindingBytes'],$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_require_publication_operation_account_binding(BYTEA, UUID, BYTEA)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, projection_anchor, '')))
        / length(projection_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V56 World accountOrder projection expression';
    END IF;
    EXECUTE replace(original, projection_anchor, corrected_projection);
END;
$migration$;
-- [jooq ignore stop]
