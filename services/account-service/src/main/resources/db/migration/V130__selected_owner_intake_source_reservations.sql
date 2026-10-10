-- Preliminary Entity/Automation source participation only; no finalized owner authorization.
CREATE TABLE account_selected_owner_intake_source_read_reservations (
    operation_id UUID PRIMARY KEY,
    fence_id UUID NOT NULL UNIQUE,
    intake_request_id UUID NOT NULL,
    owner VARCHAR(32) NOT NULL CHECK (owner IN ('ENTITY_MANAGEMENT', 'AUTOMATION_SCRIPTING')),
    actor_account_uuid UUID NOT NULL REFERENCES accounts(account_uuid),
    tenant_uuid UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id),
    version_uuid UUID NOT NULL,
    scope_bytes BYTEA NOT NULL CHECK (octet_length(scope_bytes) BETWEEN 1 AND 4194304),
    scope_digest VARCHAR(71) NOT NULL CHECK (scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    issuance_operation_id UUID NOT NULL REFERENCES account_control_ui_issuance_operations(operation_id),
    issuance_fence BIGINT NOT NULL CHECK (issuance_fence > 0),
    source_payload BYTEA NOT NULL CHECK (octet_length(source_payload) BETWEEN 1 AND 131072),
    issuance_bundle BYTEA NOT NULL CHECK (octet_length(issuance_bundle) BETWEEN 1 AND 131072),
    outbox_checkpoints BYTEA NOT NULL CHECK (octet_length(outbox_checkpoints) BETWEEN 1 AND 131072),
    producer_xid BIGINT NOT NULL DEFAULT txid_current(),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'),
    CHECK (fence_id <> '00000000-0000-0000-0000-000000000000'),
    CHECK (intake_request_id <> '00000000-0000-0000-0000-000000000000'),
    CHECK (actor_account_uuid <> '00000000-0000-0000-0000-000000000000'),
    CHECK (tenant_uuid <> '00000000-0000-0000-0000-000000000000'),
    CHECK (version_uuid <> '00000000-0000-0000-0000-000000000000'),
    UNIQUE (tenant_uuid, intake_request_id)
);
CREATE TABLE account_selected_owner_intake_source_read_sources (
    operation_id UUID NOT NULL REFERENCES account_selected_owner_intake_source_read_reservations(operation_id),
    source_key VARCHAR(2048) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 131072),
    PRIMARY KEY (operation_id, source_key)
);
CREATE INDEX account_selected_owner_intake_source_read_lookup
    ON account_selected_owner_intake_source_read_sources(source_key, operation_id);
CREATE TABLE account_selected_owner_intake_source_read_aborts (
    operation_id UUID PRIMARY KEY REFERENCES account_selected_owner_intake_source_read_reservations(operation_id),
    aborted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- [jooq ignore start]
CREATE FUNCTION account_selected_owner_intake_source_read_is_pending(operation_value UUID)
RETURNS BOOLEAN LANGUAGE SQL STABLE STRICT AS $$
    SELECT EXISTS (
        SELECT 1 FROM account_selected_owner_intake_source_read_reservations reservation
        WHERE reservation.operation_id = operation_value
          AND NOT EXISTS (
              SELECT 1 FROM account_selected_owner_intake_source_read_aborts aborted
              WHERE aborted.operation_id = reservation.operation_id));
$$;

CREATE FUNCTION account_selected_owner_intake_source_read_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    p INTEGER := 1;
    q INTEGER := 1;
    parsed BYTEA;
    selected_bytes BYTEA;
    selected JSONB;
    namespace_value TEXT;
    owner_value TEXT;
    purpose_value TEXT;
    reader_value TEXT;
    source_bytes BYTEA;
    source_kind TEXT;
    source_scope TEXT;
    source_key_value TEXT;
    marker TEXT;
    ignored_frame BYTEA;
    source_position INTEGER;
    source_count INTEGER := 0;
    seen TEXT[] := ARRAY[]::TEXT[];
    issuer account_control_ui_issuance_operations%ROWTYPE;
    original account_draft_authorization_fences%ROWTYPE;
    vector JSONB;
BEGIN
    SELECT * INTO STRICT issuer FROM account_control_ui_issuance_operations
        WHERE operation_id = NEW.issuance_operation_id;
    IF NEW.producer_xid <> txid_current()
        OR issuer.status IS DISTINCT FROM 'COMMITTED'
        OR extract(epoch FROM clock_timestamp()) >= issuer.expires_at_epoch_second
        OR issuer.account_uuid IS DISTINCT FROM NEW.actor_account_uuid
        OR issuer.tenant_uuid IS DISTINCT FROM NEW.tenant_uuid
        OR issuer.source_payload IS DISTINCT FROM NEW.source_payload
        OR issuer.bundle_payload IS DISTINCT FROM NEW.issuance_bundle
        OR (convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->>'issuanceFence')::BIGINT IS DISTINCT FROM NEW.issuance_fence
        OR convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->'outboxCheckpoints'
            IS DISTINCT FROM convert_from(NEW.outbox_checkpoints, 'UTF8')::JSONB
        OR NEW.scope_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.scope_bytes), 'hex') THEN
        RAISE EXCEPTION 'Preliminary owner scope requires exact current committed creator evidence'
            USING ERRCODE = '23514';
    END IF;

    SELECT frame_value, next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') NOT IN (
        'account-entity-intake-source-read/v1', 'account-automation-intake-source-read/v1') THEN
        RAISE EXCEPTION 'Invalid preliminary owner scope schema' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO owner_value, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF owner_value IS DISTINCT FROM NEW.owner
        OR (NEW.owner = 'ENTITY_MANAGEMENT'
            AND convert_from(parsed, 'UTF8') <> 'account-entity-intake-source-read/v1')
        OR (NEW.owner = 'AUTOMATION_SCRIPTING'
            AND convert_from(parsed, 'UTF8') <> 'account-automation-intake-source-read/v1') THEN
        RAISE EXCEPTION 'Preliminary owner schema and owner differ' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO namespace_value, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF namespace_value !~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$' THEN
        RAISE EXCEPTION 'Invalid preliminary owner namespace' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.operation_id::TEXT THEN
        RAISE EXCEPTION 'Changed preliminary owner operation' USING ERRCODE = '23514'; END IF;
    SELECT frame_value, next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.fence_id::TEXT THEN
        RAISE EXCEPTION 'Changed preliminary owner fence' USING ERRCODE = '23514'; END IF;
    SELECT frame_value, next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.intake_request_id::TEXT THEN
        RAISE EXCEPTION 'Changed preliminary owner request' USING ERRCODE = '23514'; END IF;
    SELECT frame_value, next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.actor_account_uuid::TEXT THEN
        RAISE EXCEPTION 'Changed preliminary owner actor' USING ERRCODE = '23514'; END IF;
    SELECT frame_value, next_position INTO selected_bytes, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    selected := convert_from(selected_bytes, 'UTF8')::JSONB;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM 'sha256:' || encode(sha256(selected_bytes), 'hex')
        OR selected->>'requestId' IS NULL OR selected->>'commitId' IS NULL
        OR jsonb_typeof(selected->'requiredOwners') IS DISTINCT FROM 'array'
        OR selected->>'canonicalTenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR selected->>'canonicalVersionId' IS DISTINCT FROM NEW.version_uuid::TEXT THEN
        RAISE EXCEPTION 'Changed preliminary owner complete selection' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO reader_value, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF reader_value IS DISTINCT FROM 'spiffe://firemud/ns/' || namespace_value || '/sa/account-service' THEN
        RAISE EXCEPTION 'Invalid preliminary owner intended reader' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO purpose_value, p
        FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF purpose_value IS DISTINCT FROM CASE NEW.owner
        WHEN 'ENTITY_MANAGEMENT' THEN 'ENTITY_INTAKE_SOURCE'
        WHEN 'AUTOMATION_SCRIPTING' THEN 'AUTOMATION_INTAKE_SOURCE'
        ELSE NULL END
        OR p <> octet_length(NEW.scope_bytes) + 1 THEN
        RAISE EXCEPTION 'Invalid preliminary owner purpose or trailing scope bytes' USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT original FROM account_draft_authorization_fences
        WHERE request_id = (selected->>'requestId')::UUID
          AND commit_id = (selected->>'commitId')::UUID;
    PERFORM account_draft_authorization_required_owners(original.binding);
    FOR i IN 1..11 LOOP
        SELECT frame_value, next_position INTO parsed, q
            FROM account_publication_authorization_read_frame(original.binding, q);
    END LOOP;
    SELECT frame_value, next_position INTO parsed, q
        FROM account_publication_authorization_read_frame(original.binding, q);
    IF parsed IS DISTINCT FROM selected_bytes
        OR original.ordering IS DISTINCT FROM 'COMMIT_ORDER'
        OR NOT account_draft_authorization_is_settled(original.operation_id)
        OR EXISTS (SELECT 1 FROM account_draft_authorization_owner_readbacks
            WHERE operation_id = original.operation_id AND outcome IS DISTINCT FROM 'COMMITTED')
        OR (SELECT count(*) FROM account_draft_authorization_owner_readbacks
            WHERE operation_id = original.operation_id)
            IS DISTINCT FROM cardinality(account_draft_authorization_required_owners(original.binding)) THEN
        RAISE EXCEPTION 'Exact original creator Draft must be committed and settled before source read'
            USING ERRCODE = '23514';
    END IF;

    vector := convert_from(NEW.source_payload, 'UTF8')::JSONB->'sources';
    IF jsonb_typeof(vector) IS DISTINCT FROM 'array'
        OR jsonb_array_length(vector) < 1
        OR jsonb_array_length(vector) IS DISTINCT FROM
            (SELECT count(*) FROM account_selected_owner_intake_source_read_sources
                WHERE operation_id = NEW.operation_id) THEN
        RAISE EXCEPTION 'Incomplete preliminary complete Account source participation'
            USING ERRCODE = '23514';
    END IF;
    FOR source_bytes IN SELECT decode(value, 'base64') FROM jsonb_array_elements_text(vector) LOOP
        source_count := source_count + 1;
        source_position := 1;
        SELECT frame_value, next_position INTO parsed, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        IF convert_from(parsed, 'UTF8') <> 'account-draft-source-evidence/v1' THEN
            RAISE EXCEPTION 'Invalid preliminary Account source schema' USING ERRCODE = '23514'; END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_kind, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        IF source_kind NOT IN ('ISSUER', 'ACCOUNT', 'TENANT', 'MEMBERSHIP', 'GLOBAL_ROLES', 'CREATOR_PARTY', 'HOSTED_TERMS') THEN
            RAISE EXCEPTION 'Invalid preliminary Account source kind' USING ERRCODE = '23514'; END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_scope, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        IF marker = 'PRESENT' THEN
            SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, source_position
                FROM account_publication_authorization_read_frame(source_bytes, source_position);
            IF marker !~ '^(0|[1-9][0-9]*)$' THEN
                RAISE EXCEPTION 'Invalid preliminary source generation' USING ERRCODE = '23514'; END IF;
        ELSIF marker <> 'ABSENT' THEN
            RAISE EXCEPTION 'Invalid preliminary source generation marker' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        IF marker !~ '^(0|[1-9][0-9]*)$' THEN
            RAISE EXCEPTION 'Invalid preliminary source version' USING ERRCODE = '23514'; END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        IF marker = 'PRESENT' THEN
            SELECT frame_value, next_position INTO ignored_frame, source_position
                FROM account_publication_authorization_read_frame(source_bytes, source_position);
            IF octet_length(ignored_frame) = 0 THEN
                RAISE EXCEPTION 'Invalid preliminary source checkpoint stream' USING ERRCODE = '23514'; END IF;
            SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, source_position
                FROM account_publication_authorization_read_frame(source_bytes, source_position);
            IF marker !~ '^(0|[1-9][0-9]*)$' THEN
                RAISE EXCEPTION 'Invalid preliminary source checkpoint sequence' USING ERRCODE = '23514'; END IF;
        ELSIF marker <> 'ABSENT' THEN
            RAISE EXCEPTION 'Invalid preliminary source checkpoint marker' USING ERRCODE = '23514';
        END IF;
        SELECT frame_value, next_position INTO ignored_frame, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        source_key_value := source_kind || ':' || source_scope;
        IF octet_length(source_bytes) > 131072 OR source_scope = ''
            OR source_position <> octet_length(source_bytes) + 1
            OR octet_length(ignored_frame) = 0
            OR source_key_value = ANY(seen)
            OR NOT EXISTS (SELECT 1 FROM account_selected_owner_intake_source_read_sources
                WHERE operation_id = NEW.operation_id AND source_key = source_key_value
                  AND source_evidence = source_bytes) THEN
            RAISE EXCEPTION 'Changed or noncanonical preliminary complete Account source vector'
                USING ERRCODE = '23514';
        END IF;
        seen := array_append(seen, source_key_value);
    END LOOP;
    IF source_count <> jsonb_array_length(vector) THEN
        RAISE EXCEPTION 'Incomplete preliminary Account source vector' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_selected_owner_intake_source_read_complete
    AFTER INSERT ON account_selected_owner_intake_source_read_reservations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_selected_owner_intake_source_read_complete_guard();

CREATE FUNCTION account_selected_owner_intake_source_read_source_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM account_selected_owner_intake_source_read_reservations
        WHERE operation_id = NEW.operation_id AND producer_xid = txid_current()) THEN
        RAISE EXCEPTION 'Preliminary owner sources must accompany original reservation transaction'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_selected_owner_intake_source_read_source_validate
    BEFORE INSERT ON account_selected_owner_intake_source_read_sources FOR EACH ROW
    EXECUTE FUNCTION account_selected_owner_intake_source_read_source_insert_guard();

CREATE FUNCTION account_selected_owner_intake_source_read_abort_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM lock_row.source_key FROM account_draft_authorization_source_locks lock_row
        JOIN account_selected_owner_intake_source_read_sources source
            ON source.source_key = lock_row.source_key
        WHERE source.operation_id = NEW.operation_id
        ORDER BY account_publication_authorization_source_sort_key(lock_row.source_key)
        FOR UPDATE OF lock_row;
    PERFORM operation_id FROM account_selected_owner_intake_source_read_reservations
        WHERE operation_id = NEW.operation_id FOR UPDATE;
    IF NOT FOUND OR EXISTS (SELECT 1 FROM account_selected_owner_intake_source_read_aborts
        WHERE operation_id = NEW.operation_id) THEN
        RAISE EXCEPTION 'Only the exact active preliminary owner reservation can be aborted'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_selected_owner_intake_source_read_abort_validate
    BEFORE INSERT ON account_selected_owner_intake_source_read_aborts FOR EACH ROW
    EXECUTE FUNCTION account_selected_owner_intake_source_read_abort_guard();

CREATE TRIGGER account_selected_owner_intake_source_read_immutable
    BEFORE UPDATE OR DELETE ON account_selected_owner_intake_source_read_reservations
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_owner_intake_source_read_no_truncate
    BEFORE TRUNCATE ON account_selected_owner_intake_source_read_reservations
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_owner_intake_source_read_sources_immutable
    BEFORE UPDATE OR DELETE ON account_selected_owner_intake_source_read_sources
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_owner_intake_source_read_sources_no_truncate
    BEFORE TRUNCATE ON account_selected_owner_intake_source_read_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_owner_intake_source_read_aborts_immutable
    BEFORE UPDATE OR DELETE ON account_selected_owner_intake_source_read_aborts
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_owner_intake_source_read_aborts_no_truncate
    BEFORE TRUNCATE ON account_selected_owner_intake_source_read_aborts
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();

-- Keep the current V129 assembled source-writer hook and add this independent owner participation.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_draft_authorization_sources s$anchor$;
    owner_guard TEXT := $guard$    FOR operation_value IN SELECT DISTINCT source.operation_id
        FROM account_selected_owner_intake_source_read_sources source
        WHERE source.source_key = ANY(source_keys)
          AND account_selected_owner_intake_source_read_is_pending(source.operation_id)
        ORDER BY source.operation_id LOOP
        PERFORM operation_id FROM account_selected_owner_intake_source_read_reservations
            WHERE operation_id = operation_value FOR UPDATE NOWAIT;
        RAISE EXCEPTION 'Selected owner intake source read remains pending for a required source'
            USING ERRCODE = '55P03';
    END LOOP;
$guard$;
BEGIN
    SELECT pg_get_functiondef('account_control_ui_hold_required_sources(TEXT[])'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one current Account source-writer anchor';
    END IF;
    EXECUTE replace(original, anchor, owner_guard || anchor);
END;
$migration$;

-- Pending reservations exclude terminal source changes after the current V129 sorted source locks.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    FOR operation IN SELECT DISTINCT source.operation_id
        FROM account_draft_authorization_changed_scopes changed$anchor$;
    owner_guard TEXT := $guard$    IF EXISTS (
        SELECT 1 FROM account_draft_authorization_changed_scopes changed
        JOIN account_selected_owner_intake_source_read_sources source
            ON source.source_key = changed.source_key
        WHERE changed.change_id = OLD.change_id
          AND account_selected_owner_intake_source_read_is_pending(source.operation_id)
    ) THEN
        PERFORM reservation.operation_id
            FROM account_draft_authorization_changed_scopes changed
            JOIN account_selected_owner_intake_source_read_sources source
                ON source.source_key = changed.source_key
            JOIN account_selected_owner_intake_source_read_reservations reservation
                ON reservation.operation_id = source.operation_id
            WHERE changed.change_id = OLD.change_id
              AND account_selected_owner_intake_source_read_is_pending(source.operation_id)
            ORDER BY reservation.operation_id FOR UPDATE OF reservation NOWAIT;
        RAISE EXCEPTION 'Pending selected owner intake source read excludes source-change completion'
            USING ERRCODE = '55P03';
    END IF;
$guard$;
BEGIN
    SELECT pg_get_functiondef('account_draft_authorization_source_change_guard()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one current V129 source-change anchor';
    END IF;
    EXECUTE replace(original, anchor, owner_guard || anchor);
END;
$migration$;

-- A hosted-terms dispatch/retry cannot disclose a source vector still reserved for an owner read.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    IF OLD.status = 'PREPARED' AND NEW.status = 'DISPATCH_AUTHORIZED'$anchor$;
    owner_guard TEXT := $guard$    IF OLD.status IN ('PREPARED', 'AMBIGUOUS')
        AND NEW.status = 'DISPATCH_AUTHORIZED' THEN
        PERFORM source_lock.source_key
            FROM account_draft_authorization_source_locks source_lock
            JOIN (
                SELECT OLD.source_key AS source_key
                UNION
                SELECT handoff_source.source_key
                FROM account_hosted_terms_disclosure_sources handoff_source
                WHERE handoff_source.handoff_id = OLD.handoff_id
            ) exact_source USING (source_key)
            ORDER BY account_publication_authorization_source_sort_key(source_lock.source_key)
            FOR UPDATE OF source_lock NOWAIT;
        IF EXISTS (
            SELECT 1 FROM account_selected_owner_intake_source_read_sources source
            WHERE source.source_key IN (
                SELECT OLD.source_key
                UNION
                SELECT handoff_source.source_key
                FROM account_hosted_terms_disclosure_sources handoff_source
                WHERE handoff_source.handoff_id = OLD.handoff_id)
              AND account_selected_owner_intake_source_read_is_pending(source.operation_id)
        ) THEN
            PERFORM reservation.operation_id
                FROM account_selected_owner_intake_source_read_sources source
                JOIN account_selected_owner_intake_source_read_reservations reservation
                    ON reservation.operation_id = source.operation_id
                WHERE source.source_key IN (
                    SELECT OLD.source_key
                    UNION
                    SELECT handoff_source.source_key
                    FROM account_hosted_terms_disclosure_sources handoff_source
                    WHERE handoff_source.handoff_id = OLD.handoff_id)
                  AND account_selected_owner_intake_source_read_is_pending(source.operation_id)
                ORDER BY reservation.operation_id FOR UPDATE OF reservation NOWAIT;
            RAISE EXCEPTION 'Pending selected owner intake source read excludes hosted-terms dispatch'
                USING ERRCODE = '55P03';
        END IF;
    END IF;
$guard$;
BEGIN
    SELECT pg_get_functiondef('account_hosted_terms_disclosure_handoff_guard()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one current V129 hosted-terms dispatch anchor';
    END IF;
    EXECUTE replace(original, anchor, owner_guard || anchor);
END;
$migration$;
-- [jooq ignore stop]
