-- Retain a lookup key for each success delivery independently of invoice capture or consent.
-- The original signed inbox row remains the complete evidence used by the unpaid veto.
CREATE TABLE stripe_first_invoice_success_lookup (
    provider_event_id VARCHAR PRIMARY KEY
        REFERENCES stripe_event_inbox(provider_event_id) ON DELETE RESTRICT,
    livemode BOOLEAN NOT NULL,
    payment_intent_id VARCHAR(255) NOT NULL CHECK (payment_intent_id ~ '^pi_[A-Za-z0-9]+$')
);

CREATE INDEX idx_stripe_first_invoice_success_lookup_intent
    ON stripe_first_invoice_success_lookup (livemode, payment_intent_id);

-- [jooq ignore start]
CREATE FUNCTION account_first_invoice_success_lookup_intent(raw_payload BYTEA)
RETURNS TEXT LANGUAGE SQL STABLE STRICT AS $$
    SELECT convert_from(raw_payload, 'UTF8')::jsonb #>> '{data,object,id}';
$$;

CREATE FUNCTION account_first_invoice_success_lookup_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE intent_id TEXT;
BEGIN
    intent_id := account_first_invoice_success_lookup_intent(NEW.raw_payload);
    IF intent_id ~ '^pi_[A-Za-z0-9]+$' AND char_length(intent_id) <= 255 THEN
        INSERT INTO stripe_first_invoice_success_lookup (provider_event_id, livemode, payment_intent_id)
        VALUES (NEW.provider_event_id, NEW.livemode, intent_id);
    END IF;
    RETURN NEW;
END;
$$;

-- Fence inbox inserts through backfill and trigger installation. Unparseable retained success
-- bytes abort the migration so malformed evidence cannot silently disappear from the veto.
LOCK TABLE stripe_event_inbox IN SHARE ROW EXCLUSIVE MODE;

WITH retained AS MATERIALIZED (
    SELECT provider_event_id, livemode, account_first_invoice_success_lookup_intent(raw_payload) AS intent_id
    FROM stripe_event_inbox
    WHERE event_type = 'payment_intent.succeeded'
)
INSERT INTO stripe_first_invoice_success_lookup (provider_event_id, livemode, payment_intent_id)
SELECT provider_event_id, livemode, intent_id
FROM retained
WHERE intent_id ~ '^pi_[A-Za-z0-9]+$' AND char_length(intent_id) <= 255;

CREATE TRIGGER first_invoice_success_lookup_insert
    AFTER INSERT ON stripe_event_inbox
    FOR EACH ROW WHEN (NEW.event_type = 'payment_intent.succeeded')
    EXECUTE FUNCTION account_first_invoice_success_lookup_insert();

CREATE TRIGGER stripe_first_invoice_success_lookup_immutable
    BEFORE UPDATE OR DELETE ON stripe_first_invoice_success_lookup
    FOR EACH ROW EXECUTE FUNCTION stripe_event_inbox_no_mutation();

CREATE TRIGGER stripe_first_invoice_success_lookup_no_truncate
    BEFORE TRUNCATE ON stripe_first_invoice_success_lookup
    FOR EACH STATEMENT EXECUTE FUNCTION stripe_event_inbox_no_mutation();

CREATE OR REPLACE FUNCTION account_first_invoice_signed_success_retained(intent_id TEXT, provider_livemode BOOLEAN)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
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
    );
$$;
-- [jooq ignore stop]

DROP INDEX idx_stripe_event_inbox_first_invoice_success_mode_created;
