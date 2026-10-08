-- PostgreSQL truncates these V31 identifiers at 63 bytes. Rename the existing relations and
-- foreign keys through their published names so PostgreSQL and DDL-based jOOQ generation converge
-- on the same canonical identifiers without changing the V31 checksum.
ALTER TABLE game_session_canonical_account_coverage_snapshot_issuer_obligation
    RENAME TO gs_canonical_account_coverage_snapshot_issuer_obligation;

ALTER TABLE gs_canonical_account_coverage_snapshot_issuer_obligation
    RENAME CONSTRAINT fk_gs_canonical_account_coverage_snapshot_issuer_obligation_operation
    TO fk_gs_account_coverage_snapshot_issuer_operation;

ALTER TABLE game_session_canonical_account_coverage_snapshot_region_obligation
    RENAME TO gs_canonical_account_coverage_snapshot_region_obligation;

ALTER TABLE gs_canonical_account_coverage_snapshot_region_obligation
    RENAME CONSTRAINT fk_gs_canonical_account_coverage_snapshot_region_obligation_operation
    TO fk_gs_account_coverage_snapshot_region_operation;

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION reject_runtime_row_mutation_during_lease_bound_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    locked_transition RECORD;
BEGIN
    -- Keep the V34 lock order: candidate transition rows, including PREPARED rows, serialize
    -- mutation against decision acquisition. A waiting runtime write rechecks the committed
    -- transition state before applying this row-level guard.
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
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'Game Instance runtime row is fenced by an unresolved lease-bound binding decision'
                    USING ERRCODE = '23514',
                          CONSTRAINT = 'gs_binding_runtime_row_mutation_fenced';
            END IF;
            IF ROW(
                   NEW.tenant_id,
                   NEW.id,
                   NEW.game_instance_uuid,
                   NEW.status,
                   NEW.run_owned_start_active_epoch,
                   NEW.version_id,
                   NEW.game_template_id,
                   NEW.launch_descriptor_id,
                   NEW.release_bundle_id,
                   NEW.generation_config_revision,
                   NEW.version_state_epoch,
                   NEW.run_owned_start_request_id,
                   NEW.run_owned_start_published_release_bundle_ref
               ) IS DISTINCT FROM ROW(
                   OLD.tenant_id,
                   OLD.id,
                   OLD.game_instance_uuid,
                   OLD.status,
                   OLD.run_owned_start_active_epoch,
                   OLD.version_id,
                   OLD.game_template_id,
                   OLD.launch_descriptor_id,
                   OLD.release_bundle_id,
                   OLD.generation_config_revision,
                   OLD.version_state_epoch,
                   OLD.run_owned_start_request_id,
                   OLD.run_owned_start_published_release_bundle_ref
               )
               OR NEW.row_version IS NULL
               OR NEW.row_version < OLD.row_version THEN
                RAISE EXCEPTION 'Game Instance runtime row is fenced by an unresolved lease-bound binding decision'
                    USING ERRCODE = '23514',
                          CONSTRAINT = 'gs_binding_runtime_row_mutation_fenced';
            END IF;
        END IF;
    END LOOP;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
