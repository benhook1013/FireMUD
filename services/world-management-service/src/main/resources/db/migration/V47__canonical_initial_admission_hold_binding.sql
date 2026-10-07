-- Canonical first-open holds retain their complete owner request alongside the legacy numeric
-- termination-blocking record. Retained V23 rows intentionally remain unmapped.
ALTER TABLE initial_admission_bind_hold
    DROP CONSTRAINT initial_admission_bind_hold_expected_no_prior_pointer_check,
    ADD COLUMN canonical_target_namespace VARCHAR(63),
    ADD COLUMN canonical_tenant_id UUID,
    ADD COLUMN canonical_world_slug VARCHAR(120),
    ADD COLUMN canonical_game_instance_id UUID,
    ADD COLUMN canonical_version_id UUID,
    ADD COLUMN initial_admission_origin VARCHAR(32),
    ADD COLUMN expected_prior_pointer_version BIGINT,
    ADD COLUMN canonical_request_bytes BYTEA,
    ADD COLUMN hold_binding_digest VARCHAR(71),
    ADD CONSTRAINT fk_initial_admission_bind_canonical_association
        FOREIGN KEY (
            canonical_target_namespace,
            canonical_tenant_id,
            canonical_world_slug,
            playable_state_namespace_uuid,
            playable_state_scope,
            canonical_game_instance_id
        ) REFERENCES world_canonical_instance_association (
            canonical_target_namespace,
            canonical_tenant_id,
            canonical_world_slug,
            playable_state_namespace_id,
            playable_state_scope,
            canonical_game_instance_id
        ) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_initial_admission_bind_canonical_metadata CHECK (
        (
            canonical_target_namespace IS NULL
            AND canonical_tenant_id IS NULL
            AND canonical_world_slug IS NULL
            AND canonical_game_instance_id IS NULL
            AND canonical_version_id IS NULL
            AND initial_admission_origin IS NULL
            AND expected_prior_pointer_version IS NULL
            AND canonical_request_bytes IS NULL
            AND hold_binding_digest IS NULL
            AND expected_no_prior_pointer IS TRUE
        )
        OR
        (
            canonical_target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND canonical_world_slug IS NOT NULL
            AND canonical_game_instance_id IS NOT NULL
            AND canonical_version_id IS NOT NULL
            AND initial_admission_origin IS NOT NULL
            AND canonical_request_bytes IS NOT NULL
            AND hold_binding_digest IS NOT NULL
            AND canonical_target_namespace COLLATE "C" ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_world_slug COLLATE "C" ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
            AND octet_length(canonical_world_slug) BETWEEN 1 AND 120
            AND canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND octet_length(canonical_request_bytes) > 0
            AND hold_binding_digest COLLATE "C" ~ '^sha256:[0-9a-f]{64}$'
            AND (
                (initial_admission_origin = 'NO_PRIOR_POINTER'
                    AND expected_no_prior_pointer IS TRUE
                    AND expected_prior_pointer_version IS NULL)
                OR
                (initial_admission_origin = 'EXPECT_CLOSED'
                    AND expected_no_prior_pointer IS FALSE
                    AND expected_prior_pointer_version IS NOT NULL
                    AND expected_prior_pointer_version > 0)
            )
        )
    );

-- [jooq ignore start]
REVOKE ALL ON initial_admission_bind_hold FROM PUBLIC;

CREATE FUNCTION world_guard_canonical_initial_admission_hold()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    instance_row "${serviceSchema}".world_instance%ROWTYPE;
    association_row "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
    stored_request JSONB;
    expected_request JSONB;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.canonical_request_bytes IS NOT NULL THEN
            RAISE EXCEPTION 'Canonical initial-admission hold identity cannot be deleted'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.hold_id IS DISTINCT FROM NEW.hold_id
            OR OLD.hold_fence IS DISTINCT FROM NEW.hold_fence
            OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
            OR OLD.realm_uuid IS DISTINCT FROM NEW.realm_uuid
            OR OLD.playable_state_namespace_uuid IS DISTINCT FROM NEW.playable_state_namespace_uuid
            OR OLD.playable_state_scope IS DISTINCT FROM NEW.playable_state_scope
            OR OLD.game_instance_id IS DISTINCT FROM NEW.game_instance_id
            OR OLD.version_id IS DISTINCT FROM NEW.version_id
            OR OLD.active_lifecycle_epoch IS DISTINCT FROM NEW.active_lifecycle_epoch
            OR OLD.initial_admission_request_id IS DISTINCT FROM NEW.initial_admission_request_id
            OR OLD.request_digest IS DISTINCT FROM NEW.request_digest
            OR OLD.expected_no_prior_pointer IS DISTINCT FROM NEW.expected_no_prior_pointer
            OR OLD.expected_catalog_revision IS DISTINCT FROM NEW.expected_catalog_revision
            OR OLD.diagnostic_expires_at IS DISTINCT FROM NEW.diagnostic_expires_at
            OR OLD.created_at IS DISTINCT FROM NEW.created_at
            OR OLD.canonical_target_namespace IS DISTINCT FROM NEW.canonical_target_namespace
            OR OLD.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
            OR OLD.canonical_world_slug IS DISTINCT FROM NEW.canonical_world_slug
            OR OLD.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id
            OR OLD.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
            OR OLD.initial_admission_origin IS DISTINCT FROM NEW.initial_admission_origin
            OR OLD.expected_prior_pointer_version IS DISTINCT FROM NEW.expected_prior_pointer_version
            OR OLD.canonical_request_bytes IS DISTINCT FROM NEW.canonical_request_bytes
            OR OLD.hold_binding_digest IS DISTINCT FROM NEW.hold_binding_digest THEN
            RAISE EXCEPTION 'Initial-admission hold acquisition identity is immutable'
                USING ERRCODE = '55000';
        END IF;
        IF OLD.canonical_request_bytes IS NOT NULL
            AND (OLD.status IS DISTINCT FROM NEW.status
                OR OLD.owner_proof_id IS DISTINCT FROM NEW.owner_proof_id
                OR OLD.owner_proof_digest IS DISTINCT FROM NEW.owner_proof_digest
                OR OLD.owner_pointer_audit_id IS DISTINCT FROM NEW.owner_pointer_audit_id
                OR OLD.owner_pointer_version IS DISTINCT FROM NEW.owner_pointer_version
                OR OLD.terminal_at IS DISTINCT FROM NEW.terminal_at) THEN
            RAISE EXCEPTION 'Canonical initial-admission hold requires its deferred typed owner outcome'
                USING ERRCODE = '55000';
        END IF;
        IF OLD.canonical_request_bytes IS NOT NULL THEN
            RAISE EXCEPTION 'Canonical initial-admission hold lifecycle updates are not enabled'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    -- A retained V23 numeric hold can never be promoted to canonical evidence. New untyped
    -- acquisitions are rejected when their actual private World row already has an association.
    IF NEW.canonical_request_bytes IS NULL THEN
        SELECT wi.* INTO instance_row
        FROM "${serviceSchema}".world_instance wi
        WHERE wi.tenant_id = NEW.tenant_id
            AND wi.game_instance_id = NEW.game_instance_id
        FOR UPDATE;
        IF FOUND AND EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_canonical_instance_association a
            WHERE a.world_instance_id = instance_row.id
        ) THEN
            RAISE EXCEPTION 'Canonical World instances require a typed initial-admission hold'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    -- Resolve private numeric keys only through the actual association row and its World row.
    SELECT a.* INTO association_row
    FROM "${serviceSchema}".world_canonical_instance_association a
    JOIN "${serviceSchema}".world_instance wi ON wi.id = a.world_instance_id
    WHERE wi.tenant_id = NEW.tenant_id
        AND wi.game_instance_id = NEW.game_instance_id
        AND wi.version_id = NEW.version_id
    FOR UPDATE OF wi;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Canonical initial-admission hold lacks its actual World association'
            USING ERRCODE = '23514';
    END IF;

    SELECT wi.* INTO instance_row
    FROM "${serviceSchema}".world_instance wi
    WHERE wi.id = association_row.world_instance_id;
    IF NOT FOUND
        OR association_row.canonical_target_namespace IS DISTINCT FROM NEW.canonical_target_namespace
        OR association_row.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR association_row.canonical_world_slug IS DISTINCT FROM NEW.canonical_world_slug
        OR association_row.playable_state_namespace_id IS DISTINCT FROM NEW.playable_state_namespace_uuid
        OR association_row.playable_state_scope IS DISTINCT FROM NEW.playable_state_scope
        OR association_row.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id
        OR association_row.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
        OR association_row.local_tenant_key IS DISTINCT FROM NEW.tenant_id
        OR association_row.private_game_instance_key IS DISTINCT FROM NEW.game_instance_id
        OR association_row.local_version_key IS DISTINCT FROM NEW.version_id
        OR instance_row.canonical_game_instance_id IS DISTINCT FROM association_row.canonical_game_instance_id
        OR instance_row.canonical_target_namespace IS DISTINCT FROM association_row.canonical_target_namespace
        OR instance_row.canonical_tenant_id IS DISTINCT FROM association_row.canonical_tenant_id
        OR instance_row.canonical_world_slug IS DISTINCT FROM association_row.canonical_world_slug
        OR instance_row.playable_state_namespace_id IS DISTINCT FROM association_row.playable_state_namespace_id
        OR instance_row.playable_state_scope IS DISTINCT FROM association_row.playable_state_scope
        OR instance_row.version_id IS DISTINCT FROM association_row.local_version_key
        OR instance_row.status IS DISTINCT FROM 'ACTIVE'
        OR instance_row.lifecycle_epoch IS DISTINCT FROM NEW.active_lifecycle_epoch THEN
        RAISE EXCEPTION 'Canonical initial-admission hold differs from its actual World association'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.hold_binding_digest IS DISTINCT FROM
        ('sha256:' || encode(sha256(NEW.canonical_request_bytes), 'hex')) THEN
        RAISE EXCEPTION 'Canonical initial-admission hold digest differs from its request bytes'
            USING ERRCODE = '23514';
    END IF;

    stored_request := convert_from(NEW.canonical_request_bytes, 'UTF8')::JSONB;
    expected_request := jsonb_build_object(
        'schema', 'world-canonical-initial-admission-hold-request/v1',
        'request', jsonb_build_object(
            'targetNamespace', NEW.canonical_target_namespace,
            'canonicalTenantId', NEW.canonical_tenant_id::TEXT,
            'worldSlug', NEW.canonical_world_slug,
            'realmId', NEW.realm_uuid::TEXT,
            'playableStateNamespaceId', NEW.playable_state_namespace_uuid::TEXT,
            'playableStateScope', NEW.playable_state_scope,
            'canonicalGameInstanceId', NEW.canonical_game_instance_id::TEXT,
            'canonicalVersionId', NEW.canonical_version_id::TEXT,
            'activeLifecycleEpoch', NEW.active_lifecycle_epoch::TEXT,
            'initialAdmissionRequestId', NEW.initial_admission_request_id,
            'initialAdmissionRequestDigest', NEW.request_digest::TEXT,
            'initialAdmissionOrigin', NEW.initial_admission_origin,
            'expectedCatalogRevision', NEW.expected_catalog_revision::TEXT,
            'expectedPriorPointerVersion', CASE
                WHEN NEW.expected_prior_pointer_version IS NULL THEN NULL
                ELSE NEW.expected_prior_pointer_version::TEXT
            END
        )
    );
    IF jsonb_typeof(stored_request) IS DISTINCT FROM 'object'
        OR stored_request IS DISTINCT FROM expected_request THEN
        RAISE EXCEPTION 'Canonical initial-admission request bytes differ from hold columns'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status <> 'PENDING'
        OR NEW.owner_proof_id IS NOT NULL
        OR NEW.owner_proof_digest IS NOT NULL
        OR NEW.owner_pointer_audit_id IS NOT NULL
        OR NEW.owner_pointer_version IS NOT NULL
        OR NEW.terminal_at IS NOT NULL THEN
        RAISE EXCEPTION 'Canonical initial-admission acquisition must begin PENDING'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_world_guard_canonical_initial_admission_hold
    BEFORE INSERT OR UPDATE OR DELETE ON initial_admission_bind_hold
    FOR EACH ROW EXECUTE FUNCTION world_guard_canonical_initial_admission_hold();
REVOKE ALL ON FUNCTION world_guard_canonical_initial_admission_hold() FROM PUBLIC;
-- [jooq ignore stop]
