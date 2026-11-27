-- Reserve terminal evidence space inside each fixed 1 MiB capacity evidence charge.
-- The current length-prefixed ASCII terminal codec fits within 512 bytes.
-- Validate retained rows without rewriting any evidence.
ALTER TABLE game_session_original_new_capacity_execution
    ADD CONSTRAINT gs_capacity_execution_record_bytes
        CHECK (octet_length(canonical_execution) <= 1048064),
    ADD CONSTRAINT gs_capacity_terminal_record_bytes
        CHECK (octet_length(terminal_readback) <= 512);

-- [jooq ignore start]
-- Preserve the V1065.0.9 anchors used by the following catalog migrations.
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.9'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.9'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.9.1__bound_original_new_capacity_record_bytes.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.9.1'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.9'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.9.1''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.9','V1065.0.9__retain_region_fence_dispatch_custody.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.9.1'',''V1065.0.9.1__bound_original_new_capacity_record_bytes.sql''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog marker'; END IF;
    EXECUTE replace(definition,original,replacement);
END;
$migration$;

INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT epoch,bytes,'sha256:'||encode(sha256(bytes),'hex') FROM (
    SELECT epoch,gs_runtime_family_catalog_bytes(epoch) AS bytes FROM (
        SELECT coalesce(max(catalog_epoch),0)+1 AS epoch FROM game_session_runtime_family_catalog_epoch
    ) next_epoch
) seal;
-- [jooq ignore stop]
