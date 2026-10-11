-- Forward-only correction; preserve the already published V28 migration bytes.
-- [jooq ignore start]
-- Fresh source-bound tenant keys may back canonical OPEN pointer and audit rows only when the
-- exact pending initial-admission ledger entry, source-qualified tenant association, and launch
-- binding all agree. Numeric-only tenant writes retain the V25/V26 denial.
CREATE OR REPLACE FUNCTION reserve_game_session_tenant_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_kind VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.tenant_id IS NOT DISTINCT FROM OLD.tenant_id THEN
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.tenant_id <= 0 THEN
        RAISE EXCEPTION 'Game Session tenant scope must be positive'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_tenant_scope_reservation_positive';
    END IF;

    INSERT INTO game_session_tenant_scope_reservation
        (game_session_tenant_id, reservation_kind)
    VALUES (NEW.tenant_id, 'LEGACY_OCCUPIED')
    ON CONFLICT (game_session_tenant_id) DO NOTHING;

    SELECT reservation_kind INTO current_kind
      FROM game_session_tenant_scope_reservation
     WHERE game_session_tenant_id = NEW.tenant_id
     FOR KEY SHARE;

    IF current_kind = 'FRESH_SOURCE_BOUND' AND TG_TABLE_NAME = 'game_instances'
       AND EXISTS (
           SELECT 1
             FROM game_session_tenant_canonical_claim claim
             JOIN game_session_fresh_tenant_association association
               ON association.target_namespace = claim.target_namespace
              AND association.canonical_tenant_id = claim.canonical_tenant_id
              AND association.legacy_game_session_tenant_id = claim.legacy_game_session_tenant_id
              AND association.association_operation_id = claim.association_operation_id
              AND association.association_kind = claim.association_kind
            WHERE claim.legacy_game_session_tenant_id = NEW.tenant_id
              AND claim.association_kind = 'FRESH_SOURCE_BOUND'
              AND association.association_kind = 'FRESH_SOURCE_BOUND'
       ) THEN
        RETURN NEW;
    END IF;

    IF current_kind = 'FRESH_SOURCE_BOUND'
       AND TG_TABLE_NAME IN ('gameplay_admission_pointer', 'gameplay_admission_pointer_event') THEN
      IF NEW.representation_version = 3
         AND NEW.admission_state = 'OPEN'
         AND EXISTS (
           SELECT 1
             FROM game_session_canonical_initial_admission_attempt attempt
             JOIN game_session_canonical_instance_launch launch
               ON launch.target_namespace = attempt.target_namespace
              AND launch.canonical_tenant_id = attempt.canonical_tenant_id
              AND launch.canonical_realm_id = attempt.realm_id
              AND launch.world_slug = attempt.world_slug
              AND launch.playable_state_namespace_id = attempt.playable_state_namespace_id
              AND launch.playable_state_scope = attempt.playable_state_scope
              AND launch.game_session_tenant_id = attempt.game_session_tenant_id
              AND launch.game_instance_id = attempt.game_instance_id
              AND launch.game_instance_uuid = attempt.canonical_game_instance_id
              AND launch.version_id = attempt.runtime_version_id
              AND launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
                  = attempt.canonical_version_id::text
             JOIN game_session_tenant_canonical_claim claim
               ON claim.target_namespace = launch.target_namespace
              AND claim.canonical_tenant_id = launch.canonical_tenant_id
              AND claim.legacy_game_session_tenant_id = launch.game_session_tenant_id
              AND claim.association_operation_id = launch.tenant_association_operation_id
              AND claim.association_request_id = launch.tenant_association_request_id
              AND claim.association_kind = launch.tenant_association_kind
              AND claim.association_kind = 'FRESH_SOURCE_BOUND'
              AND claim.reservation_kind = 'FRESH_SOURCE_BOUND'
             JOIN game_session_fresh_tenant_association source_association
               ON source_association.target_namespace = claim.target_namespace
              AND source_association.canonical_tenant_id = claim.canonical_tenant_id
              AND source_association.legacy_game_session_tenant_id = claim.legacy_game_session_tenant_id
              AND source_association.association_operation_id = claim.association_operation_id
              AND source_association.association_request_id = launch.tenant_association_request_id
              AND source_association.source_schema_version = launch.tenant_source_schema_version
              AND source_association.source_target_namespace = launch.tenant_source_target_namespace
              AND source_association.source_request_id = launch.tenant_source_request_id
              AND source_association.source_canonical_tenant_id = launch.tenant_source_canonical_tenant_id
              AND source_association.source_game_row_id = launch.tenant_source_game_row_id
              AND source_association.source_game_tenant_key = launch.tenant_source_game_tenant_key
              AND source_association.provenance_kind = launch.tenant_source_provenance_kind
              AND source_association.association_kind = launch.tenant_association_kind
            WHERE attempt.status = 'PENDING'
              AND attempt.target_namespace = NEW.target_namespace
              AND attempt.initial_admission_request_id = NEW.initial_admission_request_id
              AND attempt.request_digest = NEW.initial_admission_request_digest
              AND attempt.canonical_tenant_id = NEW.canonical_tenant_id
              AND attempt.world_slug = NEW.world_slug
              AND attempt.realm_id = NEW.realm_id
              AND attempt.playable_state_namespace_id = NEW.playable_state_namespace_id
              AND attempt.playable_state_scope = NEW.state_scope
              AND attempt.canonical_game_instance_id = NEW.canonical_game_instance_id
              AND attempt.canonical_version_id = NEW.canonical_version_id
              AND attempt.game_session_tenant_id = NEW.tenant_id
              AND attempt.game_instance_id = NEW.game_instance_id
              AND attempt.runtime_version_id = NEW.runtime_version_id
              AND attempt.expected_catalog_revision = NEW.catalog_revision
              AND attempt.origin_kind = NEW.initial_admission_origin_kind
              AND attempt.expected_prior_pointer_version IS NOT DISTINCT FROM
                  NEW.initial_admission_prior_pointer_version
              AND attempt.active_lifecycle_epoch = NEW.initial_admission_active_epoch
              AND attempt.hold_id = NEW.initial_admission_hold_id
              AND attempt.hold_fence = NEW.initial_admission_hold_fence
              AND attempt.hold_binding_digest = NEW.initial_admission_hold_binding_digest
         ) THEN
        IF TG_TABLE_NAME = 'gameplay_admission_pointer' THEN
            IF NEW.last_updated_by IS DISTINCT FROM 'game-session-canonical-initial-admission'
               OR NEW.last_update_reason IS DISTINCT FROM 'World-held initial admission' THEN
                RAISE EXCEPTION 'Canonical OPEN pointer requires its exact initial-admission owner audit identity'
                    USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_canonical_initial_admission_owner_write';
            END IF;
        END IF;
        IF TG_TABLE_NAME = 'gameplay_admission_pointer_event' THEN
            IF NEW.control_plane_request_id IS DISTINCT FROM NEW.initial_admission_request_id
               OR NEW.actor_principal IS DISTINCT FROM 'game-session-canonical-initial-admission'
               OR NEW.reason IS DISTINCT FROM 'World-held initial admission' THEN
                RAISE EXCEPTION 'Canonical OPEN audit event requires its exact initial-admission owner identity'
                    USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_canonical_initial_admission_owner_write';
            END IF;
        END IF;
        RETURN NEW;
      END IF;
    END IF;

    IF current_kind IS DISTINCT FROM 'LEGACY_OCCUPIED' THEN
        RAISE EXCEPTION 'Numeric-only tenant scope cannot use a fresh source-bound key'
            USING ERRCODE = '23514', CONSTRAINT = 'chk_gs_numeric_fresh_tenant_scope_denied';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
