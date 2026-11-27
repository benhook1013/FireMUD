CREATE TABLE account_tenant_role_operations (
    request_id UUID PRIMARY KEY,
    schema_version SMALLINT NOT NULL CHECK (schema_version = 1),
    actor_account_uuid UUID NOT NULL REFERENCES accounts (account_uuid),
    tenant_uuid UUID NOT NULL,
    target_account_uuid UUID NOT NULL REFERENCES accounts (account_uuid),
    action VARCHAR(32) NOT NULL CHECK (
        action IN ('GRANT_DESIGNER', 'REVOKE_DESIGNER', 'TRANSFER_TENANT_ADMIN')),
    expected_actor_membership_version BIGINT NOT NULL CHECK (expected_actor_membership_version > 0),
    expected_target_membership_version BIGINT NOT NULL CHECK (expected_target_membership_version > 0),
    request_payload BYTEA NOT NULL,
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMMITTED')),
    audit_event_id UUID,
    audit_event_type VARCHAR(80),
    audit_occurred_at TIMESTAMPTZ,
    audit_payload_digest VARCHAR(71),
    audit_payload BYTEA,
    result_payload BYTEA,
    result_digest VARCHAR(71),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    CONSTRAINT account_tenant_role_transfer_members_are_distinct CHECK (
        action <> 'TRANSFER_TENANT_ADMIN' OR actor_account_uuid <> target_account_uuid),
    CONSTRAINT account_tenant_role_operation_completion_shape CHECK (
        (status = 'IN_PROGRESS' AND audit_event_id IS NULL AND audit_event_type IS NULL
            AND audit_occurred_at IS NULL AND audit_payload_digest IS NULL
            AND audit_payload IS NULL AND result_payload IS NULL AND result_digest IS NULL
            AND committed_at IS NULL)
        OR (status = 'COMMITTED' AND audit_event_id IS NOT NULL
            AND audit_event_type IS NOT NULL
            AND audit_event_type = 'ACCOUNT_TENANT_ROLE_CHANGED'
            AND audit_occurred_at IS NOT NULL
            AND audit_payload_digest IS NOT NULL
            AND audit_payload_digest ~ '^sha256:[0-9a-f]{64}$'
            AND audit_payload IS NOT NULL AND result_payload IS NOT NULL
            AND result_digest IS NOT NULL
            AND result_digest ~ '^sha256:[0-9a-f]{64}$' AND committed_at IS NOT NULL))
);

CREATE TABLE account_tenant_role_operation_members (
    request_id UUID NOT NULL REFERENCES account_tenant_role_operations (request_id) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL REFERENCES accounts (account_uuid),
    tenant_uuid UUID NOT NULL,
    membership_version BIGINT NOT NULL CHECK (membership_version > 0),
    membership_authority_generation BIGINT NOT NULL CHECK (membership_authority_generation > 0),
    outbox_stream_key VARCHAR(256) NOT NULL,
    event_request_id VARCHAR(128) NOT NULL,
    event_sequence BIGINT NOT NULL CHECK (event_sequence > 0),
    event_id VARCHAR(512) NOT NULL CHECK (length(btrim(event_id)) > 0),
    event_digest VARCHAR(71) NOT NULL CHECK (event_digest ~ '^sha256:[0-9a-f]{64}$'),
    caller_bound_authority_invalidated BOOLEAN NOT NULL,
    event_payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_tenant_role_operation_members_pk PRIMARY KEY (request_id, account_uuid),
    CONSTRAINT account_tenant_role_operation_members_event_uq
        UNIQUE (outbox_stream_key, event_request_id)
);

CREATE INDEX account_tenant_role_operation_members_current_event_idx
    ON account_tenant_role_operation_members (account_uuid, tenant_uuid, event_request_id);

-- [jooq ignore start]
CREATE FUNCTION account_tenant_role_operation_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.actor_account_uuid IS DISTINCT FROM OLD.actor_account_uuid
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.target_account_uuid IS DISTINCT FROM OLD.target_account_uuid
        OR NEW.action IS DISTINCT FROM OLD.action
        OR NEW.expected_actor_membership_version IS DISTINCT FROM OLD.expected_actor_membership_version
        OR NEW.expected_target_membership_version IS DISTINCT FROM OLD.expected_target_membership_version
        OR NEW.request_payload IS DISTINCT FROM OLD.request_payload
        OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR OLD.status <> 'IN_PROGRESS'
        OR NEW.status <> 'COMMITTED' THEN
        RAISE EXCEPTION 'Account tenant-role operation request is immutable'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_tenant_role_operation_identity_immutable';
    END IF;
    NEW.committed_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_role_operation_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'IN_PROGRESS'
        OR NEW.audit_event_id IS NOT NULL
        OR NEW.audit_event_type IS NOT NULL
        OR NEW.audit_occurred_at IS NOT NULL
        OR NEW.audit_payload_digest IS NOT NULL
        OR NEW.audit_payload IS NOT NULL
        OR NEW.result_payload IS NOT NULL
        OR NEW.result_digest IS NOT NULL
        OR NEW.committed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Account tenant-role operation must begin with an empty immutable result'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_tenant_role_operation_initial_state';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_role_operation_no_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account tenant-role operation history cannot be deleted'
        USING ERRCODE = '23514',
            CONSTRAINT = 'account_tenant_role_operation_no_delete';
    RETURN OLD;
END;
$$;

CREATE FUNCTION account_tenant_role_operation_member_no_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account tenant-role member evidence is immutable'
        USING ERRCODE = '23514',
            CONSTRAINT = 'account_tenant_role_operation_member_immutable';
    RETURN OLD;
END;
$$;

CREATE FUNCTION account_tenant_role_operation_complete_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_operation account_tenant_role_operations%ROWTYPE;
    member_count BIGINT;
BEGIN
    SELECT * INTO current_operation
    FROM account_tenant_role_operations
    WHERE request_id = NEW.request_id;
    IF current_operation.status <> 'COMMITTED' THEN
        RAISE EXCEPTION 'Incomplete Account tenant-role operation cannot commit'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_tenant_role_operation_must_complete';
    END IF;

    SELECT count(*) INTO member_count
    FROM account_tenant_role_operation_members
    WHERE request_id = NEW.request_id;
    IF (current_operation.action = 'TRANSFER_TENANT_ADMIN' AND member_count <> 2)
        OR (current_operation.action <> 'TRANSFER_TENANT_ADMIN' AND member_count <> 1) THEN
        RAISE EXCEPTION 'Account tenant-role operation has an incomplete member result'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_tenant_role_operation_member_result_count';
    END IF;

    IF current_operation.action = 'TRANSFER_TENANT_ADMIN'
        AND (NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id
                AND account_uuid = current_operation.actor_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid)
            OR NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id
                AND account_uuid = current_operation.target_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid))
        OR current_operation.action IN ('GRANT_DESIGNER', 'REVOKE_DESIGNER')
        AND NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id
                AND account_uuid = current_operation.target_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid) THEN
        RAISE EXCEPTION 'Account tenant-role operation member identities differ from its request'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_tenant_role_operation_member_identity';
    END IF;

    IF (current_operation.action = 'TRANSFER_TENANT_ADMIN'
        AND NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id
                AND account_uuid = current_operation.actor_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid
                AND caller_bound_authority_invalidated)
        OR current_operation.action = 'TRANSFER_TENANT_ADMIN'
        AND NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id
                AND account_uuid = current_operation.target_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid
                AND NOT caller_bound_authority_invalidated)
        OR current_operation.action IN ('GRANT_DESIGNER', 'REVOKE_DESIGNER')
        AND NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id
                AND account_uuid = current_operation.target_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid
                AND caller_bound_authority_invalidated =
                    (current_operation.action = 'REVOKE_DESIGNER'))) THEN
        RAISE EXCEPTION 'Account tenant-role operation invalidation evidence differs from action'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_tenant_role_operation_invalidation_shape';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_tenant_role_operation_update
    BEFORE UPDATE ON account_tenant_role_operations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_role_operation_update_guard();
CREATE TRIGGER account_tenant_role_operation_insert
    BEFORE INSERT ON account_tenant_role_operations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_role_operation_insert_guard();
CREATE TRIGGER account_tenant_role_operation_delete
    BEFORE DELETE ON account_tenant_role_operations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_role_operation_no_delete();
CREATE TRIGGER account_tenant_role_operation_member_update
    BEFORE UPDATE OR DELETE ON account_tenant_role_operation_members
    FOR EACH ROW EXECUTE FUNCTION account_tenant_role_operation_member_no_mutation();
CREATE CONSTRAINT TRIGGER account_tenant_role_operation_complete
    AFTER INSERT OR UPDATE ON account_tenant_role_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_tenant_role_operation_complete_guard();
CREATE CONSTRAINT TRIGGER account_tenant_role_operation_member_complete
    AFTER INSERT ON account_tenant_role_operation_members
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_tenant_role_operation_complete_guard();
-- [jooq ignore stop]
