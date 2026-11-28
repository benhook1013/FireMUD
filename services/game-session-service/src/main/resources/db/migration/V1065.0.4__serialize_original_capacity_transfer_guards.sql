-- [jooq ignore start]
CREATE FUNCTION reject_gs_capacity_close_after_final_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='AUTHORIZED' AND NEW.status='CLOSED' AND EXISTS (
        SELECT 1 FROM game_session_canonical_binding_transition t
        WHERE t.original_new_final_session_sha256 IS NOT NULL
          AND t.original_new_capacity_execution_digest=OLD.execution_digest) THEN
        RAISE EXCEPTION 'Original NEW execution cannot close after retained final receipt' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_capacity_close_after_final_receipt BEFORE UPDATE
    ON game_session_original_new_capacity_execution FOR EACH ROW
    EXECUTE FUNCTION reject_gs_capacity_close_after_final_receipt();

CREATE OR REPLACE FUNCTION guard_gs_original_new_capacity_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.original_new_capacity_execution_digest IS NOT NULL
        AND NEW.original_new_capacity_execution_digest IS DISTINCT FROM OLD.original_new_capacity_execution_digest THEN
        RAISE EXCEPTION 'Original NEW capacity receipt is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.original_new_final_session_sha256 IS NULL AND NEW.original_new_capacity_execution_digest IS NOT NULL THEN
        RAISE EXCEPTION 'Original NEW capacity receipt binds only a final admission' USING ERRCODE = '23514';
    END IF;
    IF NEW.original_new_final_session_sha256 IS NOT NULL AND OLD.original_new_final_session_sha256 IS NULL THEN
        -- The transition is already locked by its UPDATE. Touch the exact execution tuple in
        -- transition -> execution order so an older REPEATABLE READ closer cannot miss this
        -- receipt after waiting on the execution row.
        UPDATE game_session_original_new_capacity_execution e
           SET status=e.status
         WHERE e.execution_digest=NEW.original_new_capacity_execution_digest
           AND e.transition_id=NEW.transition_id AND e.tenant_id=NEW.tenant_id
           AND e.request_id=NEW.admission_request_id AND e.admission_id=NEW.transition_id
           AND e.status='AUTHORIZED';
        IF NEW.original_new_capacity_execution_digest IS NULL OR NOT FOUND THEN
            RAISE EXCEPTION 'Original NEW final receipt requires its exact authorized capacity execution' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- Existing split receipts predate the tuple touch above. Materialize their exact execution
-- version once so REPEATABLE READ close attempts also conflict after this migration.
UPDATE game_session_original_new_capacity_execution e
   SET status=e.status
  FROM game_session_canonical_binding_transition t
 WHERE t.original_new_final_session_sha256 IS NOT NULL
   AND t.original_new_capacity_execution_digest=e.execution_digest
   AND e.status='AUTHORIZED';

CREATE OR REPLACE FUNCTION fence_gs_capacity_dispatch_during_transfer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE scope RECORD; current_owner RECORD;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Region dispatch requires READ COMMITTED owner fencing' USING ERRCODE='23514';
    END IF;
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
    IF EXISTS (SELECT 1 FROM game_session_region_owner_transfer t WHERE t.tenant_id=scope.tenant_id
        AND t.game_instance_id=scope.game_instance_id AND t.region_id=scope.region_id AND t.state='COMPLETED'
        AND t.old_owner_id=NEW.selected_owner_id AND t.old_owner_fence=NEW.selected_owner_fence) THEN
        RAISE EXCEPTION 'Completed region predecessor cannot acquire dispatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION require_gs_frozen_region_owner_transfer_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_owner RECORD;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Region transfer freeze requires READ COMMITTED owner fencing' USING ERRCODE='23514';
    END IF;
    IF NEW.state IS DISTINCT FROM 'FROZEN'
        OR NEW.incarnation_digest IS NOT NULL OR NEW.verification_round IS NOT NULL
        OR NEW.verification_cursor IS DISTINCT FROM -1
        OR NEW.verification_complete IS DISTINCT FROM false
        OR NEW.new_owner_id IS NOT NULL OR NEW.new_owner_fence IS NOT NULL
        OR NEW.new_lease_correlation IS NOT NULL THEN
        RAISE EXCEPTION 'Region transfer must start as a clean frozen intent' USING ERRCODE='23514';
    END IF;
    SELECT r.region_epoch,r.owner_instance_id,r.executor_fence,r.lease_token_correlation,r.owner_service,r.paused
      INTO current_owner
      FROM game_session_region_runtime_status r
     WHERE r.tenant_id=NEW.tenant_id AND r.game_instance_id=NEW.game_instance_id AND r.region_id=NEW.region_id
       FOR UPDATE;
    IF NOT FOUND OR current_owner.region_epoch IS DISTINCT FROM NEW.region_epoch
        OR current_owner.owner_instance_id IS DISTINCT FROM NEW.old_owner_id
        OR current_owner.executor_fence IS DISTINCT FROM NEW.old_owner_fence
        OR current_owner.lease_token_correlation IS DISTINCT FROM NEW.old_lease_correlation
        OR current_owner.owner_service IS DISTINCT FROM 'game-session-service'
        OR current_owner.paused IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'Region transfer freeze requires the exact current owner' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION guard_gs_region_owner_transfer_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent game_session_region_owner_transfer%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Region transfer item is retained' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
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
        WHERE e.execution_digest=NEW.execution_digest AND e.canonical_execution=NEW.canonical_execution
          AND e.tenant_gameplay_tag=NEW.tenant_gameplay_tag AND t.original_new_candidate_digest=NEW.candidate_digest
          AND b.tenant_id=parent.tenant_id AND b.game_instance_id=parent.game_instance_id AND b.region_id=parent.region_id
          AND e.status IN ('INSTALL_DISPATCHED','AUTHORIZED')
        FOR SHARE OF e;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Transfer item differs from unresolved original region execution' USING ERRCODE='23514';
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

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1064'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1064'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.4__serialize_original_capacity_transfer_guards.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.4'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1064'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.4''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1064','V1064__restore_final_human_completion_lower_bound.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.4'',''V1065.0.4__serialize_original_capacity_transfer_guards.sql''';
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
