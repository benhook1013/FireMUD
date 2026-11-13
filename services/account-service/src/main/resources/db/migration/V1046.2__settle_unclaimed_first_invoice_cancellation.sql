-- [jooq ignore start]
ALTER TABLE subscription_provisioning_work DROP CONSTRAINT subscription_provisioning_work_phase_check;
ALTER TABLE subscription_provisioning_work ADD CONSTRAINT subscription_provisioning_work_phase_check
    CHECK (phase IN ('RECONCILING','CLAIMED','AWAITING_PAYMENT','CONFIRMED','CANCELED','BILLING_RECONCILIATION_TERMINAL_FAILURE'));

CREATE OR REPLACE FUNCTION account_subscription_provisioning_work_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Provisioning work is retained' USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.phase <> 'RECONCILING' OR NEW.attempt_count <> 0 OR NEW.row_version <> 1
            OR NEW.failure_reason IS NOT NULL OR NEW.terminal_at IS NOT NULL THEN
            RAISE EXCEPTION 'Provisioning work must start unclaimed' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF OLD.phase IN ('CONFIRMED','CANCELED','BILLING_RECONCILIATION_TERMINAL_FAILURE')
            OR (OLD.phase = 'AWAITING_PAYMENT' AND NEW.phase NOT IN ('CONFIRMED','CANCELED','BILLING_RECONCILIATION_TERMINAL_FAILURE'))
            OR (NEW.phase = 'CANCELED' AND OLD.phase <> 'AWAITING_PAYMENT')
            OR (NEW.request_id, NEW.subscription_id, NEW.max_attempts, NEW.max_stalled_attempts,
                NEW.lease_millis, NEW.backoff_millis, NEW.deadline_at)
                IS DISTINCT FROM (OLD.request_id, OLD.subscription_id, OLD.max_attempts, OLD.max_stalled_attempts,
                    OLD.lease_millis, OLD.backoff_millis, OLD.deadline_at)
            OR NEW.row_version <> OLD.row_version + 1 THEN
            RAISE EXCEPTION 'Provisioning identity, limits or terminal fence rejected' USING ERRCODE = '23514';
        END IF;
        IF NEW.phase = 'CLAIMED' THEN
            IF NEW.attempt_count <> OLD.attempt_count + 1 OR NEW.last_attempt_id IS NOT DISTINCT FROM OLD.last_attempt_id
                OR NEW.attempt_count > NEW.max_attempts OR NEW.attempt_count > NEW.max_stalled_attempts THEN
                RAISE EXCEPTION 'Provisioning claim count or attempt fence rejected' USING ERRCODE = '23514';
            END IF;
        ELSIF (NEW.attempt_count, NEW.last_attempt_id, NEW.subscription_row_version, NEW.lease_expires_at)
            IS DISTINCT FROM (OLD.attempt_count, OLD.last_attempt_id, OLD.subscription_row_version, OLD.lease_expires_at) THEN
            RAISE EXCEPTION 'Provisioning settlement cannot replace its attempt' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION account_subscription_provisioning_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    work subscription_provisioning_work%ROWTYPE;
    attempt subscription_provisioning_attempts%ROWTYPE;
    current_row subscription%ROWTYPE;
    notice subscription_provisioning_operator_outbox%ROWTYPE;
BEGIN
    SELECT * INTO STRICT work FROM subscription_provisioning_work WHERE request_id = NEW.request_id;
    SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = work.subscription_id;
    IF work.last_attempt_id IS NOT NULL THEN
        SELECT * INTO STRICT attempt FROM subscription_provisioning_attempts WHERE attempt_id = work.last_attempt_id;
        IF (attempt.request_id, attempt.subscription_id, attempt.attempt_number, attempt.claimed_row_version, attempt.expires_at)
                IS DISTINCT FROM (work.request_id, work.subscription_id, work.attempt_count, work.subscription_row_version, work.lease_expires_at)
            OR attempt.expires_at > work.deadline_at
            OR (work.phase NOT IN ('CONFIRMED','CANCELED')
                AND (current_row.status <> 'provisioning' OR current_row.row_version < attempt.claimed_row_version
                    OR current_row.superseded_at IS NOT NULL)) THEN
            RAISE EXCEPTION 'Provisioning attempt lacks its exact current CAS' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF work.phase = 'BILLING_RECONCILIATION_TERMINAL_FAILURE' THEN
        SELECT * INTO STRICT notice FROM subscription_provisioning_operator_outbox WHERE request_id = work.request_id;
        IF (notice.subscription_id, notice.reason, notice.recorded_at)
                IS DISTINCT FROM (work.subscription_id, work.failure_reason, work.terminal_at) THEN
            RAISE EXCEPTION 'Provisioning failure lacks its exact operator work item' USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM subscription_provisioning_operator_outbox WHERE request_id = work.request_id) THEN
        RAISE EXCEPTION 'Operator resolution work requires terminal reconciliation failure' USING ERRCODE = '23514';
    END IF;
    IF work.phase = 'CANCELED' AND (
        work.last_attempt_id IS NULL
        OR current_row.status <> 'canceled'
        OR NOT EXISTS (
            SELECT 1 FROM subscription_first_invoices invoice
            JOIN subscription_lifecycle_receipts receipt USING (subscription_id)
            JOIN subscription_lifecycle_operations operation USING (request_id, subscription_id)
            JOIN subscription_lifecycle_observations observation
                ON (observation.request_id, observation.attempt_id, observation.evidence_digest)
                    = (receipt.request_id, receipt.attempt_id, receipt.observation_digest)
            JOIN subscription_provider_terminal_resolutions terminal
                ON terminal.subscription_id = invoice.subscription_id
            JOIN subscription_instrument_reservations reservation
                ON reservation.subscription_id = invoice.subscription_id
            WHERE invoice.subscription_id = work.subscription_id
                AND invoice.creation_request_id = work.request_id
                AND (operation.creation_request_id, operation.provider_subscription_id)
                    = (invoice.creation_request_id, invoice.provider_subscription_id)
                AND operation.request_id = current_row.current_receipt_request_id
                AND operation.state = 'COMMITTED' AND operation.provisioning_attempt_id IS NULL
                AND convert_from(operation.request_payload, 'UTF8')::jsonb->>'schemaVersion'
                    = 'account-hosting-unpaid-first-invoice-cancellation/v1'
                AND receipt.status = 'canceled'
                AND receipt.subscription_row_version = current_row.row_version
                AND receipt.provider_subscription_id = current_row.provider_subscription_id
                AND receipt.committed_transaction_id = txid_current()
                AND observation.raw_status = 'canceled'
                AND terminal.resolution_id = current_row.terminal_resolution_id
                AND terminal.outcome = 'CANCELED'
                AND terminal.evidence_digest = observation.evidence_digest
                AND reservation.request_id = work.request_id
                AND reservation.terminal_resolution_id = terminal.resolution_id
                AND reservation.released_at IS NOT NULL)
        OR EXISTS (SELECT 1 FROM subscription_first_invoice_command_claims WHERE subscription_id = work.subscription_id)
        OR EXISTS (SELECT 1 FROM subscription_first_invoice_success_evidence WHERE subscription_id = work.subscription_id)) THEN
        RAISE EXCEPTION 'Canceled first-invoice provisioning requires exact unclaimed terminal release' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

-- Normal signed fulfillment already has a durable command claim which excludes this cancellation.
-- Serialize an unclaimed signed success on the same subscription row so it cannot race release.
CREATE FUNCTION account_unclaimed_first_invoice_success_cancellation_fence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_status VARCHAR;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM subscription_first_invoice_command_claims WHERE subscription_id = NEW.subscription_id) THEN
        SELECT status INTO STRICT current_status FROM subscription WHERE subscription_id = NEW.subscription_id FOR UPDATE;
        IF current_status = 'canceled' AND EXISTS (
            SELECT 1 FROM subscription_provisioning_work WHERE subscription_id = NEW.subscription_id AND phase = 'CANCELED') THEN
            RAISE EXCEPTION 'Unclaimed first-invoice success conflicts with retained terminal cancellation' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_unclaimed_success_cancellation_fence BEFORE INSERT ON subscription_first_invoice_success_evidence
    FOR EACH ROW EXECUTE FUNCTION account_unclaimed_first_invoice_success_cancellation_fence();
-- [jooq ignore stop]
