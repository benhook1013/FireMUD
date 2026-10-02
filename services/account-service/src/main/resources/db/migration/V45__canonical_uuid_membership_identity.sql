-- Preserve existing numeric membership rows as unbridged history. Only an
-- Account-owned canonical writer may attach one of the immutable tenant sources.
ALTER TABLE account_tenant_membership
    ADD COLUMN tenant_uuid UUID,
    ADD COLUMN tenant_provenance_kind VARCHAR(32) NOT NULL DEFAULT 'UNBRIDGED_RETAINED',
    ADD COLUMN tenant_source_operation_id UUID,
    ADD COLUMN tenant_provenance_digest VARCHAR(71);

ALTER TABLE account_tenant_membership
    ALTER COLUMN tenant_id DROP NOT NULL;

ALTER TABLE account_tenant_membership
    ADD CONSTRAINT account_tenant_membership_tenant_identity_check
        CHECK (
            (tenant_provenance_kind = 'UNBRIDGED_RETAINED'
                AND tenant_id IS NOT NULL
                AND tenant_uuid IS NULL
                AND tenant_source_operation_id IS NULL
                AND tenant_provenance_digest IS NULL)
            OR
            (tenant_provenance_kind = 'APPROVED_RETAINED'
                AND tenant_id IS NOT NULL
                AND tenant_id > 0
                AND tenant_uuid IS NOT NULL
                AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_source_operation_id IS NOT NULL
                AND tenant_source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_provenance_digest IS NOT NULL
                AND tenant_provenance_digest ~ '^sha256:[0-9a-f]{64}$')
            OR
            (tenant_provenance_kind = 'FRESH_GAME_DESIGN'
                AND tenant_id IS NULL
                AND tenant_uuid IS NOT NULL
                AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_source_operation_id IS NOT NULL
                AND tenant_source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_provenance_digest IS NOT NULL
                AND tenant_provenance_digest ~ '^sha256:[0-9a-f]{64}$')
        );

CREATE UNIQUE INDEX account_tenant_membership_canonical_identity_uq
    ON account_tenant_membership (account_id, tenant_uuid)
    WHERE tenant_uuid IS NOT NULL;

-- The discriminator and source tuple are durable membership identity. Existing
-- rows may be bound exactly once to their authenticated retained association;
-- fresh identity is created only on a new UUID-only row.
-- [jooq ignore start]
CREATE FUNCTION validate_account_membership_tenant_identity() RETURNS trigger AS $$
DECLARE
    source_matches BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.account_id IS DISTINCT FROM OLD.account_id
            OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
            RAISE EXCEPTION 'Account membership storage identity is immutable'
                USING ERRCODE = 'check_violation';
        END IF;

        IF OLD.tenant_provenance_kind <> 'UNBRIDGED_RETAINED' THEN
            IF NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
                OR NEW.tenant_provenance_kind IS DISTINCT FROM OLD.tenant_provenance_kind
                OR NEW.tenant_source_operation_id IS DISTINCT FROM OLD.tenant_source_operation_id
                OR NEW.tenant_provenance_digest IS DISTINCT FROM OLD.tenant_provenance_digest THEN
                RAISE EXCEPTION 'Canonical Account membership tenant identity is immutable'
                    USING ERRCODE = 'check_violation';
            END IF;
            RETURN NEW;
        END IF;

        IF NEW.tenant_provenance_kind = 'UNBRIDGED_RETAINED'
            AND NEW.tenant_uuid IS NULL
            AND NEW.tenant_source_operation_id IS NULL
            AND NEW.tenant_provenance_digest IS NULL THEN
            RETURN NEW;
        END IF;
    END IF;

    IF NEW.tenant_provenance_kind = 'UNBRIDGED_RETAINED' THEN
        RETURN NEW;
    ELSIF NEW.tenant_provenance_kind = 'APPROVED_RETAINED' THEN
        SELECT EXISTS (
            SELECT 1
            FROM account_approved_legacy_tenant_associations retained
            WHERE retained.legacy_tenant_id = NEW.tenant_id
              AND retained.canonical_tenant_id = NEW.tenant_uuid
              AND retained.operation_id = NEW.tenant_source_operation_id
              AND retained.manifest_digest = NEW.tenant_provenance_digest
        ) INTO source_matches;
    ELSIF NEW.tenant_provenance_kind = 'FRESH_GAME_DESIGN' THEN
        SELECT EXISTS (
            SELECT 1
            FROM account_fresh_tenant_identity_associations fresh
            WHERE fresh.canonical_tenant_id = NEW.tenant_uuid
              AND fresh.operation_id = NEW.tenant_source_operation_id
              AND fresh.evidence_digest = NEW.tenant_provenance_digest
              AND EXISTS (
                  SELECT 1
                  FROM account_canonical_tenant_identity_claims claim
                  WHERE claim.canonical_tenant_id = fresh.canonical_tenant_id
                    AND claim.identity_kind = NEW.tenant_provenance_kind
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
              )
        ) INTO source_matches;
    END IF;

    IF NOT source_matches THEN
        RAISE EXCEPTION 'Account membership tenant identity has no exact immutable source'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_tenant_membership_tenant_identity_guard
    BEFORE INSERT OR UPDATE ON account_tenant_membership
    FOR EACH ROW EXECUTE FUNCTION validate_account_membership_tenant_identity();
-- [jooq ignore stop]
