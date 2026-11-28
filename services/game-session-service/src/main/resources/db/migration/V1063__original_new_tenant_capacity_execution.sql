-- Execution evidence only. Account remains sole reservation, fence and usage authority.
CREATE TABLE game_session_original_new_capacity_execution (
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    admission_id UUID NOT NULL,
    admission_attempt_id UUID NOT NULL UNIQUE,
    transition_id UUID NOT NULL REFERENCES game_session_canonical_binding_transition(transition_id),
    capacity_rearm_fence BIGINT NOT NULL CHECK (capacity_rearm_fence > 0),
    reservation_id UUID NOT NULL,
    reservation_fence BIGINT NOT NULL CHECK (reservation_fence > 0),
    mutation_digest CHAR(64) NOT NULL CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
    execution_digest VARCHAR(71) NOT NULL UNIQUE CHECK (execution_digest = 'sha256:' || encode(sha256(canonical_execution), 'hex')),
    canonical_execution BYTEA NOT NULL CHECK (octet_length(canonical_execution) BETWEEN 1 AND 1048576),
    selected_owner_id VARCHAR(120) NOT NULL CHECK (selected_owner_id ~ '^[A-Za-z0-9._:-]{1,120}$'),
    selected_owner_fence BIGINT NOT NULL CHECK (selected_owner_fence > 0),
    tenant_gameplay_tag VARCHAR(44) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('INSTALL_DISPATCHED', 'AUTHORIZED', 'COMMITTED', 'CLOSED')),
    terminal_readback BYTEA CHECK (octet_length(terminal_readback) BETWEEN 1 AND 1048576),
    PRIMARY KEY (tenant_id, request_id, admission_id, admission_attempt_id),
    CHECK ((status IN ('COMMITTED', 'CLOSED')) = (terminal_readback IS NOT NULL))
);
ALTER TABLE game_session_canonical_binding_transition
    ADD COLUMN original_new_capacity_execution_digest VARCHAR(71)
        REFERENCES game_session_original_new_capacity_execution(execution_digest);

-- [jooq ignore start]
CREATE FUNCTION guard_gs_original_new_capacity_execution() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Original NEW capacity execution is retained' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.tenant_id, NEW.request_id, NEW.admission_id, NEW.admission_attempt_id,
        NEW.transition_id, NEW.capacity_rearm_fence, NEW.reservation_id, NEW.reservation_fence,
        NEW.mutation_digest, NEW.execution_digest, NEW.canonical_execution,
        NEW.selected_owner_id, NEW.selected_owner_fence, NEW.tenant_gameplay_tag)
        IS DISTINCT FROM ROW(OLD.tenant_id, OLD.request_id, OLD.admission_id, OLD.admission_attempt_id,
        OLD.transition_id, OLD.capacity_rearm_fence, OLD.reservation_id, OLD.reservation_fence,
        OLD.mutation_digest, OLD.execution_digest, OLD.canonical_execution,
        OLD.selected_owner_id, OLD.selected_owner_fence, OLD.tenant_gameplay_tag)
        OR (OLD.status IN ('COMMITTED', 'CLOSED') AND NEW IS DISTINCT FROM OLD)
        OR (OLD.status = 'AUTHORIZED' AND NEW.status NOT IN ('AUTHORIZED', 'COMMITTED', 'CLOSED'))
        OR (OLD.status = 'INSTALL_DISPATCHED' AND NEW.status NOT IN ('INSTALL_DISPATCHED', 'AUTHORIZED', 'CLOSED')) THEN
        RAISE EXCEPTION 'Original NEW capacity execution identity/outcome is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION reject_gs_original_new_capacity_truncate() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original NEW capacity execution is retained' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER gs_original_new_capacity_execution_no_truncate BEFORE TRUNCATE
    ON game_session_original_new_capacity_execution FOR EACH STATEMENT
    EXECUTE FUNCTION reject_gs_original_new_capacity_truncate();
CREATE TRIGGER gs_original_new_capacity_execution_guard BEFORE UPDATE OR DELETE
    ON game_session_original_new_capacity_execution FOR EACH ROW
    EXECUTE FUNCTION guard_gs_original_new_capacity_execution();

CREATE FUNCTION guard_gs_original_new_capacity_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.original_new_capacity_execution_digest IS NOT NULL
        AND NEW.original_new_capacity_execution_digest IS DISTINCT FROM OLD.original_new_capacity_execution_digest THEN
        RAISE EXCEPTION 'Original NEW capacity receipt is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.original_new_final_session_sha256 IS NULL AND NEW.original_new_capacity_execution_digest IS NOT NULL THEN
        RAISE EXCEPTION 'Original NEW capacity receipt binds only a final admission' USING ERRCODE = '23514';
    END IF;
    IF NEW.original_new_final_session_sha256 IS NOT NULL AND OLD.original_new_final_session_sha256 IS NULL THEN
        IF NEW.original_new_capacity_execution_digest IS NULL OR NOT EXISTS (
            SELECT 1 FROM game_session_original_new_capacity_execution e
            WHERE e.execution_digest = NEW.original_new_capacity_execution_digest
                AND e.transition_id = NEW.transition_id AND e.status = 'AUTHORIZED') THEN
            RAISE EXCEPTION 'Original NEW final receipt requires its exact capacity execution' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_original_new_capacity_receipt_guard BEFORE UPDATE
    ON game_session_canonical_binding_transition FOR EACH ROW
    EXECUTE FUNCTION guard_gs_original_new_capacity_receipt();

-- Preserve the complete original runtime/source and terminal-release guards. Only their
-- already-approved final receipt branch gains the exact capacity-execution receipt field;
-- guard_gs_original_new_capacity_receipt independently requires its authorized exact attempt.
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
                    'original_new_continuity_expires_at_ms', 'original_new_session_duration_ms',
                    'original_new_capacity_execution_digest'])
                  IS DISTINCT FROM
               (to_jsonb(OLD) - ARRAY['status', 'inventory_revision',
                    'original_new_final_session_sha256', 'original_new_admission_at_ms',
                    'original_new_continuity_expires_at_ms', 'original_new_session_duration_ms',
                    'original_new_capacity_execution_digest']) THEN
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
                    'original_new_continuity_expires_at_ms', 'original_new_session_duration_ms',
                    'original_new_capacity_execution_digest'])
                IS NOT DISTINCT FROM
               (to_jsonb(OLD) - ARRAY['status', 'inventory_revision',
                    'original_new_final_session_sha256', 'original_new_admission_at_ms',
                    'original_new_continuity_expires_at_ms', 'original_new_session_duration_ms',
                    'original_new_capacity_execution_digest']) THEN
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

-- The new evidence is retained original execution control, never tenant usage authority.
INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_original_new_capacity_execution','game_session_original_new_capacity_execution',
    'CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Exact original NEW capacity execution and permanent closure are retained Account recovery evidence. Never clone, release from expiry, or infer no execution from missing evidence.');
INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,component_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_canonical_binding_transition.original_new_capacity_execution_digest',
    'game_session_canonical_binding_transition','original_new_capacity_execution_digest',
    'CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Final original NEW receipt binds the exact retained capacity execution; no independent reservation or usage authority.');

CREATE OR REPLACE FUNCTION gs_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
    SELECT (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1057__game_session_owner_runtime_family_catalog.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1057'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1058__game_session_catalog_view_options.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1058'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1059__private_lifecycle_preparation_after_catalog_allocation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1059'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1060__private_catalog_lifecycle_revisions.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1060'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1061__private_fresh_runtime_identity_allocation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1061'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1062__private_preparation_source_protection.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1062'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1063__original_new_tenant_capacity_execution.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1063'
    );
$$;

CREATE OR REPLACE FUNCTION gs_runtime_family_catalog_bytes(p_epoch BIGINT) RETURNS BYTEA
LANGUAGE plpgsql STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_families JSONB; v_facts JSONB; v_gaps JSONB; v_bytes BYTEA;
BEGIN
    IF p_epoch IS NULL OR p_epoch<=0 THEN
        RAISE EXCEPTION 'Positive Game Session catalog epoch required' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='${serviceSchema}' AND c.relkind IN ('r','p','f')
        EXCEPT SELECT relation_name FROM game_session_runtime_family_classification WHERE component_name=''
    ) OR EXISTS (
        SELECT 1 FROM game_session_runtime_family_classification f
        LEFT JOIN pg_namespace n ON n.nspname='${serviceSchema}'
        LEFT JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=f.relation_name AND c.relkind IN ('r','p','f')
        WHERE c.oid IS NULL OR (f.component_name<>'' AND NOT EXISTS (
            SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname=f.component_name
            AND a.attnum>0 AND NOT a.attisdropped))
    ) THEN
        RAISE EXCEPTION 'Unclassified or changed Game Session schema family' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_agg(jsonb_build_object('familyId',family_id,'relation',relation_name,'component',component_name,
        'role',family_role,'stateClass',state_class,'freshDisposition',fresh_disposition,
        'retentionDisposition',retention_disposition,'ownerConsequence',owner_consequence)
        ORDER BY family_id COLLATE "C") INTO v_families FROM game_session_runtime_family_classification;
    SELECT jsonb_agg(jsonb_build_object('id',gap_id,'disposition',disposition,'ownerConsequence',owner_consequence)
        ORDER BY gap_id COLLATE "C") INTO v_gaps FROM game_session_runtime_catalog_non_sql_gap;

    -- Definitions only, not rows, OIDs, sequence positions, statistics or installation timestamps.
    WITH relations AS (
        SELECT c.* FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='${serviceSchema}'
    ), definitions(kind,identity,definition) AS (
        SELECT 'TABLE',c.relname::TEXT,jsonb_build_array(c.relkind,c.relpersistence,
            c.relrowsecurity,c.relforcerowsecurity,c.reloptions,pg_get_partkeydef(c.oid))::TEXT
        FROM relations c WHERE c.relkind IN ('r','p','f')
        UNION ALL
        SELECT 'COLUMN',c.relname||'.'||a.attname,jsonb_build_array(a.attnum,
            format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,
            pg_get_expr(d.adbin,d.adrelid),co.collname)::TEXT
        FROM relations c JOIN pg_attribute a ON a.attrelid=c.oid
        LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        LEFT JOIN pg_collation co ON co.oid=a.attcollation
        WHERE c.relkind IN ('r','p','f','v','m') AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL
        SELECT 'CONSTRAINT',c.relname||'.'||k.conname,
            jsonb_build_array(pg_get_constraintdef(k.oid,true),k.convalidated,k.condeferrable,k.condeferred)::TEXT
        FROM relations c JOIN pg_constraint k ON k.conrelid=c.oid
        UNION ALL
        SELECT 'INDEX',c.relname::TEXT,jsonb_build_array(pg_get_indexdef(c.oid),i.indisvalid,i.indisready)::TEXT
        FROM relations c JOIN pg_index i ON i.indexrelid=c.oid
        UNION ALL
        SELECT 'TRIGGER',c.relname||'.'||t.tgname,jsonb_build_array(pg_get_triggerdef(t.oid,true),t.tgenabled)::TEXT
        FROM relations c JOIN pg_trigger t ON t.tgrelid=c.oid WHERE NOT t.tgisinternal
        UNION ALL
        SELECT 'POLICY',c.relname||'.'||p.polname,jsonb_build_array(p.polcmd,p.polpermissive,
            (SELECT jsonb_agg(r.rolname ORDER BY r.rolname COLLATE "C") FROM pg_roles r WHERE r.oid=ANY(p.polroles)),
            pg_get_expr(p.polqual,p.polrelid),pg_get_expr(p.polwithcheck,p.polrelid))::TEXT
        FROM relations c JOIN pg_policy p ON p.polrelid=c.oid
        UNION ALL
        SELECT 'RULE',c.relname||'.'||r.rulename,pg_get_ruledef(r.oid,true)
        FROM relations c JOIN pg_rewrite r ON r.ev_class=c.oid
        UNION ALL
        SELECT 'ROUTINE',p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',pg_get_functiondef(p.oid)
        FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='${serviceSchema}' AND p.prokind IN ('f','p')
        UNION ALL
        SELECT 'SEQUENCE',c.relname::TEXT,(to_jsonb(s)-'seqrelid')::TEXT
        FROM relations c JOIN pg_sequence s ON s.seqrelid=c.oid
        UNION ALL
        SELECT 'VIEW',c.relname::TEXT,jsonb_build_array(pg_get_viewdef(c.oid,true),c.reloptions)::TEXT
        FROM relations c WHERE c.relkind IN ('v','m')
        UNION ALL
        SELECT 'TYPE',t.typname::TEXT,jsonb_build_array(t.typtype,t.typnotnull,t.typdefault,
            (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))::TEXT
        FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='${serviceSchema}'
        UNION ALL
        SELECT 'MIGRATION',installed_rank::TEXT||':'||coalesce(version,'repeatable')||':'||script,
            jsonb_build_array(version,type,script,checksum,success)::TEXT
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1057' AND version IS DISTINCT FROM '1058'
            AND version IS DISTINCT FROM '1059' AND version IS DISTINCT FROM '1060'
            AND version IS DISTINCT FROM '1061' AND version IS DISTINCT FROM '1062'
            AND version IS DISTINCT FROM '1063'
        UNION ALL
        -- The seven installer rows are independently required as exact successful SQL scripts.
        -- Stable markers bind their installation without inventing future Flyway checksums.
        SELECT 'MIGRATION','catalog-installation:1057','V1057__game_session_owner_runtime_family_catalog.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1058','V1058__game_session_catalog_view_options.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1059','V1059__private_lifecycle_preparation_after_catalog_allocation.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1060','V1060__private_catalog_lifecycle_revisions.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1061','V1061__private_fresh_runtime_identity_allocation.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1062','V1062__private_preparation_source_protection.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1063','V1063__original_new_tenant_capacity_execution.sql'
    )
    SELECT jsonb_agg(jsonb_build_object('kind',kind,'identity',identity,
        'definitionDigest','sha256:'||encode(sha256(convert_to(definition,'UTF8')),'hex'))
        ORDER BY (kind||'/'||identity) COLLATE "C") INTO v_facts FROM definitions;
    IF v_families IS NULL OR v_facts IS NULL OR v_gaps IS NULL
        OR jsonb_array_length(v_families)>512 OR jsonb_array_length(v_facts)>16384
        OR jsonb_array_length(v_gaps)>32 THEN
        RAISE EXCEPTION 'Complete Game Session catalog exceeds source bounds' USING ERRCODE='23514';
    END IF;
    v_bytes := convert_to(jsonb_build_object('schema','game-session-runtime-family-catalog/v1',
        'owner','game-session-service','catalogEpoch',p_epoch::TEXT,
        'families',v_families,'schemaFacts',v_facts,'nonSqlGaps',v_gaps)::TEXT,'UTF8');
    IF octet_length(v_bytes)>2097152 THEN
        RAISE EXCEPTION 'Complete Game Session catalog exceeds byte bound' USING ERRCODE='23514';
    END IF;
    RETURN v_bytes;
END;
$$;

-- Seal only after every routine replacement; old epochs and snapshots stay byte-for-byte
-- retained and old requests conflict with this explicitly installed next owner revision.
INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (
    SELECT epoch,gs_runtime_family_catalog_bytes(epoch) AS bytes
    FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS epoch
        FROM game_session_runtime_family_catalog_epoch) next_epoch
) seal;
-- [jooq ignore stop]
