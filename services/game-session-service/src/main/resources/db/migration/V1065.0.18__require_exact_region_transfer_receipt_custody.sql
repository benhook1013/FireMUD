-- Preserve retained transfer history while strengthening the custody required for new writes.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION guard_gs_region_owner_transfer_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent game_session_region_owner_transfer%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Region transfer item is retained' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' OR NEW.fence_readback IS NOT NULL THEN
        SELECT * INTO STRICT parent FROM game_session_region_owner_transfer WHERE transfer_id=NEW.transfer_id FOR UPDATE;
    ELSE
        SELECT * INTO STRICT parent FROM game_session_region_owner_transfer WHERE transfer_id=NEW.transfer_id;
    END IF;
    IF parent.state<>'FROZEN' THEN
        RAISE EXCEPTION 'Completed transfer cannot acquire or change custody' USING ERRCODE='23514';
    END IF;
    -- Advance the parent tuple while holding its lock. A concurrent completion with an older
    -- REPEATABLE READ snapshot must fail serialization instead of completing over this child.
    IF TG_OP='INSERT' THEN
        UPDATE game_session_region_owner_transfer SET state=state
         WHERE transfer_id=NEW.transfer_id AND state='FROZEN';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Completed transfer cannot acquire or change custody' USING ERRCODE='23514';
        END IF;
        PERFORM 1 FROM game_session_original_new_capacity_execution e
        JOIN game_session_canonical_binding_region_bridge_obligation b ON b.transition_id=e.transition_id
        JOIN game_session_canonical_binding_transition t ON t.transition_id=e.transition_id
        JOIN game_session_canonical_tenant_gameplay_tag a ON a.tenant_id=e.tenant_id
        WHERE a.tenant_gameplay_tag=NEW.tenant_gameplay_tag AND a.allocation_version=NEW.allocation_version
          AND e.execution_digest=NEW.execution_digest AND e.canonical_execution=NEW.canonical_execution
          AND e.tenant_gameplay_tag=NEW.tenant_gameplay_tag AND t.original_new_candidate_digest=NEW.candidate_digest
          AND b.tenant_id=parent.tenant_id AND b.game_instance_id=parent.game_instance_id AND b.region_id=parent.region_id
          AND e.status IN ('INSTALL_DISPATCHED','AUTHORIZED')
        FOR SHARE OF e;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Transfer item differs from unresolved original region execution or tenant allocation' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='UPDATE' AND (
        ROW(NEW.transfer_id,NEW.target_ordinal,NEW.execution_digest,NEW.canonical_execution,NEW.candidate_digest,
            NEW.tenant_gameplay_tag,NEW.workload_namespace,NEW.allocation_version)
        IS DISTINCT FROM ROW(OLD.transfer_id,OLD.target_ordinal,OLD.execution_digest,OLD.canonical_execution,OLD.candidate_digest,
            OLD.tenant_gameplay_tag,OLD.workload_namespace,OLD.allocation_version)
        OR (OLD.fence_readback IS NOT NULL AND ROW(NEW.fence_readback,NEW.incarnation_digest,NEW.before_payload_digest,NEW.after_payload_digest)
            IS DISTINCT FROM ROW(OLD.fence_readback,OLD.incarnation_digest,OLD.before_payload_digest,OLD.after_payload_digest))
        OR (OLD.verified_round IS NOT NULL AND NEW.verified_round IS DISTINCT FROM OLD.verified_round)) THEN
        RAISE EXCEPTION 'Transfer original and retained fence receipt are immutable' USING ERRCODE='23514';
    END IF;
    -- Enrollment and receipt writes share the frozen parent lock with dispatch issuance
    -- and completion. Only one transfer for the region can be frozen at a time.
    IF NEW.fence_readback IS NOT NULL THEN
        IF parent.incarnation_digest IS NULL
           OR NEW.incarnation_digest IS DISTINCT FROM parent.incarnation_digest THEN
            RAISE EXCEPTION 'Transfer receipt differs from the bound parent incarnation' USING ERRCODE='23514';
        END IF;
        IF EXISTS (
            SELECT 1 FROM game_session_region_owner_transfer_item dispatched
            WHERE dispatched.execution_digest=NEW.execution_digest AND dispatched.fence_dispatch_id IS NOT NULL
              AND dispatched.fence_dispatch_incarnation IS DISTINCT FROM NEW.incarnation_digest
        ) THEN
            RAISE EXCEPTION 'Transfer receipt differs from the retained dispatch incarnation' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.verified_round IS NOT NULL AND NEW.verified_round IS DISTINCT FROM parent.verification_round THEN
        RAISE EXCEPTION 'Transfer verification round differs from current intent' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION guard_gs_region_owner_transfer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Region owner transfer custody is retained' USING ERRCODE='23514';
    END IF;
    IF ROW(NEW.transfer_id,NEW.tenant_id,NEW.game_instance_id,NEW.region_id,NEW.region_epoch,
        NEW.old_owner_id,NEW.old_owner_fence,NEW.old_lease_correlation,NEW.source_digest,NEW.intent_digest)
       IS DISTINCT FROM ROW(OLD.transfer_id,OLD.tenant_id,OLD.game_instance_id,OLD.region_id,OLD.region_epoch,
        OLD.old_owner_id,OLD.old_owner_fence,OLD.old_lease_correlation,OLD.source_digest,OLD.intent_digest)
       OR (OLD.state='COMPLETED' AND NEW IS DISTINCT FROM OLD)
       OR (OLD.incarnation_digest IS NOT NULL AND NEW.incarnation_digest IS DISTINCT FROM OLD.incarnation_digest)
       OR (OLD.verification_round IS NOT NULL AND NEW.verification_round IS DISTINCT FROM OLD.verification_round)
       OR NEW.verification_cursor<OLD.verification_cursor
       OR (OLD.verification_complete AND NOT NEW.verification_complete) THEN
        RAISE EXCEPTION 'Region transfer source and completed custody are immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.state='COMPLETED' AND OLD.state='FROZEN'
       AND EXISTS (SELECT 1 FROM game_session_region_owner_transfer_item i WHERE i.transfer_id=NEW.transfer_id) THEN
        IF NEW.verification_complete IS DISTINCT FROM true
           OR NEW.incarnation_digest IS NULL OR NEW.verification_round IS NULL THEN
            RAISE EXCEPTION 'Captured region transfer requires complete current verification' USING ERRCODE='23514';
        END IF;
        IF EXISTS (
            SELECT 1 FROM game_session_region_owner_transfer_item i
            LEFT JOIN game_session_original_new_capacity_execution e
              ON e.execution_digest=i.execution_digest
            LEFT JOIN game_session_canonical_binding_transition t
              ON t.transition_id=e.transition_id
            LEFT JOIN game_session_canonical_binding_region_bridge_obligation b
              ON b.transition_id=e.transition_id AND b.tenant_id=NEW.tenant_id
             AND b.game_instance_id=NEW.game_instance_id AND b.region_id=NEW.region_id
            LEFT JOIN game_session_canonical_tenant_gameplay_tag a
              ON a.tenant_id=e.tenant_id
            WHERE i.transfer_id=NEW.transfer_id AND (
                e.execution_digest IS NULL
                OR i.canonical_execution IS DISTINCT FROM e.canonical_execution
                OR i.candidate_digest IS DISTINCT FROM t.original_new_candidate_digest
                OR i.tenant_gameplay_tag IS DISTINCT FROM e.tenant_gameplay_tag
                OR i.tenant_gameplay_tag IS DISTINCT FROM a.tenant_gameplay_tag
                OR i.allocation_version IS DISTINCT FROM a.allocation_version
                OR b.transition_id IS NULL
                OR i.fence_readback IS NULL
                OR i.incarnation_digest IS DISTINCT FROM NEW.incarnation_digest
                OR EXISTS (
                    SELECT 1 FROM game_session_region_owner_transfer_item dispatched
                    WHERE dispatched.execution_digest=i.execution_digest AND dispatched.fence_dispatch_id IS NOT NULL
                      AND dispatched.fence_dispatch_incarnation IS DISTINCT FROM i.incarnation_digest
                )
                OR i.before_payload_digest IS NULL
                OR i.after_payload_digest IS NULL
                OR i.verified_round IS DISTINCT FROM NEW.verification_round)) THEN
            RAISE EXCEPTION 'Captured region transfer item lacks its full exact current receipt' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.state='COMPLETED' AND EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_execution e
        JOIN game_session_canonical_binding_region_bridge_obligation b ON b.transition_id=e.transition_id
        WHERE b.tenant_id=NEW.tenant_id AND b.game_instance_id=NEW.game_instance_id AND b.region_id=NEW.region_id
          AND e.status IN ('INSTALL_DISPATCHED','AUTHORIZED') AND NOT EXISTS (
            SELECT 1 FROM game_session_region_owner_transfer_item i
            WHERE i.transfer_id=NEW.transfer_id AND i.execution_digest=e.execution_digest
              AND i.canonical_execution=e.canonical_execution AND i.fence_readback IS NOT NULL
              AND i.incarnation_digest=NEW.incarnation_digest AND i.verified_round=NEW.verification_round
              AND NEW.verification_complete)) THEN
        RAISE EXCEPTION 'Unresolved original lacks complete current transfer custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION require_gs_region_owner_transfer_custody() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.executor_fence,NEW.owner_instance_id,NEW.lease_token_correlation,NEW.owner_service)
       IS NOT DISTINCT FROM ROW(OLD.executor_fence,OLD.owner_instance_id,OLD.lease_token_correlation,OLD.owner_service) THEN RETURN NEW; END IF;
    IF NEW.executor_fence <= OLD.executor_fence THEN
        RAISE EXCEPTION 'Region owner replacement requires an advancing executor fence' USING ERRCODE='23514';
    END IF;
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

-- Runtime-family catalog registration
DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.17'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.17'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.18__require_exact_region_transfer_receipt_custody.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.18'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.17'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.18''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.17','V1065.0.17__retain_capacity_send_boundary.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.18'',''V1065.0.18__require_exact_region_transfer_receipt_custody.sql''';
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
