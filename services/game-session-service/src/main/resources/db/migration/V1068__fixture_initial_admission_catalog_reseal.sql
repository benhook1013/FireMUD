-- Seal the existing fixture-purpose schema without changing any owner or admission guard.
-- Original catalog epochs and snapshots remain immutable. Both migration facts are required;
-- stable installation markers avoid hashing this migration's not-yet-inserted Flyway row.
-- [jooq ignore start]
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1066.1'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1066.1'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1067__canonical_fixture_initial_admission.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1067'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1068__fixture_initial_admission_catalog_reseal.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1068'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1066.1'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1067''\n            AND version IS DISTINCT FROM ''1068''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1066.1','V1066.1__transferred_capacity_origin_recovery.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1067'',''V1067__canonical_fixture_initial_admission.sql''\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1068'',''V1068__fixture_initial_admission_catalog_reseal.sql''';
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
