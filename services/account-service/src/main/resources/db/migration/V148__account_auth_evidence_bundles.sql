-- Strict, immutable, non-authorizing evidence snapshots for exact Account-owned issuance rows.
-- This table does not introduce COMMITTED/ACTIVE state or provide credential response recovery.
CREATE TABLE account_gameplay_auth_evidence_source_fence (
    allocator_id BOOLEAN PRIMARY KEY DEFAULT TRUE,
    last_source_fence BIGINT NOT NULL,
    last_operation_id UUID,
    last_transaction_id VARCHAR(20),
    CONSTRAINT account_gameplay_auth_evidence_source_fence_singleton
        CHECK (allocator_id),
    CONSTRAINT account_gameplay_auth_evidence_source_fence_state
        CHECK ((last_source_fence = 0 AND last_operation_id IS NULL AND last_transaction_id IS NULL)
            OR (last_source_fence > 0 AND last_operation_id IS NOT NULL
                AND last_transaction_id IS NOT NULL
                AND last_transaction_id ~ '^[1-9][0-9]{0,19}$'
                AND last_transaction_id::numeric <= 18446744073709551615)),
    CONSTRAINT account_gameplay_auth_evidence_source_fence_operation_fk
        FOREIGN KEY (last_operation_id)
        REFERENCES account_gameplay_delegation_issuance_operations(operation_id)
        ON DELETE RESTRICT
);
INSERT INTO account_gameplay_auth_evidence_source_fence (allocator_id, last_source_fence)
VALUES (TRUE, 0);

CREATE TABLE account_gameplay_delegation_auth_evidence_bundles (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    account_uuid UUID NOT NULL,
    bundle_schema VARCHAR(64) NOT NULL,
    bundle_version VARCHAR(19) NOT NULL,
    source_version VARCHAR(20) NOT NULL,
    source_fence VARCHAR(19) NOT NULL,
    issuance_fence VARCHAR(19) NOT NULL,
    linearization VARCHAR(20) NOT NULL,
    canonical_sha256 VARCHAR(64) NOT NULL,
    canonical_bundle_bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_gameplay_auth_evidence_schema_check
        CHECK (bundle_schema = 'account-auth-evidence-bundle/v1'),
    CONSTRAINT account_gameplay_auth_evidence_positive_values_check
        CHECK (bundle_version ~ '^[1-9][0-9]{0,18}$'
            AND bundle_version::numeric <= 9223372036854775807
            AND source_version ~ '^[1-9][0-9]{0,19}$'
            AND source_version::numeric <= 18446744073709551615
            AND source_fence ~ '^[1-9][0-9]{0,18}$'
            AND source_fence::numeric <= 9223372036854775807
            AND issuance_fence ~ '^[1-9][0-9]{0,18}$'
            AND issuance_fence::numeric <= 9223372036854775807
            AND linearization ~ '^[1-9][0-9]{0,19}$'
            AND linearization::numeric <= 18446744073709551615),
    CONSTRAINT account_gameplay_auth_evidence_source_fence_unique UNIQUE (source_fence),
    CONSTRAINT account_gameplay_auth_evidence_digest_check
        CHECK (canonical_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_gameplay_auth_evidence_size_check
        CHECK (octet_length(canonical_bundle_bytes) BETWEEN 1 AND 8192),
    CONSTRAINT account_gameplay_auth_evidence_json_check
        CHECK (jsonb_typeof(convert_from(canonical_bundle_bytes, 'UTF8')::jsonb) = 'object'),
    CONSTRAINT account_gameplay_auth_evidence_operation_fk
        FOREIGN KEY (operation_id)
        REFERENCES account_gameplay_delegation_issuance_operations(operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_gameplay_auth_evidence_request_fk
        FOREIGN KEY (request_id)
        REFERENCES account_gameplay_delegation_issuance_operations(request_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_gameplay_auth_evidence_account_fk
        FOREIGN KEY (account_uuid)
        REFERENCES accounts(account_uuid)
        ON DELETE RESTRICT
);

-- SQL binds the snapshot to the exact immutable pending operation, current owner rows, Account
-- identity, and existing positive outbox event identities. A zero checkpoint is only a closed
-- encoding for an owner-proved initialized empty stream; this trigger can check the stored shape,
-- but cannot prove its exhaustive-history provenance. The Account repository additionally has to
-- validate event payload contracts and cutoff applicability before it can insert. A
-- sequence/high-water mark alone is never accepted as source evidence.
-- [jooq ignore start]
CREATE FUNCTION account_gameplay_auth_evidence_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    operation_row RECORD;
    account_row RECORD;
    bundle JSONB;
    checkpoint JSONB;
    expected_account_stream TEXT;
    expected_issuer_stream TEXT := 'account:auth-authority:v1:issuer/firemud-account-service';
    capture_transaction_id TEXT;
    allocated_source_fence BIGINT;
    allocated_operation_id UUID;
    allocated_transaction_id TEXT;
    checkpoint_count INTEGER;
    matched_account_checkpoint BOOLEAN := FALSE;
    matched_issuer_checkpoint BOOLEAN := FALSE;
    checkpoint_sequence BIGINT;
    stream_sequence BIGINT;
    current_issuer_generation BIGINT;
    current_issuer_source_version BIGINT;
    current_account_generation BIGINT;
    current_account_source_version BIGINT;
    current_issuance_fence BIGINT;
    current_fence_source_version BIGINT;
    event_row RECORD;
BEGIN
    bundle := convert_from(NEW.canonical_bundle_bytes, 'UTF8')::jsonb;
    capture_transaction_id := pg_current_xact_id()::text;

    SELECT last_source_fence, last_operation_id, last_transaction_id
      INTO allocated_source_fence, allocated_operation_id, allocated_transaction_id
      FROM account_gameplay_auth_evidence_source_fence
      WHERE allocator_id = TRUE
      FOR UPDATE;
    IF allocated_source_fence IS NULL
        OR allocated_source_fence <= 0
        OR NEW.source_fence IS DISTINCT FROM allocated_source_fence::text
        OR allocated_operation_id IS DISTINCT FROM NEW.operation_id
        OR allocated_transaction_id IS DISTINCT FROM capture_transaction_id
        OR NEW.source_version IS DISTINCT FROM capture_transaction_id
        OR NEW.linearization IS DISTINCT FROM capture_transaction_id THEN
        RAISE EXCEPTION 'Account auth-evidence bundle reference is not the exact persisted capture allocation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_capture_reference';
    END IF;

    -- Match the Account owner lock order used by source snapshots and issuance: Account row,
    -- issuer/account generations, issuance fence, then the operation row. The Account identity is
    -- read-only here, so FOR SHARE also avoids upgrading the source reader's existing lock.
    SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id
      INTO account_row
      FROM accounts
      WHERE account_uuid = NEW.account_uuid
      FOR SHARE;
    IF account_row.id IS NULL
        OR account_row.id <> account_row.account_uuid_source_numeric_id
        OR account_row.account_uuid_provenance NOT IN (
            'ACCOUNT_V29_MIGRATION', 'ACCOUNT_REPOSITORY_INSERT', 'ACCOUNT_DATABASE_INSERT')
        OR bundle#>>'{accountIdentitySource,sourceRowId}' IS DISTINCT FROM account_row.id::text
        OR bundle#>>'{accountIdentitySource,sourceNumericId}' IS DISTINCT FROM account_row.account_uuid_source_numeric_id::text
        OR bundle#>>'{accountIdentitySource,provenance}' IS DISTINCT FROM account_row.account_uuid_provenance THEN
        RAISE EXCEPTION 'Account auth-evidence bundle does not match the Account identity row'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_identity_match';
    END IF;

    SELECT generation, source_version
      INTO current_issuer_generation, current_issuer_source_version
      FROM account_authority_generations
      WHERE scope_kind = 'ISSUER' AND issuer_id = 'firemud-account-service'
        AND account_uuid IS NULL AND tenant_uuid IS NULL
      FOR UPDATE;
    SELECT generation, source_version
      INTO current_account_generation, current_account_source_version
      FROM account_authority_generations
      WHERE scope_kind = 'ACCOUNT' AND account_uuid = NEW.account_uuid
        AND issuer_id IS NULL AND tenant_uuid IS NULL
      FOR UPDATE;
    SELECT issuance_fence, source_version
      INTO current_issuance_fence, current_fence_source_version
      FROM account_authority_issuance_fences
      WHERE account_uuid = NEW.account_uuid
      FOR UPDATE;

    SELECT * INTO operation_row
      FROM account_gameplay_delegation_issuance_operations
      WHERE operation_id = NEW.operation_id
      FOR UPDATE;
    IF operation_row.operation_id IS NULL
        OR operation_row.request_id <> NEW.request_id
        OR operation_row.account_uuid <> NEW.account_uuid
        OR operation_row.status <> 'PENDING'
        OR bundle->>'schema' IS DISTINCT FROM NEW.bundle_schema
        OR bundle->>'profile' IS DISTINCT FROM 'game-session-account-delegation'
        OR bundle->>'issuer' IS DISTINCT FROM 'firemud-account-service'
        OR bundle->>'audience' IS DISTINCT FROM 'account-service'
        OR bundle#>>'{bundleRef,bundleVersion}' IS DISTINCT FROM NEW.bundle_version
        OR bundle#>>'{bundleRef,sourceVersion}' IS DISTINCT FROM NEW.source_version
        OR bundle#>>'{bundleRef,sourceFence}' IS DISTINCT FROM NEW.source_fence
        OR bundle#>>'{bundleRef,linearization}' IS DISTINCT FROM NEW.linearization
        OR bundle#>>'{scope,kind}' IS DISTINCT FROM 'account'
        OR bundle#>>'{scope,accountId}' IS DISTINCT FROM NEW.account_uuid::text
        OR jsonb_typeof(bundle#>'{scope,tenantIds}') IS DISTINCT FROM 'array'
        OR COALESCE(jsonb_array_length(bundle#>'{scope,tenantIds}'), -1) <> 0
        OR bundle#>>'{operation,operationId}' IS DISTINCT FROM NEW.operation_id::text
        OR bundle#>>'{operation,requestId}' IS DISTINCT FROM NEW.request_id::text
        OR bundle#>>'{operation,requestDigest}' IS DISTINCT FROM operation_row.request_digest
        OR bundle#>>'{operation,callerWorkload}' IS DISTINCT FROM operation_row.caller_workload
        OR bundle#>>'{operation,callerContextId}' IS DISTINCT FROM operation_row.caller_context_id::text
        OR bundle#>>'{operation,accountId}' IS DISTINCT FROM NEW.account_uuid::text
        OR bundle#>>'{tokenIdentity,jti}' IS DISTINCT FROM operation_row.token_jti::text
        OR bundle#>>'{tokenIdentity,tokenGeneration}' IS DISTINCT FROM operation_row.token_generation::text
        OR bundle#>>'{tokenIdentity,iat}' IS DISTINCT FROM operation_row.issued_at_epoch_second::text
        OR bundle#>>'{tokenIdentity,nbf}' IS DISTINCT FROM operation_row.not_before_epoch_second::text
        OR bundle#>>'{tokenIdentity,exp}' IS DISTINCT FROM operation_row.expires_at_epoch_second::text
        OR bundle->'authorityTuple' IS DISTINCT FROM
            convert_from(operation_row.authority_tuple_canonical_bytes, 'UTF8')::jsonb
        OR bundle->'membershipVersion' IS DISTINCT FROM
            convert_from(operation_row.membership_version_canonical_bytes, 'UTF8')::jsonb
        OR bundle->>'issuanceFence' IS DISTINCT FROM operation_row.issuance_fence::text
        OR bundle->'authoritySourceVersions' IS DISTINCT FROM
            jsonb_build_object(
                'issuerSourceVersion', operation_row.authority_issuer_source_version::text,
                'accountSourceVersion', operation_row.authority_account_source_version::text,
                'issuanceFenceSourceVersion', operation_row.issuance_fence_source_version::text)
        OR NEW.issuance_fence <> operation_row.issuance_fence::text THEN
        RAISE EXCEPTION 'Account auth-evidence bundle does not match the exact pending operation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_operation_match';
    END IF;

    IF current_issuer_generation IS NULL
        OR current_account_generation IS NULL
        OR current_issuance_fence IS NULL
        OR operation_row.authority_issuer_generation <> current_issuer_generation
        OR operation_row.authority_issuer_source_version <> current_issuer_source_version
        OR operation_row.authority_account_generation <> current_account_generation
        OR operation_row.authority_account_source_version <> current_account_source_version
        OR operation_row.issuance_fence <> current_issuance_fence
        OR operation_row.issuance_fence_source_version <> current_fence_source_version THEN
        RAISE EXCEPTION 'Account auth-evidence bundle is stale against current authority'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_current_authority';
    END IF;

    expected_account_stream := 'account:auth-authority:v1:account/' || NEW.account_uuid::text;
    IF jsonb_typeof(bundle->'outboxCheckpoints') <> 'array'
        OR jsonb_array_length(bundle->'outboxCheckpoints') <> 2 THEN
        RAISE EXCEPTION 'Account auth-evidence bundle requires exact issuer and Account checkpoints'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_checkpoints';
    END IF;
    SELECT count(*) INTO checkpoint_count FROM jsonb_array_elements(bundle->'outboxCheckpoints');
    IF checkpoint_count <> 2 THEN
        RAISE EXCEPTION 'Account auth-evidence bundle checkpoints are ambiguous'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_checkpoints';
    END IF;
    FOR checkpoint IN SELECT value FROM jsonb_array_elements(bundle->'outboxCheckpoints') LOOP
        IF checkpoint->>'outboxStreamKey' = expected_account_stream THEN
            matched_account_checkpoint := TRUE;
        ELSIF checkpoint->>'outboxStreamKey' = expected_issuer_stream THEN
            matched_issuer_checkpoint := TRUE;
        ELSE
            RAISE EXCEPTION 'Account auth-evidence bundle has an out-of-scope checkpoint'
                USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_checkpoints';
        END IF;
        IF COALESCE(checkpoint->>'outboxSequence', '') !~ '^(0|[1-9][0-9]{0,18})$' THEN
            RAISE EXCEPTION 'Account auth-evidence checkpoint sequence is malformed'
                USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_checkpoints';
        END IF;
        checkpoint_sequence := (checkpoint->>'outboxSequence')::BIGINT;
        SELECT last_sequence INTO stream_sequence
          FROM account_authority_outbox_streams
          WHERE outbox_stream_key = checkpoint->>'outboxStreamKey'
          FOR SHARE;
        IF checkpoint_sequence = 0 THEN
            IF jsonb_object_length(checkpoint) <> 2
                OR checkpoint ? 'sourceEventId'
                OR checkpoint ? 'sourceEventDigest'
                OR stream_sequence IS DISTINCT FROM 0
                OR EXISTS (
                    SELECT 1 FROM account_authority_outbox_events
                    WHERE outbox_stream_key = checkpoint->>'outboxStreamKey') THEN
                RAISE EXCEPTION 'Account zero checkpoint is not an exact initialized empty stream'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_checkpoints';
            END IF;
        ELSE
            IF jsonb_object_length(checkpoint) <> 4
                OR checkpoint->>'sourceEventId' IS NULL
                OR checkpoint->>'sourceEventDigest' IS NULL THEN
                RAISE EXCEPTION 'Account positive checkpoint is missing exact event identity'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_checkpoints';
            END IF;
            SELECT event_id, event_digest INTO event_row
              FROM account_authority_outbox_events
              WHERE outbox_stream_key = checkpoint->>'outboxStreamKey'
                AND outbox_sequence = checkpoint_sequence;
            IF stream_sequence IS DISTINCT FROM checkpoint_sequence
                OR event_row.event_id IS NULL
                OR checkpoint->>'sourceEventId' <> event_row.event_id
                OR checkpoint->>'sourceEventDigest' <> event_row.event_digest THEN
                RAISE EXCEPTION 'Account auth-evidence checkpoint does not match a current stored event'
                    USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_checkpoints';
            END IF;
        END IF;
    END LOOP;
    IF NOT matched_account_checkpoint OR NOT matched_issuer_checkpoint THEN
        RAISE EXCEPTION 'Account auth-evidence bundle checkpoints are incomplete'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_checkpoints';
    END IF;
    IF bundle#>'{authorityTuple,accountSecurityCutoff}' IS NOT NULL THEN
        IF bundle#>>'{authorityTuple,accountSecurityCutoff,outboxStreamKey}' IS DISTINCT FROM expected_account_stream
            OR bundle#>>'{authorityTuple,accountSecurityCutoff,accountAuthorityGeneration}' IS DISTINCT FROM
                operation_row.authority_account_generation::text
            OR COALESCE(bundle#>>'{authorityTuple,accountSecurityCutoff,outboxSequence}', '') !~ '^[1-9][0-9]{0,18}$' THEN
            RAISE EXCEPTION 'Account auth-evidence security cutoff is malformed or out of scope'
                USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_cutoff';
        END IF;
        IF (bundle#>>'{authorityTuple,accountSecurityCutoff,outboxSequence}')::BIGINT > (
            SELECT (item->>'outboxSequence')::BIGINT
              FROM jsonb_array_elements(bundle->'outboxCheckpoints') AS item
              WHERE item->>'outboxStreamKey' = expected_account_stream) THEN
            RAISE EXCEPTION 'Account auth-evidence checkpoint does not cover its cutoff'
                USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_cutoff';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_gameplay_auth_evidence_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account auth-evidence bundle is immutable'
        USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_immutable';
    RETURN OLD;
END;
$$;

CREATE FUNCTION account_gameplay_auth_evidence_source_fence_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.allocator_id IS DISTINCT FROM OLD.allocator_id
        OR NEW.last_source_fence <> OLD.last_source_fence + 1
        OR NEW.last_operation_id IS NULL
        OR NEW.last_transaction_id IS DISTINCT FROM pg_current_xact_id()::text THEN
        RAISE EXCEPTION 'Account auth-evidence source fence must advance exactly once in its capture transaction'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_source_fence_monotonic';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_gameplay_auth_evidence_source_fence_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account auth-evidence source fence allocator cannot be inserted, deleted, or truncated'
        USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_source_fence_immutable';
    RETURN OLD;
END;
$$;

CREATE FUNCTION account_gameplay_auth_evidence_no_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM account_gameplay_delegation_auth_evidence_bundles) THEN
        RAISE EXCEPTION 'Account auth-evidence bundles cannot be truncated'
            USING ERRCODE = '23514', CONSTRAINT = 'account_gameplay_auth_evidence_immutable';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_gameplay_auth_evidence_insert
    BEFORE INSERT ON account_gameplay_delegation_auth_evidence_bundles
    FOR EACH ROW EXECUTE FUNCTION account_gameplay_auth_evidence_insert_guard();
CREATE TRIGGER account_gameplay_auth_evidence_update_delete
    BEFORE UPDATE OR DELETE ON account_gameplay_delegation_auth_evidence_bundles
    FOR EACH ROW EXECUTE FUNCTION account_gameplay_auth_evidence_immutable_guard();
CREATE TRIGGER account_gameplay_auth_evidence_truncate
    BEFORE TRUNCATE ON account_gameplay_delegation_auth_evidence_bundles
    FOR EACH STATEMENT EXECUTE FUNCTION account_gameplay_auth_evidence_no_truncate();
CREATE TRIGGER account_gameplay_auth_evidence_source_fence_update
    BEFORE UPDATE ON account_gameplay_auth_evidence_source_fence
    FOR EACH ROW EXECUTE FUNCTION account_gameplay_auth_evidence_source_fence_update_guard();
CREATE TRIGGER account_gameplay_auth_evidence_source_fence_insert_delete
    BEFORE INSERT OR DELETE ON account_gameplay_auth_evidence_source_fence
    FOR EACH ROW EXECUTE FUNCTION account_gameplay_auth_evidence_source_fence_immutable_guard();
CREATE TRIGGER account_gameplay_auth_evidence_source_fence_truncate
    BEFORE TRUNCATE ON account_gameplay_auth_evidence_source_fence
    FOR EACH STATEMENT EXECUTE FUNCTION account_gameplay_auth_evidence_source_fence_immutable_guard();
-- [jooq ignore stop]
