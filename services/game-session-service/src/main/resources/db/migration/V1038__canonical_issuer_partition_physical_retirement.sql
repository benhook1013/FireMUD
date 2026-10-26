-- One opaque current Redis mutation revision per installed issuer partition. The initial value is
-- created only by the existing positive bootstrap owner; every later value is owner-allocated and
-- advanced atomically with one original physical mutation. Historical mutation and retirement
-- receipts stay in PostgreSQL while retired capacity fields leave the bounded Redis projection.

CREATE TABLE game_session_canonical_issuer_partition_mutation_head (
    issuer_id character varying(64) NOT NULL,
    partition_id numeric NOT NULL,
    bootstrap_operation_id uuid NOT NULL,
    initial_revision uuid NOT NULL,
    current_revision uuid NOT NULL,
    state character varying(32) NOT NULL,
    pending_mutation_id uuid,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_gs_issuer_partition_mutation_head PRIMARY KEY (issuer_id, partition_id),
    CONSTRAINT uq_gs_issuer_partition_mutation_head_initial UNIQUE (initial_revision),
    CONSTRAINT fk_gs_issuer_partition_mutation_head_bootstrap
        FOREIGN KEY (bootstrap_operation_id)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent (operation_id),
    CONSTRAINT chk_gs_issuer_partition_mutation_head CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND partition_id >= 0
        AND bootstrap_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND initial_revision <> '00000000-0000-0000-0000-000000000000'::uuid
        AND current_revision <> '00000000-0000-0000-0000-000000000000'::uuid
        AND state IN ('INITIAL_INSTALL_PENDING', 'CURRENT')
        AND ((state = 'INITIAL_INSTALL_PENDING'
                AND current_revision = initial_revision
                AND pending_mutation_id IS NULL)
            OR state = 'CURRENT')
        AND (pending_mutation_id IS NULL
            OR pending_mutation_id <> '00000000-0000-0000-0000-000000000000'::uuid)
    )
);

-- Preserve the already-retained V1033 audit rows as legacy proof. New proof rows bind the exact
-- initial physical revision installed in Redis for this bootstrap partition.
ALTER TABLE game_session_canonical_issuer_bootstrap_partition_proof
    ADD COLUMN initial_physical_mutation_revision uuid;

ALTER TABLE game_session_canonical_issuer_bootstrap_partition_proof
    DROP CONSTRAINT chk_gs_issuer_bootstrap_partition_proof;

ALTER TABLE game_session_canonical_issuer_bootstrap_partition_proof
    ADD CONSTRAINT chk_gs_issuer_bootstrap_partition_proof CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND bootstrap_operation_fence > 0
        AND bootstrap_coverage_fence > 0
        AND bootstrap_operation_fence <> bootstrap_coverage_fence
        AND issuer_inventory_snapshot_revision > 0
        AND layout_version > 0
        AND partition_count > 0
        AND partition_capacity > 0
        AND mapping_identity = 'bindingRef/v1;sha256-u256-mod/v1'
        AND octet_length(layout_descriptor_bytes) > 0
        AND layout_descriptor_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(source_checkpoint_bytes) > 0
        AND source_checkpoint_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((source_event_bytes IS NULL AND source_event_digest IS NULL)
            OR (source_event_bytes IS NOT NULL
                AND octet_length(source_event_bytes) > 0
                AND source_event_digest IS NOT NULL
                AND source_event_digest ~ '^sha256:[0-9a-f]{64}$'))
        AND octet_length(incarnation_bytes) > 0
        AND incarnation_digest ~ '^sha256:[0-9a-f]{64}$'
        AND partition_id >= 0
        AND partition_id < partition_count
        AND partition_id = floor(partition_id)
        AND octet_length(exact_metadata_bytes) BETWEEN 1 AND 65536
        AND octet_length(exact_physical_field_set_bytes) BETWEEN 1 AND 17825792
        AND exact_physical_field_set_digest ~ '^sha256:[0-9a-f]{64}$'
        AND physical_field_count > 0
        AND physical_reservation_count >= 0
        AND physical_binding_count >= 0
        AND physical_counted_capacity_count >= 0
        AND physical_counted_capacity_count <= partition_capacity
        AND ((initial_physical_mutation_revision IS NULL
                AND physical_field_count = 1 + physical_reservation_count + physical_binding_count)
            OR (initial_physical_mutation_revision IS NOT NULL
                AND initial_physical_mutation_revision <> '00000000-0000-0000-0000-000000000000'::uuid
                AND physical_field_count = 2 + physical_reservation_count + physical_binding_count))
        AND planned_aggregate_capacity_count >= 0
        AND planned_aggregate_capacity_count <= partition_count * partition_capacity
        AND proof_state = 'EXACT_PHYSICAL_READBACK'
    );

CREATE TABLE game_session_canonical_issuer_partition_mutation (
    mutation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    partition_id numeric NOT NULL,
    mutation_kind character varying(16) NOT NULL,
    owner_operation_id uuid NOT NULL,
    expected_revision uuid NOT NULL,
    successor_revision uuid NOT NULL,
    exact_intended_change_bytes bytea NOT NULL,
    exact_intended_change_digest character varying(71) NOT NULL,
    status character varying(32) NOT NULL,
    exact_readback_field_set_bytes bytea,
    exact_readback_field_set_digest character varying(71),
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamp with time zone,

    CONSTRAINT pk_gs_issuer_partition_mutation PRIMARY KEY (mutation_id),
    CONSTRAINT uq_gs_issuer_partition_mutation_operation
        UNIQUE (issuer_id, partition_id, mutation_kind, owner_operation_id),
    CONSTRAINT uq_gs_issuer_partition_mutation_successor UNIQUE (successor_revision),
    CONSTRAINT uq_gs_issuer_partition_mutation_identity
        UNIQUE (issuer_id, partition_id, mutation_id),
    CONSTRAINT uq_gs_issuer_partition_mutation_expected
        UNIQUE (issuer_id, partition_id, expected_revision),
    CONSTRAINT fk_gs_issuer_partition_mutation_head
        FOREIGN KEY (issuer_id, partition_id)
        REFERENCES game_session_canonical_issuer_partition_mutation_head (issuer_id, partition_id),
    CONSTRAINT chk_gs_issuer_partition_mutation CHECK (
        mutation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND partition_id >= 0
        AND mutation_kind IN ('ADD_CAPACITY', 'ADD_BINDING', 'RELEASE', 'RETIRE')
        AND owner_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND expected_revision <> '00000000-0000-0000-0000-000000000000'::uuid
        AND successor_revision <> '00000000-0000-0000-0000-000000000000'::uuid
        AND successor_revision <> expected_revision
        AND octet_length(exact_intended_change_bytes) BETWEEN 1 AND 262144
        AND exact_intended_change_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((status = 'PENDING_PHYSICAL_PROOF'
                AND exact_readback_field_set_bytes IS NULL
                AND exact_readback_field_set_digest IS NULL
                AND completed_at IS NULL)
            OR (status = 'COMMITTED'
                AND exact_readback_field_set_bytes IS NOT NULL
                AND octet_length(exact_readback_field_set_bytes) BETWEEN 1 AND 17825792
                AND exact_readback_field_set_digest ~ '^sha256:[0-9a-f]{64}$'
                AND completed_at IS NOT NULL))
    )
);

CREATE INDEX ix_gs_issuer_partition_mutation_revision_expected
    ON game_session_canonical_issuer_partition_mutation (expected_revision);

ALTER TABLE game_session_canonical_issuer_partition_mutation_head
    ADD CONSTRAINT fk_gs_issuer_partition_mutation_head_pending
    FOREIGN KEY (issuer_id, partition_id, pending_mutation_id)
    REFERENCES game_session_canonical_issuer_partition_mutation
        (issuer_id, partition_id, mutation_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE game_session_canonical_issuer_binding_local_retirement (
    retirement_operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    partition_id numeric NOT NULL,
    release_operation_id uuid NOT NULL,
    physical_mutation_id uuid NOT NULL,
    original_local_operation_id uuid NOT NULL,
    transition_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    binding_ref bytea NOT NULL,
    original_reservation_fence uuid NOT NULL,
    original_issuer_coverage_operation_id uuid NOT NULL,
    original_issuer_coverage_operation_fence numeric NOT NULL,
    original_coverage_fence numeric NOT NULL,
    original_inventory_snapshot_revision numeric NOT NULL,
    original_reservation_inventory_revision numeric NOT NULL,
    anchor_issuer_coverage_operation_id uuid NOT NULL,
    anchor_operation_fence numeric NOT NULL,
    anchor_coverage_fence numeric NOT NULL,
    anchor_inventory_snapshot_revision numeric NOT NULL,
    release_source_inventory_revision numeric NOT NULL,
    release_completion_inventory_revision numeric NOT NULL,
    original_member_value_bytes bytea NOT NULL,
    original_member_value_digest character varying(71) NOT NULL,
    released_member_value_bytes bytea NOT NULL,
    released_member_value_digest character varying(71) NOT NULL,
    expected_revision uuid NOT NULL,
    successor_revision uuid NOT NULL,
    exact_intended_change_bytes bytea NOT NULL,
    exact_intended_change_digest character varying(71) NOT NULL,
    status character varying(32) NOT NULL,
    exact_absence_readback_field_set_bytes bytea,
    exact_absence_readback_field_set_digest character varying(71),
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamp with time zone,

    CONSTRAINT pk_gs_issuer_binding_local_retirement PRIMARY KEY (retirement_operation_id),
    CONSTRAINT uq_gs_issuer_binding_local_retirement_release UNIQUE (release_operation_id),
    CONSTRAINT uq_gs_issuer_binding_local_retirement_successor UNIQUE (successor_revision),
    CONSTRAINT fk_gs_issuer_binding_local_retirement_release
        FOREIGN KEY (release_operation_id)
        REFERENCES game_session_canonical_issuer_binding_local_release_operation (operation_id),
    CONSTRAINT fk_gs_issuer_binding_local_retirement_original
        FOREIGN KEY (original_local_operation_id)
        REFERENCES game_session_canonical_issuer_binding_local_operation (operation_id),
    CONSTRAINT fk_gs_issuer_binding_local_retirement_mutation
        FOREIGN KEY (issuer_id, partition_id, physical_mutation_id)
        REFERENCES game_session_canonical_issuer_partition_mutation
            (issuer_id, partition_id, mutation_id),
    CONSTRAINT chk_gs_issuer_binding_local_retirement CHECK (
        retirement_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND partition_id >= 0
        AND release_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_local_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND octet_length(binding_ref) BETWEEN 1 AND 6144
        AND original_reservation_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_issuer_coverage_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_issuer_coverage_operation_fence > 0
        AND original_coverage_fence > 0
        AND original_inventory_snapshot_revision > 0
        AND original_reservation_inventory_revision > 0
        AND anchor_issuer_coverage_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND anchor_operation_fence > 0
        AND anchor_coverage_fence > 0
        AND anchor_inventory_snapshot_revision > 0
        AND release_source_inventory_revision > 0
        AND release_completion_inventory_revision > release_source_inventory_revision
        AND octet_length(original_member_value_bytes) BETWEEN 1 AND 65536
        AND original_member_value_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(released_member_value_bytes) BETWEEN 1 AND 65536
        AND released_member_value_digest ~ '^sha256:[0-9a-f]{64}$'
        AND expected_revision <> '00000000-0000-0000-0000-000000000000'::uuid
        AND successor_revision <> '00000000-0000-0000-0000-000000000000'::uuid
        AND successor_revision <> expected_revision
        AND octet_length(exact_intended_change_bytes) BETWEEN 1 AND 262144
        AND exact_intended_change_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((status = 'PENDING_PHYSICAL_PROOF'
                AND exact_absence_readback_field_set_bytes IS NULL
                AND exact_absence_readback_field_set_digest IS NULL
                AND completed_at IS NULL)
            OR (status = 'RETIRED'
                AND exact_absence_readback_field_set_bytes IS NOT NULL
                AND octet_length(exact_absence_readback_field_set_bytes) BETWEEN 1 AND 17825792
                AND exact_absence_readback_field_set_digest ~ '^sha256:[0-9a-f]{64}$'
                AND completed_at IS NOT NULL))
    )
);

CREATE INDEX ix_gs_issuer_partition_mutation_pending
    ON game_session_canonical_issuer_partition_mutation (issuer_id, partition_id, status);

CREATE INDEX ix_gs_issuer_local_retirement_status
    ON game_session_canonical_issuer_binding_local_retirement (issuer_id, status, partition_id);

-- The intended mutation and its identity are immutable. Only a pending proof may acquire one exact
-- independently read complete field-set receipt; a committed physical mutation never changes.
-- [jooq ignore start]
CREATE FUNCTION guard_canonical_issuer_partition_mutation_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING_PHYSICAL_PROOF'
           OR NOT EXISTS (
                SELECT 1 FROM game_session_canonical_issuer_partition_mutation_head head
                 WHERE head.issuer_id = NEW.issuer_id
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

CREATE TRIGGER trg_gs_issuer_partition_mutation_history
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_issuer_partition_mutation
    FOR EACH ROW EXECUTE FUNCTION guard_canonical_issuer_partition_mutation_history();

CREATE FUNCTION guard_canonical_issuer_partition_revision_uniqueness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_partition_mutation_head head
         WHERE head.initial_revision = NEW.successor_revision
            OR (head.initial_revision = NEW.expected_revision
                AND (head.issuer_id, head.partition_id) <> (NEW.issuer_id, NEW.partition_id)))
       OR EXISTS (
        SELECT 1 FROM game_session_canonical_issuer_partition_mutation mutation
         WHERE mutation.expected_revision = NEW.successor_revision
            OR mutation.successor_revision = NEW.successor_revision
            OR ((mutation.expected_revision = NEW.expected_revision
                    OR mutation.successor_revision = NEW.expected_revision)
                AND (mutation.issuer_id, mutation.partition_id) <> (NEW.issuer_id, NEW.partition_id))) THEN
        RAISE EXCEPTION 'Issuer partition physical revisions are never reused';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_gs_issuer_partition_revision_uniqueness
    BEFORE INSERT ON game_session_canonical_issuer_partition_mutation
    FOR EACH ROW EXECUTE FUNCTION guard_canonical_issuer_partition_revision_uniqueness();

CREATE FUNCTION guard_canonical_issuer_partition_initial_revision_uniqueness()
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
           AND (head.issuer_id, head.partition_id) <> (NEW.issuer_id, NEW.partition_id)) THEN
        RAISE EXCEPTION 'Issuer partition initial physical revision is reused';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_gs_issuer_partition_initial_revision_uniqueness
    BEFORE INSERT ON game_session_canonical_issuer_partition_mutation_head
    FOR EACH ROW EXECUTE FUNCTION guard_canonical_issuer_partition_initial_revision_uniqueness();

CREATE FUNCTION guard_canonical_issuer_partition_mutation_head()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'INITIAL_INSTALL_PENDING'
           OR NEW.current_revision <> NEW.initial_revision
           OR NEW.pending_mutation_id IS NOT NULL
           OR EXISTS (
                SELECT 1 FROM game_session_canonical_issuer_bootstrap_partition_proof proof
                 WHERE proof.bootstrap_operation_id = NEW.bootstrap_operation_id
                   AND proof.partition_id = NEW.partition_id)
           OR EXISTS (
                SELECT 1 FROM game_session_canonical_issuer_installed_anchor anchor
                 WHERE anchor.issuer_id = NEW.issuer_id
                   AND anchor.bootstrap_operation_id = NEW.bootstrap_operation_id) THEN
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

CREATE TRIGGER trg_gs_issuer_partition_mutation_head
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_issuer_partition_mutation_head
    FOR EACH ROW EXECUTE FUNCTION guard_canonical_issuer_partition_mutation_head();

CREATE FUNCTION guard_canonical_issuer_bootstrap_partition_proof_initial_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.initial_physical_mutation_revision IS NULL
       OR NEW.initial_physical_mutation_revision = '00000000-0000-0000-0000-000000000000'::uuid
       OR NOT EXISTS (
            SELECT 1 FROM game_session_canonical_issuer_partition_mutation_head head
             WHERE head.issuer_id = NEW.issuer_id
               AND head.partition_id = NEW.partition_id
               AND head.bootstrap_operation_id = NEW.bootstrap_operation_id
               AND head.initial_revision = NEW.initial_physical_mutation_revision) THEN
        RAISE EXCEPTION 'New issuer partition proof requires its exact SQL-installed initial revision'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_gs_issuer_bootstrap_partition_proof_initial_revision
    BEFORE INSERT ON game_session_canonical_issuer_bootstrap_partition_proof
    FOR EACH ROW EXECUTE FUNCTION guard_canonical_issuer_bootstrap_partition_proof_initial_revision();

CREATE FUNCTION guard_canonical_issuer_binding_local_retirement()
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

CREATE TRIGGER trg_gs_issuer_binding_local_retirement
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_issuer_binding_local_retirement
    FOR EACH ROW EXECUTE FUNCTION guard_canonical_issuer_binding_local_retirement();
-- Retained initial revision, mutation and retirement receipts cannot be bypassed by TRUNCATE.
CREATE FUNCTION reject_canonical_issuer_physical_history_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Issuer physical mutation and retirement history is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_gs_issuer_partition_mutation_head_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_partition_mutation_head
    FOR EACH STATEMENT EXECUTE FUNCTION reject_canonical_issuer_physical_history_truncate();

CREATE TRIGGER trg_gs_issuer_partition_mutation_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_partition_mutation
    FOR EACH STATEMENT EXECUTE FUNCTION reject_canonical_issuer_physical_history_truncate();

CREATE TRIGGER trg_gs_issuer_local_retirement_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_binding_local_retirement
    FOR EACH STATEMENT EXECUTE FUNCTION reject_canonical_issuer_physical_history_truncate();
-- [jooq ignore stop]
