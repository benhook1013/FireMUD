-- Distinct non-activating publication order. There is deliberately no terminal-write surface:
-- authenticated publication-owner readback is a subsequent integration boundary.
CREATE TABLE account_selected_publication_authorizations (
    operation_id UUID PRIMARY KEY,
    fence_id UUID NOT NULL UNIQUE,
    actor_account_uuid UUID NOT NULL REFERENCES accounts(account_uuid),
    tenant_uuid UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id),
    publish_request_id VARCHAR(256) NOT NULL CHECK (length(publish_request_id) > 0 AND octet_length(publish_request_id) <= 256),
    input_digest VARCHAR(71) NOT NULL CHECK (input_digest ~ '^sha256:[0-9a-f]{64}$'),
    binding BYTEA NOT NULL CHECK (octet_length(binding) BETWEEN 1 AND 1048576),
    world_evidence BYTEA NOT NULL CHECK (octet_length(world_evidence) BETWEEN 1 AND 4194304),
    issuance_operation_id UUID NOT NULL REFERENCES account_control_ui_issuance_operations(operation_id),
    issuance_fence BIGINT NOT NULL CHECK (issuance_fence > 0),
    source_payload BYTEA NOT NULL CHECK (octet_length(source_payload) BETWEEN 1 AND 131072),
    issuance_bundle BYTEA NOT NULL CHECK (octet_length(issuance_bundle) BETWEEN 1 AND 131072),
    outbox_checkpoints BYTEA NOT NULL CHECK (octet_length(outbox_checkpoints) BETWEEN 1 AND 131072),
    producer_xid BIGINT NOT NULL DEFAULT txid_current(),
    ordered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_uuid, publish_request_id)
);

CREATE TABLE account_selected_publication_sources (
    operation_id UUID NOT NULL REFERENCES account_selected_publication_authorizations(operation_id),
    source_key VARCHAR(512) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 131072),
    PRIMARY KEY (operation_id, source_key)
);
CREATE INDEX account_selected_publication_source_lookup ON account_selected_publication_sources(source_key, operation_id);

-- [jooq ignore start]
CREATE FUNCTION account_selected_publication_source_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM account_selected_publication_authorizations
        WHERE operation_id = NEW.operation_id AND producer_xid = txid_current()) THEN
        RAISE EXCEPTION 'Publication sources must accompany the original owner transaction' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_selected_publication_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    p INTEGER := 1;
    parsed BYTEA;
    input_bytes BYTEA;
    source_bytes BYTEA;
    source_position INTEGER;
    kind TEXT;
    scope TEXT;
    source_key_value TEXT;
    seen TEXT[] := ARRAY[]::TEXT[];
    source_count INTEGER;
    issuer account_control_ui_issuance_operations%ROWTYPE;
BEGIN
    SELECT * INTO issuer FROM account_control_ui_issuance_operations WHERE operation_id = NEW.issuance_operation_id;
    IF issuer.status IS DISTINCT FROM 'COMMITTED' OR issuer.account_uuid <> NEW.actor_account_uuid
        OR issuer.tenant_uuid <> NEW.tenant_uuid OR issuer.source_payload <> NEW.source_payload
        OR issuer.bundle_payload <> NEW.issuance_bundle
        OR (convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->>'issuanceFence')::BIGINT <> NEW.issuance_fence
        OR convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->'outboxCheckpoints'
            IS DISTINCT FROM convert_from(NEW.outbox_checkpoints, 'UTF8')::JSONB
        OR NEW.producer_xid <> txid_current()
        OR convert_from(NEW.source_payload, 'UTF8')::JSONB->>'accountId' <> NEW.actor_account_uuid::TEXT
        OR convert_from(NEW.source_payload, 'UTF8')::JSONB->>'tenantId' <> NEW.tenant_uuid::TEXT THEN
        RAISE EXCEPTION 'Publication requires exact original committed issuance evidence' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    IF convert_from(parsed, 'UTF8') <> 'account-publication-authorization/v1' THEN
        RAISE EXCEPTION 'Invalid publication schema' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    IF convert_from(parsed, 'UTF8') <> NEW.operation_id::TEXT THEN
        RAISE EXCEPTION 'Changed publication operation' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    IF convert_from(parsed, 'UTF8') <> NEW.fence_id::TEXT THEN
        RAISE EXCEPTION 'Changed publication fence' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO input_bytes, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    IF 'sha256:' || encode(sha256(input_bytes), 'hex') <> NEW.input_digest THEN
        RAISE EXCEPTION 'Changed publication input digest' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, source_position FROM account_publication_authorization_read_frame(input_bytes, 1);
    IF convert_from(parsed, 'UTF8') <> 'account-publication-input/v1' THEN
        RAISE EXCEPTION 'Invalid publication input schema' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, source_position FROM account_publication_authorization_read_frame(input_bytes, source_position);
    IF convert_from(parsed, 'UTF8') <> NEW.actor_account_uuid::TEXT THEN
        RAISE EXCEPTION 'Changed publication actor' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, source_position FROM account_publication_authorization_read_frame(input_bytes, source_position);
    IF convert_from(parsed, 'UTF8')::JSONB->'intent'->>'canonicalTenantId' <> NEW.tenant_uuid::TEXT
        OR convert_from(parsed, 'UTF8')::JSONB->'intent'->>'publishRequestId' <> NEW.publish_request_id THEN
        RAISE EXCEPTION 'Changed publication selection identity' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    source_count := convert_from(parsed, 'UTF8')::INTEGER;
    IF source_count < 1 OR source_count <> (SELECT count(*) FROM account_selected_publication_sources WHERE operation_id = NEW.operation_id)
        OR source_count <> jsonb_array_length(convert_from(NEW.source_payload, 'UTF8')::JSONB->'sources') THEN
        RAISE EXCEPTION 'Incomplete publication sources' USING ERRCODE = '23514';
    END IF;
    FOR i IN 1..source_count LOOP
        SELECT frame_value, next_position INTO source_bytes, p FROM account_publication_authorization_read_frame(NEW.binding, p);
        IF source_bytes <> decode(convert_from(NEW.source_payload, 'UTF8')::JSONB->'sources'->>(i - 1), 'base64') THEN
            RAISE EXCEPTION 'Publication source differs from original complete issuance vector' USING ERRCODE = '23514';
        END IF;
        SELECT frame_value, next_position INTO parsed, source_position FROM account_publication_authorization_read_frame(source_bytes, 1);
        IF convert_from(parsed, 'UTF8') <> 'account-draft-source-evidence/v1' THEN
            RAISE EXCEPTION 'Invalid publication source schema' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO kind, source_position FROM account_publication_authorization_read_frame(source_bytes, source_position);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO scope, source_position FROM account_publication_authorization_read_frame(source_bytes, source_position);
        source_key_value := kind || ':' || scope;
        IF source_key_value = ANY(seen) OR NOT EXISTS (SELECT 1 FROM account_selected_publication_sources
            WHERE operation_id = NEW.operation_id AND source_key = source_key_value AND source_evidence = source_bytes) THEN
            RAISE EXCEPTION 'Changed publication source participation' USING ERRCODE = '23514';
        END IF;
        seen := array_append(seen, source_key_value);
    END LOOP;
    IF p <> octet_length(NEW.binding) + 1 THEN
        RAISE EXCEPTION 'Trailing publication evidence' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER account_selected_publication_complete
    AFTER INSERT ON account_selected_publication_authorizations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_selected_publication_complete_guard();
CREATE TRIGGER account_selected_publication_source_insert
    BEFORE INSERT ON account_selected_publication_sources FOR EACH ROW
    EXECUTE FUNCTION account_selected_publication_source_insert_guard();
CREATE TRIGGER account_selected_publication_immutable
    BEFORE UPDATE OR DELETE ON account_selected_publication_authorizations
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_publication_sources_immutable
    BEFORE UPDATE OR DELETE ON account_selected_publication_sources
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_publication_no_truncate
    BEFORE TRUNCATE ON account_selected_publication_authorizations
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_publication_sources_no_truncate
    BEFORE TRUNCATE ON account_selected_publication_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();

-- Preserve original Draft ordering and add the separate publication participants. A Draft
-- terminal readback can never clear this publication hold.
CREATE OR REPLACE FUNCTION account_control_ui_hold_required_sources(source_keys TEXT[])
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    source_key_value TEXT;
    operation_value UUID;
BEGIN
    FOR source_key_value IN SELECT value FROM
        (SELECT DISTINCT value FROM unnest(source_keys) value WHERE value IS NOT NULL) exact_keys
        ORDER BY account_publication_authorization_source_sort_key(value) LOOP
        INSERT INTO account_draft_authorization_source_locks(source_key)
            VALUES (source_key_value) ON CONFLICT DO NOTHING;
        PERFORM source_key FROM account_draft_authorization_source_locks
            WHERE source_key = source_key_value FOR UPDATE NOWAIT;
    END LOOP;
    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_draft_authorization_sources s WHERE s.source_key = ANY(source_keys)
        ORDER BY s.operation_id LOOP
        PERFORM operation_id FROM account_draft_authorization_fences
            WHERE operation_id = operation_value FOR UPDATE NOWAIT;
        IF NOT account_draft_authorization_is_settled(operation_value) THEN
            RAISE EXCEPTION 'Required creator source is held until every original owner settles' USING ERRCODE = '55P03';
        END IF;
    END LOOP;
    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_selected_publication_sources s WHERE s.source_key = ANY(source_keys)
        ORDER BY s.operation_id LOOP
        PERFORM operation_id FROM account_selected_publication_authorizations
            WHERE operation_id = operation_value FOR UPDATE NOWAIT;
        RAISE EXCEPTION 'Distinct selected-publication operation remains pending' USING ERRCODE = '55P03';
    END LOOP;
END;
$$;
-- [jooq ignore stop]
