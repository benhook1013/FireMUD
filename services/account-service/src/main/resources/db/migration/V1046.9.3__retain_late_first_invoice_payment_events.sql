-- Additional independently verified success events point to the original immutable operator
-- conflict. They do not create another conflict identity or reopen fulfillment.
CREATE TABLE subscription_first_invoice_late_paid_conflict_events (
    provider_event_id VARCHAR(255) PRIMARY KEY
        REFERENCES stripe_event_inbox(provider_event_id) ON DELETE RESTRICT,
    conflict_provider_event_id VARCHAR(255) NOT NULL
        REFERENCES subscription_first_invoice_late_paid_conflicts(provider_event_id) ON DELETE RESTRICT,
    event_payload_digest VARCHAR(71) NOT NULL
        CHECK (event_payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    provider_subscription_id VARCHAR(255) NOT NULL,
    provider_customer_id VARCHAR(255) NOT NULL,
    provider_payment_method_id VARCHAR(255) NOT NULL,
    provider_price_id VARCHAR(255) NOT NULL,
    provider_quantity BIGINT NOT NULL CHECK (provider_quantity > 0),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[a-z]{3}$'),
    livemode BOOLEAN NOT NULL,
    observed_status VARCHAR(32) NOT NULL CHECK (observed_status = 'succeeded'),
    observation_read_started_at TIMESTAMPTZ NOT NULL,
    observation_observed_at TIMESTAMPTZ NOT NULL,
    observation_expires_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    CHECK (provider_event_id <> conflict_provider_event_id),
    CHECK (observation_observed_at >= observation_read_started_at),
    CHECK (observation_expires_at > observation_read_started_at)
);
CREATE INDEX subscription_first_invoice_late_paid_conflict_events_conflict_idx
    ON subscription_first_invoice_late_paid_conflict_events(conflict_provider_event_id);

-- [jooq ignore start]
CREATE FUNCTION account_first_invoice_late_paid_conflict_event_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    retained_conflict subscription_first_invoice_late_paid_conflicts%ROWTYPE;
    current_row subscription%ROWTYPE;
    first_invoice subscription_first_invoices%ROWTYPE;
    inbox stripe_event_inbox%ROWTYPE;
    original_inbox stripe_event_inbox%ROWTYPE;
    event_payload JSONB;
    original_payload JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Late first-invoice conflict event evidence is immutable'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT retained_conflict
    FROM subscription_first_invoice_late_paid_conflicts
    WHERE provider_event_id = NEW.conflict_provider_event_id;
    SELECT * INTO STRICT current_row
    FROM subscription
    WHERE subscription_id = retained_conflict.subscription_id
    FOR UPDATE;
    SELECT * INTO STRICT first_invoice
    FROM subscription_first_invoices
    WHERE subscription_id = retained_conflict.subscription_id;
    SELECT * INTO STRICT inbox
    FROM stripe_event_inbox
    WHERE provider_event_id = NEW.provider_event_id;
    SELECT * INTO STRICT original_inbox
    FROM stripe_event_inbox
    WHERE provider_event_id = retained_conflict.provider_event_id;

    event_payload := convert_from(inbox.raw_payload, 'UTF8')::jsonb;
    original_payload := convert_from(original_inbox.raw_payload, 'UTF8')::jsonb;
    IF current_row.status <> 'canceled'
        OR current_row.current_receipt_request_id IS NULL
        OR current_row.lifecycle_inflight_request_id IS NOT NULL
        OR current_row.provider_subscription_id IS DISTINCT FROM first_invoice.provider_subscription_id
        OR (first_invoice.creation_request_id, first_invoice.tuple_digest, first_invoice.invoice_id,
            first_invoice.payment_intent_id, first_invoice.provider_subscription_id,
            first_invoice.provider_customer_id, first_invoice.provider_payment_method_id,
            first_invoice.provider_price_id, first_invoice.provider_quantity, first_invoice.amount,
            first_invoice.currency, first_invoice.livemode)
            IS DISTINCT FROM (retained_conflict.creation_request_id, retained_conflict.tuple_digest,
                retained_conflict.invoice_id, retained_conflict.payment_intent_id,
                retained_conflict.provider_subscription_id, retained_conflict.provider_customer_id,
                retained_conflict.provider_payment_method_id, retained_conflict.provider_price_id,
                retained_conflict.provider_quantity, retained_conflict.amount,
                retained_conflict.currency, retained_conflict.livemode)
        OR retained_conflict.observed_status <> 'succeeded'
        OR original_inbox.event_type <> 'payment_intent.succeeded'
        OR original_inbox.payload_sha256 <> retained_conflict.event_payload_digest
        OR original_inbox.provider_event_id IS DISTINCT FROM original_payload->>'id'
        OR original_inbox.event_type IS DISTINCT FROM original_payload->>'type'
        OR original_inbox.provider_created_at_epoch_seconds::TEXT IS DISTINCT FROM original_payload->>'created'
        OR original_inbox.provider_api_version IS DISTINCT FROM original_payload->>'api_version'
        OR original_inbox.livemode::TEXT IS DISTINCT FROM original_payload->>'livemode'
        OR original_payload->'data'->'object'->>'object' IS DISTINCT FROM 'payment_intent'
        OR original_payload->'data'->'object'->>'status' IS DISTINCT FROM 'succeeded'
        OR original_payload->'data'->'object'->>'id' IS DISTINCT FROM retained_conflict.payment_intent_id
        OR original_payload->'data'->'object'->>'customer' IS DISTINCT FROM retained_conflict.provider_customer_id
        OR original_payload->'data'->'object'->>'payment_method' IS DISTINCT FROM retained_conflict.provider_payment_method_id
        OR original_payload->'data'->'object'->>'amount' IS DISTINCT FROM retained_conflict.amount::TEXT
        OR original_payload->'data'->'object'->>'amount_received' IS DISTINCT FROM retained_conflict.amount::TEXT
        OR original_payload->'data'->'object'->>'currency' IS DISTINCT FROM retained_conflict.currency
        OR original_payload->'data'->'object'->>'livemode' IS DISTINCT FROM retained_conflict.livemode::TEXT
        OR NEW.provider_event_id = retained_conflict.provider_event_id
        OR inbox.event_type <> 'payment_intent.succeeded'
        OR inbox.payload_sha256 <> NEW.event_payload_digest
        OR inbox.livemode <> retained_conflict.livemode
        OR inbox.provider_event_id IS DISTINCT FROM event_payload->>'id'
        OR inbox.event_type IS DISTINCT FROM event_payload->>'type'
        OR inbox.provider_created_at_epoch_seconds::TEXT IS DISTINCT FROM event_payload->>'created'
        OR inbox.provider_api_version IS DISTINCT FROM event_payload->>'api_version'
        OR inbox.livemode::TEXT IS DISTINCT FROM event_payload->>'livemode'
        OR event_payload->'data'->'object'->>'object' IS DISTINCT FROM 'payment_intent'
        OR event_payload->'data'->'object'->>'status' IS DISTINCT FROM 'succeeded'
        OR event_payload->'data'->'object'->>'id' IS DISTINCT FROM retained_conflict.payment_intent_id
        OR event_payload->'data'->'object'->>'customer' IS DISTINCT FROM retained_conflict.provider_customer_id
        OR event_payload->'data'->'object'->>'payment_method' IS DISTINCT FROM retained_conflict.provider_payment_method_id
        OR event_payload->'data'->'object'->>'amount' IS DISTINCT FROM retained_conflict.amount::TEXT
        OR event_payload->'data'->'object'->>'amount_received' IS DISTINCT FROM retained_conflict.amount::TEXT
        OR event_payload->'data'->'object'->>'currency' IS DISTINCT FROM retained_conflict.currency
        OR event_payload->'data'->'object'->>'livemode' IS DISTINCT FROM retained_conflict.livemode::TEXT
        OR (NEW.provider_subscription_id, NEW.provider_customer_id, NEW.provider_payment_method_id,
            NEW.provider_price_id, NEW.provider_quantity, NEW.amount, NEW.currency, NEW.livemode,
            NEW.observed_status)
            IS DISTINCT FROM (retained_conflict.provider_subscription_id, retained_conflict.provider_customer_id,
                retained_conflict.provider_payment_method_id, retained_conflict.provider_price_id,
                retained_conflict.provider_quantity, retained_conflict.amount, retained_conflict.currency,
                retained_conflict.livemode, retained_conflict.observed_status)
        OR NEW.observation_read_started_at < inbox.received_at
        OR NEW.observation_observed_at < NEW.observation_read_started_at
        OR NEW.observation_observed_at - NEW.observation_read_started_at >= INTERVAL '20 seconds'
        OR NEW.observation_expires_at IS DISTINCT FROM NEW.observation_read_started_at + INTERVAL '20 seconds'
        OR NEW.observation_observed_at >= NEW.observation_expires_at
        OR NOT EXISTS (
            SELECT 1
            FROM subscription_lifecycle_operations cancellation
            JOIN subscription_lifecycle_receipts receipt
                ON receipt.request_id = cancellation.request_id
                AND receipt.subscription_id = cancellation.subscription_id
            WHERE cancellation.request_id = retained_conflict.cancellation_request_id
                AND cancellation.subscription_id = retained_conflict.subscription_id
                AND cancellation.state = 'COMMITTED'
                AND cancellation.creation_request_id = first_invoice.creation_request_id
                AND cancellation.tenant_id = first_invoice.tenant_id
                AND cancellation.provider_subscription_id = first_invoice.provider_subscription_id
                AND cancellation.provisioning_attempt_id IS NULL
                AND cancellation.request_digest = receipt.request_digest
                AND cancellation.request_digest = 'sha256:' || encode(sha256(cancellation.request_payload), 'hex')
                AND receipt.status = 'canceled'
                AND receipt.receipt_digest = retained_conflict.cancellation_receipt_digest
                AND receipt.subscription_row_version = retained_conflict.cancellation_row_version
                AND receipt.committed_transaction_id = retained_conflict.cancellation_committed_transaction_id
                AND receipt.provider_subscription_id = first_invoice.provider_subscription_id
                AND receipt.plan_quote_digest = first_invoice.quote_digest
                AND convert_from(cancellation.request_payload, 'UTF8')::jsonb->>'schemaVersion'
                    = 'account-hosting-unpaid-first-invoice-cancellation/v1'
        ) THEN
        RAISE EXCEPTION 'Late first-invoice conflict event must match its exact retained conflict and paid tuple'
            USING ERRCODE = '23514';
    END IF;

    NEW.created_transaction_id := txid_current();
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_first_invoice_late_paid_conflict_event_guard
    BEFORE INSERT OR UPDATE OR DELETE ON subscription_first_invoice_late_paid_conflict_events
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_late_paid_conflict_event_guard();
CREATE FUNCTION account_first_invoice_late_paid_conflict_event_no_truncate() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Late first-invoice conflict event evidence is retained'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER account_first_invoice_late_paid_conflict_event_no_truncate
    BEFORE TRUNCATE ON subscription_first_invoice_late_paid_conflict_events
    FOR EACH STATEMENT EXECUTE FUNCTION account_first_invoice_late_paid_conflict_event_no_truncate();
-- [jooq ignore stop]
