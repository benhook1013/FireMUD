-- Separate remote dispatch from renewable worker attempts. Presence/replay is never permission.
CREATE TABLE subscription_provider_dispatch_claim (
    creation_request_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL UNIQUE,
    provider_operation_id UUID NOT NULL UNIQUE,
    dispatch_id UUID NOT NULL UNIQUE CHECK (dispatch_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    classification VARCHAR(24) NOT NULL CHECK (classification IN ('FIRST_DISPATCH', 'PREEXISTING_UNKNOWN')),
    first_attempt_id UUID,
    first_claimed_row_version BIGINT,
    recorded_at TIMESTAMPTZ NOT NULL,
    CHECK ((first_attempt_id IS NULL) = (first_claimed_row_version IS NULL)),
    CHECK (classification <> 'FIRST_DISPATCH' OR first_attempt_id IS NOT NULL),
    FOREIGN KEY (creation_request_id, subscription_id)
        REFERENCES subscription_create_operations(request_id, subscription_id) ON DELETE RESTRICT,
    FOREIGN KEY (creation_request_id, provider_operation_id)
        REFERENCES subscription_create_operations(request_id, subscription_provisioning_request_id) ON DELETE RESTRICT,
    FOREIGN KEY (first_attempt_id, creation_request_id, subscription_id, first_claimed_row_version)
        REFERENCES subscription_provisioning_attempts(attempt_id, request_id, subscription_id, claimed_row_version) ON DELETE RESTRICT
);

-- Existing attempt-1/work rows may already have unknown remote effects. Preserve their bytes and
-- add only a non-authorizing guard. No dispatched/absent/canceled result is inferred.
-- Work without an attempt has no dispatch history to guard.
INSERT INTO subscription_provider_dispatch_claim
    (creation_request_id, subscription_id, provider_operation_id, dispatch_id, classification,
     first_attempt_id, first_claimed_row_version, recorded_at)
SELECT w.request_id, w.subscription_id, c.subscription_provisioning_request_id,
       gen_random_uuid(), 'PREEXISTING_UNKNOWN', a.attempt_id, a.claimed_row_version, clock_timestamp()
FROM subscription_provisioning_work w
JOIN subscription_create_operations c ON c.request_id = w.request_id
JOIN subscription_provisioning_attempts a ON a.request_id = w.request_id AND a.attempt_number = 1;

-- [jooq ignore start]
CREATE FUNCTION account_subscription_provider_dispatch_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    creation subscription_create_operations%ROWTYPE;
    current_row subscription%ROWTYPE;
    work subscription_provisioning_work%ROWTYPE;
    attempt subscription_provisioning_attempts%ROWTYPE;
    instrument payment_instrument%ROWTYPE;
    customer billing_customer%ROWTYPE;
    dispatch_at TIMESTAMPTZ;
BEGIN
    SELECT * INTO STRICT creation FROM subscription_create_operations WHERE request_id = NEW.creation_request_id;
    PERFORM 1 FROM accounts WHERE account_uuid = creation.account_uuid FOR UPDATE;
    -- Match the canonical Account -> customer -> instrument acquisition order.
    SELECT * INTO STRICT customer FROM billing_customer WHERE account_uuid = creation.account_uuid FOR UPDATE;
    SELECT * INTO STRICT instrument FROM payment_instrument WHERE instrument_uuid = creation.payment_instrument_id FOR UPDATE;
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = creation.subscription_id FOR UPDATE;
    SELECT * INTO STRICT work FROM subscription_provisioning_work WHERE request_id = creation.request_id FOR UPDATE;
    SELECT * INTO STRICT attempt FROM subscription_provisioning_attempts WHERE attempt_id = NEW.first_attempt_id;
    -- Sample after every potentially blocking owner lock. A caller timestamp or transaction-start
    -- timestamp cannot prove that the original lease is still live at the dispatch boundary.
    dispatch_at := clock_timestamp();
    IF NEW.classification <> 'FIRST_DISPATCH'
        OR NEW.subscription_id <> creation.subscription_id
        OR NEW.provider_operation_id <> creation.subscription_provisioning_request_id
        OR NEW.dispatch_id IN (creation.request_id, creation.subscription_provisioning_request_id, creation.cleanup_provider_identity)
        OR NEW.first_claimed_row_version <> attempt.claimed_row_version
        OR attempt.request_id <> creation.request_id OR attempt.subscription_id <> creation.subscription_id
        OR work.phase <> 'CLAIMED' OR work.last_attempt_id <> attempt.attempt_id
        OR work.attempt_count > work.max_attempts OR work.attempt_count > work.max_stalled_attempts
        OR current_row.row_version <> attempt.claimed_row_version
        OR current_row.status <> 'provisioning' OR current_row.provider_subscription_id IS NOT NULL
        OR current_row.superseded_at IS NOT NULL OR current_row.lifecycle_inflight_request_id IS NOT NULL
        OR instrument.account_uuid <> creation.account_uuid OR instrument.state <> 'ATTACHABLE'
        OR instrument.row_version <> creation.reserved_instrument_version
        OR instrument.customer_provisioning_request_id <> creation.customer_provisioning_request_id
        OR instrument.provider_customer_id <> creation.provider_customer_id
        OR customer.state <> 'CONFIRMED' OR customer.provider_customer_id <> creation.provider_customer_id
        OR customer.customer_provisioning_request_id <> creation.customer_provisioning_request_id
        OR dispatch_at < attempt.claimed_at OR dispatch_at >= attempt.expires_at
        OR dispatch_at >= work.deadline_at THEN
        RAISE EXCEPTION 'Provider dispatch lacks exact live original provisioning fence' USING ERRCODE = '23514';
    END IF;
    NEW.recorded_at := dispatch_at;
    RETURN NEW;
END;
$$;
CREATE TRIGGER subscription_provider_dispatch_insert_guard
    BEFORE INSERT ON subscription_provider_dispatch_claim
    FOR EACH ROW EXECUTE FUNCTION account_subscription_provider_dispatch_guard();
CREATE TRIGGER subscription_provider_dispatch_immutable
    BEFORE UPDATE OR DELETE ON subscription_provider_dispatch_claim
    FOR EACH ROW EXECUTE FUNCTION account_billing_prerequisite_no_erasure();
CREATE TRIGGER subscription_provider_dispatch_no_truncate
    BEFORE TRUNCATE ON subscription_provider_dispatch_claim
    FOR EACH STATEMENT EXECUTE FUNCTION account_billing_prerequisite_no_erasure();
-- [jooq ignore stop]
