-- Purpose-bound ORIGINAL-NEW Account delegation lineage and protected rebind handle.
-- The existing LOGIN issuance rows, their unscoped claim invariant, and their retained bytes are
-- not rewritten. This row family has one fresh generation-1 lineage per exact original NEW lease.
CREATE TABLE account_original_new_bound_gameplay_issuances (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    account_uuid UUID NOT NULL REFERENCES accounts (account_uuid) ON DELETE RESTRICT,
    caller_workload VARCHAR(256) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    lease_id UUID NOT NULL UNIQUE,
    lease_fence BIGINT NOT NULL,
    binding_decision_id UUID NOT NULL UNIQUE,
    binding_scope_sha256 VARCHAR(64) NOT NULL UNIQUE,
    original_lease_evidence_sha256 VARCHAR(64) NOT NULL,
    original_ack_receipt_xid VARCHAR(32) NOT NULL,
    original_ack_evidence_sha256 VARCHAR(64) NOT NULL,
    binding_scope_canonical_bytes BYTEA NOT NULL,
    authority_tuple_canonical_bytes BYTEA NOT NULL,
    membership_baseline_canonical_bytes BYTEA NOT NULL,
    membership_version_canonical_bytes BYTEA NOT NULL,
    outbox_checkpoints_canonical_bytes BYTEA NOT NULL,
    issuance_fence BIGINT NOT NULL,
    token_jti UUID NOT NULL UNIQUE,
    token_generation BIGINT NOT NULL DEFAULT 1,
    issued_at_epoch_second BIGINT NOT NULL,
    not_before_epoch_second BIGINT NOT NULL,
    expires_at_epoch_second BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    token_hash VARCHAR(64),
    signer_kid VARCHAR(64),
    signer_generation VARCHAR(19),
    pending_registry_record_bytes BYTEA,
    rebind_handle_sha256 VARCHAR(64) UNIQUE,
    encrypted_result_envelope_sha256 VARCHAR(64),
    encrypted_result_envelope_bytes BYTEA,
    committed_registry_receipt_sha256 VARCHAR(64),
    confirmed_admission_at_ms BIGINT,
    confirmed_continuity_expires_at_ms BIGINT,
    confirmation_readback_sha256 VARCHAR(64),
    handle_consumed_at_ms BIGINT,
    handle_consumption_request_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    CONSTRAINT account_original_new_bound_identity_check CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND operation_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND request_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND account_uuid::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND lease_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND binding_decision_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND token_jti::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND operation_id <> token_jti),
    CONSTRAINT account_original_new_bound_caller_check CHECK (
        caller_workload ~ '^spiffe://firemud/ns/[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?/sa/game-session-service$'
        AND request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_original_new_bound_fence_check CHECK (
        lease_fence > 0 AND issuance_fence > 0 AND token_generation = 1),
    CONSTRAINT account_original_new_bound_source_check CHECK (
        binding_scope_sha256 ~ '^[0-9a-f]{64}$'
        AND binding_scope_sha256 = encode(sha256(binding_scope_canonical_bytes), 'hex')
        AND
        original_lease_evidence_sha256 ~ '^[0-9a-f]{64}$'
        AND original_ack_evidence_sha256 ~ '^[0-9a-f]{64}$'
        AND original_ack_receipt_xid ~ '^[1-9][0-9]{0,19}$'
        AND octet_length(binding_scope_canonical_bytes) BETWEEN 1 AND 4096
        AND jsonb_typeof(convert_from(binding_scope_canonical_bytes, 'UTF8')::jsonb) = 'object'
        AND octet_length(authority_tuple_canonical_bytes) BETWEEN 1 AND 4096
        AND jsonb_typeof(convert_from(authority_tuple_canonical_bytes, 'UTF8')::jsonb) = 'object'
        AND octet_length(membership_baseline_canonical_bytes) BETWEEN 1 AND 4096
        AND jsonb_typeof(convert_from(membership_baseline_canonical_bytes, 'UTF8')::jsonb) = 'object'
        AND octet_length(membership_version_canonical_bytes) BETWEEN 1 AND 4096
        AND jsonb_typeof(convert_from(membership_version_canonical_bytes, 'UTF8')::jsonb) = 'object'
        AND convert_from(membership_version_canonical_bytes, 'UTF8') <> '{}'
        AND octet_length(outbox_checkpoints_canonical_bytes) BETWEEN 1 AND 8192
        AND jsonb_typeof(convert_from(outbox_checkpoints_canonical_bytes, 'UTF8')::jsonb) = 'array'),
    CONSTRAINT account_original_new_bound_time_check CHECK (
        issued_at_epoch_second > 0 AND not_before_epoch_second > 0
        AND not_before_epoch_second <= issued_at_epoch_second
        AND expires_at_epoch_second > issued_at_epoch_second
        AND expires_at_epoch_second - issued_at_epoch_second <= 300),
    CONSTRAINT account_original_new_bound_state_check CHECK (status IN ('PENDING', 'COMMITTED')),
    CONSTRAINT account_original_new_bound_candidate_check CHECK (
        (token_hash IS NULL AND signer_kid IS NULL AND signer_generation IS NULL
            AND pending_registry_record_bytes IS NULL AND rebind_handle_sha256 IS NULL
            AND encrypted_result_envelope_sha256 IS NULL AND encrypted_result_envelope_bytes IS NULL
            AND committed_registry_receipt_sha256 IS NULL AND committed_at IS NULL)
        OR (token_hash IS NOT NULL AND token_hash ~ '^[0-9a-f]{64}$'
            AND signer_kid IS NOT NULL AND signer_kid ~ '^[A-Za-z0-9_-]{1,64}$'
            AND signer_generation IS NOT NULL AND signer_generation ~ '^[1-9][0-9]{0,18}$'
            AND pending_registry_record_bytes IS NOT NULL
            AND octet_length(pending_registry_record_bytes) BETWEEN 1 AND 16384
            AND rebind_handle_sha256 IS NOT NULL AND rebind_handle_sha256 ~ '^[0-9a-f]{64}$'
            AND encrypted_result_envelope_sha256 IS NOT NULL
            AND encrypted_result_envelope_sha256 ~ '^[0-9a-f]{64}$'
            AND encrypted_result_envelope_bytes IS NOT NULL
            AND octet_length(encrypted_result_envelope_bytes) BETWEEN 1 AND 65610
            AND ((status = 'PENDING' AND committed_registry_receipt_sha256 IS NULL AND committed_at IS NULL)
                OR (status = 'COMMITTED' AND committed_registry_receipt_sha256 IS NOT NULL
                    AND committed_registry_receipt_sha256 ~ '^[0-9a-f]{64}$'
                    AND committed_at IS NOT NULL)))),
    CONSTRAINT account_original_new_bound_confirmation_check CHECK (
        (confirmed_admission_at_ms IS NULL AND confirmed_continuity_expires_at_ms IS NULL
            AND confirmation_readback_sha256 IS NULL)
        OR (confirmed_admission_at_ms IS NOT NULL AND confirmed_admission_at_ms > 0
            AND status = 'COMMITTED' AND token_hash IS NOT NULL
            AND confirmed_continuity_expires_at_ms IS NOT NULL
            AND confirmed_continuity_expires_at_ms > confirmed_admission_at_ms
            AND confirmation_readback_sha256 IS NOT NULL
            AND confirmation_readback_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT account_original_new_bound_consumption_check CHECK (
        (handle_consumed_at_ms IS NULL AND handle_consumption_request_id IS NULL)
        OR (handle_consumed_at_ms IS NOT NULL AND handle_consumed_at_ms > 0
            AND status = 'COMMITTED' AND confirmed_admission_at_ms IS NOT NULL
            AND handle_consumed_at_ms >= confirmed_admission_at_ms
            AND confirmed_continuity_expires_at_ms IS NOT NULL
            AND handle_consumed_at_ms < confirmed_continuity_expires_at_ms
            AND handle_consumption_request_id IS NOT NULL
            AND handle_consumption_request_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
);

-- A row is admitted only when the exact original lease and its independently committed ACK receipt
-- already exist. Their source bytes remain untouched and are compared by digest here and in Java.
-- [jooq ignore start]
CREATE FUNCTION account_original_new_bound_gameplay_issuance_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original RECORD;
    receipt RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Original-new gameplay issuance evidence cannot be deleted' USING ERRCODE = '23514';
    END IF;
    PERFORM account_uuid FROM accounts WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Original-new Account source is absent' USING ERRCODE = '23514';
    END IF;
    SELECT request_id, account_uuid, lease_id, lease_fence, binding_decision_id,
           evidence_sha256, evidence_json, expires_at_ms, status
      INTO original FROM account_gameplay_admission_lease_operations
      WHERE request_id = NEW.request_id AND lease_id = NEW.lease_id FOR SHARE;
    SELECT request_id, account_uuid, lease_id, lease_fence, binding_decision_id,
           evidence_sha256, receipt_xid
      INTO receipt FROM account_gameplay_admission_original_commit_ack_receipts
      WHERE request_id = NEW.request_id FOR SHARE;
    IF original.request_id IS NULL OR original.status <> 'COMMITTED'
        OR original.account_uuid <> NEW.account_uuid OR original.lease_id <> NEW.lease_id
        OR original.lease_fence <> NEW.lease_fence
        OR original.binding_decision_id <> NEW.binding_decision_id
        OR original.evidence_sha256 <> NEW.original_lease_evidence_sha256
        OR original.evidence_json::jsonb -> 'bindingScope'
            IS DISTINCT FROM convert_from(NEW.binding_scope_canonical_bytes, 'UTF8')::jsonb
        OR original.evidence_json::jsonb -> 'authorityTuple'
            IS DISTINCT FROM convert_from(NEW.authority_tuple_canonical_bytes, 'UTF8')::jsonb
        OR original.evidence_json::jsonb -> 'membershipBaseline'
            IS DISTINCT FROM convert_from(NEW.membership_baseline_canonical_bytes, 'UTF8')::jsonb
        OR original.evidence_json::jsonb -> 'membershipBaseline' -> 'membershipVersion'
            IS DISTINCT FROM convert_from(NEW.membership_version_canonical_bytes, 'UTF8')::jsonb
        OR original.evidence_json::jsonb -> 'outboxCheckpoints'
            IS DISTINCT FROM convert_from(NEW.outbox_checkpoints_canonical_bytes, 'UTF8')::jsonb
        OR original.evidence_json::jsonb ->> 'issuanceFence' IS DISTINCT FROM NEW.issuance_fence::text
        OR receipt.request_id IS NULL OR receipt.account_uuid <> NEW.account_uuid
        OR receipt.lease_id <> NEW.lease_id OR receipt.lease_fence <> NEW.lease_fence
        OR receipt.binding_decision_id <> NEW.binding_decision_id
        OR receipt.evidence_sha256 <> NEW.original_ack_evidence_sha256
        OR receipt.receipt_xid <> NEW.original_ack_receipt_xid THEN
        RAISE EXCEPTION 'Original-new credential requires the exact committed lease and ACK'
            USING ERRCODE = '23514', CONSTRAINT = 'account_original_new_bound_original_commit_required';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.token_hash IS NOT NULL
            OR NEW.signer_kid IS NOT NULL OR NEW.signer_generation IS NOT NULL
            OR NEW.pending_registry_record_bytes IS NOT NULL OR NEW.rebind_handle_sha256 IS NOT NULL
            OR NEW.encrypted_result_envelope_sha256 IS NOT NULL
            OR NEW.encrypted_result_envelope_bytes IS NOT NULL
            OR NEW.committed_registry_receipt_sha256 IS NOT NULL OR NEW.committed_at IS NOT NULL
            OR NEW.confirmed_admission_at_ms IS NOT NULL
            OR NEW.confirmed_continuity_expires_at_ms IS NOT NULL
            OR NEW.confirmation_readback_sha256 IS NOT NULL
            OR NEW.handle_consumed_at_ms IS NOT NULL
            OR NEW.handle_consumption_request_id IS NOT NULL THEN
            RAISE EXCEPTION 'Original-new lineage must start as unbound PENDING'
                USING ERRCODE = '23514', CONSTRAINT = 'account_original_new_bound_initial_pending_only';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.operation_id, NEW.request_id, NEW.account_uuid, NEW.caller_workload,
            NEW.request_digest, NEW.lease_id, NEW.lease_fence, NEW.binding_decision_id,
            NEW.binding_scope_sha256,
            NEW.original_lease_evidence_sha256, NEW.original_ack_receipt_xid,
            NEW.original_ack_evidence_sha256, NEW.binding_scope_canonical_bytes,
            NEW.authority_tuple_canonical_bytes, NEW.membership_baseline_canonical_bytes,
            NEW.membership_version_canonical_bytes,
            NEW.outbox_checkpoints_canonical_bytes, NEW.issuance_fence, NEW.token_jti,
            NEW.token_generation, NEW.issued_at_epoch_second, NEW.not_before_epoch_second,
            NEW.expires_at_epoch_second, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.operation_id, OLD.request_id, OLD.account_uuid, OLD.caller_workload,
            OLD.request_digest, OLD.lease_id, OLD.lease_fence, OLD.binding_decision_id,
            OLD.binding_scope_sha256,
            OLD.original_lease_evidence_sha256, OLD.original_ack_receipt_xid,
            OLD.original_ack_evidence_sha256, OLD.binding_scope_canonical_bytes,
            OLD.authority_tuple_canonical_bytes, OLD.membership_baseline_canonical_bytes,
            OLD.membership_version_canonical_bytes,
            OLD.outbox_checkpoints_canonical_bytes, OLD.issuance_fence, OLD.token_jti,
            OLD.token_generation, OLD.issued_at_epoch_second, OLD.not_before_epoch_second,
            OLD.expires_at_epoch_second, OLD.created_at) THEN
        RAISE EXCEPTION 'Original-new lineage identity and frozen claims are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_original_new_bound_identity_immutable';
    END IF;
    IF OLD.token_hash IS NULL THEN
        IF NEW.token_hash IS NULL OR NEW.signer_kid IS NULL OR NEW.signer_generation IS NULL
            OR NEW.pending_registry_record_bytes IS NULL OR NEW.rebind_handle_sha256 IS NULL
            OR NEW.encrypted_result_envelope_sha256 IS NULL
            OR NEW.encrypted_result_envelope_bytes IS NULL
            OR NEW.status <> 'PENDING' OR NEW.committed_registry_receipt_sha256 IS NOT NULL
            OR NEW.committed_at IS NOT NULL THEN
            RAISE EXCEPTION 'Original-new signed identity must bind once before commit'
                USING ERRCODE = '23514', CONSTRAINT = 'account_original_new_bound_candidate_one_way';
        END IF;
    ELSIF ROW(NEW.token_hash, NEW.signer_kid, NEW.signer_generation,
            NEW.pending_registry_record_bytes, NEW.rebind_handle_sha256,
            NEW.encrypted_result_envelope_sha256, NEW.encrypted_result_envelope_bytes)
        IS DISTINCT FROM ROW(OLD.token_hash, OLD.signer_kid, OLD.signer_generation,
            OLD.pending_registry_record_bytes, OLD.rebind_handle_sha256,
            OLD.encrypted_result_envelope_sha256, OLD.encrypted_result_envelope_bytes) THEN
        RAISE EXCEPTION 'Original-new signed identity and encrypted result are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_original_new_bound_candidate_immutable';
    END IF;
    IF OLD.status = 'PENDING' AND NEW.status = 'COMMITTED'
        AND OLD.token_hash IS NOT NULL AND NEW.committed_registry_receipt_sha256 ~ '^[0-9a-f]{64}$'
        AND NEW.committed_at IS NOT NULL
        AND ROW(NEW.confirmed_admission_at_ms, NEW.confirmed_continuity_expires_at_ms,
                NEW.confirmation_readback_sha256, NEW.handle_consumed_at_ms,
                NEW.handle_consumption_request_id)
            IS NOT DISTINCT FROM ROW(OLD.confirmed_admission_at_ms, OLD.confirmed_continuity_expires_at_ms,
                OLD.confirmation_readback_sha256, OLD.handle_consumed_at_ms,
                OLD.handle_consumption_request_id) THEN
        RETURN NEW;
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
        OR NEW.committed_registry_receipt_sha256 IS DISTINCT FROM OLD.committed_registry_receipt_sha256
        OR NEW.committed_at IS DISTINCT FROM OLD.committed_at THEN
        RAISE EXCEPTION 'Original-new issuance lifecycle transition conflicts'
            USING ERRCODE = '23514', CONSTRAINT = 'account_original_new_bound_commit_once';
    END IF;
    IF OLD.confirmed_admission_at_ms IS NOT NULL
        AND ROW(NEW.confirmed_admission_at_ms, NEW.confirmed_continuity_expires_at_ms,
                NEW.confirmation_readback_sha256)
            IS DISTINCT FROM ROW(OLD.confirmed_admission_at_ms, OLD.confirmed_continuity_expires_at_ms,
                OLD.confirmation_readback_sha256) THEN
        RAISE EXCEPTION 'Original-new final-admission confirmation is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_original_new_bound_confirmation_immutable';
    END IF;
    IF OLD.confirmed_admission_at_ms IS NULL AND NEW.confirmed_admission_at_ms IS NOT NULL
        AND (OLD.status <> 'COMMITTED' OR NEW.status <> 'COMMITTED'
            OR OLD.token_hash IS NULL OR OLD.rebind_handle_sha256 IS NULL
            OR OLD.encrypted_result_envelope_bytes IS NULL) THEN
        RAISE EXCEPTION 'Original-new confirmation requires the committed protected result'
            USING ERRCODE = '23514', CONSTRAINT = 'account_original_new_bound_confirmation_after_commit';
    END IF;
    IF OLD.handle_consumed_at_ms IS NOT NULL
        AND ROW(NEW.handle_consumed_at_ms, NEW.handle_consumption_request_id)
            IS DISTINCT FROM ROW(OLD.handle_consumed_at_ms, OLD.handle_consumption_request_id) THEN
        RAISE EXCEPTION 'Original-new rebind handle consumption is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'account_original_new_bound_consumption_immutable';
    END IF;
    IF OLD.handle_consumed_at_ms IS NULL AND NEW.handle_consumed_at_ms IS NOT NULL
        AND (OLD.status <> 'COMMITTED' OR NEW.status <> 'COMMITTED'
            OR OLD.confirmed_admission_at_ms IS NULL
            OR OLD.confirmed_continuity_expires_at_ms IS NULL
            OR NEW.handle_consumed_at_ms < OLD.confirmed_admission_at_ms
            OR NEW.handle_consumed_at_ms >= OLD.confirmed_continuity_expires_at_ms) THEN
        RAISE EXCEPTION 'Original-new handle consumption requires prior current admission confirmation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_original_new_bound_consumption_after_confirmation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_original_new_bound_gameplay_issuance_guard
BEFORE INSERT OR UPDATE OR DELETE ON account_original_new_bound_gameplay_issuances
FOR EACH ROW EXECUTE FUNCTION account_original_new_bound_gameplay_issuance_guard();

CREATE FUNCTION account_original_new_bound_gameplay_issuance_no_truncate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM account_original_new_bound_gameplay_issuances) THEN
        RAISE EXCEPTION 'Original-new gameplay issuance evidence cannot be truncated' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER account_original_new_bound_gameplay_issuance_no_truncate
BEFORE TRUNCATE ON account_original_new_bound_gameplay_issuances
FOR EACH STATEMENT EXECUTE FUNCTION account_original_new_bound_gameplay_issuance_no_truncate();
-- [jooq ignore stop]

-- Extend the existing token-identity fence with an explicit purpose arm. Existing rows are LOGIN
-- and retain their old source predicate; ORIGINAL_NEW fences require the separate immutable
-- Account-owned ORIGINAL-NEW issuance row above to be COMMITTED.
-- PostgreSQL supplies this name for the original inline foreign key; the jOOQ H2
-- interpreter assigns a different generated name. The real migration must remove it.
-- [jooq ignore start]
ALTER TABLE account_gameplay_token_identity_fences
    DROP CONSTRAINT account_gameplay_token_identity_fences_operation_id_fkey;
-- [jooq ignore stop]
ALTER TABLE account_gameplay_token_identity_fences
    ADD COLUMN source_kind VARCHAR(16) NOT NULL DEFAULT 'LOGIN';
ALTER TABLE account_gameplay_token_identity_fences
    ADD CONSTRAINT account_gameplay_token_identity_source_kind_check
        CHECK (source_kind IN ('LOGIN', 'ORIGINAL_NEW'));

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_gameplay_token_identity_fence_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Account token fence retention cannot be bypassed by deletion' USING ERRCODE = '23514';
    END IF;
    PERFORM account_uuid FROM accounts WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account token fence Account source absent' USING ERRCODE = '23514';
    END IF;
    IF NEW.source_kind = 'LOGIN' THEN
        PERFORM operation_id FROM account_gameplay_delegation_issuance_operations
            WHERE operation_id = NEW.operation_id AND account_uuid = NEW.account_uuid
                AND request_id = NEW.issuance_request_id AND token_hash = NEW.token_hash
                AND token_jti = NEW.token_jti AND not_before_epoch_second = NEW.not_before_epoch_second
                AND token_generation = NEW.token_generation AND issuance_fence = NEW.issuance_fence
                AND status = 'COMMITTED' FOR SHARE;
    ELSIF NEW.source_kind = 'ORIGINAL_NEW' THEN
        PERFORM operation_id FROM account_original_new_bound_gameplay_issuances
            WHERE operation_id = NEW.operation_id AND account_uuid = NEW.account_uuid
                AND request_id = NEW.issuance_request_id AND token_hash = NEW.token_hash
                AND token_jti = NEW.token_jti AND not_before_epoch_second = NEW.not_before_epoch_second
                AND token_generation = NEW.token_generation AND issuance_fence = NEW.issuance_fence
                AND status = 'COMMITTED' FOR SHARE;
    ELSE
        RAISE EXCEPTION 'Account token fence purpose is unsupported' USING ERRCODE = '23514';
    END IF;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account token fence requires exact COMMITTED purpose-bound issuance' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'ACTIVE' OR NEW.token_identity_fence <> 1
            OR NEW.revocation_request_id IS NOT NULL OR NEW.revocation_digest IS NOT NULL THEN
            RAISE EXCEPTION 'Account token fence must begin ACTIVE' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.operation_id, NEW.account_uuid, NEW.issuance_request_id, NEW.token_hash,
            NEW.token_jti, NEW.not_before_epoch_second, NEW.token_generation, NEW.issuance_fence,
            NEW.source_kind, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.operation_id, OLD.account_uuid, OLD.issuance_request_id, OLD.token_hash,
            OLD.token_jti, OLD.not_before_epoch_second, OLD.token_generation, OLD.issuance_fence,
            OLD.source_kind, OLD.created_at) THEN
        RAISE EXCEPTION 'Account token fence identity is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' AND NEW.token_identity_fence = 2 THEN
        RETURN NEW;
    END IF;
    -- Storage only: there is no completion API until the real Gateway deny/clear owner exists.
    IF OLD.state = 'PENDING' AND NEW.state = 'COMMITTED' AND NEW.token_identity_fence = 3
        AND NEW.revocation_request_id = OLD.revocation_request_id
        AND NEW.revocation_digest = OLD.revocation_digest THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Account token fence transition or revocation identity conflicts' USING ERRCODE = '23514';
END;
$$;
-- [jooq ignore stop]
