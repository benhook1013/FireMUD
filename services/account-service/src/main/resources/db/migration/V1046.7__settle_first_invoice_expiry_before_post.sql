-- A claimed confirmation may be canceled only when its winning invocation retained an
-- independently committed expiry-before-post marker and an exact later terminal Stripe read.
ALTER TABLE subscription_first_invoice_expired_before_post
    ADD COLUMN created_transaction_id BIGINT NOT NULL DEFAULT txid_current()
        CHECK (created_transaction_id > 0);

CREATE INDEX idx_stripe_event_inbox_first_invoice_success_mode_created
    ON stripe_event_inbox (livemode, provider_created_at_epoch_seconds)
    WHERE event_type = 'payment_intent.succeeded';

CREATE TABLE subscription_first_invoice_no_send_cancellations (
    cancellation_request_id UUID PRIMARY KEY
        REFERENCES subscription_lifecycle_operations(request_id) ON DELETE RESTRICT,
    confirmation_request_id UUID NOT NULL UNIQUE
        REFERENCES subscription_first_invoice_expired_before_post(request_id) ON DELETE RESTRICT,
    invoice_id VARCHAR(255) NOT NULL UNIQUE
        REFERENCES subscription_first_invoices(invoice_id) ON DELETE RESTRICT,
    payment_intent_id VARCHAR(255) NOT NULL UNIQUE
        REFERENCES subscription_first_invoices(payment_intent_id) ON DELETE RESTRICT,
    read_started_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_transaction_id BIGINT NOT NULL DEFAULT txid_current() CHECK (created_transaction_id > 0),
    CHECK (cancellation_request_id <> confirmation_request_id),
    CHECK (observed_at >= read_started_at),
    FOREIGN KEY (cancellation_request_id)
        REFERENCES subscription_lifecycle_receipts(request_id) DEFERRABLE INITIALLY DEFERRED
);

-- The only inbox rows which block cancellation are an exact signed success for the retained
-- first intent, mode, customer, method, amount, currency and original consent.
-- [jooq ignore start]
CREATE FUNCTION account_first_invoice_signed_success_retained(intent_id TEXT, provider_livemode BOOLEAN)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (
        SELECT 1
        FROM stripe_event_inbox inbox
        JOIN subscription_first_invoices invoice
            ON invoice.payment_intent_id = intent_id
            AND invoice.livemode = provider_livemode
        JOIN subscription_first_invoice_command_claims claim
            ON claim.subscription_id = invoice.subscription_id
        JOIN subscription_first_invoice_expired_before_post marker
            ON marker.request_id = claim.request_id
        JOIN subscription_first_invoice_confirmations confirmation
            ON (confirmation.request_id, confirmation.subscription_id)
                = (claim.request_id, claim.subscription_id)
        WHERE inbox.event_type = 'payment_intent.succeeded'
            AND inbox.livemode = provider_livemode
            AND inbox.provider_event_id = convert_from(inbox.raw_payload, 'UTF8')::jsonb->>'id'
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->>'type' = inbox.event_type
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->>'livemode' = provider_livemode::TEXT
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->'data'->'object'->>'object' = 'payment_intent'
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->'data'->'object'->>'status' = 'succeeded'
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->'data'->'object'->>'id' = invoice.payment_intent_id
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->'data'->'object'->>'customer' = invoice.provider_customer_id
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->'data'->'object'->>'payment_method' = invoice.provider_payment_method_id
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->'data'->'object'->>'amount' = invoice.amount::TEXT
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->'data'->'object'->>'amount_received' = invoice.amount::TEXT
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->'data'->'object'->>'currency' = invoice.currency
            AND convert_from(inbox.raw_payload, 'UTF8')::jsonb->'data'->'object'->>'livemode' = provider_livemode::TEXT
            AND inbox.provider_created_at_epoch_seconds >= floor(EXTRACT(EPOCH FROM confirmation.consented_at))
    );
$$;

-- Intake and terminal release serialize on the immutable subscription row. If intake wins, the
-- cancellation journal observes the retained success and refuses. If cancellation wins, intake
-- remains available for the separate late-payment conflict journal.
CREATE FUNCTION account_first_invoice_success_intake_lock() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE event_payload JSONB; target_subscription UUID;
BEGIN
    IF TG_OP <> 'INSERT' OR NEW.event_type <> 'payment_intent.succeeded' THEN
        RETURN NEW;
    END IF;
    event_payload := convert_from(NEW.raw_payload, 'UTF8')::jsonb;
    IF event_payload->>'id' IS DISTINCT FROM NEW.provider_event_id
        OR event_payload->>'type' IS DISTINCT FROM NEW.event_type
        OR event_payload->>'livemode' IS DISTINCT FROM NEW.livemode::TEXT
        OR event_payload->'data'->'object'->>'object' IS DISTINCT FROM 'payment_intent'
        OR event_payload->'data'->'object'->>'status' IS DISTINCT FROM 'succeeded' THEN
        RETURN NEW;
    END IF;
    SELECT invoice.subscription_id INTO target_subscription
    FROM subscription_first_invoices invoice
    WHERE invoice.payment_intent_id = event_payload->'data'->'object'->>'id'
        AND invoice.livemode = NEW.livemode
        AND invoice.provider_customer_id = event_payload->'data'->'object'->>'customer'
        AND invoice.provider_payment_method_id = event_payload->'data'->'object'->>'payment_method'
        AND invoice.amount::TEXT = event_payload->'data'->'object'->>'amount'
        AND invoice.amount::TEXT = event_payload->'data'->'object'->>'amount_received'
        AND invoice.currency = event_payload->'data'->'object'->>'currency'
        AND event_payload->'data'->'object'->>'livemode' = invoice.livemode::TEXT
    LIMIT 1;
    IF target_subscription IS NOT NULL THEN
        PERFORM 1 FROM subscription WHERE subscription_id = target_subscription FOR UPDATE;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_success_intake_lock
    BEFORE INSERT ON stripe_event_inbox
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_success_intake_lock();

CREATE FUNCTION account_first_invoice_no_send_cancellation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    invoice subscription_first_invoices%ROWTYPE;
    confirmation subscription_first_invoice_confirmations%ROWTYPE;
    claim subscription_first_invoice_command_claims%ROWTYPE;
    auth subscription_first_invoice_authorizations%ROWTYPE;
    marker subscription_first_invoice_expired_before_post%ROWTYPE;
    operation subscription_lifecycle_operations%ROWTYPE;
    current_row subscription%ROWTYPE;
    creation subscription_create_operations%ROWTYPE;
    owner account_tenant_billing_owners%ROWTYPE;
    operation_payload JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'First-invoice no-send cancellations are immutable' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT invoice FROM subscription_first_invoices
        WHERE invoice_id = NEW.invoice_id AND payment_intent_id = NEW.payment_intent_id;
    SELECT * INTO STRICT claim FROM subscription_first_invoice_command_claims
        WHERE request_id = NEW.confirmation_request_id AND subscription_id = invoice.subscription_id;
    SELECT * INTO STRICT marker FROM subscription_first_invoice_expired_before_post
        WHERE request_id = NEW.confirmation_request_id;
    SELECT * INTO STRICT confirmation FROM subscription_first_invoice_confirmations
        WHERE request_id = claim.request_id AND subscription_id = claim.subscription_id;
    SELECT * INTO STRICT auth FROM subscription_first_invoice_authorizations
        WHERE authorization_request_id = claim.authorization_request_id
            AND request_id = claim.request_id AND subscription_id = claim.subscription_id;
    SELECT * INTO STRICT operation FROM subscription_lifecycle_operations
        WHERE request_id = NEW.cancellation_request_id;
    SELECT * INTO STRICT current_row FROM subscription
        WHERE subscription_id = invoice.subscription_id FOR UPDATE;
    SELECT * INTO STRICT creation FROM subscription_create_operations
        WHERE request_id = invoice.creation_request_id;
    SELECT * INTO STRICT owner FROM account_tenant_billing_owners
        WHERE tenant_uuid = invoice.tenant_id;
    operation_payload := convert_from(operation.request_payload, 'UTF8')::jsonb;

    IF NEW.created_transaction_id <> txid_current()
        OR claim.created_transaction_id = txid_current()
        OR marker.created_transaction_id = txid_current()
        OR marker.created_transaction_id = claim.created_transaction_id
        OR operation.created_transaction_id <> txid_current()
        OR operation.state <> 'PENDING'
        OR operation.provisioning_attempt_id IS NOT NULL
        OR (operation.creation_request_id, operation.subscription_id, operation.tenant_id,
            operation.provider_subscription_id)
            IS DISTINCT FROM (invoice.creation_request_id, invoice.subscription_id, invoice.tenant_id,
                invoice.provider_subscription_id)
        OR (current_row.status, current_row.account_uuid, current_row.tenant_id,
            current_row.creation_request_id, current_row.provider_subscription_id,
            current_row.provider_customer_id, current_row.payment_instrument_id,
            current_row.reserved_instrument_version, current_row.row_version,
            current_row.lifecycle_inflight_request_id, current_row.superseded_at)
            IS DISTINCT FROM ('provisioning', invoice.account_uuid, invoice.tenant_id,
                invoice.creation_request_id, invoice.provider_subscription_id,
                invoice.provider_customer_id, invoice.payment_instrument_id,
                invoice.instrument_version, operation.reserved_row_version,
                operation.request_id, NULL::timestamptz)
        OR (creation.account_uuid, creation.tenant_id, creation.owner_version, creation.owner_generation,
            creation.provider_customer_id, creation.payment_instrument_id, creation.reserved_instrument_version)
            IS DISTINCT FROM (invoice.account_uuid, invoice.tenant_id, current_row.owner_version,
                current_row.owner_generation, invoice.provider_customer_id, invoice.payment_instrument_id,
                invoice.instrument_version)
        OR (owner.owner_account_uuid, owner.owner_version, owner.owner_generation)
            IS DISTINCT FROM (invoice.account_uuid, creation.owner_version, creation.owner_generation)
        OR (confirmation.tuple_digest, confirmation.account_uuid)
            IS DISTINCT FROM (invoice.tuple_digest, invoice.account_uuid)
        OR marker.observed_at < auth.valid_until
        OR NEW.read_started_at < marker.observed_at
        OR NEW.observed_at < NEW.read_started_at
        OR NEW.observed_at > operation.created_at
        OR operation.created_at >= operation.deadline_at
        OR operation.created_at - NEW.read_started_at
            >= operation.observation_age_millis * INTERVAL '1 millisecond'
        OR operation_payload->>'schemaVersion' IS DISTINCT FROM 'account-hosting-unpaid-first-invoice-cancellation/v1'
        OR operation_payload->>'requestId' IS DISTINCT FROM operation.request_id::TEXT
        OR operation_payload->>'creationRequestId' IS DISTINCT FROM invoice.creation_request_id::TEXT
        OR operation_payload->>'expectedRowVersion' IS DISTINCT FROM operation.expected_row_version::TEXT
        OR operation_payload->>'providerSubscriptionId' IS DISTINCT FROM invoice.provider_subscription_id
        OR operation_payload->>'provisioningAttemptId' IS DISTINCT FROM ''
        OR account_first_invoice_signed_success_retained(invoice.payment_intent_id, invoice.livemode)
        OR EXISTS (SELECT 1 FROM subscription_first_invoice_success_evidence
            WHERE subscription_id = invoice.subscription_id)
        OR EXISTS (SELECT 1 FROM subscription_first_invoice_completions
            WHERE subscription_id = invoice.subscription_id) THEN
        RAISE EXCEPTION 'Claimed first-invoice cancellation requires an exact expired no-send claim and fresh terminal read' USING ERRCODE = '23514';
    END IF;
    NEW.created_transaction_id := txid_current();
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_no_send_cancellation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON subscription_first_invoice_no_send_cancellations
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_no_send_cancellation_guard();

CREATE FUNCTION account_first_invoice_no_send_cancellation_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM subscription_first_invoice_no_send_cancellations release
        JOIN subscription_first_invoices invoice
            ON invoice.invoice_id = release.invoice_id
            AND invoice.payment_intent_id = release.payment_intent_id
        JOIN subscription_first_invoice_command_claims claim
            ON claim.request_id = release.confirmation_request_id
            AND claim.subscription_id = invoice.subscription_id
        JOIN subscription_first_invoice_expired_before_post marker
            ON marker.request_id = claim.request_id
        JOIN subscription_first_invoice_authorizations auth
            ON (auth.authorization_request_id, auth.request_id, auth.subscription_id)
                = (claim.authorization_request_id, claim.request_id, claim.subscription_id)
        JOIN subscription_lifecycle_operations operation
            ON operation.request_id = release.cancellation_request_id
            AND operation.subscription_id = invoice.subscription_id
        JOIN subscription_lifecycle_receipts receipt
            ON receipt.request_id = operation.request_id
            AND receipt.subscription_id = operation.subscription_id
        JOIN subscription_lifecycle_observations observation
            ON (observation.request_id, observation.attempt_id, observation.evidence_digest)
                = (receipt.request_id, receipt.attempt_id, receipt.observation_digest)
        JOIN subscription_lifecycle_attempts attempt
            ON (attempt.request_id, attempt.attempt_id) = (receipt.request_id, receipt.attempt_id)
        JOIN subscription_provider_terminal_resolutions terminal
            ON terminal.subscription_id = invoice.subscription_id
        JOIN subscription_instrument_reservations reservation
            ON reservation.subscription_id = invoice.subscription_id
        JOIN subscription current_row
            ON current_row.subscription_id = invoice.subscription_id
        JOIN subscription_create_operations creation
            ON creation.request_id = invoice.creation_request_id
        JOIN account_tenant_billing_owners owner
            ON owner.tenant_uuid = invoice.tenant_id
        WHERE release.cancellation_request_id = NEW.cancellation_request_id
            AND release.confirmation_request_id = NEW.confirmation_request_id
            AND release.invoice_id = NEW.invoice_id
            AND release.payment_intent_id = NEW.payment_intent_id
            AND release.created_transaction_id = txid_current()
            AND claim.created_transaction_id <> marker.created_transaction_id
            AND marker.created_transaction_id <> release.created_transaction_id
            AND marker.observed_at >= auth.valid_until
            AND release.read_started_at >= marker.observed_at
            AND release.read_started_at >= observation.observed_at
            AND release.read_started_at >= attempt.started_at
            AND release.observed_at >= release.read_started_at
            AND release.observed_at <= operation.created_at
            AND release.observed_at < attempt.expires_at
            AND operation.created_at < attempt.expires_at
            AND operation.created_at - release.read_started_at
                < operation.observation_age_millis * INTERVAL '1 millisecond'
            AND operation.state = 'COMMITTED'
            AND operation.created_transaction_id = release.created_transaction_id
            AND operation.creation_request_id = invoice.creation_request_id
            AND operation.tenant_id = invoice.tenant_id
            AND operation.provider_subscription_id = invoice.provider_subscription_id
            AND operation.provisioning_attempt_id IS NULL
            AND operation.request_digest = receipt.request_digest
            AND receipt.committed_transaction_id = release.created_transaction_id
            AND receipt.status = 'canceled'
            AND receipt.tenant_id = invoice.tenant_id
            AND receipt.provider_subscription_id = invoice.provider_subscription_id
            AND receipt.plan_quote_digest = creation.plan_quote_digest
            AND observation.raw_status = 'canceled'
            AND terminal.request_id = invoice.creation_request_id
            AND terminal.outcome = 'CANCELED'
            AND terminal.provider_subscription_id = invoice.provider_subscription_id
            AND terminal.evidence_digest = observation.evidence_digest
            AND reservation.request_id = invoice.creation_request_id
            AND reservation.terminal_resolution_id = terminal.resolution_id
            AND reservation.released_at IS NOT NULL
            AND current_row.status = 'canceled'
            AND current_row.current_receipt_request_id = operation.request_id
            AND current_row.lifecycle_inflight_request_id IS NULL
            AND current_row.row_version = receipt.subscription_row_version
            AND current_row.provider_subscription_id = invoice.provider_subscription_id
            AND current_row.tenant_id = invoice.tenant_id
            AND current_row.account_uuid = invoice.account_uuid
            AND current_row.superseded_at IS NULL
            AND (owner.owner_account_uuid, owner.owner_version, owner.owner_generation)
                = (invoice.account_uuid, creation.owner_version, creation.owner_generation)
            AND (current_row.owner_version, current_row.owner_generation)
                = (creation.owner_version, creation.owner_generation)
            AND NOT EXISTS (SELECT 1 FROM subscription_lifecycle_operator_outbox
                WHERE request_id = operation.request_id)
            AND NOT EXISTS (SELECT 1 FROM subscription_provisioning_operator_outbox
                WHERE request_id = invoice.creation_request_id)
            AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_success_evidence
                WHERE subscription_id = invoice.subscription_id)
            AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_completions
                WHERE subscription_id = invoice.subscription_id)
            AND convert_from(operation.request_payload, 'UTF8')::jsonb->>'schemaVersion'
                = 'account-hosting-unpaid-first-invoice-cancellation/v1'
            AND convert_from(operation.request_payload, 'UTF8')::jsonb->>'requestId'
                = operation.request_id::TEXT
            AND convert_from(operation.request_payload, 'UTF8')::jsonb->>'creationRequestId'
                = invoice.creation_request_id::TEXT
            AND convert_from(operation.request_payload, 'UTF8')::jsonb->>'expectedRowVersion'
                = operation.expected_row_version::TEXT
            AND convert_from(operation.request_payload, 'UTF8')::jsonb->>'providerSubscriptionId'
                = invoice.provider_subscription_id
            AND convert_from(operation.request_payload, 'UTF8')::jsonb->>'provisioningAttemptId' = ''
    ) THEN
        RAISE EXCEPTION 'No-send cancellation lacks its exact committed lifecycle receipt and terminal release' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER first_invoice_no_send_cancellation_consistency
    AFTER INSERT ON subscription_first_invoice_no_send_cancellations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_first_invoice_no_send_cancellation_consistency();
CREATE TRIGGER first_invoice_no_send_cancellation_immutable
    BEFORE UPDATE OR DELETE ON subscription_first_invoice_no_send_cancellations
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER first_invoice_no_send_cancellation_no_truncate
    BEFORE TRUNCATE ON subscription_first_invoice_no_send_cancellations
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();

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
                AND reservation.released_at IS NOT NULL
                AND (
                    (
                        NOT EXISTS (SELECT 1 FROM subscription_first_invoice_command_claims claim
                            WHERE claim.subscription_id = work.subscription_id)
                        AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_success_evidence success
                            WHERE success.subscription_id = work.subscription_id)
                        AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_no_send_cancellations release
                            WHERE release.invoice_id = invoice.invoice_id)
                    )
                    OR
                    (
                        EXISTS (SELECT 1 FROM subscription_first_invoice_command_claims claim
                            WHERE claim.subscription_id = work.subscription_id)
                        AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_success_evidence success
                            WHERE success.subscription_id = work.subscription_id)
                        AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_completions completion
                            WHERE completion.subscription_id = work.subscription_id)
                        AND EXISTS (
                            SELECT 1
                            FROM subscription_first_invoice_no_send_cancellations release
                            JOIN subscription_first_invoice_command_claims claim
                                ON claim.request_id = release.confirmation_request_id
                                AND claim.subscription_id = invoice.subscription_id
                            JOIN subscription_first_invoice_expired_before_post marker
                                ON marker.request_id = claim.request_id
                            JOIN subscription_first_invoice_authorizations auth
                                ON (auth.authorization_request_id, auth.request_id, auth.subscription_id)
                                    = (claim.authorization_request_id, claim.request_id, claim.subscription_id)
                            JOIN subscription_lifecycle_attempts cancellation_attempt
                                ON (cancellation_attempt.request_id, cancellation_attempt.attempt_id)
                                    = (operation.request_id, observation.attempt_id)
                            WHERE release.cancellation_request_id = operation.request_id
                                AND release.confirmation_request_id = claim.request_id
                                AND release.invoice_id = invoice.invoice_id
                                AND release.payment_intent_id = invoice.payment_intent_id
                                AND release.created_transaction_id = receipt.committed_transaction_id
                                AND release.created_transaction_id = txid_current()
                                AND release.read_started_at >= observation.observed_at
                                AND release.read_started_at >= cancellation_attempt.started_at
                                AND release.observed_at >= release.read_started_at
                                AND release.observed_at <= operation.created_at
                                AND release.observed_at < cancellation_attempt.expires_at
                                AND operation.created_at < cancellation_attempt.expires_at
                                AND operation.created_at - release.read_started_at
                                    < operation.observation_age_millis * INTERVAL '1 millisecond'
                                AND claim.created_transaction_id <> marker.created_transaction_id
                                AND marker.created_transaction_id <> release.created_transaction_id
                                AND marker.observed_at >= auth.valid_until
                                AND release.read_started_at >= marker.observed_at
                        )
                    )
                )
        )) THEN
        RAISE EXCEPTION 'Canceled first-invoice provisioning requires exact terminal release and, for a claimed command, its committed no-send witness' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

-- A signed success is terminally conflicting after either the original unclaimed cancellation or
-- a claimed cancellation whose exact no-send witness was committed with that terminal receipt.
CREATE OR REPLACE FUNCTION account_unclaimed_first_invoice_success_cancellation_fence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_status VARCHAR;
BEGIN
    SELECT status INTO STRICT current_status FROM subscription
        WHERE subscription_id = NEW.subscription_id FOR UPDATE;
    IF current_status = 'canceled' AND EXISTS (
        SELECT 1 FROM subscription_provisioning_work
        WHERE subscription_id = NEW.subscription_id AND phase = 'CANCELED') THEN
        RAISE EXCEPTION 'First-invoice success conflicts with retained terminal cancellation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION account_first_invoice_late_paid_conflict_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    current_row subscription%ROWTYPE;
    first_invoice subscription_first_invoices%ROWTYPE;
    confirmation subscription_first_invoice_confirmations%ROWTYPE;
    inbox stripe_event_inbox%ROWTYPE;
    cancellation subscription_lifecycle_operations%ROWTYPE;
    receipt subscription_lifecycle_receipts%ROWTYPE;
    provisioning subscription_provisioning_work%ROWTYPE;
    paid_payload JSONB;
    cancellation_payload JSONB;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Late first-invoice payment conflicts are retained' USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        -- The intake trigger takes this same row lock before retaining a signed success. A success
        -- committed before cancellation blocks release; one committed after release reaches here.
        SELECT * INTO STRICT current_row FROM subscription WHERE subscription_id = NEW.subscription_id FOR UPDATE;
        SELECT * INTO STRICT first_invoice FROM subscription_first_invoices WHERE subscription_id = NEW.subscription_id;
        SELECT * INTO STRICT confirmation FROM subscription_first_invoice_confirmations
            WHERE request_id = NEW.request_id AND subscription_id = NEW.subscription_id;
        SELECT * INTO STRICT inbox FROM stripe_event_inbox WHERE provider_event_id = NEW.provider_event_id;

        paid_payload := convert_from(inbox.raw_payload, 'UTF8')::jsonb;
        IF current_row.status <> 'canceled'
            OR current_row.current_receipt_request_id IS NULL
            OR current_row.lifecycle_inflight_request_id IS NOT NULL
            OR current_row.provider_subscription_id IS DISTINCT FROM first_invoice.provider_subscription_id
            OR (current_row.superseded_at IS NULL AND current_row.row_version <> NEW.cancellation_row_version)
            OR (first_invoice.creation_request_id, first_invoice.tuple_digest, first_invoice.invoice_id,
                first_invoice.payment_intent_id, first_invoice.provider_subscription_id,
                first_invoice.provider_customer_id, first_invoice.provider_payment_method_id,
                first_invoice.provider_price_id, first_invoice.provider_quantity,
                first_invoice.amount, first_invoice.currency, first_invoice.livemode)
                IS DISTINCT FROM (NEW.creation_request_id, NEW.tuple_digest, NEW.invoice_id,
                    NEW.payment_intent_id, NEW.provider_subscription_id, NEW.provider_customer_id,
                    NEW.provider_payment_method_id, NEW.provider_price_id, NEW.provider_quantity,
                    NEW.amount, NEW.currency, NEW.livemode)
            OR (confirmation.lifecycle_request_id, confirmation.tuple_digest, confirmation.account_uuid)
                IS DISTINCT FROM (NEW.lifecycle_request_id, NEW.tuple_digest, first_invoice.account_uuid)
            OR inbox.event_type <> 'payment_intent.succeeded'
            OR inbox.payload_sha256 <> NEW.event_payload_digest
            OR inbox.livemode <> NEW.livemode
            OR inbox.provider_event_id IS DISTINCT FROM paid_payload->>'id'
            OR inbox.event_type IS DISTINCT FROM paid_payload->>'type'
            OR paid_payload->>'livemode' IS DISTINCT FROM NEW.livemode::TEXT
            OR paid_payload->'data'->'object'->>'object' IS DISTINCT FROM 'payment_intent'
            OR paid_payload->'data'->'object'->>'status' IS DISTINCT FROM 'succeeded'
            OR paid_payload->'data'->'object'->>'id' IS DISTINCT FROM NEW.payment_intent_id
            OR paid_payload->'data'->'object'->>'customer' IS DISTINCT FROM NEW.provider_customer_id
            OR paid_payload->'data'->'object'->>'payment_method' IS DISTINCT FROM NEW.provider_payment_method_id
            OR paid_payload->'data'->'object'->>'amount' IS DISTINCT FROM NEW.amount::TEXT
            OR paid_payload->'data'->'object'->>'amount_received' IS DISTINCT FROM NEW.amount::TEXT
            OR paid_payload->'data'->'object'->>'currency' IS DISTINCT FROM NEW.currency
            OR paid_payload->'data'->'object'->>'livemode' IS DISTINCT FROM NEW.livemode::TEXT
            OR inbox.provider_created_at_epoch_seconds < floor(EXTRACT(EPOCH FROM confirmation.consented_at))
            OR NEW.observed_status <> 'succeeded'
            OR NEW.observation_read_started_at < inbox.received_at
            OR NEW.observation_observed_at < NEW.observation_read_started_at
            OR NEW.observation_observed_at - NEW.observation_read_started_at >= INTERVAL '20 seconds'
            OR NEW.observation_expires_at <> first_invoice.expires_at
            OR NOT EXISTS (
                SELECT 1
                FROM subscription_provisioning_work work
                JOIN subscription_lifecycle_operations canceled_operation
                    ON canceled_operation.request_id = current_row.current_receipt_request_id
                    AND canceled_operation.subscription_id = current_row.subscription_id
                JOIN subscription_lifecycle_receipts canceled_receipt
                    ON canceled_receipt.request_id = canceled_operation.request_id
                    AND canceled_receipt.subscription_id = canceled_operation.subscription_id
                JOIN subscription_lifecycle_observations cancellation_observation
                    ON (cancellation_observation.request_id, cancellation_observation.attempt_id,
                        cancellation_observation.evidence_digest)
                        = (canceled_receipt.request_id, canceled_receipt.attempt_id,
                            canceled_receipt.observation_digest)
                JOIN subscription_provider_terminal_resolutions terminal
                    ON terminal.resolution_id = current_row.terminal_resolution_id
                    AND terminal.subscription_id = current_row.subscription_id
                JOIN subscription_instrument_reservations reservation
                    ON reservation.subscription_id = current_row.subscription_id
                WHERE work.request_id = first_invoice.creation_request_id
                    AND work.subscription_id = current_row.subscription_id
                    AND work.phase = 'CANCELED'
                    AND work.last_attempt_id IS NOT NULL
                    AND canceled_operation.state = 'COMMITTED'
                    AND canceled_operation.creation_request_id = first_invoice.creation_request_id
                    AND canceled_operation.tenant_id = first_invoice.tenant_id
                    AND canceled_operation.provider_subscription_id = first_invoice.provider_subscription_id
                    AND canceled_operation.provisioning_attempt_id IS NULL
                    AND canceled_operation.request_id = NEW.cancellation_request_id
                    AND canceled_operation.request_digest = canceled_receipt.request_digest
                    AND canceled_operation.request_digest = 'sha256:' || encode(sha256(canceled_operation.request_payload), 'hex')
                    AND canceled_receipt.status = 'canceled'
                    AND canceled_receipt.receipt_digest = NEW.cancellation_receipt_digest
                    AND canceled_receipt.subscription_row_version = NEW.cancellation_row_version
                    AND canceled_receipt.committed_transaction_id = NEW.cancellation_committed_transaction_id
                    AND canceled_receipt.committed_transaction_id <> txid_current()
                    AND canceled_receipt.provider_subscription_id = first_invoice.provider_subscription_id
                    AND canceled_receipt.plan_quote_digest = first_invoice.quote_digest
                    AND cancellation_observation.raw_status = 'canceled'
                    AND terminal.request_id = first_invoice.creation_request_id
                    AND terminal.outcome = 'CANCELED'
                    AND terminal.provider_subscription_id = first_invoice.provider_subscription_id
                    AND terminal.evidence_digest = cancellation_observation.evidence_digest
                    AND reservation.request_id = first_invoice.creation_request_id
                    AND reservation.terminal_resolution_id = terminal.resolution_id
                    AND reservation.released_at IS NOT NULL
                    AND convert_from(canceled_operation.request_payload, 'UTF8')::jsonb->>'schemaVersion'
                        = 'account-hosting-unpaid-first-invoice-cancellation/v1'
                    AND convert_from(canceled_operation.request_payload, 'UTF8')::jsonb->>'requestId'
                        = canceled_operation.request_id::TEXT
                    AND convert_from(canceled_operation.request_payload, 'UTF8')::jsonb->>'creationRequestId'
                        = first_invoice.creation_request_id::TEXT
                    AND convert_from(canceled_operation.request_payload, 'UTF8')::jsonb->>'expectedRowVersion'
                        = canceled_operation.expected_row_version::TEXT
                    AND convert_from(canceled_operation.request_payload, 'UTF8')::jsonb->>'providerSubscriptionId'
                        = first_invoice.provider_subscription_id
                    AND convert_from(canceled_operation.request_payload, 'UTF8')::jsonb->>'provisioningAttemptId' = ''
                    AND (current_row.superseded_at IS NOT NULL
                        OR canceled_receipt.subscription_row_version = current_row.row_version)
                    AND (
                        NOT EXISTS (SELECT 1 FROM subscription_first_invoice_command_claims claim
                            WHERE claim.subscription_id = current_row.subscription_id)
                        OR EXISTS (
                            SELECT 1
                            FROM subscription_first_invoice_no_send_cancellations release
                            JOIN subscription_first_invoice_command_claims claim
                                ON claim.request_id = release.confirmation_request_id
                                    AND claim.subscription_id = current_row.subscription_id
                            JOIN subscription_first_invoice_expired_before_post marker
                                ON marker.request_id = claim.request_id
                            JOIN subscription_first_invoice_authorizations auth
                                ON (auth.authorization_request_id, auth.request_id,
                                    auth.subscription_id)
                                    = (claim.authorization_request_id, claim.request_id, claim.subscription_id)
                            JOIN subscription_lifecycle_operations witness_operation
                                ON witness_operation.request_id = release.cancellation_request_id
                                    AND witness_operation.subscription_id = current_row.subscription_id
                            JOIN subscription_lifecycle_receipts witness_receipt
                                ON witness_receipt.request_id = witness_operation.request_id
                                    AND witness_receipt.subscription_id = witness_operation.subscription_id
                            JOIN subscription_lifecycle_observations witness_observation
                                ON (witness_observation.request_id, witness_observation.attempt_id,
                                    witness_observation.evidence_digest)
                                    = (witness_receipt.request_id, witness_receipt.attempt_id,
                                        witness_receipt.observation_digest)
                            JOIN subscription_lifecycle_attempts witness_attempt
                                ON (witness_attempt.request_id, witness_attempt.attempt_id)
                                    = (witness_operation.request_id, witness_observation.attempt_id)
                            WHERE release.cancellation_request_id = NEW.cancellation_request_id
                                AND release.confirmation_request_id = NEW.request_id
                                AND release.invoice_id = first_invoice.invoice_id
                                AND release.payment_intent_id = first_invoice.payment_intent_id
                                AND release.created_transaction_id = NEW.cancellation_committed_transaction_id
                                AND release.created_transaction_id = witness_receipt.committed_transaction_id
                                AND release.read_started_at >= witness_observation.observed_at
                                AND release.read_started_at >= witness_attempt.started_at
                                AND release.observed_at >= release.read_started_at
                                AND release.observed_at <= witness_operation.created_at
                                AND release.observed_at < witness_attempt.expires_at
                                AND witness_operation.created_at < witness_attempt.expires_at
                                AND witness_operation.created_at - release.read_started_at
                                    < witness_operation.observation_age_millis * INTERVAL '1 millisecond'
                                AND marker.created_transaction_id <> release.created_transaction_id
                                AND marker.observed_at >= auth.valid_until
                                AND claim.created_transaction_id <> marker.created_transaction_id
                                AND witness_operation.state = 'COMMITTED'
                                AND witness_receipt.status = 'canceled'
                                AND witness_observation.raw_status = 'canceled'
                        )
                    )
            )
            OR EXISTS (SELECT 1 FROM subscription_lifecycle_operator_outbox
                WHERE request_id = current_row.current_receipt_request_id)
            OR EXISTS (SELECT 1 FROM subscription_provisioning_operator_outbox
                WHERE request_id = first_invoice.creation_request_id)
            OR EXISTS (SELECT 1 FROM subscription_first_invoice_success_evidence
                WHERE subscription_id = NEW.subscription_id)
            OR EXISTS (SELECT 1 FROM subscription_first_invoice_completions
                WHERE subscription_id = NEW.subscription_id) THEN
            RAISE EXCEPTION 'Late first-invoice payment requires its exact verified success and committed unpaid cancellation' USING ERRCODE = '23514';
        END IF;
        cancellation_payload := convert_from((SELECT request_payload FROM subscription_lifecycle_operations
            WHERE request_id = NEW.cancellation_request_id), 'UTF8')::jsonb;
        IF NEW.cancellation_request_id IS DISTINCT FROM current_row.current_receipt_request_id
            OR NEW.cancellation_committed_transaction_id = txid_current()
            OR cancellation_payload->>'schemaVersion' IS DISTINCT FROM 'account-hosting-unpaid-first-invoice-cancellation/v1' THEN
            RAISE EXCEPTION 'Late first-invoice payment must retain the current committed unpaid cancellation' USING ERRCODE = '23514';
        END IF;
        NEW.created_transaction_id := txid_current();
        RETURN NEW;
    ELSE
        RAISE EXCEPTION 'Late first-invoice payment conflicts are immutable' USING ERRCODE = '23514';
    END IF;
END;
$$;
-- [jooq ignore stop]
