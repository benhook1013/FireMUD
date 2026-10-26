-- These records qualify one local initial issuer-layout installation only. They do not install
-- an issuer tenant barrier, open it, or authorize a binding or admission.
CREATE TABLE game_session_canonical_issuer_bootstrap_partition_proof (
    build_operation_id uuid NOT NULL,
    bootstrap_operation_id uuid NOT NULL,
    bootstrap_request_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    build_plan_digest character varying(71) NOT NULL,
    bootstrap_operation_fence numeric NOT NULL,
    bootstrap_coverage_fence numeric NOT NULL,
    issuer_inventory_snapshot_revision numeric NOT NULL,
    layout_version numeric NOT NULL,
    partition_count numeric NOT NULL,
    partition_capacity numeric NOT NULL,
    mapping_identity character varying(96) NOT NULL,
    layout_descriptor_bytes bytea NOT NULL,
    layout_descriptor_digest character varying(71) NOT NULL,
    source_checkpoint_bytes bytea NOT NULL,
    source_checkpoint_digest character varying(71) NOT NULL,
    source_event_bytes bytea,
    source_event_digest character varying(71),
    incarnation_bytes bytea NOT NULL,
    incarnation_digest character varying(71) NOT NULL,
    partition_id numeric NOT NULL,
    exact_metadata_bytes bytea NOT NULL,
    exact_physical_field_set_bytes bytea NOT NULL,
    exact_physical_field_set_digest character varying(71) NOT NULL,
    physical_field_count integer NOT NULL,
    physical_reservation_count integer NOT NULL,
    physical_binding_count integer NOT NULL,
    physical_counted_capacity_count integer NOT NULL,
    planned_aggregate_capacity_count numeric NOT NULL,
    proof_state character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_bootstrap_partition_proof
        PRIMARY KEY (bootstrap_operation_id, partition_id),
    CONSTRAINT fk_gs_issuer_bootstrap_partition_proof_plan FOREIGN KEY (
        build_operation_id, bootstrap_operation_id, bootstrap_request_id, issuer_id,
        build_plan_digest)
        REFERENCES game_session_canonical_issuer_partition_build_plan (
            build_operation_id, bootstrap_operation_id, bootstrap_request_id, issuer_id, plan_digest),
    CONSTRAINT chk_gs_issuer_bootstrap_partition_proof CHECK (
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
        AND physical_field_count = 1 + physical_reservation_count + physical_binding_count
        AND planned_aggregate_capacity_count >= 0
        AND planned_aggregate_capacity_count <= partition_count * partition_capacity
        AND proof_state = 'EXACT_PHYSICAL_READBACK')
);

CREATE TABLE game_session_canonical_issuer_bootstrap_coverage_counter (
    issuer_id character varying(64) NOT NULL,
    last_issued_coverage_generation numeric NOT NULL,
    counter_version bigint NOT NULL,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_bootstrap_coverage_counter PRIMARY KEY (issuer_id),
    CONSTRAINT uq_gs_issuer_bootstrap_coverage_counter_value
        UNIQUE (issuer_id, last_issued_coverage_generation),
    CONSTRAINT chk_gs_issuer_bootstrap_coverage_counter CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND last_issued_coverage_generation >= 0
        AND last_issued_coverage_generation = floor(last_issued_coverage_generation)
        AND length(CAST(last_issued_coverage_generation AS varchar)) <= 256
        AND counter_version >= 0
        AND ((counter_version = 0 AND last_issued_coverage_generation = 0)
            OR (counter_version > 0 AND last_issued_coverage_generation > 0)))
);

CREATE TABLE game_session_canonical_issuer_installed_anchor (
    issuer_id character varying(64) NOT NULL,
    bootstrap_operation_id uuid NOT NULL,
    bootstrap_request_id uuid NOT NULL,
    build_operation_id uuid NOT NULL,
    build_plan_digest character varying(71) NOT NULL,
    control_epoch numeric NOT NULL,
    issuer_coverage_operation_id uuid NOT NULL,
    operation_fence numeric NOT NULL,
    coverage_fence numeric NOT NULL,
    issuer_inventory_snapshot_revision numeric NOT NULL,
    layout_version numeric NOT NULL,
    partition_count numeric NOT NULL,
    partition_capacity numeric NOT NULL,
    mapping_identity character varying(96) NOT NULL,
    layout_descriptor_bytes bytea NOT NULL,
    layout_descriptor_digest character varying(71) NOT NULL,
    issuer_auth_generation numeric NOT NULL,
    source_checkpoint_bytes bytea NOT NULL,
    source_checkpoint_digest character varying(71) NOT NULL,
    source_event_bytes bytea,
    source_event_digest character varying(71),
    incarnation_bytes bytea NOT NULL,
    incarnation_digest character varying(71) NOT NULL,
    global_coverage_generation numeric NOT NULL,
    partition_proof_count bigint NOT NULL,
    physical_reservation_count bigint NOT NULL,
    physical_binding_count bigint NOT NULL,
    aggregate_counted_capacity numeric NOT NULL,
    partition_proof_root_digest character varying(71) NOT NULL,
    exact_anchor_bytes bytea NOT NULL,
    exact_anchor_digest character varying(71) NOT NULL,
    anchor_state character varying(24) NOT NULL,
    installed_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_installed_anchor PRIMARY KEY (issuer_id),
    CONSTRAINT uq_gs_issuer_installed_anchor_operation UNIQUE (bootstrap_operation_id),
    CONSTRAINT uq_gs_issuer_installed_anchor_generation
        UNIQUE (issuer_id, global_coverage_generation),
    CONSTRAINT fk_gs_issuer_installed_anchor_intent FOREIGN KEY (
        bootstrap_operation_id, issuer_id, control_epoch, operation_fence, coverage_fence)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent (
            operation_id, issuer_id, control_epoch, operation_fence, coverage_fence),
    CONSTRAINT fk_gs_issuer_installed_anchor_request FOREIGN KEY (
        issuer_id, bootstrap_request_id)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent (issuer_id, request_id),
    CONSTRAINT fk_gs_issuer_installed_anchor_plan FOREIGN KEY (
        build_operation_id, bootstrap_operation_id, bootstrap_request_id, issuer_id,
        build_plan_digest)
        REFERENCES game_session_canonical_issuer_partition_build_plan (
            build_operation_id, bootstrap_operation_id, bootstrap_request_id, issuer_id, plan_digest),
    CONSTRAINT fk_gs_issuer_installed_anchor_counter FOREIGN KEY (issuer_id)
        REFERENCES game_session_canonical_issuer_bootstrap_coverage_counter (issuer_id),
    CONSTRAINT chk_gs_issuer_installed_anchor CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND issuer_coverage_operation_id = bootstrap_operation_id
        AND control_epoch > 0
        AND operation_fence = control_epoch
        AND operation_fence > 0
        AND coverage_fence > 0
        AND coverage_fence <> operation_fence
        AND issuer_inventory_snapshot_revision > 0
        AND layout_version > 0
        AND partition_count > 0
        AND partition_capacity > 0
        AND mapping_identity = 'bindingRef/v1;sha256-u256-mod/v1'
        AND octet_length(layout_descriptor_bytes) > 0
        AND layout_descriptor_digest ~ '^sha256:[0-9a-f]{64}$'
        AND issuer_auth_generation > 0
        AND octet_length(source_checkpoint_bytes) > 0
        AND source_checkpoint_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((source_event_bytes IS NULL AND source_event_digest IS NULL)
            OR (source_event_bytes IS NOT NULL
                AND octet_length(source_event_bytes) > 0
                AND source_event_digest IS NOT NULL
                AND source_event_digest ~ '^sha256:[0-9a-f]{64}$'))
        AND octet_length(incarnation_bytes) > 0
        AND incarnation_digest ~ '^sha256:[0-9a-f]{64}$'
        AND global_coverage_generation > 0
        AND partition_proof_count = partition_count
        AND physical_reservation_count >= 0
        AND physical_binding_count >= 0
        AND aggregate_counted_capacity >= 0
        AND aggregate_counted_capacity <= partition_count * partition_capacity
        AND partition_proof_root_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(exact_anchor_bytes) BETWEEN 1 AND 262144
        AND exact_anchor_digest ~ '^sha256:[0-9a-f]{64}$'
        AND anchor_state = 'INITIAL_INSTALLED')
);

-- The proof, counter, and initial pointer are append-only. Counter advancement is the only
-- mutable write and is a same-issuer monotonic compare-and-set in the installation transaction.
-- [jooq ignore start]
CREATE FUNCTION game_session_reject_issuer_bootstrap_coverage_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'issuer bootstrap coverage artifacts are immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_gs_issuer_bootstrap_partition_proof_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_bootstrap_partition_proof
    FOR EACH ROW EXECUTE FUNCTION game_session_reject_issuer_bootstrap_coverage_change();
CREATE TRIGGER trg_gs_issuer_bootstrap_partition_proof_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_bootstrap_partition_proof
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_bootstrap_coverage_change();
CREATE TRIGGER trg_gs_issuer_installed_anchor_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_installed_anchor
    FOR EACH ROW EXECUTE FUNCTION game_session_reject_issuer_bootstrap_coverage_change();
CREATE TRIGGER trg_gs_issuer_installed_anchor_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_installed_anchor
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_bootstrap_coverage_change();
CREATE TRIGGER trg_gs_issuer_bootstrap_coverage_counter_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_bootstrap_coverage_counter
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_bootstrap_coverage_change();

CREATE FUNCTION game_session_guard_issuer_bootstrap_coverage_counter()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'issuer bootstrap coverage counter cannot be deleted'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.issuer_id IS DISTINCT FROM OLD.issuer_id
        OR NEW.counter_version <> OLD.counter_version + 1
        OR NEW.last_issued_coverage_generation <= OLD.last_issued_coverage_generation THEN
        RAISE EXCEPTION 'issuer bootstrap coverage counter CAS must advance its global domain'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION game_session_guard_issuer_bootstrap_coverage_counter_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.counter_version <> 0 OR NEW.last_issued_coverage_generation <> 0 THEN
        RAISE EXCEPTION 'issuer bootstrap coverage counter must start at its unissued zero state'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_gs_issuer_bootstrap_coverage_counter_initial
    BEFORE INSERT ON game_session_canonical_issuer_bootstrap_coverage_counter
    FOR EACH ROW EXECUTE FUNCTION game_session_guard_issuer_bootstrap_coverage_counter_insert();
CREATE TRIGGER trg_gs_issuer_bootstrap_coverage_counter_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_bootstrap_coverage_counter
    FOR EACH ROW EXECUTE FUNCTION game_session_guard_issuer_bootstrap_coverage_counter();
-- [jooq ignore stop]

COMMENT ON TABLE game_session_canonical_issuer_bootstrap_partition_proof IS
    'Exact per-partition physical readback proof for local initial issuer bootstrap; not tenant barrier state or admission authority.';
COMMENT ON TABLE game_session_canonical_issuer_installed_anchor IS
    'Unique local initial installed issuer coverage/layout anchor; distinct from issuer tenant-barrier INSTALLED or OPEN.';
