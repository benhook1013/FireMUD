-- This immutable holder orders real owner source writers around a future fresh-tenant flow.
-- It is data only: it grants no permission to dispatch Game Design or complete tenant creation.
CREATE TABLE account_tenant_creation_source_orders (
    creation_operation_id UUID PRIMARY KEY
        REFERENCES account_tenant_creation_admissions(creation_operation_id) ON DELETE RESTRICT,
    intent_request_id UUID NOT NULL UNIQUE
        REFERENCES account_tenant_creation_original_intents(request_id) ON DELETE RESTRICT,
    target_namespace VARCHAR(63) NOT NULL,
    game_design_creation_request_id UUID NOT NULL,
    actor_account_id UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    creator_party_id UUID NOT NULL,
    intent_request_payload BYTEA NOT NULL CHECK (octet_length(intent_request_payload) BETWEEN 1 AND 8192),
    intent_request_digest VARCHAR(71) NOT NULL
        CHECK (intent_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    admission_binding_payload BYTEA NOT NULL CHECK (octet_length(admission_binding_payload) BETWEEN 1 AND 8192),
    admission_binding_digest VARCHAR(64) NOT NULL
        CHECK (admission_binding_digest ~ '^[0-9a-f]{64}$'),
    authorization_binding_payload BYTEA NOT NULL
        CHECK (octet_length(authorization_binding_payload) BETWEEN 1 AND 4194304),
    authorization_digest VARCHAR(71) NOT NULL
        CHECK (authorization_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (authorization_digest = 'sha256:' || encode(sha256(authorization_binding_payload), 'hex')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT account_tenant_creation_source_order_gd_request_fk
        FOREIGN KEY (target_namespace, game_design_creation_request_id)
        REFERENCES account_tenant_creation_original_intents(target_namespace, game_design_creation_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_tenant_creation_source_order_namespace_check
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT account_tenant_creation_source_order_creator_party_check
        CHECK (creator_party_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT account_tenant_creation_source_order_admission_digest_check
        CHECK (admission_binding_digest = encode(sha256(admission_binding_payload), 'hex')),
    CONSTRAINT account_tenant_creation_source_order_parent_digest_check
        CHECK (intent_request_digest = 'sha256:' || encode(sha256(intent_request_payload), 'hex'))
);

CREATE TABLE account_tenant_creation_source_order_sources (
    creation_operation_id UUID NOT NULL
        REFERENCES account_tenant_creation_source_orders(creation_operation_id) ON DELETE RESTRICT,
    source_key VARCHAR(512) NOT NULL
        REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 131072),
    PRIMARY KEY (creation_operation_id, source_key)
);
CREATE INDEX account_tenant_creation_source_order_sources_by_key
    ON account_tenant_creation_source_order_sources(source_key, creation_operation_id);

-- One immutable exact terminal read closes one original source holder. recorded_at is local Account
-- observation time; the remote protocol intentionally makes no claim about an outcome timestamp.
CREATE TABLE account_tenant_creation_source_order_settlements (
    creation_operation_id UUID PRIMARY KEY
        REFERENCES account_tenant_creation_source_orders(creation_operation_id) ON DELETE RESTRICT,
    target_namespace VARCHAR(63) NOT NULL,
    game_design_creation_request_id UUID NOT NULL,
    account_authorization_operation_id UUID NOT NULL,
    initiating_account_id UUID NOT NULL,
    creation_request_digest VARCHAR(71) NOT NULL,
    account_authorization_digest VARCHAR(71) NOT NULL,
    outcome_status VARCHAR(16) NOT NULL CHECK (outcome_status IN ('CREATED', 'NOT_CREATED')),
    game_design_creation_operation_id UUID,
    creation_evidence_digest VARCHAR(71),
    terminal_read_payload BYTEA NOT NULL CHECK (octet_length(terminal_read_payload) BETWEEN 1 AND 8192),
    terminal_read_digest VARCHAR(71) NOT NULL
        CHECK (terminal_read_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (terminal_read_digest = 'sha256:' || encode(sha256(terminal_read_payload), 'hex')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (creation_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CHECK (account_authorization_digest ~ '^sha256:[0-9a-f]{64}$'),
    CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CHECK (game_design_creation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (account_authorization_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (initiating_account_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (
        (outcome_status = 'NOT_CREATED'
            AND game_design_creation_operation_id IS NULL
            AND creation_evidence_digest IS NULL)
        OR
        (outcome_status = 'CREATED'
            AND game_design_creation_operation_id IS NOT NULL
            AND game_design_creation_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND creation_evidence_digest IS NOT NULL
            AND creation_evidence_digest ~ '^sha256:[0-9a-f]{64}$')
    )
);

-- [jooq ignore start]
CREATE FUNCTION account_tenant_creation_source_order_source_key(encoded BYTEA)
RETURNS TEXT LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    position_value INTEGER := 1;
    parsed BYTEA;
    schema_value TEXT;
    kind_value TEXT;
    scope_value TEXT;
    presence_value TEXT;
    generation_value TEXT;
    version_value TEXT;
    checkpoint_value TEXT;
    checkpoint_sequence TEXT;
    evidence_value BYTEA;
    result_value TEXT;
BEGIN
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(encoded, position_value);
    schema_value := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(encoded, position_value);
    kind_value := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(encoded, position_value);
    scope_value := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(encoded, position_value);
    presence_value := convert_from(parsed, 'UTF8');
    IF presence_value = 'PRESENT' THEN
        SELECT frame_value, next_position INTO parsed, position_value
            FROM account_publication_authorization_read_frame(encoded, position_value);
        generation_value := convert_from(parsed, 'UTF8');
        IF generation_value !~ '^[1-9][0-9]*$' THEN
            RAISE EXCEPTION 'Invalid source evidence generation frame' USING ERRCODE = '23514';
        END IF;
    ELSIF presence_value <> 'ABSENT' THEN
        RAISE EXCEPTION 'Invalid source evidence generation presence' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(encoded, position_value);
    version_value := convert_from(parsed, 'UTF8');
    IF version_value !~ '^[1-9][0-9]*$' THEN
        RAISE EXCEPTION 'Invalid source evidence version frame' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(encoded, position_value);
    checkpoint_value := convert_from(parsed, 'UTF8');
    IF checkpoint_value = 'PRESENT' THEN
        SELECT frame_value, next_position INTO parsed, position_value
            FROM account_publication_authorization_read_frame(encoded, position_value);
        IF octet_length(parsed) = 0 THEN
            RAISE EXCEPTION 'Empty source evidence checkpoint stream' USING ERRCODE = '23514';
        END IF;
        SELECT frame_value, next_position INTO parsed, position_value
            FROM account_publication_authorization_read_frame(encoded, position_value);
        checkpoint_sequence := convert_from(parsed, 'UTF8');
        IF checkpoint_sequence !~ '^(0|[1-9][0-9]*)$' THEN
            RAISE EXCEPTION 'Invalid source evidence checkpoint sequence' USING ERRCODE = '23514';
        END IF;
    ELSIF checkpoint_value <> 'ABSENT' THEN
        RAISE EXCEPTION 'Invalid source evidence checkpoint presence' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO evidence_value, position_value
        FROM account_publication_authorization_read_frame(encoded, position_value);
    IF schema_value <> 'account-draft-source-evidence/v1'
        OR octet_length(scope_value) = 0
        OR octet_length(evidence_value) = 0
        OR position_value <> octet_length(encoded) + 1 THEN
        RAISE EXCEPTION 'Invalid or trailing canonical source evidence frames' USING ERRCODE = '23514';
    END IF;
    IF kind_value = 'RECENT_REAUTH_POLICY'
        AND (scope_value NOT IN ('SELF_SERVICE_GAME_CREATION/PLATFORM',
                'SELF_SERVICE_GAME_CREATION/DEPLOYMENT')
            OR generation_value IS NOT NULL OR checkpoint_value <> 'ABSENT') THEN
        RAISE EXCEPTION 'Recent reauthentication policy source contains fabricated generation data'
            USING ERRCODE = '23514';
    END IF;
    result_value := kind_value || ':' || scope_value;
    IF octet_length(result_value) > 2048 THEN
        RAISE EXCEPTION 'Canonical Account source key exceeds its bound' USING ERRCODE = '23514';
    END IF;
    RETURN result_value;
END;
$$;

CREATE FUNCTION account_tenant_creation_source_order_request_matches(
    encoded BYTEA, intent_request_id UUID)
RETURNS BOOLEAN LANGUAGE plpgsql STABLE STRICT AS $$
DECLARE
    original account_tenant_creation_original_intents%ROWTYPE;
    frames BYTEA[] := ARRAY[]::BYTEA[];
    position_value INTEGER := 1;
    parsed BYTEA;
    frame_count INTEGER;
    description_presence TEXT;
BEGIN
    SELECT * INTO original FROM account_tenant_creation_original_intents
        WHERE request_id = intent_request_id;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    WHILE position_value <= octet_length(encoded) LOOP
        SELECT frame_value, next_position INTO parsed, position_value
            FROM account_publication_authorization_read_frame(encoded, position_value);
        frames := array_append(frames, parsed);
    END LOOP;
    frame_count := cardinality(frames);
    IF frame_count < 27
        OR convert_from(frames[1], 'UTF8') <> 'account-tenant-creation-intent-request/v1'
        OR convert_from(frames[2], 'UTF8') <> 'requestId'
        OR convert_from(frames[3], 'UTF8') <> original.request_id::TEXT
        OR convert_from(frames[4], 'UTF8') <> 'creationOperationId'
        OR convert_from(frames[5], 'UTF8') <> original.creation_operation_id::TEXT
        OR convert_from(frames[6], 'UTF8') <> 'gameDesignCreationRequestId'
        OR convert_from(frames[7], 'UTF8') <> original.game_design_creation_request_id::TEXT
        OR convert_from(frames[8], 'UTF8') <> 'initiatingAccountId'
        OR convert_from(frames[9], 'UTF8') <> original.initiating_account_uuid::TEXT
        OR convert_from(frames[10], 'UTF8') <> 'targetNamespace'
        OR convert_from(frames[11], 'UTF8') <> original.target_namespace
        OR convert_from(frames[12], 'UTF8') <> 'sourceGameTenantKey'
        OR convert_from(frames[13], 'UTF8') <> original.source_game_tenant_key
        OR convert_from(frames[14], 'UTF8') <> 'name'
        OR convert_from(frames[15], 'UTF8') <> original.name
        OR convert_from(frames[16], 'UTF8') <> 'description' THEN
        RETURN FALSE;
    END IF;
    description_presence := convert_from(frames[17], 'UTF8');
    IF original.description IS NULL THEN
        IF description_presence <> 'ABSENT' OR frame_count <> 27 THEN RETURN FALSE; END IF;
    ELSE
        IF description_presence <> 'PRESENT' OR frame_count <> 28
            OR convert_from(frames[18], 'UTF8') <> original.description THEN
            RETURN FALSE;
        END IF;
    END IF;
    IF description_presence = 'ABSENT' THEN
        IF convert_from(frames[18], 'UTF8') <> 'recentReauthenticationEvidenceId'
            OR convert_from(frames[19], 'UTF8') <> original.recent_reauthentication_evidence_id::TEXT
            OR convert_from(frames[20], 'UTF8') <> 'creatorPartyId'
            OR convert_from(frames[21], 'UTF8') <> original.creator_party_id::TEXT
            OR convert_from(frames[22], 'UTF8') <> 'consentOperationId'
            OR convert_from(frames[23], 'UTF8') <> original.consent_operation_id::TEXT
            OR convert_from(frames[24], 'UTF8') <> 'consentStatementVersion'
            OR convert_from(frames[25], 'UTF8') <> original.consent_statement_version::TEXT
            OR convert_from(frames[26], 'UTF8') <> 'affirmativeConsent'
            OR convert_from(frames[27], 'UTF8') <> 'true' THEN
            RETURN FALSE;
        END IF;
    ELSE
        IF convert_from(frames[19], 'UTF8') <> 'recentReauthenticationEvidenceId'
            OR convert_from(frames[20], 'UTF8') <> original.recent_reauthentication_evidence_id::TEXT
            OR convert_from(frames[21], 'UTF8') <> 'creatorPartyId'
            OR convert_from(frames[22], 'UTF8') <> original.creator_party_id::TEXT
            OR convert_from(frames[23], 'UTF8') <> 'consentOperationId'
            OR convert_from(frames[24], 'UTF8') <> original.consent_operation_id::TEXT
            OR convert_from(frames[25], 'UTF8') <> 'consentStatementVersion'
            OR convert_from(frames[26], 'UTF8') <> original.consent_statement_version::TEXT
            OR convert_from(frames[27], 'UTF8') <> 'affirmativeConsent'
            OR convert_from(frames[28], 'UTF8') <> 'true' THEN
            RETURN FALSE;
        END IF;
    END IF;
    RETURN original.affirmative_consent;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

CREATE FUNCTION account_tenant_creation_source_order_validate_binding(
    order_value account_tenant_creation_source_orders)
RETURNS TEXT[] LANGUAGE plpgsql STABLE STRICT AS $$
DECLARE
    original account_tenant_creation_original_intents%ROWTYPE;
    admission_request account_tenant_creation_admission_requests%ROWTYPE;
    admission account_tenant_creation_admissions%ROWTYPE;
    parsed BYTEA;
    position_value INTEGER := 1;
    keys TEXT[] := ARRAY[]::TEXT[];
    source_key_value TEXT;
    prior_sort_key INTEGER[];
    current_sort_key INTEGER[];
    frame_index INTEGER;
    source_count TEXT;
    evidence_value BYTEA;
    environment_count INTEGER;
    hosted_scope_count INTEGER;
BEGIN
    SELECT * INTO STRICT original FROM account_tenant_creation_original_intents
        WHERE creation_operation_id = order_value.creation_operation_id;
    SELECT * INTO STRICT admission_request FROM account_tenant_creation_admission_requests
        WHERE creation_operation_id = order_value.creation_operation_id;
    SELECT * INTO STRICT admission FROM account_tenant_creation_admissions
        WHERE creation_operation_id = order_value.creation_operation_id;
    IF order_value.intent_request_id IS DISTINCT FROM original.request_id
        OR order_value.target_namespace IS DISTINCT FROM original.target_namespace
        OR order_value.game_design_creation_request_id IS DISTINCT FROM original.game_design_creation_request_id
        OR order_value.actor_account_id IS DISTINCT FROM original.initiating_account_uuid
        OR order_value.creator_party_id IS DISTINCT FROM original.creator_party_id
        OR order_value.intent_request_payload IS DISTINCT FROM original.request_payload
        OR order_value.intent_request_digest IS DISTINCT FROM original.request_digest
        OR NOT account_tenant_creation_source_order_request_matches(
            order_value.intent_request_payload, original.request_id)
        OR admission_request.intent_request_id IS DISTINCT FROM original.request_id
        OR admission_request.actor_account_id IS DISTINCT FROM original.initiating_account_uuid
        OR admission_request.target_namespace IS DISTINCT FROM original.target_namespace
        OR admission_request.game_design_creation_request_id IS DISTINCT FROM original.game_design_creation_request_id
        OR admission_request.game_design_request_digest IS DISTINCT FROM original.game_design_request_digest
        OR admission_request.intent_digest IS DISTINCT FROM original.request_digest
        OR admission_request.reauthentication_evidence_id IS DISTINCT FROM original.recent_reauthentication_evidence_id
        OR order_value.admission_binding_payload IS DISTINCT FROM admission_request.binding_payload
        OR order_value.admission_binding_digest IS DISTINCT FROM admission_request.binding_digest
        OR admission.receipt_payload IS DISTINCT FROM admission_request.binding_payload
        OR admission.receipt_digest IS DISTINCT FROM admission_request.binding_digest THEN
        RAISE EXCEPTION 'Tenant-creation source order differs from its exact durable intent/admission'
            USING ERRCODE = '23514';
    END IF;
    IF order_value.authorization_digest IS DISTINCT FROM
        'sha256:' || encode(sha256(order_value.authorization_binding_payload), 'hex') THEN
        RAISE EXCEPTION 'Tenant-creation source-order digest differs from its exact bytes'
            USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            order_value.authorization_binding_payload, position_value);
    IF convert_from(parsed, 'UTF8') <> 'account-tenant-creation-source-order/v1' THEN
        RAISE EXCEPTION 'Unsupported tenant-creation source-order binding schema'
            USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            order_value.authorization_binding_payload, position_value);
    IF parsed IS DISTINCT FROM original.request_payload THEN
        RAISE EXCEPTION 'Source-order binding does not retain exact original request bytes'
            USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            order_value.authorization_binding_payload, position_value);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM original.request_digest THEN
        RAISE EXCEPTION 'Source-order binding does not retain exact original request digest'
            USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            order_value.authorization_binding_payload, position_value);
    IF parsed IS DISTINCT FROM admission_request.binding_payload THEN
        RAISE EXCEPTION 'Source-order binding does not retain exact durable admission bytes'
            USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            order_value.authorization_binding_payload, position_value);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM admission_request.binding_digest THEN
        RAISE EXCEPTION 'Source-order binding does not retain exact admission binding digest'
            USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            order_value.authorization_binding_payload, position_value);
    source_count := convert_from(parsed, 'UTF8');
    IF source_count <> '8' THEN
        RAISE EXCEPTION 'Tenant-creation source order requires exactly eight owner sources'
            USING ERRCODE = '23514';
    END IF;
    FOR frame_index IN 1..8 LOOP
        SELECT frame_value, next_position INTO evidence_value, position_value
            FROM account_publication_authorization_read_frame(
                order_value.authorization_binding_payload, position_value);
        source_key_value := account_tenant_creation_source_order_source_key(evidence_value);
        current_sort_key := account_publication_authorization_source_sort_key(source_key_value);
        IF prior_sort_key IS NOT NULL AND current_sort_key <= prior_sort_key THEN
            RAISE EXCEPTION 'Tenant-creation source keys are duplicated or not in canonical order'
                USING ERRCODE = '23514';
        END IF;
        prior_sort_key := current_sort_key;
        keys := array_append(keys, source_key_value);
    END LOOP;
    IF position_value <> octet_length(order_value.authorization_binding_payload) + 1 THEN
        RAISE EXCEPTION 'Tenant-creation source-order binding has trailing frames'
            USING ERRCODE = '23514';
    END IF;
    IF cardinality(keys) <> 8
        OR NOT ('ISSUER:firemud-account-service' = ANY(keys))
        OR NOT (('ACCOUNT:' || original.initiating_account_uuid::TEXT) = ANY(keys))
        OR NOT (('GLOBAL_ROLES:' || original.initiating_account_uuid::TEXT) = ANY(keys))
        OR NOT (('CREATOR_PARTY:' || original.creator_party_id::TEXT) = ANY(keys))
        OR NOT ('RECENT_REAUTH_POLICY:SELF_SERVICE_GAME_CREATION/PLATFORM' = ANY(keys))
        OR NOT ('RECENT_REAUTH_POLICY:SELF_SERVICE_GAME_CREATION/DEPLOYMENT' = ANY(keys)) THEN
        RAISE EXCEPTION 'Tenant-creation source order differs from the closed owner-source set'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO environment_count FROM unnest(keys) value
        WHERE value LIKE 'HOSTED_TERMS:environment-boundary/%'
          AND length(value) > length('HOSTED_TERMS:environment-boundary/');
    SELECT count(*) INTO hosted_scope_count FROM unnest(keys) value
        WHERE value ~ '^HOSTED_TERMS:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          AND substring(value FROM length('HOSTED_TERMS:') + 1)
              <> '00000000-0000-0000-0000-000000000000';
    IF environment_count <> 1 OR hosted_scope_count <> 1 THEN
        RAISE EXCEPTION 'Tenant-creation source order requires one exact hosted scope and environment binding'
            USING ERRCODE = '23514';
    END IF;
    RETURN keys;
END;
$$;

CREATE FUNCTION account_tenant_creation_source_order_validate_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    keys TEXT[];
    source_key_value TEXT;
    locked_key TEXT;
BEGIN
    NEW.created_at := clock_timestamp();
    keys := account_tenant_creation_source_order_validate_binding(NEW);
    FOR source_key_value IN
        SELECT value FROM unnest(keys) value
        ORDER BY account_publication_authorization_source_sort_key(value)
    LOOP
        SELECT source_key INTO locked_key FROM account_draft_authorization_source_locks
            WHERE source_key = source_key_value FOR UPDATE NOWAIT;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Existing Account source serialization row is unavailable'
                USING ERRCODE = '55P03';
        END IF;
    END LOOP;
    IF EXISTS (
        SELECT 1 FROM account_draft_authorization_changed_scopes changed
        JOIN account_draft_authorization_source_changes source_change
            ON source_change.change_id = changed.change_id
        WHERE changed.source_key = ANY(keys) AND source_change.status = 'WAITING'
    ) THEN
        RAISE EXCEPTION 'An applicable Account source change is already waiting'
            USING ERRCODE = '55P03';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_creation_source_order_validate_complete()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    order_value account_tenant_creation_source_orders%ROWTYPE;
    keys TEXT[];
    evidence_value BYTEA;
    position_value INTEGER := 1;
    parsed BYTEA;
    index_value INTEGER;
    stored_key TEXT;
    stored_evidence BYTEA;
    source_row_count BIGINT;
BEGIN
    SELECT * INTO order_value FROM account_tenant_creation_source_orders
        WHERE creation_operation_id = COALESCE(NEW.creation_operation_id, OLD.creation_operation_id);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tenant-creation source-order header is missing'
            USING ERRCODE = '23514';
    END IF;
    keys := account_tenant_creation_source_order_validate_binding(order_value);
    SELECT count(*) INTO source_row_count FROM account_tenant_creation_source_order_sources
        WHERE creation_operation_id = order_value.creation_operation_id;
    IF source_row_count <> 8 THEN
        RAISE EXCEPTION 'Tenant-creation source-order stored source vector is incomplete'
            USING ERRCODE = '23514';
    END IF;
    FOR index_value IN 1..5 LOOP
        SELECT frame_value, next_position INTO parsed, position_value
            FROM account_publication_authorization_read_frame(
                order_value.authorization_binding_payload, position_value);
    END LOOP;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            order_value.authorization_binding_payload, position_value);
    FOR index_value IN 1..8 LOOP
        SELECT frame_value, next_position INTO evidence_value, position_value
            FROM account_publication_authorization_read_frame(
                order_value.authorization_binding_payload, position_value);
        SELECT source_key, source_evidence INTO stored_key, stored_evidence
            FROM account_tenant_creation_source_order_sources
            WHERE creation_operation_id = order_value.creation_operation_id
              AND source_key = keys[index_value];
        IF NOT FOUND OR stored_key IS DISTINCT FROM keys[index_value]
            OR stored_evidence IS DISTINCT FROM evidence_value THEN
            RAISE EXCEPTION 'Stored source row differs from exact canonical source-order bytes'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF position_value <> octet_length(order_value.authorization_binding_payload) + 1 THEN
        RAISE EXCEPTION 'Tenant-creation source-order binding has trailing source frames'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_tenant_creation_source_order_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original tenant-creation source-order data is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION account_tenant_creation_source_order_settlement_valid(
    settlement_value account_tenant_creation_source_order_settlements)
RETURNS BOOLEAN LANGUAGE plpgsql STABLE AS $$
DECLARE
    order_value account_tenant_creation_source_orders%ROWTYPE;
    intent_value account_tenant_creation_original_intents%ROWTYPE;
    position_value INTEGER := 1;
    parsed BYTEA;
    schema_value BIGINT;
    namespace_value TEXT;
    request_id_value TEXT;
    account_operation_value TEXT;
    account_id_value TEXT;
    request_digest_value TEXT;
    authorization_digest_value TEXT;
    outcome_value TEXT;
    receipt_flag INTEGER;
    receipt_namespace TEXT;
    receipt_request_id TEXT;
    receipt_operation_id TEXT;
    receipt_request_digest TEXT;
    tenant_id TEXT;
    source_game_key TEXT;
    provenance TEXT;
    receipt_digest TEXT;
    row_id NUMERIC;
BEGIN
    SELECT * INTO order_value FROM account_tenant_creation_source_orders
        WHERE creation_operation_id = settlement_value.creation_operation_id;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    PERFORM account_tenant_creation_source_order_validate_binding(order_value);
    SELECT * INTO intent_value FROM account_tenant_creation_original_intents
        WHERE creation_operation_id = order_value.creation_operation_id;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    IF settlement_value.target_namespace IS DISTINCT FROM order_value.target_namespace
        OR settlement_value.game_design_creation_request_id
            IS DISTINCT FROM order_value.game_design_creation_request_id
        OR settlement_value.account_authorization_operation_id
            IS DISTINCT FROM order_value.creation_operation_id
        OR settlement_value.initiating_account_id IS DISTINCT FROM order_value.actor_account_id
        OR settlement_value.creation_request_digest IS DISTINCT FROM intent_value.game_design_request_digest
        OR settlement_value.account_authorization_digest IS DISTINCT FROM order_value.authorization_digest
        OR settlement_value.terminal_read_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(settlement_value.terminal_read_payload), 'hex') THEN
        RETURN FALSE;
    END IF;

    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    IF convert_from(parsed, 'UTF8') <> 'game-tenant-creation-original-outcome/v1'
        OR position_value > octet_length(settlement_value.terminal_read_payload) - 3 THEN
        RETURN FALSE;
    END IF;
    schema_value := get_byte(settlement_value.terminal_read_payload, position_value - 1)::BIGINT * 16777216
        + get_byte(settlement_value.terminal_read_payload, position_value)::BIGINT * 65536
        + get_byte(settlement_value.terminal_read_payload, position_value + 1)::BIGINT * 256
        + get_byte(settlement_value.terminal_read_payload, position_value + 2)::BIGINT;
    position_value := position_value + 4;
    IF schema_value <> 1 THEN RETURN FALSE; END IF;

    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    namespace_value := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    request_id_value := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    account_operation_value := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    account_id_value := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    request_digest_value := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    authorization_digest_value := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    outcome_value := convert_from(parsed, 'UTF8');

    IF namespace_value IS DISTINCT FROM order_value.target_namespace
        OR request_id_value IS DISTINCT FROM order_value.game_design_creation_request_id::TEXT
        OR account_operation_value IS DISTINCT FROM order_value.creation_operation_id::TEXT
        OR account_id_value IS DISTINCT FROM order_value.actor_account_id::TEXT
        OR request_digest_value IS DISTINCT FROM intent_value.game_design_request_digest
        OR authorization_digest_value IS DISTINCT FROM order_value.authorization_digest
        OR outcome_value IS DISTINCT FROM settlement_value.outcome_status
        OR request_id_value !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR account_operation_value !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR account_id_value !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR request_id_value::UUID::TEXT IS DISTINCT FROM request_id_value
        OR account_operation_value::UUID::TEXT IS DISTINCT FROM account_operation_value
        OR account_id_value::UUID::TEXT IS DISTINCT FROM account_id_value
        OR request_digest_value !~ '^sha256:[0-9a-f]{64}$'
        OR authorization_digest_value !~ '^sha256:[0-9a-f]{64}$' THEN
        RETURN FALSE;
    END IF;
    IF position_value > octet_length(settlement_value.terminal_read_payload) THEN
        RETURN FALSE;
    END IF;
    receipt_flag := get_byte(settlement_value.terminal_read_payload, position_value - 1);
    position_value := position_value + 1;

    IF outcome_value = 'NOT_CREATED' THEN
        RETURN receipt_flag = 0
            AND settlement_value.game_design_creation_operation_id IS NULL
            AND settlement_value.creation_evidence_digest IS NULL
            AND position_value = octet_length(settlement_value.terminal_read_payload) + 1;
    ELSIF outcome_value <> 'CREATED' OR receipt_flag <> 1 THEN
        RETURN FALSE;
    END IF;

    IF position_value > octet_length(settlement_value.terminal_read_payload) - 3 THEN
        RETURN FALSE;
    END IF;
    schema_value := get_byte(settlement_value.terminal_read_payload, position_value - 1)::BIGINT * 16777216
        + get_byte(settlement_value.terminal_read_payload, position_value)::BIGINT * 65536
        + get_byte(settlement_value.terminal_read_payload, position_value + 1)::BIGINT * 256
        + get_byte(settlement_value.terminal_read_payload, position_value + 2)::BIGINT;
    position_value := position_value + 4;
    IF schema_value <> 1 THEN RETURN FALSE; END IF;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    receipt_namespace := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    receipt_request_id := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    receipt_operation_id := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    receipt_request_digest := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    tenant_id := convert_from(parsed, 'UTF8');
    IF position_value > octet_length(settlement_value.terminal_read_payload) - 7 THEN
        RETURN FALSE;
    END IF;
    row_id := get_byte(settlement_value.terminal_read_payload, position_value - 1)::NUMERIC * 72057594037927936
        + get_byte(settlement_value.terminal_read_payload, position_value)::NUMERIC * 281474976710656
        + get_byte(settlement_value.terminal_read_payload, position_value + 1)::NUMERIC * 1099511627776
        + get_byte(settlement_value.terminal_read_payload, position_value + 2)::NUMERIC * 4294967296
        + get_byte(settlement_value.terminal_read_payload, position_value + 3)::NUMERIC * 16777216
        + get_byte(settlement_value.terminal_read_payload, position_value + 4)::NUMERIC * 65536
        + get_byte(settlement_value.terminal_read_payload, position_value + 5)::NUMERIC * 256
        + get_byte(settlement_value.terminal_read_payload, position_value + 6)::NUMERIC;
    IF get_byte(settlement_value.terminal_read_payload, position_value - 1) >= 128 THEN
        row_id := row_id - 18446744073709551616;
    END IF;
    position_value := position_value + 8;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    source_game_key := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    provenance := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(
            settlement_value.terminal_read_payload, position_value);
    receipt_digest := convert_from(parsed, 'UTF8');

    RETURN namespace_value = receipt_namespace
        AND request_id_value = receipt_request_id
        AND request_digest_value = receipt_request_digest
        AND receipt_request_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND receipt_request_id::UUID::TEXT = receipt_request_id
        AND receipt_operation_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND receipt_operation_id::UUID::TEXT = receipt_operation_id
        AND receipt_operation_id::UUID = settlement_value.game_design_creation_operation_id
        AND tenant_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND tenant_id::UUID::TEXT = tenant_id
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'
        AND row_id > 0
        AND octet_length(source_game_key) BETWEEN 1 AND 72
        AND char_length(source_game_key) BETWEEN 1 AND 36
        AND provenance = 'NEW_GAME_ROW'
        AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND settlement_value.creation_evidence_digest = receipt_digest
        AND position_value = octet_length(settlement_value.terminal_read_payload) + 1;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$;

CREATE FUNCTION account_tenant_creation_source_order_settlement_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    order_value account_tenant_creation_source_orders%ROWTYPE;
    source_keys TEXT[];
    source_key_value TEXT;
    locked_key TEXT;
BEGIN
    SELECT * INTO order_value FROM account_tenant_creation_source_orders
        WHERE creation_operation_id = NEW.creation_operation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tenant-creation settlement has no original source order'
            USING ERRCODE = '23514';
    END IF;
    source_keys := account_tenant_creation_source_order_validate_binding(order_value);
    FOR source_key_value IN
        SELECT value FROM unnest(source_keys) value
        ORDER BY account_publication_authorization_source_sort_key(value)
    LOOP
        SELECT source_key INTO locked_key FROM account_draft_authorization_source_locks
            WHERE source_key = source_key_value FOR UPDATE NOWAIT;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Existing Account source serialization row is unavailable'
                USING ERRCODE = '55P03';
        END IF;
    END LOOP;
    NEW.recorded_at := clock_timestamp();
    IF NOT account_tenant_creation_source_order_settlement_valid(NEW) THEN
        RAISE EXCEPTION 'Tenant-creation settlement is not the exact canonical terminal read'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_creation_source_order_is_settled(operation_value UUID)
RETURNS BOOLEAN LANGUAGE SQL STABLE STRICT AS $$
    SELECT EXISTS (
        SELECT 1 FROM account_tenant_creation_source_order_settlements settlement
        WHERE settlement.creation_operation_id = operation_value
          AND account_tenant_creation_source_order_settlement_valid(settlement)
    )
$$;

CREATE TRIGGER account_tenant_creation_source_order_insert_guard
    BEFORE INSERT ON account_tenant_creation_source_orders
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_source_order_validate_insert();
CREATE CONSTRAINT TRIGGER account_tenant_creation_source_order_complete
    AFTER INSERT ON account_tenant_creation_source_orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_source_order_validate_complete();
CREATE CONSTRAINT TRIGGER account_tenant_creation_source_order_sources_complete
    AFTER INSERT ON account_tenant_creation_source_order_sources
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_source_order_validate_complete();
CREATE TRIGGER account_tenant_creation_source_order_immutable
    BEFORE UPDATE OR DELETE ON account_tenant_creation_source_orders
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_source_order_no_mutation();
CREATE TRIGGER account_tenant_creation_source_order_no_truncate
    BEFORE TRUNCATE ON account_tenant_creation_source_orders
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_source_order_no_mutation();
CREATE TRIGGER account_tenant_creation_source_order_sources_immutable
    BEFORE UPDATE OR DELETE ON account_tenant_creation_source_order_sources
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_source_order_no_mutation();
CREATE TRIGGER account_tenant_creation_source_order_sources_no_truncate
    BEFORE TRUNCATE ON account_tenant_creation_source_order_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_source_order_no_mutation();
CREATE TRIGGER account_tenant_creation_source_order_settlement_insert_guard
    BEFORE INSERT ON account_tenant_creation_source_order_settlements
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_source_order_settlement_insert_guard();
CREATE TRIGGER account_tenant_creation_source_order_settlement_immutable
    BEFORE UPDATE OR DELETE ON account_tenant_creation_source_order_settlements
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_source_order_no_mutation();
CREATE TRIGGER account_tenant_creation_source_order_settlement_no_truncate
    BEFORE TRUNCATE ON account_tenant_creation_source_order_settlements
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_source_order_no_mutation();

-- Extend the assembled V87 writer exclusion in-place. Actual owner UPDATE/DELETE/INSERT and
-- TRUNCATE guards keep deriving exact old/new source scopes; this adds only the new pending holder.
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
            RAISE EXCEPTION 'Required creator source is held until every original owner settles'
                USING ERRCODE = '55P03';
        END IF;
    END LOOP;
    FOR operation_value IN SELECT DISTINCT s.creation_operation_id
        FROM account_tenant_creation_source_order_sources s WHERE s.source_key = ANY(source_keys)
        ORDER BY s.creation_operation_id LOOP
        IF NOT account_tenant_creation_source_order_is_settled(operation_value) THEN
            RAISE EXCEPTION 'Required creator source is held by a pending tenant-creation source order'
                USING ERRCODE = '55P03';
        END IF;
    END LOOP;
END;
$$;
-- [jooq ignore stop]
