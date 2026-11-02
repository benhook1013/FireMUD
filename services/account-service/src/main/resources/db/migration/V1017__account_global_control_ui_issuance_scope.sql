-- Constant DEFAULT uses PostgreSQL's missing-value representation for retained rows;
-- no issuance evidence, response envelope, or installed Flyway history is rewritten.
ALTER TABLE account_control_ui_issuance_operations
    ADD COLUMN authorization_scope VARCHAR(16) NOT NULL DEFAULT 'TENANT';

ALTER TABLE account_control_ui_issuance_operations
    ALTER COLUMN tenant_uuid DROP NOT NULL;

ALTER TABLE account_control_ui_issuance_operations
    ADD CONSTRAINT account_control_ui_authorization_scope_shape CHECK (
        (authorization_scope = 'ACCOUNT' AND tenant_uuid IS NULL)
        OR (authorization_scope = 'TENANT' AND tenant_uuid IS NOT NULL)
    );

-- The existing tenant identity FK remains in force for TENANT rows. V90's JSONB
-- original-operation guard automatically includes authorization_scope as immutable.
-- Publication's older nullable <> comparison needs an explicit scope predicate;
-- retain the entire latest function body and its other original invariants.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_selected_publication_complete_guard()
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
    IF issuer.authorization_scope IS DISTINCT FROM 'TENANT'
        OR issuer.status IS DISTINCT FROM 'COMMITTED' OR issuer.account_uuid <> NEW.actor_account_uuid
        OR issuer.tenant_uuid <> NEW.tenant_uuid OR issuer.source_payload <> NEW.source_payload
        OR issuer.bundle_payload <> NEW.issuance_bundle
        OR (convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->>'issuanceFence')::BIGINT IS DISTINCT FROM NEW.issuance_fence
        OR convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->'outboxCheckpoints'
            IS DISTINCT FROM convert_from(NEW.outbox_checkpoints, 'UTF8')::JSONB
        OR NEW.producer_xid <> txid_current()
        OR convert_from(NEW.source_payload, 'UTF8')::JSONB->>'accountId' IS DISTINCT FROM NEW.actor_account_uuid::TEXT
        OR convert_from(NEW.source_payload, 'UTF8')::JSONB->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT THEN
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
    IF convert_from(parsed, 'UTF8')::JSONB->'intent'->>'canonicalTenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR convert_from(parsed, 'UTF8')::JSONB->'intent'->>'publishRequestId' IS DISTINCT FROM NEW.publish_request_id THEN
        RAISE EXCEPTION 'Changed publication selection identity' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p FROM account_publication_authorization_read_frame(NEW.binding, p);
    source_count := convert_from(parsed, 'UTF8')::INTEGER;
    IF source_count < 1 OR source_count IS DISTINCT FROM
            (SELECT count(*)::INTEGER FROM account_selected_publication_sources WHERE operation_id = NEW.operation_id)
        OR source_count IS DISTINCT FROM
            jsonb_array_length(convert_from(NEW.source_payload, 'UTF8')::JSONB->'sources') THEN
        RAISE EXCEPTION 'Incomplete publication sources' USING ERRCODE = '23514';
    END IF;
    FOR i IN 1..source_count LOOP
        SELECT frame_value, next_position INTO source_bytes, p FROM account_publication_authorization_read_frame(NEW.binding, p);
        IF source_bytes IS DISTINCT FROM
            decode(convert_from(NEW.source_payload, 'UTF8')::JSONB->'sources'->>(i - 1), 'base64') THEN
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
-- [jooq ignore stop]
