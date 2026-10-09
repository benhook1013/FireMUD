-- Immutable Account pending-intent and World terminal-receipt correlation only.
-- No logical Account state/source/generation/fence/outbox mutation, serving authorization producer,
-- expiry, or retained-row backfill. BEFORE INSERT versions the unchanged Account row to fence
-- older REPEATABLE_READ and SERIALIZABLE snapshots.
CREATE TABLE account_lifecycle_serving_operations (
    request_id UUID PRIMARY KEY
        CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    actor_account_uuid UUID NOT NULL
        CHECK (actor_account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID)
        REFERENCES accounts (account_uuid) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL,
    account_id BIGINT NOT NULL CHECK (account_id > 0),
    account_provenance VARCHAR(40) NOT NULL
        CHECK (account_provenance IN (
            'ACCOUNT_V29_MIGRATION', 'ACCOUNT_REPOSITORY_INSERT', 'ACCOUNT_DATABASE_INSERT')),
    tenant_uuid UUID NOT NULL
        CHECK (tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID),
    purpose VARCHAR(48) NOT NULL CHECK (purpose = 'WORLD_ACTIVATION_INVALIDATION'),
    caller_proof_binding BYTEA NOT NULL
        CHECK (octet_length(caller_proof_binding) BETWEEN 1 AND 16384),
    world_activation_request_id UUID NOT NULL
        CHECK (world_activation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    world_activation_request_digest VARCHAR(71) NOT NULL
        CHECK (world_activation_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    world_activation_request_bytes BYTEA NOT NULL
        CHECK (octet_length(world_activation_request_bytes) BETWEEN 1 AND 1048576),
    world_activation_preparing_evidence BYTEA NOT NULL
        CHECK (octet_length(world_activation_preparing_evidence) BETWEEN 1 AND 1048576),
    request_payload BYTEA NOT NULL
        CHECK (octet_length(request_payload) BETWEEN 1 AND 2200000),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    account_stream_key VARCHAR(2048) NOT NULL,
    account_generation BIGINT NOT NULL CHECK (account_generation > 0),
    account_source_version BIGINT NOT NULL CHECK (account_source_version > 0),
    account_issuance_fence BIGINT NOT NULL CHECK (account_issuance_fence > 0),
    account_fence_source_version BIGINT NOT NULL CHECK (account_fence_source_version > 0),
    checkpoint_sequence BIGINT NOT NULL CHECK (checkpoint_sequence >= 0),
    checkpoint_event_sequence BIGINT CHECK (checkpoint_event_sequence > 0),
    checkpoint_event_request_id VARCHAR(512),
    checkpoint_event_id VARCHAR(512),
    checkpoint_event_digest VARCHAR(512),
    checkpoint_payload BYTEA NOT NULL CHECK (octet_length(checkpoint_payload) <= 1048576),
    capture_payload BYTEA NOT NULL
        CHECK (octet_length(capture_payload) BETWEEN 1 AND 1100000),
    capture_digest VARCHAR(71) NOT NULL CHECK (capture_digest ~ '^sha256:[0-9a-f]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'WORLD_TERMINAL')),
    world_result_outcome VARCHAR(16),
    world_result_payload BYTEA,
    world_result_digest VARCHAR(71),
    world_terminal_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_lifecycle_serving_account_identity_fk
        FOREIGN KEY (account_uuid, account_id, account_provenance)
        REFERENCES accounts (account_uuid, account_uuid_source_numeric_id, account_uuid_provenance)
        ON DELETE RESTRICT,
    CONSTRAINT account_lifecycle_serving_stream_fk
        FOREIGN KEY (account_stream_key)
        REFERENCES account_authority_source_records (outbox_stream_key) ON DELETE RESTRICT,
    CONSTRAINT account_lifecycle_serving_checkpoint_event_fk
        FOREIGN KEY (account_stream_key, checkpoint_event_sequence)
        REFERENCES account_authority_outbox_events (outbox_stream_key, outbox_sequence)
        ON DELETE RESTRICT,
    CONSTRAINT account_lifecycle_serving_checkpoint_shape CHECK (
        account_stream_key = 'account:auth-authority:v1:account/' || account_uuid::TEXT
        AND account_generation = account_source_version
        AND account_issuance_fence = account_generation
        AND account_fence_source_version = account_source_version
        AND account_generation = checkpoint_sequence + 1
        AND account_source_version = checkpoint_sequence + 1
        AND account_issuance_fence = checkpoint_sequence + 1
        AND account_fence_source_version = checkpoint_sequence + 1
        AND (
            (checkpoint_sequence = 0
                AND checkpoint_event_sequence IS NULL
                AND checkpoint_event_request_id IS NULL
                AND checkpoint_event_id IS NULL
                AND checkpoint_event_digest IS NULL
                AND octet_length(checkpoint_payload) = 0)
            OR
            (checkpoint_sequence > 0
                AND checkpoint_event_sequence = checkpoint_sequence
                AND checkpoint_event_request_id IS NOT NULL
                AND length(btrim(checkpoint_event_request_id)) > 0
                AND checkpoint_event_id IS NOT NULL
                AND length(btrim(checkpoint_event_id)) > 0
                AND checkpoint_event_digest IS NOT NULL
                AND checkpoint_event_digest ~ '^sha256:[0-9a-f]{64}$'
                AND octet_length(checkpoint_payload) BETWEEN 1 AND 1048576)
        )),
    CONSTRAINT account_lifecycle_serving_result_shape CHECK (
        (status = 'PENDING'
            AND world_result_outcome IS NULL
            AND world_result_payload IS NULL
            AND world_result_digest IS NULL
            AND world_terminal_at IS NULL)
        OR
        (status = 'WORLD_TERMINAL'
            AND world_result_outcome IN ('COMMITTED', 'ABORTED')
            AND world_result_payload IS NOT NULL
            AND octet_length(world_result_payload) BETWEEN 1 AND 2200000
            AND world_result_digest IS NOT NULL
            AND world_result_digest ~ '^sha256:[0-9a-f]{64}$'
            AND world_terminal_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX account_lifecycle_serving_one_pending_scope_uq
    ON account_lifecycle_serving_operations (account_uuid, tenant_uuid)
    WHERE status IN ('PENDING', 'WORLD_TERMINAL');

-- [jooq ignore start]
CREATE FUNCTION account_lifecycle_serving_account_row_version_fence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    touched_rows INTEGER;
BEGIN
    UPDATE accounts
        SET id = id
        WHERE account_uuid = NEW.account_uuid
          AND id = NEW.account_id
          AND account_uuid_provenance = NEW.account_provenance
          AND account_uuid_source_numeric_id = NEW.account_id;
    GET DIAGNOSTICS touched_rows = ROW_COUNT;
    IF touched_rows <> 1 THEN
        RAISE EXCEPTION 'Account lifecycle operation requires the exact persisted Account row'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_lifecycle_serving_account_row_version_fence';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_lifecycle_serving_account_row_version_fence
    BEFORE INSERT ON account_lifecycle_serving_operations
    FOR EACH ROW EXECUTE FUNCTION account_lifecycle_serving_account_row_version_fence();

CREATE FUNCTION account_lifecycle_serving_operation_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR NEW.status <> 'WORLD_TERMINAL'
        OR (to_jsonb(NEW) - ARRAY['status', 'world_result_outcome', 'world_result_payload',
            'world_result_digest', 'world_terminal_at'])
            IS DISTINCT FROM
            (to_jsonb(OLD) - ARRAY['status', 'world_result_outcome', 'world_result_payload',
                'world_result_digest', 'world_terminal_at']) THEN
        RAISE EXCEPTION 'Account lifecycle request and capture are immutable; only one terminal World receipt may be added'
            USING ERRCODE = '23514', CONSTRAINT = 'account_lifecycle_serving_operation_immutable';
    END IF;
    NEW.world_terminal_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_lifecycle_serving_operation_update
    BEFORE UPDATE ON account_lifecycle_serving_operations
    FOR EACH ROW EXECUTE FUNCTION account_lifecycle_serving_operation_update_guard();

CREATE FUNCTION account_lifecycle_serving_operation_delete_denied()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account lifecycle pending operations are retained'
        USING ERRCODE = '23514', CONSTRAINT = 'account_lifecycle_serving_operation_no_delete';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_lifecycle_serving_operation_delete
    BEFORE DELETE ON account_lifecycle_serving_operations
    FOR EACH ROW EXECUTE FUNCTION account_lifecycle_serving_operation_delete_denied();

CREATE FUNCTION account_lifecycle_serving_operation_truncate_denied()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account lifecycle pending-operation history cannot be truncated'
        USING ERRCODE = '23514', CONSTRAINT = 'account_lifecycle_serving_operation_no_truncate';
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_lifecycle_serving_operation_truncate
    BEFORE TRUNCATE ON account_lifecycle_serving_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_lifecycle_serving_operation_truncate_denied();
-- [jooq ignore stop]
