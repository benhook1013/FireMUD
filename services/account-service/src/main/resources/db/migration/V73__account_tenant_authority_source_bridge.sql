-- Preserve source-owner initialization provenance for future exact tenant-authority enrollment.
-- Existing rows remain NULL and are deliberately not assigned a synthetic generation-one proof.
ALTER TABLE account_fresh_tenant_identity_associations
    ADD COLUMN authority_source_transaction_id BIGINT;
ALTER TABLE account_fresh_tenant_identity_associations
    ADD CONSTRAINT account_fresh_tenant_authority_source_transaction_check
        CHECK (authority_source_transaction_id IS NULL OR authority_source_transaction_id > 0);

-- This is an Account-owned typed head over the generic authority outbox, not a second event log.
-- A sequence-zero row exists only inside the first authority transaction and cannot commit.
CREATE TABLE account_tenant_authority_source_records (
    outbox_stream_key VARCHAR(2048) PRIMARY KEY
        REFERENCES account_authority_outbox_streams (outbox_stream_key) ON DELETE RESTRICT,
    tenant_uuid UUID NOT NULL UNIQUE
        REFERENCES account_fresh_tenant_identity_associations (canonical_tenant_id) ON DELETE RESTRICT,
    initialization_transaction_id BIGINT NOT NULL CHECK (initialization_transaction_id > 0),
    baseline_generation BIGINT NOT NULL DEFAULT 1 CHECK (baseline_generation = 1),
    baseline_source_version BIGINT NOT NULL DEFAULT 1 CHECK (baseline_source_version = 1),
    current_generation BIGINT NOT NULL CHECK (current_generation > 0),
    current_source_version BIGINT NOT NULL CHECK (current_source_version > 0),
    last_outbox_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_outbox_sequence >= 0),
    last_request_id UUID,
    last_request_digest VARCHAR(71),
    last_event_id VARCHAR(512),
    last_event_digest VARCHAR(71),
    tenant_billing_sequence BIGINT,
    tenant_billing_event_id UUID,
    tenant_billing_event_digest VARCHAR(71),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_tenant_authority_source_key_check CHECK (
        outbox_stream_key = 'account:auth-authority:v1:tenant/' || tenant_uuid::TEXT),
    CONSTRAINT account_tenant_authority_source_version_check CHECK (
        current_generation = baseline_generation + last_outbox_sequence
        AND current_source_version = baseline_source_version + last_outbox_sequence),
    CONSTRAINT account_tenant_authority_source_head_check CHECK (
        (last_outbox_sequence = 0 AND last_request_id IS NULL AND last_request_digest IS NULL
            AND last_event_id IS NULL AND last_event_digest IS NULL
            AND tenant_billing_sequence IS NULL AND tenant_billing_event_id IS NULL
            AND tenant_billing_event_digest IS NULL)
        OR (last_outbox_sequence > 0 AND last_request_id IS NOT NULL
            AND last_request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND last_event_id IS NOT NULL AND length(btrim(last_event_id)) > 0
            AND last_event_digest ~ '^sha256:[0-9a-f]{64}$'
            AND tenant_billing_sequence > 0 AND tenant_billing_event_id IS NOT NULL
            AND tenant_billing_event_digest ~ '^sha256:[0-9a-f]{64}$'))
);

ALTER TABLE account_demo_tenant_entitlement_operations
    ADD COLUMN tenant_authority_outbox_stream_key VARCHAR(2048),
    ADD COLUMN tenant_authority_outbox_sequence BIGINT,
    ADD COLUMN tenant_authority_event_id VARCHAR(512),
    ADD COLUMN tenant_authority_event_digest VARCHAR(71);
-- [jooq ignore start]
-- PostgreSQL retains historical rows without validating this new source receipt constraint.
ALTER TABLE account_demo_tenant_entitlement_operations
    ADD CONSTRAINT account_demo_entitlement_authority_receipt_shape CHECK (
        (status = 'PENDING' AND tenant_authority_outbox_stream_key IS NULL
            AND tenant_authority_outbox_sequence IS NULL AND tenant_authority_event_id IS NULL
            AND tenant_authority_event_digest IS NULL)
        OR (status = 'COMMITTED' AND tenant_authority_outbox_stream_key IS NOT NULL
            AND tenant_authority_outbox_sequence > 0
            AND tenant_authority_event_id IS NOT NULL
            AND tenant_authority_event_digest ~ '^sha256:[0-9a-f]{64}$')) NOT VALID;
-- [jooq ignore stop]
ALTER TABLE account_demo_tenant_entitlement_operations
    ADD CONSTRAINT account_demo_tenant_entitlement_operations_authority_event_fk
        FOREIGN KEY (tenant_authority_outbox_stream_key, tenant_authority_outbox_sequence)
        REFERENCES account_authority_outbox_events (outbox_stream_key, outbox_sequence)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE account_demo_tenant_entitlements
    ADD COLUMN tenant_authority_outbox_stream_key VARCHAR(2048),
    ADD COLUMN tenant_authority_outbox_sequence BIGINT,
    ADD COLUMN tenant_authority_event_id VARCHAR(512),
    ADD COLUMN tenant_authority_event_digest VARCHAR(71);
-- [jooq ignore start]
-- PostgreSQL retains historical rows without validating this new source head constraint.
ALTER TABLE account_demo_tenant_entitlements
    ADD CONSTRAINT account_demo_entitlement_authority_head_shape CHECK (
        (tenant_authority_outbox_stream_key IS NULL AND tenant_authority_outbox_sequence IS NULL
            AND tenant_authority_event_id IS NULL AND tenant_authority_event_digest IS NULL)
        OR (tenant_authority_outbox_stream_key IS NOT NULL
            AND tenant_authority_outbox_sequence > 0 AND tenant_authority_event_id IS NOT NULL
            AND tenant_authority_event_digest ~ '^sha256:[0-9a-f]{64}$')) NOT VALID;
-- [jooq ignore stop]
ALTER TABLE account_demo_tenant_entitlements
    ADD CONSTRAINT account_demo_tenant_entitlements_authority_event_fk
        FOREIGN KEY (tenant_authority_outbox_stream_key, tenant_authority_outbox_sequence)
        REFERENCES account_authority_outbox_events (outbox_stream_key, outbox_sequence)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

-- Only Account's fresh tenant import path stamps this transaction ID. Retained rows are untouched.
-- [jooq ignore start]
CREATE FUNCTION account_fresh_tenant_authority_source_transaction_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.authority_source_transaction_id := txid_current();
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_fresh_tenant_authority_source_transaction
    BEFORE INSERT ON account_fresh_tenant_identity_associations
    FOR EACH ROW EXECUTE FUNCTION account_fresh_tenant_authority_source_transaction_guard();

CREATE FUNCTION account_tenant_authority_source_insert_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    fresh_row RECORD;
    generation_row RECORD;
    stream_sequence BIGINT;
BEGIN
    SELECT authority_source_transaction_id, provenance_kind INTO fresh_row
        FROM account_fresh_tenant_identity_associations
        WHERE canonical_tenant_id = NEW.tenant_uuid;
    SELECT generation, source_version, created_transaction_id INTO generation_row
        FROM account_authority_generations
        WHERE scope_kind = 'TENANT' AND tenant_uuid = NEW.tenant_uuid;
    SELECT last_sequence INTO stream_sequence FROM account_authority_outbox_streams
        WHERE outbox_stream_key = NEW.outbox_stream_key;
    IF NEW.outbox_stream_key <> 'account:auth-authority:v1:tenant/' || NEW.tenant_uuid::TEXT
        OR NEW.initialization_transaction_id IS NULL
        OR fresh_row.authority_source_transaction_id IS DISTINCT FROM NEW.initialization_transaction_id
        OR fresh_row.provenance_kind IS DISTINCT FROM 'NEW_GAME_ROW'
        OR generation_row.created_transaction_id IS DISTINCT FROM NEW.initialization_transaction_id
        OR generation_row.generation IS DISTINCT FROM 1
        OR generation_row.source_version IS DISTINCT FROM 1
        OR stream_sequence IS DISTINCT FROM 0
        OR NEW.baseline_generation IS DISTINCT FROM 1
        OR NEW.baseline_source_version IS DISTINCT FROM 1
        OR NEW.current_generation IS DISTINCT FROM 1
        OR NEW.current_source_version IS DISTINCT FROM 1
        OR NEW.last_outbox_sequence IS DISTINCT FROM 0
        OR NEW.last_event_id IS NOT NULL OR NEW.last_event_digest IS NOT NULL
        OR EXISTS (SELECT 1 FROM account_authority_outbox_events
                   WHERE outbox_stream_key = NEW.outbox_stream_key) THEN
        RAISE EXCEPTION 'Tenant authority baseline lacks fresh Account source initialization proof'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_source_baseline';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_authority_source_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    authority_event RECORD;
    payload JSONB;
    billing_event RECORD;
    fresh_row RECORD;
BEGIN
    IF NEW.outbox_stream_key IS DISTINCT FROM OLD.outbox_stream_key
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.initialization_transaction_id IS DISTINCT FROM OLD.initialization_transaction_id
        OR NEW.baseline_generation IS DISTINCT FROM OLD.baseline_generation
        OR NEW.baseline_source_version IS DISTINCT FROM OLD.baseline_source_version
        OR NEW.last_outbox_sequence <> OLD.last_outbox_sequence + 1
        OR NEW.current_generation <> OLD.current_generation + 1
        OR NEW.current_source_version <> OLD.current_source_version + 1
        OR NEW.current_generation <> NEW.baseline_generation + NEW.last_outbox_sequence
        OR NEW.current_source_version <> NEW.baseline_source_version + NEW.last_outbox_sequence
        OR NEW.last_request_id IS NULL OR NEW.last_request_digest !~ '^sha256:[0-9a-f]{64}$'
        OR NEW.last_event_id IS NULL OR NEW.last_event_digest !~ '^sha256:[0-9a-f]{64}$'
        OR NEW.tenant_billing_sequence IS NULL OR NEW.tenant_billing_sequence <= 0
        OR NEW.tenant_billing_event_id IS NULL
        OR NEW.tenant_billing_event_digest !~ '^sha256:[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Tenant authority source must advance one exact immutable event'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_source_monotonic';
    END IF;
    SELECT event_id, event_digest, payload INTO authority_event
        FROM account_authority_outbox_events
        WHERE outbox_stream_key = NEW.outbox_stream_key
          AND outbox_sequence = NEW.last_outbox_sequence;
    IF authority_event.event_id IS DISTINCT FROM NEW.last_event_id
        OR authority_event.event_digest IS DISTINCT FROM NEW.last_event_digest THEN
        RAISE EXCEPTION 'Tenant authority source head differs from its exact event'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_source_event_link';
    END IF;
    payload := convert_from(authority_event.payload, 'UTF8')::JSONB;
    IF payload->>'schemaVersion' IS DISTINCT FROM 'account-tenant-authority-event/v1'
        OR payload->>'eventType' IS DISTINCT FROM 'TENANT_AUTHORITY_CHANGED'
        OR payload->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR payload->>'outboxStreamKey' IS DISTINCT FROM NEW.outbox_stream_key
        OR payload->>'outboxSequence' IS DISTINCT FROM NEW.last_outbox_sequence::TEXT
        OR payload->>'eventId' IS DISTINCT FROM NEW.last_event_id
        OR payload->>'eventDigest' IS DISTINCT FROM NEW.last_event_digest
        OR payload->>'requestId' IS DISTINCT FROM NEW.last_request_id::TEXT
        OR payload->>'requestDigest' IS DISTINCT FROM NEW.last_request_digest
        OR payload->>'tenantAuthorityGeneration' IS DISTINCT FROM NEW.current_generation::TEXT
        OR payload->>'tenantAuthoritySourceVersion' IS DISTINCT FROM NEW.current_source_version::TEXT
        OR payload#>>'{tenantBillingReceipt,outboxStreamKey}'
            IS DISTINCT FROM ('account:tenant-entitlement:v1:tenant/' || NEW.tenant_uuid::TEXT)
        OR payload#>>'{tenantBillingReceipt,tenantBillingSequence}'
            IS DISTINCT FROM NEW.tenant_billing_sequence::TEXT
        OR payload#>>'{tenantBillingReceipt,eventId}' IS DISTINCT FROM NEW.tenant_billing_event_id::TEXT
        OR payload#>>'{tenantBillingReceipt,eventDigest}' IS DISTINCT FROM NEW.tenant_billing_event_digest THEN
        RAISE EXCEPTION 'Tenant authority source event does not match its exact checkpoint'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_source_event_values';
    END IF;
    SELECT schema_version, target_namespace, creation_request_id, operation_id, request_digest,
           source_game_row_id, source_game_tenant_key, provenance_kind, evidence_digest
        INTO fresh_row FROM account_fresh_tenant_identity_associations
        WHERE canonical_tenant_id = NEW.tenant_uuid;
    IF payload#>>'{sourceEvidence,schemaVersion}' IS DISTINCT FROM fresh_row.schema_version::TEXT
        OR payload#>>'{sourceEvidence,targetNamespace}' IS DISTINCT FROM fresh_row.target_namespace
        OR payload#>>'{sourceEvidence,creationRequestId}' IS DISTINCT FROM fresh_row.creation_request_id::TEXT
        OR payload#>>'{sourceEvidence,operationId}' IS DISTINCT FROM fresh_row.operation_id::TEXT
        OR payload#>>'{sourceEvidence,requestDigest}' IS DISTINCT FROM fresh_row.request_digest
        OR payload#>>'{sourceEvidence,canonicalTenantId}' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR payload#>>'{sourceEvidence,sourceGameRowId}' IS DISTINCT FROM fresh_row.source_game_row_id::TEXT
        OR payload#>>'{sourceEvidence,sourceGameTenantKey}' IS DISTINCT FROM fresh_row.source_game_tenant_key
        OR payload#>>'{sourceEvidence,provenanceKind}' IS DISTINCT FROM fresh_row.provenance_kind
        OR payload#>>'{sourceEvidence,evidenceDigest}' IS DISTINCT FROM fresh_row.evidence_digest THEN
        RAISE EXCEPTION 'Tenant authority event differs from fresh Game Design source evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_source_evidence';
    END IF;
    SELECT event_id, event_digest, payload INTO billing_event
        FROM account_tenant_entitlement_outbox_events
        WHERE tenant_uuid = NEW.tenant_uuid
          AND tenant_billing_sequence = NEW.tenant_billing_sequence;
    IF billing_event.event_id IS DISTINCT FROM NEW.tenant_billing_event_id
        OR billing_event.event_digest IS DISTINCT FROM NEW.tenant_billing_event_digest THEN
        RAISE EXCEPTION 'Tenant authority event does not link to the exact tenant billing event'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_billing_link';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_tenant_authority_source_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    head RECORD;
    generation_row RECORD;
    stream_sequence BIGINT;
    operation_row RECORD;
    entitlement_row RECORD;
BEGIN
    SELECT * INTO head FROM account_tenant_authority_source_records
        WHERE outbox_stream_key = NEW.outbox_stream_key;
    SELECT generation, source_version INTO generation_row FROM account_authority_generations
        WHERE scope_kind = 'TENANT' AND tenant_uuid = NEW.tenant_uuid;
    SELECT last_sequence INTO stream_sequence FROM account_authority_outbox_streams
        WHERE outbox_stream_key = NEW.outbox_stream_key;
    IF head.outbox_stream_key IS NULL OR head.last_outbox_sequence <= 0
        OR head.current_generation IS DISTINCT FROM generation_row.generation
        OR head.current_source_version IS DISTINCT FROM generation_row.source_version
        OR stream_sequence IS DISTINCT FROM head.last_outbox_sequence THEN
        RAISE EXCEPTION 'Tenant authority source checkpoint is not the current Account generation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_source_consistent';
    END IF;
    SELECT request_id, tenant_uuid, request_digest, source_creation_request_id,
           source_request_digest, source_operation_id, source_evidence_digest, status,
           tenant_billing_sequence, event_id, event_digest,
           tenant_authority_outbox_stream_key, tenant_authority_outbox_sequence,
           tenant_authority_event_id, tenant_authority_event_digest
        INTO operation_row FROM account_demo_tenant_entitlement_operations
        WHERE request_id = head.last_request_id;
    SELECT * INTO entitlement_row FROM account_demo_tenant_entitlements
        WHERE tenant_uuid = NEW.tenant_uuid;
    IF operation_row.status IS DISTINCT FROM 'COMMITTED'
        OR operation_row.tenant_uuid IS DISTINCT FROM NEW.tenant_uuid
        OR operation_row.request_digest IS DISTINCT FROM head.last_request_digest
        OR operation_row.tenant_billing_sequence IS DISTINCT FROM head.tenant_billing_sequence
        OR operation_row.event_id IS DISTINCT FROM head.tenant_billing_event_id
        OR operation_row.event_digest IS DISTINCT FROM head.tenant_billing_event_digest
        OR operation_row.tenant_authority_outbox_stream_key IS DISTINCT FROM head.outbox_stream_key
        OR operation_row.tenant_authority_outbox_sequence IS DISTINCT FROM head.last_outbox_sequence
        OR operation_row.tenant_authority_event_id IS DISTINCT FROM head.last_event_id
        OR operation_row.tenant_authority_event_digest IS DISTINCT FROM head.last_event_digest
        OR entitlement_row.tenant_uuid IS DISTINCT FROM NEW.tenant_uuid
        OR entitlement_row.tenant_authority_generation IS DISTINCT FROM head.current_generation
        OR entitlement_row.tenant_authority_source_version IS DISTINCT FROM head.current_source_version
        OR entitlement_row.tenant_billing_sequence IS DISTINCT FROM head.tenant_billing_sequence
        OR entitlement_row.event_id IS DISTINCT FROM head.tenant_billing_event_id
        OR entitlement_row.event_digest IS DISTINCT FROM head.tenant_billing_event_digest
        OR entitlement_row.tenant_authority_outbox_stream_key IS DISTINCT FROM head.outbox_stream_key
        OR entitlement_row.tenant_authority_outbox_sequence IS DISTINCT FROM head.last_outbox_sequence
        OR entitlement_row.tenant_authority_event_id IS DISTINCT FROM head.last_event_id
        OR entitlement_row.tenant_authority_event_digest IS DISTINCT FROM head.last_event_digest THEN
        RAISE EXCEPTION 'Tenant authority source lacks its exact current Account receipt and entitlement'
            USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_source_receipt';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_tenant_authority_generation_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_row RECORD;
BEGIN
    IF NEW.scope_kind = 'TENANT' THEN
        SELECT current_generation, current_source_version INTO source_row
            FROM account_tenant_authority_source_records WHERE tenant_uuid = NEW.tenant_uuid;
        IF source_row.current_generation IS DISTINCT FROM NEW.generation
            OR source_row.current_source_version IS DISTINCT FROM NEW.source_version THEN
            RAISE EXCEPTION 'Tenant authority generation advance lacks its canonical source event'
                USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_generation_source';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_tenant_authority_stream_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_row RECORD;
BEGIN
    IF left(NEW.outbox_stream_key, length('account:auth-authority:v1:tenant/'))
        = 'account:auth-authority:v1:tenant/' THEN
        SELECT last_outbox_sequence INTO source_row
            FROM account_tenant_authority_source_records
            WHERE outbox_stream_key = NEW.outbox_stream_key;
        IF source_row.last_outbox_sequence IS DISTINCT FROM NEW.last_sequence
            OR NEW.last_sequence <= 0 THEN
            RAISE EXCEPTION 'Tenant authority outbox head lacks an Account-owned source checkpoint'
                USING ERRCODE = '23514', CONSTRAINT = 'account_tenant_authority_stream_source';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_demo_entitlement_authority_event_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    authority_event RECORD;
    payload JSONB;
    generation_row RECORD;
BEGIN
    IF NEW.tenant_authority_outbox_stream_key IS NULL
        OR NEW.tenant_authority_outbox_sequence IS NULL
        OR NEW.tenant_authority_event_id IS NULL
        OR NEW.tenant_authority_event_digest IS NULL THEN
        RAISE EXCEPTION 'Demo entitlement requires a distinct canonical tenant authority event'
            USING ERRCODE = '23514', CONSTRAINT = 'account_demo_entitlement_authority_required';
    END IF;
    IF NEW.tenant_authority_outbox_stream_key
        IS DISTINCT FROM ('account:auth-authority:v1:tenant/' || NEW.tenant_uuid::TEXT) THEN
        RAISE EXCEPTION 'Demo entitlement tenant authority event has the wrong scope'
            USING ERRCODE = '23514', CONSTRAINT = 'account_demo_entitlement_authority_scope';
    END IF;
    SELECT event_id, event_digest, payload INTO authority_event
        FROM account_authority_outbox_events
        WHERE outbox_stream_key = NEW.tenant_authority_outbox_stream_key
          AND outbox_sequence = NEW.tenant_authority_outbox_sequence;
    IF authority_event.event_id IS DISTINCT FROM NEW.tenant_authority_event_id
        OR authority_event.event_digest IS DISTINCT FROM NEW.tenant_authority_event_digest THEN
        RAISE EXCEPTION 'Demo entitlement authority receipt differs from its exact source event'
            USING ERRCODE = '23514', CONSTRAINT = 'account_demo_entitlement_authority_event_link';
    END IF;
    payload := convert_from(authority_event.payload, 'UTF8')::JSONB;
    SELECT generation, source_version INTO generation_row FROM account_authority_generations
        WHERE scope_kind = 'TENANT' AND tenant_uuid = NEW.tenant_uuid;
    IF payload->>'schemaVersion' IS DISTINCT FROM 'account-tenant-authority-event/v1'
        OR payload->>'eventType' IS DISTINCT FROM 'TENANT_AUTHORITY_CHANGED'
        OR payload->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
        OR payload->>'tenantAuthorityGeneration' IS DISTINCT FROM NEW.tenant_authority_generation::TEXT
        OR payload->>'tenantAuthoritySourceVersion' IS DISTINCT FROM NEW.tenant_authority_source_version::TEXT
        OR payload->>'outboxStreamKey' IS DISTINCT FROM NEW.tenant_authority_outbox_stream_key
        OR payload->>'outboxSequence' IS DISTINCT FROM NEW.tenant_authority_outbox_sequence::TEXT
        OR payload->>'eventId' IS DISTINCT FROM NEW.tenant_authority_event_id
        OR payload->>'eventDigest' IS DISTINCT FROM NEW.tenant_authority_event_digest
        OR payload->>'requestId' IS DISTINCT FROM NEW.last_request_id::TEXT
        OR payload->>'requestDigest' IS DISTINCT FROM NEW.last_request_digest
        OR payload#>>'{tenantBillingReceipt,outboxStreamKey}'
            IS DISTINCT FROM ('account:tenant-entitlement:v1:tenant/' || NEW.tenant_uuid::TEXT)
        OR payload#>>'{tenantBillingReceipt,tenantBillingSequence}'
            IS DISTINCT FROM NEW.tenant_billing_sequence::TEXT
        OR payload#>>'{tenantBillingReceipt,eventId}' IS DISTINCT FROM NEW.event_id::TEXT
        OR payload#>>'{tenantBillingReceipt,eventDigest}' IS DISTINCT FROM NEW.event_digest
        OR generation_row.generation IS DISTINCT FROM NEW.tenant_authority_generation
        OR generation_row.source_version IS DISTINCT FROM NEW.tenant_authority_source_version THEN
        RAISE EXCEPTION 'Demo entitlement differs from its actual tenant authority source event'
            USING ERRCODE = '23514', CONSTRAINT = 'account_demo_entitlement_authority_values';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_demo_entitlement_authority_receipt_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    authority_event RECORD;
    payload JSONB;
BEGIN
    IF NEW.status = 'COMMITTED' THEN
        IF NEW.tenant_authority_outbox_stream_key
            IS DISTINCT FROM ('account:auth-authority:v1:tenant/' || NEW.tenant_uuid::TEXT)
            OR NEW.tenant_authority_outbox_sequence IS NULL
            OR NEW.tenant_authority_event_id IS NULL
            OR NEW.tenant_authority_event_digest IS NULL THEN
            RAISE EXCEPTION 'Committed demo receipt requires its exact tenant authority checkpoint'
                USING ERRCODE = '23514', CONSTRAINT = 'account_demo_entitlement_authority_receipt_required';
        END IF;
        SELECT event_id, event_digest, payload INTO authority_event
            FROM account_authority_outbox_events
            WHERE outbox_stream_key = NEW.tenant_authority_outbox_stream_key
              AND outbox_sequence = NEW.tenant_authority_outbox_sequence;
        IF authority_event.event_id IS DISTINCT FROM NEW.tenant_authority_event_id
            OR authority_event.event_digest IS DISTINCT FROM NEW.tenant_authority_event_digest THEN
            RAISE EXCEPTION 'Committed demo receipt authority event differs from Account outbox'
                USING ERRCODE = '23514', CONSTRAINT = 'account_demo_entitlement_authority_receipt_event';
        END IF;
        payload := convert_from(authority_event.payload, 'UTF8')::JSONB;
        IF payload->>'requestId' IS DISTINCT FROM NEW.request_id::TEXT
            OR payload->>'requestDigest' IS DISTINCT FROM NEW.request_digest
            OR payload->>'tenantId' IS DISTINCT FROM NEW.tenant_uuid::TEXT
            OR payload->>'tenantAuthorityGeneration'
                IS DISTINCT FROM NEW.tenant_authority_generation::TEXT
            OR payload->>'tenantAuthoritySourceVersion'
                IS DISTINCT FROM NEW.tenant_authority_source_version::TEXT
            OR payload#>>'{tenantBillingReceipt,tenantBillingSequence}'
                IS DISTINCT FROM NEW.tenant_billing_sequence::TEXT
            OR payload#>>'{tenantBillingReceipt,eventId}' IS DISTINCT FROM NEW.event_id::TEXT
            OR payload#>>'{tenantBillingReceipt,eventDigest}' IS DISTINCT FROM NEW.event_digest THEN
            RAISE EXCEPTION 'Committed demo receipt differs from its canonical authority event'
                USING ERRCODE = '23514', CONSTRAINT = 'account_demo_entitlement_authority_receipt_values';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_tenant_authority_source_insert
    BEFORE INSERT ON account_tenant_authority_source_records
    FOR EACH ROW EXECUTE FUNCTION account_tenant_authority_source_insert_guard();
CREATE TRIGGER account_tenant_authority_source_update
    BEFORE UPDATE OR DELETE ON account_tenant_authority_source_records
    FOR EACH ROW EXECUTE FUNCTION account_tenant_authority_source_update_guard();
CREATE TRIGGER account_tenant_authority_source_no_truncate
    BEFORE TRUNCATE ON account_tenant_authority_source_records
    FOR EACH STATEMENT EXECUTE FUNCTION account_demo_entitlement_reject_truncate();
CREATE CONSTRAINT TRIGGER account_tenant_authority_source_consistency
    AFTER INSERT OR UPDATE ON account_tenant_authority_source_records
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_tenant_authority_source_consistency();
CREATE CONSTRAINT TRIGGER account_tenant_authority_generation_consistency
    AFTER UPDATE ON account_authority_generations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_tenant_authority_generation_consistency();
CREATE CONSTRAINT TRIGGER account_tenant_authority_stream_consistency
    AFTER INSERT OR UPDATE ON account_authority_outbox_streams
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION account_tenant_authority_stream_consistency();
CREATE TRIGGER account_demo_entitlement_authority_event
    BEFORE INSERT OR UPDATE ON account_demo_tenant_entitlements
    FOR EACH ROW EXECUTE FUNCTION account_demo_entitlement_authority_event_guard();
CREATE TRIGGER account_demo_entitlement_authority_receipt
    BEFORE INSERT OR UPDATE ON account_demo_tenant_entitlement_operations
    FOR EACH ROW EXECUTE FUNCTION account_demo_entitlement_authority_receipt_guard();
-- [jooq ignore end]
