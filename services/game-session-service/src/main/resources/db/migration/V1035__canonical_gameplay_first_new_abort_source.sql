-- Retain Account's actual first-NEW ABORTED result on the existing transition. The cleanup UUID
-- is not a parallel decision ledger; the immutable Account lease and existing transition remain
-- the authority source.
ALTER TABLE game_session_canonical_binding_transition
    ADD COLUMN admission_orphan_cleanup_id uuid,
    ADD CONSTRAINT chk_gs_canonical_binding_transition_abort_cleanup CHECK (
        admission_orphan_cleanup_id IS NULL
        OR (status = 'ABORTED'
            AND admission_lease_id IS NOT NULL
            AND admission_lease_kind = 'NEW_BINDING'
            AND expected_prior_binding_ref IS NULL
            AND expected_prior_binding_generation IS NULL
            AND admission_orphan_cleanup_id <>
                '00000000-0000-0000-0000-000000000000'::uuid));

CREATE UNIQUE INDEX uq_gs_canonical_binding_abort_cleanup
    ON game_session_canonical_binding_transition (admission_orphan_cleanup_id)
    WHERE admission_orphan_cleanup_id IS NOT NULL;

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION require_running_runtime_for_lease_bound_binding_decision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    runtime_row game_instances%ROWTYPE;
    approved_first_new_abort boolean := false;
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

    -- Definitive Account abort is a permanent source, including after cleanup retries.
    -- Inventory advancement may retain the source, but may never rewrite its decision.
    IF OLD.status = 'ABORTED' AND OLD.admission_orphan_cleanup_id IS NOT NULL THEN
        IF (to_jsonb(NEW) - 'inventory_revision')
              IS DISTINCT FROM (to_jsonb(OLD) - 'inventory_revision')
           OR NEW.inventory_revision < OLD.inventory_revision THEN
            RAISE EXCEPTION 'Definitive first-NEW Account abort source is immutable'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_first_new_abort_terminal_source_immutable';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status IN ('PROVISIONAL', 'AMBIGUOUS')
       AND OLD.admission_lease_id IS NOT NULL THEN
        IF OLD.status = 'PROVISIONAL'
           AND NEW.status = 'ABORTED'
           AND OLD.admission_lease_kind = 'NEW_BINDING'
           AND OLD.expected_prior_binding_ref IS NULL
           AND OLD.expected_prior_binding_generation IS NULL
           AND OLD.admission_orphan_cleanup_id IS NULL
           AND NEW.admission_orphan_cleanup_id IS NOT NULL
           AND NEW.admission_orphan_cleanup_id <>
               '00000000-0000-0000-0000-000000000000'::uuid
           AND NEW.inventory_revision > OLD.inventory_revision THEN
            approved_first_new_abort := true;
        ELSIF NEW.status NOT IN ('PROVISIONAL', 'AMBIGUOUS')
           OR (OLD.status = 'AMBIGUOUS' AND NEW.status <> 'AMBIGUOUS') THEN
            RAISE EXCEPTION 'A lease-bound runtime fence cannot be released without definitive first-NEW Account abort evidence'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_binding_runtime_fence_terminal_owner_required';
        END IF;

        IF approved_first_new_abort
           AND ROW(
                NEW.transition_id, NEW.tenant_id, NEW.playable_state_namespace_id,
                NEW.character_id, NEW.expected_prior_binding_ref,
                NEW.expected_prior_binding_generation, NEW.candidate_binding_ref,
                NEW.candidate_binding_generation, NEW.candidate_account_index_fence,
                NEW.issuer_reservation_id, NEW.created_at,
                NEW.account_coverage_state, NEW.account_coverage_operation_id,
                NEW.account_coverage_lifecycle, NEW.account_coverage_operation_fence,
                NEW.account_admission_fence_kind, NEW.account_admission_fence,
                NEW.historical_account_admission_fence_kind,
                NEW.historical_account_admission_fence, NEW.resolved_scope_kind,
                NEW.resolved_scope_account_id, NEW.account_coverage_fence,
                NEW.account_coverage_generation, NEW.account_inventory_snapshot_revision
            ) IS DISTINCT FROM ROW(
                OLD.transition_id, OLD.tenant_id, OLD.playable_state_namespace_id,
                OLD.character_id, OLD.expected_prior_binding_ref,
                OLD.expected_prior_binding_generation, OLD.candidate_binding_ref,
                OLD.candidate_binding_generation, OLD.candidate_account_index_fence,
                OLD.issuer_reservation_id, OLD.created_at,
                OLD.account_coverage_state, OLD.account_coverage_operation_id,
                OLD.account_coverage_lifecycle, OLD.account_coverage_operation_fence,
                OLD.account_admission_fence_kind, OLD.account_admission_fence,
                OLD.historical_account_admission_fence_kind,
                OLD.historical_account_admission_fence, OLD.resolved_scope_kind,
                OLD.resolved_scope_account_id, OLD.account_coverage_fence,
                OLD.account_coverage_generation, OLD.account_inventory_snapshot_revision
            ) THEN
            RAISE EXCEPTION 'First-NEW abort cannot change its transition, Account-control, or inventory source'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_first_new_abort_transition_source_immutable';
        END IF;

        IF NOT approved_first_new_abort
           AND NEW.admission_orphan_cleanup_id IS DISTINCT FROM OLD.admission_orphan_cleanup_id THEN
            RAISE EXCEPTION 'A lease-bound cleanup identity requires the exact first-NEW Account abort transition'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_binding_runtime_fence_abort_cleanup_identity';
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

    -- This SELECT is in the same transaction as markProvisional's transition UPDATE. Its row
    -- lock serializes the decision with stop/replacement/deletion and is retained through commit.
    SELECT runtime.*
      INTO runtime_row
      FROM game_instances runtime
      JOIN game_session_canonical_instance_launch launch
        ON launch.game_session_tenant_id = runtime.tenant_id
       AND launch.game_instance_id = runtime.id
       AND launch.game_instance_uuid = runtime.game_instance_uuid
      JOIN game_session_canonical_gameplay_binding_inventory candidate
        ON candidate.binding_ref = NEW.candidate_binding_ref
       AND candidate.tenant_id = launch.canonical_tenant_id
       AND candidate.runtime_game_instance_id = runtime.id
       AND candidate.game_instance_id = runtime.game_instance_uuid
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
        RAISE EXCEPTION 'Lease-bound binding decision requires the exact current RUNNING launch row and candidate identity'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_binding_runtime_fence_exact_running_launch_required';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION reject_unowned_lease_bound_binding_release()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF (OLD.status IN ('PROVISIONAL', 'AMBIGUOUS')
                AND OLD.admission_lease_id IS NOT NULL)
           OR (OLD.status = 'ABORTED'
                AND OLD.admission_lease_id IS NOT NULL
                AND OLD.admission_orphan_cleanup_id IS NOT NULL) THEN
            RAISE EXCEPTION 'A retained lease-bound binding source cannot be deleted'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_binding_runtime_fence_delete_owner_required';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status IN ('PROVISIONAL', 'AMBIGUOUS')
       AND OLD.admission_lease_id IS NOT NULL
       AND (NEW.status NOT IN ('PROVISIONAL', 'AMBIGUOUS')
            OR (OLD.status = 'AMBIGUOUS' AND NEW.status <> 'AMBIGUOUS')) THEN
        IF OLD.status = 'PROVISIONAL'
           AND NEW.status = 'ABORTED'
           AND OLD.admission_lease_kind = 'NEW_BINDING'
           AND OLD.expected_prior_binding_ref IS NULL
           AND OLD.expected_prior_binding_generation IS NULL
           AND OLD.admission_orphan_cleanup_id IS NULL
           AND NEW.admission_orphan_cleanup_id IS NOT NULL
           AND NEW.admission_orphan_cleanup_id <>
               '00000000-0000-0000-0000-000000000000'::uuid
           AND NEW.inventory_revision > OLD.inventory_revision THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'An unresolved lease-bound binding decision requires exact terminal owner reconciliation'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_binding_runtime_fence_terminal_owner_required';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION reject_truncate_unresolved_lease_bound_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM game_session_canonical_binding_transition transition
         WHERE (transition.status IN ('PROVISIONAL', 'AMBIGUOUS')
                AND transition.admission_lease_id IS NOT NULL)
            OR (transition.status = 'ABORTED'
                AND transition.admission_lease_id IS NOT NULL
                AND transition.admission_orphan_cleanup_id IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'TRUNCATE cannot discard unresolved or retained-abort lease-bound binding decisions'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_binding_runtime_fence_transition_truncate_guard';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION require_first_new_abort_reservation_release()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.lifecycle = 'RESERVED' AND NEW.lifecycle = 'RELEASE_PENDING' THEN
        IF NEW.inventory_revision <= OLD.inventory_revision
           OR ROW(
                NEW.reservation_id, NEW.binding_ref, NEW.account_id, NEW.tenant_id,
                NEW.game_instance_id, NEW.runtime_game_instance_id, NEW.session_id,
                NEW.binding_generation, NEW.transition_id, NEW.issuer_id,
                NEW.issuer_auth_generation, NEW.playable_state_namespace_id,
                NEW.character_id, NEW.playable_state_scope, NEW.region_id, NEW.region_epoch,
                NEW.partition_id, NEW.issuer_index_layout_version,
                NEW.issuer_index_partition_count, NEW.issuer_index_partition_capacity,
                NEW.reservation_fence, NEW.issuer_coverage_operation_id,
                NEW.issuer_coverage_operation_fence, NEW.coverage_fence,
                NEW.inventory_snapshot_revision, NEW.created_at
            ) IS DISTINCT FROM ROW(
                OLD.reservation_id, OLD.binding_ref, OLD.account_id, OLD.tenant_id,
                OLD.game_instance_id, OLD.runtime_game_instance_id, OLD.session_id,
                OLD.binding_generation, OLD.transition_id, OLD.issuer_id,
                OLD.issuer_auth_generation, OLD.playable_state_namespace_id,
                OLD.character_id, OLD.playable_state_scope, OLD.region_id, OLD.region_epoch,
                OLD.partition_id, OLD.issuer_index_layout_version,
                OLD.issuer_index_partition_count, OLD.issuer_index_partition_capacity,
                OLD.reservation_fence, OLD.issuer_coverage_operation_id,
                OLD.issuer_coverage_operation_fence, OLD.coverage_fence,
                OLD.inventory_snapshot_revision, OLD.created_at
            ) THEN
            RAISE EXCEPTION 'First-NEW abort reservation transition changed its retained owner tuple'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_first_new_abort_reservation_immutable';
        END IF;

        IF NOT EXISTS (
            SELECT 1
              FROM game_session_canonical_binding_transition transition
              JOIN game_session_canonical_gameplay_binding_inventory candidate
                ON candidate.binding_ref = transition.candidate_binding_ref
             WHERE transition.transition_id = NEW.transition_id
               AND transition.status = 'ABORTED'
               AND transition.admission_lease_id IS NOT NULL
               AND transition.admission_lease_kind = 'NEW_BINDING'
               AND transition.expected_prior_binding_ref IS NULL
               AND transition.expected_prior_binding_generation IS NULL
               AND transition.admission_orphan_cleanup_id IS NOT NULL
               AND transition.admission_orphan_cleanup_id <>
                   '00000000-0000-0000-0000-000000000000'::uuid
               AND transition.inventory_revision = NEW.inventory_revision
               AND transition.issuer_reservation_id = NEW.reservation_id
               AND transition.candidate_binding_ref = NEW.binding_ref
               AND transition.candidate_binding_generation = NEW.binding_generation
               AND transition.tenant_id = NEW.tenant_id
               AND transition.playable_state_namespace_id = NEW.playable_state_namespace_id
               AND transition.character_id = NEW.character_id
               AND transition.candidate_account_index_fence = candidate.account_index_fence
               AND candidate.lifecycle = 'PROVISIONAL'
               AND candidate.inventory_revision = OLD.inventory_revision
               AND candidate.transition_id = NEW.transition_id
               AND candidate.issuer_reservation_id = NEW.reservation_id
               AND candidate.account_id = NEW.account_id
               AND candidate.tenant_id = NEW.tenant_id
               AND candidate.playable_state_namespace_id = NEW.playable_state_namespace_id
               AND candidate.character_id = NEW.character_id
               AND candidate.game_instance_id = NEW.game_instance_id
               AND candidate.runtime_game_instance_id = NEW.runtime_game_instance_id
               AND candidate.session_id = NEW.session_id
               AND candidate.region_id = NEW.region_id
               AND candidate.region_epoch = NEW.region_epoch
               AND candidate.issuer_id = NEW.issuer_id
               AND candidate.issuer_auth_generation = NEW.issuer_auth_generation
               AND candidate.issuer_index_layout_version = NEW.issuer_index_layout_version
               AND candidate.issuer_index_partition_count = NEW.issuer_index_partition_count
               AND candidate.issuer_index_partition_capacity = NEW.issuer_index_partition_capacity
               AND candidate.binding_generation = NEW.binding_generation
               AND candidate.binding_ref = NEW.binding_ref
               AND candidate.account_index_fence = transition.candidate_account_index_fence
        ) THEN
            RAISE EXCEPTION 'Only an exact retained first-NEW Account abort may release its reservation'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_first_new_abort_reservation_source_required';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_first_new_abort_reservation_release
    BEFORE UPDATE ON game_session_canonical_issuer_partition_reservation
    FOR EACH ROW
    EXECUTE FUNCTION require_first_new_abort_reservation_release();

CREATE FUNCTION require_first_new_abort_candidate_retention()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.lifecycle = 'PROVISIONAL' AND NEW.lifecycle = 'TERMINAL_UNRESOLVED' THEN
        IF NEW.inventory_revision <= OLD.inventory_revision
           OR ROW(
                NEW.binding_ref, NEW.account_id, NEW.tenant_id,
                NEW.playable_state_namespace_id, NEW.character_id, NEW.playable_state_scope,
                NEW.game_instance_id, NEW.runtime_game_instance_id, NEW.session_id,
                NEW.binding_generation, NEW.region_id, NEW.region_epoch, NEW.issuer_id,
                NEW.issuer_auth_generation, NEW.issuer_index_layout_version,
                NEW.issuer_index_partition_count, NEW.issuer_index_partition_capacity,
                NEW.issuer_partition_id, NEW.account_index_fence,
                NEW.issuer_reservation_id, NEW.transition_id,
                NEW.account_index_state, NEW.issuer_index_state, NEW.created_at
            ) IS DISTINCT FROM ROW(
                OLD.binding_ref, OLD.account_id, OLD.tenant_id,
                OLD.playable_state_namespace_id, OLD.character_id, OLD.playable_state_scope,
                OLD.game_instance_id, OLD.runtime_game_instance_id, OLD.session_id,
                OLD.binding_generation, OLD.region_id, OLD.region_epoch, OLD.issuer_id,
                OLD.issuer_auth_generation, OLD.issuer_index_layout_version,
                OLD.issuer_index_partition_count, OLD.issuer_index_partition_capacity,
                OLD.issuer_partition_id, OLD.account_index_fence,
                OLD.issuer_reservation_id, OLD.transition_id,
                OLD.account_index_state, OLD.issuer_index_state, OLD.created_at
            ) THEN
            RAISE EXCEPTION 'First-NEW abort candidate transition changed its retained owner tuple'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_first_new_abort_candidate_immutable';
        END IF;

        IF NOT EXISTS (
            SELECT 1
              FROM game_session_canonical_binding_transition transition
              JOIN game_session_canonical_issuer_partition_reservation reservation
                ON reservation.reservation_id = transition.issuer_reservation_id
             WHERE transition.transition_id = NEW.transition_id
               AND transition.status = 'ABORTED'
               AND transition.admission_lease_id IS NOT NULL
               AND transition.admission_lease_kind = 'NEW_BINDING'
               AND transition.expected_prior_binding_ref IS NULL
               AND transition.expected_prior_binding_generation IS NULL
               AND transition.admission_orphan_cleanup_id IS NOT NULL
               AND transition.inventory_revision = NEW.inventory_revision
               AND transition.candidate_binding_ref = NEW.binding_ref
               AND transition.candidate_binding_generation = NEW.binding_generation
               AND transition.candidate_account_index_fence = NEW.account_index_fence
               AND transition.issuer_reservation_id = NEW.issuer_reservation_id
               AND transition.tenant_id = NEW.tenant_id
               AND transition.playable_state_namespace_id = NEW.playable_state_namespace_id
               AND transition.character_id = NEW.character_id
               AND reservation.lifecycle = 'RELEASE_PENDING'
               AND reservation.inventory_revision = NEW.inventory_revision
               AND reservation.transition_id = NEW.transition_id
               AND reservation.binding_ref = NEW.binding_ref
               AND reservation.binding_generation = NEW.binding_generation
               AND reservation.account_id = NEW.account_id
               AND reservation.tenant_id = NEW.tenant_id
               AND reservation.playable_state_namespace_id = NEW.playable_state_namespace_id
               AND reservation.character_id = NEW.character_id
               AND reservation.game_instance_id = NEW.game_instance_id
               AND reservation.runtime_game_instance_id = NEW.runtime_game_instance_id
               AND reservation.session_id = NEW.session_id
               AND reservation.region_id = NEW.region_id
               AND reservation.region_epoch = NEW.region_epoch
               AND reservation.issuer_id = NEW.issuer_id
               AND reservation.issuer_auth_generation = NEW.issuer_auth_generation
               AND reservation.issuer_index_layout_version = NEW.issuer_index_layout_version
               AND reservation.issuer_index_partition_count = NEW.issuer_index_partition_count
               AND reservation.issuer_index_partition_capacity = NEW.issuer_index_partition_capacity
        ) THEN
            RAISE EXCEPTION 'Only an exact retained first-NEW Account abort may retain its candidate unresolved'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_first_new_abort_candidate_source_required';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_first_new_abort_candidate_retention
    BEFORE UPDATE ON game_session_canonical_gameplay_binding_inventory
    FOR EACH ROW
    EXECUTE FUNCTION require_first_new_abort_candidate_retention();

CREATE FUNCTION require_first_new_abort_source_complete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM game_session_canonical_binding_transition transition
          JOIN game_session_canonical_gameplay_binding_inventory candidate
            ON candidate.binding_ref = transition.candidate_binding_ref
          JOIN game_session_canonical_issuer_partition_reservation reservation
            ON reservation.reservation_id = transition.issuer_reservation_id
          JOIN game_session_canonical_binding_account_index_obligation account_obligation
            ON account_obligation.transition_id = transition.transition_id
           AND account_obligation.obligation_ordinal = 0
          JOIN game_session_canonical_binding_issuer_index_obligation issuer_obligation
            ON issuer_obligation.transition_id = transition.transition_id
           AND issuer_obligation.binding_ref = transition.candidate_binding_ref
          JOIN game_session_canonical_binding_region_bridge_obligation region_obligation
            ON region_obligation.transition_id = transition.transition_id
         WHERE transition.transition_id = NEW.transition_id
           AND transition.status = 'ABORTED'
           AND transition.admission_lease_id IS NOT NULL
           AND transition.admission_lease_kind = 'NEW_BINDING'
           AND transition.admission_lease_evidence IS NOT NULL
           AND transition.admission_lease_digest IS NOT NULL
           AND transition.expected_prior_binding_ref IS NULL
           AND transition.expected_prior_binding_generation IS NULL
           AND transition.admission_orphan_cleanup_id IS NOT NULL
           AND transition.admission_orphan_cleanup_id <>
               '00000000-0000-0000-0000-000000000000'::uuid
           AND transition.inventory_revision = candidate.inventory_revision
           AND transition.inventory_revision = reservation.inventory_revision
           AND transition.inventory_revision = account_obligation.inventory_revision
           AND transition.inventory_revision = issuer_obligation.inventory_revision
           AND transition.inventory_revision = region_obligation.inventory_revision
           AND transition.candidate_binding_ref = candidate.binding_ref
           AND transition.candidate_binding_generation = candidate.binding_generation
           AND transition.candidate_account_index_fence = candidate.account_index_fence
           AND transition.issuer_reservation_id = candidate.issuer_reservation_id
           AND transition.tenant_id = candidate.tenant_id
           AND transition.playable_state_namespace_id = candidate.playable_state_namespace_id
           AND transition.character_id = candidate.character_id
           AND candidate.lifecycle = 'TERMINAL_UNRESOLVED'
           AND reservation.lifecycle = 'RELEASE_PENDING'
           AND reservation.transition_id = transition.transition_id
           AND reservation.binding_ref = candidate.binding_ref
           AND reservation.binding_generation = candidate.binding_generation
           AND reservation.reservation_fence IS NOT NULL
           AND reservation.account_id = candidate.account_id
           AND reservation.tenant_id = candidate.tenant_id
           AND reservation.playable_state_namespace_id = candidate.playable_state_namespace_id
           AND reservation.character_id = candidate.character_id
           AND reservation.game_instance_id = candidate.game_instance_id
           AND reservation.runtime_game_instance_id = candidate.runtime_game_instance_id
           AND reservation.session_id = candidate.session_id
           AND reservation.region_id = candidate.region_id
           AND reservation.region_epoch = candidate.region_epoch
           AND reservation.issuer_id = candidate.issuer_id
           AND reservation.issuer_auth_generation = candidate.issuer_auth_generation
           AND reservation.issuer_index_layout_version = candidate.issuer_index_layout_version
           AND reservation.issuer_index_partition_count = candidate.issuer_index_partition_count
           AND reservation.issuer_index_partition_capacity = candidate.issuer_index_partition_capacity
           AND account_obligation.binding_ref = candidate.binding_ref
           AND account_obligation.account_id = candidate.account_id
           AND account_obligation.tenant_id = candidate.tenant_id
           AND account_obligation.action = 'ADD_OR_RETAIN'
           AND account_obligation.binding_generation = candidate.binding_generation
           AND account_obligation.expected_prior_generation IS NULL
           AND account_obligation.account_index_fence = candidate.account_index_fence
           AND account_obligation.execution_phase = 'BEFORE_FINAL_CAS'
           AND account_obligation.status = 'REQUIRED'
           AND (SELECT count(*)
                  FROM game_session_canonical_binding_account_index_obligation extra_account
                 WHERE extra_account.transition_id = transition.transition_id) = 1
           AND issuer_obligation.reservation_id = reservation.reservation_id
           AND issuer_obligation.binding_ref = candidate.binding_ref
           AND issuer_obligation.binding_generation = candidate.binding_generation
           AND issuer_obligation.status = 'REQUIRED'
           AND (SELECT count(*)
                  FROM game_session_canonical_binding_issuer_index_obligation extra_issuer
                 WHERE extra_issuer.transition_id = transition.transition_id) = 1
           AND region_obligation.binding_ref = candidate.binding_ref
           AND region_obligation.binding_generation = candidate.binding_generation
           AND region_obligation.account_id = candidate.account_id
           AND region_obligation.tenant_id = candidate.tenant_id
           AND region_obligation.game_instance_id = candidate.game_instance_id
           AND region_obligation.runtime_game_instance_id = candidate.runtime_game_instance_id
           AND region_obligation.session_id = candidate.session_id
           AND region_obligation.region_id = candidate.region_id
           AND region_obligation.region_epoch = candidate.region_epoch
           AND region_obligation.status = 'REQUIRED'
           AND NOT EXISTS (
               SELECT 1
                 FROM game_session_canonical_gameplay_binding_inventory active
                WHERE active.tenant_id = candidate.tenant_id
                  AND active.playable_state_namespace_id = candidate.playable_state_namespace_id
                  AND active.character_id = candidate.character_id
                  AND active.lifecycle = 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'First-NEW Account abort requires its exact retained candidate, reservation, and unresolved obligations'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_first_new_abort_source_incomplete';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER game_session_first_new_abort_source_atomic
    AFTER UPDATE ON game_session_canonical_binding_transition
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status = 'ABORTED' AND NEW.admission_lease_id IS NOT NULL)
    EXECUTE FUNCTION require_first_new_abort_source_complete();

-- [jooq ignore stop]
