-- Permit the immutable canonical Human completion as a second exact source for the existing
-- runtime fence. The run-owned route and every transition immutability check remain unchanged.
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
-- [jooq ignore stop]
