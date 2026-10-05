-- Existing exact source identities and all V36..V58 history remain unchanged. Support the full
-- configured issuer length without truncating or replacing its UTF-8 participation key.
ALTER TABLE account_draft_authorization_source_locks ALTER COLUMN source_key TYPE VARCHAR(2048);
ALTER TABLE account_draft_authorization_sources ALTER COLUMN source_key TYPE VARCHAR(2048);
ALTER TABLE account_draft_authorization_changed_scopes ALTER COLUMN source_key TYPE VARCHAR(2048);

-- [jooq ignore start]
DO $$
DECLARE
    capacity_guard TEXT;
    guard_count INTEGER;
BEGIN
    SELECT count(*), min(constraint_row.conname::text) INTO guard_count, capacity_guard
    FROM pg_constraint constraint_row
    JOIN pg_attribute attribute_row ON attribute_row.attrelid = constraint_row.conrelid
        AND attribute_row.attnum = ANY(constraint_row.conkey)
    WHERE constraint_row.conrelid = 'account_draft_authorization_source_locks'::regclass
        AND constraint_row.contype = 'c' AND attribute_row.attname = 'source_key';
    IF guard_count <> 1 THEN
        RAISE EXCEPTION 'Expected the single existing V57 source-key capacity guard';
    END IF;
    EXECUTE format('ALTER TABLE account_draft_authorization_source_locks DROP CONSTRAINT %I', capacity_guard);
END;
$$;
-- [jooq ignore stop]
ALTER TABLE account_draft_authorization_source_locks
    ADD CONSTRAINT account_draft_authorization_source_key_capacity
    CHECK (length(source_key) > 0 AND octet_length(source_key) <= 2048);

CREATE TABLE account_issuer_tenant_draft_source_changes (
    source_kind VARCHAR(6) NOT NULL CHECK (source_kind IN ('ISSUER', 'TENANT')),
    scope_id VARCHAR(512) NOT NULL CHECK (length(scope_id) > 0),
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    expected_generation BIGINT NOT NULL CHECK (expected_generation > 0),
    expected_source_version BIGINT NOT NULL CHECK (expected_source_version > 0),
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) > 0),
    source_change_id UUID NOT NULL UNIQUE REFERENCES account_draft_authorization_source_changes(change_id)
        CHECK (source_change_id <> '00000000-0000-0000-0000-000000000000'),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) > 0),
    source_change_binding BYTEA NOT NULL CHECK (octet_length(source_change_binding) > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('WAITING', 'SOURCE_COMMITTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    event_stream VARCHAR(1024),
    event_sequence BIGINT CHECK (event_sequence > 0),
    event_id VARCHAR(256),
    event_digest VARCHAR(128),
    event_payload BYTEA,
    PRIMARY KEY (source_kind, scope_id, request_id),
    CHECK (octet_length(source_kind || ':' || scope_id) <= 2048),
    CHECK (CASE WHEN source_kind = 'TENANT' THEN scope_id = lower(scope_id::uuid::text)
        AND scope_id <> '00000000-0000-0000-0000-000000000000' ELSE true END),
    CHECK ((status = 'WAITING' AND committed_at IS NULL AND event_stream IS NULL
        AND event_sequence IS NULL AND event_id IS NULL AND event_digest IS NULL AND event_payload IS NULL)
        OR (status = 'SOURCE_COMMITTED' AND committed_at IS NOT NULL AND event_stream IS NOT NULL
        AND event_sequence IS NOT NULL AND event_id IS NOT NULL AND event_digest IS NOT NULL
        AND event_payload IS NOT NULL AND octet_length(event_payload) > 0))
);

-- [jooq ignore start]
CREATE FUNCTION account_issuer_tenant_source_frame(value BYTEA)
RETURNS BYTEA LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT int4send(octet_length(value)) || value;
$$;

CREATE FUNCTION account_issuer_tenant_source_journal_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    expected_request BYTEA;
    expected_change BYTEA;
    expected_source_prefix BYTEA;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'WAITING' OR NEW.status <> 'SOURCE_COMMITTED'
            OR NEW.source_kind IS DISTINCT FROM OLD.source_kind
            OR NEW.scope_id IS DISTINCT FROM OLD.scope_id
            OR NEW.request_id IS DISTINCT FROM OLD.request_id
            OR NEW.expected_generation IS DISTINCT FROM OLD.expected_generation
            OR NEW.expected_source_version IS DISTINCT FROM OLD.expected_source_version
            OR NEW.request_payload IS DISTINCT FROM OLD.request_payload
            OR NEW.source_change_id IS DISTINCT FROM OLD.source_change_id
            OR NEW.source_evidence IS DISTINCT FROM OLD.source_evidence
            OR NEW.source_change_binding IS DISTINCT FROM OLD.source_change_binding
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Issuer/tenant source request and original capture are immutable'
                USING ERRCODE = '23514';
        END IF;
        NEW.committed_at = CURRENT_TIMESTAMP;
        RETURN NEW;
    END IF;
    IF NEW.status <> 'WAITING' THEN
        RAISE EXCEPTION 'Issuer/tenant source request must begin with a WAITING intent'
            USING ERRCODE = '23514';
    END IF;
    expected_request := account_issuer_tenant_source_frame(convert_to('account-issuer-tenant-draft-source-request/v1', 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to(NEW.source_kind, 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to(NEW.scope_id, 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to(NEW.request_id::text, 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to(NEW.expected_generation::text, 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to(NEW.expected_source_version::text, 'UTF8'));
    expected_source_prefix := account_issuer_tenant_source_frame(convert_to('account-draft-source-evidence/v1', 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to(NEW.source_kind, 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to(NEW.scope_id, 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to('PRESENT', 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to(NEW.expected_generation::text, 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to(NEW.expected_source_version::text, 'UTF8'));
    expected_change := account_issuer_tenant_source_frame(convert_to('account-draft-source-change/v1', 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to(NEW.source_change_id::text, 'UTF8'))
        || account_issuer_tenant_source_frame(convert_to('1', 'UTF8'))
        || account_issuer_tenant_source_frame(NEW.source_evidence)
        || account_issuer_tenant_source_frame(NEW.request_payload);
    IF NEW.request_payload IS DISTINCT FROM expected_request
        OR substring(NEW.source_evidence FROM 1 FOR octet_length(expected_source_prefix)) IS DISTINCT FROM expected_source_prefix
        OR NEW.source_change_binding IS DISTINCT FROM expected_change
        OR NOT EXISTS (SELECT 1 FROM account_draft_authorization_source_changes
            WHERE change_id = NEW.source_change_id AND status = 'WAITING'
                AND binding = expected_change)
        OR (SELECT count(*) FROM account_draft_authorization_changed_scopes
            WHERE change_id = NEW.source_change_id) <> 1
        OR NOT EXISTS (SELECT 1 FROM account_draft_authorization_changed_scopes
            WHERE change_id = NEW.source_change_id AND source_key = NEW.source_kind || ':' || NEW.scope_id) THEN
        RAISE EXCEPTION 'Issuer/tenant request must bind its exact original V57 source intent'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_issuer_tenant_source_link_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal account_issuer_tenant_draft_source_changes%ROWTYPE;
    change_status TEXT;
    change_binding BYTEA;
    selected_change UUID;
BEGIN
    IF TG_TABLE_NAME = 'account_issuer_tenant_draft_source_changes' THEN
        selected_change := NEW.source_change_id;
    ELSE
        selected_change := NEW.change_id;
    END IF;
    SELECT * INTO journal FROM account_issuer_tenant_draft_source_changes
        WHERE source_change_id = selected_change;
    IF NOT FOUND THEN
        RETURN NULL; -- Other V57 source owners and retained history remain untouched.
    END IF;
    SELECT status, binding INTO change_status, change_binding
        FROM account_draft_authorization_source_changes WHERE change_id = selected_change;
    IF change_status IS DISTINCT FROM journal.status
        OR change_binding IS DISTINCT FROM journal.source_change_binding THEN
        RAISE EXCEPTION 'Issuer/tenant journal and V57 source transition must commit atomically'
            USING ERRCODE = '23514';
    END IF;
    IF journal.status = 'SOURCE_COMMITTED' AND (
        journal.event_stream IS DISTINCT FROM 'account:auth-authority:v1:' || lower(journal.source_kind) || '/' || journal.scope_id
        OR journal.event_id IS DISTINCT FROM CASE journal.source_kind
            WHEN 'ISSUER' THEN 'account-issuer-authority-event-v1:' ELSE 'account-tenant-generation-event-v1:' END || journal.request_id::text
        OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_events
            WHERE outbox_stream_key = journal.event_stream AND outbox_sequence = journal.event_sequence
                AND request_id = journal.request_id::text AND event_id = journal.event_id
                AND event_digest = journal.event_digest AND payload = journal.event_payload)) THEN
        RAISE EXCEPTION 'Issuer/tenant completed request lacks its exact retained source event'
            USING ERRCODE = '23514';
    END IF;
    IF journal.status = 'WAITING' AND EXISTS (SELECT 1 FROM account_authority_outbox_events
        WHERE outbox_stream_key = 'account:auth-authority:v1:' || lower(journal.source_kind) || '/' || journal.scope_id
            AND request_id = journal.request_id::text) THEN
        RAISE EXCEPTION 'A WAITING issuer/tenant request cannot publish its source event'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_issuer_tenant_source_request_guard
    BEFORE INSERT OR UPDATE ON account_issuer_tenant_draft_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_issuer_tenant_source_journal_guard();
CREATE TRIGGER account_issuer_tenant_source_delete_guard
    BEFORE DELETE ON account_issuer_tenant_draft_source_changes
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_issuer_tenant_source_truncate_guard
    BEFORE TRUNCATE ON account_issuer_tenant_draft_source_changes
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE CONSTRAINT TRIGGER account_issuer_tenant_source_complete_guard
    AFTER INSERT OR UPDATE ON account_issuer_tenant_draft_source_changes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_issuer_tenant_source_link_guard();
CREATE CONSTRAINT TRIGGER account_issuer_tenant_source_v57_link_guard
    AFTER INSERT OR UPDATE ON account_draft_authorization_source_changes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_issuer_tenant_source_link_guard();
-- [jooq ignore stop]
