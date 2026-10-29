-- Installed bootstrap provenance is an alternative original source, never a local registration.
ALTER TABLE game_session_canonical_issuer_binding_local_release_operation
    ALTER COLUMN original_local_operation_id DROP NOT NULL;
ALTER TABLE game_session_canonical_issuer_binding_local_release_operation
    ADD COLUMN original_bootstrap_member_operation_id uuid,
    ADD CONSTRAINT fk_gs_issuer_release_bootstrap_original FOREIGN KEY (original_bootstrap_member_operation_id)
        REFERENCES game_session_canonical_issuer_bootstrap_member_operation (member_operation_id),
    ADD CONSTRAINT uq_gs_issuer_release_bootstrap_original UNIQUE (original_bootstrap_member_operation_id),
    ADD CONSTRAINT chk_gs_issuer_release_original_xor CHECK (
        (original_local_operation_id IS NOT NULL) <> (original_bootstrap_member_operation_id IS NOT NULL)
        AND (original_bootstrap_member_operation_id IS NULL OR original_bootstrap_member_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid));
ALTER TABLE game_session_canonical_issuer_binding_local_retirement
    ALTER COLUMN original_local_operation_id DROP NOT NULL;
ALTER TABLE game_session_canonical_issuer_binding_local_retirement
    ADD COLUMN original_bootstrap_member_operation_id uuid,
    ADD CONSTRAINT fk_gs_issuer_retirement_bootstrap_original FOREIGN KEY (original_bootstrap_member_operation_id)
        REFERENCES game_session_canonical_issuer_bootstrap_member_operation (member_operation_id),
    ADD CONSTRAINT uq_gs_issuer_retirement_bootstrap_original UNIQUE (original_bootstrap_member_operation_id),
    ADD CONSTRAINT chk_gs_issuer_retirement_original_xor CHECK (
        (original_local_operation_id IS NOT NULL) <> (original_bootstrap_member_operation_id IS NOT NULL)
        AND (original_bootstrap_member_operation_id IS NULL OR original_bootstrap_member_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid));

-- [jooq ignore start]
CREATE FUNCTION gs_issuer_bootstrap_release_has_exact_source(release game_session_canonical_issuer_binding_local_release_operation)
RETURNS boolean LANGUAGE sql AS $$
    SELECT EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_bootstrap_member_operation original
        JOIN game_session_canonical_issuer_installed_anchor anchor
          ON anchor.issuer_id = original.issuer_id AND anchor.bootstrap_operation_id = original.bootstrap_operation_id
        JOIN game_session_canonical_issuer_partition_build_plan plan
          ON plan.build_operation_id = original.build_operation_id AND plan.bootstrap_operation_id = anchor.bootstrap_operation_id
        JOIN game_session_canonical_issuer_partition_build_cohort_row cohort
          ON cohort.build_operation_id = plan.build_operation_id AND cohort.source_operation_id = anchor.bootstrap_operation_id
         AND cohort.source_kind = 'RESERVATION' AND cohort.source_key = original.reservation_id::text
        JOIN game_session_canonical_issuer_partition_reservation reservation ON reservation.reservation_id = original.reservation_id
        JOIN game_session_canonical_binding_transition transition ON transition.transition_id = reservation.transition_id
        JOIN game_session_canonical_gameplay_binding_inventory candidate ON candidate.binding_ref = transition.candidate_binding_ref
        JOIN game_session_canonical_binding_issuer_index_obligation obligation
          ON obligation.transition_id = transition.transition_id AND obligation.binding_ref = candidate.binding_ref
        JOIN game_session_canonical_issuer_bootstrap_partition_proof proof
          ON proof.bootstrap_operation_id = anchor.bootstrap_operation_id AND proof.partition_id = reservation.partition_id
        JOIN game_session_canonical_issuer_partition_mutation_head head
          ON head.bootstrap_operation_id = anchor.bootstrap_operation_id AND head.partition_id = reservation.partition_id AND head.issuer_id = original.issuer_id
        CROSS JOIN LATERAL (SELECT convert_from(original.member_value_bytes, 'UTF8')::jsonb AS value) encoded
        WHERE original.member_operation_id = release.original_bootstrap_member_operation_id
          AND release.original_local_operation_id IS NULL
          AND original.issuer_id = release.issuer_id
          AND original.bootstrap_request_id = anchor.bootstrap_request_id
          AND original.build_operation_id = anchor.build_operation_id AND original.build_plan_digest = anchor.build_plan_digest
          AND plan.plan_digest = original.build_plan_digest
          AND original.binding_lifecycle = 'CANDIDATE_PREPARED' AND original.reservation_lifecycle = 'RESERVED'
          AND NOT original.expected_binding_present AND original.counts_toward_capacity
          AND original.member_status = 'PENDING_PHYSICAL_PROOF' AND original.member_schema_version = 2
          AND original.operation_fence = release.original_local_operation_fence
          AND original.local_member_generation = release.original_local_member_generation
          AND original.member_value_bytes = release.original_member_value_bytes
          AND original.member_value_digest = release.original_member_value_digest
          AND original.reservation_id = release.reservation_id AND reservation.binding_ref = release.binding_ref
          AND reservation.binding_generation = release.binding_generation AND reservation.partition_id = release.partition_id
          AND reservation.reservation_fence = release.original_reservation_fence
          AND reservation.issuer_coverage_operation_id = release.original_issuer_coverage_operation_id
          AND reservation.issuer_coverage_operation_fence = release.original_issuer_coverage_operation_fence
          AND reservation.coverage_fence = release.original_coverage_fence
          AND reservation.inventory_snapshot_revision = release.original_inventory_snapshot_revision
          AND reservation.lifecycle = 'RELEASE_PENDING' AND reservation.inventory_revision = release.source_inventory_revision
          AND original.bootstrap_anchor_operation_id = release.anchor_issuer_coverage_operation_id
          AND original.bootstrap_anchor_operation_fence = release.anchor_operation_fence
          AND original.bootstrap_anchor_coverage_fence = release.anchor_coverage_fence
          AND original.bootstrap_anchor_inventory_snapshot_revision = release.anchor_inventory_snapshot_revision
          AND release.anchor_issuer_coverage_operation_id = anchor.issuer_coverage_operation_id
          AND release.anchor_operation_fence = anchor.operation_fence AND release.anchor_coverage_fence = anchor.coverage_fence
          AND release.anchor_inventory_snapshot_revision = anchor.issuer_inventory_snapshot_revision
          AND transition.transition_id = release.transition_id AND original_new_negative_terminal(transition.transition_id)
          AND transition.expected_prior_binding_ref IS NULL AND transition.expected_prior_binding_generation IS NULL
          AND transition.original_new_abandonment_orphan_cleanup_id = release.orphan_cleanup_id
          AND transition.inventory_revision = release.source_inventory_revision
          AND candidate.lifecycle = 'TERMINAL_UNRESOLVED' AND candidate.inventory_revision = release.source_inventory_revision
          AND candidate.transition_id = transition.transition_id AND candidate.issuer_reservation_id = reservation.reservation_id
          AND candidate.binding_generation = release.binding_generation AND candidate.account_index_fence = transition.candidate_account_index_fence
          AND obligation.status = 'REQUIRED' AND obligation.local_release_operation_id IS NULL
          AND obligation.reservation_id = reservation.reservation_id AND obligation.inventory_revision = release.source_inventory_revision
          AND proof.initial_physical_mutation_revision = head.initial_revision AND head.state = 'CURRENT'
          AND encoded.value ->> 'issuerId' = reservation.issuer_id
          AND encoded.value ->> 'accountId' = reservation.account_id::text
          AND encoded.value ->> 'tenantId' = reservation.tenant_id::text
          AND encoded.value ->> 'gameInstanceId' = reservation.game_instance_id::text
          AND encoded.value ->> 'runtimeGameInstanceId' IS NOT DISTINCT FROM reservation.runtime_game_instance_id::text
          AND encoded.value ->> 'sessionId' = reservation.session_id
          AND encoded.value ->> 'bindingGeneration' = reservation.binding_generation::text
          AND encoded.value ->> 'playableStateNamespaceId' = reservation.playable_state_namespace_id::text
          AND encoded.value ->> 'characterId' = reservation.character_id::text
          AND encoded.value ->> 'playableStateScope' = reservation.playable_state_scope
          AND encoded.value ->> 'regionId' = reservation.region_id::text AND encoded.value ->> 'regionEpoch' = reservation.region_epoch::text
          AND encoded.value ->> 'issuerAuthGeneration' = reservation.issuer_auth_generation::text
          AND encoded.value ->> 'issuerIndexLayoutVersion' = reservation.issuer_index_layout_version::text
          AND encoded.value -> 'layout' ->> 'partitionCount' = reservation.issuer_index_partition_count::text
          AND encoded.value -> 'layout' ->> 'partitionCapacity' = reservation.issuer_index_partition_capacity::text
          AND encoded.value -> 'reservation' ->> 'transitionId' = release.transition_id::text
          AND encoded.value -> 'reservation' ->> 'bindingRef' = convert_from(release.binding_ref, 'UTF8')
          AND encoded.value -> 'reservation' ->> 'reservationId' = release.reservation_id::text
          AND encoded.value -> 'reservation' ->> 'inventoryRevision' = release.original_reservation_inventory_revision::text
          AND encoded.value ->> 'schemaVersion' = '2'
          AND encoded.value ->> 'issuerControlEpoch' = anchor.control_epoch::text
          AND encoded.value -> 'memberOperation' ->> 'operationId' = original.member_operation_id::text
          AND encoded.value -> 'memberOperation' ->> 'operationFence' = original.operation_fence::text
          AND encoded.value -> 'memberOperation' ->> 'coverageGeneration' = original.local_member_generation::text
          AND encoded.value -> 'reservation' ->> 'lifecycle' = 'RESERVED'
          AND encoded.value -> 'reservation' ->> 'partitionId' = reservation.partition_id::text
          AND encoded.value -> 'reservation' ->> 'reservationFence' = release.original_reservation_fence::text
          AND encoded.value -> 'reservation' ->> 'issuerCoverageOperationId' = release.original_issuer_coverage_operation_id::text
          AND encoded.value -> 'reservation' ->> 'issuerCoverageOperationFence' = release.original_issuer_coverage_operation_fence::text
          AND encoded.value -> 'reservation' ->> 'coverageFence' = release.original_coverage_fence::text
          AND encoded.value -> 'reservation' ->> 'inventorySnapshotRevision' = release.original_inventory_snapshot_revision::text
          AND encoded.value -> 'anchor' ->> 'issuerCoverageOperationId' = anchor.issuer_coverage_operation_id::text
          AND encoded.value -> 'anchor' ->> 'operationFence' = anchor.operation_fence::text
          AND encoded.value -> 'anchor' ->> 'coverageFence' = anchor.coverage_fence::text
          AND encoded.value -> 'anchor' ->> 'issuerInventorySnapshotRevision' = anchor.issuer_inventory_snapshot_revision::text
          AND decode(encoded.value -> 'anchor' ->> 'sourceCheckpointBytes', 'base64') = anchor.source_checkpoint_bytes
          AND encoded.value -> 'anchor' ->> 'sourceCheckpointDigest' = anchor.source_checkpoint_digest
          AND decode(encoded.value -> 'anchor' ->> 'sourceEventBytes', 'base64') IS NOT DISTINCT FROM anchor.source_event_bytes
          AND encoded.value -> 'anchor' ->> 'sourceEventDigest' IS NOT DISTINCT FROM anchor.source_event_digest
          AND encoded.value -> 'layout' ->> 'mappingIdentity' = anchor.mapping_identity
          AND decode(encoded.value -> 'layout' ->> 'descriptorBytes', 'base64') = anchor.layout_descriptor_bytes
          AND encoded.value -> 'layout' ->> 'descriptorDigest' = anchor.layout_descriptor_digest
          AND (cohort.source_row_json::jsonb ->> 'inventory_revision')::numeric = release.original_reservation_inventory_revision
    );
$$;

CREATE OR REPLACE FUNCTION require_first_new_issuer_release_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.original_bootstrap_member_operation_id IS NOT NULL THEN
        PERFORM singleton_id FROM game_session_canonical_binding_inventory_clock WHERE singleton_id = 1 FOR UPDATE;
        PERFORM gs_require_issuer_reservation_current_membership(NEW.reservation_id);
        IF NOT gs_issuer_bootstrap_release_has_exact_source(NEW) THEN
            RAISE EXCEPTION 'Bootstrap cleanup requires its exact installed frozen member and negative terminal' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
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

CREATE OR REPLACE FUNCTION guard_canonical_issuer_binding_local_retirement()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (
            SELECT 1
              FROM game_session_canonical_issuer_binding_local_release_operation release
             WHERE release.operation_id = NEW.release_operation_id
               AND release.issuer_id = NEW.issuer_id
               AND release.partition_id = NEW.partition_id
               AND release.original_local_operation_id IS NOT DISTINCT FROM NEW.original_local_operation_id
               AND release.original_bootstrap_member_operation_id IS NOT DISTINCT FROM NEW.original_bootstrap_member_operation_id
               AND release.transition_id = NEW.transition_id
               AND release.reservation_id = NEW.reservation_id
               AND release.binding_ref = NEW.binding_ref
               AND release.original_reservation_fence = NEW.original_reservation_fence
               AND release.original_issuer_coverage_operation_id = NEW.original_issuer_coverage_operation_id
               AND release.original_issuer_coverage_operation_fence = NEW.original_issuer_coverage_operation_fence
               AND release.original_coverage_fence = NEW.original_coverage_fence
               AND release.original_inventory_snapshot_revision = NEW.original_inventory_snapshot_revision
               AND release.original_reservation_inventory_revision = NEW.original_reservation_inventory_revision
               AND release.anchor_issuer_coverage_operation_id = NEW.anchor_issuer_coverage_operation_id
               AND release.anchor_operation_fence = NEW.anchor_operation_fence
               AND release.anchor_coverage_fence = NEW.anchor_coverage_fence
               AND release.anchor_inventory_snapshot_revision = NEW.anchor_inventory_snapshot_revision
               AND release.source_inventory_revision = NEW.release_source_inventory_revision
               AND release.completion_inventory_revision = NEW.release_completion_inventory_revision
               AND release.original_member_value_bytes = NEW.original_member_value_bytes
               AND release.original_member_value_digest = NEW.original_member_value_digest
               AND release.released_member_value_bytes = NEW.released_member_value_bytes
               AND release.released_member_value_digest = NEW.released_member_value_digest
               AND release.status = 'RELEASED') THEN
            RAISE EXCEPTION 'Issuer retirement must reference the exact committed release receipt'
                USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1
              FROM game_session_canonical_issuer_partition_mutation mutation
             WHERE mutation.mutation_id = NEW.physical_mutation_id
               AND mutation.issuer_id = NEW.issuer_id
               AND mutation.partition_id = NEW.partition_id
               AND mutation.mutation_kind = 'RETIRE'
               AND mutation.owner_operation_id = NEW.retirement_operation_id
               AND mutation.expected_revision = NEW.expected_revision
               AND mutation.successor_revision = NEW.successor_revision
               AND mutation.exact_intended_change_bytes = NEW.exact_intended_change_bytes
               AND mutation.exact_intended_change_digest = NEW.exact_intended_change_digest
               AND mutation.status = 'PENDING_PHYSICAL_PROOF') THEN
            RAISE EXCEPTION 'Issuer retirement lacks its exact pending physical mutation'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Issuer local retirement history is immutable';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'exact_absence_readback_field_set_bytes'
            - 'exact_absence_readback_field_set_digest' - 'completed_at')
        IS DISTINCT FROM
       (to_jsonb(OLD) - 'status' - 'exact_absence_readback_field_set_bytes'
            - 'exact_absence_readback_field_set_digest' - 'completed_at') THEN
        RAISE EXCEPTION 'Issuer local retirement intent is immutable';
    END IF;
    IF OLD.status <> 'PENDING_PHYSICAL_PROOF'
       OR NEW.status <> 'RETIRED'
       OR NEW.exact_absence_readback_field_set_bytes IS NULL
       OR NEW.exact_absence_readback_field_set_digest IS NULL
       OR NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'Issuer local retirement may only commit one exact absence readback';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM game_session_canonical_issuer_partition_mutation mutation
         WHERE mutation.mutation_id = NEW.physical_mutation_id
           AND mutation.issuer_id = NEW.issuer_id
           AND mutation.partition_id = NEW.partition_id
           AND mutation.mutation_kind = 'RETIRE'
           AND mutation.owner_operation_id = NEW.retirement_operation_id
           AND mutation.expected_revision = NEW.expected_revision
           AND mutation.successor_revision = NEW.successor_revision
           AND mutation.exact_intended_change_bytes = NEW.exact_intended_change_bytes
           AND mutation.exact_intended_change_digest = NEW.exact_intended_change_digest
           AND mutation.status = 'COMMITTED'
           AND mutation.exact_readback_field_set_bytes = NEW.exact_absence_readback_field_set_bytes
           AND mutation.exact_readback_field_set_digest = NEW.exact_absence_readback_field_set_digest) THEN
        RAISE EXCEPTION 'Issuer retirement needs its exact committed physical absence proof';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION gs_issuer_bootstrap_member_has_exact_retirement(original_id uuid)
RETURNS boolean LANGUAGE sql AS $$
    SELECT EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_bootstrap_member_operation original
        JOIN game_session_canonical_issuer_binding_local_release_operation release ON release.original_bootstrap_member_operation_id = original.member_operation_id
        JOIN game_session_canonical_issuer_binding_local_retirement retirement ON retirement.release_operation_id = release.operation_id
        JOIN game_session_canonical_issuer_partition_mutation mutation ON mutation.mutation_id = retirement.physical_mutation_id
        JOIN game_session_canonical_issuer_partition_reservation reservation ON reservation.reservation_id = original.reservation_id
        JOIN game_session_canonical_issuer_installed_anchor anchor ON anchor.bootstrap_operation_id = original.bootstrap_operation_id AND anchor.issuer_id = original.issuer_id
        WHERE original.member_operation_id = original_id AND release.original_local_operation_id IS NULL
          AND original_new_negative_terminal(retirement.transition_id)
          AND original.binding_lifecycle = 'CANDIDATE_PREPARED' AND original.reservation_lifecycle = 'RESERVED'
          AND NOT original.expected_binding_present AND original.counts_toward_capacity
          AND original.bootstrap_request_id = anchor.bootstrap_request_id
          AND original.build_operation_id = anchor.build_operation_id AND original.build_plan_digest = anchor.build_plan_digest
          AND release.status = 'RELEASED' AND retirement.status = 'RETIRED' AND mutation.status = 'COMMITTED'
          AND release.original_member_value_bytes = original.member_value_bytes AND release.original_member_value_digest = original.member_value_digest
          AND release.original_local_operation_fence = original.operation_fence AND release.original_local_member_generation = original.local_member_generation
          AND original.reservation_id = retirement.reservation_id AND original.issuer_id = retirement.issuer_id
          AND original.bootstrap_anchor_operation_id = retirement.anchor_issuer_coverage_operation_id
          AND original.bootstrap_anchor_operation_fence = retirement.anchor_operation_fence
          AND original.bootstrap_anchor_coverage_fence = retirement.anchor_coverage_fence
          AND original.bootstrap_anchor_inventory_snapshot_revision = retirement.anchor_inventory_snapshot_revision
          AND reservation.lifecycle = 'RELEASED'
          AND (reservation.inventory_revision = release.completion_inventory_revision
            OR (reservation.inventory_revision > release.completion_inventory_revision
              AND original_new_negative_terminal(retirement.transition_id)
              AND EXISTS (SELECT 1 FROM game_session_canonical_gameplay_binding_inventory terminal
                WHERE terminal.binding_ref = retirement.binding_ref AND terminal.transition_id = retirement.transition_id
                  AND terminal.lifecycle = 'TERMINAL' AND terminal.inventory_revision = reservation.inventory_revision)))
          AND release.source_inventory_revision = retirement.release_source_inventory_revision
          AND release.completion_inventory_revision = retirement.release_completion_inventory_revision
          AND retirement.issuer_id IS NOT DISTINCT FROM release.issuer_id
          AND retirement.partition_id IS NOT DISTINCT FROM release.partition_id
          AND retirement.original_local_operation_id IS NOT DISTINCT FROM release.original_local_operation_id
          AND retirement.original_bootstrap_member_operation_id IS NOT DISTINCT FROM release.original_bootstrap_member_operation_id
          AND retirement.transition_id IS NOT DISTINCT FROM release.transition_id
          AND retirement.reservation_id IS NOT DISTINCT FROM release.reservation_id
          AND retirement.binding_ref IS NOT DISTINCT FROM release.binding_ref
          AND retirement.original_reservation_fence IS NOT DISTINCT FROM release.original_reservation_fence
          AND retirement.original_issuer_coverage_operation_id IS NOT DISTINCT FROM release.original_issuer_coverage_operation_id
          AND retirement.original_issuer_coverage_operation_fence IS NOT DISTINCT FROM release.original_issuer_coverage_operation_fence
          AND retirement.original_coverage_fence IS NOT DISTINCT FROM release.original_coverage_fence
          AND retirement.original_inventory_snapshot_revision IS NOT DISTINCT FROM release.original_inventory_snapshot_revision
          AND retirement.original_reservation_inventory_revision IS NOT DISTINCT FROM release.original_reservation_inventory_revision
          AND retirement.anchor_issuer_coverage_operation_id IS NOT DISTINCT FROM release.anchor_issuer_coverage_operation_id
          AND retirement.anchor_operation_fence IS NOT DISTINCT FROM release.anchor_operation_fence
          AND retirement.anchor_coverage_fence IS NOT DISTINCT FROM release.anchor_coverage_fence
          AND retirement.anchor_inventory_snapshot_revision IS NOT DISTINCT FROM release.anchor_inventory_snapshot_revision
          AND retirement.original_member_value_bytes IS NOT DISTINCT FROM release.original_member_value_bytes
          AND retirement.original_member_value_digest IS NOT DISTINCT FROM release.original_member_value_digest
          AND retirement.released_member_value_bytes IS NOT DISTINCT FROM release.released_member_value_bytes
          AND retirement.released_member_value_digest IS NOT DISTINCT FROM release.released_member_value_digest
          AND mutation.issuer_id = retirement.issuer_id AND mutation.bootstrap_operation_id = original.bootstrap_operation_id
          AND mutation.partition_id = retirement.partition_id AND mutation.mutation_kind = 'RETIRE'
          AND mutation.owner_operation_id = retirement.retirement_operation_id
          AND mutation.expected_revision = retirement.expected_revision AND mutation.successor_revision = retirement.successor_revision
          AND mutation.exact_intended_change_bytes = retirement.exact_intended_change_bytes
          AND mutation.exact_intended_change_digest = retirement.exact_intended_change_digest
          AND mutation.exact_readback_field_set_bytes = retirement.exact_absence_readback_field_set_bytes
          AND mutation.exact_readback_field_set_digest = retirement.exact_absence_readback_field_set_digest
          AND retirement.completed_at IS NOT NULL);
$$;

CREATE OR REPLACE FUNCTION gs_retire_issuer_current_local_operation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.original_bootstrap_member_operation_id IS NOT NULL THEN
        IF OLD.status = 'PENDING_PHYSICAL_PROOF' AND NEW.status = 'RETIRED'
           AND NOT gs_issuer_bootstrap_member_has_exact_retirement(NEW.original_bootstrap_member_operation_id) THEN
            RAISE EXCEPTION 'Bootstrap retirement lost exact committed original proof' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    -- The existing BEFORE guard has already required this exact original mutation/absence proof.
    IF OLD.status = 'PENDING_PHYSICAL_PROOF' AND NEW.status = 'RETIRED' THEN
        DELETE FROM game_session_canonical_issuer_current_local_operation current
         USING game_session_canonical_issuer_binding_local_operation original
         WHERE original.operation_id = NEW.original_local_operation_id
           AND original.anchor_issuer_coverage_operation_id = NEW.anchor_issuer_coverage_operation_id
           AND current.operation_id = original.operation_id
           AND current.issuer_id = NEW.issuer_id
           AND current.anchor_bootstrap_operation_id = original.anchor_bootstrap_operation_id
           AND current.partition_id = NEW.partition_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Completed issuer retirement lost its exact current original membership'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
