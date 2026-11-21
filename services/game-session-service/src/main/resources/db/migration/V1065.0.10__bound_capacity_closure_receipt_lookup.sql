-- [jooq ignore start]
CREATE OR REPLACE FUNCTION reject_gs_capacity_close_after_final_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='AUTHORIZED' AND NEW.status='CLOSED' AND EXISTS (
        SELECT 1 FROM game_session_canonical_binding_transition t
        WHERE t.transition_id=OLD.transition_id
          AND t.original_new_final_session_sha256 IS NOT NULL
          AND t.original_new_capacity_execution_digest=OLD.execution_digest) THEN
        RAISE EXCEPTION 'Original NEW execution cannot close after retained final receipt' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.9'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.9'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.10__bound_capacity_closure_receipt_lookup.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.10'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.9'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.10''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.9','V1065.0.9__retain_region_fence_dispatch_custody.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.10'',''V1065.0.10__bound_capacity_closure_receipt_lookup.sql''';
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
