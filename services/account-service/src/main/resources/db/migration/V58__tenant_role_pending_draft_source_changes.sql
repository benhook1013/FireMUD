-- Preserve V56 committed history; only a request bound to an exact V57 WAITING intent may remain
-- pending. No authenticated actor, public route, or hosted-terms producer is enabled here.
ALTER TABLE account_tenant_role_operations ADD COLUMN source_change_binding BYTEA;

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_tenant_role_operation_update_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
        OR OLD.status <> 'IN_PROGRESS' THEN
        RAISE EXCEPTION 'Account tenant-role operation request is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_operation_identity_immutable';
    END IF;

    IF OLD.source_change_binding IS NULL AND NEW.source_change_binding IS NOT NULL
        AND NEW.status = 'IN_PROGRESS' THEN
        IF NOT EXISTS (
            SELECT 1 FROM account_draft_authorization_source_changes
            WHERE change_id = NEW.request_id AND status = 'WAITING'
                AND binding = NEW.source_change_binding)
            OR octet_length(NEW.source_change_binding) < octet_length(NEW.request_payload) + 4
            OR substring(NEW.source_change_binding FROM
                octet_length(NEW.source_change_binding) - octet_length(NEW.request_payload) - 3)
                IS DISTINCT FROM int4send(octet_length(NEW.request_payload)) || NEW.request_payload THEN
            RAISE EXCEPTION 'Tenant-role pending capture must bind the exact V57 request intent'
                USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_pending_source_binding';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status <> 'COMMITTED'
        OR NEW.source_change_binding IS DISTINCT FROM OLD.source_change_binding THEN
        RAISE EXCEPTION 'Account tenant-role source capture and result are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_operation_identity_immutable';
    END IF;
    NEW.committed_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_role_source_capture_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source_change_binding IS NOT NULL THEN
        RAISE EXCEPTION 'Tenant-role source capture must follow the exact V57 source-change claim'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_pending_source_binding';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_tenant_role_source_capture_insert
    BEFORE INSERT ON account_tenant_role_operations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_role_source_capture_insert_guard();

CREATE OR REPLACE FUNCTION account_tenant_role_operation_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    current_operation account_tenant_role_operations%ROWTYPE;
    member_count BIGINT;
    source_status TEXT;
BEGIN
    SELECT * INTO current_operation FROM account_tenant_role_operations
    WHERE request_id = NEW.request_id;
    SELECT count(*) INTO member_count FROM account_tenant_role_operation_members
    WHERE request_id = NEW.request_id;

    IF current_operation.source_change_binding IS NOT NULL THEN
        SELECT status INTO source_status FROM account_draft_authorization_source_changes
        WHERE change_id = current_operation.request_id
            AND binding = current_operation.source_change_binding;
        IF source_status IS NULL THEN
            RAISE EXCEPTION 'Tenant-role operation lacks its immutable V57 source intent'
                USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_pending_source_binding';
        END IF;
    END IF;
    IF current_operation.status = 'IN_PROGRESS' THEN
        IF source_status = 'WAITING' AND member_count = 0 THEN
            RETURN NULL;
        END IF;
        RAISE EXCEPTION 'Incomplete Account tenant-role operation cannot commit'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_operation_must_complete';
    END IF;
    IF source_status IS NOT NULL AND source_status <> 'SOURCE_COMMITTED' THEN
        RAISE EXCEPTION 'Tenant-role result requires the same owner transaction source commit'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_pending_source_binding';
    END IF;

    IF (current_operation.action = 'TRANSFER_TENANT_ADMIN' AND member_count <> 2)
        OR (current_operation.action <> 'TRANSFER_TENANT_ADMIN' AND member_count <> 1) THEN
        RAISE EXCEPTION 'Account tenant-role operation has an incomplete member result'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_operation_member_result_count';
    END IF;
    IF current_operation.action = 'TRANSFER_TENANT_ADMIN'
        AND (NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id AND account_uuid = current_operation.actor_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid)
            OR NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id AND account_uuid = current_operation.target_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid))
        OR current_operation.action IN ('GRANT_DESIGNER', 'REVOKE_DESIGNER')
        AND NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id AND account_uuid = current_operation.target_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid) THEN
        RAISE EXCEPTION 'Account tenant-role operation member identities differ from its request'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_operation_member_identity';
    END IF;
    IF (current_operation.action = 'TRANSFER_TENANT_ADMIN'
        AND NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id AND account_uuid = current_operation.actor_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid AND caller_bound_authority_invalidated)
        OR current_operation.action = 'TRANSFER_TENANT_ADMIN'
        AND NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id AND account_uuid = current_operation.target_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid AND NOT caller_bound_authority_invalidated)
        OR current_operation.action IN ('GRANT_DESIGNER', 'REVOKE_DESIGNER')
        AND NOT EXISTS (
            SELECT 1 FROM account_tenant_role_operation_members
            WHERE request_id = NEW.request_id AND account_uuid = current_operation.target_account_uuid
                AND tenant_uuid = current_operation.tenant_uuid
                AND caller_bound_authority_invalidated = (current_operation.action = 'REVOKE_DESIGNER'))) THEN
        RAISE EXCEPTION 'Account tenant-role operation invalidation evidence differs from action'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_operation_invalidation_shape';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_tenant_role_linked_source_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM account_tenant_role_operations operation
        WHERE operation.request_id = NEW.change_id
            AND operation.source_change_binding IS NOT NULL
            AND (operation.source_change_binding IS DISTINCT FROM NEW.binding
                OR (NEW.status = 'SOURCE_COMMITTED' AND operation.status <> 'COMMITTED'))) THEN
        RAISE EXCEPTION 'Linked tenant-role source and operation must commit together'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_role_pending_source_binding';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_tenant_role_linked_source_complete
    AFTER UPDATE ON account_draft_authorization_source_changes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_tenant_role_linked_source_complete_guard();
-- [jooq ignore stop]
