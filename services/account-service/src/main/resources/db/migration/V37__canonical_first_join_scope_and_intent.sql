-- V1 rows remain byte-compatible, while new first-JOIN scope and intent evidence uses canonical
-- UUID identities and the shared global request-id key. This migration intentionally stops at
-- durable PENDING intent/policy evidence; a producer-owned membership/outbox transaction follows.
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

ALTER TABLE account_connect_scope_records ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE account_connect_scope_records
    ALTER COLUMN playable_state_namespace_id DROP NOT NULL;
ALTER TABLE account_connect_scope_records ALTER COLUMN game_instance_id DROP NOT NULL;
ALTER TABLE account_connect_scope_records
    DROP CONSTRAINT account_connect_scope_positive_check;
ALTER TABLE account_connect_scope_records
    ADD CONSTRAINT account_connect_scope_positive_check CHECK (
        (scope_digest_version = 1 AND tenant_id IS NOT NULL AND tenant_id > 0
            AND game_instance_id IS NOT NULL AND game_instance_id > 0
            AND playable_state_namespace_id IS NOT NULL
            AND catalog_revision > 0 AND pointer_version > 0)
        OR
        (scope_digest_version = 2 AND tenant_id IS NULL AND game_instance_id IS NULL
            AND playable_state_namespace_id IS NULL
            AND catalog_revision > 0 AND pointer_version > 0)
    );
ALTER TABLE account_connect_scope_records
    ADD CONSTRAINT account_connect_scope_digest_version_check
        CHECK (scope_digest_version IN (1, 2));
ALTER TABLE account_connect_scope_records
    ADD CONSTRAINT account_connect_scope_identity_version_check CHECK (
        (scope_digest_version = 1 AND account_uuid IS NULL AND tenant_uuid IS NULL
            AND tenant_slug IS NULL AND playable_state_namespace_uuid IS NULL
            AND game_instance_uuid IS NULL AND tenant_provenance_kind IS NULL
            AND tenant_provenance_legacy_tenant_id IS NULL
            AND tenant_source_operation_id IS NULL AND tenant_provenance_digest IS NULL)
        OR
        (scope_digest_version = 2 AND target_class = 'PUBLIC_PRODUCTION'
            AND playtest_lifecycle_id IS NULL AND playtest_state_generation IS NULL
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
            AND playable_state_scope IN ('SHARED', 'ISOLATED')
            AND game_instance_uuid IS NOT NULL
            AND game_instance_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND evaluated_at IS NOT NULL AND evaluated_at <> ''
            AND connect_scope_expires_at IS NOT NULL AND connect_scope_expires_at <> ''
            AND snapshot_digest ~ '^sha256:[0-9a-f]{64}$'
            AND tenant_provenance_kind IS NOT NULL
            AND tenant_source_operation_id IS NOT NULL
            AND tenant_source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND tenant_provenance_digest IS NOT NULL
            AND tenant_provenance_digest ~ '^sha256:[0-9a-f]{64}$'
            AND ((tenant_provenance_kind = 'APPROVED_RETAINED'
                    AND tenant_provenance_legacy_tenant_id IS NOT NULL
                    AND tenant_provenance_legacy_tenant_id > 0)
                OR (tenant_provenance_kind = 'FRESH_GAME_DESIGN'
                    AND tenant_provenance_legacy_tenant_id IS NULL)))
    );

-- [jooq ignore start]
CREATE FUNCTION validate_account_connect_scope_identity() RETURNS trigger AS $$
DECLARE
    account_matches BOOLEAN := FALSE;
    tenant_source_matches BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.scope_digest_version = 2 THEN
            RAISE EXCEPTION 'Canonical Account connect scope evidence cannot be deleted'
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
        SELECT 1 FROM accounts account_row
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
            SELECT 1 FROM account_approved_legacy_tenant_associations retained
            WHERE retained.identity_kind = 'APPROVED_RETAINED'
              AND retained.legacy_tenant_id = NEW.tenant_provenance_legacy_tenant_id
              AND retained.canonical_tenant_id = NEW.tenant_uuid
              AND retained.operation_id = NEW.tenant_source_operation_id
              AND EXISTS (
                  SELECT 1 FROM account_approved_legacy_tenant_association_payload payload
                  WHERE payload.operation_id = retained.operation_id
                    AND payload.manifest_digest = NEW.tenant_provenance_digest
              )
        ) INTO tenant_source_matches;
    ELSIF NEW.tenant_provenance_kind = 'FRESH_GAME_DESIGN' THEN
        SELECT EXISTS (
            SELECT 1 FROM account_fresh_tenant_identity_associations fresh
            WHERE fresh.canonical_tenant_id = NEW.tenant_uuid
              AND fresh.operation_id = NEW.tenant_source_operation_id
              AND fresh.evidence_digest = NEW.tenant_provenance_digest
              AND EXISTS (
                  SELECT 1 FROM account_canonical_tenant_identity_claims claim
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
    IF EXISTS (SELECT 1 FROM account_connect_scope_records WHERE scope_digest_version = 2) THEN
        RAISE EXCEPTION 'Canonical Account connect scope evidence cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_connect_scope_truncate_guard
    BEFORE TRUNCATE ON account_connect_scope_records
    FOR EACH STATEMENT EXECUTE FUNCTION guard_account_connect_scope_truncate();
-- [jooq ignore stop]

ALTER TABLE account_join_operations
    ADD COLUMN operation_representation_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN scope_digest_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN target_class VARCHAR(20),
    ADD COLUMN account_uuid UUID,
    ADD COLUMN tenant_uuid UUID,
    ADD COLUMN tenant_slug VARCHAR(128),
    ADD COLUMN playable_state_namespace_uuid UUID,
    ADD COLUMN game_instance_uuid UUID;

ALTER TABLE account_join_operations ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE account_join_operations ALTER COLUMN playable_state_namespace_id DROP NOT NULL;
ALTER TABLE account_join_operations ALTER COLUMN game_instance_id DROP NOT NULL;
ALTER TABLE account_join_operations
    DROP CONSTRAINT account_join_operation_positive_versions_check;
ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_operation_positive_versions_check CHECK (
        (operation_representation_version = 1 AND tenant_id IS NOT NULL AND tenant_id > 0
            AND game_instance_id IS NOT NULL AND game_instance_id > 0
            AND playable_state_namespace_id IS NOT NULL
            AND catalog_revision > 0 AND pointer_version > 0
            AND (entitlement_version IS NULL OR entitlement_version > 0))
        OR
        (operation_representation_version = 2 AND tenant_id IS NULL
            AND game_instance_id IS NULL AND playable_state_namespace_id IS NULL
            AND catalog_revision > 0 AND pointer_version > 0
            AND (entitlement_version IS NULL OR entitlement_version > 0))
    );
ALTER TABLE account_join_operations
    DROP CONSTRAINT account_join_intent_digest_version_check;
ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_intent_digest_version_check CHECK (
        (operation_representation_version = 1 AND intent_digest_version = 1
            AND length(intent_digest) = 71 AND intent_digest LIKE 'sha256:%')
        OR
        (operation_representation_version = 2 AND intent_digest_version = 2
            AND intent_digest IS NOT NULL AND intent_digest ~ '^sha256:[0-9a-f]{64}$')
    );
ALTER TABLE account_join_operations DROP CONSTRAINT account_join_policy_digest_check;
ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_policy_digest_check CHECK (
        (operation_representation_version = 1 AND (
            (request_digest_version IS NULL AND request_digest IS NULL
                AND entitlement_authority_availability <> 'AVAILABLE'
                AND entitlement_version IS NULL AND allow_public_join IS NULL)
            OR
            (request_digest_version = 1 AND request_digest IS NOT NULL
                AND length(request_digest) = 71 AND request_digest LIKE 'sha256:%'
                AND entitlement_authority_availability = 'AVAILABLE'
                AND entitlement_version IS NOT NULL AND allow_public_join IS NOT NULL)))
        OR
        (operation_representation_version = 2 AND (
            (request_digest_version IS NULL AND request_digest IS NULL
                AND entitlement_authority_availability IN ('UNAVAILABLE', 'NOT_EVALUATED')
                AND entitlement_version IS NULL AND allow_public_join IS NULL)
            OR
            (request_digest_version = 2 AND request_digest IS NOT NULL
                AND request_digest ~ '^sha256:[0-9a-f]{64}$'
                AND entitlement_authority_availability = 'AVAILABLE'
                AND entitlement_version IS NOT NULL AND entitlement_version > 0
                AND allow_public_join IS NOT NULL)))
    );
/* [jooq ignore start] */
ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_operation_representation_check CHECK (
        (operation_representation_version = 1 AND scope_digest_version = 1
            AND target_class IS NULL AND account_uuid IS NULL AND tenant_uuid IS NULL
            AND tenant_slug IS NULL AND playable_state_namespace_uuid IS NULL
            AND game_instance_uuid IS NULL)
        OR
        (operation_representation_version = 2 AND scope_digest_version = 2
            AND request_id IS NOT NULL AND account_id IS NOT NULL
            AND verified_caller_binding IS NOT NULL AND scope_token_hash IS NOT NULL
            AND connect_scope_digest IS NOT NULL AND realm_id IS NOT NULL
            AND world_slug IS NOT NULL AND realm_slug IS NOT NULL
            AND playable_state_scope IS NOT NULL AND catalog_revision IS NOT NULL
            AND pointer_version IS NOT NULL AND intent_digest_version IS NOT NULL
            AND intent_digest IS NOT NULL AND entitlement_authority_availability IS NOT NULL
            AND last_attempt_authority_availability IS NOT NULL
            AND caller_bound_authority_invalidated IS FALSE AND status IS NOT NULL
            AND btrim(request_id) <> '' AND verified_caller_binding <> ''
            AND scope_token_hash ~ '^sha256:[0-9a-f]{64}$'
            AND target_class = 'PUBLIC_PRODUCTION'
            AND tenant_id IS NULL AND game_instance_id IS NULL
            AND playable_state_namespace_id IS NULL
            AND account_uuid IS NOT NULL
            AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND tenant_uuid IS NOT NULL
            AND tenant_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND tenant_slug IS NOT NULL AND tenant_slug <> ''
            AND realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND world_slug <> '' AND realm_slug <> ''
            AND playable_state_namespace_uuid IS NOT NULL
            AND playable_state_namespace_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND playable_state_scope IN ('SHARED', 'ISOLATED')
            AND game_instance_uuid IS NOT NULL
            AND game_instance_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND connect_scope_digest ~ '^sha256:[0-9a-f]{64}$'
            AND intent_digest_version = 2
            AND intent_digest ~ '^sha256:[0-9a-f]{64}$'
            AND status = 'PENDING' AND outcome IS NULL AND membership_id IS NULL
            AND outcome_membership_version IS NULL
            AND outcome_membership_authority_generation IS NULL)
    );
/* [jooq ignore stop] */

-- [jooq ignore start]
CREATE FUNCTION guard_account_join_operation_identity() RETURNS trigger AS $$
DECLARE
    scope_source_matches BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.operation_representation_version = 2 THEN
            RAISE EXCEPTION 'Canonical JOIN operation evidence cannot be deleted'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.operation_representation_version = 1 THEN
            RETURN NEW;
        END IF;
        IF NEW.operation_representation_version IS DISTINCT FROM 2
            OR NEW.scope_digest_version IS DISTINCT FROM 2
            OR NEW.intent_digest_version IS DISTINCT FROM 2
            OR NEW.status IS DISTINCT FROM 'PENDING'
            OR NEW.outcome IS NOT NULL OR NEW.membership_id IS NOT NULL
            OR NEW.outcome_membership_version IS NOT NULL
            OR NEW.outcome_membership_authority_generation IS NOT NULL
            OR NEW.caller_bound_authority_invalidated IS DISTINCT FROM FALSE
            OR NEW.entitlement_authority_availability IS DISTINCT FROM 'NOT_EVALUATED'
            OR NEW.entitlement_version IS NOT NULL OR NEW.allow_public_join IS NOT NULL
            OR NEW.request_digest_version IS NOT NULL OR NEW.request_digest IS NOT NULL
            OR NEW.last_attempt_authority_availability IS DISTINCT FROM 'NOT_EVALUATED'
            OR NEW.last_attempt_failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'Canonical JOIN operation must start as an exact pending intent'
                USING ERRCODE = 'check_violation';
        END IF;

        SELECT EXISTS (
            SELECT 1 FROM account_connect_scope_records scope_row
            WHERE scope_row.scope_token_hash = NEW.scope_token_hash
              AND scope_row.scope_digest_version = 2
              AND scope_row.snapshot_digest = NEW.connect_scope_digest
              AND scope_row.account_id = NEW.account_id
              AND scope_row.account_uuid = NEW.account_uuid
              AND scope_row.target_class = NEW.target_class
              AND scope_row.tenant_id IS NULL
              AND scope_row.tenant_uuid = NEW.tenant_uuid
              AND scope_row.tenant_slug = NEW.tenant_slug
              AND scope_row.realm_id = NEW.realm_id
              AND scope_row.world_slug = NEW.world_slug
              AND scope_row.realm_slug = NEW.realm_slug
              AND scope_row.playable_state_namespace_id IS NULL
              AND scope_row.playable_state_namespace_uuid = NEW.playable_state_namespace_uuid
              AND scope_row.playable_state_scope = NEW.playable_state_scope
              AND scope_row.game_instance_id IS NULL
              AND scope_row.game_instance_uuid = NEW.game_instance_uuid
              AND scope_row.catalog_revision = NEW.catalog_revision
              AND scope_row.pointer_version = NEW.pointer_version
        ) INTO scope_source_matches;
        IF NOT scope_source_matches THEN
            RAISE EXCEPTION 'Canonical JOIN operation has no exact persisted scope source'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.operation_representation_version = 1 THEN
        IF NEW.operation_representation_version <> 1 THEN
            RAISE EXCEPTION 'Retained JOIN operation evidence cannot change representation'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.operation_representation_version <> 2
        OR NEW.operation_representation_version <> 2
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.account_id IS DISTINCT FROM OLD.account_id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.verified_caller_binding IS DISTINCT FROM OLD.verified_caller_binding
        OR NEW.scope_token_hash IS DISTINCT FROM OLD.scope_token_hash
        OR NEW.connect_scope_digest IS DISTINCT FROM OLD.connect_scope_digest
        OR NEW.world_slug IS DISTINCT FROM OLD.world_slug
        OR NEW.realm_slug IS DISTINCT FROM OLD.realm_slug
        OR NEW.realm_id IS DISTINCT FROM OLD.realm_id
        OR NEW.playable_state_namespace_id IS DISTINCT FROM OLD.playable_state_namespace_id
        OR NEW.playable_state_scope IS DISTINCT FROM OLD.playable_state_scope
        OR NEW.game_instance_id IS DISTINCT FROM OLD.game_instance_id
        OR NEW.catalog_revision IS DISTINCT FROM OLD.catalog_revision
        OR NEW.pointer_version IS DISTINCT FROM OLD.pointer_version
        OR NEW.intent_digest_version IS DISTINCT FROM OLD.intent_digest_version
        OR NEW.intent_digest IS DISTINCT FROM OLD.intent_digest
        OR NEW.scope_digest_version IS DISTINCT FROM OLD.scope_digest_version
        OR NEW.target_class IS DISTINCT FROM OLD.target_class
        OR NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.tenant_slug IS DISTINCT FROM OLD.tenant_slug
        OR NEW.playable_state_namespace_uuid IS DISTINCT FROM OLD.playable_state_namespace_uuid
        OR NEW.game_instance_uuid IS DISTINCT FROM OLD.game_instance_uuid
        OR NEW.status IS DISTINCT FROM 'PENDING' OR NEW.outcome IS NOT NULL
        OR NEW.membership_id IS NOT NULL OR NEW.outcome_membership_version IS NOT NULL
        OR NEW.outcome_membership_authority_generation IS NOT NULL
        OR NEW.caller_bound_authority_invalidated IS DISTINCT FROM OLD.caller_bound_authority_invalidated
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Canonical JOIN operation intent evidence is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.request_digest IS NOT NULL THEN
        RAISE EXCEPTION 'Canonical JOIN available policy evidence is immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.request_digest IS NOT NULL THEN
        IF NEW.request_digest_version IS DISTINCT FROM 2
            OR NEW.entitlement_authority_availability IS DISTINCT FROM 'AVAILABLE'
            OR NEW.entitlement_version IS NULL OR NEW.entitlement_version <= 0
            OR NEW.allow_public_join IS NULL
            OR NEW.last_attempt_authority_availability IS DISTINCT FROM 'AVAILABLE'
            OR NEW.last_attempt_failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'Canonical JOIN policy binding is incomplete'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NEW.request_digest_version IS NOT NULL
        OR NEW.entitlement_version IS NOT NULL OR NEW.allow_public_join IS NOT NULL
        OR NEW.entitlement_authority_availability IS DISTINCT FROM 'UNAVAILABLE'
        OR NEW.last_attempt_authority_availability IS DISTINCT FROM 'UNAVAILABLE'
        OR NEW.last_attempt_failure_code IS NULL
        OR btrim(NEW.last_attempt_failure_code) = '' THEN
        RAISE EXCEPTION 'Canonical JOIN unavailable attempt cannot invent policy evidence'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_join_operation_identity_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_join_operations
    FOR EACH ROW EXECUTE FUNCTION guard_account_join_operation_identity();

CREATE FUNCTION guard_account_join_operation_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM account_join_operations WHERE operation_representation_version = 2) THEN
        RAISE EXCEPTION 'Canonical JOIN operation evidence cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_join_operation_truncate_guard
    BEFORE TRUNCATE ON account_join_operations
    FOR EACH STATEMENT EXECUTE FUNCTION guard_account_join_operation_truncate();
-- [jooq ignore stop]
