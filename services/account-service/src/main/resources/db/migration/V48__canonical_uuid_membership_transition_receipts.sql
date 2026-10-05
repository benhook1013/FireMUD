-- Widen the existing provisional receipt family. V1 head keys and receipt bytes remain intact;
-- canonical receipts consume the same per-head sequence and global request-ID fence.
ALTER TABLE account_membership_transition_receipts
    DROP CONSTRAINT account_membership_transition_receipts_stream_fk;

ALTER TABLE account_membership_transition_receipt_stream_heads
    DROP CONSTRAINT account_membership_transition_receipt_stream_heads_pk;

ALTER TABLE account_membership_transition_receipt_stream_heads
    ADD COLUMN receipt_head_id BIGINT GENERATED ALWAYS AS IDENTITY;

ALTER TABLE account_membership_transition_receipt_stream_heads
    ADD COLUMN account_uuid UUID;

ALTER TABLE account_membership_transition_receipt_stream_heads
    ADD COLUMN tenant_uuid UUID;

ALTER TABLE account_membership_transition_receipt_stream_heads
    ADD COLUMN tenant_provenance_kind VARCHAR(32);

ALTER TABLE account_membership_transition_receipt_stream_heads
    ADD COLUMN tenant_source_operation_id UUID;

ALTER TABLE account_membership_transition_receipt_stream_heads
    ADD COLUMN tenant_provenance_digest VARCHAR(71);

ALTER TABLE account_membership_transition_receipt_stream_heads
    ALTER COLUMN tenant_id DROP NOT NULL;

ALTER TABLE account_membership_transition_receipt_stream_heads
    ADD CONSTRAINT account_membership_transition_receipt_stream_heads_pk
        PRIMARY KEY (receipt_head_id);

ALTER TABLE account_membership_transition_receipt_stream_heads
    DROP CONSTRAINT account_membership_transition_receipt_stream_heads_key_check;

ALTER TABLE account_membership_transition_receipt_stream_heads
    ADD CONSTRAINT account_membership_transition_receipt_stream_heads_numeric_pair_unique
        UNIQUE (account_id, tenant_id);

ALTER TABLE account_membership_transition_receipt_stream_heads
    ADD CONSTRAINT account_membership_transition_receipt_stream_heads_canonical_pair_unique
        UNIQUE (account_uuid, tenant_uuid);

ALTER TABLE account_membership_transition_receipt_stream_heads
    ADD CONSTRAINT account_membership_transition_receipt_stream_heads_identity_check
        CHECK (
            (account_uuid IS NULL
                AND tenant_uuid IS NULL
                AND tenant_provenance_kind IS NULL
                AND tenant_source_operation_id IS NULL
                AND tenant_provenance_digest IS NULL
                AND tenant_id IS NOT NULL
                AND tenant_id > 0
                AND receipt_stream_key =
                    'account:membership-transition-receipt:v1:membership/'
                        || account_id::TEXT || '/' || tenant_id::TEXT)
            OR
            (account_uuid IS NOT NULL
                AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_uuid IS NOT NULL
                AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_provenance_kind IS NOT NULL
                AND tenant_source_operation_id IS NOT NULL
                AND tenant_source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_provenance_digest IS NOT NULL
                AND tenant_provenance_digest ~ '^sha256:[0-9a-f]{64}$'
                AND (
                    (tenant_provenance_kind = 'APPROVED_RETAINED'
                        AND tenant_id IS NOT NULL
                        AND tenant_id > 0
                        AND receipt_stream_key =
                            'account:membership-transition-receipt:v1:membership/'
                                || account_id::TEXT || '/' || tenant_id::TEXT)
                    OR
                    (tenant_provenance_kind = 'FRESH_GAME_DESIGN'
                        AND tenant_id IS NULL
                        AND receipt_stream_key =
                            'account:membership-transition-receipt:v2:membership/'
                                || account_uuid::TEXT || '/' || tenant_uuid::TEXT)
                ))
        );

ALTER TABLE account_membership_transition_receipts
    ADD CONSTRAINT account_membership_transition_receipts_stream_fk
        FOREIGN KEY (account_id, tenant_id)
        REFERENCES account_membership_transition_receipt_stream_heads (account_id, tenant_id)
        ON DELETE RESTRICT;

ALTER TABLE account_membership_transition_receipts
    ADD COLUMN receipt_head_id BIGINT;

ALTER TABLE account_membership_transition_receipts
    ADD COLUMN receipt_version SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE account_membership_transition_receipts
    ADD COLUMN account_uuid UUID;

ALTER TABLE account_membership_transition_receipts
    ADD COLUMN tenant_uuid UUID;

ALTER TABLE account_membership_transition_receipts
    ADD COLUMN tenant_provenance_kind VARCHAR(32);

ALTER TABLE account_membership_transition_receipts
    ADD COLUMN tenant_source_operation_id UUID;

ALTER TABLE account_membership_transition_receipts
    ADD COLUMN tenant_provenance_digest VARCHAR(71);

ALTER TABLE account_membership_transition_receipts
    ALTER COLUMN tenant_id DROP NOT NULL;

-- V41 protects retained receipt bytes from runtime mutation. Disable only that row guard for
-- this migration-owned key backfill, then restore it before the migration continues.
-- [jooq ignore start]
ALTER TABLE account_membership_transition_receipts
    DISABLE TRIGGER account_membership_transition_receipts_append_only;
-- [jooq ignore stop]

UPDATE account_membership_transition_receipts receipt
SET receipt_head_id = (
    SELECT head.receipt_head_id
    FROM account_membership_transition_receipt_stream_heads head
    WHERE receipt.account_id = head.account_id
      AND receipt.tenant_id = head.tenant_id
)
WHERE EXISTS (
    SELECT 1
    FROM account_membership_transition_receipt_stream_heads head
    WHERE receipt.account_id = head.account_id
      AND receipt.tenant_id = head.tenant_id
);

-- [jooq ignore start]
ALTER TABLE account_membership_transition_receipts
    ENABLE TRIGGER account_membership_transition_receipts_append_only;
-- [jooq ignore stop]

ALTER TABLE account_membership_transition_receipts
    ALTER COLUMN receipt_head_id SET NOT NULL;

ALTER TABLE account_membership_transition_receipts
    DROP CONSTRAINT account_membership_transition_receipts_key_check;

ALTER TABLE account_membership_transition_receipts
    ADD CONSTRAINT account_membership_transition_receipts_head_sequence_unique
        UNIQUE (receipt_head_id, receipt_sequence);

ALTER TABLE account_membership_transition_receipts
    ADD CONSTRAINT account_membership_transition_receipts_head_fk
        FOREIGN KEY (receipt_head_id)
        REFERENCES account_membership_transition_receipt_stream_heads (receipt_head_id)
        ON DELETE RESTRICT;

ALTER TABLE account_membership_transition_receipts
    ADD CONSTRAINT account_membership_transition_receipts_version_check
        CHECK (receipt_version IN (1, 2));

ALTER TABLE account_membership_transition_receipts
    ADD CONSTRAINT account_membership_transition_receipts_key_check
        CHECK (
            (receipt_version = 1
                AND account_uuid IS NULL
                AND tenant_uuid IS NULL
                AND tenant_provenance_kind IS NULL
                AND tenant_source_operation_id IS NULL
                AND tenant_provenance_digest IS NULL
                AND tenant_id IS NOT NULL
                AND receipt_stream_key =
                    'account:membership-transition-receipt:v1:membership/'
                        || account_id::TEXT || '/' || tenant_id::TEXT)
            OR
            (receipt_version = 2
                AND account_uuid IS NOT NULL
                AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_uuid IS NOT NULL
                AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_provenance_kind IS NOT NULL
                AND tenant_source_operation_id IS NOT NULL
                AND tenant_source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_provenance_digest IS NOT NULL
                AND tenant_provenance_digest ~ '^sha256:[0-9a-f]{64}$'
                AND (
                    (tenant_provenance_kind = 'APPROVED_RETAINED'
                        AND tenant_id IS NOT NULL
                        AND tenant_id > 0)
                    OR
                    (tenant_provenance_kind = 'FRESH_GAME_DESIGN'
                        AND tenant_id IS NULL)
                )
                AND receipt_stream_key =
                    'account:membership-transition-receipt:v2:membership/'
                        || account_uuid::TEXT || '/' || tenant_uuid::TEXT)
        );

CREATE INDEX idx_account_membership_transition_receipts_canonical_latest
    ON account_membership_transition_receipts
        (account_uuid, tenant_uuid, receipt_sequence DESC)
    WHERE receipt_version = 2;

-- [jooq ignore start]
CREATE FUNCTION validate_account_membership_receipt_head_identity() RETURNS trigger AS $$
DECLARE
    persisted_account_uuid UUID;
    source_matches BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.receipt_head_id IS DISTINCT FROM OLD.receipt_head_id
            OR NEW.account_id IS DISTINCT FROM OLD.account_id
            OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
            OR NEW.receipt_stream_key IS DISTINCT FROM OLD.receipt_stream_key THEN
            RAISE EXCEPTION 'Account membership receipt head storage identity is immutable'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.last_receipt_sequence < OLD.last_receipt_sequence
            OR (NEW.last_receipt_sequence <> OLD.last_receipt_sequence
                AND (OLD.last_receipt_sequence = 9223372036854775807
                    OR NEW.last_receipt_sequence <> OLD.last_receipt_sequence + 1)) THEN
            RAISE EXCEPTION 'Account membership receipt sequence cannot reset, skip, or wrap'
                USING ERRCODE = 'check_violation';
        END IF;
        IF OLD.account_uuid IS NOT NULL AND (
            NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
            OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
            OR NEW.tenant_provenance_kind IS DISTINCT FROM OLD.tenant_provenance_kind
            OR NEW.tenant_source_operation_id IS DISTINCT FROM OLD.tenant_source_operation_id
            OR NEW.tenant_provenance_digest IS DISTINCT FROM OLD.tenant_provenance_digest) THEN
            RAISE EXCEPTION 'Canonical Account membership receipt head identity is immutable'
                USING ERRCODE = 'check_violation';
        END IF;
        IF OLD.account_uuid IS NULL AND NEW.account_uuid IS NOT NULL
            AND NEW.tenant_provenance_kind <> 'APPROVED_RETAINED' THEN
            RAISE EXCEPTION 'Only an approved retained receipt head may be bound in place'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF NEW.account_uuid IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT account.account_uuid
    INTO persisted_account_uuid
    FROM accounts account
    WHERE account.id = NEW.account_id;
    IF persisted_account_uuid IS DISTINCT FROM NEW.account_uuid THEN
        RAISE EXCEPTION 'Account membership receipt head Account UUID is not persisted'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.tenant_provenance_kind = 'APPROVED_RETAINED' THEN
        SELECT EXISTS (
            SELECT 1
            FROM account_approved_legacy_tenant_associations retained
            JOIN account_canonical_tenant_identity_claims claim
              ON claim.canonical_tenant_id = retained.canonical_tenant_id
             AND claim.identity_kind = retained.identity_kind
             AND claim.source_operation_id = retained.operation_id
             AND claim.source_account_legacy_tenant_id = retained.legacy_tenant_id
             AND claim.source_target_namespace = retained.target_namespace
             AND claim.source_creation_request_id IS NULL
             AND claim.source_request_digest IS NULL
             AND claim.source_game_row_id = retained.source_game_row_id
             AND claim.source_game_tenant_key = retained.source_legacy_game_tenant_id
             AND claim.source_provenance_kind IS NULL
             AND claim.source_evidence_digest = retained.account_evidence_digest
             AND claim.source_manifest_digest = retained.manifest_digest
            WHERE retained.legacy_tenant_id = NEW.tenant_id
              AND retained.canonical_tenant_id = NEW.tenant_uuid
              AND retained.operation_id = NEW.tenant_source_operation_id
              AND retained.manifest_digest = NEW.tenant_provenance_digest
        ) INTO source_matches;
    ELSIF NEW.tenant_provenance_kind = 'FRESH_GAME_DESIGN' THEN
        SELECT EXISTS (
            SELECT 1
            FROM account_fresh_tenant_identity_associations fresh
            JOIN account_canonical_tenant_identity_claims claim
              ON claim.canonical_tenant_id = fresh.canonical_tenant_id
             AND claim.identity_kind = fresh.identity_kind
             AND claim.source_operation_id = fresh.operation_id
             AND claim.source_target_namespace = fresh.target_namespace
             AND claim.source_creation_request_id = fresh.creation_request_id
             AND claim.source_request_digest = fresh.request_digest
             AND claim.source_game_row_id = fresh.source_game_row_id
             AND claim.source_game_tenant_key = fresh.source_game_tenant_key
             AND claim.source_provenance_kind = fresh.provenance_kind
             AND claim.source_evidence_digest = fresh.evidence_digest
             AND claim.source_account_legacy_tenant_id IS NULL
             AND claim.source_manifest_digest IS NULL
            WHERE fresh.canonical_tenant_id = NEW.tenant_uuid
              AND fresh.operation_id = NEW.tenant_source_operation_id
              AND fresh.evidence_digest = NEW.tenant_provenance_digest
        ) INTO source_matches;
    END IF;

    IF NOT source_matches THEN
        RAISE EXCEPTION 'Account membership receipt head has no exact immutable tenant source'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION validate_account_membership_transition_receipt_head() RETURNS trigger AS $$
DECLARE
    head_row account_membership_transition_receipt_stream_heads%ROWTYPE;
BEGIN
    SELECT * INTO head_row
    FROM account_membership_transition_receipt_stream_heads head
    WHERE head.receipt_head_id = NEW.receipt_head_id;
    IF NOT FOUND
        OR head_row.account_id IS DISTINCT FROM NEW.account_id
        OR head_row.tenant_id IS DISTINCT FROM NEW.tenant_id
        OR NEW.receipt_sequence > head_row.last_receipt_sequence THEN
        RAISE EXCEPTION 'Account membership transition receipt does not match its shared head'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.receipt_version = 1 THEN
        IF head_row.account_uuid IS NOT NULL
            AND head_row.tenant_provenance_kind <> 'APPROVED_RETAINED' THEN
            RAISE EXCEPTION 'V1 receipt cannot use a canonical-only Account membership head'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.receipt_stream_key IS DISTINCT FROM head_row.receipt_stream_key THEN
            RAISE EXCEPTION 'V1 Account membership receipt changed its retained stream key'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF head_row.account_uuid IS DISTINCT FROM NEW.account_uuid
        OR head_row.tenant_uuid IS DISTINCT FROM NEW.tenant_uuid
        OR head_row.tenant_provenance_kind IS DISTINCT FROM NEW.tenant_provenance_kind
        OR head_row.tenant_source_operation_id IS DISTINCT FROM NEW.tenant_source_operation_id
        OR head_row.tenant_provenance_digest IS DISTINCT FROM NEW.tenant_provenance_digest THEN
        RAISE EXCEPTION 'Canonical Account membership receipt source differs from its head'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION reject_account_membership_transition_receipt_v2_update() RETURNS trigger AS $$
BEGIN
    IF OLD.receipt_version = 2 OR NEW.receipt_version = 2
        OR OLD.receipt_version IS DISTINCT FROM NEW.receipt_version THEN
        RAISE EXCEPTION 'Canonical Account membership transition receipts are immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_membership_receipt_head_identity_guard
    BEFORE INSERT OR UPDATE ON account_membership_transition_receipt_stream_heads
    FOR EACH ROW EXECUTE FUNCTION validate_account_membership_receipt_head_identity();

CREATE TRIGGER account_membership_transition_receipt_head_guard
    BEFORE INSERT ON account_membership_transition_receipts
    FOR EACH ROW EXECUTE FUNCTION validate_account_membership_transition_receipt_head();

CREATE TRIGGER account_membership_transition_receipt_v2_immutable
    BEFORE UPDATE ON account_membership_transition_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_account_membership_transition_receipt_v2_update();
-- [jooq ignore stop]
