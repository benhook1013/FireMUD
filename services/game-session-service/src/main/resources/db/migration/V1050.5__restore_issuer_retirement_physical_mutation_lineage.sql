-- Bind pending and committed retirement proof to the exact installed bootstrap domain.
-- Preserve both ordinary and installed-bootstrap release sources and all immutable history.
-- [jooq ignore start]
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
               AND mutation.bootstrap_operation_id = NEW.anchor_issuer_coverage_operation_id
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
           AND mutation.bootstrap_operation_id = NEW.anchor_issuer_coverage_operation_id
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
-- [jooq ignore stop]
