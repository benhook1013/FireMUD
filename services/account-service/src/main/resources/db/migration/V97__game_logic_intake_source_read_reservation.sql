-- Forward-only preliminary source-read authority. Existing final v1 orders are never rewritten.
CREATE TABLE account_game_logic_intake_source_read_reservations (
    operation_id UUID PRIMARY KEY,
    fence_id UUID NOT NULL UNIQUE,
    intake_request_id UUID NOT NULL,
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
    UNIQUE (tenant_uuid, intake_request_id)
);
CREATE TABLE account_game_logic_intake_source_read_sources (
    operation_id UUID NOT NULL REFERENCES account_game_logic_intake_source_read_reservations(operation_id),
    source_key VARCHAR(2048) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 131072),
    PRIMARY KEY (operation_id, source_key)
);
CREATE INDEX account_game_logic_intake_source_read_lookup
    ON account_game_logic_intake_source_read_sources(source_key, operation_id);
CREATE TABLE account_game_logic_intake_source_read_aborts (
    operation_id UUID PRIMARY KEY REFERENCES account_game_logic_intake_source_read_reservations(operation_id),
    aborted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- [jooq ignore start]
CREATE FUNCTION account_game_logic_intake_source_read_is_pending(operation_value UUID)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM account_game_logic_intake_source_read_reservations r
        WHERE r.operation_id = operation_value
        AND NOT EXISTS (SELECT 1 FROM account_game_logic_intake_source_read_aborts a WHERE a.operation_id = r.operation_id)
        AND NOT EXISTS (SELECT 1 FROM account_game_logic_intake_authorizations f WHERE f.operation_id = r.operation_id));
$$;

CREATE FUNCTION account_game_logic_intake_source_read_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    p INTEGER := 1;
    q INTEGER := 1;
    parsed BYTEA;
    selected_bytes BYTEA;
    selected JSONB;
    namespace_value TEXT;
    source_bytes BYTEA;
    source_kind TEXT;
    source_scope TEXT;
    source_key_value TEXT;
    seen TEXT[] := ARRAY[]::TEXT[];
    issuer account_control_ui_issuance_operations%ROWTYPE;
    original account_draft_authorization_fences%ROWTYPE;
    vector JSONB;
BEGIN
    SELECT * INTO STRICT issuer FROM account_control_ui_issuance_operations WHERE operation_id = NEW.issuance_operation_id;
    IF NEW.producer_xid <> txid_current() OR issuer.status IS DISTINCT FROM 'COMMITTED'
        OR extract(epoch FROM clock_timestamp()) >= issuer.expires_at_epoch_second
        OR issuer.account_uuid IS DISTINCT FROM NEW.actor_account_uuid
        OR issuer.tenant_uuid IS DISTINCT FROM NEW.tenant_uuid
        OR issuer.source_payload IS DISTINCT FROM NEW.source_payload
        OR issuer.bundle_payload IS DISTINCT FROM NEW.issuance_bundle
        OR (convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->>'issuanceFence')::BIGINT IS DISTINCT FROM NEW.issuance_fence
        OR convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->'outboxCheckpoints'
            IS DISTINCT FROM convert_from(NEW.outbox_checkpoints, 'UTF8')::JSONB
        OR NEW.scope_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.scope_bytes), 'hex') THEN
        RAISE EXCEPTION 'Preliminary scope requires exact current committed creator evidence' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') <> 'account-game-logic-intake-source-read/v1' THEN
        RAISE EXCEPTION 'Invalid preliminary scope schema' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO namespace_value, p FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF namespace_value !~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$' THEN
        RAISE EXCEPTION 'Invalid preliminary scope namespace' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.operation_id::TEXT THEN
        RAISE EXCEPTION 'Changed preliminary operation' USING ERRCODE = '23514'; END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.fence_id::TEXT THEN
        RAISE EXCEPTION 'Changed preliminary fence' USING ERRCODE = '23514'; END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.intake_request_id::TEXT THEN
        RAISE EXCEPTION 'Changed preliminary request' USING ERRCODE = '23514'; END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.actor_account_uuid::TEXT THEN
        RAISE EXCEPTION 'Changed preliminary actor' USING ERRCODE = '23514'; END IF;
    SELECT frame_value, next_position INTO selected_bytes, p FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    selected := convert_from(selected_bytes, 'UTF8')::JSONB;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM 'sha256:' || encode(sha256(selected_bytes), 'hex')
        OR selected->>'canonicalTenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR selected->>'canonicalVersionId' IS DISTINCT FROM NEW.version_uuid::TEXT THEN
        RAISE EXCEPTION 'Changed preliminary selection' USING ERRCODE = '23514'; END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM 'spiffe://firemud/ns/' || namespace_value || '/sa/account-service' THEN
        RAISE EXCEPTION 'Invalid preliminary intended reader' USING ERRCODE = '23514'; END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.scope_bytes, p);
    IF convert_from(parsed, 'UTF8') <> 'GAME_LOGIC_INTAKE_SOURCE' OR p <> octet_length(NEW.scope_bytes) + 1 THEN
        RAISE EXCEPTION 'Invalid preliminary purpose or trailing bytes' USING ERRCODE = '23514'; END IF;

    SELECT * INTO STRICT original FROM account_draft_authorization_fences
        WHERE request_id = (selected->>'requestId')::UUID AND commit_id = (selected->>'commitId')::UUID;
    FOR i IN 1..12 LOOP
        SELECT frame_value, next_position INTO parsed, q FROM account_publication_authorization_read_frame(original.binding, q);
    END LOOP;
    IF parsed IS DISTINCT FROM selected_bytes OR original.ordering <> 'COMMIT_ORDER'
        OR NOT account_draft_authorization_is_settled(original.operation_id)
        OR EXISTS (SELECT 1 FROM account_draft_authorization_owner_readbacks WHERE operation_id = original.operation_id AND outcome <> 'COMMITTED') THEN
        RAISE EXCEPTION 'Exact original author must have committed settlement before source scope' USING ERRCODE = '23514'; END IF;

    vector := convert_from(NEW.source_payload, 'UTF8')::JSONB->'sources';
    IF jsonb_typeof(vector) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'Incomplete preliminary source participation' USING ERRCODE = '23514'; END IF;
    IF jsonb_array_length(vector) < 1 OR jsonb_array_length(vector) IS DISTINCT FROM
        (SELECT count(*) FROM account_game_logic_intake_source_read_sources WHERE operation_id = NEW.operation_id) THEN
        RAISE EXCEPTION 'Incomplete preliminary source participation' USING ERRCODE = '23514'; END IF;
    FOR source_bytes IN SELECT decode(value, 'base64') FROM jsonb_array_elements_text(vector) LOOP
        q := 1;
        SELECT frame_value, next_position INTO parsed, q FROM account_publication_authorization_read_frame(source_bytes, q);
        IF convert_from(parsed, 'UTF8') <> 'account-draft-source-evidence/v1' THEN
            RAISE EXCEPTION 'Invalid preliminary source schema' USING ERRCODE = '23514'; END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_kind, q FROM account_publication_authorization_read_frame(source_bytes, q);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_scope, q FROM account_publication_authorization_read_frame(source_bytes, q);
        source_key_value := source_kind || ':' || source_scope;
        IF source_key_value = ANY(seen) OR NOT EXISTS (SELECT 1 FROM account_game_logic_intake_source_read_sources
            WHERE operation_id = NEW.operation_id AND source_key = source_key_value AND source_evidence = source_bytes) THEN
            RAISE EXCEPTION 'Changed preliminary complete source vector' USING ERRCODE = '23514'; END IF;
        seen := array_append(seen, source_key_value);
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_game_logic_intake_source_read_complete
    AFTER INSERT ON account_game_logic_intake_source_read_reservations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_game_logic_intake_source_read_complete_guard();

CREATE FUNCTION account_game_logic_intake_source_read_source_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM account_game_logic_intake_source_read_reservations
        WHERE operation_id = NEW.operation_id AND producer_xid = txid_current()) THEN
        RAISE EXCEPTION 'Preliminary sources must accompany original reservation transaction' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_game_logic_intake_source_read_source_validate BEFORE INSERT ON account_game_logic_intake_source_read_sources
    FOR EACH ROW EXECUTE FUNCTION account_game_logic_intake_source_read_source_guard();

CREATE FUNCTION account_game_logic_intake_source_read_terminal_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_game_logic_intake_source_read_reservations%ROWTYPE;
    issuer account_control_ui_issuance_operations%ROWTYPE;
    p INTEGER := 1;
    q INTEGER := 1;
    parsed BYTEA;
    selected_bytes BYTEA;
    source_bytes BYTEA;
BEGIN
    PERFORM l.source_key FROM account_draft_authorization_source_locks l
        JOIN account_game_logic_intake_source_read_sources s ON s.source_key = l.source_key
        WHERE s.operation_id = NEW.operation_id ORDER BY account_publication_authorization_source_sort_key(l.source_key) FOR UPDATE OF l;
    SELECT * INTO STRICT original FROM account_game_logic_intake_source_read_reservations WHERE operation_id = NEW.operation_id FOR UPDATE;
    IF TG_TABLE_NAME = 'account_game_logic_intake_source_read_aborts' THEN
        IF EXISTS (SELECT 1 FROM account_game_logic_intake_authorizations WHERE operation_id = NEW.operation_id) THEN
            RAISE EXCEPTION 'Finalized intake cannot be preliminarily aborted' USING ERRCODE = '23514'; END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT issuer FROM account_control_ui_issuance_operations WHERE operation_id = original.issuance_operation_id;
    IF EXISTS (SELECT 1 FROM account_game_logic_intake_source_read_aborts WHERE operation_id = NEW.operation_id)
        OR extract(epoch FROM clock_timestamp()) >= issuer.expires_at_epoch_second OR issuer.status <> 'COMMITTED'
        OR NEW.fence_id IS DISTINCT FROM original.fence_id OR NEW.intake_request_id IS DISTINCT FROM original.intake_request_id
        OR NEW.actor_account_uuid IS DISTINCT FROM original.actor_account_uuid
        OR NEW.tenant_uuid IS DISTINCT FROM original.tenant_uuid OR NEW.version_uuid IS DISTINCT FROM original.version_uuid
        OR NEW.issuance_operation_id IS DISTINCT FROM original.issuance_operation_id
        OR NEW.issuance_fence IS DISTINCT FROM original.issuance_fence OR NEW.source_payload IS DISTINCT FROM original.source_payload
        OR NEW.issuance_bundle IS DISTINCT FROM original.issuance_bundle OR NEW.outbox_checkpoints IS DISTINCT FROM original.outbox_checkpoints THEN
        RAISE EXCEPTION 'Finalization requires original current un-aborted source scope' USING ERRCODE = '23514'; END IF;
    FOR i IN 1..7 LOOP
        SELECT frame_value, next_position INTO selected_bytes, p FROM account_publication_authorization_read_frame(original.scope_bytes, p);
    END LOOP;
    FOR i IN 1..6 LOOP
        SELECT frame_value, next_position INTO source_bytes, q FROM account_publication_authorization_read_frame(NEW.binding, q);
    END LOOP;
    IF selected_bytes IS DISTINCT FROM convert_to(convert_from(source_bytes, 'UTF8')::JSONB->>'bindingJson', 'UTF8') THEN
        RAISE EXCEPTION 'Finalization selected source differs from preliminary scope' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_game_logic_intake_source_read_abort_validate BEFORE INSERT ON account_game_logic_intake_source_read_aborts
    FOR EACH ROW EXECUTE FUNCTION account_game_logic_intake_source_read_terminal_guard();
-- A constraint trigger also enforces expiry at the final owner transaction's commit checks.
CREATE CONSTRAINT TRIGGER account_game_logic_intake_source_read_finalize_validate
    AFTER INSERT ON account_game_logic_intake_authorizations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_game_logic_intake_source_read_terminal_guard();

CREATE TRIGGER account_game_logic_intake_source_read_immutable BEFORE UPDATE OR DELETE ON account_game_logic_intake_source_read_reservations
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_game_logic_intake_source_read_no_truncate BEFORE TRUNCATE ON account_game_logic_intake_source_read_reservations
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_game_logic_intake_source_read_sources_immutable BEFORE UPDATE OR DELETE ON account_game_logic_intake_source_read_sources
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_game_logic_intake_source_read_sources_no_truncate BEFORE TRUNCATE ON account_game_logic_intake_source_read_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_game_logic_intake_source_read_aborts_immutable BEFORE UPDATE OR DELETE ON account_game_logic_intake_source_read_aborts
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_game_logic_intake_source_read_aborts_no_truncate BEFORE TRUNCATE ON account_game_logic_intake_source_read_aborts
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_game_logic_intake_sources s WHERE s.source_key = ANY(source_keys)$anchor$;
    preliminary_guard TEXT := $guard$    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_game_logic_intake_source_read_sources s WHERE s.source_key = ANY(source_keys)
        AND account_game_logic_intake_source_read_is_pending(s.operation_id) ORDER BY s.operation_id LOOP
        PERFORM operation_id FROM account_game_logic_intake_source_read_reservations
            WHERE operation_id = operation_value FOR UPDATE NOWAIT;
        RAISE EXCEPTION 'Preliminary Game Logic source read remains pending' USING ERRCODE = '55P03';
    END LOOP;
$guard$;
BEGIN
    SELECT pg_get_functiondef('account_control_ui_hold_required_sources(TEXT[])'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one existing intake source-writer guard'; END IF;
    EXECUTE replace(original, anchor, preliminary_guard || anchor);
END;
$migration$;
-- [jooq ignore stop]
