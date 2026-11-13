-- An exact retained success fences either unpaid checkout branch, including an invoice whose
-- first confirmation has not yet been claimed. When original consent exists, retain its floor.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_first_invoice_signed_success_retained(intent_id TEXT, provider_livemode BOOLEAN)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (
        SELECT 1
        FROM subscription_first_invoices invoice
        LEFT JOIN subscription_first_invoice_confirmations confirmation
            ON confirmation.subscription_id = invoice.subscription_id
        JOIN stripe_event_inbox inbox ON inbox.livemode = invoice.livemode
        CROSS JOIN LATERAL (
            SELECT convert_from(inbox.raw_payload, 'UTF8')::jsonb AS event
        ) payload
        CROSS JOIN LATERAL (
            SELECT payload.event #> '{data,object}' AS intent
        ) payment
        WHERE invoice.payment_intent_id = intent_id
            AND invoice.livemode = provider_livemode
            AND inbox.event_type = 'payment_intent.succeeded'
            AND payload.event->>'id' = inbox.provider_event_id
            AND payload.event->>'type' = inbox.event_type
            AND payload.event->'created' = to_jsonb(inbox.provider_created_at_epoch_seconds)
            AND payload.event->'livemode' = to_jsonb(invoice.livemode)
            AND payment.intent->>'object' = 'payment_intent'
            AND payment.intent->>'status' = 'succeeded'
            AND payment.intent->>'id' = invoice.payment_intent_id
            AND payment.intent->>'customer' = invoice.provider_customer_id
            AND payment.intent->>'payment_method' = invoice.provider_payment_method_id
            AND payment.intent->'amount' = to_jsonb(invoice.amount)
            AND payment.intent->'amount_received' = to_jsonb(invoice.amount)
            AND payment.intent->>'currency' = invoice.currency
            AND payment.intent->'livemode' = to_jsonb(invoice.livemode)
            AND (confirmation.consented_at IS NULL
                OR inbox.provider_created_at_epoch_seconds >= floor(EXTRACT(EPOCH FROM confirmation.consented_at)))
    );
$$;

-- New markers carry the actual inserting transaction. Existing immutable markers stay intact.
CREATE OR REPLACE FUNCTION account_first_invoice_expired_before_post_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE claim_transaction BIGINT; authorization_expiry TIMESTAMPTZ;
BEGIN
    SELECT c.created_transaction_id, a.valid_until INTO STRICT claim_transaction, authorization_expiry
    FROM subscription_first_invoice_command_claims c
    JOIN subscription_first_invoice_authorizations a USING (authorization_request_id, request_id, subscription_id)
    WHERE c.request_id = NEW.request_id;
    IF claim_transaction = txid_current() OR NEW.observed_at < authorization_expiry THEN
        RAISE EXCEPTION 'First-invoice no-send evidence requires an independently committed claim and expired authorization' USING ERRCODE = '23514';
    END IF;
    NEW.created_transaction_id := txid_current();
    RETURN NEW;
END;
$$;

-- The transition and release share the subscription lock used by signed inbox intake. Require
-- a fresh snapshot after that lock so a raw writer cannot miss an independently committed event.
CREATE FUNCTION account_first_invoice_unpaid_cancellation_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE invoice subscription_first_invoices%ROWTYPE;
BEGIN
    PERFORM 1 FROM subscription WHERE subscription_id = NEW.subscription_id FOR UPDATE;
    SELECT * INTO invoice FROM subscription_first_invoices WHERE subscription_id = NEW.subscription_id;
    IF NOT FOUND OR EXISTS (SELECT 1 FROM subscription_first_invoice_completions
        WHERE subscription_id = NEW.subscription_id) THEN
        RETURN NULL;
    END IF;

    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Unpaid first-invoice cancellation requires READ COMMITTED isolation' USING ERRCODE = '23514';
    END IF;
    IF account_first_invoice_signed_success_retained(invoice.payment_intent_id, invoice.livemode) THEN
        RAISE EXCEPTION 'Unpaid first-invoice cancellation conflicts with retained signed success' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM subscription_provisioning_work
        WHERE request_id = invoice.creation_request_id AND subscription_id = invoice.subscription_id
            AND phase = 'CANCELED') THEN
        RAISE EXCEPTION 'Unpaid first-invoice cancellation requires exact terminal provisioning work' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER first_invoice_unpaid_subscription_cancellation
    AFTER UPDATE ON subscription
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status <> 'canceled' AND NEW.status = 'canceled')
    EXECUTE FUNCTION account_first_invoice_unpaid_cancellation_consistency();

CREATE CONSTRAINT TRIGGER first_invoice_unpaid_reservation_release
    AFTER UPDATE ON subscription_instrument_reservations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.released_at IS NULL AND NEW.released_at IS NOT NULL)
    EXECUTE FUNCTION account_first_invoice_unpaid_cancellation_consistency();
-- [jooq ignore stop]
