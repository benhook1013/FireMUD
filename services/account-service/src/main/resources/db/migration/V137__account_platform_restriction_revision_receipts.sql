-- Account-local restriction revision receipts. The V81 (source V88) NONRESTRICTED rows remain historical
-- birth evidence only; they are the predecessor for a first revision, never current absence.
CREATE TABLE account_platform_restriction_operations (
    request_id UUID PRIMARY KEY
        CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    account_uuid UUID NOT NULL,
    account_id BIGINT NOT NULL,
    account_provenance VARCHAR(40) NOT NULL,
    category VARCHAR(64) NOT NULL,
    request_payload BYTEA NOT NULL CHECK (octet_length(request_payload) > 0),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    expected_account_generation BIGINT NOT NULL CHECK (expected_account_generation > 0),
    expected_account_source_version BIGINT NOT NULL CHECK (expected_account_source_version > 0),
    expected_fence BIGINT NOT NULL CHECK (expected_fence > 0),
    expected_fence_source_version BIGINT NOT NULL CHECK (expected_fence_source_version > 0),
    expected_category_revision BIGINT NOT NULL CHECK (expected_category_revision > 0),
    expected_enforcement_epoch BIGINT NOT NULL CHECK (expected_enforcement_epoch > 0),
    expected_result_id UUID NOT NULL
        CHECK (expected_result_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    desired_state VARCHAR(16) NOT NULL,
    source_kind VARCHAR(40) NOT NULL,
    source_request_id UUID NOT NULL
        CHECK (source_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    source_digest VARCHAR(71) NOT NULL CHECK (source_digest ~ '^sha256:[0-9a-f]{64}$'),
    result_id UUID NOT NULL CHECK (result_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    result_category_revision BIGINT NOT NULL CHECK (result_category_revision > 0),
    result_enforcement_epoch BIGINT NOT NULL CHECK (result_enforcement_epoch > 0),
    result_state VARCHAR(16) NOT NULL,
    result_generation BIGINT NOT NULL CHECK (result_generation > 0),
    result_source_version BIGINT NOT NULL CHECK (result_source_version > 0),
    result_fence BIGINT NOT NULL CHECK (result_fence > 0),
    result_fence_source_version BIGINT NOT NULL CHECK (result_fence_source_version > 0),
    outbox_stream_key VARCHAR(2048) NOT NULL,
    event_sequence BIGINT NOT NULL CHECK (event_sequence > 0),
    event_id VARCHAR(128) NOT NULL,
    event_digest VARCHAR(71) NOT NULL CHECK (event_digest ~ '^sha256:[0-9a-f]{64}$'),
    event_payload BYTEA NOT NULL CHECK (octet_length(event_payload) > 0),
    status VARCHAR(12) NOT NULL DEFAULT 'COMMITTED' CHECK (status = 'COMMITTED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_platform_restriction_operation_identity_check CHECK (
        category IN ('account_security_lock', 'platform_access_ban')
        AND desired_state IN ('RESTRICTED', 'NONRESTRICTED')
        AND result_state = desired_state
        AND result_category_revision = expected_category_revision + 1
        AND result_enforcement_epoch = expected_enforcement_epoch + 1
        AND result_generation = expected_account_generation + 1
        AND result_source_version = expected_account_source_version + 1
        AND result_fence = expected_fence + 1
        AND result_fence_source_version = expected_fence_source_version + 1
        AND outbox_stream_key = 'account:auth-authority:v1:account/' || account_uuid::TEXT
        AND event_sequence = result_generation - 1
        AND event_id = 'account-restriction-event-v1:' || request_id::TEXT
        AND ((category = 'account_security_lock'
                AND ((source_kind = 'ACCOUNT_SECURITY_POLICY' AND desired_state = 'RESTRICTED')
                    OR (source_kind = 'ACCOUNT_SECURITY_RECOVERY' AND desired_state = 'NONRESTRICTED')))
            OR (category = 'platform_access_ban'
                AND source_kind = 'LOGGING_ADMIN_MODERATION'))),
    CONSTRAINT account_platform_restriction_operation_account_fk
        FOREIGN KEY (account_uuid, account_id, account_provenance)
        REFERENCES accounts(account_uuid, account_uuid_source_numeric_id, account_uuid_provenance)
        ON DELETE RESTRICT,
    CONSTRAINT account_platform_restriction_operation_outbox_fk
        FOREIGN KEY (outbox_stream_key, event_sequence)
        REFERENCES account_authority_outbox_events(outbox_stream_key, outbox_sequence)
        ON DELETE RESTRICT,
    CONSTRAINT account_platform_restriction_operation_request_category_uq
        UNIQUE (request_id, account_uuid, category),
    CONSTRAINT account_platform_restriction_operation_revision_uq
        UNIQUE (account_uuid, category, result_category_revision),
    CONSTRAINT account_platform_restriction_operation_event_uq
        UNIQUE (outbox_stream_key, event_id)
);

CREATE TABLE account_platform_restriction_revisions (
    account_uuid UUID NOT NULL,
    category VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    enforcement_epoch BIGINT NOT NULL CHECK (enforcement_epoch > 0),
    result_id UUID NOT NULL CHECK (result_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_id UUID NOT NULL,
    restriction_state VARCHAR(16) NOT NULL,
    source_kind VARCHAR(40) NOT NULL,
    source_request_id UUID NOT NULL,
    source_digest VARCHAR(71) NOT NULL CHECK (source_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    result_generation BIGINT NOT NULL CHECK (result_generation > 0),
    result_source_version BIGINT NOT NULL CHECK (result_source_version > 0),
    outbox_stream_key VARCHAR(2048) NOT NULL,
    outbox_sequence BIGINT NOT NULL CHECK (outbox_sequence > 0),
    event_id VARCHAR(128) NOT NULL,
    event_digest VARCHAR(71) NOT NULL CHECK (event_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_platform_restriction_revision_pk PRIMARY KEY (account_uuid, category, revision),
    CONSTRAINT account_platform_restriction_revision_category_check CHECK (
        category IN ('account_security_lock', 'platform_access_ban')
        AND restriction_state IN ('RESTRICTED', 'NONRESTRICTED')),
    CONSTRAINT account_platform_restriction_revision_operation_fk
        FOREIGN KEY (request_id, account_uuid, category)
        REFERENCES account_platform_restriction_operations(request_id, account_uuid, category)
        ON DELETE RESTRICT,
    CONSTRAINT account_platform_restriction_revision_outbox_fk
        FOREIGN KEY (outbox_stream_key, outbox_sequence)
        REFERENCES account_authority_outbox_events(outbox_stream_key, outbox_sequence)
        ON DELETE RESTRICT,
    CONSTRAINT account_platform_restriction_revision_request_uq UNIQUE (request_id),
    CONSTRAINT account_platform_restriction_revision_current_fk_target_uq
        UNIQUE (account_uuid, category, revision, enforcement_epoch, result_id, restriction_state, request_id)
);

CREATE TABLE account_platform_restriction_current_projections (
    account_uuid UUID NOT NULL,
    category VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    enforcement_epoch BIGINT NOT NULL CHECK (enforcement_epoch > 0),
    result_id UUID NOT NULL CHECK (result_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    restriction_state VARCHAR(16) NOT NULL,
    request_id UUID NOT NULL,
    CONSTRAINT account_platform_restriction_current_pk PRIMARY KEY (account_uuid, category),
    CONSTRAINT account_platform_restriction_current_revision_fk
        FOREIGN KEY (account_uuid, category, revision, enforcement_epoch, result_id,
                     restriction_state, request_id)
        REFERENCES account_platform_restriction_revisions(
            account_uuid, category, revision, enforcement_epoch, result_id,
            restriction_state, request_id)
        ON DELETE RESTRICT
);

-- Operations and revisions are permanent receipts; only the explicit current projection advances.
-- This migration intentionally does not backfill V81 (source V88) birth state into a current projection.
-- The closed source-head dispatch is added below, but the mutation command remains default-denied
-- until the exact policy/intent/authentication proof receiver exists; these tables alone do not
-- activate writes.
-- [jooq ignore start]
CREATE FUNCTION account_platform_restriction_operation_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    payload JSONB;
    event_row RECORD;
    expected_fields TEXT[] := ARRAY[
        'schemaVersion', 'eventType', 'eventId', 'requestId', 'accountId', 'sourceScope',
        'outboxStreamKey', 'outboxSequence', 'accountAuthorityGeneration', 'sourceVersion',
        'accountSecurityCutoff', 'restrictionCategory', 'restrictionRevision',
        'restrictionEnforcementEpoch', 'restrictionState', 'restrictionResultId',
        'restrictionRequestDigest', 'restrictionSourceKind', 'restrictionSourceRequestId',
        'restrictionSourceDigest', 'eventDigest'];
BEGIN
    payload := convert_from(NEW.event_payload, 'UTF8')::JSONB;
    SELECT request_id, event_id, event_digest, payload
        INTO event_row
        FROM account_authority_outbox_events
        WHERE outbox_stream_key = NEW.outbox_stream_key
          AND outbox_sequence = NEW.event_sequence;
    IF event_row.request_id IS DISTINCT FROM NEW.request_id::TEXT
        OR event_row.event_id IS DISTINCT FROM NEW.event_id
        OR event_row.event_digest IS DISTINCT FROM NEW.event_digest
        OR event_row.payload IS DISTINCT FROM NEW.event_payload
        OR (payload - expected_fields) <> '{}'::JSONB
        OR NOT (payload ?& expected_fields)
        OR payload->>'schemaVersion' IS DISTINCT FROM 'account-auth-account-security-state-event/v1'
        OR payload->>'eventType' IS DISTINCT FROM 'ACCOUNT_RESTRICTION_CHANGED'
        OR payload->>'eventId' IS DISTINCT FROM NEW.event_id
        OR payload->>'requestId' IS DISTINCT FROM NEW.request_id::TEXT
        OR payload->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR payload->>'sourceScope' IS DISTINCT FROM 'account/' || NEW.account_uuid::TEXT
        OR payload->>'outboxStreamKey' IS DISTINCT FROM NEW.outbox_stream_key
        OR payload->>'outboxSequence' IS DISTINCT FROM NEW.event_sequence::TEXT
        OR payload->>'accountAuthorityGeneration' IS DISTINCT FROM NEW.result_generation::TEXT
        OR payload->>'sourceVersion' IS DISTINCT FROM NEW.result_source_version::TEXT
        OR payload#>>'{accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM NEW.result_generation::TEXT
        OR payload#>>'{accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM NEW.outbox_stream_key
        OR payload#>>'{accountSecurityCutoff,outboxSequence}' IS DISTINCT FROM NEW.event_sequence::TEXT
        OR payload->>'restrictionCategory' IS DISTINCT FROM NEW.category
        OR payload->>'restrictionRevision' IS DISTINCT FROM NEW.result_category_revision::TEXT
        OR payload->>'restrictionEnforcementEpoch' IS DISTINCT FROM NEW.result_enforcement_epoch::TEXT
        OR payload->>'restrictionState' IS DISTINCT FROM NEW.result_state
        OR payload->>'restrictionResultId' IS DISTINCT FROM NEW.result_id::TEXT
        OR payload->>'restrictionRequestDigest' IS DISTINCT FROM NEW.request_digest
        OR payload->>'restrictionSourceKind' IS DISTINCT FROM NEW.source_kind
        OR payload->>'restrictionSourceRequestId' IS DISTINCT FROM NEW.source_request_id::TEXT
        OR payload->>'restrictionSourceDigest' IS DISTINCT FROM NEW.source_digest THEN
        RAISE EXCEPTION 'Account restriction operation requires its exact closed source event and outbox receipt'
            USING ERRCODE = '23514', CONSTRAINT = 'account_platform_restriction_operation_event_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_platform_restriction_operation_insert_guard
    BEFORE INSERT ON account_platform_restriction_operations
    FOR EACH ROW EXECUTE FUNCTION account_platform_restriction_operation_insert_guard();

CREATE FUNCTION account_platform_restriction_revision_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    operation_row RECORD;
BEGIN
    SELECT * INTO operation_row
        FROM account_platform_restriction_operations
        WHERE request_id = NEW.request_id;
    IF operation_row.request_id IS NULL
        OR operation_row.account_uuid IS DISTINCT FROM NEW.account_uuid
        OR operation_row.category IS DISTINCT FROM NEW.category
        OR operation_row.result_category_revision IS DISTINCT FROM NEW.revision
        OR operation_row.result_enforcement_epoch IS DISTINCT FROM NEW.enforcement_epoch
        OR operation_row.result_id IS DISTINCT FROM NEW.result_id
        OR operation_row.result_state IS DISTINCT FROM NEW.restriction_state
        OR operation_row.source_kind IS DISTINCT FROM NEW.source_kind
        OR operation_row.source_request_id IS DISTINCT FROM NEW.source_request_id
        OR operation_row.source_digest IS DISTINCT FROM NEW.source_digest
        OR operation_row.request_digest IS DISTINCT FROM NEW.request_digest
        OR operation_row.result_generation IS DISTINCT FROM NEW.result_generation
        OR operation_row.result_source_version IS DISTINCT FROM NEW.result_source_version
        OR operation_row.outbox_stream_key IS DISTINCT FROM NEW.outbox_stream_key
        OR operation_row.event_sequence IS DISTINCT FROM NEW.outbox_sequence
        OR operation_row.event_id IS DISTINCT FROM NEW.event_id
        OR operation_row.event_digest IS DISTINCT FROM NEW.event_digest THEN
        RAISE EXCEPTION 'Account restriction revision must match its exact immutable operation and source receipt'
            USING ERRCODE = '23514', CONSTRAINT = 'account_platform_restriction_revision_receipt';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_platform_restriction_revision_insert_guard
    BEFORE INSERT ON account_platform_restriction_revisions
    FOR EACH ROW EXECUTE FUNCTION account_platform_restriction_revision_insert_guard();

CREATE FUNCTION account_platform_restriction_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account restriction operation and revision receipts are immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'account_platform_restriction_immutable';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_platform_restriction_operation_immutable
    BEFORE UPDATE OR DELETE ON account_platform_restriction_operations
    FOR EACH ROW EXECUTE FUNCTION account_platform_restriction_immutable_guard();
CREATE TRIGGER account_platform_restriction_revision_immutable
    BEFORE UPDATE OR DELETE ON account_platform_restriction_revisions
    FOR EACH ROW EXECUTE FUNCTION account_platform_restriction_immutable_guard();

CREATE FUNCTION account_platform_restriction_current_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_revision BIGINT;
    expected_epoch BIGINT;
    expected_result UUID;
    operation_row RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Current Account restriction projection cannot be removed'
            USING ERRCODE = '23514', CONSTRAINT = 'account_platform_restriction_current_monotonic';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
            OR NEW.category IS DISTINCT FROM OLD.category
            OR NEW.revision <> OLD.revision + 1
            OR NEW.enforcement_epoch <> OLD.enforcement_epoch + 1 THEN
            RAISE EXCEPTION 'Current Account restriction category revision must advance exactly once'
                USING ERRCODE = '23514', CONSTRAINT = 'account_platform_restriction_current_monotonic';
        END IF;
        SELECT * INTO operation_row
            FROM account_platform_restriction_operations
            WHERE request_id = NEW.request_id;
        IF operation_row.request_id IS NULL
            OR operation_row.account_uuid IS DISTINCT FROM NEW.account_uuid
            OR operation_row.category IS DISTINCT FROM NEW.category
            OR operation_row.expected_category_revision IS DISTINCT FROM OLD.revision
            OR operation_row.expected_enforcement_epoch IS DISTINCT FROM OLD.enforcement_epoch
            OR operation_row.expected_result_id IS DISTINCT FROM OLD.result_id
            OR operation_row.result_category_revision IS DISTINCT FROM NEW.revision
            OR operation_row.result_enforcement_epoch IS DISTINCT FROM NEW.enforcement_epoch
            OR operation_row.result_id IS DISTINCT FROM NEW.result_id
            OR operation_row.result_state IS DISTINCT FROM NEW.restriction_state THEN
            RAISE EXCEPTION 'Current Account restriction projection does not advance its exact immutable category receipt'
                USING ERRCODE = '23514', CONSTRAINT = 'account_platform_restriction_current_receipt';
        END IF;
        RETURN NEW;
    END IF;

    SELECT revision, enforcement_epoch, result_id
        INTO expected_revision, expected_epoch, expected_result
        FROM account_platform_restriction_births
        WHERE account_uuid = NEW.account_uuid AND category = NEW.category;
    IF expected_revision IS NULL
        OR NEW.revision <> expected_revision + 1
        OR NEW.enforcement_epoch <> expected_epoch + 1
        OR NEW.result_id = expected_result THEN
        RAISE EXCEPTION 'First current restriction result must advance its historical birth predecessor'
            USING ERRCODE = '23514', CONSTRAINT = 'account_platform_restriction_current_birth_predecessor';
    END IF;
    SELECT * INTO operation_row
        FROM account_platform_restriction_operations
        WHERE request_id = NEW.request_id;
    IF operation_row.request_id IS NULL
        OR operation_row.account_uuid IS DISTINCT FROM NEW.account_uuid
        OR operation_row.category IS DISTINCT FROM NEW.category
        OR operation_row.expected_category_revision IS DISTINCT FROM expected_revision
        OR operation_row.expected_enforcement_epoch IS DISTINCT FROM expected_epoch
        OR operation_row.expected_result_id IS DISTINCT FROM expected_result
        OR operation_row.result_category_revision IS DISTINCT FROM NEW.revision
        OR operation_row.result_enforcement_epoch IS DISTINCT FROM NEW.enforcement_epoch
        OR operation_row.result_id IS DISTINCT FROM NEW.result_id
        OR operation_row.result_state IS DISTINCT FROM NEW.restriction_state THEN
        RAISE EXCEPTION 'First current Account restriction projection does not advance its exact birth and operation receipts'
            USING ERRCODE = '23514', CONSTRAINT = 'account_platform_restriction_current_receipt';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_platform_restriction_current_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_platform_restriction_current_projections
    FOR EACH ROW EXECUTE FUNCTION account_platform_restriction_current_guard();

CREATE FUNCTION account_platform_restriction_truncate_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Current Account restriction projection cannot be truncated'
        USING ERRCODE = '23514', CONSTRAINT = 'account_platform_restriction_current_monotonic';
    RETURN NULL;
END;
$$;
CREATE TRIGGER account_platform_restriction_operation_no_truncate
    BEFORE TRUNCATE ON account_platform_restriction_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_platform_restriction_truncate_guard();
CREATE TRIGGER account_platform_restriction_revision_no_truncate
    BEFORE TRUNCATE ON account_platform_restriction_revisions
    FOR EACH STATEMENT EXECUTE FUNCTION account_platform_restriction_truncate_guard();
CREATE TRIGGER account_platform_restriction_current_no_truncate
    BEFORE TRUNCATE ON account_platform_restriction_current_projections
    FOR EACH STATEMENT EXECUTE FUNCTION account_platform_restriction_truncate_guard();
-- [jooq ignore end]

-- [jooq ignore start]
CREATE FUNCTION account_platform_restriction_require_source_event(
    source_record account_authority_source_records,
    event_payload JSONB,
    event_payload_bytes BYTEA)
RETURNS account_platform_restriction_operations
LANGUAGE plpgsql
AS $$
DECLARE
    expected_fields TEXT[] := ARRAY[
        'schemaVersion', 'eventType', 'eventId', 'requestId', 'accountId', 'sourceScope',
        'outboxStreamKey', 'outboxSequence', 'accountAuthorityGeneration', 'sourceVersion',
        'accountSecurityCutoff', 'restrictionCategory', 'restrictionRevision',
        'restrictionEnforcementEpoch', 'restrictionState', 'restrictionResultId',
        'restrictionRequestDigest', 'restrictionSourceKind', 'restrictionSourceRequestId',
        'restrictionSourceDigest', 'eventDigest'];
    expected_cutoff_fields TEXT[] := ARRAY[
        'accountAuthorityGeneration', 'outboxStreamKey', 'outboxSequence'];
    event_row RECORD;
    account_row RECORD;
    authority_row RECORD;
    fence_row RECORD;
    operation_row account_platform_restriction_operations%ROWTYPE;
    revision_row RECORD;
    current_row RECORD;
    request_uuid UUID;
    result_uuid UUID;
    source_request_uuid UUID;
    field_name TEXT;
    restriction_category_value TEXT;
    restriction_state TEXT;
    source_kind TEXT;
    cutoff JSONB;
BEGIN
    IF jsonb_typeof(event_payload) IS DISTINCT FROM 'object'
        OR (event_payload - expected_fields) <> '{}'::JSONB
        OR NOT (event_payload ?& expected_fields) THEN
        RAISE EXCEPTION 'Account restriction source event is not the exact closed current variant'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_restriction_event_values';
    END IF;
    FOREACH field_name IN ARRAY expected_fields LOOP
        IF field_name <> 'accountSecurityCutoff'
            AND jsonb_typeof(event_payload->field_name) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Account restriction source event fields must use their exact string representation'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_restriction_event_values';
        END IF;
    END LOOP;

    IF event_payload->>'schemaVersion' IS DISTINCT FROM 'account-auth-account-security-state-event/v1'
        OR event_payload->>'eventType' IS DISTINCT FROM 'ACCOUNT_RESTRICTION_CHANGED'
        OR event_payload->>'requestId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR event_payload->>'accountId' IS DISTINCT FROM source_record.account_uuid::TEXT
        OR event_payload->>'sourceScope' IS DISTINCT FROM 'account/' || source_record.account_uuid::TEXT
        OR event_payload->>'outboxStreamKey' IS DISTINCT FROM source_record.outbox_stream_key
        OR event_payload->>'outboxSequence' IS DISTINCT FROM source_record.last_outbox_sequence::TEXT
        OR event_payload->>'eventId' IS DISTINCT FROM
            'account-restriction-event-v1:' || (event_payload->>'requestId')
        OR event_payload->>'eventId' IS DISTINCT FROM source_record.last_event_id
        OR event_payload->>'eventDigest' IS DISTINCT FROM source_record.last_event_digest
        OR event_payload->>'accountAuthorityGeneration' IS DISTINCT FROM source_record.current_generation::TEXT
        OR event_payload->>'sourceVersion' IS DISTINCT FROM source_record.current_source_version::TEXT
        OR event_payload->>'accountAuthorityGeneration' !~ '^[1-9][0-9]*$'
        OR event_payload->>'sourceVersion' !~ '^[1-9][0-9]*$'
        OR event_payload->>'outboxSequence' !~ '^[1-9][0-9]*$'
        OR event_payload->>'eventDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR event_payload->>'restrictionRequestDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR event_payload->>'restrictionSourceDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR event_payload->>'restrictionRevision' !~ '^[1-9][0-9]*$'
        OR event_payload->>'restrictionEnforcementEpoch' !~ '^[1-9][0-9]*$'
        OR event_payload->>'restrictionResultId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR event_payload->>'restrictionSourceRequestId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR event_payload->>'restrictionState' NOT IN ('RESTRICTED', 'NONRESTRICTED') THEN
        RAISE EXCEPTION 'Account restriction source event is not the exact closed current variant'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_restriction_event_values';
    END IF;
    cutoff := event_payload->'accountSecurityCutoff';
    IF jsonb_typeof(cutoff) IS DISTINCT FROM 'object'
        OR (cutoff - expected_cutoff_fields) <> '{}'::JSONB
        OR NOT (cutoff ?& expected_cutoff_fields)
        OR cutoff->>'accountAuthorityGeneration' IS DISTINCT FROM event_payload->>'accountAuthorityGeneration'
        OR cutoff->>'outboxStreamKey' IS DISTINCT FROM event_payload->>'outboxStreamKey'
        OR cutoff->>'outboxSequence' IS DISTINCT FROM event_payload->>'outboxSequence' THEN
        RAISE EXCEPTION 'Account restriction source event cutoff is not its exact current source'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_restriction_event_values';
    END IF;
    FOREACH field_name IN ARRAY expected_cutoff_fields LOOP
        IF jsonb_typeof(cutoff->field_name) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Account restriction source event cutoff fields must be strings'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_restriction_event_values';
        END IF;
    END LOOP;

    request_uuid := (event_payload->>'requestId')::UUID;
    result_uuid := (event_payload->>'restrictionResultId')::UUID;
    source_request_uuid := (event_payload->>'restrictionSourceRequestId')::UUID;
    restriction_category_value := event_payload->>'restrictionCategory';
    restriction_state := event_payload->>'restrictionState';
    source_kind := event_payload->>'restrictionSourceKind';
    IF request_uuid = '00000000-0000-0000-0000-000000000000'::UUID
        OR result_uuid = '00000000-0000-0000-0000-000000000000'::UUID
        OR source_request_uuid = '00000000-0000-0000-0000-000000000000'::UUID
        OR NOT (restriction_category_value IN ('account_security_lock', 'platform_access_ban'))
        OR NOT ((restriction_category_value = 'account_security_lock'
                AND ((source_kind = 'ACCOUNT_SECURITY_POLICY' AND restriction_state = 'RESTRICTED')
                    OR (source_kind = 'ACCOUNT_SECURITY_RECOVERY' AND restriction_state = 'NONRESTRICTED')))
            OR (restriction_category_value = 'platform_access_ban'
                AND source_kind = 'LOGGING_ADMIN_MODERATION')) THEN
        RAISE EXCEPTION 'Account restriction source category or owner kind is unsupported'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_restriction_event_values';
    END IF;

    SELECT event_id, event_digest, request_id, payload
        INTO event_row FROM account_authority_outbox_events
        WHERE outbox_stream_key = source_record.outbox_stream_key
          AND outbox_sequence = source_record.last_outbox_sequence;
    IF event_row.event_id IS DISTINCT FROM source_record.last_event_id
        OR event_row.event_digest IS DISTINCT FROM source_record.last_event_digest
        OR event_row.request_id IS DISTINCT FROM request_uuid::TEXT
        OR event_row.payload IS DISTINCT FROM event_payload_bytes THEN
        RAISE EXCEPTION 'Account restriction source event differs from its exact outbox receipt'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_restriction_event_values';
    END IF;

    SELECT id, account_uuid_source_numeric_id, account_uuid_provenance
        INTO account_row FROM accounts WHERE account_uuid = source_record.account_uuid;
    SELECT generation, source_version INTO authority_row
        FROM account_authority_generations
        WHERE scope_kind = 'ACCOUNT' AND account_uuid = source_record.account_uuid
          AND issuer_id IS NULL AND tenant_uuid IS NULL;
    SELECT issuance_fence, source_version INTO fence_row
        FROM account_authority_issuance_fences WHERE account_uuid = source_record.account_uuid;
    IF source_record.scope_kind IS DISTINCT FROM 'ACCOUNT'
        OR account_row.id IS DISTINCT FROM source_record.account_source_numeric_id
        OR account_row.account_uuid_source_numeric_id IS DISTINCT FROM source_record.account_source_numeric_id
        OR account_row.account_uuid_provenance IS DISTINCT FROM source_record.account_uuid_provenance
        OR authority_row.generation IS DISTINCT FROM source_record.current_generation
        OR authority_row.source_version IS DISTINCT FROM source_record.current_source_version
        OR fence_row.issuance_fence IS DISTINCT FROM source_record.current_issuance_fence
        OR fence_row.source_version IS DISTINCT FROM source_record.current_issuance_fence_source_version THEN
        RAISE EXCEPTION 'Account restriction source event differs from its current Account source owners'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_restriction_event_values';
    END IF;

    SELECT * INTO operation_row FROM account_platform_restriction_operations
        WHERE request_id = request_uuid;
    IF operation_row.request_id IS NULL
        OR operation_row.status IS DISTINCT FROM 'COMMITTED'
        OR operation_row.account_uuid IS DISTINCT FROM source_record.account_uuid
        OR operation_row.account_id IS DISTINCT FROM account_row.id
        OR operation_row.account_provenance IS DISTINCT FROM source_record.account_uuid_provenance
        OR operation_row.category IS DISTINCT FROM restriction_category_value
        OR operation_row.request_digest IS DISTINCT FROM event_payload->>'restrictionRequestDigest'
        OR operation_row.desired_state IS DISTINCT FROM restriction_state
        OR operation_row.source_kind IS DISTINCT FROM source_kind
        OR operation_row.source_request_id IS DISTINCT FROM source_request_uuid
        OR operation_row.source_digest IS DISTINCT FROM event_payload->>'restrictionSourceDigest'
        OR operation_row.expected_category_revision + 1 IS DISTINCT FROM operation_row.result_category_revision
        OR operation_row.expected_enforcement_epoch + 1 IS DISTINCT FROM operation_row.result_enforcement_epoch
        OR operation_row.expected_account_generation + 1 IS DISTINCT FROM source_record.current_generation
        OR operation_row.expected_account_source_version + 1 IS DISTINCT FROM source_record.current_source_version
        OR operation_row.expected_fence + 1 IS DISTINCT FROM source_record.current_issuance_fence
        OR operation_row.expected_fence_source_version + 1 IS DISTINCT FROM source_record.current_issuance_fence_source_version
        OR operation_row.result_id IS DISTINCT FROM result_uuid
        OR operation_row.result_category_revision::TEXT IS DISTINCT FROM event_payload->>'restrictionRevision'
        OR operation_row.result_enforcement_epoch::TEXT IS DISTINCT FROM event_payload->>'restrictionEnforcementEpoch'
        OR operation_row.result_state IS DISTINCT FROM restriction_state
        OR operation_row.result_generation IS DISTINCT FROM source_record.current_generation
        OR operation_row.result_source_version IS DISTINCT FROM source_record.current_source_version
        OR operation_row.result_fence IS DISTINCT FROM source_record.current_issuance_fence
        OR operation_row.result_fence_source_version IS DISTINCT FROM source_record.current_issuance_fence_source_version
        OR operation_row.outbox_stream_key IS DISTINCT FROM source_record.outbox_stream_key
        OR operation_row.event_sequence IS DISTINCT FROM source_record.last_outbox_sequence
        OR operation_row.event_id IS DISTINCT FROM source_record.last_event_id
        OR operation_row.event_digest IS DISTINCT FROM source_record.last_event_digest
        OR operation_row.event_payload IS DISTINCT FROM event_payload_bytes THEN
        RAISE EXCEPTION 'Account restriction source event has no exact committed owner receipt'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_restriction_receipt';
    END IF;

    SELECT * INTO revision_row FROM account_platform_restriction_revisions
        WHERE account_uuid = source_record.account_uuid AND category = restriction_category_value
          AND revision = operation_row.result_category_revision;
    SELECT * INTO current_row FROM account_platform_restriction_current_projections
        WHERE account_uuid = source_record.account_uuid AND category = restriction_category_value;
    IF revision_row.request_id IS DISTINCT FROM request_uuid
        OR revision_row.enforcement_epoch IS DISTINCT FROM operation_row.result_enforcement_epoch
        OR revision_row.result_id IS DISTINCT FROM result_uuid
        OR revision_row.restriction_state IS DISTINCT FROM restriction_state
        OR revision_row.source_kind IS DISTINCT FROM source_kind
        OR revision_row.source_request_id IS DISTINCT FROM source_request_uuid
        OR revision_row.source_digest IS DISTINCT FROM event_payload->>'restrictionSourceDigest'
        OR revision_row.request_digest IS DISTINCT FROM event_payload->>'restrictionRequestDigest'
        OR revision_row.result_generation IS DISTINCT FROM source_record.current_generation
        OR revision_row.result_source_version IS DISTINCT FROM source_record.current_source_version
        OR revision_row.outbox_stream_key IS DISTINCT FROM source_record.outbox_stream_key
        OR revision_row.outbox_sequence IS DISTINCT FROM source_record.last_outbox_sequence
        OR revision_row.event_id IS DISTINCT FROM source_record.last_event_id
        OR revision_row.event_digest IS DISTINCT FROM source_record.last_event_digest
        OR current_row.revision IS DISTINCT FROM operation_row.result_category_revision
        OR current_row.enforcement_epoch IS DISTINCT FROM operation_row.result_enforcement_epoch
        OR current_row.result_id IS DISTINCT FROM result_uuid
        OR current_row.restriction_state IS DISTINCT FROM restriction_state
        OR current_row.request_id IS DISTINCT FROM request_uuid
        OR (SELECT MAX(revision) FROM account_platform_restriction_revisions
            WHERE account_uuid = source_record.account_uuid AND category = restriction_category_value)
            IS DISTINCT FROM current_row.revision THEN
        RAISE EXCEPTION 'Account restriction current projection or retained revision differs from its exact source receipt'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_restriction_projection';
    END IF;
    RETURN operation_row;
END;
$$;

CREATE FUNCTION account_platform_restriction_require_lifecycle_transition(
    old_account accounts,
    new_account accounts,
    source_record account_authority_source_records,
    event_payload JSONB,
    event_payload_bytes BYTEA)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    operation_row account_platform_restriction_operations%ROWTYPE;
BEGIN
    operation_row := account_platform_restriction_require_source_event(
        source_record, event_payload, event_payload_bytes);
    IF old_account.account_uuid IS DISTINCT FROM new_account.account_uuid
        OR old_account.id IS DISTINCT FROM new_account.id
        OR old_account.account_uuid_provenance IS DISTINCT FROM new_account.account_uuid_provenance
        OR old_account.account_uuid_source_numeric_id IS DISTINCT FROM new_account.account_uuid_source_numeric_id
        OR old_account.password_hash IS DISTINCT FROM new_account.password_hash
        OR old_account.email_verified IS DISTINCT FROM new_account.email_verified
        OR old_account.login_auth_modes IS DISTINCT FROM new_account.login_auth_modes
        OR old_account.role IS DISTINCT FROM new_account.role
        OR operation_row.category IS DISTINCT FROM 'account_security_lock'
        OR NOT ((operation_row.result_state = 'RESTRICTED'
                AND old_account.lifecycle_state = 'active'
                AND new_account.lifecycle_state = 'security_locked')
            OR (operation_row.result_state = 'NONRESTRICTED'
                AND old_account.lifecycle_state = 'security_locked'
                AND new_account.lifecycle_state = 'active')) THEN
        RAISE EXCEPTION 'Account lifecycle mutation requires the exact protective Account security-lock result'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_account_security_event_mismatch';
    END IF;
END;
$$;

CREATE FUNCTION account_platform_restriction_sync_security_lock_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    event_row RECORD;
    event_payload JSONB;
    operation_row account_platform_restriction_operations%ROWTYPE;
BEGIN
    IF NEW.scope_kind IS DISTINCT FROM 'ACCOUNT'
        OR NEW.last_outbox_sequence = OLD.last_outbox_sequence THEN
        RETURN NULL;
    END IF;
    SELECT payload INTO event_row FROM account_authority_outbox_events
        WHERE outbox_stream_key = NEW.outbox_stream_key
          AND outbox_sequence = NEW.last_outbox_sequence;
    IF event_row.payload IS NULL THEN RETURN NULL; END IF;
    event_payload := convert_from(event_row.payload, 'UTF8')::JSONB;
    IF event_payload->>'eventType' IS DISTINCT FROM 'ACCOUNT_RESTRICTION_CHANGED'
        OR event_payload->>'restrictionCategory' IS DISTINCT FROM 'account_security_lock' THEN
        RETURN NULL;
    END IF;
    operation_row := account_platform_restriction_require_source_event(
        NEW, event_payload, event_row.payload);
    UPDATE accounts SET lifecycle_state =
        CASE operation_row.result_state
            WHEN 'RESTRICTED' THEN 'security_locked'
            ELSE 'active'
        END
        WHERE account_uuid = NEW.account_uuid
          AND lifecycle_state IS DISTINCT FROM CASE operation_row.result_state
              WHEN 'RESTRICTED' THEN 'security_locked'
              ELSE 'active'
          END;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_platform_restriction_sync_security_lock_lifecycle
    AFTER UPDATE OF last_event_id ON account_authority_source_records
    FOR EACH ROW EXECUTE FUNCTION account_platform_restriction_sync_security_lock_lifecycle();

-- Preserve every existing source-head and Account mutation branch verbatim, inserting only the
-- closed restriction dispatch before each existing unsupported/legacy fallback.
DO $$
DECLARE
    source_definition TEXT;
    account_definition TEXT;
    old_source_dispatch TEXT := $old$
        ELSE
            RAISE EXCEPTION 'Unsupported Account source event type'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
        END IF;$old$;
    new_source_dispatch TEXT := $new$
        ELSIF event_payload->>'eventType' = 'ACCOUNT_RESTRICTION_CHANGED' THEN
            PERFORM account_platform_restriction_require_source_event(
                NEW, event_payload, event_row.payload);
        ELSE
            RAISE EXCEPTION 'Unsupported Account source event type'
                USING ERRCODE = '23514', CONSTRAINT = 'account_authority_source_event_values';
        END IF;$new$;
    old_account_dispatch TEXT := $old$
        RETURN NULL;
    END IF;

    -- Keep the legacy Account-auth event checks and mutation-family interpretation unchanged.$old$;
    new_account_dispatch TEXT := $new$
        RETURN NULL;
    ELSIF event_payload->>'eventType' = 'ACCOUNT_RESTRICTION_CHANGED' THEN
        PERFORM account_platform_restriction_require_lifecycle_transition(
            OLD, NEW, source_row, event_payload, event_row.payload);
        RETURN NULL;
    END IF;

    -- Keep the legacy Account-auth event checks and mutation-family interpretation unchanged.$new$;
BEGIN
    SELECT pg_get_functiondef('account_authority_source_record_update_guard()'::regprocedure)
        INTO source_definition;
    IF source_definition IS NULL
        OR length(source_definition) - length(replace(source_definition, old_source_dispatch, ''))
            <> length(old_source_dispatch) THEN
        RAISE EXCEPTION 'Expected exact V80 source-head fallback was not found once';
    END IF;
    EXECUTE replace(source_definition, old_source_dispatch, new_source_dispatch);

    SELECT pg_get_functiondef('account_authority_account_security_update_guard()'::regprocedure)
        INTO account_definition;
    IF account_definition IS NULL
        OR length(account_definition) - length(replace(account_definition, old_account_dispatch, ''))
            <> length(old_account_dispatch) THEN
        RAISE EXCEPTION 'Expected exact V80 Account mutation fallback was not found once';
    END IF;
    EXECUTE replace(account_definition, old_account_dispatch, new_account_dispatch);
END;
$$;
-- [jooq ignore end]
