-- Retain exact per-transition Redis projection observations without satisfying the account-index
-- obligation. A v2 accountIndexAcknowledgement still requires the separate account-wide owner
-- operation, complete snapshot, fencing lifecycle, coverage generation, and same-operation proof.
ALTER TABLE game_session_canonical_binding_account_index_obligation
    ADD COLUMN projection_state character varying(32) NOT NULL DEFAULT 'REQUIRED',
    ADD COLUMN projection_member text,
    ADD COLUMN projection_inventory_revision numeric;

ALTER TABLE game_session_canonical_binding_account_index_obligation
    ADD CONSTRAINT chk_gs_canonical_binding_account_index_projection CHECK (
        (projection_state = 'REQUIRED'
            AND projection_member IS NULL
            AND projection_inventory_revision IS NULL)
        OR (projection_state = 'PRESENT_AWAITING_COVERAGE'
            AND action = 'ADD_OR_RETAIN'
            AND execution_phase = 'BEFORE_FINAL_CAS'
            AND status = 'REQUIRED'
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
            AND projection_inventory_revision <= inventory_revision)
    );

CREATE INDEX ix_gs_canonical_binding_account_projection_repair
    ON game_session_canonical_binding_account_index_obligation
       (account_id, projection_state, inventory_revision);
