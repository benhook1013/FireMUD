-- Retain only signature-verified Stripe event evidence. No local correlation, billing transition,
-- provider mutation, or entitlement authority is assigned at webhook intake.
CREATE TABLE stripe_event_inbox (
    provider_event_id VARCHAR NOT NULL,
    raw_payload BYTEA NOT NULL CHECK (octet_length(raw_payload) > 0),
    payload_sha256 VARCHAR(71) NOT NULL
        CHECK (payload_sha256 ~ '^sha256:[0-9a-f]{64}$')
        CHECK (payload_sha256 = 'sha256:' || encode(sha256(raw_payload), 'hex')),
    event_type TEXT NOT NULL CHECK (char_length(btrim(event_type)) > 0),
    provider_created_at_epoch_seconds BIGINT NOT NULL,
    provider_api_version TEXT,
    livemode BOOLEAN NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processing_status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (processing_status = 'PENDING'),
    CONSTRAINT stripe_event_inbox_provider_event_id_pkey PRIMARY KEY (provider_event_id),
    CONSTRAINT stripe_event_inbox_provider_event_id_nonblank
        CHECK (char_length(btrim(provider_event_id)) > 0)
);

-- Unresolved verified evidence is not eligible for age-based deletion or mutation. No cleanup or
-- TTL policy is selected by this intake slice.
-- [jooq ignore start]
CREATE FUNCTION stripe_event_inbox_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Stripe event inbox evidence is append-only'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER stripe_event_inbox_immutable
    BEFORE UPDATE OR DELETE ON stripe_event_inbox
    FOR EACH ROW EXECUTE FUNCTION stripe_event_inbox_no_mutation();
CREATE TRIGGER stripe_event_inbox_no_truncate
    BEFORE TRUNCATE ON stripe_event_inbox
    FOR EACH STATEMENT EXECUTE FUNCTION stripe_event_inbox_no_mutation();
-- [jooq ignore stop]
