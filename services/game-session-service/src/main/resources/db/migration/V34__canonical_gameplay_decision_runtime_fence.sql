-- A lease-bound PROVISIONAL binding decision pins its exact current Game Instance row until a
-- terminal owner operation is implemented.  The decision remains the existing transition row;
-- this migration adds no hold/receipt ledger and never infers release from lease expiry.
-- [jooq ignore start]
CREATE FUNCTION require_running_runtime_for_lease_bound_binding_decision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    runtime_row game_instances%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.admission_lease_id IS NOT NULL
           AND NEW.status IN ('PROVISIONAL', 'AMBIGUOUS') THEN
            RAISE EXCEPTION 'Lease-bound runtime fence must be acquired from an existing PREPARED transition'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_binding_runtime_fence_requires_prepared_transition';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status IN ('PROVISIONAL', 'AMBIGUOUS')
       AND OLD.admission_lease_id IS NOT NULL THEN
        IF NEW.status NOT IN ('PROVISIONAL', 'AMBIGUOUS')
           OR (OLD.status = 'AMBIGUOUS' AND NEW.status <> 'AMBIGUOUS') THEN
            RAISE EXCEPTION 'A lease-bound runtime fence cannot be released without terminal owner reconciliation'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_binding_runtime_fence_terminal_owner_required';
        END IF;

        IF ROW(
            NEW.admission_request_id,
            NEW.admission_lease_id,
            NEW.admission_lease_fence,
            NEW.admission_lease_kind,
            NEW.admission_lease_digest,
            NEW.admission_lease_evidence,
            NEW.admission_resume_episode_id,
            NEW.admission_expected_old_binding_generation,
            NEW.admission_lease_expires_at,
            NEW.admission_target_namespace,
            NEW.admission_target_tenant_slug,
            NEW.admission_target_tenant_id,
            NEW.admission_target_world_slug,
            NEW.admission_target_world_display_name,
            NEW.admission_target_realm_id,
            NEW.admission_target_realm_slug,
            NEW.admission_target_realm_display_name,
            NEW.admission_target_game_session_tenant_id,
            NEW.admission_target_game_instance_id,
            NEW.admission_target_playable_state_namespace_id,
            NEW.admission_target_playable_state_scope,
            NEW.admission_target_canonical_game_instance_id,
            NEW.admission_target_canonical_version_id,
            NEW.admission_target_runtime_version_id,
            NEW.admission_target_catalog_revision,
            NEW.admission_target_pointer_version,
            NEW.admission_target_pointer_snapshot_digest,
            NEW.admission_target_active_world_epoch,
            NEW.admission_target_initial_admission_request_id,
            NEW.admission_target_initial_admission_request_digest,
            NEW.admission_target_origin_kind,
            NEW.admission_target_expected_prior_pointer_version,
            NEW.admission_target_hold_id,
            NEW.admission_target_hold_fence,
            NEW.admission_target_hold_binding_digest,
            NEW.admission_target_audit_event_id,
            NEW.admission_target_owner_proof_digest,
            NEW.admission_target_owner_proof_outcome,
            NEW.admission_target_positive_durable_abort,
            NEW.admission_target_terminal_at,
            NEW.admission_target_character_creation_policy
        ) IS DISTINCT FROM ROW(
            OLD.admission_request_id,
            OLD.admission_lease_id,
            OLD.admission_lease_fence,
            OLD.admission_lease_kind,
            OLD.admission_lease_digest,
            OLD.admission_lease_evidence,
            OLD.admission_resume_episode_id,
            OLD.admission_expected_old_binding_generation,
            OLD.admission_lease_expires_at,
            OLD.admission_target_namespace,
            OLD.admission_target_tenant_slug,
            OLD.admission_target_tenant_id,
            OLD.admission_target_world_slug,
            OLD.admission_target_world_display_name,
            OLD.admission_target_realm_id,
            OLD.admission_target_realm_slug,
            OLD.admission_target_realm_display_name,
            OLD.admission_target_game_session_tenant_id,
            OLD.admission_target_game_instance_id,
            OLD.admission_target_playable_state_namespace_id,
            OLD.admission_target_playable_state_scope,
            OLD.admission_target_canonical_game_instance_id,
            OLD.admission_target_canonical_version_id,
            OLD.admission_target_runtime_version_id,
            OLD.admission_target_catalog_revision,
            OLD.admission_target_pointer_version,
            OLD.admission_target_pointer_snapshot_digest,
            OLD.admission_target_active_world_epoch,
            OLD.admission_target_initial_admission_request_id,
            OLD.admission_target_initial_admission_request_digest,
            OLD.admission_target_origin_kind,
            OLD.admission_target_expected_prior_pointer_version,
            OLD.admission_target_hold_id,
            OLD.admission_target_hold_fence,
            OLD.admission_target_hold_binding_digest,
            OLD.admission_target_audit_event_id,
            OLD.admission_target_owner_proof_digest,
            OLD.admission_target_owner_proof_outcome,
            OLD.admission_target_positive_durable_abort,
            OLD.admission_target_terminal_at,
            OLD.admission_target_character_creation_policy
        ) THEN
            RAISE EXCEPTION 'A lease-bound runtime fence decision is immutable'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_binding_runtime_fence_decision_immutable';
        END IF;
    ELSIF NEW.admission_lease_id IS NOT NULL
          AND NEW.status IN ('PROVISIONAL', 'AMBIGUOUS') THEN
        IF OLD.status <> 'PREPARED'
           OR OLD.admission_lease_id IS NOT NULL
           OR OLD.admission_request_id IS NOT NULL THEN
            RAISE EXCEPTION 'Lease-bound runtime fence must be acquired from the exact PREPARED transition'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_binding_runtime_fence_requires_prepared_transition';
        END IF;
    ELSE
        RETURN NEW;
    END IF;

    -- This SELECT is in the same transaction as markProvisional's transition UPDATE.  Its row
    -- lock serializes the decision with stop/replacement/deletion and is retained through commit.
    SELECT runtime.*
      INTO runtime_row
      FROM game_instances runtime
      JOIN game_session_canonical_instance_launch launch
        ON launch.game_session_tenant_id = runtime.tenant_id
       AND launch.game_instance_id = runtime.id
       AND launch.game_instance_uuid = runtime.game_instance_uuid
      JOIN game_session_canonical_realm_catalog catalog
        ON catalog.target_namespace = launch.target_namespace
       AND catalog.canonical_tenant_id = launch.canonical_tenant_id
       AND catalog.realm_id = launch.canonical_realm_id
       AND catalog.world_slug = launch.world_slug
       AND catalog.playable_state_namespace_id = launch.playable_state_namespace_id
       AND catalog.catalog_revision = NEW.admission_target_catalog_revision
     WHERE runtime.tenant_id = NEW.admission_target_game_session_tenant_id
       AND runtime.id = NEW.admission_target_game_instance_id
       AND runtime.status = 'RUNNING'
       -- Match the decision's actual World ACTIVE read to the owner-recorded RUNNING row. V10
       -- independently requires the matching recorded PREPARING epoch to be exactly one lower.
       AND runtime.run_owned_start_active_epoch = NEW.admission_target_active_world_epoch
       AND runtime.game_instance_uuid = NEW.admission_target_canonical_game_instance_id
       AND runtime.version_id = NEW.admission_target_runtime_version_id
       AND runtime.game_template_id = launch.game_template_id
       AND runtime.launch_descriptor_id = launch.launch_descriptor_id
       AND runtime.release_bundle_id = launch.release_bundle_id
       AND runtime.generation_config_revision = launch.generation_config_revision
       AND runtime.version_state_epoch = launch.version_state_epoch
       AND runtime.run_owned_start_request_id = launch.control_plane_request_id
       AND runtime.run_owned_start_published_release_bundle_ref =
           launch.complete_launch_binding_evidence -> 'descriptor' ->> 'publishedReleaseBundleRef'
       AND launch.target_namespace = NEW.admission_target_namespace
       AND launch.canonical_tenant_id = NEW.admission_target_tenant_id
       AND launch.canonical_realm_id = NEW.admission_target_realm_id
       AND catalog.tenant_slug = NEW.admission_target_tenant_slug
       AND catalog.world_slug = NEW.admission_target_world_slug
       AND catalog.source_world_display_name = NEW.admission_target_world_display_name
       AND catalog.realm_slug = NEW.admission_target_realm_slug
       AND catalog.realm_display_name = NEW.admission_target_realm_display_name
       AND launch.playable_state_namespace_id = NEW.admission_target_playable_state_namespace_id
       AND launch.playable_state_scope = NEW.admission_target_playable_state_scope
       AND launch.version_id = NEW.admission_target_runtime_version_id
       AND launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'canonicalVersionId'
           = NEW.admission_target_canonical_version_id::text
       AND catalog.visible
       AND catalog.public_production
       AND catalog.state_scope = 'SHARED'
       AND launch.public_production
       AND launch.playable_state_scope = 'SHARED'
       AND launch.captured_starting_row_version <= runtime.row_version
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'targetNamespace'
           = launch.target_namespace
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'controlPlaneRequestId'
           = launch.control_plane_request_id
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'canonicalTenantId'
           = launch.canonical_tenant_id::text
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'worldSlug'
           = launch.world_slug
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'gameTemplateId'
           = launch.game_template_id::text
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'launchDescriptorId'
           = launch.launch_descriptor_id
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'versionId'
           = launch.version_id::text
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'releaseBundleId'
           = launch.release_bundle_id::text
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'generationConfigRevision'
           = launch.generation_config_revision
       AND launch.complete_launch_binding_evidence -> 'descriptor' ->> 'versionStateEpoch'
           = launch.version_state_epoch::text
     FOR UPDATE OF runtime;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Lease-bound binding decision requires the exact current RUNNING launch row'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_binding_runtime_fence_exact_running_launch_required';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_canonical_binding_runtime_fence
    BEFORE INSERT OR UPDATE ON game_session_canonical_binding_transition
    FOR EACH ROW
    EXECUTE FUNCTION require_running_runtime_for_lease_bound_binding_decision();

CREATE FUNCTION reject_runtime_row_mutation_during_lease_bound_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    locked_transition RECORD;
BEGIN
    -- Lock every candidate transition for this runtime identity, including PREPARED rows. The
    -- target columns are populated only as the decision is acquired, so matching only those
    -- columns would miss a command whose snapshot predates that commit. The candidate identity is
    -- already durable before this point and is unchanged by PREPARED -> PROVISIONAL. SELECT FOR
    -- UPDATE waits/rechecks the current transition tuple; opposing runtime/transition lock order
    -- may deadlock, but either transaction aborts rather than allowing STOPPED + PROVISIONAL.
    FOR locked_transition IN
        SELECT transition.status, transition.admission_lease_id
          FROM game_session_canonical_binding_transition transition
          JOIN game_session_canonical_gameplay_binding_inventory candidate
            ON candidate.binding_ref = transition.candidate_binding_ref
          JOIN game_session_canonical_instance_launch launch
            ON launch.canonical_tenant_id = candidate.tenant_id
           AND launch.game_session_tenant_id = OLD.tenant_id
           AND launch.game_instance_id = OLD.id
           AND launch.game_instance_uuid = OLD.game_instance_uuid
         WHERE candidate.tenant_id = launch.canonical_tenant_id
           AND candidate.runtime_game_instance_id = OLD.id
           AND candidate.game_instance_id = OLD.game_instance_uuid
         ORDER BY transition.transition_id
         FOR UPDATE OF transition
    LOOP
        IF locked_transition.admission_lease_id IS NOT NULL
           AND locked_transition.status IN ('PROVISIONAL', 'AMBIGUOUS') THEN
            RAISE EXCEPTION 'Game Instance runtime row is fenced by an unresolved lease-bound binding decision'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_binding_runtime_row_mutation_fenced';
        END IF;
    END LOOP;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_instances_lease_bound_binding_runtime_fence
    BEFORE UPDATE OR DELETE ON game_instances
    FOR EACH ROW
    EXECUTE FUNCTION reject_runtime_row_mutation_during_lease_bound_binding();

CREATE FUNCTION reject_truncate_during_lease_bound_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM game_session_canonical_binding_transition transition
         WHERE transition.status IN ('PROVISIONAL', 'AMBIGUOUS')
           AND transition.admission_lease_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'TRUNCATE cannot remove rows protected by an unresolved lease-bound binding decision'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_binding_runtime_truncate_fenced';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER game_instances_lease_bound_binding_no_truncate
    BEFORE TRUNCATE ON game_instances
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_truncate_during_lease_bound_binding();

CREATE FUNCTION reject_unowned_lease_bound_binding_release()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('PROVISIONAL', 'AMBIGUOUS')
           AND OLD.admission_lease_id IS NOT NULL THEN
            RAISE EXCEPTION 'An unresolved lease-bound binding decision cannot be deleted'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_binding_runtime_fence_delete_owner_required';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status IN ('PROVISIONAL', 'AMBIGUOUS')
       AND OLD.admission_lease_id IS NOT NULL
       AND (NEW.status NOT IN ('PROVISIONAL', 'AMBIGUOUS')
            OR (OLD.status = 'AMBIGUOUS' AND NEW.status <> 'AMBIGUOUS')) THEN
        RAISE EXCEPTION 'An unresolved lease-bound binding decision requires terminal owner reconciliation'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_binding_runtime_fence_terminal_owner_required';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_canonical_binding_runtime_fence_release_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_binding_transition
    FOR EACH ROW
    EXECUTE FUNCTION reject_unowned_lease_bound_binding_release();

CREATE FUNCTION reject_truncate_unresolved_lease_bound_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM game_session_canonical_binding_transition transition
         WHERE transition.status IN ('PROVISIONAL', 'AMBIGUOUS')
           AND transition.admission_lease_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'TRUNCATE cannot discard unresolved lease-bound binding decisions'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_binding_runtime_fence_transition_truncate_guard';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER game_session_canonical_binding_runtime_fence_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_binding_transition
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_truncate_unresolved_lease_bound_binding();
-- [jooq ignore stop]
