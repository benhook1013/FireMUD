-- Keep one immutable conflict per cancellation while retaining every accepted event identity.
CREATE TABLE subscription_first_invoice_late_paid_event_replays (
    provider_event_id VARCHAR(255) PRIMARY KEY
        REFERENCES stripe_event_inbox(provider_event_id) ON DELETE RESTRICT,
    subscription_id UUID NOT NULL
        REFERENCES subscription_first_invoice_late_paid_conflicts(subscription_id) ON DELETE RESTRICT,
    event_payload_digest VARCHAR(71) NOT NULL
        CHECK (event_payload_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_first_invoice_late_paid_event_replays_subscription
    ON subscription_first_invoice_late_paid_event_replays(subscription_id);

-- [jooq ignore start]
CREATE FUNCTION account_first_invoice_late_paid_event_replay_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    conflict subscription_first_invoice_late_paid_conflicts%ROWTYPE;
    inbox stripe_event_inbox%ROWTYPE;
    envelope JSONB;
    payment JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Late first-invoice payment event links are retained' USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT conflict FROM subscription_first_invoice_late_paid_conflicts
        WHERE subscription_id = NEW.subscription_id;
    SELECT * INTO STRICT inbox FROM stripe_event_inbox WHERE provider_event_id = NEW.provider_event_id;
    envelope := convert_from(inbox.raw_payload, 'UTF8')::jsonb;
    payment := envelope->'data'->'object';

    IF EXISTS (
        SELECT 1 FROM subscription_first_invoice_late_paid_conflicts
        WHERE provider_event_id = NEW.provider_event_id
    )
        OR inbox.event_type <> 'payment_intent.succeeded'
        OR inbox.payload_sha256 IS DISTINCT FROM NEW.event_payload_digest
        OR inbox.livemode IS DISTINCT FROM conflict.livemode
        OR envelope->>'id' IS DISTINCT FROM inbox.provider_event_id
        OR envelope->>'type' IS DISTINCT FROM inbox.event_type
        OR envelope->'created' IS DISTINCT FROM to_jsonb(inbox.provider_created_at_epoch_seconds)
        OR envelope->'livemode' IS DISTINCT FROM to_jsonb(conflict.livemode)
        OR payment->>'object' IS DISTINCT FROM 'payment_intent'
        OR payment->>'status' IS DISTINCT FROM 'succeeded'
        OR payment->>'id' IS DISTINCT FROM conflict.payment_intent_id
        OR payment->>'customer' IS DISTINCT FROM conflict.provider_customer_id
        OR payment->>'payment_method' IS DISTINCT FROM conflict.provider_payment_method_id
        OR payment->'amount' IS DISTINCT FROM to_jsonb(conflict.amount)
        OR payment->'amount_received' IS DISTINCT FROM to_jsonb(conflict.amount)
        OR payment->>'currency' IS DISTINCT FROM conflict.currency
        OR payment->'livemode' IS DISTINCT FROM to_jsonb(conflict.livemode) THEN
        RAISE EXCEPTION 'Late first-invoice payment event link refused' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_first_invoice_late_paid_event_replay_guard
    BEFORE INSERT OR UPDATE OR DELETE ON subscription_first_invoice_late_paid_event_replays
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_late_paid_event_replay_guard();
CREATE TRIGGER account_first_invoice_late_paid_event_replay_no_truncate
    BEFORE TRUNCATE ON subscription_first_invoice_late_paid_event_replays
    FOR EACH STATEMENT EXECUTE FUNCTION account_first_invoice_late_paid_conflict_no_truncate();
-- [jooq ignore stop]
