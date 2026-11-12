-- [jooq ignore start]
CREATE FUNCTION require_gs_initial_capacity_execution_dispatch() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- The status constraint and any separately guarded custody states determine the legal
    -- initial markers. Positive authorization and terminal proof require a later transition.
    IF NEW.status IN ('AUTHORIZED','COMMITTED','CLOSED') OR NEW.terminal_readback IS NOT NULL THEN
        RAISE EXCEPTION 'Original NEW capacity execution cannot start with an authorization or terminal proof' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_capacity_execution_initial_dispatch BEFORE INSERT ON game_session_original_new_capacity_execution
    FOR EACH ROW EXECUTE FUNCTION require_gs_initial_capacity_execution_dispatch();

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1064'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1064'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.3__require_initial_capacity_execution_dispatch.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.3'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1064'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.3''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1064','V1064__restore_final_human_completion_lower_bound.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.3'',''V1065.0.3__require_initial_capacity_execution_dispatch.sql''';
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
