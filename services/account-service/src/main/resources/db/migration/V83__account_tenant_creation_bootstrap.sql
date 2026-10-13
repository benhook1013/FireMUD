-- One immutable owner receipt for the first control-only Account membership created
-- from an exact, authenticated Game Design tenant-creation source. This storage does
-- not provide authentication or expose a mutation route.
CREATE TABLE account_tenant_creation_bootstrap_operations (
    request_id UUID PRIMARY KEY,
    schema_version SMALLINT NOT NULL CHECK (schema_version = 1),
    initiating_account_uuid UUID NOT NULL REFERENCES accounts (account_uuid),
    tenant_uuid UUID NOT NULL,
    creation_request_id UUID NOT NULL,
    creation_operation_id UUID NOT NULL,
    account_authorization_operation_id UUID NOT NULL,
    account_authorization_digest VARCHAR(71) NOT NULL
        CHECK (account_authorization_digest ~ '^sha256:[0-9a-f]{64}$'),
    creator_evidence_digest VARCHAR(71) NOT NULL
        CHECK (creator_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    creator_evidence_payload BYTEA NOT NULL,
    source_snapshot_payload BYTEA NOT NULL,
    source_snapshot_digest VARCHAR(71) NOT NULL
        CHECK (source_snapshot_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_payload BYTEA NOT NULL,
    request_digest VARCHAR(71) NOT NULL
        CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    baseline_membership_version BIGINT NOT NULL CHECK (baseline_membership_version > 0),
    baseline_membership_authority_generation BIGINT NOT NULL
        CHECK (baseline_membership_authority_generation > 0),
    baseline_event_sequence BIGINT NOT NULL CHECK (baseline_event_sequence = 0),
    membership_id BIGINT,
    membership_version BIGINT,
    membership_authority_generation BIGINT,
    membership_lifecycle_state VARCHAR(16),
    gameplay_admission_allowed BOOLEAN,
    membership_roles_payload BYTEA,
    event_stream_key VARCHAR(256),
    event_request_id VARCHAR(128),
    event_sequence BIGINT,
    event_id VARCHAR(512),
    event_digest VARCHAR(71),
    caller_bound_authority_invalidated BOOLEAN,
    event_payload BYTEA,
    audit_event_id UUID,
    audit_event_type VARCHAR(80),
    audit_occurred_at TIMESTAMPTZ,
    audit_payload_digest VARCHAR(71),
    audit_payload BYTEA,
    result_payload BYTEA,
    result_digest VARCHAR(71),
    status VARCHAR(16) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMMITTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    CONSTRAINT account_tenant_creation_bootstrap_authorization_identity_check
        CHECK (request_id = account_authorization_operation_id),
    CONSTRAINT account_tenant_creation_bootstrap_creator_tenant_uq
        UNIQUE (tenant_uuid),
    CONSTRAINT account_tenant_creation_bootstrap_creation_operation_uq
        UNIQUE (creation_operation_id),
    CONSTRAINT account_tenant_creation_bootstrap_membership_state_check
        CHECK (membership_lifecycle_state IS NULL OR membership_lifecycle_state = 'ACTIVE'),
    CONSTRAINT account_tenant_creation_bootstrap_admission_check
        CHECK (gameplay_admission_allowed IS NULL OR gameplay_admission_allowed = FALSE),
    CONSTRAINT account_tenant_creation_bootstrap_generation_check
        CHECK (membership_authority_generation IS NULL OR
            membership_authority_generation = baseline_membership_authority_generation),
    CONSTRAINT account_tenant_creation_bootstrap_event_check
        CHECK (event_sequence IS NULL OR event_sequence = 1),
    CONSTRAINT account_tenant_creation_bootstrap_event_scope_check
        CHECK (event_stream_key IS NULL OR event_stream_key =
            'account:auth-authority:v1:membership/' || initiating_account_uuid::TEXT || '/' || tenant_uuid::TEXT),
    CONSTRAINT account_tenant_creation_bootstrap_event_request_check
        CHECK (event_request_id IS NULL OR event_request_id = request_id::TEXT),
    CONSTRAINT account_tenant_creation_bootstrap_invalidation_check
        CHECK (caller_bound_authority_invalidated IS NULL OR caller_bound_authority_invalidated = FALSE),
    CONSTRAINT account_tenant_creation_bootstrap_completion_shape CHECK (
        (status = 'IN_PROGRESS'
            AND membership_id IS NULL AND membership_version IS NULL
            AND membership_authority_generation IS NULL AND membership_lifecycle_state IS NULL
            AND gameplay_admission_allowed IS NULL AND membership_roles_payload IS NULL
            AND event_stream_key IS NULL AND event_request_id IS NULL AND event_sequence IS NULL
            AND event_id IS NULL AND event_digest IS NULL
            AND caller_bound_authority_invalidated IS NULL AND event_payload IS NULL
            AND audit_event_id IS NULL AND audit_event_type IS NULL AND audit_occurred_at IS NULL
            AND audit_payload_digest IS NULL AND audit_payload IS NULL
            AND result_payload IS NULL AND result_digest IS NULL AND committed_at IS NULL)
        OR (status = 'COMMITTED'
            AND membership_id IS NOT NULL AND membership_id > 0
            AND membership_version = baseline_membership_version + 1
            AND membership_authority_generation = baseline_membership_authority_generation
            AND membership_lifecycle_state = 'ACTIVE'
            AND gameplay_admission_allowed = FALSE
            AND membership_roles_payload = convert_to('["tenantAdmin"]', 'UTF8')
            AND event_stream_key IS NOT NULL AND event_request_id IS NOT NULL
            AND event_sequence = 1 AND event_id IS NOT NULL AND length(btrim(event_id)) > 0
            AND event_digest IS NOT NULL AND event_digest ~ '^sha256:[0-9a-f]{64}$'
            AND caller_bound_authority_invalidated = FALSE AND event_payload IS NOT NULL
            AND audit_event_id IS NOT NULL AND audit_event_type = 'ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED'
            AND audit_occurred_at IS NOT NULL
            AND audit_payload_digest IS NOT NULL AND audit_payload_digest ~ '^sha256:[0-9a-f]{64}$'
            AND audit_payload IS NOT NULL
            AND result_payload IS NOT NULL AND result_digest IS NOT NULL
            AND result_digest ~ '^sha256:[0-9a-f]{64}$' AND committed_at IS NOT NULL))
);

CREATE INDEX account_tenant_creation_bootstrap_creator_idx
    ON account_tenant_creation_bootstrap_operations
        (initiating_account_uuid, tenant_uuid, request_id);

-- [jooq ignore start]
CREATE FUNCTION account_tenant_creation_bootstrap_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.initiating_account_uuid IS DISTINCT FROM OLD.initiating_account_uuid
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.creation_request_id IS DISTINCT FROM OLD.creation_request_id
        OR NEW.creation_operation_id IS DISTINCT FROM OLD.creation_operation_id
        OR NEW.account_authorization_operation_id IS DISTINCT FROM OLD.account_authorization_operation_id
        OR NEW.account_authorization_digest IS DISTINCT FROM OLD.account_authorization_digest
        OR NEW.creator_evidence_digest IS DISTINCT FROM OLD.creator_evidence_digest
        OR NEW.creator_evidence_payload IS DISTINCT FROM OLD.creator_evidence_payload
        OR NEW.source_snapshot_payload IS DISTINCT FROM OLD.source_snapshot_payload
        OR NEW.source_snapshot_digest IS DISTINCT FROM OLD.source_snapshot_digest
        OR NEW.request_payload IS DISTINCT FROM OLD.request_payload
        OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
        OR NEW.baseline_membership_version IS DISTINCT FROM OLD.baseline_membership_version
        OR NEW.baseline_membership_authority_generation IS DISTINCT FROM OLD.baseline_membership_authority_generation
        OR NEW.baseline_event_sequence IS DISTINCT FROM OLD.baseline_event_sequence
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR OLD.status <> 'IN_PROGRESS'
        OR NEW.status <> 'COMMITTED' THEN
        RAISE EXCEPTION 'Account creator-bootstrap request is immutable'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_tenant_creation_bootstrap_identity_immutable';
    END IF;
    NEW.committed_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_creation_bootstrap_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'IN_PROGRESS'
        OR NEW.membership_id IS NOT NULL OR NEW.membership_version IS NOT NULL
        OR NEW.membership_authority_generation IS NOT NULL
        OR NEW.membership_lifecycle_state IS NOT NULL OR NEW.gameplay_admission_allowed IS NOT NULL
        OR NEW.membership_roles_payload IS NOT NULL
        OR NEW.event_stream_key IS NOT NULL OR NEW.event_request_id IS NOT NULL
        OR NEW.event_sequence IS NOT NULL OR NEW.event_id IS NOT NULL OR NEW.event_digest IS NOT NULL
        OR NEW.caller_bound_authority_invalidated IS NOT NULL OR NEW.event_payload IS NOT NULL
        OR NEW.audit_event_id IS NOT NULL OR NEW.audit_event_type IS NOT NULL
        OR NEW.audit_occurred_at IS NOT NULL OR NEW.audit_payload_digest IS NOT NULL
        OR NEW.audit_payload IS NOT NULL OR NEW.result_payload IS NOT NULL
        OR NEW.result_digest IS NOT NULL OR NEW.committed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Account creator-bootstrap operation must begin without a result'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_tenant_creation_bootstrap_initial_state';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_creation_bootstrap_no_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account creator-bootstrap history cannot be deleted'
        USING ERRCODE = '23514',
            CONSTRAINT = 'account_tenant_creation_bootstrap_no_delete';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_tenant_creation_bootstrap_insert
    BEFORE INSERT ON account_tenant_creation_bootstrap_operations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_bootstrap_insert_guard();

CREATE TRIGGER account_tenant_creation_bootstrap_update
    BEFORE UPDATE ON account_tenant_creation_bootstrap_operations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_bootstrap_update_guard();

CREATE TRIGGER account_tenant_creation_bootstrap_delete
    BEFORE DELETE ON account_tenant_creation_bootstrap_operations
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_bootstrap_no_delete();
-- [jooq ignore stop]
