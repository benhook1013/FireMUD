-- Preserve an exact completed per-binding region bridge as abort source evidence.
-- The ACK remains unresolved cleanup input; it is not a cleanup or admission claim.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION require_first_new_abort_source_complete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    affected_transition_id uuid;
    initial_abort_transition boolean := false;
BEGIN
    IF TG_OP = 'DELETE' THEN affected_transition_id := OLD.transition_id;
    ELSE affected_transition_id := NEW.transition_id;
    END IF;
    IF TG_TABLE_NAME = 'game_session_canonical_binding_transition'
       AND TG_OP = 'UPDATE' THEN
        IF OLD.status = 'PROVISIONAL' AND NEW.status = 'ABORTED' THEN
            initial_abort_transition := true;
        END IF;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM game_session_canonical_binding_transition
        WHERE transition_id = affected_transition_id AND status = 'ABORTED'
          AND admission_orphan_cleanup_id IS NOT NULL) THEN
        RETURN NULL;
    END IF;
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
         WHERE transition.transition_id = affected_transition_id
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
           AND reservation.lifecycle IN ('RELEASE_PENDING', 'RELEASED')
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
           AND (
               (account_obligation.status = 'REQUIRED' AND candidate.account_index_state = 'REPAIR_REQUIRED')
               OR (account_obligation.status = 'ACKNOWLEDGED'
                   AND candidate.account_index_state = 'ACKNOWLEDGED'
                   AND account_obligation.projection_state = 'PRESENT_AWAITING_COVERAGE'
                   AND EXISTS (
                       SELECT 1 FROM game_session_canonical_account_member_repair_operation repair
                      WHERE repair.transition_id = transition.transition_id
                        AND repair.obligation_ordinal = 0
                        AND repair.status = 'ACKNOWLEDGED'
                        AND repair.binding_ref = candidate.binding_ref
                        AND repair.account_id = candidate.account_id
                        AND repair.tenant_id = candidate.tenant_id
                        AND repair.binding_generation = candidate.binding_generation
                        AND repair.account_index_fence = candidate.account_index_fence
                        AND repair.original_action = 'ADD_OR_RETAIN'
                        AND repair.original_expected_prior_generation IS NULL
                        AND repair.original_execution_phase = 'BEFORE_FINAL_CAS'
                        AND repair.original_status = 'REQUIRED'
                        AND repair.desired_action = 'ADD_OR_RETAIN'
                        AND repair.account_control_state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
                        AND (
                            NOT initial_abort_transition
                            OR EXISTS (
                                SELECT 1
                                  FROM game_session_canonical_account_coverage_control control
                                 WHERE control.account_id = repair.account_id
                                   AND control.state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
                                   AND control.last_operation_fence = repair.account_control_epoch))
                        AND repair.coverage_generation > repair.inventory_snapshot_revision
                        AND repair.completed_inventory_revision > repair.inventory_snapshot_revision
                        AND repair.completed_inventory_revision < transition.inventory_revision
                        AND repair.readback_state = 'PRESENT'
                        AND repair.readback_member = repair.member
                        AND repair.member = account_obligation.projection_member
                        AND repair.member = 'accountIndexMember/v1:'
                            || rtrim(translate(replace(encode(candidate.binding_ref, 'base64'), E'\n', ''), '+/', '-_'), '=')
                            || ':' || candidate.binding_generation::text || ':' || candidate.account_index_fence::text
                        AND ((repair.original_projection_state = 'REQUIRED'
                              AND account_obligation.projection_inventory_revision = repair.completed_inventory_revision)
                            OR (repair.original_projection_state = 'PRESENT_AWAITING_COVERAGE'
                                AND repair.original_projection_member = repair.member
                                AND account_obligation.projection_inventory_revision = repair.original_projection_inventory_revision))
                   ))
           )
           AND (SELECT count(*)
                  FROM game_session_canonical_binding_account_index_obligation extra_account
                 WHERE extra_account.transition_id = transition.transition_id) = 1
           AND issuer_obligation.reservation_id = reservation.reservation_id
           AND issuer_obligation.binding_ref = candidate.binding_ref
           AND issuer_obligation.binding_generation = candidate.binding_generation
           AND (
               (issuer_obligation.status = 'REQUIRED'
                   AND issuer_obligation.local_release_operation_id IS NULL
                   AND reservation.lifecycle = 'RELEASE_PENDING'
                   AND NOT EXISTS (
                       SELECT 1 FROM game_session_canonical_issuer_binding_local_release_operation pending_release
                        WHERE pending_release.transition_id = transition.transition_id
                          AND pending_release.status <> 'PENDING_PHYSICAL_PROOF'))
               OR (issuer_obligation.status = 'RELEASED'
                   AND issuer_obligation.local_release_operation_id IS NOT NULL
                   AND reservation.lifecycle = 'RELEASED'
                   AND EXISTS (
                       SELECT 1 FROM game_session_canonical_issuer_binding_local_release_operation released
                        WHERE released.operation_id = issuer_obligation.local_release_operation_id
                          AND released.transition_id = transition.transition_id
                          AND released.orphan_cleanup_id = transition.admission_orphan_cleanup_id
                          AND released.reservation_id = reservation.reservation_id
                          AND released.binding_ref = candidate.binding_ref
                          AND released.binding_generation = candidate.binding_generation
                          AND released.status = 'RELEASED'
                          AND released.source_inventory_revision < transition.inventory_revision
                          AND released.completion_inventory_revision = transition.inventory_revision
                          AND released.physical_readback_field_set_bytes IS NOT NULL
                          AND released.physical_readback_field_set_digest ~ '^sha256:[0-9a-f]{64}$'
                          AND released.released_member_value_bytes IS NOT NULL
                          AND released.released_member_value_digest ~ '^sha256:[0-9a-f]{64}$'))
           )
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
           AND (
               region_obligation.status = 'REQUIRED'
               OR (region_obligation.status = 'ACKNOWLEDGED'
                   AND region_obligation.bridge_operation_id IS NOT NULL
                   AND region_obligation.bridge_preimage_state = 'ABSENT'
                   AND region_obligation.runtime_entity_id > 0
                   AND region_obligation.entity_projection_generation > 0
                   AND region_obligation.entity_projection_fence > 0
                   AND region_obligation.entity_workload_namespace IS NOT NULL
                   AND region_obligation.entity_workload_namespace <> ''
                   AND region_obligation.entity_workload_namespace =
                       btrim(region_obligation.entity_workload_namespace)
                   AND region_obligation.entity_workload_namespace !~ '[[:cntrl:]]'
                   AND region_obligation.assignment_uuid IS NOT NULL
                   AND region_obligation.assignment_uuid <>
                       '00000000-0000-0000-0000-000000000000'::uuid
                   AND region_obligation.assignment_intent_digest ~ '^[0-9a-f]{64}$'
                   AND region_obligation.entity_reference_digest ~ '^[0-9a-f]{64}$'
                   AND region_obligation.world_source_digest ~ '^[0-9a-f]{64}$'
                   AND region_obligation.canonical_region_id IS NOT NULL
                   AND region_obligation.canonical_region_id <>
                       '00000000-0000-0000-0000-000000000000'::uuid
                   AND region_obligation.world_lifecycle_epoch > 0
                   AND region_obligation.world_lifecycle_row_version >= 0
                   AND region_obligation.world_capture_id IS NOT NULL
                   AND region_obligation.world_capture_id <>
                       '00000000-0000-0000-0000-000000000000'::uuid
                   AND region_obligation.world_graph_sha256 ~ '^[0-9a-f]{64}$'
                   AND region_obligation.world_preparation_input_digest ~ '^sha256:[0-9a-f]{64}$'
                   AND region_obligation.executor_fence > 0
                   AND region_obligation.lease_token_correlation ~ '^[0-9a-f]{64}$'
                   AND region_obligation.redis_readback_digest ~ '^[0-9a-f]{64}$'
                   AND region_obligation.acknowledged_at IS NOT NULL)
           )
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
-- [jooq ignore stop]
