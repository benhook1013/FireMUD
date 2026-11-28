-- Future execution exclusion only. Account retains every unresolved reservation and full hold.
CREATE TABLE game_session_region_owner_transfer (
    transfer_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    game_instance_id UUID NOT NULL,
    region_id UUID NOT NULL,
    region_epoch BIGINT NOT NULL CHECK (region_epoch > 0),
    old_owner_id VARCHAR(120) NOT NULL CHECK (old_owner_id <> '' AND btrim(old_owner_id)=old_owner_id),
    old_owner_fence BIGINT NOT NULL UNIQUE CHECK (old_owner_fence > 0),
    old_lease_correlation CHAR(64) NOT NULL CHECK (old_lease_correlation ~ '^[0-9a-f]{64}$'),
    source_digest VARCHAR(71) NOT NULL CHECK (source_digest ~ '^sha256:[0-9a-f]{64}$'),
    intent_digest VARCHAR(71) NOT NULL CHECK (intent_digest ~ '^sha256:[0-9a-f]{64}$'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('FROZEN','COMPLETED')),
    incarnation_digest VARCHAR(71) CHECK (incarnation_digest ~ '^sha256:[0-9a-f]{64}$'),
    verification_round UUID,
    verification_cursor BIGINT NOT NULL DEFAULT -1 CHECK (verification_cursor >= -1),
    verification_complete BOOLEAN NOT NULL DEFAULT false,
    new_owner_id VARCHAR(120),
    new_owner_fence BIGINT CHECK (new_owner_fence > old_owner_fence),
    new_lease_correlation CHAR(64) CHECK (new_lease_correlation ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY (tenant_id,game_instance_id,region_id)
        REFERENCES game_session_region_runtime_status(tenant_id,game_instance_id,region_id),
    CHECK ((state='COMPLETED') = (new_owner_id IS NOT NULL AND new_owner_fence IS NOT NULL AND new_lease_correlation IS NOT NULL)),
    CHECK (state='COMPLETED' OR (new_owner_id IS NULL AND new_owner_fence IS NULL AND new_lease_correlation IS NULL))
);
CREATE UNIQUE INDEX gs_region_owner_transfer_frozen_scope
    ON game_session_region_owner_transfer(tenant_id,game_instance_id,region_id) WHERE state='FROZEN';

CREATE TABLE game_session_region_owner_transfer_item (
    transfer_id UUID NOT NULL REFERENCES game_session_region_owner_transfer(transfer_id),
    target_ordinal BIGINT NOT NULL CHECK (target_ordinal >= 0),
    execution_digest VARCHAR(71) NOT NULL REFERENCES game_session_original_new_capacity_execution(execution_digest),
    canonical_execution BYTEA NOT NULL CHECK (octet_length(canonical_execution) BETWEEN 1 AND 1048576),
    candidate_digest VARCHAR(71) NOT NULL CHECK (candidate_digest ~ '^sha256:[0-9a-f]{64}$'),
    tenant_gameplay_tag VARCHAR(44) NOT NULL,
    workload_namespace VARCHAR(253) NOT NULL,
    allocation_version INTEGER NOT NULL CHECK (allocation_version > 0),
    fence_readback BYTEA CHECK (octet_length(fence_readback) BETWEEN 1 AND 1048576),
    incarnation_digest VARCHAR(71) CHECK (incarnation_digest ~ '^sha256:[0-9a-f]{64}$'),
    before_payload_digest VARCHAR(71) CHECK (before_payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    after_payload_digest VARCHAR(71) CHECK (after_payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    verified_round UUID,
    PRIMARY KEY (transfer_id,target_ordinal),
    UNIQUE (transfer_id,execution_digest),
    CHECK ((fence_readback IS NULL AND incarnation_digest IS NULL AND before_payload_digest IS NULL AND after_payload_digest IS NULL)
        OR (fence_readback IS NOT NULL AND incarnation_digest IS NOT NULL AND before_payload_digest IS NOT NULL AND after_payload_digest IS NOT NULL)),
    CHECK (verified_round IS NULL OR fence_readback IS NOT NULL)
);
CREATE INDEX gs_original_new_capacity_unresolved_transition
    ON game_session_original_new_capacity_execution(transition_id,execution_digest)
    WHERE status IN ('INSTALL_DISPATCHED','AUTHORIZED');

-- [jooq ignore start]
CREATE FUNCTION guard_gs_region_owner_transfer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
CREATE FUNCTION guard_gs_region_owner_transfer_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent game_session_region_owner_transfer%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Region transfer item is retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT parent FROM game_session_region_owner_transfer WHERE transfer_id=NEW.transfer_id;
    IF parent.state<>'FROZEN' THEN
        RAISE EXCEPTION 'Completed transfer cannot acquire or change custody' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' AND NOT EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_execution e
        JOIN game_session_canonical_binding_region_bridge_obligation b ON b.transition_id=e.transition_id
        JOIN game_session_canonical_binding_transition t ON t.transition_id=e.transition_id
        WHERE e.execution_digest=NEW.execution_digest AND e.canonical_execution=NEW.canonical_execution
          AND e.tenant_gameplay_tag=NEW.tenant_gameplay_tag AND t.original_new_candidate_digest=NEW.candidate_digest
          AND b.tenant_id=parent.tenant_id AND b.game_instance_id=parent.game_instance_id AND b.region_id=parent.region_id) THEN
        RAISE EXCEPTION 'Transfer item differs from original region execution' USING ERRCODE='23514';
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
CREATE FUNCTION reject_gs_region_owner_transfer_truncate() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Region owner transfer custody is retained' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER gs_region_owner_transfer_guard BEFORE UPDATE OR DELETE ON game_session_region_owner_transfer
    FOR EACH ROW EXECUTE FUNCTION guard_gs_region_owner_transfer();
CREATE TRIGGER gs_region_owner_transfer_item_guard BEFORE INSERT OR UPDATE OR DELETE ON game_session_region_owner_transfer_item
    FOR EACH ROW EXECUTE FUNCTION guard_gs_region_owner_transfer_item();
CREATE TRIGGER gs_region_owner_transfer_no_truncate BEFORE TRUNCATE ON game_session_region_owner_transfer
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_region_owner_transfer_truncate();
CREATE TRIGGER gs_region_owner_transfer_item_no_truncate BEFORE TRUNCATE ON game_session_region_owner_transfer_item
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_region_owner_transfer_truncate();

CREATE FUNCTION fence_gs_capacity_dispatch_during_transfer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE scope RECORD;
BEGIN
    SELECT DISTINCT tenant_id,game_instance_id,region_id INTO STRICT scope
        FROM game_session_canonical_binding_region_bridge_obligation WHERE transition_id=NEW.transition_id;
    PERFORM 1 FROM game_session_region_runtime_status r
        WHERE r.tenant_id=scope.tenant_id AND r.game_instance_id=scope.game_instance_id AND r.region_id=scope.region_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM game_session_region_owner_transfer t WHERE t.tenant_id=scope.tenant_id
        AND t.game_instance_id=scope.game_instance_id AND t.region_id=scope.region_id AND t.state='FROZEN') THEN
        RAISE EXCEPTION 'Region dispatch is frozen for normal owner transfer' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_capacity_dispatch_transfer_fence BEFORE INSERT ON game_session_original_new_capacity_execution
    FOR EACH ROW EXECUTE FUNCTION fence_gs_capacity_dispatch_during_transfer();

CREATE FUNCTION require_gs_region_owner_transfer_custody() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.executor_fence,NEW.owner_instance_id,NEW.lease_token_correlation)
       IS NOT DISTINCT FROM ROW(OLD.executor_fence,OLD.owner_instance_id,OLD.lease_token_correlation) THEN RETURN NEW; END IF;
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
CREATE TRIGGER gs_region_owner_transfer_custody BEFORE UPDATE ON game_session_region_runtime_status
    FOR EACH ROW EXECUTE FUNCTION require_gs_region_owner_transfer_custody();

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_region_owner_transfer','game_session_region_owner_transfer','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Normal-handoff dispatch freeze and exact successor custody are retained. Future exclusion is not an execution outcome or Account release authority.'),
    ('game_session_region_owner_transfer_item','game_session_region_owner_transfer_item','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Exact original execution, current storage-bound denial and bounded verification remain retained. Never clone, discard from expiry, or infer non-execution from missing storage.');

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1064'
    );$needle$;
    replacement := $replacement$WHERE version='1064'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065__original_new_capacity_owner_transfer_fencing.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065'
    );$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog frontier'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1064'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1064','V1064__restore_final_human_completion_lower_bound.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065'',''V1065__original_new_capacity_owner_transfer_fencing.sql''';
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
