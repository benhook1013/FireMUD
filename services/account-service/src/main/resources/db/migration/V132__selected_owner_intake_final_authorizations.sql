-- Final Entity/Automation retention authorization. Without an authenticated owner terminal,
-- this order remains held indefinitely; there is deliberately no timeout or local settlement.
CREATE TABLE account_selected_owner_intake_authorizations (
    operation_id UUID PRIMARY KEY
        REFERENCES account_selected_owner_intake_source_read_reservations(operation_id),
    fence_id UUID NOT NULL UNIQUE,
    intake_request_id UUID NOT NULL,
    owner VARCHAR(32) NOT NULL CHECK (owner IN ('ENTITY_MANAGEMENT', 'AUTOMATION_SCRIPTING')),
    target_namespace VARCHAR(63) NOT NULL,
    actor_account_uuid UUID NOT NULL REFERENCES accounts(account_uuid),
    tenant_uuid UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id),
    version_uuid UUID NOT NULL,
    content_bytes BYTEA NOT NULL CHECK (octet_length(content_bytes) BETWEEN 1 AND 8388608),
    content_digest VARCHAR(71) NOT NULL CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    binding_bytes BYTEA NOT NULL CHECK (octet_length(binding_bytes) BETWEEN 1 AND 16777216),
    binding_digest VARCHAR(71) NOT NULL CHECK (binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    issuance_operation_id UUID NOT NULL REFERENCES account_control_ui_issuance_operations(operation_id),
    issuance_fence BIGINT NOT NULL CHECK (issuance_fence > 0),
    source_payload BYTEA NOT NULL CHECK (octet_length(source_payload) BETWEEN 1 AND 131072),
    issuance_bundle BYTEA NOT NULL CHECK (octet_length(issuance_bundle) BETWEEN 1 AND 131072),
    outbox_checkpoints BYTEA NOT NULL CHECK (octet_length(outbox_checkpoints) BETWEEN 1 AND 131072),
    producer_xid BIGINT NOT NULL DEFAULT txid_current(),
    finalized_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'),
    CHECK (fence_id <> '00000000-0000-0000-0000-000000000000'),
    CHECK (intake_request_id <> '00000000-0000-0000-0000-000000000000'),
    CHECK (actor_account_uuid <> '00000000-0000-0000-0000-000000000000'),
    CHECK (tenant_uuid <> '00000000-0000-0000-0000-000000000000'),
    CHECK (version_uuid <> '00000000-0000-0000-0000-000000000000'),
    UNIQUE (tenant_uuid, intake_request_id)
);

CREATE TABLE account_selected_owner_intake_sources (
    operation_id UUID NOT NULL REFERENCES account_selected_owner_intake_authorizations(operation_id),
    source_key VARCHAR(2048) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 131072),
    PRIMARY KEY (operation_id, source_key)
);
CREATE INDEX account_selected_owner_intake_source_lookup
    ON account_selected_owner_intake_sources(source_key, operation_id);

-- [jooq ignore start]
-- Serialize finalization and preliminary abort with the same sorted source-lock then reservation
-- order used by the existing V130 abort path.
CREATE OR REPLACE FUNCTION account_selected_owner_intake_source_read_abort_guard()
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
    IF NOT FOUND
        OR EXISTS (SELECT 1 FROM account_selected_owner_intake_source_read_aborts
            WHERE operation_id = NEW.operation_id)
        OR EXISTS (SELECT 1 FROM account_selected_owner_intake_authorizations
            WHERE operation_id = NEW.operation_id) THEN
        RAISE EXCEPTION 'Only an unfinalized active preliminary owner reservation can be aborted'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_selected_owner_intake_authorization_serialize()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_selected_owner_intake_source_read_reservations%ROWTYPE;
BEGIN
    PERFORM lock_row.source_key FROM account_draft_authorization_source_locks lock_row
        JOIN account_selected_owner_intake_source_read_sources source
            ON source.source_key = lock_row.source_key
        WHERE source.operation_id = NEW.operation_id
        ORDER BY account_publication_authorization_source_sort_key(lock_row.source_key)
        FOR UPDATE OF lock_row;
    SELECT * INTO STRICT original FROM account_selected_owner_intake_source_read_reservations
        WHERE operation_id = NEW.operation_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM account_selected_owner_intake_source_read_aborts
            WHERE operation_id = NEW.operation_id)
        OR EXISTS (SELECT 1 FROM account_selected_owner_intake_authorizations
            WHERE operation_id = NEW.operation_id)
        OR NEW.producer_xid <> txid_current()
        OR NEW.operation_id IS DISTINCT FROM original.operation_id
        OR NEW.fence_id IS DISTINCT FROM original.fence_id
        OR NEW.intake_request_id IS DISTINCT FROM original.intake_request_id
        OR NEW.owner IS DISTINCT FROM original.owner
        OR NEW.actor_account_uuid IS DISTINCT FROM original.actor_account_uuid
        OR NEW.tenant_uuid IS DISTINCT FROM original.tenant_uuid
        OR NEW.version_uuid IS DISTINCT FROM original.version_uuid
        OR NEW.issuance_operation_id IS DISTINCT FROM original.issuance_operation_id
        OR NEW.issuance_fence IS DISTINCT FROM original.issuance_fence
        OR NEW.source_payload IS DISTINCT FROM original.source_payload
        OR NEW.issuance_bundle IS DISTINCT FROM original.issuance_bundle
        OR NEW.outbox_checkpoints IS DISTINCT FROM original.outbox_checkpoints THEN
        RAISE EXCEPTION 'Finalization must bind the exact active preliminary owner reservation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_selected_owner_intake_authorization_serialize
    BEFORE INSERT ON account_selected_owner_intake_authorizations
    FOR EACH ROW EXECUTE FUNCTION account_selected_owner_intake_authorization_serialize();

CREATE FUNCTION account_selected_owner_intake_authorization_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    p INTEGER := 1;
    q INTEGER := 1;
    scope_position INTEGER := 1;
    source_position INTEGER := 1;
    parsed BYTEA;
    scope_bytes BYTEA;
    content_bytes BYTEA;
    selected_bytes BYTEA;
    source_bytes BYTEA;
    snapshot_bytes BYTEA;
    source_schema TEXT;
    text_value TEXT;
    scope_schema TEXT;
    scope_owner TEXT;
    namespace_value TEXT;
    operation_value TEXT;
    fence_value TEXT;
    request_value TEXT;
    actor_value TEXT;
    selected_digest TEXT;
    scope_digest TEXT;
    content_digest TEXT;
    binding_schema TEXT;
    expected_binding_schema TEXT;
    recipient_value TEXT;
    purpose_value TEXT;
    count_text TEXT;
    source_count INTEGER;
    max_source_count INTEGER;
    source_index INTEGER := 0;
    family_value TEXT;
    family_digest TEXT;
    family_schema TEXT;
    expected_family_schema TEXT;
    snapshot JSONB;
    source_kind TEXT;
    source_scope TEXT;
    source_key_value TEXT;
    generation_marker TEXT;
    vector JSONB;
    seen TEXT[] := ARRAY[]::TEXT[];
    previous_key TEXT;
    original account_selected_owner_intake_source_read_reservations%ROWTYPE;
    issuer account_control_ui_issuance_operations%ROWTYPE;
    expected_schema TEXT;
    expected_purpose TEXT;
    expected_reader TEXT;
    families TEXT[] := ARRAY['COMMAND', 'REALM_POLICY', 'ASSET', 'GAMEPLAY_RULE', 'BRANDING', 'TEMPLATE_CONFIG'];
BEGIN
    SELECT * INTO STRICT original FROM account_selected_owner_intake_source_read_reservations
        WHERE operation_id = NEW.operation_id;
    SELECT * INTO STRICT issuer FROM account_control_ui_issuance_operations
        WHERE operation_id = NEW.issuance_operation_id;
    IF NEW.producer_xid <> txid_current()
        OR issuer.status IS DISTINCT FROM 'COMMITTED'
        OR extract(epoch FROM clock_timestamp()) >= issuer.expires_at_epoch_second
        OR issuer.account_uuid IS DISTINCT FROM NEW.actor_account_uuid
        OR issuer.tenant_uuid IS DISTINCT FROM NEW.tenant_uuid
        OR issuer.source_payload IS DISTINCT FROM NEW.source_payload
        OR issuer.bundle_payload IS DISTINCT FROM NEW.issuance_bundle
        OR NEW.binding_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.binding_bytes), 'hex')
        OR NEW.content_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.content_bytes), 'hex')
        OR (convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->>'issuanceFence')::BIGINT
            IS DISTINCT FROM NEW.issuance_fence
        OR convert_from(NEW.issuance_bundle, 'UTF8')::JSONB->'outboxCheckpoints'
            IS DISTINCT FROM convert_from(NEW.outbox_checkpoints, 'UTF8')::JSONB
        OR original.scope_digest IS DISTINCT FROM 'sha256:' || encode(sha256(original.scope_bytes), 'hex')
        OR EXISTS (SELECT 1 FROM account_selected_owner_intake_source_read_aborts
            WHERE operation_id = NEW.operation_id) THEN
        RAISE EXCEPTION 'Finalized authorization requires exact current committed creator evidence'
            USING ERRCODE = '23514';
    END IF;

    -- The complete selected-owner authorization frame is closed and content-bound.
    SELECT frame_value, next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(NEW.binding_bytes, p);
    binding_schema := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO content_bytes, p
        FROM account_publication_authorization_read_frame(NEW.binding_bytes, p);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO content_digest, p
        FROM account_publication_authorization_read_frame(NEW.binding_bytes, p);
    IF content_bytes IS DISTINCT FROM NEW.content_bytes
        OR content_digest IS DISTINCT FROM NEW.content_digest THEN
        RAISE EXCEPTION 'Finalized authorization differs from exact selected content'
            USING ERRCODE = '23514';
    END IF;

    -- Decode preliminary scope from the content and require the exact retained owner tuple.
    SELECT frame_value, next_position INTO parsed, q
        FROM account_publication_authorization_read_frame(content_bytes, q);
    IF convert_from(parsed, 'UTF8') <> 'game-design-selected-owner-intake-source/v1' THEN
        RAISE EXCEPTION 'Unknown selected owner content schema' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO scope_bytes, q
        FROM account_publication_authorization_read_frame(content_bytes, q);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO scope_digest, q
        FROM account_publication_authorization_read_frame(content_bytes, q);
    IF scope_bytes IS DISTINCT FROM original.scope_bytes
        OR scope_digest IS DISTINCT FROM 'sha256:' || encode(sha256(scope_bytes), 'hex') THEN
        RAISE EXCEPTION 'Selected content scope differs from original reservation'
            USING ERRCODE = '23514';
    END IF;

    SELECT frame_value, next_position INTO parsed, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    scope_schema := convert_from(parsed, 'UTF8');
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO scope_owner, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO namespace_value, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO operation_value, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO fence_value, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO request_value, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO actor_value, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    SELECT frame_value, next_position INTO selected_bytes, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO selected_digest, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    IF scope_owner = 'ENTITY_MANAGEMENT' THEN
        expected_schema := 'account-entity-intake-source-read/v1';
        expected_purpose := 'ENTITY_INTAKE_SOURCE';
        expected_reader := 'spiffe://firemud/ns/' || namespace_value || '/sa/account-service';
        expected_binding_schema := 'account-entity-intake-authorization/v1';
    ELSIF scope_owner = 'AUTOMATION_SCRIPTING' THEN
        expected_schema := 'account-automation-intake-source-read/v1';
        expected_purpose := 'AUTOMATION_INTAKE_SOURCE';
        expected_reader := 'spiffe://firemud/ns/' || namespace_value || '/sa/account-service';
        expected_binding_schema := 'account-automation-intake-authorization/v1';
    ELSE
        RAISE EXCEPTION 'Unknown selected owner domain' USING ERRCODE = '23514';
    END IF;
    IF scope_schema IS DISTINCT FROM expected_schema
        OR namespace_value !~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        OR operation_value IS DISTINCT FROM NEW.operation_id::TEXT
        OR fence_value IS DISTINCT FROM NEW.fence_id::TEXT
        OR request_value IS DISTINCT FROM NEW.intake_request_id::TEXT
        OR actor_value IS DISTINCT FROM NEW.actor_account_uuid::TEXT
        OR selected_digest IS DISTINCT FROM 'sha256:' || encode(sha256(selected_bytes), 'hex')
        OR NEW.owner IS DISTINCT FROM scope_owner
        OR NEW.target_namespace IS DISTINCT FROM namespace_value
        OR NEW.actor_account_uuid::TEXT IS DISTINCT FROM actor_value
        OR NEW.tenant_uuid::TEXT IS DISTINCT FROM convert_from(selected_bytes, 'UTF8')::JSONB->>'canonicalTenantId'
        OR NEW.version_uuid::TEXT IS DISTINCT FROM convert_from(selected_bytes, 'UTF8')::JSONB->>'canonicalVersionId'
        THEN
        RAISE EXCEPTION 'Selected owner scope fields differ from finalized authorization'
            USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO text_value, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    IF text_value IS DISTINCT FROM expected_reader THEN
        RAISE EXCEPTION 'Selected owner Account reader differs from closed scope' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO text_value, scope_position
        FROM account_publication_authorization_read_frame(scope_bytes, scope_position);
    IF text_value IS DISTINCT FROM expected_purpose
        OR scope_position <> octet_length(scope_bytes) + 1 THEN
        RAISE EXCEPTION 'Selected owner scope has an unknown purpose or trailing bytes'
            USING ERRCODE = '23514';
    END IF;

    -- Exactly six named canonical snapshots, each tied to this exact selected Draft binding.
    FOR family_value IN SELECT unnest(families) LOOP
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO text_value, q
            FROM account_publication_authorization_read_frame(content_bytes, q);
        SELECT frame_value, next_position INTO snapshot_bytes, q
            FROM account_publication_authorization_read_frame(content_bytes, q);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO family_digest, q
            FROM account_publication_authorization_read_frame(content_bytes, q);
        IF text_value IS DISTINCT FROM family_value
            OR octet_length(snapshot_bytes) = 0
            OR family_digest IS DISTINCT FROM 'sha256:' || encode(sha256(snapshot_bytes), 'hex') THEN
            RAISE EXCEPTION 'Selected owner content family is missing, unknown, or changed'
                USING ERRCODE = '23514';
        END IF;
        snapshot := convert_from(snapshot_bytes, 'UTF8')::JSONB;
        family_schema := snapshot->>'schema';
        expected_family_schema := CASE family_value
            WHEN 'COMMAND' THEN 'game-design-command-source-snapshot/v1'
            WHEN 'REALM_POLICY' THEN 'game-design-realm-policy-snapshot/v1'
            WHEN 'ASSET' THEN 'game-design-ordinary-asset-source-snapshot/v1'
            WHEN 'GAMEPLAY_RULE' THEN 'game-design-gameplay-rule-source-snapshot/v1'
            WHEN 'BRANDING' THEN 'game-design-branding-asset-source-snapshot/v1'
            ELSE NULL END;
        IF jsonb_typeof(snapshot) IS DISTINCT FROM 'object'
            OR snapshot->>'bindingJson' IS DISTINCT FROM convert_from(selected_bytes, 'UTF8')
            OR snapshot->>'bindingDigest' IS DISTINCT FROM selected_digest
            OR (family_value = 'TEMPLATE_CONFIG'
                AND (family_schema IS NULL OR family_schema NOT IN (
                    'game-design-template-config-source-snapshot/v1',
                    'game-design-template-config-source-snapshot/v2')))
            OR (family_value <> 'TEMPLATE_CONFIG'
                AND family_schema IS DISTINCT FROM expected_family_schema) THEN
            RAISE EXCEPTION 'Selected owner family differs from exact selected Draft binding'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF q <> octet_length(content_bytes) + 1 THEN
        RAISE EXCEPTION 'Selected owner content has trailing or extra family bytes'
            USING ERRCODE = '23514';
    END IF;

    -- Final authorization reader/purpose and all source evidence frames are closed.
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO recipient_value, p
        FROM account_publication_authorization_read_frame(NEW.binding_bytes, p);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO purpose_value, p
        FROM account_publication_authorization_read_frame(NEW.binding_bytes, p);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO count_text, p
        FROM account_publication_authorization_read_frame(NEW.binding_bytes, p);
    IF binding_schema IS DISTINCT FROM expected_binding_schema
        OR recipient_value IS DISTINCT FROM
            (CASE scope_owner WHEN 'ENTITY_MANAGEMENT'
                THEN 'spiffe://firemud/ns/' || namespace_value || '/sa/entity-management-service'
                ELSE 'spiffe://firemud/ns/' || namespace_value || '/sa/automation-scripting-service' END)
        OR purpose_value IS DISTINCT FROM
            (CASE scope_owner WHEN 'ENTITY_MANAGEMENT' THEN 'ENTITY_INTAKE_RETENTION'
                ELSE 'AUTOMATION_INTAKE_RETENTION' END)
        OR count_text !~ '^[1-9][0-9]*$' THEN
        RAISE EXCEPTION 'Finalized authorization schema, reader, purpose, or source count is invalid'
            USING ERRCODE = '23514';
    END IF;
    max_source_count := (octet_length(NEW.binding_bytes) - p) / 4;
    IF length(count_text) > length(max_source_count::TEXT) THEN
        RAISE EXCEPTION 'Finalized source count exceeds remaining canonical frame capacity'
            USING ERRCODE = '23514';
    END IF;
    source_count := count_text::INTEGER;
    vector := convert_from(NEW.source_payload, 'UTF8')::JSONB->'sources';
    IF source_count < 1 OR source_count > max_source_count
        OR jsonb_typeof(vector) IS DISTINCT FROM 'array'
        OR jsonb_array_length(vector) IS DISTINCT FROM source_count
        OR (SELECT count(*) FROM account_selected_owner_intake_sources
            WHERE operation_id = NEW.operation_id) IS DISTINCT FROM source_count THEN
        RAISE EXCEPTION 'Finalized authorization requires the complete current source vector'
            USING ERRCODE = '23514';
    END IF;
    FOR source_index IN 1..source_count LOOP
        SELECT frame_value, next_position INTO source_bytes, p
            FROM account_publication_authorization_read_frame(NEW.binding_bytes, p);
        IF source_bytes IS DISTINCT FROM decode(vector->>(source_index - 1), 'base64')
            OR octet_length(source_bytes) > 131072 THEN
            RAISE EXCEPTION 'Finalized source differs from current issuer source vector'
                USING ERRCODE = '23514';
        END IF;
        source_position := 1;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_schema, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_kind, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_scope, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO generation_marker, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        IF generation_marker = 'PRESENT' THEN
            SELECT convert_from(frame_value, 'UTF8'), next_position INTO text_value, source_position
                FROM account_publication_authorization_read_frame(source_bytes, source_position);
            IF text_value !~ '^(0|[1-9][0-9]*)$' THEN
                RAISE EXCEPTION 'Invalid finalized source generation' USING ERRCODE = '23514';
            END IF;
        ELSIF generation_marker <> 'ABSENT' THEN
            RAISE EXCEPTION 'Invalid finalized source generation marker' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO text_value, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        IF text_value !~ '^(0|[1-9][0-9]*)$' THEN
            RAISE EXCEPTION 'Invalid finalized source version' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO generation_marker, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        IF generation_marker = 'PRESENT' THEN
            SELECT frame_value, next_position INTO parsed, source_position
                FROM account_publication_authorization_read_frame(source_bytes, source_position);
            IF octet_length(parsed) = 0 THEN
                RAISE EXCEPTION 'Invalid finalized source checkpoint stream' USING ERRCODE = '23514';
            END IF;
            SELECT convert_from(frame_value, 'UTF8'), next_position INTO text_value, source_position
                FROM account_publication_authorization_read_frame(source_bytes, source_position);
            IF text_value !~ '^(0|[1-9][0-9]*)$' THEN
                RAISE EXCEPTION 'Invalid finalized source checkpoint sequence' USING ERRCODE = '23514';
            END IF;
        ELSIF generation_marker <> 'ABSENT' THEN
            RAISE EXCEPTION 'Invalid finalized source checkpoint marker' USING ERRCODE = '23514';
        END IF;
        SELECT frame_value, next_position INTO parsed, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        source_key_value := source_kind || ':' || source_scope;
        IF source_schema <> 'account-draft-source-evidence/v1'
            OR source_kind NOT IN ('ISSUER', 'ACCOUNT', 'TENANT', 'MEMBERSHIP', 'GLOBAL_ROLES', 'CREATOR_PARTY', 'HOSTED_TERMS')
            OR source_scope = '' OR source_position <> octet_length(source_bytes) + 1
            OR octet_length(parsed) = 0 OR source_key_value = ANY(seen)
            OR (previous_key IS NOT NULL AND
                account_publication_authorization_source_sort_key(previous_key) >=
                account_publication_authorization_source_sort_key(source_key_value))
            OR (source_kind IN ('ACCOUNT', 'GLOBAL_ROLES') AND source_scope IS DISTINCT FROM actor_value)
            OR (source_kind = 'TENANT' AND source_scope IS DISTINCT FROM NEW.tenant_uuid::TEXT)
            OR (source_kind = 'MEMBERSHIP' AND source_scope IS DISTINCT FROM actor_value || '/' || NEW.tenant_uuid::TEXT)
            OR NOT EXISTS (SELECT 1 FROM account_selected_owner_intake_sources held
                WHERE held.operation_id = NEW.operation_id AND held.source_key = source_key_value
                    AND held.source_evidence = source_bytes) THEN
            RAISE EXCEPTION 'Malformed, changed, or incomplete finalized source evidence'
                USING ERRCODE = '23514';
        END IF;
        seen := array_append(seen, source_key_value);
        previous_key := source_key_value;
    END LOOP;
    IF p <> octet_length(NEW.binding_bytes) + 1 THEN
        RAISE EXCEPTION 'Finalized authorization has trailing bytes' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_selected_owner_intake_authorization_complete
    AFTER INSERT ON account_selected_owner_intake_authorizations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_selected_owner_intake_authorization_complete_guard();

CREATE FUNCTION account_selected_owner_intake_source_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM account_selected_owner_intake_authorizations
        WHERE operation_id = NEW.operation_id AND producer_xid = txid_current()) THEN
        RAISE EXCEPTION 'Finalized sources must accompany the original Account transaction'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_selected_owner_intake_source_validate
    BEFORE INSERT ON account_selected_owner_intake_sources FOR EACH ROW
    EXECUTE FUNCTION account_selected_owner_intake_source_insert_guard();

CREATE TRIGGER account_selected_owner_intake_immutable
    BEFORE UPDATE OR DELETE ON account_selected_owner_intake_authorizations
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_owner_intake_no_truncate
    BEFORE TRUNCATE ON account_selected_owner_intake_authorizations
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_owner_intake_sources_immutable
    BEFORE UPDATE OR DELETE ON account_selected_owner_intake_sources
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_owner_intake_sources_no_truncate
    BEFORE TRUNCATE ON account_selected_owner_intake_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
-- [jooq ignore stop]
