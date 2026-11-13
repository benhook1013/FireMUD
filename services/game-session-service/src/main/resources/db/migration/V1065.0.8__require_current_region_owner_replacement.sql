-- [jooq ignore start]
CREATE OR REPLACE FUNCTION require_gs_region_owner_transfer_custody() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.executor_fence,NEW.owner_instance_id,NEW.lease_token_correlation)
       IS NOT DISTINCT FROM ROW(OLD.executor_fence,OLD.owner_instance_id,OLD.lease_token_correlation) THEN RETURN NEW; END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Region owner replacement requires READ COMMITTED owner fencing' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM game_session_region_owner_transfer t WHERE t.tenant_id=OLD.tenant_id
        AND t.game_instance_id=OLD.game_instance_id AND t.region_id=OLD.region_id AND t.state='FROZEN') THEN
        RAISE EXCEPTION 'Region transfer must complete with owner installation' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM game_session_original_new_capacity_execution e
        JOIN game_session_canonical_binding_region_bridge_obligation b ON b.transition_id=e.transition_id
        WHERE b.tenant_id=OLD.tenant_id AND b.game_instance_id=OLD.game_instance_id AND b.region_id=OLD.region_id
          AND e.status IN ('INSTALL_DISPATCHED','AUTHORIZED')) AND NOT EXISTS (
        SELECT 1 FROM game_session_region_owner_transfer t WHERE t.tenant_id=OLD.tenant_id
          AND t.game_instance_id=OLD.game_instance_id AND t.region_id=OLD.region_id
          AND t.region_epoch=OLD.region_epoch AND t.old_owner_id=OLD.owner_instance_id AND t.old_owner_fence=OLD.executor_fence
          AND t.old_lease_correlation=OLD.lease_token_correlation AND t.state='COMPLETED'
          AND t.new_owner_id=NEW.owner_instance_id AND t.new_owner_fence=NEW.executor_fence
          AND t.new_lease_correlation=NEW.lease_token_correlation AND t.verification_complete) THEN
        RAISE EXCEPTION 'Region owner replacement lacks exact transfer custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.7'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.7'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.8__require_current_region_owner_replacement.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.8'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.7'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.8''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.7','V1065.0.7__bound_region_transfer_history.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.8'',''V1065.0.8__require_current_region_owner_replacement.sql''';
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
