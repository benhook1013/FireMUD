-- Durable Account receipt identity for exact issuer projection snapshot captures.
CREATE TABLE account_issuer_projection_reconciliation_receipts (
    operation_id UUID NOT NULL,
    request_id UUID NOT NULL,
    issuer_id VARCHAR(512) NOT NULL,
    caller_workload_identity VARCHAR(512) NOT NULL,
    projection_key VARCHAR(2048) NOT NULL,
    request_digest_version INTEGER NOT NULL,
    request_digest BYTEA NOT NULL,
    issuer_auth_generation BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    outbox_stream_key VARCHAR(2048) NOT NULL,
    outbox_sequence BIGINT NOT NULL,
    event_outbox_sequence BIGINT,
    event_id VARCHAR(512),
    event_digest VARCHAR(71),
    event_payload BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_issuer_projection_reconciliation_operation_pk PRIMARY KEY (operation_id),
    CONSTRAINT account_issuer_projection_reconciliation_request_uq UNIQUE (issuer_id, request_id),
    CONSTRAINT account_issuer_projection_reconciliation_identity_check CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND length(btrim(issuer_id)) BETWEEN 1 AND 512
        AND length(btrim(caller_workload_identity)) BETWEEN 1 AND 512
        AND projection_key = 'session:game:auth:issuer-generation:v1:' || issuer_id
        AND request_digest_version = 1
        AND octet_length(request_digest) = 32
        AND issuer_auth_generation > 0
        AND source_version > 0
        AND outbox_stream_key = 'account:auth-authority:v1:issuer/' || issuer_id
        AND outbox_sequence >= 0
    ),
    CONSTRAINT account_issuer_projection_reconciliation_checkpoint_check CHECK (
        (outbox_sequence = 0
            AND issuer_auth_generation = 1
            AND source_version = 1
            AND event_id IS NULL
            AND event_digest IS NULL
            AND event_outbox_sequence IS NULL
            AND event_payload IS NULL)
        OR
        (outbox_sequence > 0
            AND event_id IS NOT NULL
            AND length(btrim(event_id)) BETWEEN 1 AND 512
            AND event_outbox_sequence IS NOT NULL
            AND event_outbox_sequence = outbox_sequence
            AND event_digest IS NOT NULL
            AND event_digest ~ '^sha256:[0-9a-f]{64}$'
            AND event_payload IS NOT NULL
            AND octet_length(event_payload) > 0)
    ),
    CONSTRAINT account_issuer_projection_reconciliation_outbox_fk
        FOREIGN KEY (outbox_stream_key, event_outbox_sequence)
        REFERENCES account_authority_outbox_events (outbox_stream_key, outbox_sequence)
        ON DELETE RESTRICT
);

-- [jooq ignore start]
CREATE FUNCTION account_issuer_projection_reconciliation_event_binding_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    stored_event_id TEXT;
    stored_event_digest TEXT;
    stored_payload BYTEA;
BEGIN
    IF NEW.outbox_sequence > 0 THEN
        SELECT event_id, event_digest, payload
            INTO stored_event_id, stored_event_digest, stored_payload
            FROM account_authority_outbox_events
            WHERE outbox_stream_key = NEW.outbox_stream_key
                AND outbox_sequence = NEW.outbox_sequence;
        IF stored_event_id IS DISTINCT FROM NEW.event_id
            OR stored_event_digest IS DISTINCT FROM NEW.event_digest
            OR stored_payload IS DISTINCT FROM NEW.event_payload THEN
            RAISE EXCEPTION 'Issuer reconciliation receipt differs from its source event'
                USING ERRCODE = '23514',
                    CONSTRAINT = 'account_issuer_projection_reconciliation_event_binding';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_issuer_projection_reconciliation_event_binding
    BEFORE INSERT ON account_issuer_projection_reconciliation_receipts
    FOR EACH ROW EXECUTE FUNCTION account_issuer_projection_reconciliation_event_binding_guard();

CREATE FUNCTION account_issuer_projection_reconciliation_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Issuer projection reconciliation receipts are immutable'
        USING ERRCODE = '23514',
            CONSTRAINT = 'account_issuer_projection_reconciliation_immutable';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_issuer_projection_reconciliation_immutable
    BEFORE UPDATE OR DELETE ON account_issuer_projection_reconciliation_receipts
    FOR EACH ROW EXECUTE FUNCTION account_issuer_projection_reconciliation_immutable_guard();
-- [jooq ignore stop]
