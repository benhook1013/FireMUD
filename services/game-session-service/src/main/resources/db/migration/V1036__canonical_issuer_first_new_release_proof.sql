-- One proof-backed terminal successor for an actual first-NEW Account abort. The immutable V1034
-- registration remains the old physical compare; this row retains the exact successor and its
-- later positive one-key readback/completion without rewriting that original operation.

ALTER TABLE game_session_canonical_binding_issuer_index_obligation
    DROP CONSTRAINT chk_gs_canonical_binding_issuer_index_obligation;

ALTER TABLE game_session_canonical_binding_issuer_index_obligation
    ADD COLUMN local_release_operation_id uuid;

ALTER TABLE game_session_canonical_binding_issuer_index_obligation
    ADD CONSTRAINT chk_gs_canonical_binding_issuer_index_obligation CHECK (
        binding_generation > 0
        AND inventory_revision > 0
        AND ((status = 'REQUIRED' AND local_release_operation_id IS NULL)
            OR (status = 'RELEASED' AND local_release_operation_id IS NOT NULL
                AND local_release_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid)));

CREATE TABLE game_session_canonical_issuer_binding_local_release_operation (
    operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    transition_id uuid NOT NULL,
    orphan_cleanup_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    original_local_operation_id uuid NOT NULL,
    binding_ref bytea NOT NULL,
    binding_generation numeric NOT NULL,
    partition_id numeric NOT NULL,

    original_reservation_fence uuid NOT NULL,
    original_issuer_coverage_operation_id uuid NOT NULL,
    original_issuer_coverage_operation_fence numeric NOT NULL,
    original_coverage_fence numeric NOT NULL,
    original_inventory_snapshot_revision numeric NOT NULL,
    original_reservation_inventory_revision numeric NOT NULL,
    original_local_operation_fence numeric NOT NULL,
    original_local_member_generation numeric NOT NULL,

    anchor_issuer_coverage_operation_id uuid NOT NULL,
    anchor_operation_fence numeric NOT NULL,
    anchor_coverage_fence numeric NOT NULL,
    anchor_inventory_snapshot_revision numeric NOT NULL,
    source_inventory_revision numeric NOT NULL,

    release_operation_fence numeric NOT NULL,
    release_local_member_generation numeric NOT NULL,
    original_member_value_bytes bytea NOT NULL,
    original_member_value_digest character varying(71) NOT NULL,
    released_member_value_bytes bytea NOT NULL,
    released_member_value_digest character varying(71) NOT NULL,

    status character varying(32) NOT NULL,
    physical_readback_field_set_bytes bytea,
    physical_readback_field_set_digest character varying(71),
    completion_inventory_revision numeric,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamp with time zone,

    CONSTRAINT pk_gs_issuer_binding_local_release_operation PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_issuer_binding_local_release_transition UNIQUE (transition_id),
    CONSTRAINT uq_gs_issuer_binding_local_release_reservation UNIQUE (reservation_id),
    CONSTRAINT uq_gs_issuer_binding_local_release_original UNIQUE (original_local_operation_id),
    CONSTRAINT uq_gs_issuer_binding_local_release_fence UNIQUE (issuer_id, release_operation_fence),
    CONSTRAINT uq_gs_issuer_binding_local_release_generation UNIQUE (issuer_id, release_local_member_generation),
    CONSTRAINT fk_gs_issuer_binding_local_release_transition
        FOREIGN KEY (transition_id) REFERENCES game_session_canonical_binding_transition (transition_id),
    CONSTRAINT fk_gs_issuer_binding_local_release_reservation
        FOREIGN KEY (reservation_id) REFERENCES game_session_canonical_issuer_partition_reservation (reservation_id),
    CONSTRAINT fk_gs_issuer_binding_local_release_original
        FOREIGN KEY (original_local_operation_id)
        REFERENCES game_session_canonical_issuer_binding_local_operation (operation_id),
    CONSTRAINT chk_gs_issuer_binding_local_release_operation CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND orphan_cleanup_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND binding_generation > 0
        AND partition_id >= 0
        AND original_reservation_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_issuer_coverage_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_issuer_coverage_operation_fence > 0
        AND original_coverage_fence > 0
        AND original_inventory_snapshot_revision > 0
        AND original_reservation_inventory_revision > 0
        AND original_local_operation_fence > 0
        AND original_local_member_generation > 0
        AND anchor_issuer_coverage_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND anchor_operation_fence > 0
        AND anchor_coverage_fence > 0
        AND anchor_inventory_snapshot_revision > 0
        AND source_inventory_revision > 0
        AND release_operation_fence > 0
        AND release_local_member_generation = release_operation_fence + 1
        AND release_operation_fence > original_local_operation_fence
        AND release_local_member_generation > original_local_member_generation
        AND octet_length(original_member_value_bytes) BETWEEN 1 AND 65536
        AND original_member_value_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(released_member_value_bytes) BETWEEN 1 AND 65536
        AND released_member_value_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((status = 'PENDING_PHYSICAL_PROOF'
                AND physical_readback_field_set_bytes IS NULL
                AND physical_readback_field_set_digest IS NULL
                AND completion_inventory_revision IS NULL
                AND completed_at IS NULL)
            OR (status = 'RELEASED'
                AND physical_readback_field_set_bytes IS NOT NULL
                AND octet_length(physical_readback_field_set_bytes) BETWEEN 1 AND 17825792
                AND physical_readback_field_set_digest ~ '^sha256:[0-9a-f]{64}$'
                AND completion_inventory_revision > source_inventory_revision
                AND completed_at IS NOT NULL))
    )
);

-- Preserve the exact bytea identity in PostgreSQL while keeping jOOQ's H2 schema parser bounded.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_issuer_binding_local_release_operation
    ADD CONSTRAINT fk_gs_issuer_binding_local_release_issuer_obligation
    FOREIGN KEY (transition_id, binding_ref)
    REFERENCES game_session_canonical_binding_issuer_index_obligation (transition_id, binding_ref);

ALTER TABLE game_session_canonical_binding_issuer_index_obligation
    ADD CONSTRAINT fk_gs_issuer_binding_local_release_obligation
    FOREIGN KEY (local_release_operation_id)
    REFERENCES game_session_canonical_issuer_binding_local_release_operation (operation_id);
-- [jooq ignore stop]

CREATE INDEX ix_gs_issuer_binding_local_release_status
    ON game_session_canonical_issuer_binding_local_release_operation (issuer_id, status, partition_id);

-- PostgreSQL trigger bodies remain enforced by Flyway; jOOQ OSS cannot parse this procedural DDL.
-- [jooq ignore start]
CREATE FUNCTION require_first_new_issuer_release_source()
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
           AND transition.admission_lease_kind = 'NEW_BINDING'
           AND transition.expected_prior_binding_ref IS NULL
           AND transition.expected_prior_binding_generation IS NULL
           AND transition.admission_orphan_cleanup_id = NEW.orphan_cleanup_id
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

-- The only RELEASE_PENDING -> RELEASED edge is the durable successor that already has
-- exact positive Redis readback. Its physical value carries preparation revision P; the
-- guarded current-state receipt and all V29 rows advance together at fresh revision R.
CREATE FUNCTION require_first_new_issuer_release_reservation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.lifecycle = 'RELEASE_PENDING' AND NEW.lifecycle = 'RELEASED' THEN
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
                  AND transition.admission_orphan_cleanup_id = release.orphan_cleanup_id
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
    END IF;
    RETURN NEW;
END;
$$;

-- Extend the retained V1035 guard with this exact same proof-backed edge. Its original
-- tuple, pending-state and deletion protections continue to apply to every other update.
CREATE OR REPLACE FUNCTION require_first_new_abort_reservation_release()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
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
                  AND transition.admission_orphan_cleanup_id = release.orphan_cleanup_id
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
           AND transition.admission_orphan_cleanup_id IS NOT NULL
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

CREATE TRIGGER game_session_first_new_issuer_release_reservation
    BEFORE UPDATE ON game_session_canonical_issuer_partition_reservation
    FOR EACH ROW EXECUTE FUNCTION require_first_new_issuer_release_reservation();

CREATE FUNCTION require_first_new_issuer_release_obligation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
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

CREATE TRIGGER game_session_first_new_issuer_release_obligation
    BEFORE UPDATE OR DELETE ON game_session_canonical_binding_issuer_index_obligation
    FOR EACH ROW EXECUTE FUNCTION require_first_new_issuer_release_obligation();

-- Any durable-source change rechecks the complete retained first-NEW tuple at commit.
CREATE CONSTRAINT TRIGGER game_session_first_new_issuer_release_reservation_atomic
    AFTER UPDATE ON game_session_canonical_issuer_partition_reservation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION require_first_new_abort_source_complete();
CREATE CONSTRAINT TRIGGER game_session_first_new_issuer_release_obligation_atomic
    AFTER UPDATE ON game_session_canonical_binding_issuer_index_obligation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION require_first_new_abort_source_complete();
CREATE CONSTRAINT TRIGGER game_session_first_new_issuer_release_candidate_atomic
    AFTER UPDATE ON game_session_canonical_gameplay_binding_inventory
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION require_first_new_abort_source_complete();
CREATE CONSTRAINT TRIGGER game_session_first_new_issuer_release_region_atomic
    AFTER UPDATE ON game_session_canonical_binding_region_bridge_obligation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION require_first_new_abort_source_complete();
CREATE CONSTRAINT TRIGGER game_session_first_new_issuer_release_account_atomic
    AFTER UPDATE ON game_session_canonical_binding_account_index_obligation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION require_first_new_abort_source_complete();
CREATE CONSTRAINT TRIGGER game_session_first_new_issuer_release_artifact_atomic
    AFTER INSERT OR UPDATE ON game_session_canonical_issuer_binding_local_release_operation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION require_first_new_abort_source_complete();

CREATE TRIGGER game_session_first_new_issuer_release_source
    BEFORE INSERT ON game_session_canonical_issuer_binding_local_release_operation
    FOR EACH ROW EXECUTE FUNCTION require_first_new_issuer_release_source();

CREATE FUNCTION retain_first_new_issuer_release_operation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status = 'RELEASED'
       OR NEW.status <> 'RELEASED'
       OR (to_jsonb(NEW) - 'status' - 'physical_readback_field_set_bytes'
             - 'physical_readback_field_set_digest' - 'completion_inventory_revision' - 'completed_at')
          IS DISTINCT FROM
          (to_jsonb(OLD) - 'status' - 'physical_readback_field_set_bytes'
             - 'physical_readback_field_set_digest' - 'completion_inventory_revision' - 'completed_at')
       OR NEW.physical_readback_field_set_bytes IS NULL
       OR NEW.physical_readback_field_set_digest IS NULL
       OR NEW.completion_inventory_revision IS NULL
       OR NEW.completed_at IS NULL
       OR NEW.completion_inventory_revision <= NEW.source_inventory_revision
       OR NOT EXISTS (
           SELECT 1
             FROM game_session_canonical_binding_transition transition
             JOIN game_session_canonical_gameplay_binding_inventory candidate
               ON candidate.binding_ref = transition.candidate_binding_ref
             JOIN game_session_canonical_issuer_partition_reservation reservation
               ON reservation.reservation_id = transition.issuer_reservation_id
             JOIN game_session_canonical_binding_issuer_index_obligation issuer_obligation
               ON issuer_obligation.transition_id = transition.transition_id
              AND issuer_obligation.binding_ref = transition.candidate_binding_ref
             JOIN game_session_canonical_binding_inventory_clock inventory_clock
               ON inventory_clock.singleton_id = 1
            WHERE transition.transition_id = OLD.transition_id
              AND transition.status = 'ABORTED'
              AND transition.admission_orphan_cleanup_id = OLD.orphan_cleanup_id
              AND transition.inventory_revision = OLD.source_inventory_revision
              AND candidate.lifecycle = 'TERMINAL_UNRESOLVED'
              AND candidate.inventory_revision = OLD.source_inventory_revision
              AND reservation.lifecycle = 'RELEASE_PENDING'
              AND reservation.inventory_revision = OLD.source_inventory_revision
              AND issuer_obligation.status = 'REQUIRED'
              AND issuer_obligation.local_release_operation_id IS NULL
              AND issuer_obligation.inventory_revision = OLD.source_inventory_revision
              AND inventory_clock.inventory_revision + 1 = NEW.completion_inventory_revision
       ) THEN
        RAISE EXCEPTION 'Issuer release completion requires its exact pending source and fresh inventory revision'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'gs_first_new_issuer_release_completion_source';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_first_new_issuer_release_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_binding_local_release_operation
    FOR EACH ROW EXECUTE FUNCTION retain_first_new_issuer_release_operation();
CREATE TRIGGER game_session_first_new_issuer_release_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_binding_local_release_operation
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_binding_local_operation_change();

COMMENT ON TABLE game_session_canonical_issuer_binding_local_release_operation IS
    'Immutable original-operation-linked first-NEW issuer release successor; RELEASED requires exact one-key physical readback and a fresh guarded completion revision.';


-- Retain the latest abort/account-repair source checks while permitting only a proof-backed
-- issuer-local release. Account and region obligations remain unresolved.
CREATE OR REPLACE FUNCTION require_first_new_abort_source_complete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    affected_transition_id uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN affected_transition_id := OLD.transition_id;
    ELSE affected_transition_id := NEW.transition_id;
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
                       JOIN game_session_canonical_account_coverage_control control
                         ON control.account_id = repair.account_id
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
                        AND control.state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
                        AND control.last_operation_fence = repair.account_control_epoch
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

-- [jooq ignore stop]
