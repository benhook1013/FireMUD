-- This receipt is separate from the immutable SQL preparation and physical schema-v2 member.
-- It acknowledges one original binding-local member/reservation readback only; it does not
-- publish issuer-wide coverage, initialize a tenant barrier, or authorize admission.
CREATE TABLE game_session_canonical_issuer_binding_local_ack_gen_counter (
    issuer_id character varying(64) NOT NULL,
    last_issued_binding_local_acknowledgement_generation numeric NOT NULL,
    counter_version bigint NOT NULL,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_binding_local_ack_generation_counter PRIMARY KEY (issuer_id),
    CONSTRAINT chk_gs_issuer_binding_local_ack_generation_counter CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND last_issued_binding_local_acknowledgement_generation >= 0
        AND last_issued_binding_local_acknowledgement_generation = floor(last_issued_binding_local_acknowledgement_generation)
        AND length(CAST(last_issued_binding_local_acknowledgement_generation AS varchar)) <= 256
        AND counter_version >= 0
        AND ((counter_version = 0 AND last_issued_binding_local_acknowledgement_generation = 0)
            OR (counter_version > 0 AND last_issued_binding_local_acknowledgement_generation > 0)))
);

CREATE TABLE game_session_canonical_issuer_binding_local_acknowledgement (
    operation_id uuid NOT NULL,
    acknowledgement_generation numeric NOT NULL,
    issuer_id character varying(64) NOT NULL,
    request_id uuid NOT NULL,
    transition_id uuid NOT NULL,
    binding_ref bytea NOT NULL,
    binding_generation numeric NOT NULL,
    reservation_id uuid NOT NULL,
    reservation_fence uuid NOT NULL,
    original_issuer_coverage_operation_id uuid NOT NULL,
    original_issuer_coverage_operation_fence numeric NOT NULL,
    original_coverage_fence numeric NOT NULL,
    original_inventory_snapshot_revision numeric NOT NULL,
    original_inventory_revision numeric NOT NULL,
    anchor_bootstrap_operation_id uuid NOT NULL,
    anchor_issuer_coverage_operation_id uuid NOT NULL,
    anchor_operation_fence numeric NOT NULL,
    anchor_coverage_fence numeric NOT NULL,
    anchor_inventory_snapshot_revision numeric NOT NULL,
    anchor_global_coverage_generation numeric NOT NULL,
    issuer_control_epoch numeric NOT NULL,
    local_member_operation_id uuid NOT NULL,
    local_member_operation_fence numeric NOT NULL,
    local_member_generation numeric NOT NULL,
    physical_partition_id numeric NOT NULL,
    physical_field_set_digest character varying(71) NOT NULL,
    sql_currentness_digest character varying(71) NOT NULL,
    acknowledgement_schema_version smallint NOT NULL,
    acknowledgement_bytes bytea NOT NULL,
    acknowledgement_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_binding_local_ack PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_issuer_binding_local_ack_generation UNIQUE (issuer_id, acknowledgement_generation),
    CONSTRAINT fk_gs_issuer_binding_local_ack_operation
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_issuer_binding_local_operation (operation_id),
    CONSTRAINT uq_gs_issuer_binding_local_ack_transition UNIQUE (issuer_id, transition_id),
    CONSTRAINT uq_gs_issuer_binding_local_ack_reservation UNIQUE (reservation_id),
    CONSTRAINT chk_gs_issuer_binding_local_ack CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND acknowledgement_generation > 0
        AND acknowledgement_generation = floor(acknowledgement_generation)
        AND length(CAST(acknowledgement_generation AS varchar)) <= 256
        AND request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND request_id <> transition_id
        AND octet_length(binding_ref) > 0
        AND binding_generation > 0
        AND reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND reservation_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_issuer_coverage_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_issuer_coverage_operation_fence > 0
        AND original_coverage_fence > 0
        AND original_inventory_snapshot_revision > 0
        AND original_inventory_revision > 0
        AND anchor_bootstrap_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND anchor_issuer_coverage_operation_id = anchor_bootstrap_operation_id
        AND anchor_operation_fence > 0
        AND anchor_coverage_fence > 0
        AND anchor_operation_fence <> anchor_coverage_fence
        AND anchor_inventory_snapshot_revision > 0
        AND anchor_global_coverage_generation > 0
        AND issuer_control_epoch = anchor_operation_fence
        AND local_member_operation_id = operation_id
        AND local_member_operation_fence > 0
        AND local_member_generation > 0
        AND physical_partition_id >= 0
        AND physical_partition_id = floor(physical_partition_id)
        AND physical_field_set_digest ~ '^sha256:[0-9a-f]{64}$'
        AND sql_currentness_digest ~ '^sha256:[0-9a-f]{64}$'
        AND acknowledgement_schema_version = 1
        AND octet_length(acknowledgement_bytes) BETWEEN 1 AND 1048576
        AND acknowledgement_digest ~ '^sha256:[0-9a-f]{64}$')
);

-- [jooq ignore start]
CREATE FUNCTION game_session_validate_issuer_binding_local_acknowledgement()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    source_operation game_session_canonical_issuer_binding_local_operation%ROWTYPE;
    installed_anchor game_session_canonical_issuer_installed_anchor%ROWTYPE;
    generation_counter game_session_canonical_issuer_binding_local_ack_gen_counter%ROWTYPE;
BEGIN
    SELECT * INTO source_operation
      FROM game_session_canonical_issuer_binding_local_operation
     WHERE operation_id = NEW.operation_id
     FOR KEY SHARE;
    IF NOT FOUND
        OR source_operation.issuer_id <> NEW.issuer_id
        OR source_operation.request_id <> NEW.request_id
        OR source_operation.transition_id <> NEW.transition_id
        OR source_operation.binding_ref <> NEW.binding_ref
        OR source_operation.binding_generation <> NEW.binding_generation
        OR source_operation.reservation_id <> NEW.reservation_id
        OR source_operation.reservation_fence <> NEW.reservation_fence
        OR source_operation.original_issuer_coverage_operation_id <> NEW.original_issuer_coverage_operation_id
        OR source_operation.original_issuer_coverage_operation_fence <> NEW.original_issuer_coverage_operation_fence
        OR source_operation.original_coverage_fence <> NEW.original_coverage_fence
        OR source_operation.original_inventory_snapshot_revision <> NEW.original_inventory_snapshot_revision
        OR source_operation.local_inventory_snapshot_revision <> NEW.original_inventory_revision
        OR source_operation.anchor_bootstrap_operation_id <> NEW.anchor_bootstrap_operation_id
        OR source_operation.anchor_issuer_coverage_operation_id <> NEW.anchor_issuer_coverage_operation_id
        OR source_operation.anchor_operation_fence <> NEW.anchor_operation_fence
        OR source_operation.anchor_coverage_fence <> NEW.anchor_coverage_fence
        OR source_operation.anchor_issuer_inventory_snapshot_revision <> NEW.anchor_inventory_snapshot_revision
        OR source_operation.anchor_control_epoch <> NEW.issuer_control_epoch
        OR source_operation.operation_id <> NEW.local_member_operation_id
        OR source_operation.operation_fence <> NEW.local_member_operation_fence
        OR source_operation.local_member_generation <> NEW.local_member_generation
        OR source_operation.partition_id <> NEW.physical_partition_id
        OR source_operation.member_status <> 'PENDING_PHYSICAL_PROOF'
        OR source_operation.member_schema_version <> 2 THEN
        RAISE EXCEPTION 'issuer binding-local acknowledgement differs from its immutable original operation'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO installed_anchor
      FROM game_session_canonical_issuer_installed_anchor
     WHERE issuer_id = NEW.issuer_id
     FOR KEY SHARE;
    IF NOT FOUND
        OR installed_anchor.bootstrap_operation_id <> NEW.anchor_bootstrap_operation_id
        OR installed_anchor.global_coverage_generation <> NEW.anchor_global_coverage_generation
        OR installed_anchor.exact_anchor_bytes <> source_operation.exact_anchor_bytes THEN
        RAISE EXCEPTION 'issuer binding-local acknowledgement differs from the immutable installed anchor'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO generation_counter
      FROM game_session_canonical_issuer_binding_local_ack_gen_counter
     WHERE issuer_id = NEW.issuer_id
     FOR KEY SHARE;
    IF NOT FOUND
        OR generation_counter.last_issued_binding_local_acknowledgement_generation <> NEW.acknowledgement_generation THEN
        RAISE EXCEPTION 'issuer binding-local acknowledgement generation was not issued by its owner counter'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_gs_issuer_binding_local_ack_validate
    BEFORE INSERT ON game_session_canonical_issuer_binding_local_acknowledgement
    FOR EACH ROW EXECUTE FUNCTION game_session_validate_issuer_binding_local_acknowledgement();

CREATE FUNCTION game_session_reject_issuer_binding_local_acknowledgement_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'issuer binding-local acknowledgements are immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_gs_issuer_binding_local_ack_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_binding_local_acknowledgement
    FOR EACH ROW EXECUTE FUNCTION game_session_reject_issuer_binding_local_acknowledgement_change();
CREATE TRIGGER trg_gs_issuer_binding_local_ack_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_binding_local_acknowledgement
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_binding_local_acknowledgement_change();

CREATE FUNCTION game_session_reject_issuer_local_ack_gen_counter_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'issuer binding-local acknowledgement generation counter cannot be truncated'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE FUNCTION game_session_guard_issuer_local_ack_gen_counter()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'issuer binding-local acknowledgement generation counter cannot be deleted'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.issuer_id IS DISTINCT FROM OLD.issuer_id
        OR NEW.counter_version <> OLD.counter_version + 1
        OR NEW.last_issued_binding_local_acknowledgement_generation
            <> OLD.last_issued_binding_local_acknowledgement_generation + 1 THEN
        RAISE EXCEPTION 'issuer binding-local acknowledgement generation counter must advance its own domain by one'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION game_session_guard_issuer_local_ack_gen_counter_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.counter_version <> 0
        OR NEW.last_issued_binding_local_acknowledgement_generation <> 0 THEN
        RAISE EXCEPTION 'issuer binding-local acknowledgement generation counter must start unissued'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_gs_issuer_binding_local_ack_generation_counter_initial
    BEFORE INSERT ON game_session_canonical_issuer_binding_local_ack_gen_counter
    FOR EACH ROW EXECUTE FUNCTION game_session_guard_issuer_local_ack_gen_counter_insert();
CREATE TRIGGER trg_gs_issuer_binding_local_ack_generation_counter_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_binding_local_ack_gen_counter
    FOR EACH ROW EXECUTE FUNCTION game_session_guard_issuer_local_ack_gen_counter();
CREATE TRIGGER trg_gs_issuer_binding_local_ack_generation_counter_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_binding_local_ack_gen_counter
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_local_ack_gen_counter_change();
-- [jooq ignore stop]

COMMENT ON TABLE game_session_canonical_issuer_binding_local_acknowledgement IS
    'Immutable versioned exact local issuer binding/reservation readback receipt; not issuer-wide coverage or admission.';
COMMENT ON TABLE game_session_canonical_issuer_binding_local_ack_gen_counter IS
    'Independent owner-issued per-transition issuer binding-local acknowledgement generation; distinct from local member-operation and global issuer coverage generations.';
