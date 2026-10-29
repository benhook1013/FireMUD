-- Distinct negative followup terminals are never admitting acknowledgements.
ALTER TABLE game_session_canonical_binding_account_index_obligation DROP CONSTRAINT chk_gs_canonical_binding_account_index_obligation;
ALTER TABLE game_session_canonical_binding_account_index_obligation
    ADD CONSTRAINT chk_gs_canonical_binding_account_index_obligation CHECK (
        obligation_ordinal >= 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND action IN ('ADD_OR_RETAIN', 'REMOVE')
        AND binding_generation > 0
        AND (expected_prior_generation IS NULL OR expected_prior_generation >= 0)
        AND account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND execution_phase IN ('BEFORE_FINAL_CAS', 'AFTER_FINAL_CAS')
        AND status IN ('REQUIRED', 'ACKNOWLEDGED', 'NEVER_DISPATCHED')
        AND inventory_revision > 0
        AND (status IN ('REQUIRED', 'NEVER_DISPATCHED')
            OR (status = 'ACKNOWLEDGED'
                AND obligation_ordinal = 0
                AND action = 'ADD_OR_RETAIN'
                AND execution_phase = 'BEFORE_FINAL_CAS'
                AND projection_state = 'PRESENT_AWAITING_COVERAGE'
                AND projection_member IS NOT NULL
                AND projection_inventory_revision IS NOT NULL
                AND projection_inventory_revision > 0)));
ALTER TABLE game_session_canonical_binding_region_bridge_obligation DROP CONSTRAINT chk_gs_canonical_binding_region_bridge_obligation;
ALTER TABLE game_session_canonical_binding_region_bridge_obligation
    ADD CONSTRAINT chk_gs_canonical_binding_region_bridge_obligation CHECK (
        binding_generation > 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        -- [jooq ignore start]
        AND session_id <> ''
        -- [jooq ignore stop]
        AND session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND session_id <> '00000000-0000-0000-0000-000000000000'
        AND session_id !~ '^[[:space:]]*$'
        AND session_id !~ '[[:cntrl:]]'
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND region_epoch > 0
        AND status IN ('REQUIRED', 'ACKNOWLEDGED', 'NEVER_DISPATCHED')
        AND inventory_revision > 0
        AND (
            (bridge_operation_id IS NULL
                AND bridge_preimage_state IS NULL
                AND runtime_entity_id IS NULL
                AND entity_projection_generation IS NULL
                AND entity_projection_fence IS NULL
                AND entity_workload_namespace IS NULL
                AND assignment_uuid IS NULL
                AND assignment_intent_digest IS NULL
                AND entity_reference_digest IS NULL
                AND world_source_digest IS NULL
                AND canonical_region_id IS NULL
                AND world_lifecycle_epoch IS NULL
                AND world_lifecycle_row_version IS NULL
                AND world_capture_id IS NULL
                AND world_graph_sha256 IS NULL
                AND world_preparation_input_digest IS NULL)
            OR
            (bridge_operation_id IS NOT NULL
                AND bridge_preimage_state IS NOT NULL
                AND bridge_preimage_state = 'ABSENT'
                AND runtime_entity_id IS NOT NULL
                AND runtime_entity_id > 0
                AND entity_projection_generation IS NOT NULL
                AND entity_projection_generation > 0
                AND entity_projection_fence IS NOT NULL
                AND entity_projection_fence > 0
                AND entity_workload_namespace IS NOT NULL
                -- [jooq ignore start]
                AND entity_workload_namespace <> ''
                -- [jooq ignore stop]
                AND assignment_uuid IS NOT NULL
                AND assignment_uuid <> '00000000-0000-0000-0000-000000000000'::uuid
                AND assignment_intent_digest IS NOT NULL
                AND assignment_intent_digest ~ '^[0-9a-f]{64}$'
                AND entity_reference_digest IS NOT NULL
                AND entity_reference_digest ~ '^[0-9a-f]{64}$'
                AND world_source_digest IS NOT NULL
                AND world_source_digest ~ '^[0-9a-f]{64}$'
                AND canonical_region_id IS NOT NULL
                AND canonical_region_id <> '00000000-0000-0000-0000-000000000000'::uuid
                AND world_lifecycle_epoch IS NOT NULL
                AND world_lifecycle_epoch > 0
                AND world_lifecycle_row_version IS NOT NULL
                AND world_lifecycle_row_version >= 0
                AND world_capture_id IS NOT NULL
                AND world_capture_id <> '00000000-0000-0000-0000-000000000000'::uuid
                AND world_graph_sha256 IS NOT NULL
                AND world_graph_sha256 ~ '^[0-9a-f]{64}$'
                AND world_preparation_input_digest IS NOT NULL
                AND world_preparation_input_digest ~ '^sha256:[0-9a-f]{64}$')
        )
        AND (
            (status IN ('REQUIRED', 'NEVER_DISPATCHED')
                AND redis_readback_digest IS NULL
                AND acknowledged_at IS NULL)
            OR
            (status = 'ACKNOWLEDGED'
                AND bridge_operation_id IS NOT NULL
                AND executor_fence IS NOT NULL
                AND executor_fence > 0
                AND lease_token_correlation IS NOT NULL
                AND lease_token_correlation ~ '^[0-9a-f]{64}$'
                AND redis_readback_digest IS NOT NULL
                AND redis_readback_digest ~ '^[0-9a-f]{64}$'
                AND acknowledged_at IS NOT NULL)
        )
        AND (
            (bridge_operation_id IS NULL
                AND executor_fence IS NULL
                AND lease_token_correlation IS NULL)
            OR
            (bridge_operation_id IS NOT NULL
                AND executor_fence IS NOT NULL
                AND executor_fence > 0
                AND lease_token_correlation IS NOT NULL
                AND lease_token_correlation ~ '^[0-9a-f]{64}$')
        )
    );

ALTER TABLE game_session_canonical_binding_account_index_obligation ADD CONSTRAINT chk_original_new_account_never_dispatched CHECK (
    status <> 'NEVER_DISPATCHED' OR (obligation_ordinal = 0 AND action = 'ADD_OR_RETAIN'
      AND execution_phase = 'BEFORE_FINAL_CAS' AND expected_prior_generation IS NULL
      AND projection_state = 'REQUIRED' AND projection_member IS NULL AND projection_inventory_revision IS NULL));
ALTER TABLE game_session_canonical_binding_region_bridge_obligation ADD CONSTRAINT chk_original_new_region_never_dispatched CHECK (
    status <> 'NEVER_DISPATCHED' OR (bridge_operation_id IS NULL AND redis_readback_digest IS NULL AND acknowledged_at IS NULL));

-- [jooq ignore start]
CREATE FUNCTION original_new_negative_terminal(id uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT EXISTS (SELECT 1 FROM game_session_canonical_binding_transition t
    WHERE t.transition_id = id AND t.status = 'ABORTED'
      AND t.original_new_abandonment_id IS NOT NULL
      AND t.original_new_abandonment_state = 'NEVER_ADMITTED_CLOSED'
      AND t.original_new_abandonment_closure_digest IS NOT NULL
      AND t.original_new_abandonment_orphan_cleanup_id IS NOT NULL
      AND t.original_new_abandonment_resolution_id IS NOT NULL
      AND t.admission_lease_id IS NULL AND t.original_new_candidate_readback_digest IS NULL);
$$;
CREATE OR REPLACE FUNCTION require_first_new_issuer_release_source()
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
          JOIN game_session_canonical_binding_issuer_index_obligation issuer_obligation
            ON issuer_obligation.transition_id = transition.transition_id
           AND issuer_obligation.binding_ref = transition.candidate_binding_ref
          JOIN game_session_canonical_issuer_binding_local_operation original_operation
            ON original_operation.operation_id = NEW.original_local_operation_id
         WHERE transition.transition_id = NEW.transition_id
           AND transition.status = 'ABORTED'
           AND (transition.admission_lease_kind = 'NEW_BINDING' OR original_new_negative_terminal(transition.transition_id))
           AND transition.expected_prior_binding_ref IS NULL
           AND transition.expected_prior_binding_generation IS NULL
           AND COALESCE(transition.admission_orphan_cleanup_id, transition.original_new_abandonment_orphan_cleanup_id) = NEW.orphan_cleanup_id
           AND transition.inventory_revision = NEW.source_inventory_revision
           AND candidate.lifecycle = 'TERMINAL_UNRESOLVED'
           AND candidate.inventory_revision = NEW.source_inventory_revision
           AND candidate.binding_ref = NEW.binding_ref
           AND candidate.binding_generation = NEW.binding_generation
           AND candidate.issuer_reservation_id = NEW.reservation_id
           AND reservation.lifecycle = 'RELEASE_PENDING'
           AND reservation.inventory_revision = NEW.source_inventory_revision
           AND reservation.reservation_id = NEW.reservation_id
           AND reservation.transition_id = NEW.transition_id
           AND reservation.binding_ref = NEW.binding_ref
           AND reservation.binding_generation = NEW.binding_generation
           AND reservation.partition_id = NEW.partition_id
           AND reservation.reservation_fence = NEW.original_reservation_fence
           AND reservation.issuer_coverage_operation_id = NEW.original_issuer_coverage_operation_id
           AND reservation.issuer_coverage_operation_fence = NEW.original_issuer_coverage_operation_fence
           AND reservation.coverage_fence = NEW.original_coverage_fence
           AND reservation.inventory_snapshot_revision = NEW.original_inventory_snapshot_revision
           AND issuer_obligation.status = 'REQUIRED'
           AND issuer_obligation.local_release_operation_id IS NULL
           AND issuer_obligation.inventory_revision = NEW.source_inventory_revision
           AND original_operation.transition_id = NEW.transition_id
           AND original_operation.reservation_id = NEW.reservation_id
           AND original_operation.binding_ref = NEW.binding_ref
           AND original_operation.binding_generation = NEW.binding_generation
           AND original_operation.operation_fence = NEW.original_local_operation_fence
           AND original_operation.local_member_generation = NEW.original_local_member_generation
           AND original_operation.member_status = 'PENDING_PHYSICAL_PROOF'
           AND original_operation.member_schema_version = 2
           AND original_operation.member_value_bytes = NEW.original_member_value_bytes
           AND original_operation.member_value_digest = NEW.original_member_value_digest
           AND NEW.issuer_id = original_operation.issuer_id
           AND NEW.original_reservation_inventory_revision = original_operation.local_inventory_snapshot_revision
    ) THEN
        RAISE EXCEPTION 'Issuer release requires the actual retained first-NEW Account abort and its exact original local operation'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_first_new_issuer_release_source_required';
    END IF;
    RETURN NEW;
END;
$$;

-- Preserve every original release-completion guard while admitting the distinct exact negative source.
DO $migration$
DECLARE
    original text;
    anchor text := 'transition.admission_orphan_cleanup_id = OLD.orphan_cleanup_id';
    replacement text := '(transition.admission_orphan_cleanup_id = OLD.orphan_cleanup_id
                  OR (original_new_negative_terminal(transition.transition_id)
                      AND transition.original_new_abandonment_orphan_cleanup_id = OLD.orphan_cleanup_id))';
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".retain_first_new_issuer_release_operation()'::regprocedure)
      INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact first-NEW release completion cleanup selector';
    END IF;
    EXECUTE replace(original, anchor, replacement);
END;
$migration$;

CREATE OR REPLACE FUNCTION require_first_new_abort_reservation_release()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN

    IF TG_OP = 'UPDATE' AND OLD.lifecycle = 'RESERVED' AND NEW.lifecycle = 'RELEASE_PENDING'
       AND original_new_negative_terminal(NEW.transition_id) THEN
      IF NEW.inventory_revision <= OLD.inventory_revision
          OR (to_jsonb(NEW) - 'lifecycle' - 'inventory_revision') IS DISTINCT FROM (to_jsonb(OLD) - 'lifecycle' - 'inventory_revision')
          OR NOT EXISTS (SELECT 1 FROM game_session_canonical_binding_transition t
            JOIN game_session_canonical_gameplay_binding_inventory i ON i.binding_ref = t.candidate_binding_ref
            WHERE t.transition_id = NEW.transition_id AND t.issuer_reservation_id = NEW.reservation_id
              AND t.candidate_binding_ref = NEW.binding_ref AND t.candidate_binding_generation = NEW.binding_generation
              AND t.inventory_revision = NEW.inventory_revision AND i.inventory_revision = NEW.inventory_revision
              AND i.lifecycle = 'TERMINAL_UNRESOLVED' AND i.account_id = NEW.account_id
              AND i.issuer_reservation_id = NEW.reservation_id AND i.account_index_fence = t.candidate_account_index_fence) THEN
        RAISE EXCEPTION 'Negative reservation release lost its exact retained owner' USING ERRCODE = '23514';
      END IF;
      RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.lifecycle = 'RELEASE_PENDING' AND NEW.lifecycle = 'RELEASED' THEN
        IF (to_jsonb(NEW) - 'lifecycle' - 'inventory_revision')
              IS DISTINCT FROM (to_jsonb(OLD) - 'lifecycle' - 'inventory_revision')
           OR NOT EXISTS (
               SELECT 1
                 FROM game_session_canonical_binding_transition transition
                 JOIN game_session_canonical_gameplay_binding_inventory candidate
                   ON candidate.binding_ref = transition.candidate_binding_ref
                 JOIN game_session_canonical_binding_issuer_index_obligation issuer_obligation
                   ON issuer_obligation.transition_id = transition.transition_id
                  AND issuer_obligation.binding_ref = transition.candidate_binding_ref
                 JOIN game_session_canonical_issuer_binding_local_release_operation release
                   ON release.operation_id = issuer_obligation.local_release_operation_id
                WHERE transition.transition_id = NEW.transition_id
                  AND transition.status = 'ABORTED'
                  AND COALESCE(transition.admission_orphan_cleanup_id, transition.original_new_abandonment_orphan_cleanup_id) = release.orphan_cleanup_id
                  AND transition.inventory_revision = OLD.inventory_revision
                  AND candidate.lifecycle = 'TERMINAL_UNRESOLVED'
                  AND candidate.inventory_revision = OLD.inventory_revision
                  AND candidate.binding_ref = OLD.binding_ref
                  AND candidate.binding_generation = OLD.binding_generation
                  AND candidate.issuer_reservation_id = OLD.reservation_id
                  AND issuer_obligation.status = 'RELEASED'
                  AND issuer_obligation.inventory_revision = NEW.inventory_revision
                  AND issuer_obligation.local_release_operation_id = release.operation_id
                  AND release.status = 'RELEASED'
                  AND release.transition_id = NEW.transition_id
                  AND release.reservation_id = NEW.reservation_id
                  AND release.binding_ref = NEW.binding_ref
                  AND release.binding_generation = NEW.binding_generation
                  AND release.source_inventory_revision = OLD.inventory_revision
                  AND release.completion_inventory_revision = NEW.inventory_revision
                  AND NEW.inventory_revision > OLD.inventory_revision
                  AND release.physical_readback_field_set_bytes IS NOT NULL
                  AND release.physical_readback_field_set_digest ~ '^sha256:[0-9a-f]{64}$'
           ) THEN
            RAISE EXCEPTION 'First-NEW reservation release requires its exact completed physical issuer proof'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'gs_first_new_issuer_release_reservation_source';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.lifecycle = 'RELEASE_PENDING' AND EXISTS (
        SELECT 1 FROM game_session_canonical_binding_transition transition
         WHERE transition.transition_id = OLD.transition_id
           AND transition.issuer_reservation_id = OLD.reservation_id
           AND transition.status = 'ABORTED'
           AND COALESCE(transition.admission_orphan_cleanup_id, transition.original_new_abandonment_orphan_cleanup_id) IS NOT NULL
    ) THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Retained first-NEW abort reservation requires qualified release proof'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_first_new_abort_reservation_retained';
        END IF;
        IF (to_jsonb(NEW) - 'inventory_revision') IS DISTINCT FROM (to_jsonb(OLD) - 'inventory_revision')
           OR NEW.inventory_revision < OLD.inventory_revision THEN
            RAISE EXCEPTION 'Retained first-NEW abort reservation is immutable without qualified release proof'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_first_new_abort_reservation_retained';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

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
               AND COALESCE(transition.admission_orphan_cleanup_id, transition.original_new_abandonment_orphan_cleanup_id) IS NOT NULL
               AND COALESCE(transition.admission_orphan_cleanup_id, transition.original_new_abandonment_orphan_cleanup_id) <>
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
                  AND COALESCE(transition.admission_orphan_cleanup_id, transition.original_new_abandonment_orphan_cleanup_id) = release.orphan_cleanup_id
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

CREATE FUNCTION require_original_new_negative_source_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE id uuid;
BEGIN
  IF TG_OP = 'DELETE' THEN id := OLD.transition_id; ELSE id := NEW.transition_id; END IF;
  IF NOT original_new_negative_terminal(id) THEN RETURN NULL; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM game_session_canonical_binding_transition t
    JOIN game_session_canonical_gameplay_binding_inventory i ON i.binding_ref = t.candidate_binding_ref
    JOIN game_session_canonical_issuer_partition_reservation r ON r.reservation_id = t.issuer_reservation_id
    JOIN game_session_canonical_binding_account_index_obligation a ON a.transition_id = t.transition_id AND a.obligation_ordinal = 0
    JOIN game_session_canonical_binding_region_bridge_obligation b ON b.transition_id = t.transition_id
    JOIN game_session_canonical_binding_issuer_index_obligation s ON s.transition_id = t.transition_id AND s.binding_ref = i.binding_ref
    WHERE t.transition_id = id AND i.transition_id = id AND r.transition_id = id
      AND i.binding_generation = t.candidate_binding_generation AND r.binding_generation = t.candidate_binding_generation
      AND r.binding_ref = i.binding_ref AND a.binding_ref = i.binding_ref AND b.binding_ref = i.binding_ref
      AND a.account_index_fence = i.account_index_fence AND i.account_index_fence = t.candidate_account_index_fence
      AND i.inventory_revision = t.inventory_revision AND r.inventory_revision = t.inventory_revision
      AND a.inventory_revision = t.inventory_revision AND b.inventory_revision = t.inventory_revision AND s.inventory_revision = t.inventory_revision
      AND a.status = 'NEVER_DISPATCHED' AND a.projection_state = 'REQUIRED' AND a.projection_member IS NULL
      AND b.status = 'NEVER_DISPATCHED' AND b.bridge_operation_id IS NULL
      AND ((i.lifecycle = 'TERMINAL_UNRESOLVED' AND r.lifecycle IN ('RELEASE_PENDING', 'RELEASED'))
        OR (i.lifecycle = 'TERMINAL' AND r.lifecycle = 'RELEASED'
          AND EXISTS (SELECT 1 FROM game_session_canonical_issuer_binding_local_retirement retirement
            JOIN game_session_canonical_issuer_binding_local_release_operation release ON release.operation_id = retirement.release_operation_id
            WHERE release.transition_id = id AND retirement.status = 'RETIRED' AND release.status = 'RELEASED')))
      AND NOT EXISTS (SELECT 1 FROM game_session_canonical_account_member_repair_operation WHERE transition_id = id)
      AND NOT EXISTS (SELECT 1 FROM game_session_region_timeline_preparation WHERE transition_id = id)) THEN
    RAISE EXCEPTION 'Original NEW negative terminal lost complete attributable cleanup sources' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER original_new_negative_source_complete AFTER INSERT OR UPDATE OR DELETE ON game_session_canonical_binding_transition
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_original_new_negative_source_complete();
CREATE CONSTRAINT TRIGGER original_new_negative_source_complete AFTER INSERT OR UPDATE OR DELETE ON game_session_canonical_gameplay_binding_inventory
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_original_new_negative_source_complete();
CREATE CONSTRAINT TRIGGER original_new_negative_source_complete AFTER INSERT OR UPDATE OR DELETE ON game_session_canonical_issuer_partition_reservation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_original_new_negative_source_complete();
CREATE CONSTRAINT TRIGGER original_new_negative_source_complete AFTER INSERT OR UPDATE OR DELETE ON game_session_canonical_binding_account_index_obligation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_original_new_negative_source_complete();
CREATE CONSTRAINT TRIGGER original_new_negative_source_complete AFTER INSERT OR UPDATE OR DELETE ON game_session_canonical_binding_region_bridge_obligation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_original_new_negative_source_complete();
CREATE CONSTRAINT TRIGGER original_new_negative_source_complete AFTER INSERT OR UPDATE OR DELETE ON game_session_canonical_binding_issuer_index_obligation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_original_new_negative_source_complete();
CREATE FUNCTION exclude_original_new_abandoned_add() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.mutation_kind IN ('ADD_CAPACITY', 'ADD_BINDING') AND EXISTS (
    SELECT 1 FROM game_session_canonical_issuer_binding_local_operation op
      JOIN game_session_canonical_binding_transition t ON t.transition_id = op.transition_id
    WHERE op.operation_id = NEW.owner_operation_id AND t.original_new_abandonment_id IS NOT NULL) THEN
    RAISE EXCEPTION 'An abandoned original NEW cannot grant a fresh issuer ADD' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER original_new_abandoned_add BEFORE INSERT ON game_session_canonical_issuer_partition_mutation
    FOR EACH ROW EXECUTE FUNCTION exclude_original_new_abandoned_add();

CREATE FUNCTION retain_original_new_negative_sources() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'TRUNCATE' THEN
    IF EXISTS (SELECT 1 FROM game_session_canonical_binding_transition WHERE original_new_abandonment_id IS NOT NULL) THEN
      RAISE EXCEPTION 'Original NEW negative source retention is held' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
  END IF;
  IF EXISTS (SELECT 1 FROM game_session_canonical_binding_transition
      WHERE transition_id = OLD.transition_id AND original_new_abandonment_id IS NOT NULL) THEN
    RAISE EXCEPTION 'Original NEW negative source retention is held' USING ERRCODE = '23514';
  END IF;
  RETURN OLD;
END;
$$;
CREATE TRIGGER original_new_negative_retention BEFORE DELETE ON game_session_canonical_gameplay_binding_inventory
    FOR EACH ROW EXECUTE FUNCTION retain_original_new_negative_sources();
CREATE TRIGGER original_new_negative_retention_truncate BEFORE TRUNCATE ON game_session_canonical_gameplay_binding_inventory
    FOR EACH STATEMENT EXECUTE FUNCTION retain_original_new_negative_sources();

CREATE TRIGGER original_new_negative_retention BEFORE DELETE ON game_session_canonical_issuer_partition_reservation
    FOR EACH ROW EXECUTE FUNCTION retain_original_new_negative_sources();
CREATE TRIGGER original_new_negative_retention_truncate BEFORE TRUNCATE ON game_session_canonical_issuer_partition_reservation
    FOR EACH STATEMENT EXECUTE FUNCTION retain_original_new_negative_sources();

CREATE TRIGGER original_new_negative_retention BEFORE DELETE ON game_session_canonical_binding_account_index_obligation
    FOR EACH ROW EXECUTE FUNCTION retain_original_new_negative_sources();
CREATE TRIGGER original_new_negative_retention_truncate BEFORE TRUNCATE ON game_session_canonical_binding_account_index_obligation
    FOR EACH STATEMENT EXECUTE FUNCTION retain_original_new_negative_sources();

CREATE TRIGGER original_new_negative_retention BEFORE DELETE ON game_session_canonical_binding_region_bridge_obligation
    FOR EACH ROW EXECUTE FUNCTION retain_original_new_negative_sources();
CREATE TRIGGER original_new_negative_retention_truncate BEFORE TRUNCATE ON game_session_canonical_binding_region_bridge_obligation
    FOR EACH STATEMENT EXECUTE FUNCTION retain_original_new_negative_sources();

CREATE TRIGGER original_new_negative_retention BEFORE DELETE ON game_session_canonical_binding_issuer_index_obligation
    FOR EACH ROW EXECUTE FUNCTION retain_original_new_negative_sources();
CREATE TRIGGER original_new_negative_retention_truncate BEFORE TRUNCATE ON game_session_canonical_binding_issuer_index_obligation
    FOR EACH STATEMENT EXECUTE FUNCTION retain_original_new_negative_sources();
-- [jooq ignore stop]
