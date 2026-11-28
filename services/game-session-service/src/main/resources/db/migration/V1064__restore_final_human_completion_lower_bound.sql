-- Restore the immutable Human completion row version as a lower bound while preserving
-- the complete current RUNNING launch, candidate, capacity, and identity guards.
-- [jooq ignore start]
DO $migration$
DECLARE
    definition text;
    original text := 'completion.completed_running_row_version = runtime.row_version';
    replacement text := 'completion.completed_running_row_version <= runtime.row_version';
BEGIN
    SELECT pg_get_functiondef(
        (quote_ident(current_schema()) ||
            '.require_running_runtime_for_lease_bound_binding_decision()')::regprocedure)
        INTO STRICT definition;
    IF (length(definition) - length(replace(definition, original, ''))) / length(original) <> 1 THEN
        RAISE EXCEPTION 'Expected exact final Human runtime completion comparison';
    END IF;
    EXECUTE replace(definition, original, replacement);
END;
$migration$;

CREATE OR REPLACE FUNCTION gs_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
    SELECT (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1057__game_session_owner_runtime_family_catalog.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1057'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1058__game_session_catalog_view_options.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1058'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1059__private_lifecycle_preparation_after_catalog_allocation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1059'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1060__private_catalog_lifecycle_revisions.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1060'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1061__private_fresh_runtime_identity_allocation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1061'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1062__private_preparation_source_protection.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1062'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1063__original_new_tenant_capacity_execution.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1063'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1064__restore_final_human_completion_lower_bound.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1064'
    );
$$;

DO $migration$
DECLARE
    definition text;
    original text;
    replacement text;
BEGIN
    SELECT pg_get_functiondef(
        (quote_ident(current_schema()) ||
            '.gs_runtime_family_catalog_bytes(bigint)')::regprocedure)
        INTO STRICT definition;

    original := $needle$AND version IS DISTINCT FROM '1063'$needle$;
    replacement := original || E'\n            AND version IS DISTINCT FROM ''1064''';
    IF (length(definition) - length(replace(definition, original, ''))) / length(original) <> 1 THEN
        RAISE EXCEPTION 'Expected exact Game Session catalog history exclusion';
    END IF;
    definition := replace(definition, original, replacement);

    original := $needle$SELECT 'MIGRATION','catalog-installation:1063','V1063__original_new_tenant_capacity_execution.sql'$needle$;
    replacement := original || E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1064'',''V1064__restore_final_human_completion_lower_bound.sql''';
    IF (length(definition) - length(replace(definition, original, ''))) / length(original) <> 1 THEN
        RAISE EXCEPTION 'Expected exact Game Session catalog installation marker';
    END IF;
    definition := replace(definition, original, replacement);
    EXECUTE definition;
END;
$migration$;

-- Seal the next monotonic owner epoch after every function definition is installed.
INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (
    SELECT epoch,gs_runtime_family_catalog_bytes(epoch) AS bytes
    FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS epoch
        FROM game_session_runtime_family_catalog_epoch) next_epoch
) seal;
-- [jooq ignore stop]
