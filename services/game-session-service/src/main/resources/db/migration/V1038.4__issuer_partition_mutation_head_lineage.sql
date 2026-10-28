-- Retain every existing bootstrap head and physical mutation while separating versioned
-- uninstalled bootstrap key domains. No intent, work, pending receipt or history is discarded.
ALTER TABLE game_session_canonical_issuer_partition_mutation_head
    DROP CONSTRAINT fk_gs_issuer_partition_mutation_head_bootstrap;
ALTER TABLE game_session_canonical_issuer_partition_mutation_head
    ADD CONSTRAINT fk_gs_issuer_partition_mutation_head_bootstrap
        FOREIGN KEY (bootstrap_operation_id, issuer_id)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent (operation_id, issuer_id);

ALTER TABLE game_session_canonical_issuer_partition_mutation
    ADD COLUMN bootstrap_operation_id uuid;

-- The old primary key makes this association unique. Retention guards remain enabled except for
-- this exact owner-controlled backfill of the newly introduced lineage column.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_issuer_partition_mutation
    DISABLE TRIGGER trg_gs_issuer_partition_mutation_history;
UPDATE game_session_canonical_issuer_partition_mutation mutation
    SET bootstrap_operation_id = head.bootstrap_operation_id
    FROM game_session_canonical_issuer_partition_mutation_head head
    WHERE head.issuer_id = mutation.issuer_id AND head.partition_id = mutation.partition_id;
ALTER TABLE game_session_canonical_issuer_partition_mutation
    ENABLE TRIGGER trg_gs_issuer_partition_mutation_history;
-- [jooq ignore stop]

ALTER TABLE game_session_canonical_issuer_partition_mutation
    ALTER COLUMN bootstrap_operation_id SET NOT NULL;
ALTER TABLE game_session_canonical_issuer_partition_mutation
    ADD CONSTRAINT uq_gs_issuer_partition_mutation_lineage
        UNIQUE (issuer_id, bootstrap_operation_id, partition_id, mutation_id);
ALTER TABLE game_session_canonical_issuer_partition_mutation
    DROP CONSTRAINT fk_gs_issuer_partition_mutation_head;
ALTER TABLE game_session_canonical_issuer_partition_mutation_head
    DROP CONSTRAINT fk_gs_issuer_partition_mutation_head_pending;
ALTER TABLE game_session_canonical_issuer_partition_mutation_head
    DROP CONSTRAINT pk_gs_issuer_partition_mutation_head;
ALTER TABLE game_session_canonical_issuer_partition_mutation_head
    ADD CONSTRAINT pk_gs_issuer_partition_mutation_head
        PRIMARY KEY (issuer_id, bootstrap_operation_id, partition_id);
ALTER TABLE game_session_canonical_issuer_partition_mutation
    ADD CONSTRAINT fk_gs_issuer_partition_mutation_head
        FOREIGN KEY (issuer_id, bootstrap_operation_id, partition_id)
        REFERENCES game_session_canonical_issuer_partition_mutation_head
            (issuer_id, bootstrap_operation_id, partition_id);
ALTER TABLE game_session_canonical_issuer_partition_mutation_head
    ADD CONSTRAINT fk_gs_issuer_partition_mutation_head_pending
        FOREIGN KEY (issuer_id, bootstrap_operation_id, partition_id, pending_mutation_id)
        REFERENCES game_session_canonical_issuer_partition_mutation
            (issuer_id, bootstrap_operation_id, partition_id, mutation_id)
        DEFERRABLE INITIALLY DEFERRED;

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION guard_canonical_issuer_partition_mutation_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING_PHYSICAL_PROOF'
           OR NOT EXISTS (
                SELECT 1 FROM game_session_canonical_issuer_installed_anchor anchor
                 WHERE anchor.issuer_id = NEW.issuer_id
                   AND anchor.bootstrap_operation_id = NEW.bootstrap_operation_id)
           OR NOT EXISTS (
                SELECT 1 FROM game_session_canonical_issuer_partition_mutation_head head
                 WHERE head.issuer_id = NEW.issuer_id
                   AND head.bootstrap_operation_id = NEW.bootstrap_operation_id
                   AND head.partition_id = NEW.partition_id
                   AND head.state = 'CURRENT'
                   AND head.current_revision = NEW.expected_revision
                   AND head.pending_mutation_id IS NULL) THEN
            RAISE EXCEPTION 'Issuer partition mutation requires its exact current unreserved revision'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Issuer partition mutation history is immutable';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'exact_readback_field_set_bytes'
            - 'exact_readback_field_set_digest' - 'completed_at')
        IS DISTINCT FROM
       (to_jsonb(OLD) - 'status' - 'exact_readback_field_set_bytes'
            - 'exact_readback_field_set_digest' - 'completed_at') THEN
        RAISE EXCEPTION 'Issuer partition mutation intent is immutable';
    END IF;
    IF OLD.status <> 'PENDING_PHYSICAL_PROOF'
       OR NEW.status <> 'COMMITTED'
       OR NEW.exact_readback_field_set_bytes IS NULL
       OR NEW.exact_readback_field_set_digest IS NULL
       OR NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'Issuer partition mutation may only commit one exact readback';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION guard_canonical_issuer_partition_revision_uniqueness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_partition_mutation_head head
         WHERE head.initial_revision = NEW.successor_revision
            OR (head.initial_revision = NEW.expected_revision
                AND (head.issuer_id, head.bootstrap_operation_id, head.partition_id)
                    <> (NEW.issuer_id, NEW.bootstrap_operation_id, NEW.partition_id)))
       OR EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_partition_mutation mutation
         WHERE mutation.expected_revision = NEW.successor_revision
            OR mutation.successor_revision = NEW.successor_revision
            OR ((mutation.expected_revision = NEW.expected_revision
                    OR mutation.successor_revision = NEW.expected_revision)
                AND (mutation.issuer_id, mutation.bootstrap_operation_id, mutation.partition_id)
                    <> (NEW.issuer_id, NEW.bootstrap_operation_id, NEW.partition_id))) THEN
        RAISE EXCEPTION 'Issuer partition physical revisions are never reused';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION guard_canonical_issuer_partition_initial_revision_uniqueness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_partition_mutation mutation
         WHERE mutation.expected_revision = NEW.initial_revision
            OR mutation.successor_revision = NEW.initial_revision)
       OR EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_partition_mutation_head head
         WHERE head.initial_revision = NEW.initial_revision
           AND (head.issuer_id, head.bootstrap_operation_id, head.partition_id)
                    <> (NEW.issuer_id, NEW.bootstrap_operation_id, NEW.partition_id)) THEN
        RAISE EXCEPTION 'Issuer partition initial physical revision is reused';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION guard_canonical_issuer_partition_mutation_head()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'INITIAL_INSTALL_PENDING'
           OR NOT EXISTS (
                SELECT 1 FROM game_session_canonical_issuer_layout_bootstrap_control control
                 WHERE control.issuer_id = NEW.issuer_id
                   AND control.state = 'INITIAL_BOOTSTRAP_INTENT'
                   AND control.current_operation_id = NEW.bootstrap_operation_id)
           OR NEW.current_revision <> NEW.initial_revision
           OR NEW.pending_mutation_id IS NOT NULL
           OR EXISTS (
                SELECT 1 FROM game_session_canonical_issuer_bootstrap_partition_proof proof
                 WHERE proof.bootstrap_operation_id = NEW.bootstrap_operation_id
                   AND proof.partition_id = NEW.partition_id)
           OR EXISTS (
                SELECT 1 FROM game_session_canonical_issuer_installed_anchor anchor
                 WHERE anchor.issuer_id = NEW.issuer_id) THEN
            RAISE EXCEPTION 'Initial issuer partition revision requires an uninstalled bootstrap owner';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Issuer partition mutation head is retained';
    END IF;
    IF (to_jsonb(NEW) - 'state' - 'current_revision' - 'pending_mutation_id')
        IS DISTINCT FROM
       (to_jsonb(OLD) - 'state' - 'current_revision' - 'pending_mutation_id') THEN
        RAISE EXCEPTION 'Issuer partition mutation-head identity is immutable';
    END IF;
    IF OLD.state = 'INITIAL_INSTALL_PENDING' THEN
        IF NEW.state <> 'CURRENT'
           OR NEW.current_revision <> OLD.initial_revision
           OR NEW.pending_mutation_id IS NOT NULL
           OR NOT EXISTS (
                SELECT 1 FROM game_session_canonical_issuer_bootstrap_partition_proof proof
                 WHERE proof.bootstrap_operation_id = OLD.bootstrap_operation_id
                   AND proof.issuer_id = OLD.issuer_id
                   AND proof.partition_id = OLD.partition_id
                   AND proof.initial_physical_mutation_revision = OLD.initial_revision
                   AND proof.proof_state = 'EXACT_PHYSICAL_READBACK') THEN
            RAISE EXCEPTION 'Initial issuer partition revision requires positive bootstrap proof';
        END IF;
    ELSIF OLD.pending_mutation_id IS NULL THEN
        IF NEW.state <> OLD.state
           OR NEW.pending_mutation_id IS NULL
           OR NEW.current_revision <> OLD.current_revision THEN
            RAISE EXCEPTION 'Issuer partition mutation requires one durable pending operation';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM game_session_canonical_issuer_partition_mutation mutation
             WHERE mutation.mutation_id = NEW.pending_mutation_id
               AND mutation.issuer_id = OLD.issuer_id
               AND mutation.bootstrap_operation_id = OLD.bootstrap_operation_id
               AND mutation.partition_id = OLD.partition_id
               AND mutation.status = 'PENDING_PHYSICAL_PROOF'
               AND mutation.expected_revision = OLD.current_revision) THEN
            RAISE EXCEPTION 'Issuer partition pending head lacks its exact prepared mutation';
        END IF;
    ELSE
        IF NEW.state <> OLD.state
           OR NEW.pending_mutation_id IS NOT NULL
           OR NEW.current_revision = OLD.current_revision THEN
            RAISE EXCEPTION 'Issuer partition revision requires its exact pending operation';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM game_session_canonical_issuer_partition_mutation mutation
             WHERE mutation.mutation_id = OLD.pending_mutation_id
               AND mutation.issuer_id = OLD.issuer_id
               AND mutation.bootstrap_operation_id = OLD.bootstrap_operation_id
               AND mutation.partition_id = OLD.partition_id
               AND mutation.status = 'COMMITTED'
               AND mutation.expected_revision = OLD.current_revision
               AND mutation.successor_revision = NEW.current_revision) THEN
            RAISE EXCEPTION 'Issuer partition revision does not match its committed mutation';
        END IF;
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
               AND release.original_local_operation_id = NEW.original_local_operation_id
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
