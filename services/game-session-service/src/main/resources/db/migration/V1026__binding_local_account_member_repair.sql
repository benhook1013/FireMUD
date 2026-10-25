-- Ordinary NEW bindings in NO_ACTIVE_ACCOUNT_WIDE_FLOW can complete only their exact member
-- obligation. The new acknowledgement is binding-local and never writes account-wide coverage.

ALTER TABLE game_session_canonical_binding_account_index_obligation
    DROP CONSTRAINT chk_gs_canonical_binding_account_index_obligation;

ALTER TABLE game_session_canonical_binding_account_index_obligation
    DROP CONSTRAINT chk_gs_canonical_binding_account_index_projection;

ALTER TABLE game_session_canonical_binding_account_index_obligation
    ADD CONSTRAINT chk_gs_canonical_binding_account_index_obligation CHECK (
        obligation_ordinal >= 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND action IN ('ADD_OR_RETAIN', 'REMOVE')
        AND binding_generation > 0
        AND (expected_prior_generation IS NULL OR expected_prior_generation >= 0)
        AND account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND execution_phase IN ('BEFORE_FINAL_CAS', 'AFTER_FINAL_CAS')
        AND status IN ('REQUIRED', 'ACKNOWLEDGED')
        AND inventory_revision > 0
        AND (status = 'REQUIRED'
            OR (status = 'ACKNOWLEDGED'
                AND obligation_ordinal = 0
                AND action = 'ADD_OR_RETAIN'
                AND execution_phase = 'BEFORE_FINAL_CAS'
                AND projection_state = 'PRESENT_AWAITING_COVERAGE'
                AND projection_member IS NOT NULL
                AND projection_inventory_revision IS NOT NULL
                AND projection_inventory_revision > 0)));

ALTER TABLE game_session_canonical_binding_account_index_obligation
    ADD CONSTRAINT chk_gs_canonical_binding_account_index_projection CHECK (
        (projection_state = 'REQUIRED'
            AND projection_member IS NULL
            AND projection_inventory_revision IS NULL)
        OR (projection_state = 'PRESENT_AWAITING_COVERAGE'
            AND action = 'ADD_OR_RETAIN'
            AND execution_phase = 'BEFORE_FINAL_CAS'
            AND status IN ('REQUIRED', 'ACKNOWLEDGED')
            AND projection_member IS NOT NULL
            AND projection_inventory_revision IS NOT NULL
            AND projection_inventory_revision > 0
            AND projection_inventory_revision <= inventory_revision)
        OR (projection_state = 'ABSENT_AWAITING_COVERAGE'
            AND action = 'REMOVE'
            AND execution_phase = 'AFTER_FINAL_CAS'
            AND status = 'REQUIRED'
            AND projection_member IS NULL
            AND projection_inventory_revision IS NOT NULL
            AND projection_inventory_revision > 0
            AND projection_inventory_revision <= inventory_revision));

ALTER TABLE game_session_canonical_account_coverage_snapshot_obligation
    DROP CONSTRAINT chk_gs_canonical_account_coverage_snapshot_obligation;

ALTER TABLE game_session_canonical_account_coverage_snapshot_obligation
    ADD CONSTRAINT chk_gs_canonical_account_coverage_snapshot_obligation CHECK (
        row_ordinal >= 0
        AND obligation_ordinal >= 0
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND binding_generation > 0
        AND account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND status IN ('REQUIRED', 'ACKNOWLEDGED')
        AND inventory_revision > 0
        AND expected_readback_state IN ('PRESENT', 'ABSENT', 'BLOCKED')
        AND ((redis_readback_state = 'PENDING' AND redis_readback_member IS NULL)
            OR (redis_readback_state = 'PRESENT' AND redis_readback_member IS NOT NULL)
            OR (redis_readback_state = 'ABSENT' AND redis_readback_member IS NULL)));

CREATE TABLE game_session_canonical_account_member_repair_control (
    account_id uuid NOT NULL,
    last_operation_fence numeric NOT NULL DEFAULT 0,
    last_coverage_generation numeric NOT NULL DEFAULT 0,
    CONSTRAINT pk_gs_canonical_account_member_repair_control PRIMARY KEY (account_id),
    CONSTRAINT fk_gs_canonical_account_member_repair_control_account
        FOREIGN KEY (account_id)
        REFERENCES game_session_canonical_account_coverage_control (account_id),
    CONSTRAINT chk_gs_canonical_account_member_repair_control CHECK (
        account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND last_operation_fence >= 0
        AND last_coverage_generation >= 0)
);

CREATE TABLE game_session_canonical_account_member_repair_operation (
    operation_id uuid NOT NULL,
    transition_id uuid NOT NULL,
    obligation_ordinal integer NOT NULL,
    operation_fence numeric NOT NULL,
    coverage_fence uuid NOT NULL,
    inventory_snapshot_revision numeric NOT NULL,
    binding_ref bytea NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    binding_generation numeric NOT NULL,
    account_index_fence uuid NOT NULL,
    original_action character varying(16) NOT NULL,
    original_expected_prior_generation numeric,
    original_execution_phase character varying(24) NOT NULL,
    original_status character varying(16) NOT NULL,
    original_inventory_revision numeric NOT NULL,
    original_projection_state character varying(32) NOT NULL,
    original_projection_member text,
    original_projection_inventory_revision numeric,
    desired_action character varying(16) NOT NULL,
    member text NOT NULL,
    account_control_state character varying(32) NOT NULL,
    account_control_epoch numeric NOT NULL,
    status character varying(16) NOT NULL DEFAULT 'PENDING',
    coverage_generation numeric,
    readback_state character varying(16),
    readback_member text,
    completed_inventory_revision numeric,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_canonical_account_member_repair_operation PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_canonical_account_member_repair_transition
        UNIQUE (transition_id, obligation_ordinal),
    CONSTRAINT uq_gs_canonical_account_member_repair_fence
        UNIQUE (account_id, operation_fence),
    CONSTRAINT fk_gs_canonical_account_member_repair_transition
        FOREIGN KEY (transition_id)
        REFERENCES game_session_canonical_binding_transition (transition_id),
    CONSTRAINT fk_gs_canonical_account_member_repair_obligation
        FOREIGN KEY (transition_id, obligation_ordinal)
        REFERENCES game_session_canonical_binding_account_index_obligation
            (transition_id, obligation_ordinal),
    CONSTRAINT fk_gs_canonical_account_member_repair_control
        FOREIGN KEY (account_id)
        REFERENCES game_session_canonical_account_member_repair_control (account_id),
    CONSTRAINT chk_gs_canonical_account_member_repair_operation CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND operation_id <> transition_id
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND obligation_ordinal = 0
        AND operation_fence > 0
        AND coverage_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND coverage_fence <> operation_id
        AND coverage_fence <> transition_id
        AND inventory_snapshot_revision > 0
        AND octet_length(binding_ref) > 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND binding_generation > 0
        AND account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND coverage_fence <> account_index_fence
        AND original_action = 'ADD_OR_RETAIN'
        AND original_expected_prior_generation IS NULL
        AND original_execution_phase = 'BEFORE_FINAL_CAS'
        AND original_status = 'REQUIRED'
        AND original_inventory_revision > 0
        AND original_inventory_revision <= inventory_snapshot_revision
        AND ((original_projection_state = 'REQUIRED'
                AND original_projection_member IS NULL
                AND original_projection_inventory_revision IS NULL)
            OR (original_projection_state = 'PRESENT_AWAITING_COVERAGE'
                AND original_projection_member IS NOT NULL
                AND original_projection_inventory_revision IS NOT NULL
                AND original_projection_inventory_revision > 0
                AND original_projection_inventory_revision <= original_inventory_revision))
        AND desired_action = 'ADD_OR_RETAIN'
        AND length(member) > 0
        AND account_control_state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
        AND account_control_epoch >= 0
        AND ((status = 'PENDING'
                AND coverage_generation IS NULL
                AND readback_state IS NULL
                AND readback_member IS NULL
                AND completed_inventory_revision IS NULL)
            OR (status = 'ACKNOWLEDGED'
                AND coverage_generation IS NOT NULL
                AND coverage_generation > inventory_snapshot_revision
                AND readback_state IS NOT NULL
                AND readback_state = 'PRESENT'
                AND readback_member IS NOT NULL
                AND readback_member = member
                AND completed_inventory_revision IS NOT NULL
                AND completed_inventory_revision > inventory_snapshot_revision)))
);

CREATE INDEX ix_gs_canonical_account_member_repair_pending
    ON game_session_canonical_account_member_repair_operation (account_id, status, operation_fence);
