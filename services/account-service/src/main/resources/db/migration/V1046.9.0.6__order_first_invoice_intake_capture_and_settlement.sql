-- [jooq ignore start]
-- Refuse installation while any old-order writer is active. Waiting while holding one
-- relation lock could otherwise conflict with the old multi-table write order.
LOCK TABLE stripe_event_inbox, subscription, subscription_first_invoices, subscription_instrument_reservations
    IN SHARE ROW EXCLUSIVE MODE NOWAIT;

CREATE FUNCTION account_lock_first_invoice_intent(intent_id TEXT, provider_livemode BOOLEAN)
RETURNS VOID LANGUAGE plpgsql VOLATILE STRICT AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'account-first-invoice-intent:' || provider_livemode::TEXT || ':' || intent_id, 0));
END;
$$;

-- Intake has no dependency on a committed invoice or on an owner row. A hash collision
-- only makes independent keys wait; the veto still checks the complete exact tuple.
CREATE OR REPLACE FUNCTION account_first_invoice_success_intake_lock() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE intent_id TEXT;
BEGIN
    intent_id := account_first_invoice_success_lookup_intent(NEW.raw_payload);
    IF intent_id ~ '^pi_[A-Za-z0-9]+$' AND char_length(intent_id) <= 255 THEN
        PERFORM account_lock_first_invoice_intent(intent_id, NEW.livemode);
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER first_invoice_success_intake_lock ON stripe_event_inbox;
CREATE TRIGGER first_invoice_success_intake_lock
    BEFORE INSERT ON stripe_event_inbox
    FOR EACH ROW WHEN (NEW.event_type = 'payment_intent.succeeded')
    EXECUTE FUNCTION account_first_invoice_success_intake_lock();

-- The existing BEFORE INSERT source guard already locks the subscription. Capture has
-- no success veto; it makes the immutable linkage available for subsequent reconciliation.
CREATE FUNCTION account_first_invoice_capture_intent_lock() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM account_lock_first_invoice_intent(NEW.payment_intent_id, NEW.livemode);
    RETURN NEW;
END;
$$;

CREATE TRIGGER first_invoice_capture_intent_lock
    AFTER INSERT ON subscription_first_invoices
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_capture_intent_lock();

-- Both settlement paths and the deferred SQL guard call this after locking the subscription.
-- Intake never requests that row, so waiting on the intent cannot invert the owner lock order.
CREATE OR REPLACE FUNCTION account_first_invoice_signed_success_retained(intent_id TEXT, provider_livemode BOOLEAN)
RETURNS BOOLEAN LANGUAGE plpgsql VOLATILE AS $$
DECLARE success_retained BOOLEAN;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Unpaid first-invoice cancellation requires READ COMMITTED isolation' USING ERRCODE = '23514';
    END IF;
    PERFORM account_lock_first_invoice_intent(intent_id, provider_livemode);

    -- Execute the evidence read in a separate statement so it sees an intake commit that
    -- released the lock. A STABLE function would keep the caller's pre-wait snapshot.
    WITH candidates AS MATERIALIZED (
        SELECT inbox.provider_event_id, inbox.event_type, inbox.provider_created_at_epoch_seconds,
            convert_from(inbox.raw_payload, 'UTF8')::jsonb AS event
        FROM stripe_first_invoice_success_lookup lookup
        JOIN stripe_event_inbox inbox ON inbox.provider_event_id = lookup.provider_event_id
        WHERE lookup.payment_intent_id = intent_id
            AND lookup.livemode = provider_livemode
            AND inbox.livemode = provider_livemode
            AND inbox.event_type = 'payment_intent.succeeded'
    )
    SELECT EXISTS (
        SELECT 1
        FROM subscription_first_invoices invoice
        CROSS JOIN candidates payload
        CROSS JOIN LATERAL (
            SELECT payload.event #> '{data,object}' AS intent
        ) payment
        WHERE invoice.payment_intent_id = intent_id
            AND invoice.livemode = provider_livemode
            AND payload.event->>'id' = payload.provider_event_id
            AND payload.event->>'type' = payload.event_type
            AND payload.event->'created' = to_jsonb(payload.provider_created_at_epoch_seconds)
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
    ) INTO success_retained;
    RETURN success_retained;
END;
$$;
-- [jooq ignore stop]
