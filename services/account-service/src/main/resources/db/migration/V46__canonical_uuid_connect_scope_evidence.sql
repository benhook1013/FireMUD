-- Keep retained v1 scope evidence byte-for-byte readable while storing new UUID scopes in the
-- same token-hash keyed family. V2 rows deliberately have no numeric tenant or game-instance key.
ALTER TABLE account_connect_scope_records
    ADD COLUMN scope_digest_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN account_uuid UUID,
    ADD COLUMN tenant_uuid UUID,
    ADD COLUMN tenant_slug VARCHAR(128),
    ADD COLUMN playable_state_namespace_uuid UUID,
    ADD COLUMN game_instance_uuid UUID,
    ADD COLUMN tenant_provenance_kind VARCHAR(32),
    ADD COLUMN tenant_provenance_legacy_tenant_id BIGINT,
    ADD COLUMN tenant_source_operation_id UUID,
    ADD COLUMN tenant_provenance_digest VARCHAR(71);

ALTER TABLE account_connect_scope_records
    ALTER COLUMN tenant_id DROP NOT NULL;

ALTER TABLE account_connect_scope_records
    ALTER COLUMN playable_state_namespace_id DROP NOT NULL;

ALTER TABLE account_connect_scope_records
    ALTER COLUMN game_instance_id DROP NOT NULL;

ALTER TABLE account_connect_scope_records
    DROP CONSTRAINT account_connect_scope_positive_check;

ALTER TABLE account_connect_scope_records
    ADD CONSTRAINT account_connect_scope_positive_check
        CHECK (
            (scope_digest_version = 1
                AND tenant_id IS NOT NULL AND tenant_id > 0
                AND game_instance_id IS NOT NULL AND game_instance_id > 0
                AND playable_state_namespace_id IS NOT NULL
                AND catalog_revision > 0 AND pointer_version > 0)
            OR
            (scope_digest_version = 2
                AND tenant_id IS NULL
                AND game_instance_id IS NULL
                AND playable_state_namespace_id IS NULL
                AND catalog_revision > 0 AND pointer_version > 0)
        );

ALTER TABLE account_connect_scope_records
    ADD CONSTRAINT account_connect_scope_digest_version_check
        CHECK (scope_digest_version IN (1, 2));

ALTER TABLE account_connect_scope_records
    ADD CONSTRAINT account_connect_scope_identity_version_check
        CHECK (
            (scope_digest_version = 1
                AND account_uuid IS NULL
                AND tenant_uuid IS NULL
                AND tenant_slug IS NULL
                AND playable_state_namespace_uuid IS NULL
                AND game_instance_uuid IS NULL
                AND tenant_provenance_kind IS NULL
                AND tenant_provenance_legacy_tenant_id IS NULL
                AND tenant_source_operation_id IS NULL
                AND tenant_provenance_digest IS NULL)
            OR
            (scope_digest_version = 2
                AND target_class = 'PUBLIC_PRODUCTION'
                AND playtest_lifecycle_id IS NULL
                AND playtest_state_generation IS NULL
                AND account_uuid IS NOT NULL
                AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_uuid IS NOT NULL
                AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_slug IS NOT NULL AND tenant_slug <> ''
                AND realm_id IS NOT NULL
                AND realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND world_slug IS NOT NULL AND world_slug <> ''
                AND realm_slug IS NOT NULL AND realm_slug <> ''
                AND playable_state_namespace_uuid IS NOT NULL
                AND playable_state_namespace_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND playable_state_scope IS NOT NULL
                AND playable_state_scope IN ('SHARED', 'ISOLATED')
                AND game_instance_uuid IS NOT NULL
                AND game_instance_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
                AND evaluated_at IS NOT NULL AND evaluated_at <> ''
                AND connect_scope_expires_at IS NOT NULL AND connect_scope_expires_at <> ''
                AND snapshot_digest IS NOT NULL
                AND snapshot_digest ~ '^sha256:[0-9a-f]{64}$'
                AND tenant_provenance_kind IS NOT NULL
                AND tenant_source_operation_id IS NOT NULL
                AND tenant_source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
                AND tenant_provenance_digest IS NOT NULL
                AND tenant_provenance_digest ~ '^sha256:[0-9a-f]{64}$'
                AND (
                    (tenant_provenance_kind = 'APPROVED_RETAINED'
                        AND tenant_provenance_legacy_tenant_id IS NOT NULL
                        AND tenant_provenance_legacy_tenant_id > 0)
                    OR
                    (tenant_provenance_kind = 'FRESH_GAME_DESIGN'
                        AND tenant_provenance_legacy_tenant_id IS NULL)
                ))
        );

-- V1 inserts and existing V1 rows keep their original shape and historical mutation behavior.
-- New V2 source identity and digest evidence is immutable and must point at exact owner rows.
-- [jooq ignore start]
CREATE FUNCTION validate_account_connect_scope_identity() RETURNS trigger AS $$
DECLARE
    account_matches BOOLEAN := FALSE;
    tenant_source_matches BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.scope_digest_version = 2 THEN
            RAISE EXCEPTION 'Canonical Account connect scope evidence cannot be deleted without cleanup proof'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.scope_digest_version = 2 THEN
            RAISE EXCEPTION 'Canonical Account connect scope evidence is immutable'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.scope_digest_version = 2 THEN
            RAISE EXCEPTION 'Retained Account connect scope evidence cannot be upgraded in place'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.scope_digest_version = 1 THEN
        RETURN NEW;
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM accounts account_row
        WHERE account_row.id = NEW.account_id
          AND account_row.account_uuid = NEW.account_uuid
          AND account_row.account_uuid_source_numeric_id = account_row.id
          AND account_row.account_uuid_provenance IS NOT NULL
    ) INTO account_matches;
    IF NOT account_matches THEN
        RAISE EXCEPTION 'Canonical Account connect scope has no exact persisted Account identity'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.tenant_provenance_kind = 'APPROVED_RETAINED' THEN
        SELECT EXISTS (
            SELECT 1
            FROM account_approved_legacy_tenant_associations retained
            WHERE retained.identity_kind = 'APPROVED_RETAINED'
              AND retained.legacy_tenant_id = NEW.tenant_provenance_legacy_tenant_id
              AND retained.canonical_tenant_id = NEW.tenant_uuid
              AND retained.operation_id = NEW.tenant_source_operation_id
              AND retained.manifest_digest = NEW.tenant_provenance_digest
        ) INTO tenant_source_matches;
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
                    AND claim.identity_kind = 'FRESH_GAME_DESIGN'
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
        ) INTO tenant_source_matches;
    END IF;

    IF NOT tenant_source_matches THEN
        RAISE EXCEPTION 'Canonical Account connect scope has no exact immutable tenant source'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_connect_scope_identity_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_connect_scope_records
    FOR EACH ROW EXECUTE FUNCTION validate_account_connect_scope_identity();

CREATE FUNCTION guard_account_connect_scope_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM account_connect_scope_records
        WHERE scope_digest_version = 2
    ) THEN
        RAISE EXCEPTION 'Canonical Account connect scope evidence cannot be truncated without cleanup proof'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_connect_scope_truncate_guard
    BEFORE TRUNCATE ON account_connect_scope_records
    FOR EACH STATEMENT EXECUTE FUNCTION guard_account_connect_scope_truncate();
-- [jooq ignore stop]
