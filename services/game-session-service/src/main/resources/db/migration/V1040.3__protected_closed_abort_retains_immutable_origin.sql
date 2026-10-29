-- Protected EXPECT_CLOSED abort retains the exact immutable original CLOSED origin.
-- It grants no admission and does not require that origin to remain the current pointer.
-- Positive commit, NO_PRIOR_POINTER and unprotected abort guards remain unchanged.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION require_game_session_canonical_initial_admission_atomic_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    selected_namespace character varying(63);
    selected_request_id character varying(128);
    selected_status character varying(16);
BEGIN
    IF TG_TABLE_NAME = 'gameplay_admission_pointer' THEN
        IF NEW.representation_version <> 3 THEN
            RETURN NULL;
        END IF;
        selected_namespace := NEW.target_namespace;
        selected_request_id := NEW.initial_admission_request_id;
    ELSIF TG_TABLE_NAME = 'gameplay_admission_pointer_event' THEN
        IF NEW.representation_version <> 3 THEN
            RETURN NULL;
        END IF;
        selected_namespace := NEW.target_namespace;
        selected_request_id := NEW.initial_admission_request_id;
    ELSE
        selected_namespace := NEW.target_namespace;
        selected_request_id := NEW.initial_admission_request_id;
    END IF;

    SELECT status INTO selected_status
    FROM game_session_canonical_initial_admission_attempt
    WHERE target_namespace = selected_namespace
      AND initial_admission_request_id = selected_request_id;

    IF selected_status = 'PENDING' THEN
        IF EXISTS (
            SELECT 1 FROM gameplay_admission_pointer
            WHERE representation_version = 3
              AND target_namespace = selected_namespace
              AND initial_admission_request_id = selected_request_id
        ) OR EXISTS (
            SELECT 1 FROM gameplay_admission_pointer_event
            WHERE representation_version = 3
              AND target_namespace = selected_namespace
              AND initial_admission_request_id = selected_request_id
        ) THEN
            RAISE EXCEPTION 'PENDING canonical initial-admission attempt cannot carry pointer evidence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    IF selected_status = 'COMMITTED' AND EXISTS (
        SELECT 1
        FROM game_session_canonical_initial_admission_attempt attempt
        JOIN gameplay_admission_pointer pointer
          ON pointer.id = attempt.pointer_id
         AND pointer.representation_version = 3
         AND pointer.target_namespace = attempt.target_namespace
         AND pointer.canonical_tenant_id = attempt.canonical_tenant_id
         AND pointer.realm_id = attempt.realm_id
         AND pointer.world_slug = attempt.world_slug
         AND pointer.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND pointer.state_scope = attempt.playable_state_scope
         AND pointer.canonical_game_instance_id = attempt.canonical_game_instance_id
         AND pointer.canonical_version_id = attempt.canonical_version_id
         AND pointer.runtime_version_id = attempt.runtime_version_id
         AND pointer.tenant_id = attempt.game_session_tenant_id
         AND pointer.game_instance_id = attempt.game_instance_id
         AND pointer.pointer_version = attempt.pointer_version
         AND pointer.catalog_revision = attempt.expected_catalog_revision
         AND pointer.initial_admission_request_id = attempt.initial_admission_request_id
         AND pointer.initial_admission_request_digest = attempt.request_digest
         AND pointer.initial_admission_origin_kind = attempt.origin_kind
         AND pointer.initial_admission_prior_pointer_version IS NOT DISTINCT FROM attempt.expected_prior_pointer_version
         AND pointer.initial_admission_active_epoch = attempt.active_lifecycle_epoch
         AND pointer.initial_admission_hold_id = attempt.hold_id
         AND pointer.initial_admission_hold_fence = attempt.hold_fence
         AND pointer.initial_admission_hold_binding_digest = attempt.hold_binding_digest
         AND pointer.admission_state = 'OPEN'
        JOIN gameplay_admission_pointer_event event
          ON event.id = attempt.audit_event_id
         AND event.representation_version = 3
         AND event.target_namespace = attempt.target_namespace
         AND event.canonical_tenant_id = attempt.canonical_tenant_id
         AND event.realm_id = attempt.realm_id
         AND event.world_slug = attempt.world_slug
         AND event.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND event.state_scope = attempt.playable_state_scope
         AND event.canonical_game_instance_id = attempt.canonical_game_instance_id
         AND event.canonical_version_id = attempt.canonical_version_id
         AND event.runtime_version_id = attempt.runtime_version_id
         AND event.tenant_id = attempt.game_session_tenant_id
         AND event.game_instance_id = attempt.game_instance_id
         AND event.pointer_version = attempt.pointer_version
         AND event.catalog_revision = attempt.expected_catalog_revision
         AND event.initial_admission_request_id = attempt.initial_admission_request_id
         AND event.control_plane_request_id = attempt.initial_admission_request_id
         AND event.initial_admission_request_digest = attempt.request_digest
         AND event.initial_admission_origin_kind = attempt.origin_kind
         AND event.initial_admission_prior_pointer_version IS NOT DISTINCT FROM attempt.expected_prior_pointer_version
         AND event.initial_admission_active_epoch = attempt.active_lifecycle_epoch
         AND event.initial_admission_hold_id = attempt.hold_id
         AND event.initial_admission_hold_fence = attempt.hold_fence
         AND event.initial_admission_hold_binding_digest = attempt.hold_binding_digest
         AND event.admission_state = 'OPEN'
         AND event.prepared_version_upgrade_id IS NULL
        JOIN game_session_canonical_realm_catalog catalog
          ON catalog.target_namespace = attempt.target_namespace
         AND catalog.canonical_tenant_id = attempt.canonical_tenant_id
         AND catalog.realm_id = attempt.realm_id
         AND catalog.world_slug = attempt.world_slug
         AND catalog.catalog_revision = attempt.expected_catalog_revision
         AND catalog.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND catalog.state_scope = attempt.playable_state_scope
         AND catalog.visible = pointer.visible
         AND catalog.public_production = pointer.public_production_realm
         AND catalog.character_creation_policy = pointer.character_creation_policy
        JOIN game_session_canonical_instance_launch launch
          ON launch.target_namespace = attempt.target_namespace
         AND launch.canonical_tenant_id = attempt.canonical_tenant_id
         AND launch.canonical_realm_id = attempt.realm_id
         AND launch.world_slug = attempt.world_slug
         AND launch.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND launch.playable_state_scope = attempt.playable_state_scope
         AND launch.public_production = TRUE
         AND launch.game_instance_uuid = attempt.canonical_game_instance_id
         AND launch.game_session_tenant_id = attempt.game_session_tenant_id
         AND launch.game_instance_id = attempt.game_instance_id
         AND launch.version_id = attempt.runtime_version_id
         AND launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
             = attempt.canonical_version_id::text
        JOIN game_instances current
          ON current.tenant_id = launch.game_session_tenant_id
         AND current.id = launch.game_instance_id
         AND current.game_instance_uuid = launch.game_instance_uuid
         AND current.version_id = attempt.runtime_version_id
         AND current.status = 'RUNNING'
        WHERE attempt.target_namespace = selected_namespace
          AND attempt.initial_admission_request_id = selected_request_id
          AND attempt.status = 'COMMITTED'
          AND attempt.pointer_version = CASE
              WHEN attempt.origin_kind = 'NO_PRIOR_POINTER' THEN 1
              ELSE attempt.expected_prior_pointer_version + 1 END
          AND attempt.terminal_at = (pointer.updated_at AT TIME ZONE 'UTC')
          AND attempt.terminal_at = (event.occurred_at AT TIME ZONE 'UTC')
          AND ((attempt.origin_kind = 'NO_PRIOR_POINTER'
                AND attempt.expected_prior_pointer_version IS NULL
                AND attempt.prior_pointer_request_id IS NULL
                AND attempt.prior_pointer_request_digest IS NULL
                AND attempt.prior_pointer_audit_event_id IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM game_session_canonical_closed_admission_pointer_request origin
                    WHERE origin.target_namespace = attempt.target_namespace
                      AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                      AND origin.realm_id = attempt.realm_id)
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_admission_pointer other_pointer
                    WHERE other_pointer.id <> pointer.id
                      AND (other_pointer.realm_id = attempt.realm_id
                        OR (other_pointer.target_namespace = attempt.target_namespace
                            AND other_pointer.canonical_tenant_id = attempt.canonical_tenant_id)
                        OR (other_pointer.world_slug = attempt.world_slug
                            AND other_pointer.realm_slug = catalog.realm_slug)
                        OR (other_pointer.tenant_id = attempt.game_session_tenant_id
                            AND other_pointer.game_instance_id = attempt.game_instance_id)))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_admission_pointer_event other_event
                    WHERE other_event.id <> event.id
                      AND (other_event.realm_id = attempt.realm_id
                        OR (other_event.target_namespace = attempt.target_namespace
                            AND other_event.canonical_tenant_id = attempt.canonical_tenant_id)
                        OR (other_event.world_slug = attempt.world_slug
                            AND other_event.realm_slug = catalog.realm_slug)
                        OR (other_event.tenant_id = attempt.game_session_tenant_id
                            AND other_event.game_instance_id = attempt.game_instance_id)))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_initial_admission_bind_catalog legacy_catalog
                    WHERE legacy_catalog.tenant_id = attempt.game_session_tenant_id
                      AND (legacy_catalog.realm_id = attempt.realm_id
                        OR (legacy_catalog.world_slug = attempt.world_slug
                            AND legacy_catalog.realm_slug = catalog.realm_slug)))
                AND NOT EXISTS (
                    SELECT 1 FROM gameplay_initial_admission_bind_attempt legacy_attempt
                    WHERE legacy_attempt.tenant_id = attempt.game_session_tenant_id
                      AND (legacy_attempt.realm_id = attempt.realm_id
                        OR legacy_attempt.game_instance_id = attempt.game_instance_id)))
            OR (attempt.origin_kind = 'EXPECT_CLOSED'
                AND attempt.expected_prior_pointer_version = 1
                AND attempt.prior_pointer_request_id IS NOT NULL
                AND attempt.prior_pointer_request_digest IS NOT NULL
                AND attempt.prior_pointer_audit_event_id IS NOT NULL
                AND EXISTS (
                    SELECT 1
                    FROM game_session_canonical_closed_admission_pointer_request origin
                    JOIN gameplay_admission_pointer_event origin_event
                      ON origin_event.id = origin.audit_event_id
                     AND origin_event.representation_version = 2
                     AND origin_event.admission_state = 'CLOSED'
                     AND origin_event.pointer_version = 1
                     AND origin_event.target_namespace = origin.target_namespace
                     AND origin_event.canonical_tenant_id = origin.canonical_tenant_id
                     AND origin_event.realm_id = origin.realm_id
                     AND origin_event.catalog_revision = origin.catalog_revision
                     AND origin_event.control_plane_request_id = origin.request_id::text
                    WHERE origin.target_namespace = attempt.target_namespace
                      AND origin.request_id = attempt.prior_pointer_request_id
                      AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                      AND origin.realm_id = attempt.realm_id
                      AND origin.request_digest = attempt.prior_pointer_request_digest
                      AND origin.audit_event_id = attempt.prior_pointer_audit_event_id
                      AND origin.pointer_version = attempt.expected_prior_pointer_version
                      AND origin.catalog_revision = attempt.expected_catalog_revision)))
    ) THEN
        RETURN NULL;
    END IF;

    IF selected_status = 'ABORTED' AND EXISTS (
        SELECT 1
        FROM game_session_canonical_initial_admission_attempt attempt
        JOIN game_session_canonical_realm_catalog catalog
          ON catalog.target_namespace = attempt.target_namespace
         AND catalog.canonical_tenant_id = attempt.canonical_tenant_id
         AND catalog.realm_id = attempt.realm_id
         AND catalog.world_slug = attempt.world_slug
         AND catalog.catalog_revision = attempt.expected_catalog_revision
         AND catalog.playable_state_namespace_id = attempt.playable_state_namespace_id
         AND catalog.state_scope = attempt.playable_state_scope
        WHERE attempt.target_namespace = selected_namespace
          AND attempt.initial_admission_request_id = selected_request_id
          AND attempt.status = 'ABORTED'
          AND attempt.pointer_id IS NULL
          AND attempt.audit_event_id IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM gameplay_admission_pointer pointer
              WHERE pointer.representation_version = 3
                AND pointer.target_namespace = attempt.target_namespace
                AND pointer.initial_admission_request_id = attempt.initial_admission_request_id)
          AND NOT EXISTS (
              SELECT 1 FROM gameplay_admission_pointer_event event
              WHERE event.representation_version = 3
                AND event.target_namespace = attempt.target_namespace
                AND event.initial_admission_request_id = attempt.initial_admission_request_id)
          AND ((attempt.origin_kind = 'NO_PRIOR_POINTER'
                AND (
                    -- The protection-bound terminal has already been checked against the exact
                    -- retained owner, intent and Account evidence by the independent terminal
                    -- guard. This negative result never consumes or changes another pointer.
                    (attempt.account_protection_id IS NOT NULL
                        AND attempt.expected_prior_pointer_version IS NULL
                        AND attempt.prior_pointer_request_id IS NULL
                        AND attempt.prior_pointer_request_digest IS NULL
                        AND attempt.prior_pointer_audit_event_id IS NULL)
                    OR (
                        NOT EXISTS (
                            SELECT 1 FROM gameplay_admission_pointer pointer
                            WHERE pointer.realm_id = attempt.realm_id
                              OR (pointer.target_namespace = attempt.target_namespace
                                  AND pointer.canonical_tenant_id = attempt.canonical_tenant_id)
                              OR (pointer.world_slug = attempt.world_slug
                                  AND pointer.realm_slug = catalog.realm_slug)
                              OR (pointer.tenant_id = attempt.game_session_tenant_id
                                  AND pointer.game_instance_id = attempt.game_instance_id))
                        AND NOT EXISTS (
                            SELECT 1 FROM gameplay_admission_pointer_event event
                            WHERE event.realm_id = attempt.realm_id
                              OR (event.target_namespace = attempt.target_namespace
                                  AND event.canonical_tenant_id = attempt.canonical_tenant_id)
                              OR (event.world_slug = attempt.world_slug
                                  AND event.realm_slug = catalog.realm_slug)
                              OR (event.tenant_id = attempt.game_session_tenant_id
                                  AND event.game_instance_id = attempt.game_instance_id))
                        AND NOT EXISTS (
                            SELECT 1 FROM game_session_canonical_closed_admission_pointer_request origin
                            WHERE origin.target_namespace = attempt.target_namespace
                              AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                              AND origin.realm_id = attempt.realm_id)
                        AND NOT EXISTS (
                            SELECT 1 FROM gameplay_initial_admission_bind_catalog legacy_catalog
                            WHERE legacy_catalog.tenant_id = attempt.game_session_tenant_id
                              AND (legacy_catalog.realm_id = attempt.realm_id
                                OR (legacy_catalog.world_slug = attempt.world_slug
                                    AND legacy_catalog.realm_slug = catalog.realm_slug)))
                        AND NOT EXISTS (
                            SELECT 1 FROM gameplay_initial_admission_bind_attempt legacy_attempt
                            WHERE legacy_attempt.tenant_id = attempt.game_session_tenant_id
                              AND (legacy_attempt.realm_id = attempt.realm_id
                                OR legacy_attempt.game_instance_id = attempt.game_instance_id))
                    )))
            OR (attempt.origin_kind = 'EXPECT_CLOSED'
                AND attempt.expected_prior_pointer_version = 1
                AND EXISTS (
                    SELECT 1
                    FROM game_session_canonical_closed_admission_pointer_request origin
                    JOIN gameplay_admission_pointer_event origin_event
                      ON origin_event.id = origin.audit_event_id
                     AND origin_event.representation_version = 2
                     AND origin_event.admission_state = 'CLOSED'
                     AND origin_event.pointer_version = 1
                     AND origin_event.target_namespace = origin.target_namespace
                     AND origin_event.canonical_tenant_id = origin.canonical_tenant_id
                     AND origin_event.realm_id = origin.realm_id
                     AND origin_event.catalog_revision = origin.catalog_revision
                     AND origin_event.control_plane_request_id = origin.request_id::text
                    WHERE origin.target_namespace = attempt.target_namespace
                      AND origin.request_id = attempt.prior_pointer_request_id
                      AND origin.canonical_tenant_id = attempt.canonical_tenant_id
                      AND origin.realm_id = attempt.realm_id
                      AND origin.request_digest = attempt.prior_pointer_request_digest
                      AND origin.audit_event_id = attempt.prior_pointer_audit_event_id
                      AND origin.pointer_version = attempt.expected_prior_pointer_version
                      AND origin.catalog_revision = attempt.expected_catalog_revision
                      -- Protected abort is historical; legacy abort still requires current CLOSED.
                      AND (attempt.account_protection_id IS NOT NULL
                        OR EXISTS (
                            SELECT 1 FROM gameplay_admission_pointer pointer
                            WHERE pointer.representation_version = 2
                              AND pointer.target_namespace = attempt.target_namespace
                              AND pointer.canonical_tenant_id = attempt.canonical_tenant_id
                              AND pointer.realm_id = attempt.realm_id
                              AND pointer.pointer_version = attempt.expected_prior_pointer_version
                              AND pointer.catalog_revision = attempt.expected_catalog_revision
                              AND pointer.admission_state = 'CLOSED')))))
    ) THEN
        RETURN NULL;
    END IF;

    RAISE EXCEPTION 'Canonical initial-admission attempt lacks matching atomic pointer/audit or fenced abort evidence'
        USING ERRCODE = '23514';
END;
$$;
-- [jooq ignore stop]
