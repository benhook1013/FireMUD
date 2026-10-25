CREATE TABLE entity_canonical_roster_retirement_transfers (
    observation_uuid UUID PRIMARY KEY CHECK (observation_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    operation_uuid UUID NOT NULL UNIQUE,
    original_request BYTEA NOT NULL CHECK (octet_length(original_request) BETWEEN 1 AND 4096),
    admission_uuid UUID NOT NULL CHECK (admission_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    snapshot_uuid UUID,
    snapshot_digest VARCHAR(64),
    closed_admission_receipt BYTEA CHECK (octet_length(closed_admission_receipt) BETWEEN 1 AND 131072),
    owner_fence BIGINT NOT NULL CHECK (owner_fence > 0),
    target_fence_uuid UUID NOT NULL,
    minimum_retention_millis BIGINT NOT NULL CHECK (minimum_retention_millis > 0),
    transfer_state VARCHAR(24) NOT NULL CHECK (transfer_state IN ('WAITING_RETIREMENT', 'PENDING_ACK', 'ACKNOWLEDGED', 'CONFIRMED')),
    observed_retirement BYTEA CHECK (octet_length(observed_retirement) BETWEEN 1 AND 131072),
    acknowledgement_request BYTEA CHECK (octet_length(acknowledgement_request) BETWEEN 1 AND 131072),
    request_digest VARCHAR(64) CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    acknowledgement_response BYTEA CHECK (octet_length(acknowledgement_response) BETWEEN 1 AND 4096),
    effective_minimum_retention_millis BIGINT CHECK (effective_minimum_retention_millis >= minimum_retention_millis),
    confirmation_response BYTEA CHECK (octet_length(confirmation_response) BETWEEN 1 AND 4096),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    acknowledged_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    CHECK ((snapshot_uuid IS NULL AND snapshot_digest IS NULL AND closed_admission_receipt IS NOT NULL)
        OR (snapshot_uuid IS NOT NULL AND snapshot_digest IS NOT NULL AND snapshot_digest ~ '^[0-9a-f]{64}$')),
    CHECK ((transfer_state = 'WAITING_RETIREMENT' AND observed_retirement IS NULL AND acknowledgement_request IS NULL AND request_digest IS NULL)
        OR (transfer_state <> 'WAITING_RETIREMENT' AND observed_retirement IS NOT NULL AND acknowledgement_request IS NOT NULL AND request_digest IS NOT NULL)),
    CHECK ((transfer_state IN ('WAITING_RETIREMENT', 'PENDING_ACK') AND acknowledgement_response IS NULL AND effective_minimum_retention_millis IS NULL AND acknowledged_at IS NULL)
        OR (transfer_state IN ('ACKNOWLEDGED', 'CONFIRMED') AND acknowledgement_response IS NOT NULL AND effective_minimum_retention_millis IS NOT NULL AND acknowledged_at IS NOT NULL)),
    CHECK ((transfer_state <> 'CONFIRMED' AND confirmation_response IS NULL AND confirmed_at IS NULL)
        OR (transfer_state = 'CONFIRMED' AND confirmation_response IS NOT NULL AND confirmed_at IS NOT NULL))
);

/* [jooq ignore start] */
CREATE FUNCTION guard_entity_roster_retirement_transfer()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.recorded_at := clock_timestamp();
        IF NEW.transfer_state NOT IN ('WAITING_RETIREMENT', 'PENDING_ACK') OR NOT EXISTS (
            SELECT 1 FROM entity_canonical_roster_admissions a
            WHERE a.operation_uuid = NEW.operation_uuid AND a.admission_uuid = NEW.admission_uuid
              AND a.snapshot_uuid IS NOT DISTINCT FROM NEW.snapshot_uuid
              AND ((NEW.snapshot_uuid IS NULL AND a.admission_state = 'CLOSED'
                    AND a.recovery_response = NEW.closed_admission_receipt
                    AND a.recovery_owner_request = NEW.original_request
                    AND a.recovery_owner_fence = NEW.owner_fence
                    AND a.recovery_target_fence_uuid = NEW.target_fence_uuid
                    AND a.minimum_retention_millis = NEW.minimum_retention_millis)
                OR (NEW.snapshot_uuid IS NOT NULL AND NEW.transfer_state = 'PENDING_ACK' AND EXISTS (
                    SELECT 1 FROM entity_canonical_roster_original_selections o
                    JOIN entity_canonical_roster_retention_lifecycle l USING (snapshot_uuid, snapshot_digest)
                    WHERE o.operation_uuid = NEW.operation_uuid AND o.owner_request = NEW.original_request
                      AND o.snapshot_uuid = NEW.snapshot_uuid AND o.snapshot_digest = NEW.snapshot_digest
                      AND o.owner_fence = NEW.owner_fence AND o.owner_target_fence_uuid = NEW.target_fence_uuid
                      AND l.lifecycle_state = 'RETIRED' AND l.owner_retirement_proof = NEW.observed_retirement
                      AND l.minimum_retention_millis = NEW.minimum_retention_millis)))) THEN
            RAISE EXCEPTION 'retirement transfer requires exact locally observed owner lineage' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.transfer_state = 'CONFIRMED' AND OLD.confirmed_at
            + GREATEST(OLD.minimum_retention_millis, OLD.effective_minimum_retention_millis) * interval '1 millisecond' <= clock_timestamp()
            AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_original_selections o WHERE o.operation_uuid = OLD.operation_uuid)
            AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a WHERE a.operation_uuid = OLD.operation_uuid) THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'retirement observation transfer remains protected' USING ERRCODE = '23514';
    END IF;
    IF OLD.transfer_state = 'PENDING_ACK' AND NEW.transfer_state = 'ACKNOWLEDGED' THEN
        NEW.acknowledged_at := clock_timestamp();
    END IF;
    IF OLD.transfer_state = 'ACKNOWLEDGED' AND NEW.transfer_state = 'CONFIRMED' THEN
        NEW.confirmed_at := clock_timestamp();
    END IF;
    IF ROW(NEW.observation_uuid, NEW.operation_uuid, NEW.original_request, NEW.admission_uuid,
        NEW.snapshot_uuid, NEW.snapshot_digest, NEW.closed_admission_receipt, NEW.owner_fence,
        NEW.target_fence_uuid, NEW.minimum_retention_millis, NEW.recorded_at)
        IS DISTINCT FROM ROW(OLD.observation_uuid, OLD.operation_uuid, OLD.original_request, OLD.admission_uuid,
        OLD.snapshot_uuid, OLD.snapshot_digest, OLD.closed_admission_receipt, OLD.owner_fence,
        OLD.target_fence_uuid, OLD.minimum_retention_millis, OLD.recorded_at)
        OR NOT ((OLD.transfer_state = 'WAITING_RETIREMENT' AND NEW.transfer_state = 'PENDING_ACK')
            OR (OLD.transfer_state = 'PENDING_ACK' AND NEW.transfer_state = 'ACKNOWLEDGED')
            OR (OLD.transfer_state = 'ACKNOWLEDGED' AND NEW.transfer_state = 'CONFIRMED'))
        OR (OLD.transfer_state <> 'WAITING_RETIREMENT' AND ROW(NEW.observed_retirement, NEW.acknowledgement_request, NEW.request_digest)
            IS DISTINCT FROM ROW(OLD.observed_retirement, OLD.acknowledgement_request, OLD.request_digest))
        OR (OLD.transfer_state = 'ACKNOWLEDGED' AND ROW(NEW.acknowledgement_response, NEW.effective_minimum_retention_millis, NEW.acknowledged_at)
            IS DISTINCT FROM ROW(OLD.acknowledgement_response, OLD.effective_minimum_retention_millis, OLD.acknowledged_at))
        OR (NEW.acknowledged_at IS NOT NULL AND (NEW.acknowledged_at < NEW.recorded_at OR NEW.acknowledged_at > clock_timestamp()))
        OR (NEW.confirmed_at IS NOT NULL AND (NEW.confirmed_at < NEW.acknowledged_at OR NEW.confirmed_at > clock_timestamp())) THEN
        RAISE EXCEPTION 'retirement transfer identity or monotonic state mismatch' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_retirement_transfer_guard
    BEFORE INSERT OR UPDATE OR DELETE ON entity_canonical_roster_retirement_transfers
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_retirement_transfer();
CREATE TRIGGER trg_entity_roster_retirement_transfer_no_truncate
    BEFORE TRUNCATE ON entity_canonical_roster_retirement_transfers
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entity_assignment_history_removal();

CREATE FUNCTION guard_entity_roster_transferred_admission()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.admission_state = 'CLOSED' AND OLD.snapshot_uuid IS NULL AND NOT EXISTS (
            SELECT 1 FROM entity_canonical_roster_retirement_transfers t
            WHERE t.operation_uuid = OLD.operation_uuid AND t.admission_uuid = OLD.admission_uuid
              AND t.original_request = OLD.recovery_owner_request
              AND t.closed_admission_receipt = OLD.recovery_response
              AND t.owner_fence = OLD.recovery_owner_fence
              AND t.target_fence_uuid = OLD.recovery_target_fence_uuid) THEN
            RAISE EXCEPTION 'closed original requires durable retirement transfer before removal' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.admission_state = 'OPEN' AND EXISTS (
        SELECT 1 FROM entity_canonical_roster_retirement_transfers t WHERE t.operation_uuid = NEW.operation_uuid)
        AND (TG_OP = 'UPDATE' OR NOT EXISTS (
            SELECT 1 FROM entity_canonical_roster_admissions a WHERE a.operation_uuid = NEW.operation_uuid)) THEN
        RAISE EXCEPTION 'retirement observation fences the original admission' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_entity_roster_transferred_admission_guard
    BEFORE INSERT OR UPDATE OR DELETE ON entity_canonical_roster_admissions
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_transferred_admission();
CREATE FUNCTION guard_entity_roster_original_retirement_transfer()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM entity_canonical_roster_retirement_transfers t
        WHERE t.operation_uuid = OLD.operation_uuid AND t.original_request = OLD.owner_request
          AND t.snapshot_uuid = OLD.snapshot_uuid AND t.snapshot_digest = OLD.snapshot_digest
          AND t.owner_fence = OLD.owner_fence AND t.target_fence_uuid = OLD.owner_target_fence_uuid
          AND t.transfer_state <> 'WAITING_RETIREMENT') THEN
        RAISE EXCEPTION 'bound original requires durable retirement transfer before removal' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER trg_entity_roster_original_retirement_transfer_guard
    BEFORE DELETE ON entity_canonical_roster_original_selections
    FOR EACH ROW EXECUTE FUNCTION guard_entity_roster_original_retirement_transfer();
/* [jooq ignore stop] */
