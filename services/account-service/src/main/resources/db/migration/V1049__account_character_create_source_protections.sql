-- Private CHARACTER_CREATE owner storage. No RPC, controller, scheduled reconciler or default
-- composition is enabled here. Existing source-writer serialization remains authoritative.
CREATE TABLE account_character_create_intents (
    operation_id UUID PRIMARY KEY CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'),
    target_namespace TEXT NOT NULL CHECK (length(target_namespace) BETWEEN 1 AND 63),
    account_uuid UUID NOT NULL CHECK (account_uuid <> '00000000-0000-0000-0000-000000000000'),
    tenant_uuid UUID NOT NULL CHECK (tenant_uuid <> '00000000-0000-0000-0000-000000000000'),
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    connect_scope_id TEXT NOT NULL CHECK (length(connect_scope_id) BETWEEN 1 AND 2048),
    purpose TEXT NOT NULL CHECK (purpose = 'CHARACTER_CREATE'),
    action_bytes BYTEA NOT NULL CHECK (octet_length(action_bytes) BETWEEN 1 AND 33554432),
    mutation_digest TEXT NOT NULL CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
    original_source_readback BYTEA NOT NULL CHECK (octet_length(original_source_readback) BETWEEN 1 AND 33554432),
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE account_character_create_protections (
    protection_id UUID PRIMARY KEY CHECK (protection_id <> '00000000-0000-0000-0000-000000000000'),
    operation_id UUID NOT NULL UNIQUE REFERENCES account_character_create_intents(operation_id),
    protection_fence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE CHECK (protection_fence > 0),
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    membership_snapshot BYTEA NOT NULL CHECK (octet_length(membership_snapshot) BETWEEN 1 AND 1048576),
    entitlement_snapshot BYTEA NOT NULL CHECK (octet_length(entitlement_snapshot) BETWEEN 1 AND 1048576)
);
CREATE TABLE account_character_create_settlements (
    protection_id UUID PRIMARY KEY REFERENCES account_character_create_protections(protection_id),
    protection_fence BIGINT NOT NULL,
    operation_id UUID NOT NULL UNIQUE REFERENCES account_character_create_intents(operation_id),
    original_membership_snapshot BYTEA NOT NULL,
    original_entitlement_snapshot BYTEA NOT NULL,
    original_action BYTEA NOT NULL,
    original_source_readback BYTEA NOT NULL,
    entity_terminal_id UUID NOT NULL CHECK (entity_terminal_id <> '00000000-0000-0000-0000-000000000000'),
    entity_terminal_fence BIGINT NOT NULL CHECK (entity_terminal_fence > 0),
    outcome TEXT NOT NULL CHECK (outcome IN ('COMMITTED', 'ABORTED')),
    terminal_bytes BYTEA NOT NULL CHECK (octet_length(terminal_bytes) BETWEEN 1 AND 201326592),
    terminal_digest TEXT NOT NULL CHECK (terminal_digest = 'sha256:' || encode(sha256(terminal_bytes), 'hex')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- [jooq ignore start]
-- H2 DDL emulation maps PostgreSQL TEXT to CLOB; retain this native uniqueness constraint.
ALTER TABLE account_character_create_intents
    ADD CONSTRAINT account_character_create_intents_request_key
    UNIQUE (target_namespace, account_uuid, request_id);

CREATE FUNCTION account_character_create_source_keys(account_value UUID, tenant_value UUID)
RETURNS TEXT[] LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT ARRAY['ISSUER:firemud-account-service', 'ACCOUNT:' || account_value::TEXT,
        'GLOBAL_ROLES:' || account_value::TEXT, 'TENANT:' || tenant_value::TEXT,
        'MEMBERSHIP:' || account_value::TEXT || '/' || tenant_value::TEXT];
$$;

CREATE FUNCTION account_character_create_lock_acquisition_sources(account_value UUID, tenant_value UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE key_value TEXT;
BEGIN
    FOR key_value IN SELECT value FROM unnest(account_character_create_source_keys(account_value, tenant_value)) value
        ORDER BY account_publication_authorization_source_sort_key(value)
    LOOP
        INSERT INTO account_draft_authorization_source_locks(source_key)
            VALUES (key_value) ON CONFLICT DO NOTHING;
        PERFORM source_key FROM account_draft_authorization_source_locks
            WHERE source_key = key_value FOR UPDATE NOWAIT;
    END LOOP;
END;
$$;

-- Purpose-local strict protobuf reader, not a general decoding/authorization API. All known
-- CHARACTER_CREATE messages use only scalar nonnegative int64 and length-delimited fields.
-- Reject duplicate/out-of-order/unknown fields, wrong wire types, nonminimal and overflowing
-- varints, truncation, and empty explicitly encoded defaults. Maximum schema is thirteen fields.
CREATE FUNCTION account_character_create_wire_fields(payload BYTEA, wire_types INTEGER[])
RETURNS BYTEA[] LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE fields BYTEA[] := array_fill(NULL::BYTEA, ARRAY[cardinality(wire_types)]);
    offset_value INTEGER := 0; tag_value INTEGER; field_value INTEGER; previous_field INTEGER := 0;
    wire_value INTEGER; byte_value INTEGER; number_value NUMERIC; multiplier NUMERIC;
    count_value INTEGER; size_value INTEGER; output_value BYTEA;
BEGIN
    IF cardinality(wire_types) NOT BETWEEN 1 AND 13 OR octet_length(payload) > 201326592 THEN
        RAISE EXCEPTION 'Invalid CHARACTER_CREATE wire bound' USING ERRCODE = '23514';
    END IF;
    WHILE offset_value < octet_length(payload) LOOP
        tag_value := get_byte(payload, offset_value); offset_value := offset_value + 1;
        field_value := tag_value / 8; wire_value := tag_value % 8;
        IF tag_value >= 128 OR field_value <= previous_field
            OR field_value > cardinality(wire_types)
            OR wire_types[field_value] IS DISTINCT FROM wire_value THEN
            RAISE EXCEPTION 'Invalid CHARACTER_CREATE wire field' USING ERRCODE = '23514';
        END IF;
        previous_field := field_value; number_value := 0; multiplier := 1; count_value := 0;
        LOOP
            IF offset_value >= octet_length(payload) OR count_value >= 9 THEN
                RAISE EXCEPTION 'Invalid CHARACTER_CREATE wire varint' USING ERRCODE = '23514';
            END IF;
            byte_value := get_byte(payload, offset_value); offset_value := offset_value + 1;
            number_value := number_value + (byte_value % 128) * multiplier;
            count_value := count_value + 1;
            EXIT WHEN byte_value < 128;
            multiplier := multiplier * 128;
        END LOOP;
        IF (count_value > 1 AND byte_value = 0) OR number_value <= 0
            OR number_value > 9223372036854775807 THEN
            RAISE EXCEPTION 'Noncanonical CHARACTER_CREATE wire value' USING ERRCODE = '23514';
        END IF;
        IF wire_value = 0 THEN
            output_value := convert_to(number_value::BIGINT::TEXT, 'UTF8');
        ELSIF wire_value = 2 THEN
            IF number_value > octet_length(payload) - offset_value THEN
                RAISE EXCEPTION 'Truncated CHARACTER_CREATE wire value' USING ERRCODE = '23514';
            END IF;
            size_value := number_value::INTEGER;
            output_value := substring(payload FROM offset_value + 1 FOR size_value);
            offset_value := offset_value + size_value;
        ELSE
            RAISE EXCEPTION 'Unsupported CHARACTER_CREATE wire type' USING ERRCODE = '23514';
        END IF;
        fields[field_value] := output_value;
    END LOOP;
    RETURN fields;
END;
$$;

CREATE FUNCTION account_character_create_terminal_binding(
    terminal BYTEA, original account_character_create_intents,
    protection account_character_create_protections,
    terminal_id UUID, terminal_fence BIGINT, outcome TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE t BYTEA[]; request_fields BYTEA[]; selector_fields BYTEA[]; reference_fields BYTEA[];
    original_fields BYTEA[]; protection_fields BYTEA[]; remote_fields BYTEA[];
    expected_original BYTEA[]; remote_index INTEGER; required_index INTEGER;
BEGIN
    t := account_character_create_wire_fields(terminal, ARRAY[2,2,2,2,2,2,0,2,2,0,2,2,2]);
    FOREACH required_index IN ARRAY ARRAY[1,2,3,4,5,6,7,9,10,11,13] LOOP
        IF t[required_index] IS NULL THEN RETURN FALSE; END IF;
    END LOOP;
    IF convert_from(t[9], 'UTF8') IS DISTINCT FROM terminal_id::TEXT
        OR convert_from(t[10], 'UTF8') IS DISTINCT FROM terminal_fence::TEXT
        OR convert_from(t[13], 'UTF8') !~ '^sha256:[0-9a-f]{64}$'
        OR convert_from(t[6], 'UTF8') IS DISTINCT FROM (convert_from(t[6], 'UTF8')::UUID)::TEXT
        OR convert_from(t[6], 'UTF8') = '00000000-0000-0000-0000-000000000000'
        OR convert_from(t[11], 'UTF8') !~ 'Z$'
        OR convert_from(t[11], 'UTF8')::TIMESTAMPTZ <= 'epoch'::TIMESTAMPTZ THEN RETURN FALSE; END IF;
    IF outcome = 'COMMITTED' THEN
        IF t[7] IS DISTINCT FROM convert_to('1', 'UTF8') OR t[8] IS NULL OR t[12] IS NOT NULL
            OR convert_from(t[8], 'UTF8') IS DISTINCT FROM (convert_from(t[8], 'UTF8')::UUID)::TEXT
            OR convert_from(t[8], 'UTF8') = '00000000-0000-0000-0000-000000000000' THEN RETURN FALSE; END IF;
    ELSIF outcome = 'ABORTED' THEN
        IF t[7] IS DISTINCT FROM convert_to('2', 'UTF8') OR t[12] IS NULL OR t[8] IS NOT NULL
            OR convert_from(t[12], 'UTF8') IS DISTINCT FROM (convert_from(t[12], 'UTF8')::UUID)::TEXT
            OR convert_from(t[12], 'UTF8') = '00000000-0000-0000-0000-000000000000' THEN RETURN FALSE; END IF;
    ELSE RETURN FALSE; END IF;
    expected_original := ARRAY[convert_to(original.target_namespace, 'UTF8'),
        convert_to(original.operation_id::TEXT, 'UTF8'), convert_to(original.request_id::TEXT, 'UTF8'),
        convert_to(original.connect_scope_id, 'UTF8'), convert_to(original.account_uuid::TEXT, 'UTF8'),
        convert_to(original.purpose, 'UTF8'), original.action_bytes,
        convert_to(original.mutation_digest, 'UTF8'), original.original_source_readback];
    original_fields := account_character_create_wire_fields(t[2], ARRAY[2,2,2,2,2,2,2,2,2]);
    IF original_fields IS DISTINCT FROM expected_original THEN RETURN FALSE; END IF;
    protection_fields := account_character_create_wire_fields(t[3], ARRAY[2,2,0,2,2,2]);
    IF protection_fields[1] IS DISTINCT FROM t[2]
        OR protection_fields[2] IS DISTINCT FROM convert_to(protection.protection_id::TEXT, 'UTF8')
        OR protection_fields[3] IS DISTINCT FROM convert_to(protection.protection_fence::TEXT, 'UTF8')
        OR protection_fields[4] IS NULL
        OR convert_from(protection_fields[4], 'UTF8')::TIMESTAMPTZ IS DISTINCT FROM protection.acquired_at
        OR protection_fields[5] IS DISTINCT FROM protection.membership_snapshot
        OR protection_fields[6] IS DISTINCT FROM protection.entitlement_snapshot THEN RETURN FALSE; END IF;
    request_fields := account_character_create_wire_fields(t[1], ARRAY[2,2,2,2]);
    IF array_position(request_fields, NULL::BYTEA) IS NOT NULL THEN RETURN FALSE; END IF;
    selector_fields := account_character_create_wire_fields(request_fields[1], ARRAY[2,2,2]);
    IF selector_fields IS DISTINCT FROM ARRAY[expected_original[1], expected_original[2], expected_original[8]] THEN RETURN FALSE; END IF;
    reference_fields := account_character_create_wire_fields(request_fields[2], ARRAY[2,0]);
    IF reference_fields IS DISTINCT FROM ARRAY[protection_fields[2], protection_fields[3]] THEN RETURN FALSE; END IF;
    FOR remote_index IN 4..5 LOOP
        remote_fields := account_character_create_wire_fields(t[remote_index], ARRAY[2,2,0,2,2]);
        IF array_position(remote_fields, NULL::BYTEA) IS NOT NULL
            OR remote_fields[1] IS DISTINCT FROM t[3]
            OR convert_from(remote_fields[2], 'UTF8') IS DISTINCT FROM (convert_from(remote_fields[2], 'UTF8')::UUID)::TEXT
            OR convert_from(remote_fields[2], 'UTF8') = '00000000-0000-0000-0000-000000000000'
            OR convert_from(remote_fields[4], 'UTF8') !~ 'Z$'
            OR convert_from(remote_fields[4], 'UTF8')::TIMESTAMPTZ <= 'epoch'::TIMESTAMPTZ THEN RETURN FALSE; END IF;
        reference_fields := account_character_create_wire_fields(request_fields[remote_index - 1], ARRAY[2,0]);
        IF reference_fields IS DISTINCT FROM ARRAY[remote_fields[2], remote_fields[3]] THEN RETURN FALSE; END IF;
    END LOOP;
    RETURN TRUE;
END;
$$;

CREATE FUNCTION account_character_create_lock_settlement_sources(account_value UUID, tenant_value UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE key_value TEXT; locked_key TEXT;
BEGIN
    -- Settlement locks exactly the existing serialization identities. Calling the acquisition
    -- helper would incorrectly ask an operation to authorize mutation of its own held sources.
    FOR key_value IN SELECT value FROM unnest(account_character_create_source_keys(account_value, tenant_value)) value
        ORDER BY account_publication_authorization_source_sort_key(value)
    LOOP
        SELECT source_key INTO locked_key FROM account_draft_authorization_source_locks
            WHERE source_key = key_value FOR UPDATE NOWAIT;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Character creation source serialization row is missing' USING ERRCODE = '55P03';
        END IF;
    END LOOP;
END;
$$;

CREATE FUNCTION account_character_create_assert_no_pending(source_keys TEXT[])
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM protection.protection_id
        FROM account_character_create_protections protection
        JOIN account_character_create_intents intent USING (operation_id)
        WHERE account_character_create_source_keys(intent.account_uuid, intent.tenant_uuid) && source_keys
          AND NOT EXISTS (SELECT 1 FROM account_character_create_settlements settlement
              WHERE settlement.protection_id = protection.protection_id)
        ORDER BY protection.protection_id FOR UPDATE OF protection NOWAIT;
    IF FOUND THEN
        RAISE EXCEPTION 'Character creation remains protected pending an original definitive Entity terminal'
            USING ERRCODE = '55P03';
    END IF;
END;
$$;

CREATE FUNCTION account_character_create_protection_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original account_character_create_intents%ROWTYPE; membership JSONB;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Character creation protection requires READ COMMITTED' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT original FROM account_character_create_intents WHERE operation_id = NEW.operation_id;
    PERFORM account_character_create_lock_acquisition_sources(original.account_uuid, original.tenant_uuid);
    membership := convert_from(NEW.membership_snapshot, 'UTF8')::JSONB;
    IF membership->>'accountId' IS DISTINCT FROM original.account_uuid::TEXT
        OR membership->>'tenantId' IS DISTINCT FROM original.tenant_uuid::TEXT
        OR membership->>'issuerId' IS DISTINCT FROM 'firemud-account-service'
        OR membership->>'realmGrantApplicability' IS DISTINCT FROM 'NOT_APPLICABLE_PUBLIC_PRODUCTION'
        OR membership->>'membershipExists' IS DISTINCT FROM 'true'
        OR membership->>'membershipLifecycleState' IS DISTINCT FROM 'ACTIVE'
        OR membership->>'gameplayAdmissionAllowed' IS DISTINCT FROM 'true' THEN
        RAISE EXCEPTION 'Character creation membership snapshot differs from the original' USING ERRCODE = '23514';
    END IF;
    NEW.acquired_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_character_create_protection_binding BEFORE INSERT ON account_character_create_protections
    FOR EACH ROW EXECUTE FUNCTION account_character_create_protection_guard();

CREATE FUNCTION account_character_create_settlement_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original account_character_create_intents%ROWTYPE; protection account_character_create_protections%ROWTYPE;
BEGIN
    SELECT * INTO STRICT original FROM account_character_create_intents WHERE operation_id = NEW.operation_id;
    PERFORM account_character_create_lock_settlement_sources(original.account_uuid, original.tenant_uuid);
    SELECT * INTO STRICT protection FROM account_character_create_protections
        WHERE protection_id = NEW.protection_id FOR UPDATE;
    IF protection.operation_id IS DISTINCT FROM NEW.operation_id
        OR protection.protection_fence IS DISTINCT FROM NEW.protection_fence
        OR protection.membership_snapshot IS DISTINCT FROM NEW.original_membership_snapshot
        OR protection.entitlement_snapshot IS DISTINCT FROM NEW.original_entitlement_snapshot
        OR original.action_bytes IS DISTINCT FROM NEW.original_action
        OR original.original_source_readback IS DISTINCT FROM NEW.original_source_readback THEN
        RAISE EXCEPTION 'Character creation settlement differs from original protection' USING ERRCODE = '23514';
    END IF;
    IF NOT account_character_create_terminal_binding(NEW.terminal_bytes, original, protection,
        NEW.entity_terminal_id, NEW.entity_terminal_fence, NEW.outcome) THEN
        RAISE EXCEPTION 'Character creation terminal bytes are not the exact definitive original' USING ERRCODE = '23514';
    END IF;
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_character_create_settlement_binding BEFORE INSERT ON account_character_create_settlements
    FOR EACH ROW EXECUTE FUNCTION account_character_create_settlement_guard();

DO $migration$
DECLARE table_name TEXT; original TEXT; anchor TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['account_character_create_intents',
        'account_character_create_protections', 'account_character_create_settlements'] LOOP
        EXECUTE format('CREATE TRIGGER immutable_owner_rows BEFORE UPDATE OR DELETE ON %I '
            || 'FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard()', table_name);
        EXECUTE format('CREATE TRIGGER immutable_owner_truncate BEFORE TRUNCATE ON %I '
            || 'FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard()', table_name);
    END LOOP;
    anchor := $anchor$    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_draft_authorization_sources s$anchor$;
    SELECT pg_get_functiondef('account_control_ui_hold_required_sources(TEXT[])'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one existing Account source-writer anchor';
    END IF;
    EXECUTE replace(original, anchor,
        E'    PERFORM account_character_create_assert_no_pending(source_keys);\n' || anchor);
    anchor := $anchor$    FOR operation IN SELECT DISTINCT source.operation_id
        FROM account_draft_authorization_changed_scopes changed$anchor$;
    SELECT pg_get_functiondef('account_draft_authorization_source_change_guard()'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one existing Account source-change anchor';
    END IF;
    EXECUTE replace(original, anchor, $guard$    PERFORM account_character_create_assert_no_pending(
        ARRAY(SELECT source_key FROM account_draft_authorization_changed_scopes
            WHERE change_id = OLD.change_id));
$guard$ || anchor);
END;
$migration$;
-- [jooq ignore stop]
