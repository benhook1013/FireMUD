-- Preserve the V56 public inventory projection while making JSONB extraction bind before `-`.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    projection_anchor TEXT := $anchor$        'ownerScope', private_inventory->'ownerScope' - ARRAY['localTenantKey', 'localVersionKey', 'gameDesignVersionId'],$anchor$;
    corrected_projection TEXT := $replacement$        'ownerScope', (private_inventory->'ownerScope') - ARRAY['localTenantKey', 'localVersionKey', 'gameDesignVersionId'],$replacement$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_require_publication_operation_account_binding(BYTEA, UUID, BYTEA)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, projection_anchor, '')))
        / length(projection_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V56 World ownerScope projection expression';
    END IF;
    EXECUTE replace(original, projection_anchor, corrected_projection);
END;
$migration$;
-- [jooq ignore stop]
