-- Retained V1 operation rows keep the shared request-id fence and their exact bytes. New V2
-- intents use the same journal, but store canonical target identifiers and no numeric aliases.
ALTER TABLE account_join_operations
    ADD COLUMN operation_representation_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN scope_digest_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN target_class VARCHAR(20),
    ADD COLUMN account_uuid UUID,
    ADD COLUMN tenant_uuid UUID,
    ADD COLUMN tenant_slug VARCHAR(128),
    ADD COLUMN playable_state_namespace_uuid UUID,
    ADD COLUMN game_instance_uuid UUID;

ALTER TABLE account_join_operations
    ALTER COLUMN tenant_id DROP NOT NULL;

ALTER TABLE account_join_operations
    ALTER COLUMN playable_state_namespace_id DROP NOT NULL;

ALTER TABLE account_join_operations
    ALTER COLUMN game_instance_id DROP NOT NULL;

ALTER TABLE account_join_operations
    DROP CONSTRAINT account_join_operation_positive_versions_check;

ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_operation_positive_versions_check
        CHECK (
            (operation_representation_version = 1
                AND tenant_id IS NOT NULL AND tenant_id > 0
                AND game_instance_id IS NOT NULL AND game_instance_id > 0
                AND playable_state_namespace_id IS NOT NULL
                AND catalog_revision > 0 AND pointer_version > 0
                AND (entitlement_version IS NULL OR entitlement_version > 0))
            OR
            (operation_representation_version = 2
                AND tenant_id IS NULL
                AND game_instance_id IS NULL
                AND playable_state_namespace_id IS NULL
                AND catalog_revision > 0 AND pointer_version > 0
                AND (entitlement_version IS NULL OR entitlement_version > 0))
        );

ALTER TABLE account_join_operations
    DROP CONSTRAINT account_join_intent_digest_version_check;

ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_intent_digest_version_check
        CHECK (
            (operation_representation_version = 1
                AND intent_digest_version = 1
                AND length(intent_digest) = 71
                AND intent_digest LIKE 'sha256:%')
            OR
            (operation_representation_version = 2
                AND intent_digest_version = 2
                AND intent_digest_version IS NOT NULL
                AND intent_digest IS NOT NULL
                AND intent_digest ~ '^sha256:[0-9a-f]{64}$')
        );

ALTER TABLE account_join_operations
    DROP CONSTRAINT account_join_policy_digest_check;

ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_policy_digest_check
        CHECK (
            (operation_representation_version = 1
                AND (
                    (request_digest_version IS NULL
                        AND request_digest IS NULL
                        AND entitlement_authority_availability <> 'AVAILABLE'
                        AND entitlement_version IS NULL
                        AND allow_public_join IS NULL)
                    OR
                    (request_digest_version = 1
                        AND request_digest IS NOT NULL
                        AND length(request_digest) = 71
                        AND request_digest LIKE 'sha256:%'
                        AND entitlement_authority_availability = 'AVAILABLE'
                        AND entitlement_version IS NOT NULL
                        AND allow_public_join IS NOT NULL)
                ))
            OR
            (operation_representation_version = 2
                AND (
                    (request_digest_version IS NULL
                        AND request_digest IS NULL
                        AND entitlement_authority_availability IN ('UNAVAILABLE', 'NOT_EVALUATED')
                        AND entitlement_version IS NULL
                        AND allow_public_join IS NULL)
                    OR
                    (request_digest_version = 2
                        AND request_digest_version IS NOT NULL
                        AND request_digest IS NOT NULL
                        AND request_digest ~ '^sha256:[0-9a-f]{64}$'
                        AND entitlement_authority_availability = 'AVAILABLE'
                        AND entitlement_version IS NOT NULL
                        AND entitlement_version > 0
                        AND allow_public_join IS NOT NULL)
                ))
        );

ALTER TABLE account_join_operations
    ADD CONSTRAINT account_join_operation_representation_check
        CHECK (
            (operation_representation_version = 1
                AND scope_digest_version = 1
                AND target_class IS NULL
                AND account_uuid IS NULL
                AND tenant_uuid IS NULL
                AND tenant_slug IS NULL
                AND playable_state_namespace_uuid IS NULL
                AND game_instance_uuid IS NULL)
            OR
            (operation_representation_version = 2
                AND scope_digest_version = 2
                AND scope_digest_version IS NOT NULL
                AND request_id IS NOT NULL
                AND account_id IS NOT NULL
                AND verified_caller_binding IS NOT NULL
                AND scope_token_hash IS NOT NULL
                AND connect_scope_digest IS NOT NULL
                AND realm_id IS NOT NULL
                AND world_slug IS NOT NULL
                AND realm_slug IS NOT NULL
                AND playable_state_scope IS NOT NULL
                AND catalog_revision IS NOT NULL
                AND pointer_version IS NOT NULL
                AND intent_digest_version IS NOT NULL
                AND intent_digest IS NOT NULL
                AND entitlement_authority_availability IS NOT NULL
                AND last_attempt_authority_availability IS NOT NULL
                AND caller_bound_authority_invalidated IS FALSE
                AND status IS NOT NULL
                AND btrim(request_id) <> ''
                AND verified_caller_binding <> ''
                AND scope_token_hash ~ '^sha256:[0-9a-f]{64}$'
                AND target_class IS NOT NULL
                AND target_class = 'PUBLIC_PRODUCTION'
                AND tenant_id IS NULL
                AND game_instance_id IS NULL
                AND playable_state_namespace_id IS NULL
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
                AND connect_scope_digest ~ '^sha256:[0-9a-f]{64}$'
                AND intent_digest IS NOT NULL
                AND intent_digest_version = 2
                AND intent_digest ~ '^sha256:[0-9a-f]{64}$'
                AND status = 'PENDING'
                AND outcome IS NULL
                AND membership_id IS NULL
                AND outcome_membership_version IS NULL
                AND outcome_membership_authority_generation IS NULL)
        );

-- V2 rows may only bind one available policy after their pending intent. V1 keeps its historical
-- update behavior; legacy readers and writers must not interpret or mutate V2 rows.
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
            OR NEW.outcome IS NOT NULL
            OR NEW.membership_id IS NOT NULL
            OR NEW.outcome_membership_version IS NOT NULL
            OR NEW.outcome_membership_authority_generation IS NOT NULL
            OR NEW.caller_bound_authority_invalidated IS DISTINCT FROM FALSE
            OR NEW.entitlement_authority_availability IS DISTINCT FROM 'NOT_EVALUATED'
            OR NEW.entitlement_version IS NOT NULL
            OR NEW.allow_public_join IS NOT NULL
            OR NEW.request_digest_version IS NOT NULL
            OR NEW.request_digest IS NOT NULL
            OR NEW.last_attempt_authority_availability IS DISTINCT FROM 'NOT_EVALUATED'
            OR NEW.last_attempt_failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'Canonical JOIN operation must start as an exact pending intent'
                USING ERRCODE = 'check_violation';
        END IF;

        SELECT EXISTS (
            SELECT 1
            FROM account_connect_scope_records scope_row
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
        OR NEW.status IS DISTINCT FROM 'PENDING'
        OR NEW.outcome IS NOT NULL
        OR NEW.membership_id IS NOT NULL
        OR NEW.outcome_membership_version IS NOT NULL
        OR NEW.outcome_membership_authority_generation IS NOT NULL
        OR NEW.caller_bound_authority_invalidated IS DISTINCT FROM OLD.caller_bound_authority_invalidated
        OR NEW.reconciliation_attempt_count IS DISTINCT FROM OLD.reconciliation_attempt_count
        OR NEW.last_reconciliation_attempt_at IS DISTINCT FROM OLD.last_reconciliation_attempt_at
        OR NEW.last_reconciliation_attempt_reason IS DISTINCT FROM OLD.last_reconciliation_attempt_reason
        OR NEW.next_reconciliation_attempt_at IS DISTINCT FROM OLD.next_reconciliation_attempt_at
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
            OR NEW.entitlement_version IS NULL
            OR NEW.allow_public_join IS NULL
            OR NEW.last_attempt_authority_availability IS DISTINCT FROM 'AVAILABLE'
            OR NEW.last_attempt_failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'Canonical JOIN policy binding is incomplete'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NEW.request_digest_version IS NOT NULL
        OR NEW.entitlement_version IS NOT NULL
        OR NEW.allow_public_join IS NOT NULL
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
    IF EXISTS (
        SELECT 1
        FROM account_join_operations
        WHERE operation_representation_version = 2
    ) THEN
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
