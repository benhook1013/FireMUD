-- [jooq ignore start]
CREATE OR REPLACE FUNCTION require_gs_region_owner_transfer_custody() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.executor_fence,NEW.owner_instance_id,NEW.lease_token_correlation,NEW.owner_service)
       IS NOT DISTINCT FROM ROW(OLD.executor_fence,OLD.owner_instance_id,OLD.lease_token_correlation,OLD.owner_service) THEN RETURN NEW; END IF;
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
          AND t.new_lease_correlation=NEW.lease_token_correlation AND t.verification_complete
          AND OLD.owner_service='game-session-service' AND NEW.owner_service='game-session-service') THEN
        RAISE EXCEPTION 'Region owner replacement lacks exact transfer custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

-- The owner writes completion first. Validate its exact installation at transaction end.
CREATE FUNCTION require_gs_region_transfer_successor() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM game_session_region_runtime_status r
        WHERE r.tenant_id=NEW.tenant_id AND r.game_instance_id=NEW.game_instance_id AND r.region_id=NEW.region_id
          AND r.region_epoch=NEW.region_epoch AND r.owner_service='game-session-service'
          AND r.owner_instance_id=NEW.new_owner_id AND r.executor_fence=NEW.new_owner_fence
          AND r.lease_token_correlation=NEW.new_lease_correlation
    ) THEN
        RAISE EXCEPTION 'Region transfer completion requires its exact installed successor' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER gs_region_transfer_exact_successor
    AFTER UPDATE ON game_session_region_owner_transfer
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.state='FROZEN' AND NEW.state='COMPLETED')
    EXECUTE FUNCTION require_gs_region_transfer_successor();

-- Existing dispatch and receipt tuples keep their historical recovery rules.
CREATE OR REPLACE FUNCTION preserve_gs_region_fence_dispatch() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent game_session_region_owner_transfer%ROWTYPE;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.fence_dispatch_id IS NOT NULL THEN
            RAISE EXCEPTION 'Region fence dispatch requires a retained item' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.fence_dispatch_id,NEW.fence_dispatch_incarnation)
       IS NOT DISTINCT FROM ROW(OLD.fence_dispatch_id,OLD.fence_dispatch_incarnation) THEN
        RETURN NEW;
    END IF;
    IF OLD.fence_dispatch_id IS NOT NULL OR OLD.fence_readback IS NOT NULL
       OR NEW.fence_dispatch_id IS NULL OR NEW.fence_readback IS NOT NULL
       OR current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Region fence dispatch custody is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT parent FROM game_session_region_owner_transfer
        WHERE transfer_id=NEW.transfer_id FOR UPDATE;
    IF parent.state <> 'FROZEN'
       OR parent.incarnation_digest IS NULL
       OR parent.incarnation_digest IS DISTINCT FROM NEW.fence_dispatch_incarnation
       OR EXISTS (SELECT 1 FROM game_session_region_owner_transfer_item i
           WHERE i.execution_digest=NEW.execution_digest AND i.fence_readback IS NOT NULL) THEN
        RAISE EXCEPTION 'Region fence dispatch requires an unfenced frozen original with its exact bound incarnation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

-- [jooq ignore stop]

-- Runtime-family catalog registration
-- [jooq ignore start]
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.14'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.14'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.15__require_exact_region_transfer_successor.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.15'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.14'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.15''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.14','V1065.0.14__seal_transfer_dispatch_preparation.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.15'',''V1065.0.15__require_exact_region_transfer_successor.sql''';
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
