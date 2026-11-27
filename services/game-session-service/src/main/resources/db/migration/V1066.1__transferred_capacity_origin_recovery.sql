-- Retain the issuing fence on each counted permit. Earlier NULL issuing fences are never inferred.
ALTER TABLE game_session_original_new_capacity_origin
    ADD COLUMN last_observation_custody_fence BIGINT,
    ADD CONSTRAINT gs_origin_observation_custody_fence CHECK (
        last_observation_custody_fence IS NULL OR (
            last_observation_custody_fence > 0 AND last_observation_custody_fence <= custody_owner_fence
            AND recovery_observations > 0 AND last_observation_deadline IS NOT NULL));

-- [jooq ignore start]
CREATE FUNCTION guard_gs_origin_observation_issuing_fence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.last_observation_custody_fence IS NOT NULL THEN
            RAISE EXCEPTION 'An origin observation fence must be issued with its counted permit' USING ERRCODE='23514';
        END IF;
    ELSIF (NEW.recovery_observations=OLD.recovery_observations
            AND NEW.last_observation_custody_fence IS DISTINCT FROM OLD.last_observation_custody_fence)
        OR (NEW.recovery_observations>OLD.recovery_observations
            AND NEW.last_observation_custody_fence IS DISTINCT FROM NEW.custody_owner_fence) THEN
        RAISE EXCEPTION 'An origin observation fence must bind its counted permit to current custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_origin_observation_issuing_fence BEFORE INSERT OR UPDATE
    ON game_session_original_new_capacity_origin FOR EACH ROW
    EXECUTE FUNCTION guard_gs_origin_observation_issuing_fence();

-- Closure-only construction retains the original execution identity and requires current custody.
CREATE OR REPLACE FUNCTION fence_gs_capacity_dispatch_during_transfer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE scope RECORD; current_owner RECORD;
BEGIN
    SELECT DISTINCT tenant_id,game_instance_id,region_id INTO STRICT scope
        FROM game_session_canonical_binding_region_bridge_obligation WHERE transition_id=NEW.transition_id;
    SELECT r.owner_instance_id,r.executor_fence,r.owner_service,r.paused,r.region_epoch,r.lease_token_correlation INTO current_owner
        FROM game_session_region_runtime_status r
        WHERE r.tenant_id=scope.tenant_id AND r.game_instance_id=scope.game_instance_id AND r.region_id=scope.region_id
        FOR UPDATE;
    IF NOT FOUND OR scope.tenant_id IS DISTINCT FROM NEW.tenant_id
        OR current_owner.owner_service IS DISTINCT FROM 'game-session-service'
        OR current_owner.paused IS DISTINCT FROM false
        OR (NEW.status<>'CLOSURE_CLAIMED' AND (
            current_owner.owner_instance_id IS DISTINCT FROM NEW.selected_owner_id
            OR current_owner.executor_fence IS DISTINCT FROM NEW.selected_owner_fence)) THEN
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
        AND t.old_owner_id=current_owner.owner_instance_id AND t.old_owner_fence=current_owner.executor_fence) THEN
        RAISE EXCEPTION 'Completed region predecessor cannot acquire dispatch' USING ERRCODE='23514';
    END IF;
    IF NEW.status='CLOSURE_CLAIMED' AND NOT EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_origin o
        WHERE o.admission_attempt_id=NEW.admission_attempt_id AND o.tenant_id=NEW.tenant_id
            AND o.request_id=NEW.request_id AND o.admission_id=NEW.admission_id
            AND o.transition_id=NEW.transition_id AND o.capacity_rearm_fence=NEW.capacity_rearm_fence
            AND o.mutation_digest=NEW.mutation_digest AND o.tenant_gameplay_tag=NEW.tenant_gameplay_tag
            AND o.game_instance_id=scope.game_instance_id AND o.region_id=scope.region_id
            AND o.selected_owner_id=NEW.selected_owner_id AND o.selected_owner_fence=NEW.selected_owner_fence
            AND o.custody_owner_id=current_owner.owner_instance_id AND o.custody_owner_fence=current_owner.executor_fence
            AND o.state='CLOSURE_CLAIMED' AND o.account_resolution_bytes IS NOT NULL
            AND ((o.custody_transfer_id IS NULL AND o.custody_owner_id=o.selected_owner_id
                    AND o.custody_owner_fence=o.selected_owner_fence)
                OR EXISTS (
                    SELECT 1 FROM game_session_region_owner_transfer t
                    JOIN game_session_capacity_origin_transfer_item i ON i.transfer_id=t.transfer_id
                    WHERE t.transfer_id=o.custody_transfer_id AND t.state='COMPLETED'
                        AND t.tenant_id=o.tenant_id AND t.game_instance_id=o.game_instance_id AND t.region_id=o.region_id
                        AND t.region_epoch=current_owner.region_epoch
                        AND t.new_owner_id=o.custody_owner_id AND t.new_owner_fence=o.custody_owner_fence
                        AND t.new_lease_correlation=current_owner.lease_token_correlation
                        AND i.origin_digest=o.origin_digest AND i.canonical_origin=o.canonical_origin
                        AND i.old_owner_id=t.old_owner_id AND i.old_owner_fence=t.old_owner_fence
                        AND i.captured_state='OPEN' AND i.fence_readback IS NOT NULL
                        AND i.incarnation_digest=t.incarnation_digest AND i.verified_round=t.origin_verification_round))
    ) THEN
        RAISE EXCEPTION 'Closure requires the exact current origin custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1066'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1066'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1066.1__transferred_capacity_origin_recovery.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1066.1'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1066'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1066.1''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1066','V1066__original_new_admitted_logout_intent.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1066.1'',''V1066.1__transferred_capacity_origin_recovery.sql''';
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
