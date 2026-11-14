-- Keep receipt recovery bound to the storage incarnation that actually received dispatch.
-- Validate existing tuples; never rewrite immutable dispatch/receipt history.
-- [jooq ignore start]
ALTER TABLE game_session_region_owner_transfer_item
    ADD CONSTRAINT gs_region_fence_receipt_dispatch_incarnation CHECK (
        fence_dispatch_id IS NULL OR fence_readback IS NULL
        OR incarnation_digest = fence_dispatch_incarnation);

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.10'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.10'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.11__bind_fence_receipt_dispatch_incarnation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.11'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.10'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.11''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.10','V1065.0.10__bound_capacity_closure_receipt_lookup.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.11'',''V1065.0.11__bind_fence_receipt_dispatch_incarnation.sql''';
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
