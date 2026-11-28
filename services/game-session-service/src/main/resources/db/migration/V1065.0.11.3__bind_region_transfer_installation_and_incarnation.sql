-- [jooq ignore start]
-- Completion and successor installation are one transaction, with completion written first.
CREATE FUNCTION require_gs_region_transfer_successor_installation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM game_session_region_runtime_status s
    WHERE s.tenant_id=NEW.tenant_id AND s.game_instance_id=NEW.game_instance_id
      AND s.region_id=NEW.region_id AND s.region_epoch=NEW.region_epoch
      AND s.owner_instance_id=NEW.new_owner_id AND s.executor_fence=NEW.new_owner_fence
      AND s.lease_token_correlation=NEW.new_lease_correlation
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Region transfer completion lacks its exact installed successor' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER gs_region_transfer_successor_installation
    AFTER UPDATE ON game_session_region_owner_transfer
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (OLD.state='FROZEN' AND NEW.state='COMPLETED')
    EXECUTE FUNCTION require_gs_region_transfer_successor_installation();

-- Keep the retained-data check and the new guards on one stable write boundary.
LOCK TABLE game_session_region_owner_transfer, game_session_region_owner_transfer_item
    IN SHARE ROW EXCLUSIVE MODE;

-- An older, unpinned transfer can retain one exact dispatch incarnation. Refuse
-- contradictory retained evidence without rewriting the transfer or any dispatch.
DO $retained$
BEGIN
    IF EXISTS (
        SELECT 1 FROM game_session_region_owner_transfer t
        JOIN game_session_region_owner_transfer_item i ON i.transfer_id=t.transfer_id
        WHERE i.fence_dispatch_id IS NOT NULL
        GROUP BY t.transfer_id,t.incarnation_digest
        HAVING count(DISTINCT i.fence_dispatch_incarnation)>1
            OR (t.incarnation_digest IS NOT NULL
                AND min(i.fence_dispatch_incarnation) IS DISTINCT FROM t.incarnation_digest)
    ) THEN
        RAISE EXCEPTION 'Retained region fence dispatches disagree on storage incarnation' USING ERRCODE='23514';
    END IF;
END;
$retained$;

CREATE FUNCTION require_gs_region_transfer_dispatch_incarnation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM game_session_region_owner_transfer_item i
        WHERE i.transfer_id=NEW.transfer_id AND i.fence_dispatch_id IS NOT NULL
          AND i.fence_dispatch_incarnation IS DISTINCT FROM NEW.incarnation_digest
    ) THEN
        RAISE EXCEPTION 'Region transfer incarnation disagrees with retained fence dispatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gs_region_transfer_dispatch_incarnation
    BEFORE UPDATE OF incarnation_digest ON game_session_region_owner_transfer
    FOR EACH ROW WHEN (OLD.incarnation_digest IS DISTINCT FROM NEW.incarnation_digest)
    EXECUTE FUNCTION require_gs_region_transfer_dispatch_incarnation();

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
       OR NEW.fence_dispatch_id IS NULL OR NEW.fence_dispatch_incarnation IS NULL
       OR NEW.fence_readback IS NOT NULL
       OR current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Region fence dispatch custody is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT parent FROM game_session_region_owner_transfer
        WHERE transfer_id=NEW.transfer_id FOR UPDATE;
    IF parent.state <> 'FROZEN'
       OR EXISTS (SELECT 1 FROM game_session_region_owner_transfer_item i
           WHERE i.execution_digest=NEW.execution_digest AND i.fence_readback IS NOT NULL) THEN
        RAISE EXCEPTION 'Region fence dispatch requires an unfenced frozen original' USING ERRCODE='23514';
    END IF;
    IF parent.incarnation_digest IS NULL THEN
        UPDATE game_session_region_owner_transfer SET incarnation_digest=NEW.fence_dispatch_incarnation
        WHERE transfer_id=parent.transfer_id;
    ELSIF parent.incarnation_digest IS DISTINCT FROM NEW.fence_dispatch_incarnation THEN
        RAISE EXCEPTION 'Region transfer incarnation disagrees with retained fence dispatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.11.2'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.11.2'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.11.3__bind_region_transfer_installation_and_incarnation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.11.3'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.11.2'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.11.3''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.11.2','V1065.0.11.2__require_exact_capacity_commit_receipt.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.11.3'',''V1065.0.11.3__bind_region_transfer_installation_and_incarnation.sql''';
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
