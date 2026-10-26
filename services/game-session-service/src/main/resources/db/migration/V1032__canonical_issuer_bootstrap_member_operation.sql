-- A persisted local SQL-issued operation for one exact work item in the current complete
-- PREPARED initial-bootstrap plan. This is still pending physical proof: it installs no pointer,
-- records no Redis result, and issues no issuer-wide coverage acknowledgement.
-- The local numeric generation is an operation identity, not global coverage evidence.

ALTER TABLE game_session_canonical_issuer_partition_build_plan
    ADD CONSTRAINT uq_gs_issuer_partition_build_plan_digest_link
        UNIQUE (build_operation_id, bootstrap_operation_id, bootstrap_request_id, issuer_id, plan_digest);

ALTER TABLE game_session_canonical_issuer_layout_bootstrap_intent
    ADD CONSTRAINT uq_gs_issuer_bootstrap_intent_request_link
        UNIQUE (operation_id, issuer_id, request_id);

ALTER TABLE game_session_canonical_issuer_partition_build_cohort_row
    ADD CONSTRAINT uq_gs_issuer_partition_build_cohort_digest_link
        UNIQUE (build_operation_id, source_row_ordinal, source_row_digest);

ALTER TABLE game_session_canonical_issuer_partition_build_member_work
    ADD CONSTRAINT uq_gs_issuer_partition_build_member_operation_link UNIQUE (
        build_operation_id, bootstrap_operation_id, issuer_id, work_ordinal,
        member_operation_id, member_preparation_fence_id,
        reservation_source_row_ordinal, reservation_source_row_digest,
        reservation_id,
        original_issuer_coverage_operation_id, original_issuer_coverage_operation_fence,
        original_coverage_fence, original_inventory_snapshot_revision,
        planned_anchor_operation_id, planned_anchor_operation_fence,
        planned_anchor_coverage_fence, planned_anchor_inventory_snapshot_revision);

ALTER TABLE game_session_canonical_issuer_partition_build_member_work
    ADD CONSTRAINT uq_gs_issuer_partition_build_binding_link UNIQUE (
        build_operation_id, bootstrap_operation_id, issuer_id, work_ordinal,
        binding_source_row_ordinal, binding_source_row_digest);

CREATE TABLE game_session_canonical_issuer_bootstrap_member_op_counter (
    issuer_id character varying(64) NOT NULL,
    last_issued_operation_fence numeric NOT NULL,
    last_issued_member_generation numeric NOT NULL,
    counter_version bigint NOT NULL,
    CONSTRAINT pk_gs_issuer_bootstrap_member_op_counter PRIMARY KEY (issuer_id),
    CONSTRAINT chk_gs_issuer_bootstrap_member_op_counter CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND counter_version >= 0
        AND last_issued_operation_fence >= 0
        AND last_issued_member_generation >= 0
        AND last_issued_operation_fence = floor(last_issued_operation_fence)
        AND last_issued_member_generation = floor(last_issued_member_generation)
        AND length(CAST(last_issued_operation_fence AS varchar)) <= 256
        AND length(CAST(last_issued_member_generation AS varchar)) <= 256
        AND ((counter_version = 0
                AND last_issued_operation_fence = 0
                AND last_issued_member_generation = 0)
            OR (counter_version > 0
                AND last_issued_operation_fence > 0
                AND last_issued_member_generation = last_issued_operation_fence + 1))
    )
);

CREATE TABLE game_session_canonical_issuer_bootstrap_member_operation (
    member_operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    bootstrap_operation_id uuid NOT NULL,
    bootstrap_request_id uuid NOT NULL,
    build_operation_id uuid NOT NULL,
    build_plan_digest character varying(71) NOT NULL,
    work_ordinal bigint NOT NULL,
    preparation_operation_id uuid NOT NULL,
    preparation_fence_id uuid NOT NULL,
    reservation_source_row_ordinal bigint NOT NULL,
    reservation_source_row_digest character varying(71) NOT NULL,
    binding_source_row_ordinal bigint,
    binding_source_row_digest character varying(71),
    binding_lifecycle character varying(24),
    reservation_id uuid NOT NULL,
    reservation_lifecycle character varying(24) NOT NULL,
    expected_binding_present boolean NOT NULL,
    counts_toward_capacity boolean NOT NULL,
    original_issuer_coverage_operation_id uuid NOT NULL,
    original_issuer_coverage_operation_fence numeric NOT NULL,
    original_coverage_fence numeric NOT NULL,
    original_inventory_snapshot_revision numeric NOT NULL,
    bootstrap_anchor_operation_id uuid NOT NULL,
    bootstrap_anchor_operation_fence numeric NOT NULL,
    bootstrap_anchor_coverage_fence numeric NOT NULL,
    bootstrap_anchor_inventory_snapshot_revision numeric NOT NULL,
    operation_fence numeric NOT NULL,
    local_member_generation numeric NOT NULL,
    member_status character varying(32) NOT NULL,
    member_schema_version smallint NOT NULL,
    member_value_bytes bytea NOT NULL,
    member_value_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_bootstrap_member_operation PRIMARY KEY (member_operation_id),
    CONSTRAINT uq_gs_issuer_bootstrap_member_operation_work
        UNIQUE (issuer_id, bootstrap_operation_id, work_ordinal),
    CONSTRAINT uq_gs_issuer_bootstrap_member_operation_fence
        UNIQUE (issuer_id, operation_fence),
    CONSTRAINT uq_gs_issuer_bootstrap_member_operation_generation
        UNIQUE (issuer_id, local_member_generation),
    CONSTRAINT fk_gs_issuer_bootstrap_member_operation_intent
        FOREIGN KEY (bootstrap_operation_id, issuer_id)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent (operation_id, issuer_id),
    CONSTRAINT fk_gs_issuer_bootstrap_member_operation_request
        FOREIGN KEY (bootstrap_operation_id, issuer_id, bootstrap_request_id)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent (operation_id, issuer_id, request_id),
    CONSTRAINT fk_gs_issuer_bootstrap_member_operation_plan
        FOREIGN KEY (build_operation_id, bootstrap_operation_id, bootstrap_request_id,
            issuer_id, build_plan_digest)
        REFERENCES game_session_canonical_issuer_partition_build_plan
            (build_operation_id, bootstrap_operation_id, bootstrap_request_id, issuer_id, plan_digest),
    CONSTRAINT fk_gs_issuer_bootstrap_member_operation_work
        FOREIGN KEY (
            build_operation_id, bootstrap_operation_id, issuer_id, work_ordinal,
            preparation_operation_id, preparation_fence_id,
            reservation_source_row_ordinal, reservation_source_row_digest, reservation_id,
            original_issuer_coverage_operation_id, original_issuer_coverage_operation_fence,
            original_coverage_fence, original_inventory_snapshot_revision,
            bootstrap_anchor_operation_id, bootstrap_anchor_operation_fence,
            bootstrap_anchor_coverage_fence, bootstrap_anchor_inventory_snapshot_revision)
        REFERENCES game_session_canonical_issuer_partition_build_member_work (
            build_operation_id, bootstrap_operation_id, issuer_id, work_ordinal,
            member_operation_id, member_preparation_fence_id,
            reservation_source_row_ordinal, reservation_source_row_digest, reservation_id,
            original_issuer_coverage_operation_id, original_issuer_coverage_operation_fence,
            original_coverage_fence, original_inventory_snapshot_revision,
            planned_anchor_operation_id, planned_anchor_operation_fence,
            planned_anchor_coverage_fence, planned_anchor_inventory_snapshot_revision),
    CONSTRAINT fk_gs_issuer_bootstrap_member_operation_binding_work
        FOREIGN KEY (build_operation_id, bootstrap_operation_id, issuer_id, work_ordinal,
            binding_source_row_ordinal, binding_source_row_digest)
        REFERENCES game_session_canonical_issuer_partition_build_member_work (
            build_operation_id, bootstrap_operation_id, issuer_id, work_ordinal,
            binding_source_row_ordinal, binding_source_row_digest),
    CONSTRAINT fk_gs_issuer_bootstrap_member_operation_source
        FOREIGN KEY (build_operation_id, reservation_source_row_ordinal,
            reservation_source_row_digest)
        REFERENCES game_session_canonical_issuer_partition_build_cohort_row
            (build_operation_id, source_row_ordinal, source_row_digest),
    CONSTRAINT fk_gs_issuer_bootstrap_member_operation_binding_source
        FOREIGN KEY (build_operation_id, binding_source_row_ordinal,
            binding_source_row_digest)
        REFERENCES game_session_canonical_issuer_partition_build_cohort_row
            (build_operation_id, source_row_ordinal, source_row_digest),
    CONSTRAINT fk_gs_issuer_bootstrap_member_operation_anchor
        FOREIGN KEY (build_operation_id, bootstrap_operation_id, issuer_id,
            bootstrap_anchor_operation_fence, bootstrap_anchor_coverage_fence,
            bootstrap_anchor_inventory_snapshot_revision)
        REFERENCES game_session_canonical_issuer_partition_build_plan
            (build_operation_id, bootstrap_operation_id, issuer_id,
            bootstrap_operation_fence, bootstrap_coverage_fence,
            issuer_inventory_snapshot_revision),
    CONSTRAINT chk_gs_issuer_bootstrap_member_operation CHECK (
        member_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND bootstrap_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND bootstrap_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND build_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND preparation_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND preparation_fence_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_issuer_coverage_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND bootstrap_anchor_operation_id = bootstrap_operation_id
        AND member_operation_id <> bootstrap_operation_id
        AND member_operation_id <> bootstrap_request_id
        AND member_operation_id <> build_operation_id
        AND member_operation_id <> preparation_operation_id
        AND member_operation_id <> preparation_fence_id
        AND reservation_lifecycle IN ('RESERVED', 'BOUND', 'RELEASE_PENDING', 'RELEASED')
        AND ((binding_source_row_ordinal IS NULL
                AND binding_source_row_digest IS NULL
                AND binding_lifecycle IS NULL
                AND expected_binding_present = FALSE
                AND reservation_lifecycle IN ('RESERVED', 'RELEASED'))
            OR (binding_source_row_ordinal IS NOT NULL
                AND binding_source_row_ordinal >= 0
                AND binding_source_row_digest IS NOT NULL
                AND binding_source_row_digest ~ '^sha256:[0-9a-f]{64}$'
                AND ((binding_lifecycle = 'CANDIDATE_PREPARED'
                        AND reservation_lifecycle = 'RESERVED'
                        AND expected_binding_present = FALSE)
                    OR (binding_lifecycle = 'PROVISIONAL'
                        AND reservation_lifecycle = 'RESERVED'
                        AND expected_binding_present = TRUE)
                    OR (binding_lifecycle = 'ACTIVE'
                        AND reservation_lifecycle IN ('BOUND', 'RELEASE_PENDING')
                        AND expected_binding_present = TRUE)
                    OR (binding_lifecycle = 'TERMINAL'
                        AND reservation_lifecycle = 'RELEASED'
                        AND expected_binding_present = FALSE))))
        AND operation_fence > 0
        AND local_member_generation > operation_fence
        AND original_issuer_coverage_operation_fence > 0
        AND original_coverage_fence > 0
        AND original_inventory_snapshot_revision > 0
        AND bootstrap_anchor_operation_fence > 0
        AND bootstrap_anchor_coverage_fence > 0
        AND bootstrap_anchor_inventory_snapshot_revision > 0
        AND member_status = 'PENDING_PHYSICAL_PROOF'
        AND member_schema_version = 2
        AND counts_toward_capacity = (reservation_lifecycle <> 'RELEASED')
        AND octet_length(member_value_bytes) BETWEEN 1 AND 65536
        AND build_plan_digest ~ '^sha256:[0-9a-f]{64}$'
        AND reservation_source_row_digest ~ '^sha256:[0-9a-f]{64}$'
        AND member_value_digest ~ '^sha256:[0-9a-f]{64}$'
        AND work_ordinal >= 0
        AND reservation_source_row_ordinal >= 0)
);

-- jOOQ's H2 DDL parser does not model PostgreSQL trigger functions. The deployed PostgreSQL
-- migration retains these immutable-artifact and monotonic compare-and-set guards.
-- [jooq ignore start]
CREATE FUNCTION game_session_reject_issuer_bootstrap_member_operation_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'issuer bootstrap member operation artifacts are immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_gs_issuer_bootstrap_member_operation_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_bootstrap_member_operation
    FOR EACH ROW EXECUTE FUNCTION game_session_reject_issuer_bootstrap_member_operation_change();
CREATE TRIGGER trg_gs_issuer_bootstrap_member_operation_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_bootstrap_member_operation
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_bootstrap_member_operation_change();

CREATE FUNCTION game_session_guard_issuer_bootstrap_member_operation_counter()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'issuer bootstrap member operation counters cannot be deleted'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.issuer_id IS DISTINCT FROM OLD.issuer_id
        OR NEW.counter_version <> OLD.counter_version + 1
        OR NEW.last_issued_operation_fence <= OLD.last_issued_operation_fence
        OR NEW.last_issued_member_generation <= OLD.last_issued_member_generation
        OR NEW.last_issued_member_generation <> NEW.last_issued_operation_fence + 1 THEN
        RAISE EXCEPTION 'issuer bootstrap member operation counter CAS must advance both local domains'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_gs_issuer_bootstrap_member_operation_counter_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_bootstrap_member_op_counter
    FOR EACH ROW EXECUTE FUNCTION game_session_guard_issuer_bootstrap_member_operation_counter();
CREATE TRIGGER trg_gs_issuer_bootstrap_member_operation_counter_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_bootstrap_member_op_counter
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_bootstrap_member_operation_change();
-- [jooq ignore stop]
