CREATE INDEX idx_gameplay_admission_pointer_event_tenant_world_realm_id
    ON gameplay_admission_pointer_event USING btree (tenant_id, world_slug, realm_slug, id DESC);

-- [jooq ignore start]
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure)
        INTO STRICT definition;
    original := $needle$WHERE version='1074'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1074'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1074.1__index_gameplay_admission_pointer_event_tenant_lookup.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1074.1'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure)
        INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1074'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1074.1''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1074','V1074__run_owned_fixture_completion_version_floor.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1074.1'',''V1074.1__index_gameplay_admission_pointer_event_tenant_lookup.sql''';
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
