-- Correlation/receipt storage only. No source writer, authorization grant, or retained-row backfill.
CREATE TABLE account_security_state_operations (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL,
    account_provenance VARCHAR(40) NOT NULL,
    caller_proof_binding BYTEA NOT NULL CHECK (octet_length(caller_proof_binding) BETWEEN 1 AND 16384),
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) > 0),
    request_digest VARCHAR(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    expected_generation BIGINT NOT NULL CHECK (expected_generation > 0),
    expected_source_version BIGINT NOT NULL CHECK (expected_source_version > 0),
    expected_fence BIGINT NOT NULL CHECK (expected_fence > 0),
    expected_fence_source_version BIGINT NOT NULL CHECK (expected_fence_source_version > 0),
    checkpoint_sequence BIGINT NOT NULL CHECK (checkpoint_sequence >= 0),
    checkpoint_payload BYTEA NOT NULL,
    global_role_source_version BIGINT NOT NULL CHECK (global_role_source_version > 0),
    before_state BYTEA NOT NULL,
    after_state BYTEA NOT NULL,
    mutation_kinds TEXT[] NOT NULL CHECK (cardinality(mutation_kinds) BETWEEN 1 AND 4),
    source_change_id UUID NOT NULL UNIQUE REFERENCES account_draft_authorization_source_changes(change_id),
    source_change_binding BYTEA NOT NULL CHECK (octet_length(source_change_binding) > 0),
    capture_payload BYTEA NOT NULL CHECK (octet_length(capture_payload) > 0),
    status VARCHAR(12) NOT NULL CHECK (status IN ('WAITING', 'COMMITTED')),
    event_sequence BIGINT CHECK (event_sequence > 0),
    event_id VARCHAR(128),
    event_digest VARCHAR(71),
    event_payload BYTEA,
    result_generation BIGINT CHECK (result_generation > 0),
    result_source_version BIGINT CHECK (result_source_version > 0),
    result_fence BIGINT CHECK (result_fence > 0),
    result_fence_source_version BIGINT CHECK (result_fence_source_version > 0),
    result_global_role_source_version BIGINT CHECK (result_global_role_source_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    FOREIGN KEY (account_uuid, account_id, account_provenance)
        REFERENCES accounts(account_uuid, account_uuid_source_numeric_id, account_uuid_provenance)
        ON DELETE RESTRICT,
    CONSTRAINT account_security_state_result_shape CHECK (
        (status = 'WAITING' AND event_sequence IS NULL AND event_id IS NULL AND event_digest IS NULL
            AND event_payload IS NULL AND result_generation IS NULL AND result_source_version IS NULL
            AND result_fence IS NULL AND result_fence_source_version IS NULL
            AND result_global_role_source_version IS NULL AND committed_at IS NULL)
        OR (status = 'COMMITTED' AND event_sequence IS NOT NULL AND event_id IS NOT NULL
            AND event_digest IS NOT NULL AND event_digest ~ '^sha256:[0-9a-f]{64}$' AND event_payload IS NOT NULL
            AND result_generation IS NOT NULL AND result_source_version IS NOT NULL
            AND result_fence IS NOT NULL AND result_fence_source_version IS NOT NULL
            AND result_global_role_source_version IS NOT NULL AND committed_at IS NOT NULL))
);

CREATE UNIQUE INDEX account_security_state_event_checkpoint_unique
    ON account_security_state_operations(account_uuid, event_sequence) WHERE event_sequence IS NOT NULL;

-- [jooq ignore start]
CREATE FUNCTION account_security_state_frame(value BYTEA)
RETURNS BYTEA LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT int4send(octet_length(value)) || value;
$$;

CREATE FUNCTION account_security_state_canonical_state(value BYTEA)
RETURNS TEXT LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    state JSONB := convert_from(value, 'UTF8')::JSONB;
    modes TEXT[];
    roles TEXT[];
    canonical TEXT;
BEGIN
    IF jsonb_typeof(state) <> 'object'
        OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(state) k)
            <> ARRAY['emailVerified','globalRoles','lifecycleState','loginAuthModes']
        OR jsonb_typeof(state->'emailVerified') <> 'boolean'
        OR jsonb_typeof(state->'loginAuthModes') <> 'array'
        OR jsonb_typeof(state->'globalRoles') <> 'array'
        OR jsonb_typeof(state->'lifecycleState') <> 'string'
        OR state->>'lifecycleState' NOT IN ('ACTIVE','SECURITY_LOCKED','DEACTIVATED_PENDING_DELETE','DELETED') THEN
        RAISE EXCEPTION 'Unsupported security-state representation' USING ERRCODE = '23514';
    END IF;
    SELECT COALESCE(array_agg(v ORDER BY ordinal), ARRAY[]::TEXT[]) INTO modes
        FROM jsonb_array_elements_text(state->'loginAuthModes') WITH ORDINALITY AS t(v, ordinal);
    SELECT COALESCE(array_agg(v ORDER BY ordinal), ARRAY[]::TEXT[]) INTO roles
        FROM jsonb_array_elements_text(state->'globalRoles') WITH ORDINALITY AS t(v, ordinal);
    IF cardinality(modes) = 0 OR NOT modes <@ ARRAY['EMAIL_OTP','PASSWORD']::TEXT[]
        OR modes <> (SELECT array_agg(DISTINCT v ORDER BY v) FROM unnest(modes) v)
        OR NOT roles <@ ARRAY['billingAdmin','platformAdmin','support']::TEXT[]
        OR roles <> (SELECT COALESCE(array_agg(DISTINCT v ORDER BY v), ARRAY[]::TEXT[]) FROM unnest(roles) v) THEN
        RAISE EXCEPTION 'Unsupported security-state arrays' USING ERRCODE = '23514';
    END IF;
    canonical := '{"emailVerified":' || (state->'emailVerified')::TEXT
        || ',"globalRoles":' || replace((state->'globalRoles')::TEXT, ', ', ',')
        || ',"lifecycleState":"' || (state->>'lifecycleState') || '"'
        || ',"loginAuthModes":' || replace((state->'loginAuthModes')::TEXT, ', ', ',') || '}';
    IF value IS DISTINCT FROM convert_to(canonical, 'UTF8') THEN
        RAISE EXCEPTION 'Security-state bytes must be complete canonical JSON' USING ERRCODE = '23514';
    END IF;
    RETURN canonical;
END;
$$;

CREATE FUNCTION account_security_state_operation_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    before_json JSONB;
    after_json JSONB;
    kinds TEXT[] := ARRAY[]::TEXT[];
    request_bytes BYTEA;
    capture_bytes BYTEA;
    account_source_bytes BYTEA;
    role_source_bytes BYTEA;
    change_bytes BYTEA;
    kind TEXT;
    source_row RECORD;
    baseline_row RECORD;
    account_row RECORD;
    role_row RECORD;
    authority_row RECORD;
    fence_row RECORD;
    outbox_row RECORD;
    stream_row RECORD;
    key TEXT;
    preimage TEXT;
    expected_digest TEXT;
    expected_wire TEXT;
    caller_json JSONB;
    canonical_caller TEXT;
    checkpoint_json JSONB;
    checkpoint_text TEXT;
    checkpoint_preimage TEXT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'WAITING' OR NEW.status <> 'COMMITTED'
            OR (to_jsonb(NEW) - ARRAY['status','event_sequence','event_id','event_digest','event_payload',
                'result_generation','result_source_version','result_fence','result_fence_source_version',
                'result_global_role_source_version','committed_at'])
                IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status','event_sequence','event_id','event_digest','event_payload',
                'result_generation','result_source_version','result_fence','result_fence_source_version',
                'result_global_role_source_version','committed_at']) THEN
            RAISE EXCEPTION 'Original security-state request/capture and committed results are immutable'
                USING ERRCODE = '23514';
        END IF;
        NEW.committed_at := CURRENT_TIMESTAMP;
    ELSIF NEW.status <> 'WAITING' THEN
        RAISE EXCEPTION 'Security-state operation must start WAITING' USING ERRCODE = '23514';
    END IF;
    before_json := account_security_state_canonical_state(NEW.before_state)::JSONB;
    after_json := account_security_state_canonical_state(NEW.after_state)::JSONB;
    caller_json := convert_from(NEW.caller_proof_binding,'UTF8')::JSONB;
    IF jsonb_typeof(caller_json) <> 'object'
        OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(caller_json) k)
            <> ARRAY['actorAccountUuid','ownerEvidenceDigest','ownerOperationId','schemaVersion']
        OR caller_json->>'schemaVersion' <> 'account-security-state-caller-correlation/v1'
        OR jsonb_typeof(caller_json->'actorAccountUuid') <> 'string'
        OR jsonb_typeof(caller_json->'ownerOperationId') <> 'string'
        OR jsonb_typeof(caller_json->'ownerEvidenceDigest') <> 'string'
        OR jsonb_typeof(caller_json->'schemaVersion') <> 'string'
        OR caller_json->>'actorAccountUuid' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR caller_json->>'ownerOperationId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR caller_json->>'actorAccountUuid' = '00000000-0000-0000-0000-000000000000'
        OR caller_json->>'ownerOperationId' = '00000000-0000-0000-0000-000000000000'
        OR caller_json->>'ownerEvidenceDigest' !~ '^sha256:[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Unsupported original caller correlation shape' USING ERRCODE = '23514';
    END IF;
    canonical_caller := '{"actorAccountUuid":"' || (caller_json->>'actorAccountUuid')
        || '","ownerEvidenceDigest":"' || (caller_json->>'ownerEvidenceDigest')
        || '","ownerOperationId":"' || (caller_json->>'ownerOperationId')
        || '","schemaVersion":"account-security-state-caller-correlation/v1"}';
    IF NEW.caller_proof_binding IS DISTINCT FROM convert_to(canonical_caller,'UTF8') THEN
        RAISE EXCEPTION 'Original caller correlation bytes must be canonical' USING ERRCODE = '23514';
    END IF;
    IF before_json->'emailVerified' <> after_json->'emailVerified' THEN
        kinds := array_append(kinds, 'EMAIL_LOGIN_ELIGIBILITY_CHANGED');
    END IF;
    IF before_json->'globalRoles' <> after_json->'globalRoles' THEN
        kinds := array_append(kinds, 'GLOBAL_ROLE_CHANGED');
    END IF;
    IF before_json->'lifecycleState' <> after_json->'lifecycleState' THEN
        kinds := array_append(kinds, 'LIFECYCLE_STATE_CHANGED');
    END IF;
    IF before_json->'loginAuthModes' <> after_json->'loginAuthModes' THEN
        kinds := array_append(kinds, 'LOGIN_AUTH_MODES_CHANGED');
    END IF;
    IF kinds <> NEW.mutation_kinds OR cardinality(kinds) = 0 THEN
        RAISE EXCEPTION 'Complete changed-family set differs from state images' USING ERRCODE = '23514';
    END IF;
    request_bytes := account_security_state_frame(convert_to('firemud/account/security-state/request/v1','UTF8'))
        || account_security_state_frame(convert_to(NEW.request_id::TEXT,'UTF8'))
        || account_security_state_frame(convert_to(NEW.account_uuid::TEXT,'UTF8'))
        || account_security_state_frame(NEW.caller_proof_binding)
        || account_security_state_frame(convert_to(NEW.expected_generation::TEXT,'UTF8'))
        || account_security_state_frame(convert_to(NEW.expected_source_version::TEXT,'UTF8'))
        || account_security_state_frame(convert_to(cardinality(kinds)::TEXT,'UTF8'));
    FOREACH kind IN ARRAY kinds LOOP
        request_bytes := request_bytes || account_security_state_frame(convert_to(kind,'UTF8'));
    END LOOP;
    request_bytes := request_bytes || account_security_state_frame(NEW.after_state);
    capture_bytes := account_security_state_frame(convert_to('firemud/account/security-state/capture/v1','UTF8'))
        || account_security_state_frame(request_bytes)
        || account_security_state_frame(convert_to(NEW.account_id::TEXT,'UTF8'))
        || account_security_state_frame(convert_to(NEW.account_provenance,'UTF8'))
        || account_security_state_frame(NEW.before_state)
        || account_security_state_frame(convert_to(NEW.expected_fence::TEXT,'UTF8'))
        || account_security_state_frame(convert_to(NEW.expected_fence_source_version::TEXT,'UTF8'))
        || account_security_state_frame(convert_to(NEW.checkpoint_sequence::TEXT,'UTF8'))
        || account_security_state_frame(NEW.checkpoint_payload)
        || account_security_state_frame(convert_to(NEW.global_role_source_version::TEXT,'UTF8'));
    IF request_bytes <> NEW.request_payload OR encode(sha256(request_bytes),'hex') <> NEW.request_digest
        OR capture_bytes <> NEW.capture_payload THEN
        RAISE EXCEPTION 'Security-state request/capture bytes or digest differ' USING ERRCODE = '23514';
    END IF;
    -- Reuse the exact V57 framing; correlation cannot substitute a different source vector.
    key := 'account:auth-authority:v1:account/' || NEW.account_uuid::TEXT;
    account_source_bytes := account_security_state_frame(convert_to('account-draft-source-evidence/v1','UTF8'))
        || account_security_state_frame(convert_to('ACCOUNT','UTF8'))
        || account_security_state_frame(convert_to(NEW.account_uuid::TEXT,'UTF8'))
        || account_security_state_frame(convert_to('PRESENT','UTF8'))
        || account_security_state_frame(convert_to(NEW.expected_generation::TEXT,'UTF8'))
        || account_security_state_frame(convert_to(NEW.expected_source_version::TEXT,'UTF8'))
        || account_security_state_frame(convert_to('PRESENT','UTF8'))
        || account_security_state_frame(convert_to(key,'UTF8'))
        || account_security_state_frame(convert_to(NEW.checkpoint_sequence::TEXT,'UTF8'))
        || account_security_state_frame(capture_bytes);
    change_bytes := account_security_state_frame(convert_to('account-draft-source-change/v1','UTF8'))
        || account_security_state_frame(convert_to(NEW.source_change_id::TEXT,'UTF8'))
        || account_security_state_frame(convert_to(CASE WHEN 'GLOBAL_ROLE_CHANGED' = ANY(kinds) THEN '2' ELSE '1' END,'UTF8'))
        || account_security_state_frame(account_source_bytes);
    IF 'GLOBAL_ROLE_CHANGED' = ANY(kinds) THEN
        role_source_bytes := account_security_state_frame(convert_to('account-draft-source-evidence/v1','UTF8'))
            || account_security_state_frame(convert_to('GLOBAL_ROLES','UTF8'))
            || account_security_state_frame(convert_to(NEW.account_uuid::TEXT,'UTF8'))
            || account_security_state_frame(convert_to('ABSENT','UTF8'))
            || account_security_state_frame(convert_to(NEW.global_role_source_version::TEXT,'UTF8'))
            || account_security_state_frame(convert_to('ABSENT','UTF8'))
            || account_security_state_frame(capture_bytes);
        change_bytes := change_bytes || account_security_state_frame(role_source_bytes);
    END IF;
    change_bytes := change_bytes || account_security_state_frame(capture_bytes);
    IF change_bytes <> NEW.source_change_binding THEN
        RAISE EXCEPTION 'Security-state exact V57 participation binding differs' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO account_row FROM accounts WHERE id = NEW.account_id FOR UPDATE;
    IF NOT FOUND OR account_row.account_uuid <> NEW.account_uuid
        OR account_row.account_uuid_source_numeric_id <> NEW.account_id
        OR account_row.account_uuid_provenance <> NEW.account_provenance THEN
        RAISE EXCEPTION 'Security-state Account association differs' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO source_row FROM account_draft_authorization_source_changes
        WHERE change_id = NEW.source_change_id FOR SHARE;
    IF NOT FOUND OR source_row.binding <> NEW.source_change_binding
        OR source_row.status <> (CASE WHEN TG_OP = 'INSERT' THEN 'WAITING' ELSE 'SOURCE_COMMITTED' END) THEN
        RAISE EXCEPTION 'Security-state V57 source change differs' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO authority_row FROM account_authority_generations
        WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid FOR SHARE;
    SELECT * INTO fence_row FROM account_authority_issuance_fences WHERE account_uuid = NEW.account_uuid FOR SHARE;
    SELECT * INTO role_row FROM account_global_role_sources WHERE account_uuid = NEW.account_uuid FOR SHARE;
    IF authority_row IS NULL OR fence_row IS NULL OR role_row IS NULL THEN
        RAISE EXCEPTION 'Security-state current source evidence is missing' USING ERRCODE = '23514';
    END IF;
    key := 'account:auth-authority:v1:account/' || NEW.account_uuid::TEXT;
    IF TG_OP = 'INSERT' THEN
        IF NEW.expected_generation = 9223372036854775807 OR NEW.expected_source_version = 9223372036854775807
            OR NEW.expected_fence = 9223372036854775807 OR NEW.expected_fence_source_version = 9223372036854775807
            OR NEW.checkpoint_sequence = 9223372036854775807
            OR ('GLOBAL_ROLE_CHANGED' = ANY(kinds) AND NEW.global_role_source_version = 9223372036854775807) THEN
            RAISE EXCEPTION 'Account source counter overflow' USING ERRCODE = '23514';
        END IF;
        IF authority_row.generation <> NEW.expected_generation OR authority_row.source_version <> NEW.expected_source_version
            OR fence_row.issuance_fence <> NEW.expected_fence OR fence_row.source_version <> NEW.expected_fence_source_version
            OR role_row.global_role_source_version <> NEW.global_role_source_version THEN
            RAISE EXCEPTION 'Security-state original source capture is stale' USING ERRCODE = '23514';
        END IF;
        IF NEW.checkpoint_sequence = 0 THEN
            SELECT * INTO baseline_row FROM account_authority_source_records
                WHERE outbox_stream_key = key FOR SHARE;
            SELECT * INTO stream_row FROM account_authority_outbox_streams
                WHERE outbox_stream_key = key FOR SHARE;
            -- V40 retains an explicit zero stream; counters alone never prove fresh provenance.
            IF NEW.expected_generation <> 1 OR NEW.expected_source_version <> 1
                OR NEW.expected_fence <> 1 OR NEW.expected_fence_source_version <> 1
                OR octet_length(NEW.checkpoint_payload) <> 0
                OR baseline_row IS NULL OR stream_row IS NULL
                OR stream_row.last_sequence IS DISTINCT FROM 0
                OR baseline_row.scope_kind IS DISTINCT FROM 'ACCOUNT'
                OR baseline_row.issuer_id IS NOT NULL
                OR baseline_row.account_uuid IS DISTINCT FROM NEW.account_uuid
                OR baseline_row.baseline_generation IS DISTINCT FROM 1
                OR baseline_row.baseline_source_version IS DISTINCT FROM 1
                OR baseline_row.baseline_issuance_fence IS DISTINCT FROM 1
                OR baseline_row.current_generation IS DISTINCT FROM 1
                OR baseline_row.current_source_version IS DISTINCT FROM 1
                OR baseline_row.current_issuance_fence IS DISTINCT FROM 1
                OR baseline_row.current_issuance_fence_source_version IS DISTINCT FROM 1
                OR baseline_row.last_outbox_sequence IS DISTINCT FROM 0
                OR baseline_row.last_event_id IS NOT NULL OR baseline_row.last_event_digest IS NOT NULL
                OR baseline_row.cutoff_generation IS NOT NULL OR baseline_row.cutoff_stream_key IS NOT NULL
                OR baseline_row.cutoff_sequence IS NOT NULL
                OR baseline_row.initialization_provenance IS DISTINCT FROM 'ACCOUNT_REPOSITORY_INSERT'
                OR baseline_row.account_uuid_provenance IS DISTINCT FROM NEW.account_provenance
                OR baseline_row.account_source_numeric_id IS DISTINCT FROM NEW.account_id
                OR baseline_row.initialization_transaction_id <= 0
                OR baseline_row.initialization_transaction_id IS DISTINCT FROM authority_row.created_transaction_id
                OR baseline_row.initialization_transaction_id IS DISTINCT FROM account_row.account_repository_insert_transaction_id
                OR baseline_row.account_repository_insert_transaction_id IS DISTINCT FROM account_row.account_repository_insert_transaction_id
                OR EXISTS (SELECT 1 FROM account_authority_outbox_events WHERE outbox_stream_key = key) THEN
                RAISE EXCEPTION 'Security-state pristine baseline is not proved' USING ERRCODE = '23514';
            END IF;
        ELSE
            SELECT * INTO stream_row FROM account_authority_outbox_streams WHERE outbox_stream_key = key;
            SELECT * INTO outbox_row FROM account_authority_outbox_events
                WHERE outbox_stream_key = key AND outbox_sequence = NEW.checkpoint_sequence;
            IF stream_row IS NULL OR outbox_row IS NULL OR stream_row.last_sequence <> NEW.checkpoint_sequence
                OR outbox_row.payload <> NEW.checkpoint_payload THEN
                RAISE EXCEPTION 'Security-state original checkpoint differs' USING ERRCODE = '23514';
            END IF;
            checkpoint_text := convert_from(NEW.checkpoint_payload,'UTF8');
            checkpoint_json := checkpoint_text::JSONB;
            checkpoint_preimage := replace(checkpoint_text,
                '"eventDigest":"' || outbox_row.event_digest || '",', '');
            IF COALESCE(checkpoint_json->>'schemaVersion','') NOT IN ('account-auth-password-reset-event/v1',
                    'account-auth-logout-all-event/v1', 'account-auth-account-security-state-event/v1')
                OR checkpoint_json->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
                OR checkpoint_json->>'sourceScope' IS DISTINCT FROM 'account/' || NEW.account_uuid::TEXT
                OR checkpoint_json->>'outboxStreamKey' IS DISTINCT FROM key
                OR checkpoint_json->>'outboxSequence' IS DISTINCT FROM NEW.checkpoint_sequence::TEXT
                OR checkpoint_json->>'accountAuthorityGeneration' IS DISTINCT FROM NEW.expected_generation::TEXT
                OR checkpoint_json->>'sourceVersion' IS DISTINCT FROM NEW.expected_source_version::TEXT
                OR checkpoint_json->>'requestId' IS DISTINCT FROM outbox_row.request_id
                OR checkpoint_json->>'eventId' IS DISTINCT FROM outbox_row.event_id
                OR checkpoint_json->>'eventDigest' IS DISTINCT FROM outbox_row.event_digest
                OR checkpoint_json->'accountSecurityCutoff'->>'accountAuthorityGeneration' IS DISTINCT FROM NEW.expected_generation::TEXT
                OR checkpoint_json->'accountSecurityCutoff'->>'outboxStreamKey' IS DISTINCT FROM key
                OR checkpoint_json->'accountSecurityCutoff'->>'outboxSequence' IS DISTINCT FROM NEW.checkpoint_sequence::TEXT
                OR outbox_row.event_digest IS DISTINCT FROM 'sha256:' || encode(sha256(convert_to(checkpoint_preimage,'UTF8')),'hex')
                OR checkpoint_preimage = checkpoint_text THEN
                RAISE EXCEPTION 'Security-state checkpoint identity/counters/digest differ' USING ERRCODE = '23514';
            END IF;
        END IF;
    ELSE
        IF NEW.result_generation::NUMERIC <> NEW.expected_generation::NUMERIC + 1
            OR NEW.result_source_version::NUMERIC <> NEW.expected_source_version::NUMERIC + 1
            OR NEW.result_fence::NUMERIC <> NEW.expected_fence::NUMERIC + 1
            OR NEW.result_fence_source_version::NUMERIC <> NEW.expected_fence_source_version::NUMERIC + 1
            OR NEW.event_sequence::NUMERIC <> NEW.checkpoint_sequence::NUMERIC + 1
            OR NEW.result_global_role_source_version::NUMERIC <> NEW.global_role_source_version::NUMERIC
                + (CASE WHEN 'GLOBAL_ROLE_CHANGED' = ANY(kinds) THEN 1 ELSE 0 END)
            OR authority_row.generation <> NEW.result_generation OR authority_row.source_version <> NEW.result_source_version
            OR fence_row.issuance_fence <> NEW.result_fence OR fence_row.source_version <> NEW.result_fence_source_version
            OR role_row.global_role_source_version <> NEW.result_global_role_source_version THEN
            RAISE EXCEPTION 'Security-state producer result counters differ' USING ERRCODE = '23514';
        END IF;
        SELECT * INTO outbox_row FROM account_authority_outbox_events
            WHERE outbox_stream_key = key AND request_id = NEW.request_id::TEXT;
        SELECT * INTO stream_row FROM account_authority_outbox_streams WHERE outbox_stream_key = key;
        IF outbox_row IS NULL OR stream_row IS NULL OR outbox_row.outbox_sequence <> NEW.event_sequence
            OR outbox_row.event_id <> NEW.event_id OR outbox_row.event_digest <> NEW.event_digest
            OR outbox_row.payload <> NEW.event_payload OR stream_row.last_sequence <> NEW.event_sequence THEN
            RAISE EXCEPTION 'Security-state exact outbox receipt differs' USING ERRCODE = '23514';
        END IF;
        preimage := '{"accountAuthorityGeneration":"' || NEW.result_generation::TEXT
            || '","accountId":"' || NEW.account_uuid::TEXT
            || '","accountSecurityCutoff":{"accountAuthorityGeneration":"' || NEW.result_generation::TEXT
            || '","outboxSequence":"' || NEW.event_sequence::TEXT || '","outboxStreamKey":"' || key
            || '"},"accountState":' || convert_from(NEW.after_state,'UTF8')
            || ',"eventId":"account-security-state-event-v1:' || NEW.request_id::TEXT
            || '","eventType":"ACCOUNT_SECURITY_STATE_CHANGED","mutationKinds":'
            || replace(to_jsonb(kinds)::TEXT, ', ', ',')
            || ',"outboxSequence":"' || NEW.event_sequence::TEXT || '","outboxStreamKey":"' || key
            || '","requestId":"' || NEW.request_id::TEXT
            || '","schemaVersion":"account-auth-account-security-state-event/v1","sourceScope":"account/'
            || NEW.account_uuid::TEXT || '","sourceVersion":"' || NEW.result_source_version::TEXT || '"}';
        expected_digest := 'sha256:' || encode(sha256(convert_to(preimage,'UTF8')),'hex');
        expected_wire := replace(preimage, ',"eventId":', ',"eventDigest":"' || expected_digest || '","eventId":');
        IF NEW.event_digest IS DISTINCT FROM expected_digest OR NEW.event_payload IS DISTINCT FROM convert_to(expected_wire,'UTF8') THEN
            RAISE EXCEPTION 'Security-state canonical event/digest differs' USING ERRCODE = '23514';
        END IF;
        before_json := after_json;
    END IF;
    IF (before_json->'emailVerified')::BOOLEAN IS DISTINCT FROM account_row.email_verified
        OR before_json->>'lifecycleState' IS DISTINCT FROM upper(account_row.lifecycle_state)
        OR before_json->'globalRoles' IS DISTINCT FROM to_jsonb(role_row.global_roles)
        OR before_json->'loginAuthModes' IS DISTINCT FROM (SELECT jsonb_agg(v ORDER BY v)
            FROM unnest(string_to_array(account_row.login_auth_modes, ',')) v) THEN
        RAISE EXCEPTION 'Security-state exact owner-state readback differs' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_security_state_operation_guard BEFORE INSERT OR UPDATE
    ON account_security_state_operations FOR EACH ROW EXECUTE FUNCTION account_security_state_operation_guard();

CREATE FUNCTION account_security_state_retention_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Security-state operation evidence must be retained' USING ERRCODE = '23514';
    RETURN OLD;
END;
$$;
CREATE TRIGGER account_security_state_delete_denied BEFORE DELETE ON account_security_state_operations
    FOR EACH ROW EXECUTE FUNCTION account_security_state_retention_guard();
CREATE TRIGGER account_security_state_truncate_denied BEFORE TRUNCATE ON account_security_state_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_security_state_retention_guard();
-- [jooq ignore stop]
