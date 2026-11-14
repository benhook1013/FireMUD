-- Operator-only obligation for exact signed success before local consent/cancellation.
-- This evidence grants no payment, fulfillment, refund, or cancellation authority.
CREATE TABLE subscription_first_invoice_unconsented_paid_events (
    provider_event_id VARCHAR(255) PRIMARY KEY REFERENCES stripe_event_inbox(provider_event_id) ON DELETE RESTRICT,
    subscription_id UUID NOT NULL REFERENCES subscription(subscription_id) ON DELETE RESTRICT,
    creation_request_id UUID NOT NULL,
    tuple_digest VARCHAR(71) NOT NULL CHECK (tuple_digest ~ '^sha256:[0-9a-f]{64}$'),
    event_payload_digest VARCHAR(71) NOT NULL CHECK (event_payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (subscription_id, tuple_digest)
        REFERENCES subscription_first_invoices(subscription_id, tuple_digest) ON DELETE RESTRICT
);
-- [jooq ignore start]
CREATE FUNCTION account_first_invoice_unconsented_paid_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE invoice subscription_first_invoices%ROWTYPE;
    inbox stripe_event_inbox%ROWTYPE;
    state TEXT;
    payload JSONB;
    intent JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Unconsented paid operator evidence is immutable' USING ERRCODE='23514';
    END IF;
    SELECT status INTO STRICT state FROM subscription WHERE subscription_id=NEW.subscription_id FOR UPDATE;
    SELECT * INTO STRICT invoice FROM subscription_first_invoices WHERE subscription_id=NEW.subscription_id;
    SELECT * INTO STRICT inbox FROM stripe_event_inbox WHERE provider_event_id=NEW.provider_event_id;
    payload := convert_from(inbox.raw_payload, 'UTF8')::JSONB;
    intent := payload #> '{data,object}';
    IF state='canceled'
        OR EXISTS (SELECT 1 FROM subscription_first_invoice_confirmations WHERE subscription_id=NEW.subscription_id)
        OR EXISTS (SELECT 1 FROM subscription_first_invoice_completions WHERE subscription_id=NEW.subscription_id)
        OR (NEW.creation_request_id, NEW.tuple_digest) IS DISTINCT FROM (invoice.creation_request_id, invoice.tuple_digest)
        OR NEW.event_payload_digest IS DISTINCT FROM inbox.payload_sha256
        OR inbox.event_type IS DISTINCT FROM 'payment_intent.succeeded'
        OR payload->>'id' IS DISTINCT FROM inbox.provider_event_id
        OR payload->>'type' IS DISTINCT FROM inbox.event_type
        OR payload->'created' IS DISTINCT FROM to_jsonb(inbox.provider_created_at_epoch_seconds)
        OR payload->'livemode' IS DISTINCT FROM to_jsonb(invoice.livemode)
        OR inbox.livemode IS DISTINCT FROM invoice.livemode
        OR intent->>'object' IS DISTINCT FROM 'payment_intent'
        OR intent->>'status' IS DISTINCT FROM 'succeeded'
        OR intent->>'id' IS DISTINCT FROM invoice.payment_intent_id
        OR intent->>'customer' IS DISTINCT FROM invoice.provider_customer_id
        OR intent->>'payment_method' IS DISTINCT FROM invoice.provider_payment_method_id
        OR intent->'amount' IS DISTINCT FROM to_jsonb(invoice.amount)
        OR intent->'amount_received' IS DISTINCT FROM to_jsonb(invoice.amount)
        OR intent->>'currency' IS DISTINCT FROM invoice.currency
        OR intent->'livemode' IS DISTINCT FROM to_jsonb(invoice.livemode)
        OR inbox.provider_created_at_epoch_seconds < floor(EXTRACT(EPOCH FROM invoice.captured_at))
    THEN
        RAISE EXCEPTION 'Unconsented paid operator evidence lacks exact retained invoice and signed success' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_unconsented_paid_guard
    BEFORE INSERT OR UPDATE OR DELETE ON subscription_first_invoice_unconsented_paid_events
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_unconsented_paid_guard();
CREATE TRIGGER first_invoice_unconsented_paid_no_truncate
    BEFORE TRUNCATE ON subscription_first_invoice_unconsented_paid_events
    FOR EACH STATEMENT EXECUTE FUNCTION account_first_invoice_unconsented_paid_guard();
-- A new consent cannot turn an unresolved paid operator item into another provider command.
CREATE FUNCTION account_first_invoice_unconsented_paid_confirmation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM subscription WHERE subscription_id=NEW.subscription_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM subscription_first_invoice_unconsented_paid_events
               WHERE subscription_id=NEW.subscription_id) THEN
        RAISE EXCEPTION 'Unconsented paid invoice requires operator resolution before confirmation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_unconsented_paid_confirmation_guard
    BEFORE INSERT ON subscription_first_invoice_confirmations
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_unconsented_paid_confirmation_guard();
-- [jooq ignore stop]

