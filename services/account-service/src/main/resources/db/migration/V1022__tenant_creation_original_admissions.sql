-- Frozen request data is deliberately separate from durable original admission authority.
CREATE TABLE account_tenant_creation_admission_requests (
    creation_operation_id UUID PRIMARY KEY
        REFERENCES account_tenant_creation_original_intents(creation_operation_id) ON DELETE RESTRICT,
    intent_request_id UUID NOT NULL UNIQUE
        REFERENCES account_tenant_creation_original_intents(request_id) ON DELETE RESTRICT,
    issuance_operation_id UUID NOT NULL
        REFERENCES account_control_ui_issuance_operations(operation_id) ON DELETE RESTRICT,
    actor_account_id UUID NOT NULL REFERENCES accounts(account_uuid) ON DELETE RESTRICT,
    target_namespace VARCHAR(63) NOT NULL,
    game_design_creation_request_id UUID NOT NULL,
    game_design_request_digest VARCHAR(71) NOT NULL,
    intent_digest VARCHAR(71) NOT NULL,
    active_registry_digest VARCHAR(64) NOT NULL,
    reauthentication_evidence_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    token_cleanup_deadline_epoch_millis BIGINT NOT NULL,
    token_expires_at_epoch_millis BIGINT NOT NULL,
    prepared_at_epoch_millis BIGINT NOT NULL CHECK (prepared_at_epoch_millis > 0),
    operation_deadline_epoch_millis BIGINT NOT NULL,
    receipt_cleanup_deadline_epoch_millis BIGINT NOT NULL,
    binding_payload BYTEA NOT NULL CHECK (octet_length(binding_payload) BETWEEN 1 AND 8192),
    binding_digest VARCHAR(64) NOT NULL
        CHECK (binding_digest = encode(sha256(binding_payload), 'hex')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (token_expires_at_epoch_millis % 1000 = 0
        AND token_cleanup_deadline_epoch_millis >= token_expires_at_epoch_millis
        AND token_cleanup_deadline_epoch_millis <= 9007199254740991
        AND operation_deadline_epoch_millis > prepared_at_epoch_millis
        AND operation_deadline_epoch_millis <= token_expires_at_epoch_millis
        AND operation_deadline_epoch_millis - prepared_at_epoch_millis <= 300000
        AND receipt_cleanup_deadline_epoch_millis - operation_deadline_epoch_millis = 86400000
        AND receipt_cleanup_deadline_epoch_millis <= 9007199254740991)
);

CREATE TABLE account_tenant_creation_admissions (
    creation_operation_id UUID PRIMARY KEY
        REFERENCES account_tenant_creation_admission_requests(creation_operation_id) ON DELETE RESTRICT,
    receipt_payload BYTEA NOT NULL CHECK (octet_length(receipt_payload) BETWEEN 1 AND 8192),
    receipt_digest VARCHAR(64) NOT NULL
        CHECK (receipt_digest = encode(sha256(receipt_payload), 'hex')),
    local_aof_count BIGINT NOT NULL CHECK (local_aof_count >= 1),
    replica_aof_count BIGINT NOT NULL CHECK (replica_aof_count >= 0),
    protected_binding_id VARCHAR(256) NOT NULL
        CHECK (length(protected_binding_id) > 0 AND protected_binding_id = btrim(protected_binding_id)),
    qualification_fence BIGINT NOT NULL CHECK (qualification_fence > 0),
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- [jooq ignore start]
CREATE FUNCTION account_tenant_creation_admission_request_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_tenant_creation_original_intents%ROWTYPE;
    issuer account_control_ui_issuance_operations%ROWTYPE;
    expected JSONB;
BEGIN
    SELECT * INTO STRICT original FROM account_tenant_creation_original_intents
        WHERE creation_operation_id = NEW.creation_operation_id FOR SHARE;
    SELECT * INTO STRICT issuer FROM account_control_ui_issuance_operations
        WHERE operation_id = NEW.issuance_operation_id FOR SHARE;
    IF original.request_id IS DISTINCT FROM NEW.intent_request_id
        OR original.initiating_account_uuid IS DISTINCT FROM NEW.actor_account_id
        OR original.target_namespace IS DISTINCT FROM NEW.target_namespace
        OR original.game_design_creation_request_id IS DISTINCT FROM NEW.game_design_creation_request_id
        OR original.game_design_request_digest IS DISTINCT FROM NEW.game_design_request_digest
        OR original.request_digest IS DISTINCT FROM NEW.intent_digest
        OR original.recent_reauthentication_evidence_id IS DISTINCT FROM NEW.reauthentication_evidence_id
        OR issuer.authorization_scope IS DISTINCT FROM 'ACCOUNT'
        OR issuer.tenant_uuid IS NOT NULL
        OR issuer.account_uuid IS DISTINCT FROM NEW.actor_account_id
        OR issuer.token_hash IS DISTINCT FROM NEW.token_hash
        OR encode(sha256(issuer.active_registry), 'hex') IS DISTINCT FROM NEW.active_registry_digest
        OR issuer.registry_cleanup_deadline_epoch_millis IS DISTINCT FROM NEW.token_cleanup_deadline_epoch_millis
        OR issuer.expires_at_epoch_second::NUMERIC * 1000 IS DISTINCT FROM NEW.token_expires_at_epoch_millis::NUMERIC THEN
        RAISE EXCEPTION 'Creation admission request differs from original intent or issuance' USING ERRCODE = '23514';
    END IF;
    expected := jsonb_build_object(
        'schema', 'account-tenant-creation-admission-v1',
        'intentRequestId', NEW.intent_request_id::TEXT,
        'creationOperationId', NEW.creation_operation_id::TEXT,
        'issuanceOperationId', NEW.issuance_operation_id::TEXT,
        'actorAccountId', NEW.actor_account_id::TEXT,
        'targetNamespace', NEW.target_namespace,
        'gameDesignCreationRequestId', NEW.game_design_creation_request_id::TEXT,
        'gameDesignRequestDigest', NEW.game_design_request_digest,
        'intentDigest', NEW.intent_digest,
        'activeRegistryDigest', NEW.active_registry_digest,
        'reauthenticationEvidenceId', NEW.reauthentication_evidence_id::TEXT,
        'tokenHash', NEW.token_hash,
        'tokenCleanupDeadlineEpochMillis', NEW.token_cleanup_deadline_epoch_millis,
        'tokenExpiresAtEpochMillis', NEW.token_expires_at_epoch_millis,
        'preparedAtEpochMillis', NEW.prepared_at_epoch_millis,
        'operationDeadlineEpochMillis', NEW.operation_deadline_epoch_millis,
        'receiptCleanupDeadlineEpochMillis', NEW.receipt_cleanup_deadline_epoch_millis);
    IF convert_from(NEW.binding_payload, 'UTF8')::JSONB IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'Creation admission payload differs from frozen tuple' USING ERRCODE = '23514';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_creation_admission_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    request account_tenant_creation_admission_requests%ROWTYPE;
    issuer account_control_ui_issuance_operations%ROWTYPE;
    now_millis NUMERIC;
BEGIN
    SELECT * INTO STRICT request FROM account_tenant_creation_admission_requests
        WHERE creation_operation_id = NEW.creation_operation_id FOR SHARE;
    -- FOR SHARE conflicts with beginRecoveryFailure's actual UPDATE/row lock. A stale Redis
    -- active snapshot can never bypass SQL's earlier REVOKING denial.
    SELECT * INTO STRICT issuer FROM account_control_ui_issuance_operations
        WHERE operation_id = request.issuance_operation_id FOR SHARE;
    now_millis := extract(epoch FROM clock_timestamp()) * 1000;
    IF issuer.status IS DISTINCT FROM 'COMMITTED' OR issuer.recovery_failed_at IS NOT NULL
        OR issuer.committed_at IS NULL OR issuer.authorization_scope IS DISTINCT FROM 'ACCOUNT'
        OR issuer.tenant_uuid IS NOT NULL OR issuer.account_uuid IS DISTINCT FROM request.actor_account_id
        OR issuer.token_hash IS DISTINCT FROM request.token_hash
        OR encode(sha256(issuer.active_registry), 'hex') IS DISTINCT FROM request.active_registry_digest
        OR issuer.registry_cleanup_deadline_epoch_millis IS DISTINCT FROM request.token_cleanup_deadline_epoch_millis
        OR issuer.expires_at_epoch_second::NUMERIC * 1000 IS DISTINCT FROM request.token_expires_at_epoch_millis::NUMERIC
        OR now_millis < request.prepared_at_epoch_millis
        OR now_millis >= request.operation_deadline_epoch_millis
        OR now_millis >= request.token_expires_at_epoch_millis
        OR NEW.receipt_payload IS DISTINCT FROM request.binding_payload
        OR NEW.receipt_digest IS DISTINCT FROM request.binding_digest THEN
        RAISE EXCEPTION 'Original creation admission is unavailable' USING ERRCODE = '23514';
    END IF;
    NEW.admitted_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_creation_admission_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original creation admission evidence is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER account_tenant_creation_admission_request_guard
    BEFORE INSERT ON account_tenant_creation_admission_requests FOR EACH ROW
    EXECUTE FUNCTION account_tenant_creation_admission_request_guard();
CREATE TRIGGER account_tenant_creation_admission_guard
    BEFORE INSERT ON account_tenant_creation_admissions FOR EACH ROW
    EXECUTE FUNCTION account_tenant_creation_admission_guard();
CREATE TRIGGER account_tenant_creation_admission_request_immutable
    BEFORE UPDATE OR DELETE ON account_tenant_creation_admission_requests FOR EACH ROW
    EXECUTE FUNCTION account_tenant_creation_admission_no_mutation();
CREATE TRIGGER account_tenant_creation_admission_request_no_truncate
    BEFORE TRUNCATE ON account_tenant_creation_admission_requests FOR EACH STATEMENT
    EXECUTE FUNCTION account_tenant_creation_admission_no_mutation();
CREATE TRIGGER account_tenant_creation_admission_immutable
    BEFORE UPDATE OR DELETE ON account_tenant_creation_admissions FOR EACH ROW
    EXECUTE FUNCTION account_tenant_creation_admission_no_mutation();
CREATE TRIGGER account_tenant_creation_admission_no_truncate
    BEFORE TRUNCATE ON account_tenant_creation_admissions FOR EACH STATEMENT
    EXECUTE FUNCTION account_tenant_creation_admission_no_mutation();
-- [jooq ignore stop]
