-- [jooq ignore start]
CREATE FUNCTION require_gs_frozen_region_owner_transfer_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state IS DISTINCT FROM 'FROZEN'
        OR NEW.incarnation_digest IS NOT NULL OR NEW.verification_round IS NOT NULL
        OR NEW.verification_cursor IS DISTINCT FROM -1
        OR NEW.verification_complete IS DISTINCT FROM false
        OR NEW.new_owner_id IS NOT NULL OR NEW.new_owner_fence IS NOT NULL
        OR NEW.new_lease_correlation IS NOT NULL THEN
        RAISE EXCEPTION 'Region transfer must start as a clean frozen intent' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_region_owner_transfer_frozen_insert BEFORE INSERT ON game_session_region_owner_transfer
    FOR EACH ROW EXECUTE FUNCTION require_gs_frozen_region_owner_transfer_insert();

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1064'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1064'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.2__require_frozen_region_transfer_insert.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.2'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1064'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.2''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1064','V1064__restore_final_human_completion_lower_bound.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.2'',''V1065.0.2__require_frozen_region_transfer_insert.sql''';
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
