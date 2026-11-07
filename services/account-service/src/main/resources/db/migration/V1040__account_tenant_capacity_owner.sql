-- Existing tenants are deliberately not initialized from missing usage or eligibility snapshots.
ALTER TABLE account_fresh_tenant_identity_associations ADD COLUMN capacity_birth_transaction VARCHAR(20);
ALTER TABLE account_fresh_tenant_identity_associations ALTER COLUMN capacity_birth_transaction SET DEFAULT pg_current_xact_id()::TEXT;

CREATE TABLE account_tenant_capacity_usage (
    tenant_uuid UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id),
    owner_service VARCHAR(40) NOT NULL CHECK (owner_service IN ('game-session-service', 'world-management-service')),
    bootstrap_request_id UUID NOT NULL REFERENCES account_tenant_creation_bootstrap_operations(request_id),
    creation_evidence_digest VARCHAR(71) NOT NULL CHECK (creation_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    held_units BIGINT NOT NULL CHECK (held_units >= 0),
    used_units BIGINT NOT NULL CHECK (used_units >= 0),
    usage_revision BIGINT NOT NULL CHECK (usage_revision > 0),
    PRIMARY KEY (tenant_uuid, owner_service),
    CHECK (held_units <= 9223372036854775807 - used_units)
);

CREATE TABLE account_tenant_capacity_evaluations (
    evaluation_identity VARCHAR(71) PRIMARY KEY CHECK (evaluation_identity ~ '^sha256:[0-9a-f]{64}$'),
    snapshot_identity VARCHAR(71) NOT NULL CHECK (snapshot_identity ~ '^sha256:[0-9a-f]{64}$'),
    tenant_uuid UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id),
    caller_workload VARCHAR(253) NOT NULL,
    owner_service VARCHAR(40) NOT NULL CHECK (owner_service IN ('game-session-service', 'world-management-service')),
    canonical_evidence BYTEA NOT NULL CHECK (octet_length(canonical_evidence) BETWEEN 1 AND 262144),
    evaluated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > evaluated_at)
);

CREATE TABLE account_tenant_capacity_reservations (
    tenant_uuid UUID NOT NULL,
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    admission_id UUID NOT NULL CHECK (admission_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    reservation_id UUID NOT NULL UNIQUE CHECK (reservation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    owner_service VARCHAR(40) NOT NULL,
    caller_workload VARCHAR(253) NOT NULL,
    admission_attempt_id UUID NOT NULL UNIQUE,
    capacity_rearm_fence BIGINT NOT NULL CHECK (capacity_rearm_fence > 0),
    reservation_fence BIGINT NOT NULL CHECK (reservation_fence > 0),
    mutation_digest CHAR(64) NOT NULL CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
    canonical_payload BYTEA NOT NULL CHECK (octet_length(canonical_payload) BETWEEN 1 AND 524288),
    evaluation_identity VARCHAR(71) NOT NULL REFERENCES account_tenant_capacity_evaluations(evaluation_identity),
    capacity_delta BIGINT NOT NULL CHECK (capacity_delta >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(64) NOT NULL CHECK (state IN ('RESERVED', 'FINALIZED', 'RELEASED', 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION')),
    execution_status VARCHAR(48) NOT NULL CHECK (execution_status IN ('OWNER_EXECUTION_PENDING', 'COMMITTED', 'NOT_EXECUTED_AFTER_AUTHORIZATION')),
    recovery_owner UUID,
    recovery_claim_fence BIGINT NOT NULL DEFAULT 0 CHECK (recovery_claim_fence >= 0),
    recovery_claim_expires_at TIMESTAMPTZ,
    next_reconcile_at TIMESTAMPTZ,
    reconcile_deadline TIMESTAMPTZ NOT NULL,
    recovery_policy_version VARCHAR(32) NOT NULL DEFAULT 'capacity-recovery/1' CHECK (recovery_policy_version = 'capacity-recovery/1'),
    recovery_attempt_count INT NOT NULL DEFAULT 0 CHECK (recovery_attempt_count BETWEEN 0 AND 12),
    recovery_escalated_at TIMESTAMPTZ,
    owner_readback BYTEA CHECK (octet_length(owner_readback) BETWEEN 1 AND 1048576),
    owner_readback_digest CHAR(64) CHECK (owner_readback_digest ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (tenant_uuid, request_id, admission_id),
    FOREIGN KEY (tenant_uuid, owner_service) REFERENCES account_tenant_capacity_usage(tenant_uuid, owner_service),
    CHECK ((execution_status = 'OWNER_EXECUTION_PENDING') = (owner_readback IS NULL)),
    CHECK ((owner_readback IS NULL) = (owner_readback_digest IS NULL)),
    CHECK ((state = 'FINALIZED') = (execution_status = 'COMMITTED')),
    CHECK ((state = 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION') = (recovery_escalated_at IS NOT NULL)),
    CHECK (state <> 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION' OR execution_status = 'OWNER_EXECUTION_PENDING'),
    CHECK (state <> 'RELEASED' OR execution_status = 'NOT_EXECUTED_AFTER_AUTHORIZATION'),
    CHECK ((recovery_owner IS NULL) = (recovery_claim_expires_at IS NULL))
);

CREATE TABLE account_tenant_capacity_attempt_history (
    tenant_uuid UUID NOT NULL,
    request_id UUID NOT NULL,
    admission_id UUID NOT NULL,
    admission_attempt_id UUID NOT NULL UNIQUE,
    capacity_rearm_fence BIGINT NOT NULL CHECK (capacity_rearm_fence > 0),
    mutation_digest CHAR(64) NOT NULL CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
    canonical_payload BYTEA NOT NULL CHECK (octet_length(canonical_payload) BETWEEN 1 AND 524288),
    owner_readback BYTEA NOT NULL CHECK (octet_length(owner_readback) BETWEEN 1 AND 1048576),
    PRIMARY KEY (tenant_uuid, request_id, admission_id, admission_attempt_id),
    FOREIGN KEY (tenant_uuid, request_id, admission_id) REFERENCES account_tenant_capacity_reservations(tenant_uuid, request_id, admission_id)
);

CREATE TABLE account_tenant_capacity_recovery_evidence (
    tenant_uuid UUID NOT NULL,
    request_id UUID NOT NULL,
    admission_id UUID NOT NULL,
    admission_attempt_id UUID NOT NULL,
    recovery_claim_fence BIGINT NOT NULL CHECK (recovery_claim_fence > 0),
    recovery_policy_version VARCHAR(32) NOT NULL CHECK (recovery_policy_version = 'capacity-recovery/1'),
    recovery_attempt_number INT NOT NULL CHECK (recovery_attempt_number BETWEEN 1 AND 12),
    observed_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(64) NOT NULL CHECK (outcome IN ('INDETERMINATE', 'SOURCE_UNAVAILABLE', 'COMMITTED', 'NOT_EXECUTED_AFTER_AUTHORIZATION', 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION')),
    canonical_readback BYTEA CHECK (octet_length(canonical_readback) BETWEEN 1 AND 1048576),
    readback_digest CHAR(64) CHECK (readback_digest ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (tenant_uuid, request_id, admission_id, admission_attempt_id, recovery_claim_fence),
    UNIQUE (tenant_uuid, request_id, admission_id, admission_attempt_id, recovery_attempt_number),
    FOREIGN KEY (tenant_uuid, request_id, admission_id) REFERENCES account_tenant_capacity_reservations(tenant_uuid, request_id, admission_id),
    CHECK ((canonical_readback IS NULL) = (readback_digest IS NULL)),
    CHECK (outcome NOT IN ('COMMITTED', 'NOT_EXECUTED_AFTER_AUTHORIZATION') OR canonical_readback IS NOT NULL)
);

-- [jooq ignore start]
-- Only a genuinely new same-transaction fresh tenant completion can establish zero usage.
-- Replaying/importing a previously committed tenant cannot turn unknown usage into zero.
CREATE FUNCTION account_tenant_capacity_bootstrap_new_tenant() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE birth account_fresh_tenant_identity_associations%ROWTYPE;
BEGIN
    IF OLD.status = 'IN_PROGRESS' AND NEW.status = 'COMMITTED' THEN
        SELECT * INTO birth FROM account_fresh_tenant_identity_associations
            WHERE canonical_tenant_id = NEW.tenant_uuid FOR SHARE;
        IF birth.capacity_birth_transaction IS DISTINCT FROM pg_current_xact_id()::TEXT THEN
            RETURN NEW;
        END IF;
        IF birth.operation_id IS DISTINCT FROM NEW.creation_operation_id
            OR birth.creation_request_id IS DISTINCT FROM NEW.creation_request_id
            OR NEW.baseline_event_sequence <> 0
            OR NEW.gameplay_admission_allowed IS DISTINCT FROM FALSE
            OR EXISTS (SELECT 1 FROM account_tenant_membership
                WHERE tenant_uuid = NEW.tenant_uuid AND gameplay_admission_allowed IS TRUE)
            OR EXISTS (SELECT 1 FROM account_tenant_capacity_usage WHERE tenant_uuid = NEW.tenant_uuid) THEN
            RAISE EXCEPTION 'Fresh tenant capacity baseline is not demonstrably empty' USING ERRCODE = '23514';
        END IF;
        INSERT INTO account_tenant_capacity_usage
            (tenant_uuid, owner_service, bootstrap_request_id, creation_evidence_digest, held_units, used_units, usage_revision)
        VALUES (NEW.tenant_uuid, 'game-session-service', NEW.request_id, birth.evidence_digest, 0, 0, 1),
            (NEW.tenant_uuid, 'world-management-service', NEW.request_id, birth.evidence_digest, 0, 0, 1);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_tenant_capacity_fresh_bootstrap AFTER UPDATE ON account_tenant_creation_bootstrap_operations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_bootstrap_new_tenant();

CREATE FUNCTION account_tenant_capacity_immutable_evaluation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Capacity evaluation and historical attempt evidence is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER account_tenant_capacity_evaluation_guard BEFORE UPDATE OR DELETE ON account_tenant_capacity_evaluations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_immutable_evaluation();
CREATE TRIGGER account_tenant_capacity_history_guard BEFORE UPDATE OR DELETE ON account_tenant_capacity_attempt_history
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_immutable_evaluation();
CREATE TRIGGER account_tenant_capacity_recovery_evidence_guard BEFORE UPDATE OR DELETE ON account_tenant_capacity_recovery_evidence
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_immutable_evaluation();

CREATE FUNCTION account_tenant_capacity_usage_insert_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.held_units <> 0 OR NEW.used_units <> 0 OR NEW.usage_revision <> 1 OR NOT EXISTS (
        SELECT 1 FROM account_tenant_creation_bootstrap_operations bootstrap
        JOIN account_fresh_tenant_identity_associations birth ON birth.canonical_tenant_id = bootstrap.tenant_uuid
        WHERE bootstrap.request_id = NEW.bootstrap_request_id AND bootstrap.tenant_uuid = NEW.tenant_uuid
          AND bootstrap.status = 'COMMITTED' AND bootstrap.gameplay_admission_allowed IS FALSE
          AND bootstrap.baseline_event_sequence = 0
          AND bootstrap.creation_operation_id = birth.operation_id
          AND birth.evidence_digest = NEW.creation_evidence_digest
          AND birth.capacity_birth_transaction = pg_current_xact_id()::TEXT) THEN
        RAISE EXCEPTION 'Capacity usage initialization requires same-transaction new-tenant bootstrap' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_tenant_capacity_usage_birth BEFORE INSERT ON account_tenant_capacity_usage
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_usage_insert_guard();

CREATE FUNCTION account_tenant_capacity_usage_update_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid OR NEW.owner_service IS DISTINCT FROM OLD.owner_service
        OR NEW.bootstrap_request_id IS DISTINCT FROM OLD.bootstrap_request_id
        OR NEW.creation_evidence_digest IS DISTINCT FROM OLD.creation_evidence_digest
        OR NEW.usage_revision <> OLD.usage_revision + 1 THEN
        RAISE EXCEPTION 'Capacity usage identity is immutable and revision must advance once' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_tenant_capacity_usage_revision BEFORE UPDATE ON account_tenant_capacity_usage
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_usage_update_guard();

CREATE FUNCTION account_tenant_capacity_reservation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM account_tenant_capacity_evaluations evaluation
        WHERE evaluation.evaluation_identity = NEW.evaluation_identity
          AND evaluation.tenant_uuid = NEW.tenant_uuid AND evaluation.caller_workload = NEW.caller_workload
          AND evaluation.owner_service = NEW.owner_service) THEN
        RAISE EXCEPTION 'Capacity reservation lacks its exact retained Account evaluation' USING ERRCODE = '23514';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.admission_attempt_id IS DISTINCT FROM OLD.admission_attempt_id)
        AND EXISTS (SELECT 1 FROM account_tenant_capacity_attempt_history WHERE admission_attempt_id = NEW.admission_attempt_id) THEN
        RAISE EXCEPTION 'Capacity admission attempt identity must be fresh' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'RESERVED' OR NEW.execution_status <> 'OWNER_EXECUTION_PENDING'
            OR NEW.capacity_rearm_fence <> 1 OR NEW.reservation_fence <> 1
            OR NEW.recovery_owner IS NOT NULL OR NEW.recovery_claim_fence <> 0 OR NEW.owner_readback IS NOT NULL
            OR NEW.recovery_attempt_count <> 0 OR NEW.recovery_escalated_at IS NOT NULL THEN
            RAISE EXCEPTION 'Capacity reservation must begin as the original pending attempt' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.admission_id IS DISTINCT FROM OLD.admission_id OR NEW.reservation_id IS DISTINCT FROM OLD.reservation_id
        OR NEW.owner_service IS DISTINCT FROM OLD.owner_service OR NEW.caller_workload IS DISTINCT FROM OLD.caller_workload
        OR NEW.capacity_delta IS DISTINCT FROM OLD.capacity_delta OR NEW.recovery_claim_fence < OLD.recovery_claim_fence
        OR NEW.recovery_policy_version IS DISTINCT FROM OLD.recovery_policy_version THEN
        RAISE EXCEPTION 'Capacity reservation identity and hold are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.recovery_owner IS DISTINCT FROM OLD.recovery_owner
        OR NEW.recovery_claim_expires_at IS DISTINCT FROM OLD.recovery_claim_expires_at
        OR NEW.recovery_claim_fence <> OLD.recovery_claim_fence THEN
        IF NEW.recovery_claim_fence <> OLD.recovery_claim_fence + 1 OR NEW.recovery_owner IS NULL
            OR NEW.recovery_claim_expires_at <= clock_timestamp()
            OR (OLD.recovery_owner IS DISTINCT FROM NEW.recovery_owner
                AND OLD.recovery_claim_expires_at > clock_timestamp()) THEN
            RAISE EXCEPTION 'Capacity recovery claim must monotonically fence its exact current owner' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.admission_attempt_id IS DISTINCT FROM OLD.admission_attempt_id THEN
        IF OLD.state <> 'RESERVED' OR OLD.execution_status <> 'NOT_EXECUTED_AFTER_AUTHORIZATION'
            OR OLD.owner_readback IS NULL OR OLD.recovery_owner IS NULL
            OR OLD.recovery_claim_expires_at <= clock_timestamp()
            OR NEW.recovery_owner IS DISTINCT FROM OLD.recovery_owner
            OR NEW.recovery_claim_fence <> OLD.recovery_claim_fence
            OR NEW.capacity_rearm_fence <> OLD.capacity_rearm_fence + 1
            OR NEW.reservation_fence <> OLD.reservation_fence + 1
            OR NEW.state <> 'RESERVED' OR NEW.execution_status <> 'OWNER_EXECUTION_PENDING'
            OR NEW.recovery_attempt_count <> 0 OR NEW.recovery_escalated_at IS NOT NULL
            OR NEW.owner_readback IS NOT NULL OR NEW.owner_readback_digest IS NOT NULL
            OR NOT EXISTS (SELECT 1 FROM account_tenant_capacity_attempt_history history
                WHERE history.tenant_uuid = OLD.tenant_uuid AND history.request_id = OLD.request_id
                  AND history.admission_id = OLD.admission_id AND history.admission_attempt_id = OLD.admission_attempt_id
                  AND history.capacity_rearm_fence = OLD.capacity_rearm_fence
                  AND history.mutation_digest = OLD.mutation_digest AND history.canonical_payload = OLD.canonical_payload
                  AND history.owner_readback = OLD.owner_readback) THEN
            RAISE EXCEPTION 'Capacity rearm requires exact durable terminal no-commit and current recovery claim' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.capacity_rearm_fence <> OLD.capacity_rearm_fence
        OR NEW.mutation_digest IS DISTINCT FROM OLD.mutation_digest OR NEW.canonical_payload IS DISTINCT FROM OLD.canonical_payload
        OR NEW.evaluation_identity IS DISTINCT FROM OLD.evaluation_identity OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
        OR NEW.reconcile_deadline IS DISTINCT FROM OLD.reconcile_deadline THEN
        RAISE EXCEPTION 'Changed capacity attempt is an idempotency conflict' USING ERRCODE = '23514';
    END IF;
    IF OLD.state <> 'RESERVED' THEN
        IF NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'Terminal capacity result is immutable' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.recovery_attempt_count <> OLD.recovery_attempt_count THEN
        IF NEW.recovery_attempt_count <> OLD.recovery_attempt_count + 1
            OR NEW.recovery_owner IS NULL OR NEW.recovery_claim_expires_at <= clock_timestamp()
            OR NOT EXISTS (SELECT 1 FROM account_tenant_capacity_recovery_evidence evidence
                WHERE evidence.tenant_uuid = OLD.tenant_uuid AND evidence.request_id = OLD.request_id
                  AND evidence.admission_id = OLD.admission_id AND evidence.admission_attempt_id = OLD.admission_attempt_id
                  AND evidence.recovery_claim_fence = NEW.recovery_claim_fence
                  AND evidence.recovery_attempt_number = NEW.recovery_attempt_count) THEN
            RAISE EXCEPTION 'Capacity recovery must retain each bounded observation under its current claim' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.state = 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION' THEN
        IF OLD.execution_status <> 'OWNER_EXECUTION_PENDING'
            OR (NEW.recovery_attempt_count < 12 AND clock_timestamp() < OLD.reconcile_deadline)
            OR NEW.recovery_escalated_at IS NULL
            OR NEW.next_reconcile_at IS NOT NULL THEN
            RAISE EXCEPTION 'Capacity quarantine requires exhausted bounded recovery and preserves the original hold' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.execution_status = 'NOT_EXECUTED_AFTER_AUTHORIZATION'
        AND (NEW.execution_status <> OLD.execution_status OR NEW.owner_readback IS DISTINCT FROM OLD.owner_readback
            OR NEW.owner_readback_digest IS DISTINCT FROM OLD.owner_readback_digest) THEN
        RAISE EXCEPTION 'Terminal capacity no-commit evidence cannot be replaced' USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> OLD.state OR NEW.execution_status <> OLD.execution_status THEN
        IF NEW.recovery_owner IS NULL OR NEW.recovery_claim_fence = 0
            OR NEW.recovery_claim_expires_at <= clock_timestamp()
            OR (NEW.state <> 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION' AND NEW.owner_readback IS NULL) THEN
            RAISE EXCEPTION 'Only current Account recovery claim may transition capacity evidence' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF (NEW.state = 'RELEASED' AND NEW.reservation_fence <> OLD.reservation_fence + 1)
        OR (NEW.state <> 'RELEASED' AND NEW.reservation_fence <> OLD.reservation_fence) THEN
        RAISE EXCEPTION 'Capacity release must durably fence the original reservation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_tenant_capacity_reservation_fences BEFORE INSERT OR UPDATE ON account_tenant_capacity_reservations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_reservation_guard();

-- The complete durable reservation inventory, not a local issuer counter, owns usage.
CREATE FUNCTION account_tenant_capacity_exact_usage() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE held NUMERIC; used NUMERIC; actual account_tenant_capacity_usage%ROWTYPE;
BEGIN
    SELECT * INTO actual FROM account_tenant_capacity_usage
        WHERE tenant_uuid = NEW.tenant_uuid AND owner_service = NEW.owner_service FOR UPDATE;
    SELECT COALESCE(sum(capacity_delta) FILTER (WHERE state IN ('RESERVED', 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION')), 0),
           COALESCE(sum(capacity_delta) FILTER (WHERE state = 'FINALIZED'), 0)
      INTO held, used FROM account_tenant_capacity_reservations
      WHERE tenant_uuid = NEW.tenant_uuid AND owner_service = NEW.owner_service;
    IF actual.tenant_uuid IS NULL OR held <> actual.held_units OR used <> actual.used_units THEN
        RAISE EXCEPTION 'Capacity usage differs from its complete durable reservation inventory' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_tenant_capacity_usage_complete AFTER INSERT OR UPDATE ON account_tenant_capacity_usage
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_exact_usage();
CREATE CONSTRAINT TRIGGER account_tenant_capacity_reservation_complete AFTER INSERT OR UPDATE ON account_tenant_capacity_reservations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_exact_usage();
CREATE TRIGGER account_tenant_capacity_usage_no_delete BEFORE DELETE ON account_tenant_capacity_usage
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_immutable_evaluation();
CREATE TRIGGER account_tenant_capacity_reservation_no_delete BEFORE DELETE ON account_tenant_capacity_reservations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_immutable_evaluation();
CREATE TRIGGER account_tenant_capacity_usage_no_truncate BEFORE TRUNCATE ON account_tenant_capacity_usage
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_capacity_immutable_evaluation();
CREATE TRIGGER account_tenant_capacity_reservation_no_truncate BEFORE TRUNCATE ON account_tenant_capacity_reservations
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_capacity_immutable_evaluation();
CREATE TRIGGER account_tenant_capacity_evaluation_no_truncate BEFORE TRUNCATE ON account_tenant_capacity_evaluations
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_capacity_immutable_evaluation();
CREATE TRIGGER account_tenant_capacity_history_no_truncate BEFORE TRUNCATE ON account_tenant_capacity_attempt_history
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_capacity_immutable_evaluation();
CREATE TRIGGER account_tenant_capacity_recovery_evidence_no_truncate BEFORE TRUNCATE ON account_tenant_capacity_recovery_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_capacity_immutable_evaluation();
-- [jooq ignore stop]
