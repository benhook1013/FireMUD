-- Unpaid release is vetoed by exact signed success even if it predates consent.
-- Paid fulfillment and new links to a retained late-payment conflict retain the consent floor.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_first_invoice_signed_success_retained(intent_id TEXT, provider_livemode BOOLEAN)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (
        SELECT 1
        FROM subscription_first_invoices invoice
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
    );
$$;

CREATE FUNCTION account_first_invoice_late_paid_replay_consent_floor()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original_consent TIMESTAMPTZ; event_created BIGINT;
BEGIN
    SELECT confirmation.consented_at, inbox.provider_created_at_epoch_seconds
    INTO STRICT original_consent, event_created
    FROM subscription_first_invoice_late_paid_conflicts conflict
    JOIN subscription_first_invoice_confirmations confirmation
        ON confirmation.request_id = conflict.request_id
        AND confirmation.subscription_id = conflict.subscription_id
        AND confirmation.tuple_digest = conflict.tuple_digest
    JOIN stripe_event_inbox inbox ON inbox.provider_event_id = NEW.provider_event_id
    WHERE conflict.subscription_id = NEW.subscription_id;

    IF original_consent IS NULL
        OR event_created < floor(EXTRACT(EPOCH FROM original_consent)) THEN
        RAISE EXCEPTION 'Late first-invoice payment event predates original consent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_first_invoice_late_paid_replay_consent_floor
    BEFORE INSERT ON subscription_first_invoice_late_paid_event_replays
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_late_paid_replay_consent_floor();
-- [jooq ignore stop]
