-- The final Account result is immutable history. Only the Account owner authenticates authority;
-- these constraints bind its atomic result to the actual constituent owner receipts.
CREATE TABLE account_tenant_creation_completions (
    creation_operation_id UUID PRIMARY KEY
        REFERENCES account_tenant_creation_source_orders(creation_operation_id) ON DELETE RESTRICT,
    intent_request_id UUID NOT NULL UNIQUE
        REFERENCES account_tenant_creation_original_intents(request_id) ON DELETE RESTRICT,
    tenant_uuid UUID NOT NULL UNIQUE
        REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id) ON DELETE RESTRICT,
    terminal_payload BYTEA NOT NULL CHECK (octet_length(terminal_payload) BETWEEN 1 AND 8192),
    terminal_digest VARCHAR(71) NOT NULL
        CHECK (terminal_digest = 'sha256:' || encode(sha256(terminal_payload), 'hex')),
    result_payload BYTEA NOT NULL CHECK (octet_length(result_payload) BETWEEN 1 AND 8192),
    result_digest VARCHAR(71) NOT NULL
        CHECK (result_digest = 'sha256:' || encode(sha256(result_payload), 'hex')),
    audit_event_id UUID NOT NULL UNIQUE REFERENCES account_audit_outbox(audit_event_id) ON DELETE RESTRICT,
    valid_before TIMESTAMPTZ NOT NULL CHECK (isfinite(valid_before)),
    committed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- [jooq ignore start]
CREATE FUNCTION account_tenant_creation_completion_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_tenant_creation_original_intents%ROWTYPE;
    admission account_tenant_creation_admission_requests%ROWTYPE;
    recent account_recent_reauthentication_evidence%ROWTYPE;
    result JSONB;
BEGIN
    SELECT * INTO STRICT original FROM account_tenant_creation_original_intents
        WHERE creation_operation_id = NEW.creation_operation_id;
    SELECT * INTO STRICT admission FROM account_tenant_creation_admission_requests
        WHERE creation_operation_id = NEW.creation_operation_id;
    SELECT * INTO STRICT recent FROM account_recent_reauthentication_evidence
        WHERE evidence_id = original.recent_reauthentication_evidence_id;
    result := convert_from(NEW.result_payload, 'UTF8')::JSONB;
    IF NEW.intent_request_id <> original.request_id
        OR clock_timestamp() >= NEW.valid_before
        OR NEW.valid_before > to_timestamp(admission.operation_deadline_epoch_millis / 1000.0)
        OR NEW.valid_before > recent.issued_at + make_interval(secs =>
            (recent.recent_age_seconds - recent.max_clock_skew_seconds)::DOUBLE PRECISION)
        OR result->>'schema' IS DISTINCT FROM 'account-tenant-creation-completion/v1'
        OR result->>'operationId' IS DISTINCT FROM NEW.creation_operation_id::TEXT
        OR result->>'intentDigest' IS DISTINCT FROM original.request_digest
        OR result->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR result->>'accountId' IS DISTINCT FROM original.initiating_account_uuid::TEXT
        OR result->>'creatorPartyId' IS DISTINCT FROM original.creator_party_id::TEXT
        OR result->>'consentOperationId' IS DISTINCT FROM original.consent_operation_id::TEXT
        OR result->>'consentStatementVersion' IS DISTINCT FROM original.consent_statement_version::TEXT
        OR result->>'reauthenticationEvidenceId' IS DISTINCT FROM recent.evidence_id::TEXT
        OR result->>'auditEventId' IS DISTINCT FROM NEW.audit_event_id::TEXT
        OR result->>'terminalDigest' IS DISTINCT FROM NEW.terminal_digest
        OR NOT original.affirmative_consent THEN
        RAISE EXCEPTION 'Fresh Account creation completion is expired or differs from its original'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM account_tenant_creation_source_orders holder
        JOIN account_tenant_creation_source_order_settlements settlement
            USING (creation_operation_id)
        JOIN account_tenant_creation_bootstrap_operations bootstrap
            ON bootstrap.request_id = holder.creation_operation_id
        JOIN account_initial_tenant_billing_owner_operations owner
            ON owner.request_id = original.request_id
        JOIN account_fresh_creator_party_association_operations party
            ON party.request_id = holder.creation_operation_id
        JOIN account_recent_reauthentication_consumptions consumed
            ON consumed.evidence_id = recent.evidence_id
        JOIN account_audit_outbox audit ON audit.audit_event_id = NEW.audit_event_id
        WHERE holder.creation_operation_id = NEW.creation_operation_id
            AND holder.authorization_digest = result->>'authorizationDigest'
            AND settlement.outcome_status = 'CREATED'
            AND settlement.terminal_read_payload = NEW.terminal_payload
            AND settlement.terminal_read_digest = NEW.terminal_digest
            AND bootstrap.status = 'COMMITTED'
            AND bootstrap.tenant_uuid = NEW.tenant_uuid
            AND bootstrap.initiating_account_uuid = original.initiating_account_uuid
            AND bootstrap.account_authorization_digest = holder.authorization_digest
            AND bootstrap.creation_operation_id = settlement.game_design_creation_operation_id
            AND bootstrap.result_digest = result->>'bootstrapResultDigest'
            AND bootstrap.creator_evidence_digest = result->>'creatorEvidenceDigest'
            AND owner.tenant_uuid = NEW.tenant_uuid
            AND owner.initial_owner_account_uuid = original.initiating_account_uuid
            AND owner.account_authorization_operation_id = holder.creation_operation_id
            AND owner.account_authorization_digest = holder.authorization_digest
            AND owner.creator_evidence_digest = bootstrap.creator_evidence_digest
            AND owner.creation_operation_id = settlement.game_design_creation_operation_id
            AND owner.consent_affirmative
            AND owner.consent_operation_id = original.consent_operation_id
            AND owner.consent_statement_version = original.consent_statement_version
            AND owner.result_digest = result->>'initialOwnerResultDigest'
            AND owner.initial_owner_generation::TEXT = result->>'ownerGeneration'
            AND owner.initial_owner_version::TEXT = result->>'ownerVersion'
            AND party.tenant_uuid = NEW.tenant_uuid
            AND party.creator_party_id = original.creator_party_id
            AND party.initiating_account_uuid = original.initiating_account_uuid
            AND party.creation_operation_id = settlement.game_design_creation_operation_id
            AND consumed.account_uuid = original.initiating_account_uuid
            AND consumed.creation_operation_id = NEW.creation_operation_id
            AND audit.event_type = 'ACCOUNT_TENANT_CREATION_COMPLETED'
            AND audit.payload_digest = NEW.result_digest
            AND audit.payload = convert_from(NEW.result_payload, 'UTF8')
    ) THEN
        RAISE EXCEPTION 'Account creation completion lacks exact atomic owner receipts'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_tenant_creation_completion_guard
    AFTER INSERT ON account_tenant_creation_completions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_completion_guard();
CREATE TRIGGER account_tenant_creation_completion_immutable
    BEFORE UPDATE OR DELETE ON account_tenant_creation_completions
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_source_order_no_mutation();
CREATE TRIGGER account_tenant_creation_completion_no_truncate
    BEFORE TRUNCATE ON account_tenant_creation_completions
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_source_order_no_mutation();
-- [jooq ignore stop]
