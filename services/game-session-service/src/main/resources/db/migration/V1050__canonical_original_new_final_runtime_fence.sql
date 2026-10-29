-- Extend the existing lease-bound runtime fence only for actual original-NEW final receipt
-- persistence. V1044's acquisition, ambiguity, definitive abort and complete current RUNNING
-- launch/Human/catalog validation remain intact. V1049 retains the all-or-none receipt CHECK
-- and original-source guard; SQL validity alone never establishes physical Redis admission.
-- REQUIRED and definitive-abort RELEASED retain their original meanings. ACKNOWLEDGED is a
-- distinct final original-NEW successor, admitted only by the matching proof guard below.
ALTER TABLE game_session_canonical_binding_issuer_index_obligation
    DROP CONSTRAINT chk_gs_canonical_binding_issuer_index_obligation;
ALTER TABLE game_session_canonical_binding_issuer_index_obligation
    ADD CONSTRAINT chk_gs_canonical_binding_issuer_index_obligation CHECK (
        binding_generation > 0
        AND inventory_revision > 0
        AND ((status IN ('REQUIRED', 'ACKNOWLEDGED') AND local_release_operation_id IS NULL)
            OR (status = 'RELEASED' AND local_release_operation_id IS NOT NULL
                AND local_release_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid)));

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION require_running_runtime_for_lease_bound_binding_decision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    runtime_row game_instances%ROWTYPE;
    approved_first_new_abort boolean := false;
    approved_original_new_final boolean := false;
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

    -- A committed actual final receipt is historical source, never a new dispatch permission.
    -- Retained cleanup/accounting may advance its revision, but no transition source can change.
    IF OLD.original_new_final_session_sha256 IS NOT NULL THEN
        IF (to_jsonb(NEW) - 'inventory_revision')
              IS DISTINCT FROM (to_jsonb(OLD) - 'inventory_revision')
           OR NEW.inventory_revision < OLD.inventory_revision THEN
            RAISE EXCEPTION 'Committed original NEW final source is immutable'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_original_new_final_terminal_source_immutable';
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
        ELSIF OLD.status = 'PROVISIONAL'
           AND NEW.status = 'COMMITTED'
           AND OLD.admission_lease_kind = 'NEW_BINDING'
           AND NEW.admission_lease_kind = 'NEW_BINDING'
           AND OLD.expected_prior_binding_ref IS NULL
           AND NEW.expected_prior_binding_ref IS NULL
           AND OLD.expected_prior_binding_generation IS NULL
           AND NEW.expected_prior_binding_generation IS NULL
           AND OLD.admission_orphan_cleanup_id IS NULL
           AND NEW.admission_orphan_cleanup_id IS NULL
           AND OLD.admission_resume_episode_id IS NULL
           AND NEW.admission_resume_episode_id IS NULL
           AND OLD.admission_expected_old_binding_generation IS NULL
           AND NEW.admission_expected_old_binding_generation IS NULL
           AND OLD.original_new_candidate_state = 'READBACK_CONFIRMED'
           AND NEW.original_new_candidate_state = 'READBACK_CONFIRMED'
           AND OLD.original_new_candidate_bytes IS NOT NULL
           AND OLD.original_new_candidate_digest ~ '^sha256:[0-9a-f]{64}$'
           AND OLD.original_new_candidate_readback_digest ~ '^sha256:[0-9a-f]{64}$'
           AND NEW.inventory_revision > OLD.inventory_revision
           AND NEW.original_new_final_session_sha256 IS NOT NULL
           AND NEW.original_new_final_session_sha256 ~ '^[0-9a-f]{64}$'
           AND NEW.original_new_admission_at_ms IS NOT NULL
           AND NEW.original_new_admission_at_ms > 0
           AND NEW.original_new_session_duration_ms IS NOT NULL
           AND NEW.original_new_session_duration_ms BETWEEN 1 AND 300000
           AND NEW.original_new_continuity_expires_at_ms IS NOT NULL
           AND NEW.original_new_admission_at_ms <=
               9223372036854775807 - NEW.original_new_session_duration_ms
           AND NEW.original_new_continuity_expires_at_ms =
               NEW.original_new_admission_at_ms + NEW.original_new_session_duration_ms
           AND NEW.original_new_admission_at_ms < NEW.admission_lease_expires_at THEN
            approved_original_new_final := true;
            -- Only status, inventory revision and the complete final receipt may change.
            -- This includes every original candidate/lease/target/control field, not a subset.
            IF (to_jsonb(NEW) - ARRAY['status', 'inventory_revision',
                    'original_new_final_session_sha256', 'original_new_admission_at_ms',
                    'original_new_continuity_expires_at_ms', 'original_new_session_duration_ms'])
                  IS DISTINCT FROM
               (to_jsonb(OLD) - ARRAY['status', 'inventory_revision',
                    'original_new_final_session_sha256', 'original_new_admission_at_ms',
                    'original_new_continuity_expires_at_ms', 'original_new_session_duration_ms']) THEN
                RAISE EXCEPTION 'Original NEW final commit cannot change its original transition source'
                    USING ERRCODE = '23514',
                          CONSTRAINT = 'gs_original_new_final_transition_source_immutable';
            END IF;
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

    IF NEW.status = 'COMMITTED' AND NOT approved_original_new_final THEN
        RAISE EXCEPTION 'Only the exact original NEW final commit may release this runtime fence'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_binding_runtime_fence_terminal_owner_required';
    END IF;

    -- Initial publication and approved original-NEW final commit both retain this full current
    -- runtime/launch/Human/catalog guard. No final-receipt branch returns before the locking read.
    -- Its row lock serializes the decision with stop/replacement/deletion through commit.
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
       AND runtime.game_instance_uuid = NEW.admission_target_canonical_game_instance_id
       AND runtime.version_id = NEW.admission_target_runtime_version_id
       AND runtime.game_template_id = launch.game_template_id
       AND runtime.launch_descriptor_id = launch.launch_descriptor_id
       AND runtime.release_bundle_id = launch.release_bundle_id
       AND runtime.generation_config_revision = launch.generation_config_revision
       AND runtime.version_state_epoch = launch.version_state_epoch
       AND (
           (
               -- Preserve the original run-owned source exactly for its owner route.
               runtime.run_owned_start_active_epoch = NEW.admission_target_active_world_epoch
               AND runtime.run_owned_start_request_id = launch.control_plane_request_id
               AND runtime.run_owned_start_published_release_bundle_ref =
                   launch.complete_launch_binding_evidence -> 'descriptor' ->> 'publishedReleaseBundleRef'
           )
           OR (
               -- Human STARTING -> RUNNING rows retain their own immutable positive receipt and
               -- original StartSession identity. The request/digest/release columns bind that
               -- source identity; run-owned lifecycle epochs remain absent.
               NEW.admission_target_owner_proof_outcome = 'COMMITTED'
               AND NEW.admission_target_positive_durable_abort IS FALSE
               AND runtime.run_owned_start_preparing_epoch IS NULL
               AND runtime.run_owned_start_active_epoch IS NULL
               AND EXISTS (
                   SELECT 1
                     FROM game_session_canonical_human_runtime_completion completion
                     JOIN game_session_canonical_initial_admission_attempt attempt
                       ON attempt.target_namespace = completion.target_namespace
                      AND attempt.initial_admission_request_id = completion.initial_admission_request_id
                      AND attempt.request_digest = completion.request_digest
                      AND attempt.canonical_tenant_id = completion.canonical_tenant_id
                      AND attempt.world_slug = completion.world_slug
                      AND attempt.realm_id = completion.realm_id
                      AND attempt.playable_state_namespace_id = completion.playable_state_namespace_id
                      AND attempt.playable_state_scope = completion.playable_state_scope
                      AND attempt.canonical_game_instance_id = completion.canonical_game_instance_id
                      AND attempt.canonical_version_id = completion.canonical_version_id
                      AND attempt.game_session_tenant_id = completion.game_session_tenant_id
                      AND attempt.game_instance_id = completion.game_instance_id
                      AND attempt.expected_catalog_revision = completion.expected_catalog_revision
                      AND attempt.origin_kind = completion.origin_kind
                      AND attempt.expected_prior_pointer_version IS NOT DISTINCT FROM
                          completion.expected_prior_pointer_version
                      AND attempt.active_lifecycle_epoch = completion.active_lifecycle_epoch
                      AND attempt.hold_id = completion.hold_id
                      AND attempt.hold_fence = completion.hold_fence
                      AND attempt.hold_binding_digest = completion.hold_binding_digest
                      AND attempt.status = 'COMMITTED'
                      AND attempt.pointer_id IS NOT NULL
                      AND attempt.pointer_version = NEW.admission_target_pointer_version
                      AND attempt.audit_event_id = NEW.admission_target_audit_event_id
                      AND attempt.commit_proof_digest = NEW.admission_target_owner_proof_digest
                      AND attempt.terminal_at = NEW.admission_target_terminal_at
                     JOIN game_session_start_session_operator_attempt original
                       ON original.target_namespace = completion.target_namespace
                      AND original.owner_attempt_id = completion.original_owner_attempt_id
                      AND original.owner_mutation_id = completion.original_owner_mutation_id
                      AND original.owner_fence = completion.original_owner_fence
                      AND original.canonical_tenant_id = completion.canonical_tenant_id
                      AND original.control_plane_request_id = launch.control_plane_request_id
                      AND original.post_authorization_execution_tuple =
                          completion.original_start_session_tuple
                      AND original.account_redemption_projection =
                          completion.account_redemption_projection
                      AND runtime.run_owned_start_request_id = original.control_plane_request_id
                      AND runtime.run_owned_start_request_digest = original.mutation_digest
                      AND runtime.run_owned_start_published_release_bundle_ref =
                          launch.complete_launch_binding_evidence -> 'descriptor'
                              ->> 'publishedReleaseBundleRef'
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
                      AND pointer.initial_admission_prior_pointer_version IS NOT DISTINCT FROM
                          attempt.expected_prior_pointer_version
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
                      AND event.initial_admission_prior_pointer_version IS NOT DISTINCT FROM
                          attempt.expected_prior_pointer_version
                      AND event.initial_admission_active_epoch = attempt.active_lifecycle_epoch
                      AND event.initial_admission_hold_id = attempt.hold_id
                      AND event.initial_admission_hold_fence = attempt.hold_fence
                      AND event.initial_admission_hold_binding_digest = attempt.hold_binding_digest
                      AND event.admission_state = 'OPEN'
                      AND event.prepared_version_upgrade_id IS NULL
                    WHERE completion.target_namespace = NEW.admission_target_namespace
                      AND completion.initial_admission_request_id =
                          NEW.admission_target_initial_admission_request_id
                      AND completion.request_digest =
                          NEW.admission_target_initial_admission_request_digest
                      AND completion.canonical_tenant_id = NEW.admission_target_tenant_id
                      AND completion.world_slug = NEW.admission_target_world_slug
                      AND completion.realm_id = NEW.admission_target_realm_id
                      AND completion.playable_state_namespace_id =
                          NEW.admission_target_playable_state_namespace_id
                      AND completion.playable_state_scope = NEW.admission_target_playable_state_scope
                      AND completion.canonical_game_instance_id =
                          NEW.admission_target_canonical_game_instance_id
                      AND completion.canonical_version_id = NEW.admission_target_canonical_version_id
                      AND completion.active_lifecycle_epoch = NEW.admission_target_active_world_epoch
                      AND completion.expected_catalog_revision = NEW.admission_target_catalog_revision
                      AND completion.origin_kind = NEW.admission_target_origin_kind
                      AND completion.expected_prior_pointer_version IS NOT DISTINCT FROM
                          NEW.admission_target_expected_prior_pointer_version
                      AND completion.hold_id = NEW.admission_target_hold_id
                      AND completion.hold_fence = NEW.admission_target_hold_fence
                      AND completion.hold_binding_digest = NEW.admission_target_hold_binding_digest
                      AND completion.game_session_tenant_id = runtime.tenant_id
                      AND completion.game_instance_id = runtime.id
                      AND completion.game_session_tenant_id = NEW.admission_target_game_session_tenant_id
                      AND completion.game_instance_id = NEW.admission_target_game_instance_id
                      AND completion.control_plane_request_id = launch.control_plane_request_id
                      AND completion.captured_starting_row_version = launch.captured_starting_row_version
                      AND completion.completed_running_row_version = runtime.row_version
                      AND completion.launch_descriptor_id = launch.launch_descriptor_id
                      AND completion.descriptor_request_digest =
                          launch.complete_launch_binding_evidence -> 'descriptor' ->> 'requestDigest'
                      AND completion.descriptor_result_digest =
                          launch.complete_launch_binding_evidence -> 'descriptor' ->> 'resultDigest'
                      AND completion.release_attestation_digest =
                          launch.complete_launch_binding_evidence -> 'releaseAttestation' ->> 'evidenceDigest'
                      AND completion.world_lifecycle_state = 'ACTIVE'
                      AND pointer.target_namespace = NEW.admission_target_namespace
                      AND pointer.canonical_tenant_id = NEW.admission_target_tenant_id
                      AND pointer.realm_id = NEW.admission_target_realm_id
                      AND pointer.world_slug = NEW.admission_target_world_slug
                      AND pointer.world_display_name = catalog.source_world_display_name
                      AND pointer.realm_slug = NEW.admission_target_realm_slug
                      AND pointer.realm_display_name = catalog.realm_display_name
                      AND pointer.playable_state_namespace_id =
                          NEW.admission_target_playable_state_namespace_id
                      AND pointer.state_scope = NEW.admission_target_playable_state_scope
                      AND pointer.canonical_game_instance_id =
                          NEW.admission_target_canonical_game_instance_id
                      AND pointer.canonical_version_id = NEW.admission_target_canonical_version_id
                      AND pointer.runtime_version_id = NEW.admission_target_runtime_version_id
                      AND pointer.tenant_id = NEW.admission_target_game_session_tenant_id
                      AND pointer.game_instance_id = NEW.admission_target_game_instance_id
                      AND pointer.pointer_version = NEW.admission_target_pointer_version
                      AND pointer.catalog_revision = NEW.admission_target_catalog_revision
                      AND pointer.initial_admission_request_id =
                          NEW.admission_target_initial_admission_request_id
                      AND pointer.initial_admission_request_digest =
                          NEW.admission_target_initial_admission_request_digest
                      AND pointer.initial_admission_origin_kind = NEW.admission_target_origin_kind
                      AND pointer.initial_admission_prior_pointer_version IS NOT DISTINCT FROM
                          NEW.admission_target_expected_prior_pointer_version
                      AND pointer.initial_admission_active_epoch = NEW.admission_target_active_world_epoch
                      AND pointer.initial_admission_hold_id = NEW.admission_target_hold_id
                      AND pointer.initial_admission_hold_fence = NEW.admission_target_hold_fence
                      AND pointer.initial_admission_hold_binding_digest =
                          NEW.admission_target_hold_binding_digest
                      AND pointer.admission_state = 'OPEN'
                      AND pointer.visible = catalog.visible
                      AND pointer.public_production_realm = catalog.public_production
                      AND pointer.character_creation_policy =
                          NEW.admission_target_character_creation_policy
                      AND pointer.requires_character_selection IS NULL
                      AND pointer.last_updated_by = 'game-session-canonical-initial-admission'
                      AND pointer.last_update_reason = 'World-held initial admission'
               )
           )
       )
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
-- The separate retained release guard must recognize that same exact terminal owner shape.
-- Its existing DELETE and definitive Account ABORT protections remain unchanged.
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
        IF OLD.status = 'PROVISIONAL'
           AND NEW.status = 'COMMITTED'
           AND OLD.admission_lease_kind = 'NEW_BINDING'
           AND NEW.admission_lease_kind = 'NEW_BINDING'
           AND OLD.expected_prior_binding_ref IS NULL
           AND NEW.expected_prior_binding_ref IS NULL
           AND OLD.expected_prior_binding_generation IS NULL
           AND NEW.expected_prior_binding_generation IS NULL
           AND OLD.admission_orphan_cleanup_id IS NULL
           AND NEW.admission_orphan_cleanup_id IS NULL
           AND OLD.admission_resume_episode_id IS NULL
           AND NEW.admission_resume_episode_id IS NULL
           AND OLD.admission_expected_old_binding_generation IS NULL
           AND NEW.admission_expected_old_binding_generation IS NULL
           AND OLD.original_new_candidate_state = 'READBACK_CONFIRMED'
           AND NEW.original_new_candidate_state = 'READBACK_CONFIRMED'
           AND OLD.original_new_candidate_bytes IS NOT NULL
           AND OLD.original_new_candidate_digest ~ '^sha256:[0-9a-f]{64}$'
           AND OLD.original_new_candidate_readback_digest ~ '^sha256:[0-9a-f]{64}$'
           AND NEW.inventory_revision > OLD.inventory_revision
           AND NEW.original_new_final_session_sha256 IS NOT NULL
           AND NEW.original_new_final_session_sha256 ~ '^[0-9a-f]{64}$'
           AND NEW.original_new_admission_at_ms IS NOT NULL
           AND NEW.original_new_admission_at_ms > 0
           AND NEW.original_new_session_duration_ms IS NOT NULL
           AND NEW.original_new_session_duration_ms BETWEEN 1 AND 300000
           AND NEW.original_new_continuity_expires_at_ms IS NOT NULL
           AND NEW.original_new_admission_at_ms <=
               9223372036854775807 - NEW.original_new_session_duration_ms
           AND NEW.original_new_continuity_expires_at_ms =
               NEW.original_new_admission_at_ms + NEW.original_new_session_duration_ms
           AND NEW.original_new_admission_at_ms < NEW.admission_lease_expires_at
           AND (to_jsonb(NEW) - ARRAY['status', 'inventory_revision',
                    'original_new_final_session_sha256', 'original_new_admission_at_ms',
                    'original_new_continuity_expires_at_ms', 'original_new_session_duration_ms'])
                IS NOT DISTINCT FROM
               (to_jsonb(OLD) - ARRAY['status', 'inventory_revision',
                    'original_new_final_session_sha256', 'original_new_admission_at_ms',
                    'original_new_continuity_expires_at_ms', 'original_new_session_duration_ms']) THEN
            -- The primary runtime trigger has also retained its complete current locking join.
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'An unresolved lease-bound binding decision requires exact terminal owner reconciliation'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_binding_runtime_fence_terminal_owner_required';
    END IF;
    RETURN NEW;
END;
$$;
-- Keep the existing definitive-abort release guard and add only admitting successor checks.
CREATE OR REPLACE FUNCTION require_first_new_issuer_release_obligation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status = 'ACKNOWLEDGED' THEN
            RAISE EXCEPTION 'Final issuer acknowledgement requires an existing REQUIRED obligation'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_issuer_obligation_source';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'ACKNOWLEDGED' THEN
        IF TG_OP = 'DELETE'
           OR (to_jsonb(NEW) - 'inventory_revision')
                IS DISTINCT FROM (to_jsonb(OLD) - 'inventory_revision')
           OR NEW.inventory_revision < OLD.inventory_revision THEN
            RAISE EXCEPTION 'Final original NEW issuer acknowledgement is retained and immutable'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_issuer_obligation_retained';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.status = 'ACKNOWLEDGED' THEN
        IF OLD.status NOT IN ('REQUIRED', 'ACKNOWLEDGED')
           OR (OLD.status = 'REQUIRED' AND NEW.inventory_revision <= OLD.inventory_revision)
           OR (to_jsonb(NEW) - 'status' - 'inventory_revision')
                IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'inventory_revision')
           OR NEW.local_release_operation_id IS NOT NULL
           OR NOT EXISTS (
               SELECT 1
                 FROM game_session_canonical_binding_transition transition
                 JOIN game_session_canonical_gameplay_binding_inventory candidate
                   ON candidate.binding_ref = transition.candidate_binding_ref
                  AND candidate.transition_id = transition.transition_id
                  AND candidate.binding_generation = transition.candidate_binding_generation
                  AND candidate.account_index_fence = transition.candidate_account_index_fence
                  AND candidate.issuer_reservation_id = transition.issuer_reservation_id
                 JOIN game_session_canonical_issuer_partition_reservation reservation
                   ON reservation.reservation_id = transition.issuer_reservation_id
                  AND reservation.transition_id = transition.transition_id
                  AND reservation.binding_ref = candidate.binding_ref
                  AND reservation.binding_generation = candidate.binding_generation
                 JOIN game_session_canonical_issuer_binding_local_acknowledgement receipt
                   ON receipt.transition_id = transition.transition_id
                  AND receipt.issuer_id = candidate.issuer_id
                  AND receipt.binding_ref = candidate.binding_ref
                  AND receipt.binding_generation = candidate.binding_generation
                  AND receipt.reservation_id = reservation.reservation_id
                  AND receipt.reservation_fence = reservation.reservation_fence
                  AND receipt.original_issuer_coverage_operation_id = reservation.issuer_coverage_operation_id
                  AND receipt.original_issuer_coverage_operation_fence = reservation.issuer_coverage_operation_fence
                  AND receipt.original_coverage_fence = reservation.coverage_fence
                  AND receipt.original_inventory_snapshot_revision = reservation.inventory_snapshot_revision
                  AND receipt.original_inventory_revision <= OLD.inventory_revision
                  AND receipt.physical_partition_id = reservation.partition_id
                 JOIN game_session_canonical_issuer_binding_local_operation operation
                   ON operation.operation_id = receipt.operation_id
                  AND operation.transition_id = transition.transition_id
                  AND operation.candidate_account_index_fence = transition.candidate_account_index_fence
                  AND operation.expected_prior_binding_ref IS NULL
                  AND operation.expected_prior_binding_generation IS NULL
                WHERE transition.transition_id = NEW.transition_id
                  AND transition.status = 'COMMITTED'
                  AND transition.admission_lease_kind = 'NEW_BINDING'
                  AND transition.expected_prior_binding_ref IS NULL
                  AND transition.expected_prior_binding_generation IS NULL
                  AND transition.admission_orphan_cleanup_id IS NULL
                  AND transition.original_new_final_session_sha256 IS NOT NULL
                  AND transition.original_new_admission_at_ms IS NOT NULL
                  AND transition.original_new_continuity_expires_at_ms IS NOT NULL
                  AND transition.original_new_session_duration_ms IS NOT NULL
                  AND transition.original_new_candidate_state = 'READBACK_CONFIRMED'
                  AND transition.inventory_revision = NEW.inventory_revision
                  AND candidate.binding_ref = NEW.binding_ref
                  AND candidate.binding_generation = NEW.binding_generation
                  AND candidate.lifecycle = 'ACTIVE'
                  AND candidate.account_index_state = 'ACKNOWLEDGED'
                  AND candidate.issuer_index_state = 'ACKNOWLEDGED'
                  AND candidate.inventory_revision = NEW.inventory_revision
                  AND reservation.reservation_id = NEW.reservation_id
                  -- Final SQL settles this obligation before changing RESERVED to BOUND.
                  -- Recovery observes the same immutable reservation at its committed revision.
                  AND ((OLD.status = 'REQUIRED' AND reservation.lifecycle = 'RESERVED'
                          AND reservation.inventory_revision = OLD.inventory_revision)
                      OR (OLD.status = 'ACKNOWLEDGED' AND reservation.lifecycle = 'BOUND'
                          AND reservation.inventory_revision = NEW.inventory_revision))
           ) THEN
            RAISE EXCEPTION 'Final issuer obligation requires matching committed original NEW and native acknowledgement'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_issuer_obligation_source';
        END IF;
    END IF;
    IF OLD.status = 'RELEASED'
       AND (TG_OP = 'DELETE' OR to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD)) THEN
        RAISE EXCEPTION 'Completed first-NEW issuer release obligation is retained'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_first_new_issuer_release_obligation_retained';
    END IF;
    IF OLD.status = 'REQUIRED' AND NEW.status = 'RELEASED' THEN
        IF (to_jsonb(NEW) - 'status' - 'local_release_operation_id' - 'inventory_revision')
              IS DISTINCT FROM
           (to_jsonb(OLD) - 'status' - 'local_release_operation_id' - 'inventory_revision')
           OR NEW.local_release_operation_id IS NULL
           OR NOT EXISTS (
               SELECT 1
                 FROM game_session_canonical_binding_transition transition
                 JOIN game_session_canonical_gameplay_binding_inventory candidate
                   ON candidate.binding_ref = transition.candidate_binding_ref
                 JOIN game_session_canonical_issuer_partition_reservation reservation
                   ON reservation.reservation_id = transition.issuer_reservation_id
                 JOIN game_session_canonical_issuer_binding_local_release_operation release
                   ON release.operation_id = NEW.local_release_operation_id
                WHERE transition.transition_id = NEW.transition_id
                  AND transition.status = 'ABORTED'
                  AND transition.admission_orphan_cleanup_id = release.orphan_cleanup_id
                  AND transition.inventory_revision = OLD.inventory_revision
                  AND candidate.lifecycle = 'TERMINAL_UNRESOLVED'
                  AND candidate.inventory_revision = OLD.inventory_revision
                  AND reservation.lifecycle = 'RELEASE_PENDING'
                  AND reservation.inventory_revision = OLD.inventory_revision
                  AND reservation.reservation_id = NEW.reservation_id
                  AND candidate.binding_ref = NEW.binding_ref
                  AND candidate.binding_generation = NEW.binding_generation
                  AND release.status = 'RELEASED'
                  AND release.transition_id = NEW.transition_id
                  AND release.reservation_id = reservation.reservation_id
                  AND release.binding_ref = NEW.binding_ref
                  AND release.binding_generation = NEW.binding_generation
                  AND release.source_inventory_revision = OLD.inventory_revision
                  AND release.completion_inventory_revision = NEW.inventory_revision
                  AND NEW.inventory_revision > OLD.inventory_revision
                  AND release.physical_readback_field_set_bytes IS NOT NULL
                  AND release.physical_readback_field_set_digest ~ '^sha256:[0-9a-f]{64}$'
           ) THEN
            RAISE EXCEPTION 'Issuer obligation release requires exact completed local physical proof'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_first_new_issuer_release_obligation_source';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


CREATE TRIGGER game_session_original_new_issuer_obligation_insert
    BEFORE INSERT ON game_session_canonical_binding_issuer_index_obligation
    FOR EACH ROW EXECUTE FUNCTION require_first_new_issuer_release_obligation();
-- [jooq ignore stop]
