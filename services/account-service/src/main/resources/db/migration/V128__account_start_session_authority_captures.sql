CREATE TABLE account_start_session_capture_source_version_allocator (
    allocator_id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (allocator_id),
    last_source_version BIGINT NOT NULL DEFAULT 0 CHECK (last_source_version >= 0),
    last_control_plane_request_id VARCHAR(128),
    last_transaction_id VARCHAR(20),
    CHECK ((last_source_version = 0 AND last_control_plane_request_id IS NULL AND last_transaction_id IS NULL)
        OR (last_source_version > 0 AND last_control_plane_request_id IS NOT NULL
            AND last_transaction_id IS NOT NULL
            AND last_transaction_id ~ '^[1-9][0-9]{0,19}$'
            AND last_transaction_id::NUMERIC <= 18446744073709551615))
);
INSERT INTO account_start_session_capture_source_version_allocator (allocator_id) VALUES (TRUE);

CREATE TABLE account_start_session_capture_source_fence_allocator (
    allocator_id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (allocator_id),
    last_source_fence BIGINT NOT NULL DEFAULT 0 CHECK (last_source_fence >= 0),
    last_control_plane_request_id VARCHAR(128),
    last_transaction_id VARCHAR(20),
    CHECK ((last_source_fence = 0 AND last_control_plane_request_id IS NULL AND last_transaction_id IS NULL)
        OR (last_source_fence > 0 AND last_control_plane_request_id IS NOT NULL
            AND last_transaction_id IS NOT NULL
            AND last_transaction_id ~ '^[1-9][0-9]{0,19}$'
            AND last_transaction_id::NUMERIC <= 18446744073709551615))
);
INSERT INTO account_start_session_capture_source_fence_allocator (allocator_id) VALUES (TRUE);

CREATE TABLE account_start_session_authority_captures (
    control_plane_request_id VARCHAR(128) PRIMARY KEY
        CHECK (octet_length(control_plane_request_id) BETWEEN 1 AND 128),
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    tenant_uuid UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id)
        ON DELETE RESTRICT,
    target_owner VARCHAR(64) NOT NULL CHECK (target_owner = 'game-session-service'),
    logging_workload_uri VARCHAR(256) NOT NULL
        CHECK (logging_workload_uri ~ '^spiffe://firemud/ns/[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?/sa/logging-admin-service$'),
    reservation_owner_id UUID NOT NULL CHECK (reservation_owner_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    reservation_claim_fence BIGINT NOT NULL CHECK (reservation_claim_fence > 0),
    pre_authorization_tuple BYTEA NOT NULL CHECK (octet_length(pre_authorization_tuple) BETWEEN 1 AND 8192),
    mutation_digest VARCHAR(64) NOT NULL CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
    control_ui_operation_id UUID NOT NULL
        REFERENCES account_control_ui_issuance_operations(operation_id) ON DELETE RESTRICT,
    control_ui_token_jti UUID NOT NULL CHECK (control_ui_token_jti <> '00000000-0000-0000-0000-000000000000'::UUID),
    control_ui_token_hash VARCHAR(64) NOT NULL CHECK (control_ui_token_hash ~ '^[0-9a-f]{64}$'),
    control_ui_signer_receipt_sha256 VARCHAR(64) NOT NULL
        CHECK (control_ui_signer_receipt_sha256 ~ '^[0-9a-f]{64}$'),
    issuance_fence BIGINT NOT NULL CHECK (issuance_fence > 0),
    issuance_fence_source_version BIGINT NOT NULL CHECK (issuance_fence_source_version > 0),
    captured_at TIMESTAMPTZ NOT NULL,
    snapshot_sha256 VARCHAR(64) NOT NULL CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    canonical_snapshot_bytes BYTEA NOT NULL
        CHECK (octet_length(canonical_snapshot_bytes) BETWEEN 1 AND 245760),
    source_version BIGINT NOT NULL CHECK (source_version > 0),
    source_fence BIGINT NOT NULL CHECK (source_fence > 0),
    linearization VARCHAR(20) NOT NULL
        CHECK (linearization ~ '^[1-9][0-9]{0,19}$'
            AND linearization::NUMERIC <= 18446744073709551615),
    canonical_sha256 VARCHAR(64) NOT NULL CHECK (canonical_sha256 ~ '^[0-9a-f]{64}$'),
    canonical_capture_bytes BYTEA NOT NULL
        CHECK (octet_length(canonical_capture_bytes) BETWEEN 1 AND 16384),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_start_session_capture_total_size_check
        CHECK (octet_length(canonical_snapshot_bytes) + octet_length(canonical_capture_bytes) <= 262144),
    CONSTRAINT account_start_session_capture_source_version_unique UNIQUE (source_version),
    CONSTRAINT account_start_session_capture_source_fence_unique UNIQUE (source_fence),
    CONSTRAINT account_start_session_capture_snapshot_digest
        CHECK (snapshot_sha256 = encode(sha256(canonical_snapshot_bytes), 'hex')),
    CONSTRAINT account_start_session_capture_reference_digest
        CHECK (canonical_sha256 = encode(sha256(canonical_capture_bytes), 'hex'))
);

-- [jooq ignore start]
ALTER TABLE account_start_session_authority_captures
    ADD CONSTRAINT account_start_session_capture_captured_at_millis
        CHECK (date_trunc('milliseconds', captured_at) = captured_at);
-- [jooq ignore stop]

-- [jooq ignore start]
CREATE FUNCTION account_start_session_authority_capture_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    snapshot JSONB;
    reference JSONB;
    tuple JSONB;
    control_ui RECORD;
    allocated_version BIGINT;
    allocated_version_request VARCHAR(128);
    allocated_version_transaction VARCHAR(20);
    allocated_fence BIGINT;
    allocated_fence_request VARCHAR(128);
    allocated_fence_transaction VARCHAR(20);
    capture_transaction VARCHAR(20);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'StartSession Account source captures are immutable and cannot be deleted'
            USING ERRCODE = '23514';
    END IF;

    capture_transaction := pg_current_xact_id()::TEXT;
    IF NEW.linearization IS DISTINCT FROM capture_transaction THEN
        RAISE EXCEPTION 'StartSession capture linearization must identify its inserting transaction'
            USING ERRCODE = '23514';
    END IF;

    SELECT last_source_version, last_control_plane_request_id, last_transaction_id
      INTO allocated_version, allocated_version_request, allocated_version_transaction
      FROM account_start_session_capture_source_version_allocator
      WHERE allocator_id = TRUE FOR UPDATE;
    SELECT last_source_fence, last_control_plane_request_id, last_transaction_id
      INTO allocated_fence, allocated_fence_request, allocated_fence_transaction
      FROM account_start_session_capture_source_fence_allocator
      WHERE allocator_id = TRUE FOR UPDATE;
    IF allocated_version IS DISTINCT FROM NEW.source_version
        OR allocated_fence IS DISTINCT FROM NEW.source_fence
        OR allocated_version_request IS DISTINCT FROM NEW.control_plane_request_id
        OR allocated_fence_request IS DISTINCT FROM NEW.control_plane_request_id
        OR allocated_version_transaction IS DISTINCT FROM capture_transaction
        OR allocated_fence_transaction IS DISTINCT FROM capture_transaction THEN
        RAISE EXCEPTION 'StartSession capture reference is not the exact durable source allocation'
            USING ERRCODE = '23514';
    END IF;

    snapshot := convert_from(NEW.canonical_snapshot_bytes, 'UTF8')::JSONB;
    reference := convert_from(NEW.canonical_capture_bytes, 'UTF8')::JSONB;
    tuple := convert_from(NEW.pre_authorization_tuple, 'UTF8')::JSONB;
    IF jsonb_typeof(snapshot) IS DISTINCT FROM 'object'
        OR jsonb_typeof(reference) IS DISTINCT FROM 'object'
        OR jsonb_typeof(tuple) IS DISTINCT FROM 'object'
        OR reference->>'schema' IS DISTINCT FROM 'account-start-session-authority-capture/v1'
        OR reference->>'controlPlaneRequestId' IS DISTINCT FROM NEW.control_plane_request_id
        OR reference->>'capturedAt' IS DISTINCT FROM
            to_char(NEW.captured_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
        OR reference->>'snapshotSha256' IS DISTINCT FROM NEW.snapshot_sha256
        OR reference#>>'{bundleReference,bundleVersion}' IS DISTINCT FROM 'authorityEvidenceBundle/v1'
        OR reference#>>'{bundleReference,sourceVersion}' IS DISTINCT FROM NEW.source_version::TEXT
        OR reference#>>'{bundleReference,sourceFence}' IS DISTINCT FROM NEW.source_fence::TEXT
        OR reference#>>'{bundleReference,linearization}' IS DISTINCT FROM NEW.linearization
        OR snapshot->>'schema' IS DISTINCT FROM 'account-start-session-authority-snapshot/v1'
        OR snapshot->>'controlPlaneRequestId' IS DISTINCT FROM NEW.control_plane_request_id
        OR snapshot->>'accountId' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR snapshot->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR snapshot->>'targetOwner' IS DISTINCT FROM NEW.target_owner
        OR snapshot->>'loggingWorkloadUri' IS DISTINCT FROM NEW.logging_workload_uri
        OR snapshot->>'reservationOwnerId' IS DISTINCT FROM NEW.reservation_owner_id::TEXT
        OR snapshot->>'reservationClaimFence' IS DISTINCT FROM NEW.reservation_claim_fence::TEXT
        OR snapshot->>'mutationDigest' IS DISTINCT FROM NEW.mutation_digest
        OR snapshot->>'controlUiOperationId' IS DISTINCT FROM NEW.control_ui_operation_id::TEXT
        OR snapshot->>'controlUiTokenJti' IS DISTINCT FROM NEW.control_ui_token_jti::TEXT
        OR snapshot->>'controlUiTokenHash' IS DISTINCT FROM NEW.control_ui_token_hash
        OR snapshot->>'controlUiSignerReceiptSha256' IS DISTINCT FROM NEW.control_ui_signer_receipt_sha256
        OR snapshot->>'issuanceFence' IS DISTINCT FROM NEW.issuance_fence::TEXT
        OR snapshot->>'issuanceFenceSourceVersion' IS DISTINCT FROM NEW.issuance_fence_source_version::TEXT
        OR decode(snapshot->>'preAuthorizationTuple', 'base64') IS DISTINCT FROM NEW.pre_authorization_tuple THEN
        RAISE EXCEPTION 'StartSession capture payload differs from its exact row identity'
            USING ERRCODE = '23514';
    END IF;

    IF tuple#>>'{actor,accountId}' IS DISTINCT FROM NEW.account_uuid::TEXT
        OR tuple#>>'{scope,tenantId}' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR tuple#>>'{targetOwner,ownerService}' IS DISTINCT FROM 'game-session-service'
        OR tuple#>>'{actionFamilyRequestIdentity,requestId}' IS DISTINCT FROM NEW.control_plane_request_id
        OR tuple->>'tupleSchemaId' IS DISTINCT FROM 'preAuthorizationReservationTuple'
        OR tuple->>'tupleSchemaVersion' IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'StartSession capture tuple scope or identity is inconsistent'
            USING ERRCODE = '23514';
    END IF;

    SELECT operation_id, account_uuid, tenant_uuid, token_jti, token_hash, signer_receipt, status
      INTO control_ui
      FROM account_control_ui_issuance_operations
      WHERE operation_id = NEW.control_ui_operation_id
      FOR SHARE;
    IF control_ui.operation_id IS NULL
        OR control_ui.status IS DISTINCT FROM 'COMMITTED'
        OR control_ui.account_uuid IS DISTINCT FROM NEW.account_uuid
        OR control_ui.tenant_uuid IS DISTINCT FROM NEW.tenant_uuid
        OR control_ui.token_jti IS DISTINCT FROM NEW.control_ui_token_jti
        OR control_ui.token_hash IS DISTINCT FROM NEW.control_ui_token_hash
        OR encode(sha256(control_ui.signer_receipt), 'hex') IS DISTINCT FROM NEW.control_ui_signer_receipt_sha256 THEN
        RAISE EXCEPTION 'StartSession capture is not bound to its original committed ControlUI operation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_start_session_authority_capture_no_truncate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'StartSession Account source captures cannot be truncated'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_start_session_authority_capture_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON account_start_session_authority_captures
    FOR EACH ROW EXECUTE FUNCTION account_start_session_authority_capture_guard();
CREATE TRIGGER account_start_session_authority_capture_no_truncate
    BEFORE TRUNCATE ON account_start_session_authority_captures
    FOR EACH STATEMENT EXECUTE FUNCTION account_start_session_authority_capture_no_truncate();

CREATE FUNCTION account_start_session_capture_allocator_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'StartSession capture allocators cannot be deleted or truncated'
            USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['last_source_version','last_control_plane_request_id','last_transaction_id'])
        IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['last_source_version','last_control_plane_request_id','last_transaction_id'])
        OR NEW.last_source_version <> OLD.last_source_version + 1
        OR NEW.last_control_plane_request_id IS NULL
        OR NEW.last_transaction_id IS DISTINCT FROM pg_current_xact_id()::TEXT THEN
        RAISE EXCEPTION 'StartSession capture sourceVersion allocator must advance once in its owner transaction'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_start_session_capture_fence_allocator_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'StartSession capture allocators cannot be deleted or truncated'
            USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['last_source_fence','last_control_plane_request_id','last_transaction_id'])
        IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['last_source_fence','last_control_plane_request_id','last_transaction_id'])
        OR NEW.last_source_fence <> OLD.last_source_fence + 1
        OR NEW.last_control_plane_request_id IS NULL
        OR NEW.last_transaction_id IS DISTINCT FROM pg_current_xact_id()::TEXT THEN
        RAISE EXCEPTION 'StartSession capture sourceFence allocator must advance once in its owner transaction'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_start_session_capture_source_version_allocator_guard
    BEFORE UPDATE OR DELETE ON account_start_session_capture_source_version_allocator
    FOR EACH ROW EXECUTE FUNCTION account_start_session_capture_allocator_no_mutation();
CREATE TRIGGER account_start_session_capture_source_version_allocator_no_truncate
    BEFORE TRUNCATE ON account_start_session_capture_source_version_allocator
    FOR EACH STATEMENT EXECUTE FUNCTION account_start_session_authority_capture_no_truncate();
CREATE TRIGGER account_start_session_capture_source_fence_allocator_guard
    BEFORE UPDATE OR DELETE ON account_start_session_capture_source_fence_allocator
    FOR EACH ROW EXECUTE FUNCTION account_start_session_capture_fence_allocator_no_mutation();
CREATE TRIGGER account_start_session_capture_source_fence_allocator_no_truncate
    BEFORE TRUNCATE ON account_start_session_capture_source_fence_allocator
    FOR EACH STATEMENT EXECUTE FUNCTION account_start_session_authority_capture_no_truncate();
-- [jooq ignore stop]
