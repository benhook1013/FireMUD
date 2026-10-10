-- Unregistered distinct intake order. Every retained order remains pending: no settlement writer.
CREATE TABLE account_game_logic_intake_authorizations (
    operation_id UUID PRIMARY KEY,
    fence_id UUID NOT NULL UNIQUE,
    actor_account_uuid UUID NOT NULL REFERENCES accounts(account_uuid),
    tenant_uuid UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id),
    version_uuid UUID NOT NULL,
    intake_request_id UUID NOT NULL,
    source_digest VARCHAR(71) NOT NULL CHECK (source_digest ~ '^sha256:[0-9a-f]{64}$'),
    binding BYTEA NOT NULL CHECK (octet_length(binding) BETWEEN 1 AND 4194304),
    issuance_operation_id UUID NOT NULL REFERENCES account_control_ui_issuance_operations(operation_id),
    issuance_fence BIGINT NOT NULL CHECK (issuance_fence > 0),
    source_payload BYTEA NOT NULL CHECK (octet_length(source_payload) BETWEEN 1 AND 131072),
    issuance_bundle BYTEA NOT NULL CHECK (octet_length(issuance_bundle) BETWEEN 1 AND 131072),
    outbox_checkpoints BYTEA NOT NULL CHECK (octet_length(outbox_checkpoints) BETWEEN 1 AND 131072),
    producer_xid BIGINT NOT NULL DEFAULT txid_current(),
    ordered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_uuid, intake_request_id)
);
CREATE TABLE account_game_logic_intake_sources (
    operation_id UUID NOT NULL REFERENCES account_game_logic_intake_authorizations(operation_id),
    source_key VARCHAR(2048) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 131072),
    PRIMARY KEY (operation_id, source_key)
);
CREATE INDEX account_game_logic_intake_source_lookup ON account_game_logic_intake_sources(source_key, operation_id);

-- [jooq ignore start]
CREATE FUNCTION account_game_logic_intake_source_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM account_game_logic_intake_authorizations
        WHERE operation_id = NEW.operation_id AND producer_xid = txid_current()) THEN
        RAISE EXCEPTION 'Intake sources must accompany the original owner transaction' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_game_logic_intake_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    p INTEGER := 1;
    parsed BYTEA;
    source_bytes BYTEA;
    source_position INTEGER;
    kind TEXT;
    scope TEXT;
    source_key_value TEXT;
    seen TEXT[] := ARRAY[]::TEXT[];
    source_count INTEGER;
    snapshot JSONB;
    selected JSONB;
    issuer account_control_ui_issuance_operations%ROWTYPE;
BEGIN
    SELECT * INTO STRICT issuer FROM account_control_ui_issuance_operations
        WHERE operation_id = NEW.issuance_operation_id;
    IF issuer.status IS DISTINCT FROM 'COMMITTED' OR issuer.account_uuid IS DISTINCT FROM NEW.actor_account_uuid
        OR issuer.tenant_uuid IS DISTINCT FROM NEW.tenant_uuid OR issuer.source_payload IS DISTINCT FROM NEW.source_payload
        OR issuer.bundle_payload IS DISTINCT FROM NEW.issuance_bundle
        OR (convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->>'issuanceFence')::BIGINT IS DISTINCT FROM NEW.issuance_fence
        OR convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->'outboxCheckpoints'
            IS DISTINCT FROM convert_from(NEW.outbox_checkpoints, 'UTF8')::JSONB
        OR NEW.producer_xid <> txid_current()
        OR convert_from(NEW.source_payload, 'UTF8')::JSONB->>'accountId' IS DISTINCT FROM NEW.actor_account_uuid::TEXT
        OR convert_from(NEW.source_payload, 'UTF8')::JSONB->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT THEN
        RAISE EXCEPTION 'Intake requires exact committed creator issuance evidence' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    IF convert_from(parsed, 'UTF8') <> 'account-game-logic-intake-authorization/v1' THEN
        RAISE EXCEPTION 'Invalid intake schema' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    IF convert_from(parsed, 'UTF8') <> NEW.operation_id::TEXT THEN
        RAISE EXCEPTION 'Changed intake operation' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    IF convert_from(parsed, 'UTF8') <> NEW.fence_id::TEXT THEN
        RAISE EXCEPTION 'Changed intake fence' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    IF convert_from(parsed, 'UTF8') <> NEW.intake_request_id::TEXT THEN
        RAISE EXCEPTION 'Changed intake request' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    IF convert_from(parsed, 'UTF8') <> NEW.actor_account_uuid::TEXT THEN
        RAISE EXCEPTION 'Changed intake actor' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    IF 'sha256:' || encode(sha256(parsed), 'hex') <> NEW.source_digest THEN
        RAISE EXCEPTION 'Changed complete intake source digest' USING ERRCODE = '23514';
    END IF;
    snapshot := convert_from(parsed, 'UTF8')::JSONB;
    selected := (snapshot->>'bindingJson')::JSONB;
    IF snapshot->>'schema' IS DISTINCT FROM 'game-design-gameplay-rule-source-snapshot/v1'
        OR selected->>'canonicalTenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR selected->>'canonicalVersionId' IS DISTINCT FROM NEW.version_uuid::TEXT
        OR snapshot->>'bindingDigest' IS DISTINCT FROM
            'sha256:' || encode(sha256(convert_to(snapshot->>'bindingJson', 'UTF8')), 'hex') THEN
        RAISE EXCEPTION 'Changed selected source identity' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    source_count := convert_from(parsed, 'UTF8')::INTEGER;
    IF source_count < 1 OR source_count <> (SELECT count(*) FROM account_game_logic_intake_sources WHERE operation_id = NEW.operation_id)
        OR source_count IS DISTINCT FROM jsonb_array_length(convert_from(NEW.source_payload, 'UTF8')::JSONB->'sources') THEN
        RAISE EXCEPTION 'Incomplete intake source participation' USING ERRCODE = '23514';
    END IF;
    FOR i IN 1..source_count LOOP
        SELECT frame_value, next_position INTO source_bytes, p FROM account_publication_authorization_read_frame(NEW.binding, p);
        IF source_bytes IS DISTINCT FROM decode(convert_from(NEW.source_payload, 'UTF8')::JSONB->'sources'->>(i - 1), 'base64') THEN
            RAISE EXCEPTION 'Intake source differs from original complete issuance vector' USING ERRCODE = '23514';
        END IF;
        SELECT frame_value, next_position INTO parsed, source_position FROM account_publication_authorization_read_frame(source_bytes, 1);
        IF convert_from(parsed, 'UTF8') <> 'account-draft-source-evidence/v1' THEN
            RAISE EXCEPTION 'Invalid intake authority source schema' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO kind, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO scope, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        source_key_value := kind || ':' || scope;
        IF source_key_value = ANY(seen) OR NOT EXISTS (SELECT 1 FROM account_game_logic_intake_sources
            WHERE operation_id = NEW.operation_id AND source_key = source_key_value AND source_evidence = source_bytes) THEN
            RAISE EXCEPTION 'Changed intake authority participation' USING ERRCODE = '23514';
        END IF;
        seen := array_append(seen, source_key_value);
    END LOOP;
    IF p <> octet_length(NEW.binding) + 1 THEN
        RAISE EXCEPTION 'Trailing intake authorization evidence' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_game_logic_intake_complete
    AFTER INSERT ON account_game_logic_intake_authorizations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_game_logic_intake_complete_guard();
CREATE TRIGGER account_game_logic_intake_source_insert
    BEFORE INSERT ON account_game_logic_intake_sources FOR EACH ROW
    EXECUTE FUNCTION account_game_logic_intake_source_insert_guard();
CREATE TRIGGER account_game_logic_intake_immutable
    BEFORE UPDATE OR DELETE ON account_game_logic_intake_authorizations FOR EACH ROW
    EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_game_logic_intake_sources_immutable
    BEFORE UPDATE OR DELETE ON account_game_logic_intake_sources FOR EACH ROW
    EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_game_logic_intake_no_truncate
    BEFORE TRUNCATE ON account_game_logic_intake_authorizations FOR EACH STATEMENT
    EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_game_logic_intake_sources_no_truncate
    BEFORE TRUNCATE ON account_game_logic_intake_sources FOR EACH STATEMENT
    EXECUTE FUNCTION account_draft_authorization_immutable_guard();

-- Preserve the existing source-writer locks and Draft/publication decisions exactly.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_selected_publication_sources s WHERE s.source_key = ANY(source_keys)$anchor$;
    intake_guard TEXT := $guard$    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_game_logic_intake_sources s WHERE s.source_key = ANY(source_keys)
        ORDER BY s.operation_id LOOP
        PERFORM operation_id FROM account_game_logic_intake_authorizations
            WHERE operation_id = operation_value FOR UPDATE NOWAIT;
        RAISE EXCEPTION 'Distinct Game Logic intake remains pending' USING ERRCODE = '55P03';
    END LOOP;
$guard$;
BEGIN
    SELECT pg_get_functiondef('account_control_ui_hold_required_sources(TEXT[])'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one existing publication source-writer guard';
    END IF;
    EXECUTE replace(original, anchor, intake_guard || anchor);
END;
$migration$;
-- [jooq ignore stop]
