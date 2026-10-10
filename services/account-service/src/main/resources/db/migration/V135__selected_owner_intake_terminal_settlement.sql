-- Exact Automation COMMITTED_EMPTY terminal settlement. Entity participation and every
-- unresolved/ambiguous owner outcome remain pending indefinitely.
CREATE TABLE account_selected_owner_intake_settlements (
    operation_id UUID PRIMARY KEY
        REFERENCES account_selected_owner_intake_authorizations(operation_id),
    fence_id UUID NOT NULL UNIQUE,
    intake_request_id UUID NOT NULL,
    owner VARCHAR(32) NOT NULL CHECK (owner = 'AUTOMATION_SCRIPTING'),
    target_namespace VARCHAR(63) NOT NULL
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    tenant_uuid UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id),
    version_uuid UUID NOT NULL,
    binding_bytes BYTEA NOT NULL CHECK (octet_length(binding_bytes) BETWEEN 1 AND 16777216),
    binding_digest VARCHAR(71) NOT NULL CHECK (binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    terminal_read_request_id UUID NOT NULL,
    terminal_receipt_bytes BYTEA NOT NULL CHECK (octet_length(terminal_receipt_bytes) BETWEEN 1 AND 58736640),
    terminal_receipt_digest VARCHAR(71) NOT NULL CHECK (terminal_receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    receipt_bytes BYTEA NOT NULL CHECK (octet_length(receipt_bytes) BETWEEN 1 AND 92356608),
    receipt_digest VARCHAR(71) NOT NULL CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    settled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'),
    CHECK (fence_id <> '00000000-0000-0000-0000-000000000000'),
    CHECK (intake_request_id <> '00000000-0000-0000-0000-000000000000'),
    CHECK (terminal_read_request_id <> '00000000-0000-0000-0000-000000000000')
);

-- [jooq ignore start]
CREATE FUNCTION account_selected_owner_intake_settlement_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_selected_owner_intake_authorizations%ROWTYPE;
    reservation account_selected_owner_intake_source_read_reservations%ROWTYPE;
    p INTEGER := 1;
    q INTEGER := 1;
    source_position INTEGER := 1;
    receipt_position INTEGER := 1;
    parsed BYTEA;
    binding_scope BYTEA;
    content_bytes BYTEA;
    selected_bytes BYTEA;
    terminal_binding BYTEA;
    owner_receipt BYTEA;
    world_request_bytes BYTEA;
    world_inventory_bytes BYTEA;
    source_bytes BYTEA;
    source_kind TEXT;
    source_scope TEXT;
    source_key_value TEXT;
    previous_sort_key INTEGER[];
    current_sort_key INTEGER[];
    namespace_value TEXT;
    request_id_value UUID;
    owner_world_request_id UUID;
    count_text TEXT;
    count_value INTEGER;
    index_value INTEGER;
    local_key_value BIGINT;
    local_key_text TEXT;
    first_local_key_text TEXT;
    request_digest_text TEXT;
    ignored_text TEXT;
    marker TEXT;
    retained_timestamp TIMESTAMPTZ;
BEGIN
    -- Source writers and settlement serialize on Account's retained source rows in canonical
    -- sort order, followed by reservation, final authorization, then this settlement identity.
    PERFORM lock_row.source_key
        FROM account_draft_authorization_source_locks lock_row
        JOIN account_selected_owner_intake_source_read_sources source
            ON source.source_key = lock_row.source_key
        WHERE source.operation_id = NEW.operation_id
        ORDER BY account_publication_authorization_source_sort_key(lock_row.source_key)
        FOR UPDATE OF lock_row;
    SELECT * INTO STRICT reservation
        FROM account_selected_owner_intake_source_read_reservations
        WHERE operation_id = NEW.operation_id FOR UPDATE;
    SELECT * INTO STRICT original
        FROM account_selected_owner_intake_authorizations
        WHERE operation_id = NEW.operation_id FOR UPDATE;
    PERFORM operation_id FROM account_selected_owner_intake_settlements
        WHERE operation_id = NEW.operation_id FOR UPDATE;

    IF FOUND
        OR EXISTS (SELECT 1 FROM account_selected_owner_intake_source_read_aborts
            WHERE operation_id = NEW.operation_id)
        OR NEW.operation_id IS DISTINCT FROM original.operation_id
        OR NEW.fence_id IS DISTINCT FROM original.fence_id
        OR NEW.intake_request_id IS DISTINCT FROM original.intake_request_id
        OR NEW.owner IS DISTINCT FROM original.owner
        OR NEW.target_namespace IS DISTINCT FROM original.target_namespace
        OR NEW.tenant_uuid IS DISTINCT FROM original.tenant_uuid
        OR NEW.version_uuid IS DISTINCT FROM original.version_uuid
        OR original.owner IS DISTINCT FROM 'AUTOMATION_SCRIPTING'
        OR reservation.owner IS DISTINCT FROM original.owner
        OR reservation.operation_id IS DISTINCT FROM original.operation_id
        OR reservation.fence_id IS DISTINCT FROM original.fence_id
        OR reservation.intake_request_id IS DISTINCT FROM original.intake_request_id
        OR reservation.actor_account_uuid IS DISTINCT FROM original.actor_account_uuid
        OR reservation.tenant_uuid IS DISTINCT FROM original.tenant_uuid
        OR reservation.version_uuid IS DISTINCT FROM original.version_uuid
        OR NEW.binding_bytes IS DISTINCT FROM original.binding_bytes
        OR NEW.binding_digest IS DISTINCT FROM original.binding_digest
        OR original.binding_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(original.binding_bytes), 'hex') THEN
        RAISE EXCEPTION 'Settlement must bind one exact active Automation authorization'
            USING ERRCODE = '23514';
    END IF;

    -- Recheck every finalized source child against both the complete immutable binding and the
    -- retained preliminary child vector. A digest or parent-row match alone is insufficient.
    SELECT frame_value, next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(original.binding_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM 'account-automation-intake-authorization/v1' THEN
        RAISE EXCEPTION 'Settlement original binding is not Automation authorization'
            USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO content_bytes, p
        FROM account_publication_authorization_read_frame(original.binding_bytes, p);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(original.binding_bytes, p);
    IF marker IS DISTINCT FROM 'sha256:' || encode(sha256(content_bytes), 'hex') THEN
        RAISE EXCEPTION 'Settlement original content digest differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(original.binding_bytes, p);
    IF marker IS DISTINCT FROM 'spiffe://firemud/ns/' || original.target_namespace || '/sa/automation-scripting-service' THEN
        RAISE EXCEPTION 'Settlement original retention reader differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(original.binding_bytes, p);
    IF marker IS DISTINCT FROM 'AUTOMATION_INTAKE_RETENTION' THEN
        RAISE EXCEPTION 'Settlement original retention purpose differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO count_text, p
        FROM account_publication_authorization_read_frame(original.binding_bytes, p);
    IF count_text !~ '^[1-9][0-9]*$' THEN
        RAISE EXCEPTION 'Settlement original source count is malformed' USING ERRCODE = '23514';
    END IF;
    count_value := count_text::INTEGER;
    IF count_value > 256 THEN
        RAISE EXCEPTION 'Settlement original source vector exceeds its bound'
            USING ERRCODE = '23514';
    END IF;

    q := 1;
    SELECT frame_value, next_position INTO parsed, q
        FROM account_publication_authorization_read_frame(content_bytes, q);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM 'game-design-selected-owner-intake-source/v1' THEN
        RAISE EXCEPTION 'Settlement original selected content schema differs'
            USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO binding_scope, q
        FROM account_publication_authorization_read_frame(content_bytes, q);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, q
        FROM account_publication_authorization_read_frame(content_bytes, q);
    IF marker IS DISTINCT FROM 'sha256:' || encode(sha256(binding_scope), 'hex')
        OR binding_scope IS DISTINCT FROM reservation.scope_bytes
        OR reservation.scope_digest IS DISTINCT FROM 'sha256:' || encode(sha256(binding_scope), 'hex')
        OR original.content_bytes IS DISTINCT FROM content_bytes
        OR original.content_digest IS DISTINCT FROM 'sha256:' || encode(sha256(content_bytes), 'hex') THEN
        RAISE EXCEPTION 'Settlement original selected scope or content differs'
            USING ERRCODE = '23514';
    END IF;
    -- The finalized binding contains six exact selected family frames after its scope header.
    FOREACH ignored_text IN ARRAY ARRAY['COMMAND', 'REALM_POLICY', 'ASSET', 'GAMEPLAY_RULE', 'BRANDING', 'TEMPLATE_CONFIG'] LOOP
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, q
            FROM account_publication_authorization_read_frame(content_bytes, q);
        SELECT frame_value, next_position INTO parsed, q
            FROM account_publication_authorization_read_frame(content_bytes, q);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO namespace_value, q
            FROM account_publication_authorization_read_frame(content_bytes, q);
        IF marker IS DISTINCT FROM ignored_text
            OR namespace_value IS DISTINCT FROM 'sha256:' || encode(sha256(parsed), 'hex') THEN
            RAISE EXCEPTION 'Settlement selected family snapshot differs' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF q <> octet_length(content_bytes) + 1 THEN
        RAISE EXCEPTION 'Settlement selected content has trailing bytes' USING ERRCODE = '23514';
    END IF;

    -- Bind the full preliminary scope tuple and selected-target JSON to all retained columns.
    source_position := 1;
    SELECT frame_value, next_position INTO parsed, source_position
        FROM account_publication_authorization_read_frame(binding_scope, source_position);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM 'account-automation-intake-source-read/v1' THEN
        RAISE EXCEPTION 'Settlement original preliminary scope schema differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, source_position
        FROM account_publication_authorization_read_frame(binding_scope, source_position);
    IF marker IS DISTINCT FROM 'AUTOMATION_SCRIPTING' THEN
        RAISE EXCEPTION 'Settlement original scope owner differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO namespace_value, source_position
        FROM account_publication_authorization_read_frame(binding_scope, source_position);
    FOR index_value IN 1..4 LOOP
        SELECT frame_value, next_position INTO parsed, source_position
            FROM account_publication_authorization_read_frame(binding_scope, source_position);
        IF index_value = 1 AND convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.operation_id::TEXT
            OR index_value = 2 AND convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.fence_id::TEXT
            OR index_value = 3 AND convert_from(parsed, 'UTF8') IS DISTINCT FROM NEW.intake_request_id::TEXT
            OR index_value = 4 AND convert_from(parsed, 'UTF8') IS DISTINCT FROM original.actor_account_uuid::TEXT THEN
            RAISE EXCEPTION 'Settlement operation scope tuple differs' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    SELECT frame_value, next_position INTO selected_bytes, source_position
        FROM account_publication_authorization_read_frame(binding_scope, source_position);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, source_position
        FROM account_publication_authorization_read_frame(binding_scope, source_position);
    IF namespace_value IS DISTINCT FROM NEW.target_namespace
        OR marker IS DISTINCT FROM 'sha256:' || encode(sha256(selected_bytes), 'hex')
        OR convert_from(selected_bytes, 'UTF8')::JSONB->>'canonicalTenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR convert_from(selected_bytes, 'UTF8')::JSONB->>'canonicalVersionId' IS DISTINCT FROM NEW.version_uuid::TEXT THEN
        RAISE EXCEPTION 'Settlement original selected scope differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, source_position
        FROM account_publication_authorization_read_frame(binding_scope, source_position);
    IF marker IS DISTINCT FROM 'spiffe://firemud/ns/' || NEW.target_namespace || '/sa/account-service' THEN
        RAISE EXCEPTION 'Settlement source scope reader differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, source_position
        FROM account_publication_authorization_read_frame(binding_scope, source_position);
    IF marker IS DISTINCT FROM 'AUTOMATION_INTAKE_SOURCE'
        OR source_position <> octet_length(binding_scope) + 1 THEN
        RAISE EXCEPTION 'Settlement source scope purpose or trailing bytes differ'
            USING ERRCODE = '23514';
    END IF;

    FOR index_value IN 1..count_value LOOP
        SELECT frame_value, next_position INTO source_bytes, p
            FROM account_publication_authorization_read_frame(original.binding_bytes, p);
        source_position := 1;
        SELECT frame_value, next_position INTO parsed, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        IF convert_from(parsed, 'UTF8') IS DISTINCT FROM 'account-draft-source-evidence/v1' THEN
            RAISE EXCEPTION 'Settlement source evidence schema differs' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_kind, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO source_scope, source_position
            FROM account_publication_authorization_read_frame(source_bytes, source_position);
        source_key_value := source_kind || ':' || source_scope;
        current_sort_key := account_publication_authorization_source_sort_key(source_key_value);
        IF previous_sort_key IS NOT NULL AND current_sort_key <= previous_sort_key THEN
            RAISE EXCEPTION 'Settlement source vector is duplicated or unordered'
                USING ERRCODE = '23514';
        END IF;
        previous_sort_key := current_sort_key;
        IF NOT EXISTS (
            SELECT 1 FROM account_selected_owner_intake_sources source
            WHERE source.operation_id = NEW.operation_id
                AND source.source_key = source_key_value
                AND source.source_evidence = source_bytes)
            OR NOT EXISTS (
                SELECT 1 FROM account_selected_owner_intake_source_read_sources source
                WHERE source.operation_id = NEW.operation_id
                    AND source.source_key = source_key_value
                    AND source.source_evidence = source_bytes) THEN
            RAISE EXCEPTION 'Settlement source child differs from complete original vector'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF p <> octet_length(original.binding_bytes) + 1
        OR count_value IS DISTINCT FROM (SELECT count(*) FROM account_selected_owner_intake_sources
            WHERE operation_id = NEW.operation_id)
        OR count_value IS DISTINCT FROM (SELECT count(*) FROM account_selected_owner_intake_source_read_sources
            WHERE operation_id = NEW.operation_id) THEN
        RAISE EXCEPTION 'Settlement source child vector is incomplete' USING ERRCODE = '23514';
    END IF;

    IF NEW.receipt_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.receipt_bytes), 'hex')
        OR NEW.terminal_receipt_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(NEW.terminal_receipt_bytes), 'hex') THEN
        RAISE EXCEPTION 'Settlement or Automation receipt digest differs' USING ERRCODE = '23514';
    END IF;
    p := 1;
    SELECT frame_value, next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM 'account-selected-owner-intake-settlement/v1' THEN
        RAISE EXCEPTION 'Settlement receipt schema differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF marker IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'Settlement receipt version differs' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF parsed IS DISTINCT FROM NEW.binding_bytes THEN
        RAISE EXCEPTION 'Settlement receipt changed original binding bytes' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF marker IS DISTINCT FROM NEW.binding_digest THEN
        RAISE EXCEPTION 'Settlement receipt changed original binding digest' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF marker IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'Settlement terminal request schema differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO namespace_value, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO ignored_text, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    BEGIN request_id_value := ignored_text::UUID; EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Settlement terminal read correlation is malformed' USING ERRCODE = '23514'; END;
    IF request_id_value = '00000000-0000-0000-0000-000000000000'::UUID
        OR request_id_value::TEXT IS DISTINCT FROM ignored_text
        OR request_id_value = NEW.operation_id OR request_id_value = NEW.fence_id
        OR request_id_value = NEW.intake_request_id
        OR request_id_value IS DISTINCT FROM NEW.terminal_read_request_id THEN
        RAISE EXCEPTION 'Settlement terminal read correlation differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF namespace_value IS DISTINCT FROM NEW.target_namespace
        OR marker IS DISTINCT FROM 'spiffe://firemud/ns/' || NEW.target_namespace || '/sa/account-service' THEN
        RAISE EXCEPTION 'Settlement terminal reader differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF marker IS DISTINCT FROM 'AUTOMATION_INTAKE_TERMINAL_READ' THEN
        RAISE EXCEPTION 'Settlement terminal read purpose differs' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO terminal_binding, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF terminal_binding IS DISTINCT FROM NEW.binding_bytes OR marker IS DISTINCT FROM NEW.binding_digest THEN
        RAISE EXCEPTION 'Settlement terminal request changed original Account binding'
            USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO owner_receipt, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(NEW.receipt_bytes, p);
    IF owner_receipt IS DISTINCT FROM NEW.terminal_receipt_bytes
        OR marker IS DISTINCT FROM NEW.terminal_receipt_digest
        OR p <> octet_length(NEW.receipt_bytes) + 1 THEN
        RAISE EXCEPTION 'Settlement receipt differs from exact Automation terminal bytes'
            USING ERRCODE = '23514';
    END IF;

    -- The owner receipt has a closed canonical frame and embeds the complete same binding.
    p := 1;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    IF marker IS DISTINCT FROM 'automation-empty-selected-source-intake-receipt/v1' THEN
        RAISE EXCEPTION 'Automation terminal receipt schema is not COMMITTED_EMPTY'
            USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    IF marker IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'Automation terminal receipt version differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO namespace_value, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    FOR index_value IN 1..8 LOOP
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF index_value = 1 AND marker IS DISTINCT FROM NEW.operation_id::TEXT
            OR index_value = 2 AND marker IS DISTINCT FROM NEW.fence_id::TEXT
            OR index_value = 3 AND marker IS DISTINCT FROM NEW.intake_request_id::TEXT
            OR index_value = 4 AND marker IS DISTINCT FROM NEW.tenant_uuid::TEXT
            OR index_value = 5 AND marker IS DISTINCT FROM NEW.version_uuid::TEXT
            OR index_value = 6 AND marker IS DISTINCT FROM convert_from(selected_bytes, 'UTF8')::JSONB->>'commitId' THEN
            RAISE EXCEPTION 'Automation terminal identity differs from original order'
                USING ERRCODE = '23514';
        END IF;
        IF index_value = 7 THEN
            BEGIN PERFORM marker::UUID; EXCEPTION WHEN OTHERS THEN
                RAISE EXCEPTION 'Automation source revision identity is malformed'
                    USING ERRCODE = '23514'; END;
            IF marker::UUID = '00000000-0000-0000-0000-000000000000'::UUID
                OR marker::UUID::TEXT IS DISTINCT FROM marker THEN
                RAISE EXCEPTION 'Automation source revision identity is noncanonical'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF index_value = 8 AND marker !~ '^(0|[1-9][0-9]*)$' THEN
            RAISE EXCEPTION 'Automation source revision order is noncanonical'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    -- Skip source declaration and local key/request metadata, then verify the full bound receipt
    -- digest and authorization bytes before consuming the independent World evidence frames.
    FOR index_value IN 1..3 LOOP
        SELECT frame_value, next_position INTO parsed, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF index_value < 3 THEN
            local_key_text := convert_from(parsed, 'UTF8');
            BEGIN local_key_value := local_key_text::BIGINT; EXCEPTION WHEN OTHERS THEN
                RAISE EXCEPTION 'Automation local key is malformed' USING ERRCODE = '23514'; END;
            IF local_key_value <= 0 OR local_key_value::TEXT IS DISTINCT FROM local_key_text
                OR (index_value = 2 AND local_key_text IS NOT DISTINCT FROM first_local_key_text) THEN
                RAISE EXCEPTION 'Automation local key is noncanonical or repeated'
                    USING ERRCODE = '23514';
            END IF;
            IF index_value = 1 THEN first_local_key_text := local_key_text; END IF;
        ELSE
            request_digest_text := convert_from(parsed, 'UTF8');
            IF request_digest_text !~ '^sha256:[0-9a-f]{64}$' THEN
                RAISE EXCEPTION 'Automation caller request digest is malformed'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END LOOP;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    SELECT frame_value, next_position INTO parsed, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    IF marker IS DISTINCT FROM NEW.binding_digest OR parsed IS DISTINCT FROM NEW.binding_bytes THEN
        RAISE EXCEPTION 'Automation terminal receipt changed full original binding'
            USING ERRCODE = '23514';
    END IF;
    IF namespace_value IS DISTINCT FROM NEW.target_namespace THEN
        RAISE EXCEPTION 'Automation terminal namespace differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO ignored_text, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    BEGIN owner_world_request_id := ignored_text::UUID; EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Automation World read correlation is malformed' USING ERRCODE = '23514'; END;
    IF owner_world_request_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR owner_world_request_id::TEXT IS DISTINCT FROM ignored_text THEN
        RAISE EXCEPTION 'Automation World read correlation is noncanonical' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    IF marker !~ '^sha256:[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Automation World request digest is malformed' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO world_request_bytes, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    IF octet_length(world_request_bytes) NOT BETWEEN 1 AND 33554432
        OR marker IS DISTINCT FROM 'sha256:' || encode(sha256(world_request_bytes), 'hex') THEN
        RAISE EXCEPTION 'Automation World request bytes or digest differ' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    IF marker !~ '^sha256:[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Automation World inventory digest is malformed' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO world_inventory_bytes, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    IF octet_length(world_inventory_bytes) NOT BETWEEN 1 AND 8388608
        OR marker IS DISTINCT FROM 'sha256:' || encode(sha256(world_inventory_bytes), 'hex') THEN
        RAISE EXCEPTION 'Automation World inventory bytes or digest differ' USING ERRCODE = '23514';
    END IF;
    -- COMMITTED_EMPTY is represented only by a complete all-zero three-family census.
    FOR index_value IN 1..12 LOOP
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO count_text, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF count_text !~ '^(0|[1-9][0-9]*)$' OR count_text::BIGINT <> 0 THEN
            RAISE EXCEPTION 'Automation COMMITTED_EMPTY receipt contains a nonempty census'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO ignored_text, p
        FROM account_publication_authorization_read_frame(owner_receipt, p);
    IF ignored_text !~ '^[+-]?[0-9]{4,}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2}([.][0-9]{0,8}[1-9])?)?Z$'
        OR p <> octet_length(owner_receipt) + 1 THEN
        RAISE EXCEPTION 'Automation terminal receipt timestamp or trailing bytes are invalid'
            USING ERRCODE = '23514';
    END IF;
    BEGIN retained_timestamp := ignored_text::TIMESTAMPTZ; EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Automation terminal receipt timestamp is malformed'
            USING ERRCODE = '23514'; END;
    IF retained_timestamp IS NULL THEN
        RAISE EXCEPTION 'Automation terminal receipt timestamp is absent'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_selected_owner_intake_settlement_validate
    BEFORE INSERT ON account_selected_owner_intake_settlements
    FOR EACH ROW EXECUTE FUNCTION account_selected_owner_intake_settlement_guard();
CREATE TRIGGER account_selected_owner_intake_settlement_immutable
    BEFORE UPDATE OR DELETE ON account_selected_owner_intake_settlements
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_selected_owner_intake_settlement_no_truncate
    BEFORE TRUNCATE ON account_selected_owner_intake_settlements
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();

CREATE OR REPLACE FUNCTION account_selected_owner_intake_source_read_is_pending(operation_value UUID)
RETURNS BOOLEAN LANGUAGE SQL STABLE STRICT AS $$
    SELECT NOT EXISTS (
        SELECT 1 FROM account_selected_owner_intake_source_read_aborts aborted
        WHERE aborted.operation_id = operation_value)
      AND NOT EXISTS (
        SELECT 1 FROM account_selected_owner_intake_settlements settled
        WHERE settled.operation_id = operation_value);
$$;
-- [jooq ignore stop]
