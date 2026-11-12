-- Retain positive origin custody and bounded recovery across actual owner transfer.
-- SQL DENIED alone is not physical denial. Every origin remains fenced until its exact
-- terminal evidence or a separately qualified cleanup proof is retained.
CREATE TABLE game_session_original_new_capacity_origin (
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    admission_id UUID NOT NULL,
    admission_attempt_id UUID PRIMARY KEY,
    capacity_rearm_fence BIGINT NOT NULL CHECK (capacity_rearm_fence > 0),
    transition_id UUID NOT NULL,
    game_instance_id UUID NOT NULL,
    region_id UUID NOT NULL,
    mutation_digest TEXT NOT NULL CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
    canonical_request BYTEA NOT NULL,
    canonical_candidate BYTEA NOT NULL,
    canonical_origin BYTEA NOT NULL,
    origin_digest VARCHAR(71) NOT NULL UNIQUE CHECK (origin_digest ~ '^sha256:[0-9a-f]{64}$'),
    candidate_digest VARCHAR(71) NOT NULL CHECK (candidate_digest ~ '^sha256:[0-9a-f]{64}$'),
    tenant_gameplay_tag TEXT NOT NULL,
    selected_owner_id TEXT NOT NULL,
    selected_owner_fence BIGINT NOT NULL CHECK (selected_owner_fence > 0),
    custody_owner_id TEXT NOT NULL,
    custody_owner_fence BIGINT NOT NULL CHECK (custody_owner_fence >= selected_owner_fence),
    custody_transfer_id UUID,
    recovery_deadline TIMESTAMPTZ NOT NULL,
    recovery_max_observations INTEGER NOT NULL CHECK (recovery_max_observations>0),
    recovery_observations INTEGER NOT NULL DEFAULT 0 CHECK (recovery_observations>=0),
    last_observation_deadline TIMESTAMPTZ,
    reservation_policy_applied BOOLEAN NOT NULL DEFAULT false,
    state TEXT NOT NULL CHECK (state IN ('OPEN','DISPATCH_CLAIMED','CLOSURE_CLAIMED','DENIED')),
    account_resolution_bytes BYTEA,
    account_resolution_digest TEXT,
    UNIQUE (tenant_id,request_id,admission_id,capacity_rearm_fence),
    CHECK (admission_id=transition_id),
    CHECK ((account_resolution_bytes IS NULL)=(account_resolution_digest IS NULL)),
    CHECK ((state='OPEN')=(account_resolution_bytes IS NULL))
);
ALTER TABLE game_session_region_owner_transfer
    ADD COLUMN successor_owner_id VARCHAR(120),
    ADD COLUMN successor_owner_fence BIGINT,
    ADD COLUMN successor_lease_correlation CHAR(64),
    ADD COLUMN origin_verification_round UUID,
    ADD CONSTRAINT gs_transfer_successor_exact CHECK (
        (successor_owner_id IS NULL AND successor_owner_fence IS NULL AND successor_lease_correlation IS NULL)
        OR (successor_owner_id IS NOT NULL AND successor_owner_id<>'' AND btrim(successor_owner_id)=successor_owner_id
            AND successor_owner_fence IS NOT NULL AND successor_owner_fence>old_owner_fence
            AND successor_lease_correlation IS NOT NULL AND successor_lease_correlation ~ '^[0-9a-f]{64}$'));

CREATE TABLE game_session_capacity_origin_transfer_item (
    transfer_id UUID NOT NULL REFERENCES game_session_region_owner_transfer(transfer_id),
    target_ordinal BIGINT NOT NULL CHECK (target_ordinal>=0),
    origin_digest VARCHAR(71) NOT NULL REFERENCES game_session_original_new_capacity_origin(origin_digest),
    canonical_origin BYTEA NOT NULL CHECK (octet_length(canonical_origin) BETWEEN 1 AND 1048576),
    captured_state TEXT NOT NULL CHECK (captured_state IN ('OPEN','DISPATCH_CLAIMED','CLOSURE_CLAIMED','DENIED')),
    old_owner_id VARCHAR(120) NOT NULL,
    old_owner_fence BIGINT NOT NULL CHECK (old_owner_fence>0),
    account_resolution_bytes BYTEA,
    account_resolution_digest TEXT,
    tenant_gameplay_tag VARCHAR(44) NOT NULL,
    workload_namespace VARCHAR(253) NOT NULL,
    allocation_version INTEGER NOT NULL CHECK (allocation_version>0),
    fence_readback BYTEA CHECK (octet_length(fence_readback) BETWEEN 1 AND 1048576),
    incarnation_digest VARCHAR(71) CHECK (incarnation_digest ~ '^sha256:[0-9a-f]{64}$'),
    before_payload_digest VARCHAR(71) CHECK (before_payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    after_payload_digest VARCHAR(71) CHECK (after_payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    verified_round UUID,
    PRIMARY KEY (transfer_id,target_ordinal),
    UNIQUE (transfer_id,origin_digest),
    CHECK ((account_resolution_bytes IS NULL)=(account_resolution_digest IS NULL)),
    CHECK ((captured_state='OPEN')=(account_resolution_bytes IS NULL)),
    CHECK (account_resolution_bytes IS NULL OR account_resolution_digest='sha256:'||encode(sha256(account_resolution_bytes),'hex')),
    CHECK ((fence_readback IS NULL AND incarnation_digest IS NULL AND before_payload_digest IS NULL AND after_payload_digest IS NULL)
        OR (fence_readback IS NOT NULL AND incarnation_digest IS NOT NULL AND before_payload_digest IS NOT NULL AND after_payload_digest IS NOT NULL)),
    CHECK (verified_round IS NULL OR fence_readback IS NOT NULL)
);
CREATE INDEX gs_origin_transfer_pending_scope
    ON game_session_original_new_capacity_origin(tenant_id,game_instance_id,region_id,origin_digest);

-- [jooq ignore start]
CREATE FUNCTION guard_gs_capacity_origin_custody() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Capacity origin custody is retained' USING ERRCODE='23514'; END IF;
    IF TG_OP='INSERT' AND EXISTS (SELECT 1 FROM game_session_original_new_capacity_execution e
        WHERE e.admission_attempt_id=NEW.admission_attempt_id OR e.transition_id=NEW.transition_id) THEN
        RAISE EXCEPTION 'Historical execution cannot acquire fabricated pre-Account origin' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' THEN
        IF ROW(NEW.tenant_id,NEW.request_id,NEW.admission_id,NEW.admission_attempt_id,NEW.capacity_rearm_fence,
            NEW.transition_id,NEW.game_instance_id,NEW.region_id,NEW.mutation_digest,NEW.canonical_request,NEW.canonical_candidate,
            NEW.canonical_origin,NEW.origin_digest,NEW.candidate_digest,NEW.tenant_gameplay_tag,
            NEW.selected_owner_id,NEW.selected_owner_fence)
            IS DISTINCT FROM ROW(OLD.tenant_id,OLD.request_id,OLD.admission_id,OLD.admission_attempt_id,OLD.capacity_rearm_fence,
            OLD.transition_id,OLD.game_instance_id,OLD.region_id,OLD.mutation_digest,OLD.canonical_request,OLD.canonical_candidate,
            OLD.canonical_origin,OLD.origin_digest,OLD.candidate_digest,OLD.tenant_gameplay_tag,
            OLD.selected_owner_id,OLD.selected_owner_fence) THEN
            RAISE EXCEPTION 'Capacity origin identity is immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.recovery_observations<OLD.recovery_observations OR NEW.recovery_observations>OLD.recovery_observations+1
            OR (OLD.reservation_policy_applied AND NOT NEW.reservation_policy_applied)
            OR (ROW(NEW.recovery_deadline,NEW.recovery_max_observations) IS DISTINCT FROM ROW(OLD.recovery_deadline,OLD.recovery_max_observations)
                AND NOT (NOT OLD.reservation_policy_applied AND NEW.reservation_policy_applied))
            OR (NEW.recovery_observations=OLD.recovery_observations AND NEW.last_observation_deadline IS DISTINCT FROM OLD.last_observation_deadline)
            OR (NEW.recovery_observations>OLD.recovery_observations AND (NEW.recovery_observations>NEW.recovery_max_observations OR NEW.last_observation_deadline IS NULL OR NEW.last_observation_deadline>NEW.recovery_deadline)) THEN
            RAISE EXCEPTION 'Origin observation budget cannot reset or replenish' USING ERRCODE='23514';
        END IF;
        IF NEW.state IS DISTINCT FROM OLD.state AND OLD.state<>'OPEN' THEN
            RAISE EXCEPTION 'Capacity origin claims cannot change or reopen' USING ERRCODE='23514';
        END IF;
        IF OLD.account_resolution_bytes IS NOT NULL AND
            ROW(NEW.account_resolution_bytes,NEW.account_resolution_digest)
            IS DISTINCT FROM ROW(OLD.account_resolution_bytes,OLD.account_resolution_digest) THEN
            RAISE EXCEPTION 'Exact Account resolution is immutable' USING ERRCODE='23514';
        END IF;
        IF ROW(NEW.custody_owner_id,NEW.custody_owner_fence,NEW.custody_transfer_id)
            IS DISTINCT FROM ROW(OLD.custody_owner_id,OLD.custody_owner_fence,OLD.custody_transfer_id)
            AND ROW(NEW.recovery_deadline,NEW.recovery_max_observations,NEW.recovery_observations,NEW.last_observation_deadline,NEW.reservation_policy_applied)
                IS DISTINCT FROM ROW(OLD.recovery_deadline,OLD.recovery_max_observations,OLD.recovery_observations,OLD.last_observation_deadline,OLD.reservation_policy_applied) THEN
            RAISE EXCEPTION 'Transfer cannot change or replenish origin continuation budget' USING ERRCODE='23514';
        END IF;
        IF ROW(NEW.custody_owner_id,NEW.custody_owner_fence,NEW.custody_transfer_id)
            IS DISTINCT FROM ROW(OLD.custody_owner_id,OLD.custody_owner_fence,OLD.custody_transfer_id)
            AND (NEW.custody_owner_fence<=OLD.custody_owner_fence OR NEW.custody_transfer_id IS NULL) THEN
            RAISE EXCEPTION 'Custody moves only through a retained increasing transfer' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_capacity_origin_guard BEFORE INSERT OR UPDATE OR DELETE
    ON game_session_original_new_capacity_origin FOR EACH ROW EXECUTE FUNCTION guard_gs_capacity_origin_custody();

CREATE TRIGGER gs_capacity_origin_no_truncate BEFORE TRUNCATE ON game_session_original_new_capacity_origin
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_region_owner_transfer_truncate();

-- Closure-only SQL custody never enters AUTHORIZE or COMMITTED and is not negative proof.
ALTER TABLE game_session_original_new_capacity_execution
    DROP CONSTRAINT game_session_original_new_capacity_execution_status_check;
ALTER TABLE game_session_original_new_capacity_execution ADD CONSTRAINT game_session_original_new_capacity_execution_status_check
    CHECK (status IN ('INSTALL_DISPATCHED','AUTHORIZED','COMMITTED','CLOSED','CLOSURE_CLAIMED'));

CREATE OR REPLACE FUNCTION guard_gs_original_new_capacity_execution() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Original NEW capacity execution is retained' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.tenant_id, NEW.request_id, NEW.admission_id, NEW.admission_attempt_id,
        NEW.transition_id, NEW.capacity_rearm_fence, NEW.reservation_id, NEW.reservation_fence,
        NEW.mutation_digest, NEW.execution_digest, NEW.canonical_execution,
        NEW.selected_owner_id, NEW.selected_owner_fence, NEW.tenant_gameplay_tag)
        IS DISTINCT FROM ROW(OLD.tenant_id, OLD.request_id, OLD.admission_id, OLD.admission_attempt_id,
        OLD.transition_id, OLD.capacity_rearm_fence, OLD.reservation_id, OLD.reservation_fence,
        OLD.mutation_digest, OLD.execution_digest, OLD.canonical_execution,
        OLD.selected_owner_id, OLD.selected_owner_fence, OLD.tenant_gameplay_tag)
        OR (OLD.status IN ('COMMITTED', 'CLOSED') AND NEW IS DISTINCT FROM OLD)
        OR (OLD.status = 'AUTHORIZED' AND NEW.status NOT IN ('AUTHORIZED', 'COMMITTED', 'CLOSED'))
        OR (OLD.status = 'CLOSURE_CLAIMED' AND NEW.status NOT IN ('CLOSURE_CLAIMED','CLOSED'))
        OR (OLD.status = 'INSTALL_DISPATCHED' AND NEW.status NOT IN ('INSTALL_DISPATCHED', 'AUTHORIZED', 'CLOSED')) THEN
        RAISE EXCEPTION 'Original NEW capacity execution identity/outcome is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION require_gs_exact_origin_execution_custody() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE origin RECORD;
BEGIN
    SELECT * INTO origin FROM game_session_original_new_capacity_origin
        WHERE admission_attempt_id=NEW.admission_attempt_id FOR UPDATE;
    IF NOT FOUND THEN
        IF NEW.status='CLOSURE_CLAIMED' THEN RAISE EXCEPTION 'Closure construction requires positive origin' USING ERRCODE='23514'; END IF;
        RETURN NEW; -- Existing historical/manual carrier retains its old quarantine behavior.
    END IF;
    IF ROW(origin.tenant_id,origin.request_id,origin.admission_id,origin.admission_attempt_id,
        origin.capacity_rearm_fence,origin.transition_id,origin.mutation_digest,origin.tenant_gameplay_tag,
        origin.selected_owner_id,origin.selected_owner_fence)
        IS DISTINCT FROM ROW(NEW.tenant_id,NEW.request_id,NEW.admission_id,NEW.admission_attempt_id,
        NEW.capacity_rearm_fence,NEW.transition_id,NEW.mutation_digest,NEW.tenant_gameplay_tag,
        NEW.selected_owner_id,NEW.selected_owner_fence)
        OR NOT ((origin.state='DISPATCH_CLAIMED' AND NEW.status='INSTALL_DISPATCHED')
            OR (origin.state='CLOSURE_CLAIMED' AND NEW.status='CLOSURE_CLAIMED')) THEN
        RAISE EXCEPTION 'Exact exclusive origin execution custody required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_exact_origin_execution_custody BEFORE INSERT ON game_session_original_new_capacity_execution
    FOR EACH ROW EXECUTE FUNCTION require_gs_exact_origin_execution_custody();

-- The owner APIs acquire inventory then region then origin. Fence origin enrollment/claims
-- against the same FROZEN interval; successor custody promotion preserves state/resolution.
CREATE FUNCTION fence_gs_origin_claim_during_transfer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE claim_changed BOOLEAN;
BEGIN
    IF TG_OP='INSERT' THEN claim_changed:=true;
    ELSE claim_changed:=ROW(NEW.state,NEW.account_resolution_bytes,NEW.account_resolution_digest,
        NEW.recovery_deadline,NEW.recovery_max_observations,NEW.recovery_observations,NEW.last_observation_deadline,NEW.reservation_policy_applied)
        IS DISTINCT FROM ROW(OLD.state,OLD.account_resolution_bytes,OLD.account_resolution_digest,
        OLD.recovery_deadline,OLD.recovery_max_observations,OLD.recovery_observations,OLD.last_observation_deadline,OLD.reservation_policy_applied);
    END IF;
    IF claim_changed THEN
        PERFORM 1 FROM game_session_region_runtime_status r WHERE r.tenant_id=NEW.tenant_id
            AND r.game_instance_id=NEW.game_instance_id AND r.region_id=NEW.region_id FOR UPDATE;
        IF NOT FOUND OR EXISTS (SELECT 1 FROM game_session_region_owner_transfer t
            WHERE t.tenant_id=NEW.tenant_id AND t.game_instance_id=NEW.game_instance_id
                AND t.region_id=NEW.region_id AND t.state='FROZEN') THEN
            RAISE EXCEPTION 'Origin enrollment/claim is frozen for owner transfer' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_origin_claim_transfer_fence BEFORE INSERT OR UPDATE ON game_session_original_new_capacity_origin
    FOR EACH ROW EXECUTE FUNCTION fence_gs_origin_claim_during_transfer();

-- Retain the first successor choice across indeterminate physical replies. A later retry may not
-- create another owner, lease, fence, captured state or canonical origin under that transfer.
CREATE FUNCTION guard_gs_origin_transfer_successor() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.successor_owner_id IS NOT NULL AND
        ROW(NEW.successor_owner_id,NEW.successor_owner_fence,NEW.successor_lease_correlation)
        IS DISTINCT FROM ROW(OLD.successor_owner_id,OLD.successor_owner_fence,OLD.successor_lease_correlation) THEN
        RAISE EXCEPTION 'Origin transfer successor is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.state='COMPLETED' AND NEW.successor_owner_id IS NOT NULL AND
        ROW(NEW.new_owner_id,NEW.new_owner_fence,NEW.new_lease_correlation)
        IS DISTINCT FROM ROW(NEW.successor_owner_id,NEW.successor_owner_fence,NEW.successor_lease_correlation) THEN
        RAISE EXCEPTION 'Runtime installation must consume the reserved successor' USING ERRCODE='23514';
    END IF;
    IF NEW.state='COMPLETED' AND EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_origin o
        WHERE o.tenant_id=NEW.tenant_id AND o.game_instance_id=NEW.game_instance_id AND o.region_id=NEW.region_id
            AND NOT EXISTS (
                SELECT 1 FROM game_session_capacity_origin_transfer_item i
                WHERE i.transfer_id=NEW.transfer_id AND i.origin_digest=o.origin_digest
                    AND i.canonical_origin=o.canonical_origin AND i.captured_state=o.state
                    AND i.old_owner_id=NEW.old_owner_id AND i.old_owner_fence=NEW.old_owner_fence
                    AND i.account_resolution_bytes IS NOT DISTINCT FROM o.account_resolution_bytes
                    AND i.account_resolution_digest IS NOT DISTINCT FROM o.account_resolution_digest
                    AND i.fence_readback IS NOT NULL AND i.incarnation_digest=NEW.incarnation_digest
                    AND i.verified_round=NEW.origin_verification_round
                    AND o.custody_owner_id=NEW.new_owner_id AND o.custody_owner_fence=NEW.new_owner_fence
                    AND o.custody_transfer_id=NEW.transfer_id)) THEN
        RAISE EXCEPTION 'Unresolved origin lacks exact promoted physical custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_origin_transfer_successor_guard BEFORE UPDATE ON game_session_region_owner_transfer
    FOR EACH ROW EXECUTE FUNCTION guard_gs_origin_transfer_successor();

CREATE FUNCTION guard_gs_origin_transfer_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent game_session_region_owner_transfer%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Origin transfer custody is retained' USING ERRCODE='23514'; END IF;
    SELECT * INTO STRICT parent FROM game_session_region_owner_transfer WHERE transfer_id=NEW.transfer_id;
    IF parent.state<>'FROZEN' THEN RAISE EXCEPTION 'Origin transfer is not frozen' USING ERRCODE='23514'; END IF;
    IF TG_OP='INSERT' AND NOT EXISTS (
        SELECT 1 FROM game_session_original_new_capacity_origin o
        WHERE o.origin_digest=NEW.origin_digest AND o.canonical_origin=NEW.canonical_origin
            AND o.state=NEW.captured_state AND o.custody_owner_id=NEW.old_owner_id AND o.custody_owner_fence=NEW.old_owner_fence
            AND o.custody_owner_id=parent.old_owner_id AND o.custody_owner_fence=parent.old_owner_fence
            AND o.tenant_id=parent.tenant_id AND o.game_instance_id=parent.game_instance_id AND o.region_id=parent.region_id
            AND o.tenant_gameplay_tag=NEW.tenant_gameplay_tag
            AND o.account_resolution_bytes IS NOT DISTINCT FROM NEW.account_resolution_bytes
            AND o.account_resolution_digest IS NOT DISTINCT FROM NEW.account_resolution_digest) THEN
        RAISE EXCEPTION 'Origin capture differs from retained pre-Account source' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (
        ROW(NEW.transfer_id,NEW.target_ordinal,NEW.origin_digest,NEW.canonical_origin,NEW.captured_state,NEW.old_owner_id,NEW.old_owner_fence,
            NEW.account_resolution_bytes,NEW.account_resolution_digest,NEW.tenant_gameplay_tag,NEW.workload_namespace,NEW.allocation_version)
        IS DISTINCT FROM ROW(OLD.transfer_id,OLD.target_ordinal,OLD.origin_digest,OLD.canonical_origin,OLD.captured_state,OLD.old_owner_id,OLD.old_owner_fence,
            OLD.account_resolution_bytes,OLD.account_resolution_digest,OLD.tenant_gameplay_tag,OLD.workload_namespace,OLD.allocation_version)
        OR (OLD.fence_readback IS NOT NULL AND ROW(NEW.fence_readback,NEW.incarnation_digest,NEW.before_payload_digest,NEW.after_payload_digest)
            IS DISTINCT FROM ROW(OLD.fence_readback,OLD.incarnation_digest,OLD.before_payload_digest,OLD.after_payload_digest))) THEN
        RAISE EXCEPTION 'Origin capture and physical fence receipt are immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.fence_readback IS NOT NULL AND parent.successor_owner_id IS NULL THEN
        RAISE EXCEPTION 'Origin physical custody requires a retained successor' USING ERRCODE='23514';
    END IF;
    IF NEW.verified_round IS NOT NULL AND NEW.verified_round IS DISTINCT FROM parent.origin_verification_round THEN
        RAISE EXCEPTION 'Origin current verification belongs to another round' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_origin_transfer_item_guard BEFORE INSERT OR UPDATE OR DELETE ON game_session_capacity_origin_transfer_item
    FOR EACH ROW EXECUTE FUNCTION guard_gs_origin_transfer_item();
CREATE TRIGGER gs_origin_transfer_item_no_truncate BEFORE TRUNCATE ON game_session_capacity_origin_transfer_item
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_region_owner_transfer_truncate();

-- Every owner mutation, including origin-only regions, must have completed the exact retained
-- chain in the same runtime installation transaction. Neither a pending intent nor an absent
-- execution row supplies an empty-inventory exemption.
CREATE FUNCTION require_gs_origin_runtime_handoff() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.executor_fence,NEW.owner_instance_id,NEW.lease_token_correlation)
        IS NOT DISTINCT FROM ROW(OLD.executor_fence,OLD.owner_instance_id,OLD.lease_token_correlation) THEN RETURN NEW; END IF;
    IF EXISTS (SELECT 1 FROM game_session_original_new_capacity_origin o
        WHERE o.tenant_id=OLD.tenant_id AND o.game_instance_id=OLD.game_instance_id AND o.region_id=OLD.region_id)
        AND NOT EXISTS (SELECT 1 FROM game_session_region_owner_transfer t
            WHERE t.tenant_id=OLD.tenant_id AND t.game_instance_id=OLD.game_instance_id AND t.region_id=OLD.region_id
                AND t.region_epoch=OLD.region_epoch AND t.old_owner_id=OLD.owner_instance_id AND t.old_owner_fence=OLD.executor_fence
                AND t.old_lease_correlation=OLD.lease_token_correlation AND t.state='COMPLETED'
                AND t.new_owner_id=NEW.owner_instance_id AND t.new_owner_fence=NEW.executor_fence
                AND t.new_lease_correlation=NEW.lease_token_correlation AND t.origin_verification_round IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM game_session_original_new_capacity_origin o
                    WHERE o.tenant_id=OLD.tenant_id AND o.game_instance_id=OLD.game_instance_id AND o.region_id=OLD.region_id
                        AND (o.custody_owner_id<>NEW.owner_instance_id OR o.custody_owner_fence<>NEW.executor_fence
                            OR o.custody_transfer_id IS DISTINCT FROM t.transfer_id))) THEN
        RAISE EXCEPTION 'Runtime successor lacks all-origin transfer custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_origin_runtime_handoff_guard BEFORE UPDATE ON game_session_region_runtime_status
    FOR EACH ROW EXECUTE FUNCTION require_gs_origin_runtime_handoff();

-- Custody may be promoted before COMPLETED within the transaction, but cannot commit without
-- the actual runtime successor. This is a deferred integrity check, never an execution outcome.
CREATE FUNCTION require_gs_origin_custody_successor() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.custody_owner_id,NEW.custody_owner_fence,NEW.custody_transfer_id)
        IS NOT DISTINCT FROM ROW(OLD.custody_owner_id,OLD.custody_owner_fence,OLD.custody_transfer_id) THEN RETURN NULL; END IF;
    IF NOT EXISTS (SELECT 1 FROM game_session_original_new_capacity_origin o
        JOIN game_session_region_owner_transfer t ON t.transfer_id=o.custody_transfer_id
        JOIN game_session_region_runtime_status r ON r.tenant_id=t.tenant_id AND r.game_instance_id=t.game_instance_id AND r.region_id=t.region_id
        JOIN game_session_capacity_origin_transfer_item i ON i.transfer_id=t.transfer_id AND i.origin_digest=o.origin_digest
        WHERE o.admission_attempt_id=NEW.admission_attempt_id AND t.state='COMPLETED'
            AND t.old_owner_id=OLD.custody_owner_id AND t.old_owner_fence=OLD.custody_owner_fence
            AND t.new_owner_id=o.custody_owner_id AND t.new_owner_fence=o.custody_owner_fence
            AND r.owner_instance_id=t.new_owner_id AND r.executor_fence=t.new_owner_fence AND r.region_epoch=t.region_epoch
            AND r.lease_token_correlation=t.new_lease_correlation AND i.canonical_origin=o.canonical_origin
            AND i.captured_state=o.state AND i.fence_readback IS NOT NULL
            AND i.incarnation_digest=t.incarnation_digest AND i.verified_round=t.origin_verification_round) THEN
        RAISE EXCEPTION 'Origin custody promotion requires actual installed successor' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER gs_origin_custody_successor_guard AFTER UPDATE ON game_session_original_new_capacity_origin
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_gs_origin_custody_successor();

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_original_new_capacity_origin','game_session_original_new_capacity_origin','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Retain the exact original pre-Account custody, immutable outcome and bounded observation budget. Missing evidence does not authorize release, rearm or admission.'),
    ('game_session_capacity_origin_transfer_item','game_session_capacity_origin_transfer_item','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Retain the exact original origin bytes and protected successor custody. Do not reconstruct or discard the evidence during owner movement.');

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065'
    );$needle$;
    replacement := $replacement$WHERE version='1065'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.1__original_new_capacity_origin_custody.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.1'
    );$replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog frontier'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.1''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065','V1065__original_new_capacity_owner_transfer_fencing.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.1'',''V1065.1__original_new_capacity_origin_custody.sql''';
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
