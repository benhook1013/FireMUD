-- Ordinary NEW operations are registered only after a positive initial installed issuer anchor.
-- This local durable transition is not a new inventory, an issuer-wide coverage acknowledgement,
-- a tenant barrier, or admission authority.

CREATE TABLE game_session_canonical_issuer_binding_local_operation (
    operation_id uuid NOT NULL,
    request_id uuid NOT NULL,
    transition_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    candidate_account_index_fence uuid NOT NULL,
    binding_ref bytea NOT NULL,
    binding_generation numeric NOT NULL,
    reservation_id uuid NOT NULL,
    reservation_fence uuid NOT NULL,
    partition_id numeric NOT NULL,
    local_inventory_snapshot_revision numeric NOT NULL,
    expected_prior_binding_ref bytea,
    expected_prior_binding_generation numeric,

    original_issuer_coverage_operation_id uuid NOT NULL,
    original_issuer_coverage_operation_fence numeric NOT NULL,
    original_coverage_fence numeric NOT NULL,
    original_inventory_snapshot_revision numeric NOT NULL,

    anchor_bootstrap_operation_id uuid NOT NULL,
    anchor_bootstrap_request_id uuid NOT NULL,
    anchor_build_operation_id uuid NOT NULL,
    anchor_build_plan_digest character varying(71) NOT NULL,
    anchor_control_epoch numeric NOT NULL,
    anchor_issuer_coverage_operation_id uuid NOT NULL,
    anchor_operation_fence numeric NOT NULL,
    anchor_coverage_fence numeric NOT NULL,
    anchor_issuer_inventory_snapshot_revision numeric NOT NULL,
    anchor_layout_version numeric NOT NULL,
    anchor_partition_count numeric NOT NULL,
    anchor_partition_capacity numeric NOT NULL,
    anchor_mapping_identity character varying(96) NOT NULL,
    anchor_layout_descriptor_bytes bytea NOT NULL,
    anchor_layout_descriptor_digest character varying(71) NOT NULL,
    anchor_issuer_auth_generation numeric NOT NULL,
    anchor_source_checkpoint_bytes bytea NOT NULL,
    anchor_source_checkpoint_digest character varying(71) NOT NULL,
    anchor_source_event_bytes bytea,
    anchor_source_event_digest character varying(71),
    anchor_incarnation_bytes bytea NOT NULL,
    anchor_incarnation_digest character varying(71) NOT NULL,
    exact_anchor_bytes bytea NOT NULL,
    exact_anchor_digest character varying(71) NOT NULL,

    baseline_partition_id numeric NOT NULL,
    baseline_field_set_bytes bytea NOT NULL,
    baseline_field_set_digest character varying(71) NOT NULL,
    baseline_counted_capacity integer NOT NULL,
    baseline_reservation_count integer NOT NULL,
    baseline_binding_count integer NOT NULL,

    account_control_state character varying(32) NOT NULL,
    account_control_epoch numeric NOT NULL,
    account_current_operation_id uuid,
    account_current_fence_intent_id uuid,
    account_current_operation_fence numeric,
    account_admission_fence uuid,
    account_historical_admission_fence uuid,
    account_coverage_fence uuid,
    account_inventory_snapshot_revision numeric,
    account_coverage_generation numeric,

    operation_fence numeric NOT NULL,
    local_member_generation numeric NOT NULL,
    expected_old_member_state character varying(16) NOT NULL,
    expected_old_capacity_state character varying(16) NOT NULL,
    member_status character varying(32) NOT NULL,
    member_schema_version smallint NOT NULL,
    member_value_bytes bytea NOT NULL,
    member_value_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_gs_issuer_binding_local_operation PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_issuer_binding_local_operation_request UNIQUE (issuer_id, request_id),
    CONSTRAINT uq_gs_issuer_binding_local_operation_transition UNIQUE (issuer_id, transition_id),
    CONSTRAINT uq_gs_issuer_binding_local_operation_reservation UNIQUE (reservation_id),
    CONSTRAINT uq_gs_issuer_binding_local_operation_fence UNIQUE (issuer_id, operation_fence),
    CONSTRAINT uq_gs_issuer_binding_local_operation_generation UNIQUE (issuer_id, local_member_generation),
    CONSTRAINT fk_gs_issuer_binding_local_operation_transition
        FOREIGN KEY (transition_id) REFERENCES game_session_canonical_binding_transition (transition_id),
    CONSTRAINT fk_gs_issuer_binding_local_operation_reservation
        FOREIGN KEY (reservation_id) REFERENCES game_session_canonical_issuer_partition_reservation (reservation_id),
    CONSTRAINT fk_gs_issuer_binding_local_operation_anchor
        FOREIGN KEY (anchor_bootstrap_operation_id)
        REFERENCES game_session_canonical_issuer_installed_anchor (bootstrap_operation_id),
    CONSTRAINT chk_gs_issuer_binding_local_operation CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND request_id <> transition_id
        AND issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND candidate_account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND octet_length(binding_ref) > 0
        AND binding_generation > 0
        AND reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND reservation_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND partition_id >= 0
        AND local_inventory_snapshot_revision > 0
        AND expected_prior_binding_ref IS NULL
        AND expected_prior_binding_generation IS NULL
        AND original_issuer_coverage_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_issuer_coverage_operation_fence > 0
        AND original_coverage_fence > 0
        AND original_inventory_snapshot_revision > 0
        AND anchor_bootstrap_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND anchor_bootstrap_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND anchor_build_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND anchor_build_plan_digest ~ '^sha256:[0-9a-f]{64}$'
        AND anchor_control_epoch > 0
        AND anchor_issuer_coverage_operation_id = anchor_bootstrap_operation_id
        AND anchor_operation_fence = anchor_control_epoch
        AND anchor_operation_fence > 0
        AND anchor_coverage_fence > 0
        AND anchor_coverage_fence <> anchor_operation_fence
        AND anchor_issuer_inventory_snapshot_revision > 0
        AND anchor_layout_version > 0
        AND anchor_partition_count > 0
        AND anchor_partition_capacity > 0
        AND partition_id < anchor_partition_count
        AND anchor_mapping_identity = 'bindingRef/v1;sha256-u256-mod/v1'
        AND octet_length(anchor_layout_descriptor_bytes) > 0
        AND anchor_layout_descriptor_digest ~ '^sha256:[0-9a-f]{64}$'
        AND anchor_issuer_auth_generation > 0
        AND octet_length(anchor_source_checkpoint_bytes) > 0
        AND anchor_source_checkpoint_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((anchor_source_event_bytes IS NULL AND anchor_source_event_digest IS NULL)
            OR (anchor_source_event_bytes IS NOT NULL
                AND octet_length(anchor_source_event_bytes) > 0
                AND anchor_source_event_digest ~ '^sha256:[0-9a-f]{64}$'))
        AND octet_length(anchor_incarnation_bytes) > 0
        AND anchor_incarnation_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(exact_anchor_bytes) BETWEEN 1 AND 262144
        AND exact_anchor_digest ~ '^sha256:[0-9a-f]{64}$'
        AND baseline_partition_id = partition_id
        AND octet_length(baseline_field_set_bytes) BETWEEN 1 AND 17825792
        AND baseline_field_set_digest ~ '^sha256:[0-9a-f]{64}$'
        AND baseline_counted_capacity >= 0
        AND baseline_counted_capacity <= anchor_partition_capacity
        AND baseline_reservation_count >= 0
        AND baseline_binding_count >= 0
        AND account_control_state IN ('NO_ACTIVE_ACCOUNT_WIDE_FLOW', 'HISTORICAL')
        AND account_control_epoch >= 0
        AND ((account_control_state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
                AND account_current_operation_id IS NULL
                AND account_current_fence_intent_id IS NULL
                AND account_current_operation_fence IS NULL
                AND account_admission_fence IS NULL
                AND account_historical_admission_fence IS NULL
                AND account_coverage_fence IS NULL
                AND account_inventory_snapshot_revision IS NULL
                AND account_coverage_generation IS NULL)
            OR (account_control_state = 'HISTORICAL'
                AND account_current_operation_id IS NOT NULL
                AND account_current_fence_intent_id IS NULL
                AND account_current_operation_fence > 0
                AND account_admission_fence IS NULL
                AND account_historical_admission_fence IS NOT NULL
                AND account_coverage_fence IS NOT NULL
                AND account_inventory_snapshot_revision > 0
                AND account_coverage_generation > 0))
        AND operation_fence > 0
        AND local_member_generation = operation_fence + 1
        AND expected_old_member_state = 'ABSENT'
        AND expected_old_capacity_state = 'ABSENT'
        AND member_status = 'PENDING_PHYSICAL_PROOF'
        AND member_schema_version = 2
        AND octet_length(member_value_bytes) BETWEEN 1 AND 65536
        AND member_value_digest ~ '^sha256:[0-9a-f]{64}$')
);

-- V1032 owns the local issuer-member operation counter. The operation below shares that
-- local-only fence/generation domain; neither value advances or substitutes for global coverage.

-- [jooq ignore start]
-- H2 maps PostgreSQL bytea to an incomparable BLOB and cannot model this foreign key.
-- PostgreSQL still enforces the exact inventory identity; native migration proof is required.
ALTER TABLE game_session_canonical_issuer_binding_local_operation
    ADD CONSTRAINT fk_gs_issuer_binding_local_operation_inventory
    FOREIGN KEY (binding_ref)
    REFERENCES game_session_canonical_gameplay_binding_inventory (binding_ref);

CREATE FUNCTION game_session_reject_issuer_binding_local_operation_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'issuer binding-local operations are immutable'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_gs_issuer_binding_local_operation_immutable
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_binding_local_operation
    FOR EACH ROW EXECUTE FUNCTION game_session_reject_issuer_binding_local_operation_change();
CREATE TRIGGER trg_gs_issuer_binding_local_operation_no_truncate
    BEFORE TRUNCATE ON game_session_canonical_issuer_binding_local_operation
    FOR EACH STATEMENT EXECUTE FUNCTION game_session_reject_issuer_binding_local_operation_change();
-- [jooq ignore stop]

COMMENT ON TABLE game_session_canonical_issuer_binding_local_operation IS
    'Immutable ordinary NEW preparation linked to a positively installed initial issuer anchor; pending physical proof only, not global issuer coverage or admission.';
