-- [jooq ignore start]
CREATE OR REPLACE FUNCTION fence_gs_capacity_dispatch_during_transfer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE scope RECORD; current_owner RECORD;
BEGIN
    SELECT DISTINCT tenant_id,game_instance_id,region_id INTO STRICT scope
        FROM game_session_canonical_binding_region_bridge_obligation WHERE transition_id=NEW.transition_id;
    SELECT r.owner_instance_id,r.executor_fence,r.owner_service,r.paused INTO current_owner
        FROM game_session_region_runtime_status r
        WHERE r.tenant_id=scope.tenant_id AND r.game_instance_id=scope.game_instance_id AND r.region_id=scope.region_id
        FOR UPDATE;
    IF NOT FOUND OR scope.tenant_id IS DISTINCT FROM NEW.tenant_id
        OR current_owner.owner_service IS DISTINCT FROM 'game-session-service'
        OR current_owner.paused IS DISTINCT FROM false
        OR current_owner.owner_instance_id IS DISTINCT FROM NEW.selected_owner_id
        OR current_owner.executor_fence IS DISTINCT FROM NEW.selected_owner_fence THEN
        RAISE EXCEPTION 'Region dispatch requires the exact current selected owner' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM game_session_region_owner_transfer t WHERE t.tenant_id=scope.tenant_id
        AND t.game_instance_id=scope.game_instance_id AND t.region_id=scope.region_id AND t.state='FROZEN') THEN
        RAISE EXCEPTION 'Region dispatch is frozen for normal owner transfer' USING ERRCODE='23514';
    END IF;
    -- A single owner transaction records completion before changing the runtime row. The
    -- completed predecessor cannot acquire a dispatch in between those two durable writes.
    IF EXISTS (SELECT 1 FROM game_session_region_owner_transfer t WHERE t.tenant_id=scope.tenant_id
        AND t.game_instance_id=scope.game_instance_id AND t.region_id=scope.region_id AND t.state='COMPLETED'
        AND t.old_owner_id=NEW.selected_owner_id AND t.old_owner_fence=NEW.selected_owner_fence) THEN
        RAISE EXCEPTION 'Completed region predecessor cannot acquire dispatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    -- Insert before the existing final clause so later origin/receipt migrations retain their
    -- exact forward anchors at the last current catalog frontier.
    original := $needle$WHERE version='1064'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1064'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.1__require_current_capacity_dispatch_owner.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.1'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1064'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.1''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1064','V1064__restore_final_human_completion_lower_bound.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.1'',''V1065.0.1__require_current_capacity_dispatch_owner.sql''';
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
