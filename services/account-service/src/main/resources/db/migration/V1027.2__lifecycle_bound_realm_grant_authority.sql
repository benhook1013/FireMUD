-- New lifecycle-bound owner storage only. Legacy grants are retained and are not promoted.
-- Expiry uses exact Instant seconds/nanos: PostgreSQL timestamps cannot retain nanoseconds.
CREATE TABLE account_lifecycle_bound_realm_grant_revisions (
    scope_id VARCHAR(1024) NOT NULL,
    grant_revision BIGINT NOT NULL CHECK (grant_revision > 0),
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    tenant_uuid UUID NOT NULL CHECK (tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    world_slug VARCHAR(120) NOT NULL CHECK (world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    realm_slug VARCHAR(120) NOT NULL CHECK (realm_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    playtest_lifecycle_id UUID NOT NULL CHECK (playtest_lifecycle_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_id UUID NOT NULL UNIQUE CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    actor_account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    operation VARCHAR(8) NOT NULL CHECK (operation IN ('CREATE', 'EXTEND', 'REVOKE', 'REGRANT')),
    expected_grant_revision BIGINT CHECK (expected_grant_revision > 0),
    state VARCHAR(8) NOT NULL CHECK (state IN ('ACTIVE', 'REVOKED')),
    grant_expiry_second BIGINT NOT NULL CHECK (grant_expiry_second BETWEEN -62135596800 AND 253402300799),
    grant_expiry_nano INTEGER NOT NULL CHECK (grant_expiry_nano BETWEEN 0 AND 999999999),
    fork_expiry_second BIGINT NOT NULL CHECK (fork_expiry_second BETWEEN -62135596800 AND 253402300799),
    fork_expiry_nano INTEGER NOT NULL CHECK (fork_expiry_nano BETWEEN 0 AND 999999999),
    audit_reason TEXT NOT NULL CHECK (length(btrim(audit_reason)) BETWEEN 1 AND 4096),
    mutation_preimage BYTEA NOT NULL CHECK (octet_length(mutation_preimage) BETWEEN 1 AND 32768),
    mutation_digest VARCHAR(64) NOT NULL CHECK (mutation_digest = encode(sha256(mutation_preimage), 'hex')),
    grant_expiry_text VARCHAR(30) NOT NULL,
    event_preimage BYTEA NOT NULL CHECK (octet_length(event_preimage) BETWEEN 1 AND 32768),
    event_payload BYTEA NOT NULL CHECK (octet_length(event_payload) BETWEEN 1 AND 32768),
    event_id VARCHAR(512) NOT NULL UNIQUE CHECK (event_id = 'realm-grant-event:v1:' || request_id::TEXT),
    grant_authority_generation UUID NOT NULL UNIQUE CHECK (grant_authority_generation <> '00000000-0000-0000-0000-000000000000'::UUID),
    event_digest VARCHAR(71) NOT NULL CHECK (event_digest = 'sha256:' || encode(sha256(event_preimage), 'hex')),
    outbox_stream_key VARCHAR(2048) NOT NULL,
    outbox_sequence BIGINT NOT NULL CHECK (outbox_sequence > 0),
    audit_event_id UUID NOT NULL UNIQUE REFERENCES account_audit_outbox(audit_event_id) ON DELETE RESTRICT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (scope_id, grant_revision),
    FOREIGN KEY (outbox_stream_key, outbox_sequence)
        REFERENCES account_authority_outbox_events(outbox_stream_key, outbox_sequence) ON DELETE RESTRICT,
    CHECK ((operation = 'CREATE' AND expected_grant_revision IS NULL AND grant_revision = 1)
        OR (operation <> 'CREATE' AND expected_grant_revision IS NOT NULL
            AND grant_revision = expected_grant_revision + 1)),
    CHECK ((operation = 'REVOKE' AND state = 'REVOKED') OR (operation <> 'REVOKE' AND state = 'ACTIVE'))
);

CREATE TABLE account_lifecycle_bound_realm_grants (
    scope_id VARCHAR(1024) PRIMARY KEY,
    grant_revision BIGINT NOT NULL CHECK (grant_revision > 0),
    FOREIGN KEY (scope_id, grant_revision)
        REFERENCES account_lifecycle_bound_realm_grant_revisions(scope_id, grant_revision)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

-- [jooq ignore start]
CREATE FUNCTION account_lifecycle_realm_grant_no_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Lifecycle-bound grant history and tombstones are retained and immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER account_lifecycle_realm_grant_history_immutable
    BEFORE UPDATE OR DELETE ON account_lifecycle_bound_realm_grant_revisions
    FOR EACH ROW EXECUTE FUNCTION account_lifecycle_realm_grant_no_mutation();
CREATE TRIGGER account_lifecycle_realm_grant_history_no_truncate
    BEFORE TRUNCATE ON account_lifecycle_bound_realm_grant_revisions
    FOR EACH STATEMENT EXECUTE FUNCTION account_lifecycle_realm_grant_no_mutation();
CREATE TRIGGER account_lifecycle_realm_grant_current_no_delete
    BEFORE DELETE ON account_lifecycle_bound_realm_grants
    FOR EACH ROW EXECUTE FUNCTION account_lifecycle_realm_grant_no_mutation();
CREATE TRIGGER account_lifecycle_realm_grant_current_no_truncate
    BEFORE TRUNCATE ON account_lifecycle_bound_realm_grants
    FOR EACH STATEMENT EXECUTE FUNCTION account_lifecycle_realm_grant_no_mutation();

CREATE FUNCTION account_lifecycle_realm_grant_head_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (TG_OP = 'INSERT' AND NEW.grant_revision <> 1)
        OR (TG_OP = 'UPDATE' AND (NEW.scope_id IS DISTINCT FROM OLD.scope_id
            OR NEW.grant_revision <> OLD.grant_revision + 1)) THEN
        RAISE EXCEPTION 'Lifecycle-bound grant head must advance exactly once' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_lifecycle_realm_grant_head_guard BEFORE INSERT OR UPDATE
    ON account_lifecycle_bound_realm_grants FOR EACH ROW
    EXECUTE FUNCTION account_lifecycle_realm_grant_head_guard();

CREATE FUNCTION account_lifecycle_realm_grant_revision_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    prior account_lifecycle_bound_realm_grant_revisions%ROWTYPE;
    head BIGINT;
    wire JSONB;
    preimage JSONB;
    expected_scope TEXT;
    scope_bytes TEXT;
    fields TEXT[];
    item TEXT;
    now_seconds NUMERIC;
    fraction TEXT;
BEGIN
    -- This is a storage consistency check of the shared codec's exact six framed segments.
    fields := ARRAY['grantScope/v1', NEW.account_uuid::TEXT, NEW.tenant_uuid::TEXT,
        NEW.world_slug, NEW.realm_slug, NEW.playtest_lifecycle_id::TEXT];
    scope_bytes := '';
    FOREACH item IN ARRAY fields LOOP
        scope_bytes := scope_bytes || octet_length(item)::TEXT || ':' || item;
    END LOOP;
    expected_scope := 'grant/v1/' || rtrim(translate(replace(encode(convert_to(scope_bytes, 'UTF8'), 'base64'), E'\n', ''), '+/', '-_'), '=');
    IF NEW.scope_id <> expected_scope
        OR NEW.outbox_stream_key <> 'account:auth-authority:v1:' || expected_scope THEN
        RAISE EXCEPTION 'Lifecycle-bound grant scope differs from exact five-field codec' USING ERRCODE = '23514';
    END IF;
    SELECT grant_revision INTO head FROM account_lifecycle_bound_realm_grants
        WHERE scope_id = NEW.scope_id FOR UPDATE;
    IF NEW.operation = 'CREATE' THEN
        IF head IS NOT NULL THEN
            RAISE EXCEPTION 'CREATE requires absent grant and tombstone' USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT * INTO prior FROM account_lifecycle_bound_realm_grant_revisions
            WHERE scope_id = NEW.scope_id AND grant_revision = head;
        IF head IS NULL OR head <> NEW.expected_grant_revision THEN
            RAISE EXCEPTION 'Grant revision differs from current head' USING ERRCODE = '23514';
        END IF;
        now_seconds := extract(epoch FROM clock_timestamp());
        IF NEW.operation IN ('EXTEND', 'REVOKE') AND (prior.state <> 'ACTIVE'
            OR prior.grant_expiry_second + prior.grant_expiry_nano / 1000000000.0 <= now_seconds
            OR NEW.fork_expiry_second + NEW.fork_expiry_nano / 1000000000.0 <= now_seconds) THEN
            RAISE EXCEPTION 'Mutation requires a currently active grant' USING ERRCODE = '23514';
        END IF;
        IF NEW.operation = 'EXTEND' AND (NEW.grant_expiry_second, NEW.grant_expiry_nano)
            <= (prior.grant_expiry_second, prior.grant_expiry_nano) THEN
            RAISE EXCEPTION 'Extension must increase explicit grant expiry' USING ERRCODE = '23514';
        END IF;
        IF NEW.operation = 'REVOKE' AND (NEW.grant_expiry_second, NEW.grant_expiry_nano)
            IS DISTINCT FROM (prior.grant_expiry_second, prior.grant_expiry_nano) THEN
            RAISE EXCEPTION 'Revocation retains original grant expiry as tombstone evidence' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.operation <> 'REVOKE' AND ((NEW.grant_expiry_second, NEW.grant_expiry_nano)
        > (NEW.fork_expiry_second, NEW.fork_expiry_nano)
        OR NEW.grant_expiry_second + NEW.grant_expiry_nano / 1000000000.0 <= extract(epoch FROM clock_timestamp())) THEN
        RAISE EXCEPTION 'Grant expiry must be future and bounded by current fork expiry' USING ERRCODE = '23514';
    END IF;
    IF NEW.grant_expiry_text !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3}|\.[0-9]{6}|\.[0-9]{9})?Z$'
        OR extract(epoch FROM regexp_replace(NEW.grant_expiry_text, '\.[0-9]+Z$', 'Z')::TIMESTAMPTZ) <> NEW.grant_expiry_second THEN
        RAISE EXCEPTION 'Exact grant expiry text differs from persisted seconds' USING ERRCODE = '23514';
    END IF;
    fraction := substring(NEW.grant_expiry_text FROM '\.([0-9]+)Z$');
    IF coalesce(rpad(fraction, 9, '0')::INTEGER, 0) <> NEW.grant_expiry_nano THEN
        RAISE EXCEPTION 'Exact grant expiry text differs from persisted nanoseconds' USING ERRCODE = '23514';
    END IF;
    wire := convert_from(NEW.event_payload, 'UTF8')::JSONB;
    preimage := convert_from(NEW.event_preimage, 'UTF8')::JSONB;
    IF wire - 'eventDigest' IS DISTINCT FROM preimage
        OR wire->>'eventDigest' IS DISTINCT FROM NEW.event_digest
        OR wire->>'schemaVersion' IS DISTINCT FROM 'account-auth-authority-event/v1'
        OR wire->>'eventType' IS DISTINCT FROM 'REALM_GRANT_CHANGED'
        OR wire->>'eventId' IS DISTINCT FROM NEW.event_id
        OR wire->>'requestId' IS DISTINCT FROM NEW.request_id::TEXT
        OR wire->>'actorAccountId' IS DISTINCT FROM NEW.actor_account_uuid::TEXT
        OR wire->>'auditReason' IS DISTINCT FROM NEW.audit_reason
        OR wire->>'mutationDigest' IS DISTINCT FROM NEW.mutation_digest
        OR wire->>'sourceScope' IS DISTINCT FROM NEW.scope_id
        OR wire->>'outboxStreamKey' IS DISTINCT FROM NEW.outbox_stream_key
        OR wire->>'outboxSequence' IS DISTINCT FROM NEW.outbox_sequence::TEXT
        OR wire->>'grantRevision' IS DISTINCT FROM NEW.grant_revision::TEXT
        OR wire->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR wire->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR wire->>'worldSlug' IS DISTINCT FROM NEW.world_slug
        OR wire->>'realmSlug' IS DISTINCT FROM NEW.realm_slug
        OR wire->>'playtestLifecycleId' IS DISTINCT FROM NEW.playtest_lifecycle_id::TEXT
        OR wire->>'mutationKind' IS DISTINCT FROM NEW.operation
        OR wire->>'grantState' IS DISTINCT FROM NEW.state
        OR wire->>'grantAuthorityGeneration' IS DISTINCT FROM NEW.grant_authority_generation::TEXT
        OR wire->>'grantExpiresAt' IS DISTINCT FROM NEW.grant_expiry_text THEN
        RAISE EXCEPTION 'Grant source event differs from immutable semantic receipt' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_lifecycle_realm_grant_revision_guard BEFORE INSERT
    ON account_lifecycle_bound_realm_grant_revisions FOR EACH ROW
    EXECUTE FUNCTION account_lifecycle_realm_grant_revision_guard();

CREATE FUNCTION account_lifecycle_realm_grant_commit_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM account_lifecycle_bound_realm_grants head
        WHERE head.scope_id = NEW.scope_id AND head.grant_revision >= NEW.grant_revision)
        OR NOT EXISTS (SELECT 1 FROM account_authority_outbox_events event
            WHERE event.outbox_stream_key = NEW.outbox_stream_key
                AND event.outbox_sequence = NEW.outbox_sequence
                AND event.request_id = NEW.request_id::TEXT AND event.event_id = NEW.event_id
                AND event.event_digest = NEW.event_digest AND event.payload = NEW.event_payload)
        OR NOT EXISTS (SELECT 1 FROM account_audit_outbox audit
            WHERE audit.audit_event_id = NEW.audit_event_id AND audit.scope = 'tenant'
                AND audit.tenant_identity_version = 2 AND audit.tenant_uuid = NEW.tenant_uuid
                AND audit.producer_service = 'account-service' AND audit.event_type = 'REALM_GRANT_CHANGED'
                AND audit.payload = convert_from(NEW.event_payload, 'UTF8')
                AND audit.payload_digest = 'sha256:' || encode(sha256(NEW.event_payload), 'hex')) THEN
        RAISE EXCEPTION 'Grant state, immutable result, source event and audit must commit together' USING ERRCODE = '23514';
    END IF;
    -- Real database clock at final constraint execution: no positive authority may commit expired.
    IF NEW.operation <> 'REVOKE' AND (NEW.grant_expiry_second + NEW.grant_expiry_nano / 1000000000.0 <= extract(epoch FROM clock_timestamp())
        OR NEW.fork_expiry_second + NEW.fork_expiry_nano / 1000000000.0 <= extract(epoch FROM clock_timestamp())) THEN
        RAISE EXCEPTION 'Grant or fork expired before final commit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_lifecycle_realm_grant_commit_guard AFTER INSERT
    ON account_lifecycle_bound_realm_grant_revisions DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_lifecycle_realm_grant_commit_guard();

-- A generic outbox caller cannot create a grant authority event without its owner receipt.
CREATE FUNCTION account_lifecycle_realm_grant_event_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.outbox_stream_key LIKE 'account:auth-authority:v1:grant/v1/%'
        AND NOT EXISTS (SELECT 1 FROM account_lifecycle_bound_realm_grant_revisions revision
            WHERE revision.outbox_stream_key = NEW.outbox_stream_key
                AND revision.outbox_sequence = NEW.outbox_sequence
                AND revision.event_id = NEW.event_id AND revision.event_digest = NEW.event_digest
                AND revision.request_id::TEXT = NEW.request_id AND revision.event_payload = NEW.payload) THEN
        RAISE EXCEPTION 'Canonical grant authority events require an exact atomic owner receipt' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_lifecycle_realm_grant_event_guard AFTER INSERT
    ON account_authority_outbox_events DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_lifecycle_realm_grant_event_guard();
-- [jooq ignore stop]
