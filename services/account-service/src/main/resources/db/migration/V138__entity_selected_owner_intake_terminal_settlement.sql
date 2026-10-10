-- Add Entity's authenticated closed COMMITTED_EMPTY receipt to the existing immutable family.
-- V135 Automation rows and receipt bytes are retained exactly; no pending predicate changes.
-- [jooq ignore start]
DO $migration$
DECLARE
    check_row RECORD;
    expected_limit TEXT;
    changed_count INTEGER := 0;
BEGIN
    FOR check_row IN
        SELECT constraint_row.conname, attribute_row.attname,
            pg_get_constraintdef(constraint_row.oid) AS definition
        FROM pg_constraint constraint_row
        JOIN pg_attribute attribute_row
            ON attribute_row.attrelid = constraint_row.conrelid
            AND constraint_row.conkey = ARRAY[attribute_row.attnum]::SMALLINT[]
        WHERE constraint_row.conrelid = 'account_selected_owner_intake_settlements'::REGCLASS
            AND constraint_row.contype = 'c'
            AND attribute_row.attname IN ('owner', 'terminal_receipt_bytes', 'receipt_bytes')
    LOOP
        expected_limit := CASE check_row.attname WHEN 'owner' THEN 'AUTOMATION_SCRIPTING'
            WHEN 'terminal_receipt_bytes' THEN '58736640' ELSE '92356608' END;
        IF position(expected_limit IN check_row.definition) = 0 THEN
            RAISE EXCEPTION 'Unexpected retained V135 settlement constraint: %', check_row.conname;
        END IF;
        EXECUTE format('ALTER TABLE account_selected_owner_intake_settlements DROP CONSTRAINT %I',
            check_row.conname);
        changed_count := changed_count + 1;
    END LOOP;
    IF changed_count <> 3 THEN
        RAISE EXCEPTION 'Expected all three retained V135 settlement constraints';
    END IF;
END;
$migration$;
-- [jooq ignore stop]
ALTER TABLE account_selected_owner_intake_settlements
    ADD CONSTRAINT selected_owner_settlement_owner_check
        CHECK (owner IN ('AUTOMATION_SCRIPTING', 'ENTITY_MANAGEMENT')),
    ADD CONSTRAINT selected_owner_settlement_terminal_size_check
        CHECK (octet_length(terminal_receipt_bytes) >= 1 AND
            ((owner = 'AUTOMATION_SCRIPTING' AND octet_length(terminal_receipt_bytes) <= 58736640)
            OR (owner = 'ENTITY_MANAGEMENT' AND octet_length(terminal_receipt_bytes) <= 83886080))),
    ADD CONSTRAINT selected_owner_settlement_receipt_size_check
        CHECK (octet_length(receipt_bytes) >= 1 AND
            ((owner = 'AUTOMATION_SCRIPTING' AND octet_length(receipt_bytes) <= 92356608)
            OR (owner = 'ENTITY_MANAGEMENT' AND octet_length(receipt_bytes) <= 117506048)));

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_selected_owner_intake_settlement_guard()
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
    template_snapshot JSONB;
    entity_declaration JSONB;
    revision_bytes BYTEA;
    schema_bytes BYTEA := ''::BYTEA;
    schema_digest_text TEXT;
    expected_family TEXT;
    entity_families TEXT[] := ARRAY['ACTOR_BODY_LAYOUT_ASSIGNMENTS', 'ARCHETYPE_ASSIGNMENTS', 'ARCHETYPE_CONSTRAINTS', 'ARCHETYPE_ROOTS', 'BALANCE_CURVE_ATTACHMENTS', 'BALANCE_CURVE_ROOTS', 'BODY_LAYOUT_MEMBERSHIPS', 'BODY_LAYOUT_ROOTS', 'CRAFTING_INGREDIENT_BINDINGS', 'CRAFTING_RECIPE_RESULT_BINDINGS', 'CRAFTING_RECIPE_ROOTS', 'EQUIPMENT_ATTACHMENT_RULES', 'EQUIPMENT_CAPABILITIES', 'EQUIPMENT_COMPATIBILITY_RULES', 'EQUIPMENT_OCCUPANCY_RULES', 'EQUIPMENT_SLOT_GROUPS', 'EQUIPMENT_SLOT_ROOTS', 'INBOUND_LOOT_BINDINGS', 'ITEM_TEMPLATE_ROOTS', 'LOOT_ITEM_MAPPINGS', 'LOOT_TABLE_ROOTS', 'NPC_TEMPLATE_ROOTS', 'OTHER_ACTOR_TEMPLATE_ROOTS'];
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
        OR original.owner NOT IN ('AUTOMATION_SCRIPTING', 'ENTITY_MANAGEMENT')
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
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM (CASE original.owner WHEN 'AUTOMATION_SCRIPTING' THEN 'account-automation-intake-authorization/v1' ELSE 'account-entity-intake-authorization/v1' END) THEN
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
    IF marker IS DISTINCT FROM 'spiffe://firemud/ns/' || original.target_namespace || (CASE original.owner WHEN 'AUTOMATION_SCRIPTING' THEN '/sa/automation-scripting-service' ELSE '/sa/entity-management-service' END) THEN
        RAISE EXCEPTION 'Settlement original retention reader differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
        FROM account_publication_authorization_read_frame(original.binding_bytes, p);
    IF marker IS DISTINCT FROM (CASE original.owner WHEN 'AUTOMATION_SCRIPTING' THEN 'AUTOMATION_INTAKE_RETENTION' ELSE 'ENTITY_INTAKE_RETENTION' END) THEN
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
        IF ignored_text = 'TEMPLATE_CONFIG' AND original.owner = 'ENTITY_MANAGEMENT' THEN
            template_snapshot := convert_from(parsed, 'UTF8')::JSONB;
        END IF;
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
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM (CASE original.owner WHEN 'AUTOMATION_SCRIPTING' THEN 'account-automation-intake-source-read/v1' ELSE 'account-entity-intake-source-read/v1' END) THEN
        RAISE EXCEPTION 'Settlement original preliminary scope schema differs' USING ERRCODE = '23514';
    END IF;
    SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, source_position
        FROM account_publication_authorization_read_frame(binding_scope, source_position);
    IF marker IS DISTINCT FROM original.owner THEN
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
    IF marker IS DISTINCT FROM (CASE original.owner WHEN 'AUTOMATION_SCRIPTING' THEN 'AUTOMATION_INTAKE_SOURCE' ELSE 'ENTITY_INTAKE_SOURCE' END)
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
    IF marker IS DISTINCT FROM (CASE original.owner WHEN 'AUTOMATION_SCRIPTING' THEN 'AUTOMATION_INTAKE_TERMINAL_READ' ELSE 'ENTITY_INTAKE_TERMINAL_READ' END) THEN
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

    IF original.owner = 'AUTOMATION_SCRIPTING' THEN
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
    IF ignored_text !~ '^(?:[0-9]{4}|-[0-9]{4}|-[1-9][0-9]{4,8}|[+][1-9][0-9]{4,8})-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:[.](?:[0-9]{3}|[0-9]{6}|[0-9]{9}))?)?Z$'
        OR ignored_text ~ '^-0000-'
        OR ignored_text ~ 'T(?:[01][0-9]|2[0-3]):[0-5][0-9]:00Z$'
        OR ignored_text ~ '[.]000Z$'
        OR ignored_text ~ '[.][0-9]{3}000Z$'
        OR ignored_text ~ '[.][0-9]{6}000Z$'
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

    ELSE
        -- Entity terminal bytes are a distinct closed owner receipt. Provider-row existence is
        -- established by the authenticated Entity terminal reader, never inferred by Account.
        p := 1;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF marker IS DISTINCT FROM 'entity-selected-empty-source-catalogue/v1' THEN
            RAISE EXCEPTION 'Entity terminal receipt schema differs' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF marker IS DISTINCT FROM '1' THEN
            RAISE EXCEPTION 'Entity terminal receipt version differs' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO schema_digest_text, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        FOREACH ignored_text IN ARRAY (ARRAY[
            'entity-selected-empty-source-catalogue-schema/v1',
            'entity-selected-empty-source-catalogue/v1',
            '1',
            'encoding=ADR-0047-byte-length-frames;strings=UTF-8;counts=canonical-decimal',
            'targetNamespace:utf8',
            'authorizationBindingBytes:canonical-original-account-binding',
            'authorizationBindingDigest:sha256',
            'selectedSourceBytes:canonical-game-design-source-content',
            'selectedSourceDigest:sha256',
            'selectedSourceRevisionBindingBytes:canonical-game-design-source-operation-binding',
            'selectedSourceRevisionBindingDigest:sha256',
            'worldReadRequestBytes:canonical-recipient-qualified-world-request',
            'worldReadRequestDigest:sha256',
            'worldClosureBytes:canonical-public-world-inventory',
            'worldClosureDigest:sha256',
            'localTenantKey:positive-canonical-decimal',
            'localVersionKey:positive-canonical-decimal',
            'familyStates:count-then-23-lexical-family-records-with-provider-kind',
            'genesisId:canonical-uuid',
            'requestDigest:sha256',
            'worldReadRequestId:canonical-uuid',
            'retainedAt:canonical-utc-offset-date-time',
            'familyRecordFields=count-then-family,state,evidenceKind,rowCount,unqualifiedRowCount,retainedRowCount,selectedScopeRowCount,referenceCount',
            'family:utf8',
            'state=EMPTY',
            'evidenceKind=V1_SOURCE_CENSUS|EMPTY_ONLY_OWNER_PROVIDER',
            'rowCount:canonical-nonnegative-decimal',
            'unqualifiedRowCount:canonical-nonnegative-decimal',
            'retainedRowCount:canonical-nonnegative-decimal',
            'selectedScopeRowCount:canonical-nonnegative-decimal',
            'referenceCount:canonical-nonnegative-decimal',
            '23'
        ] || entity_families) LOOP
            parsed := convert_to(ignored_text, 'UTF8');
            schema_bytes := schema_bytes || int4send(octet_length(parsed)) || parsed;
        END LOOP;
        IF schema_digest_text IS DISTINCT FROM 'sha256:' || encode(sha256(schema_bytes), 'hex') THEN
            RAISE EXCEPTION 'Entity terminal schema digest differs' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF marker IS DISTINCT FROM NEW.target_namespace THEN
            RAISE EXCEPTION 'Entity terminal namespace differs' USING ERRCODE = '23514';
        END IF;
        SELECT frame_value, next_position INTO parsed, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF parsed IS DISTINCT FROM NEW.binding_bytes OR marker IS DISTINCT FROM NEW.binding_digest THEN
            RAISE EXCEPTION 'Entity terminal changed original authorization' USING ERRCODE = '23514';
        END IF;
        SELECT frame_value, next_position INTO parsed, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF parsed IS DISTINCT FROM content_bytes
            OR marker IS DISTINCT FROM original.content_digest THEN
            RAISE EXCEPTION 'Entity terminal changed original selected source' USING ERRCODE = '23514';
        END IF;
        IF template_snapshot->>'schema' IS DISTINCT FROM 'game-design-template-config-source-snapshot/v2'
            OR jsonb_typeof(template_snapshot->'ownerSourceInventoryDeclarations') IS DISTINCT FROM 'array'
            OR (SELECT count(*) FROM jsonb_array_elements(template_snapshot->'ownerSourceInventoryDeclarations') declaration
                WHERE declaration->>'owner' = 'ENTITY_MANAGEMENT') <> 1 THEN
            RAISE EXCEPTION 'Entity original typed source declaration is unavailable' USING ERRCODE = '23514';
        END IF;
        SELECT declaration INTO STRICT entity_declaration
            FROM jsonb_array_elements(template_snapshot->'ownerSourceInventoryDeclarations') declaration
            WHERE declaration->>'owner' = 'ENTITY_MANAGEMENT';
        SELECT frame_value, next_position INTO revision_bytes, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF octet_length(revision_bytes) NOT BETWEEN 1 AND 4194304
            OR revision_bytes IS DISTINCT FROM convert_to(entity_declaration->>'sourceBindingJson', 'UTF8')
            OR marker IS DISTINCT FROM entity_declaration->>'sourceBindingDigest'
            OR marker IS DISTINCT FROM 'sha256:' || encode(sha256(revision_bytes), 'hex') THEN
            RAISE EXCEPTION 'Entity terminal changed original source revision binding' USING ERRCODE = '23514';
        END IF;
        SELECT frame_value, next_position INTO world_request_bytes, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF octet_length(world_request_bytes) NOT BETWEEN 1 AND 33554432
            OR marker IS DISTINCT FROM 'sha256:' || encode(sha256(world_request_bytes), 'hex') THEN
            RAISE EXCEPTION 'Entity World request bytes or digest differ' USING ERRCODE = '23514';
        END IF;
        SELECT frame_value, next_position INTO world_inventory_bytes, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF octet_length(world_inventory_bytes) NOT BETWEEN 1 AND 8388608
            OR marker IS DISTINCT FROM 'sha256:' || encode(sha256(world_inventory_bytes), 'hex') THEN
            RAISE EXCEPTION 'Entity World closure bytes or digest differ' USING ERRCODE = '23514';
        END IF;
        FOR index_value IN 1..2 LOOP
            SELECT convert_from(frame_value, 'UTF8'), next_position INTO local_key_text, p
                FROM account_publication_authorization_read_frame(owner_receipt, p);
            BEGIN local_key_value := local_key_text::BIGINT; EXCEPTION WHEN OTHERS THEN
                RAISE EXCEPTION 'Entity local key is malformed' USING ERRCODE = '23514'; END;
            IF local_key_value <= 0 OR local_key_value::TEXT IS DISTINCT FROM local_key_text
                OR (index_value = 2 AND local_key_text IS NOT DISTINCT FROM first_local_key_text) THEN
                RAISE EXCEPTION 'Entity local key is noncanonical or repeated' USING ERRCODE = '23514';
            END IF;
            IF index_value = 1 THEN first_local_key_text := local_key_text; END IF;
        END LOOP;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF marker IS DISTINCT FROM '23' THEN
            RAISE EXCEPTION 'Entity terminal requires all 23 families' USING ERRCODE = '23514';
        END IF;
        FOREACH expected_family IN ARRAY entity_families LOOP
            SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
                FROM account_publication_authorization_read_frame(owner_receipt, p);
            IF marker IS DISTINCT FROM expected_family THEN
                RAISE EXCEPTION 'Entity terminal family order differs' USING ERRCODE = '23514';
            END IF;
            SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
                FROM account_publication_authorization_read_frame(owner_receipt, p);
            IF marker IS DISTINCT FROM 'EMPTY' THEN
                RAISE EXCEPTION 'Entity terminal family is not EMPTY' USING ERRCODE = '23514';
            END IF;
            SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
                FROM account_publication_authorization_read_frame(owner_receipt, p);
            IF marker IS DISTINCT FROM (CASE WHEN expected_family IN (
                'ITEM_TEMPLATE_ROOTS', 'NPC_TEMPLATE_ROOTS', 'CRAFTING_RECIPE_ROOTS',
                'CRAFTING_RECIPE_RESULT_BINDINGS', 'CRAFTING_INGREDIENT_BINDINGS',
                'EQUIPMENT_SLOT_ROOTS', 'EQUIPMENT_SLOT_GROUPS', 'BODY_LAYOUT_ROOTS',
                'BODY_LAYOUT_MEMBERSHIPS') THEN 'V1_SOURCE_CENSUS' ELSE 'EMPTY_ONLY_OWNER_PROVIDER' END) THEN
                RAISE EXCEPTION 'Entity terminal family evidence kind differs' USING ERRCODE = '23514';
            END IF;
            FOR index_value IN 1..5 LOOP
                SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
                    FROM account_publication_authorization_read_frame(owner_receipt, p);
                IF marker IS DISTINCT FROM '0' THEN
                    RAISE EXCEPTION 'Entity EMPTY family contains nonzero or noncanonical counts'
                        USING ERRCODE = '23514';
                END IF;
            END LOOP;
        END LOOP;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO ignored_text, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        BEGIN request_id_value := ignored_text::UUID; EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'Entity genesis identity is malformed' USING ERRCODE = '23514'; END;
        IF request_id_value = '00000000-0000-0000-0000-000000000000'::UUID
            OR request_id_value::TEXT IS DISTINCT FROM ignored_text THEN
            RAISE EXCEPTION 'Entity genesis identity is noncanonical' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO marker, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF marker !~ '^sha256:[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'Entity original request digest is malformed' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO ignored_text, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        BEGIN request_id_value := ignored_text::UUID; EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'Entity World read identity is malformed' USING ERRCODE = '23514'; END;
        IF request_id_value = '00000000-0000-0000-0000-000000000000'::UUID
            OR request_id_value::TEXT IS DISTINCT FROM ignored_text THEN
            RAISE EXCEPTION 'Entity World read identity is noncanonical' USING ERRCODE = '23514';
        END IF;
        SELECT convert_from(frame_value, 'UTF8'), next_position INTO ignored_text, p
            FROM account_publication_authorization_read_frame(owner_receipt, p);
        IF ignored_text !~ '^(?:[0-9]{4}|-[0-9]{4}|-[1-9][0-9]{4,8}|[+][1-9][0-9]{4,8})-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:[.](?:[0-9]{3}|[0-9]{6}|[0-9]{9}))?)?Z$'
            OR ignored_text ~ '^-0000-'
            OR ignored_text ~ 'T(?:[01][0-9]|2[0-3]):[0-5][0-9]:00Z$'
            OR ignored_text ~ '[.]000Z$'
            OR ignored_text ~ '[.][0-9]{3}000Z$'
            OR ignored_text ~ '[.][0-9]{6}000Z$'
            OR p <> octet_length(owner_receipt) + 1 THEN
            RAISE EXCEPTION 'Entity terminal timestamp or trailing bytes are invalid' USING ERRCODE = '23514';
        END IF;
        BEGIN retained_timestamp := ignored_text::TIMESTAMPTZ; EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'Entity terminal timestamp is malformed' USING ERRCODE = '23514'; END;
        IF retained_timestamp IS NULL THEN
            RAISE EXCEPTION 'Entity terminal timestamp is absent' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- [jooq ignore stop]
