-- Preserve prior conflict rows while separating observation freshness from checkout expiry.
-- [jooq ignore start]
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
            OR NEW.observation_expires_at IS DISTINCT FROM NEW.observation_read_started_at + INTERVAL '20 seconds'
            OR NEW.observation_observed_at >= NEW.observation_expires_at
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
