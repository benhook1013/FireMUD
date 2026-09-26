CREATE TABLE account_authority_outbox_streams (
    outbox_stream_key VARCHAR(2048) PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_authority_outbox_stream_key_check CHECK (
        outbox_stream_key LIKE 'account:auth-authority:v1:%'
        AND length(outbox_stream_key) > char_length('account:auth-authority:v1:')
        AND length(btrim(substr(outbox_stream_key, char_length('account:auth-authority:v1:') + 1))) > 0
    ),
    CONSTRAINT account_authority_outbox_stream_sequence_check CHECK (last_sequence >= 0)
);

CREATE TABLE account_authority_outbox_events (
    outbox_stream_key VARCHAR(2048) NOT NULL,
    outbox_sequence BIGINT NOT NULL,
    request_id VARCHAR(512) NOT NULL,
    event_id VARCHAR(512) NOT NULL,
    event_digest TEXT NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_authority_outbox_events_pk
        PRIMARY KEY (outbox_stream_key, outbox_sequence),
    CONSTRAINT account_authority_outbox_events_request_uq
        UNIQUE (outbox_stream_key, request_id),
    CONSTRAINT account_authority_outbox_events_event_uq
        UNIQUE (outbox_stream_key, event_id),
    CONSTRAINT account_authority_outbox_events_sequence_check
        CHECK (outbox_sequence > 0),
    CONSTRAINT account_authority_outbox_events_request_id_check
        CHECK (length(btrim(request_id)) > 0),
    CONSTRAINT account_authority_outbox_events_event_id_check
        CHECK (length(btrim(event_id)) > 0),
    CONSTRAINT account_authority_outbox_events_digest_check
        CHECK (length(btrim(event_digest)) > 0),
    CONSTRAINT account_authority_outbox_events_payload_check
        CHECK (octet_length(payload) > 0),
    CONSTRAINT account_authority_outbox_events_stream_fk
        FOREIGN KEY (outbox_stream_key)
        REFERENCES account_authority_outbox_streams (outbox_stream_key)
        ON DELETE RESTRICT
);

-- [jooq ignore start]
CREATE FUNCTION account_authority_outbox_stream_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.outbox_stream_key IS DISTINCT FROM OLD.outbox_stream_key THEN
        RAISE EXCEPTION 'Account authority outbox stream identity cannot be reassigned'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_stream_immutable';
    END IF;
    IF NEW.last_sequence <> OLD.last_sequence + 1 THEN
        RAISE EXCEPTION 'Account authority outbox sequence must advance by one'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_sequence_contiguous';
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_authority_outbox_stream_delete_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account authority outbox stream history cannot be deleted'
        USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_stream_no_delete';
    RETURN OLD;
END;
$$;

CREATE FUNCTION account_authority_outbox_event_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    head_sequence BIGINT;
    previous_sequence BIGINT;
BEGIN
    SELECT last_sequence INTO head_sequence
        FROM account_authority_outbox_streams
        WHERE outbox_stream_key = NEW.outbox_stream_key;
    SELECT COALESCE(MAX(outbox_sequence), 0) INTO previous_sequence
        FROM account_authority_outbox_events
        WHERE outbox_stream_key = NEW.outbox_stream_key;

    IF head_sequence IS DISTINCT FROM NEW.outbox_sequence
        OR NEW.outbox_sequence <> previous_sequence + 1 THEN
        RAISE EXCEPTION 'Account authority outbox event must match the next stream sequence'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_event_contiguous';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_authority_outbox_event_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account authority outbox event evidence is immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_event_immutable';
    RETURN OLD;
END;
$$;

CREATE FUNCTION account_authority_outbox_stream_consistency_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    stream_sequence BIGINT;
    event_sequence BIGINT;
BEGIN
    SELECT last_sequence INTO stream_sequence
        FROM account_authority_outbox_streams
        WHERE outbox_stream_key = NEW.outbox_stream_key;
    SELECT COALESCE(MAX(outbox_sequence), 0) INTO event_sequence
        FROM account_authority_outbox_events
        WHERE outbox_stream_key = NEW.outbox_stream_key;

    IF stream_sequence IS NULL OR stream_sequence < 1 OR stream_sequence <> event_sequence THEN
        RAISE EXCEPTION 'Account authority outbox stream head must match committed event history'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_outbox_stream_consistent';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_authority_outbox_stream_update
    BEFORE UPDATE ON account_authority_outbox_streams
    FOR EACH ROW EXECUTE FUNCTION account_authority_outbox_stream_update_guard();

CREATE TRIGGER account_authority_outbox_stream_delete
    BEFORE DELETE ON account_authority_outbox_streams
    FOR EACH ROW EXECUTE FUNCTION account_authority_outbox_stream_delete_guard();

CREATE TRIGGER account_authority_outbox_event_insert
    BEFORE INSERT ON account_authority_outbox_events
    FOR EACH ROW EXECUTE FUNCTION account_authority_outbox_event_insert_guard();

CREATE TRIGGER account_authority_outbox_event_update
    BEFORE UPDATE OR DELETE ON account_authority_outbox_events
    FOR EACH ROW EXECUTE FUNCTION account_authority_outbox_event_immutable_guard();

CREATE CONSTRAINT TRIGGER account_authority_outbox_stream_consistent
    AFTER INSERT OR UPDATE ON account_authority_outbox_streams
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_authority_outbox_stream_consistency_guard();

CREATE CONSTRAINT TRIGGER account_authority_outbox_event_stream_consistent
    AFTER INSERT ON account_authority_outbox_events
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_authority_outbox_stream_consistency_guard();
-- [jooq ignore stop]
