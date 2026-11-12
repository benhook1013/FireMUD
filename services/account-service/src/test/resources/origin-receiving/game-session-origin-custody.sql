-- PRIVATE receiving schema definition, not an activated Flyway migration.
-- Forward installation must include transfer capture and retention/catalog guards.
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
    origin_digest TEXT NOT NULL UNIQUE CHECK (origin_digest ~ '^sha256:[0-9a-f]{64}$'),
    candidate_digest TEXT NOT NULL CHECK (candidate_digest ~ '^sha256:[0-9a-f]{64}$'),
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
