-- Immutable preparation records for a local initial issuer partition build. These records link to
-- the exact pending V1030 bootstrap intent and its complete frozen cohort. They do not publish a
-- layout pointer, install an anchor, acknowledge coverage, or authorize admission.
CREATE TABLE game_session_canonical_issuer_partition_build_plan (
    build_operation_id uuid NOT NULL,
    build_preparation_fence_id uuid NOT NULL,
    bootstrap_operation_id uuid NOT NULL,
    bootstrap_request_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    control_epoch numeric NOT NULL,
    bootstrap_operation_fence numeric NOT NULL,
    bootstrap_coverage_fence numeric NOT NULL,
    issuer_inventory_snapshot_revision numeric NOT NULL,
    layout_version numeric NOT NULL,
    partition_count numeric NOT NULL,
    partition_capacity numeric NOT NULL,
    mapping_identity character varying(96) NOT NULL,
    layout_descriptor_bytes bytea NOT NULL,
    layout_descriptor_digest character varying(71) NOT NULL,
    cohort_row_count bigint NOT NULL,
    cohort_digest character varying(71) NOT NULL,
    member_work_count bigint NOT NULL,
    blocker_count bigint NOT NULL,
    plan_state character varying(16) NOT NULL,
    coverage_generation_state character varying(24) NOT NULL,
    coverage_generation numeric,
    plan_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_partition_build_plan PRIMARY KEY (build_operation_id),
    CONSTRAINT uq_gs_issuer_partition_build_plan_bootstrap UNIQUE (bootstrap_operation_id),
    CONSTRAINT uq_gs_issuer_partition_build_plan_anchor_link UNIQUE (
        build_operation_id, bootstrap_operation_id, issuer_id,
        bootstrap_operation_fence, bootstrap_coverage_fence,
        issuer_inventory_snapshot_revision),
    CONSTRAINT fk_gs_issuer_partition_build_plan_intent FOREIGN KEY (
        bootstrap_operation_id, issuer_id)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent
            (operation_id, issuer_id),
    CONSTRAINT chk_gs_issuer_partition_build_plan CHECK (
        build_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND build_preparation_fence_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND bootstrap_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND bootstrap_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND build_operation_id <> bootstrap_operation_id
        AND build_operation_id <> bootstrap_request_id
        AND build_operation_id <> build_preparation_fence_id
        AND build_preparation_fence_id <> bootstrap_operation_id
        AND build_preparation_fence_id <> bootstrap_request_id
        AND issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND control_epoch > 0
        AND bootstrap_operation_fence = control_epoch
        AND bootstrap_coverage_fence > 0
        AND bootstrap_coverage_fence <> bootstrap_operation_fence
        AND issuer_inventory_snapshot_revision > 0
        AND layout_version > 0
        AND partition_count > 0
        AND partition_capacity > 0
        AND mapping_identity = 'bindingRef/v1;sha256-u256-mod/v1'
        AND octet_length(layout_descriptor_bytes) > 0
        AND layout_descriptor_digest ~ '^sha256:[0-9a-f]{64}$'
        AND cohort_row_count >= 0
        AND cohort_digest ~ '^sha256:[0-9a-f]{64}$'
        AND member_work_count >= 0
        AND blocker_count >= 0
        AND ((plan_state = 'PREPARED' AND blocker_count = 0)
            OR (plan_state = 'BLOCKED' AND blocker_count > 0))
        AND coverage_generation_state = 'NOT_YET_ISSUED'
        AND coverage_generation IS NULL
        AND plan_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE TABLE game_session_canonical_issuer_partition_build_cohort_row (
    build_operation_id uuid NOT NULL,
    source_operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    source_row_ordinal bigint NOT NULL,
    source_kind character varying(40) NOT NULL,
    source_key text NOT NULL,
    source_inventory_revision numeric NOT NULL,
    source_row_json text NOT NULL,
    source_row_digest character varying(71) NOT NULL,
    CONSTRAINT pk_gs_issuer_partition_build_cohort_row
        PRIMARY KEY (build_operation_id, source_row_ordinal),
    CONSTRAINT fk_gs_issuer_partition_build_cohort_plan FOREIGN KEY (build_operation_id)
        REFERENCES game_session_canonical_issuer_partition_build_plan (build_operation_id),
    CONSTRAINT fk_gs_issuer_partition_build_cohort_intent FOREIGN KEY (
        source_operation_id, issuer_id)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent
            (operation_id, issuer_id),
    CONSTRAINT chk_gs_issuer_partition_build_cohort_row CHECK (
        source_row_ordinal >= 0
        AND issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND source_kind IN (
            'BINDING', 'UNRESOLVED_LEGACY_BINDING', 'TRANSITION', 'RESERVATION',
            'UNRESOLVED_LEGACY_RESERVATION', 'ACCOUNT_OBLIGATION', 'ISSUER_OBLIGATION',
            'REGION_OBLIGATION')
        AND length(source_key) > 0
        AND source_inventory_revision > 0
        AND length(source_row_json) > 1
        AND source_row_digest ~ '^sha256:[0-9a-f]{64}$')
);

-- jOOQ's H2 DDL simulation cannot index PostgreSQL TEXT; retain this exact PostgreSQL key guard.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_issuer_partition_build_cohort_row
    ADD CONSTRAINT uq_gs_issuer_partition_build_cohort_source
        UNIQUE (build_operation_id, source_kind, source_key);
-- [jooq ignore stop]

CREATE TABLE game_session_canonical_issuer_partition_build_member_work (
    build_operation_id uuid NOT NULL,
    bootstrap_operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    work_ordinal bigint NOT NULL,
    member_operation_id uuid NOT NULL,
    member_preparation_fence_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    reservation_source_row_ordinal bigint NOT NULL,
    reservation_source_row_digest character varying(71) NOT NULL,
    binding_source_row_ordinal bigint,
    binding_source_row_digest character varying(71),
    binding_ref bytea NOT NULL,
    transition_id uuid NOT NULL,
    reservation_lifecycle character varying(24) NOT NULL,
    reservation_fence uuid NOT NULL,
    original_partition_id numeric NOT NULL,
    original_layout_version numeric NOT NULL,
    original_partition_count numeric NOT NULL,
    original_partition_capacity numeric NOT NULL,
    original_issuer_coverage_operation_id uuid NOT NULL,
    original_issuer_coverage_operation_fence numeric NOT NULL,
    original_coverage_fence numeric NOT NULL,
    original_inventory_snapshot_revision numeric NOT NULL,
    planned_partition_id numeric NOT NULL,
    planned_anchor_operation_id uuid NOT NULL,
    planned_anchor_operation_fence numeric NOT NULL,
    planned_anchor_coverage_fence numeric NOT NULL,
    planned_anchor_inventory_snapshot_revision numeric NOT NULL,
    counts_toward_capacity boolean NOT NULL,
    CONSTRAINT pk_gs_issuer_partition_build_member_work
        PRIMARY KEY (build_operation_id, work_ordinal),
    CONSTRAINT uq_gs_issuer_partition_build_member_operation
        UNIQUE (build_operation_id, member_operation_id),
    CONSTRAINT uq_gs_issuer_partition_build_member_fence
        UNIQUE (build_operation_id, member_preparation_fence_id),
    CONSTRAINT uq_gs_issuer_partition_build_member_reservation
        UNIQUE (build_operation_id, reservation_id),
    CONSTRAINT fk_gs_issuer_partition_build_member_plan FOREIGN KEY (build_operation_id)
        REFERENCES game_session_canonical_issuer_partition_build_plan (build_operation_id),
    CONSTRAINT fk_gs_issuer_partition_build_member_reservation_source FOREIGN KEY (
        build_operation_id, reservation_source_row_ordinal)
        REFERENCES game_session_canonical_issuer_partition_build_cohort_row
            (build_operation_id, source_row_ordinal),
    CONSTRAINT fk_gs_issuer_partition_build_member_binding_source FOREIGN KEY (
        build_operation_id, binding_source_row_ordinal)
        REFERENCES game_session_canonical_issuer_partition_build_cohort_row
            (build_operation_id, source_row_ordinal),
    CONSTRAINT fk_gs_issuer_partition_build_member_anchor_link FOREIGN KEY (
        build_operation_id, planned_anchor_operation_id, issuer_id,
        planned_anchor_operation_fence, planned_anchor_coverage_fence,
        planned_anchor_inventory_snapshot_revision)
        REFERENCES game_session_canonical_issuer_partition_build_plan (
            build_operation_id, bootstrap_operation_id, issuer_id,
            bootstrap_operation_fence, bootstrap_coverage_fence,
            issuer_inventory_snapshot_revision),
    CONSTRAINT chk_gs_issuer_partition_build_member_work CHECK (
        work_ordinal >= 0
        AND member_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND member_preparation_fence_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND member_operation_id <> build_operation_id
        AND member_operation_id <> bootstrap_operation_id
        AND member_operation_id <> member_preparation_fence_id
        AND member_preparation_fence_id <> build_operation_id
        AND member_preparation_fence_id <> bootstrap_operation_id
        AND (binding_source_row_ordinal IS NULL) = (binding_source_row_digest IS NULL)
        AND octet_length(binding_ref) > 0
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND reservation_lifecycle IN ('RESERVED', 'BOUND', 'RELEASE_PENDING', 'RELEASED')
        AND reservation_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_partition_id >= 0
        AND original_layout_version > 0
        AND original_partition_count > 0
        AND original_partition_id < original_partition_count
        AND original_partition_capacity > 0
        AND original_issuer_coverage_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_issuer_coverage_operation_fence > 0
        AND original_coverage_fence > 0
        AND original_inventory_snapshot_revision > 0
        AND planned_partition_id >= 0
        AND planned_anchor_operation_id = bootstrap_operation_id
        AND planned_anchor_operation_fence > 0
        AND planned_anchor_coverage_fence > 0
        AND planned_anchor_inventory_snapshot_revision > 0
        AND counts_toward_capacity = (reservation_lifecycle <> 'RELEASED')
        AND reservation_source_row_digest ~ '^sha256:[0-9a-f]{64}$'
        AND (binding_source_row_digest IS NULL
            OR binding_source_row_digest ~ '^sha256:[0-9a-f]{64}$'))
);

CREATE TABLE game_session_canonical_issuer_partition_build_blocker (
    build_operation_id uuid NOT NULL,
    blocker_ordinal bigint NOT NULL,
    source_row_ordinal bigint NOT NULL,
    source_row_digest character varying(71) NOT NULL,
    blocker_code character varying(48) NOT NULL,
    CONSTRAINT pk_gs_issuer_partition_build_blocker
        PRIMARY KEY (build_operation_id, blocker_ordinal),
    CONSTRAINT fk_gs_issuer_partition_build_blocker_plan FOREIGN KEY (build_operation_id)
        REFERENCES game_session_canonical_issuer_partition_build_plan (build_operation_id),
    CONSTRAINT fk_gs_issuer_partition_build_blocker_source FOREIGN KEY (
        build_operation_id, source_row_ordinal)
        REFERENCES game_session_canonical_issuer_partition_build_cohort_row
            (build_operation_id, source_row_ordinal),
    CONSTRAINT chk_gs_issuer_partition_build_blocker CHECK (
        blocker_ordinal >= 0
        AND source_row_digest ~ '^sha256:[0-9a-f]{64}$'
        AND blocker_code IN (
            'UNRESOLVED_LEGACY_BINDING', 'UNRESOLVED_LEGACY_RESERVATION',
            'UNREPRESENTABLE_BINDING', 'UNREPRESENTABLE_RESERVATION', 'UNREPRESENTABLE_SOURCE',
            'MISSING_RESERVATION', 'CONFLICTING_RESERVATION_LINK',
            'PARTITION_CAPACITY_EXCEEDED', 'AGGREGATE_CAPACITY_EXCEEDED'))
);

-- jOOQ's H2 DDL parser cannot represent PostgreSQL PL/pgSQL trigger functions. Keep the
-- immutable guards in the real migration; the focused PostgreSQL integration proof executes them.
-- [jooq ignore start]
CREATE FUNCTION game_session_reject_issuer_partition_build_artifact_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'issuer partition build preparation artifacts are immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_gs_issuer_partition_build_plan_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_partition_build_plan
    FOR EACH ROW EXECUTE FUNCTION game_session_reject_issuer_partition_build_artifact_change();

CREATE TRIGGER trg_gs_issuer_partition_build_cohort_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_partition_build_cohort_row
    FOR EACH ROW EXECUTE FUNCTION game_session_reject_issuer_partition_build_artifact_change();

CREATE TRIGGER trg_gs_issuer_partition_build_work_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_partition_build_member_work
    FOR EACH ROW EXECUTE FUNCTION game_session_reject_issuer_partition_build_artifact_change();

CREATE TRIGGER trg_gs_issuer_partition_build_blocker_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_partition_build_blocker
    FOR EACH ROW EXECUTE FUNCTION game_session_reject_issuer_partition_build_artifact_change();
CREATE TRIGGER trg_gs_issuer_partition_build_plan_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_partition_build_plan
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_partition_build_artifact_change();
CREATE TRIGGER trg_gs_issuer_partition_build_cohort_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_partition_build_cohort_row
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_partition_build_artifact_change();
CREATE TRIGGER trg_gs_issuer_partition_build_work_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_partition_build_member_work
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_partition_build_artifact_change();
CREATE TRIGGER trg_gs_issuer_partition_build_blocker_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_partition_build_blocker
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_partition_build_artifact_change();
-- [jooq ignore stop]
