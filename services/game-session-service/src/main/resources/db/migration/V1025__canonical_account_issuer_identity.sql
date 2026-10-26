-- Account is the only canonical JWT issuer. Retain the old UUID spellings as historical
-- evidence; this migration changes storage only and does not infer an Account issuer from them.
ALTER TABLE game_session_canonical_gameplay_binding_inventory
    DROP CONSTRAINT chk_gs_canonical_gameplay_binding_inventory_identity;

ALTER TABLE game_session_canonical_issuer_partition_reservation
    DROP CONSTRAINT chk_gs_canonical_issuer_partition_reservation;

ALTER TABLE game_session_canonical_account_coverage_snapshot_inventory
    DROP CONSTRAINT chk_gs_canonical_account_coverage_snapshot_inventory;

ALTER TABLE game_session_canonical_account_coverage_snapshot_reservation
    DROP CONSTRAINT chk_gs_canonical_account_coverage_snapshot_reservation;

ALTER TABLE game_session_canonical_gameplay_binding_inventory
    ALTER COLUMN issuer_id TYPE character varying(64)
    -- [jooq ignore start]
    USING issuer_id::text
    -- [jooq ignore stop]
;

ALTER TABLE game_session_canonical_issuer_partition_reservation
    ALTER COLUMN issuer_id TYPE character varying(64)
    -- [jooq ignore start]
    USING issuer_id::text
    -- [jooq ignore stop]
;

ALTER TABLE game_session_canonical_account_coverage_snapshot_inventory
    ALTER COLUMN issuer_id TYPE character varying(64)
    -- [jooq ignore start]
    USING issuer_id::text
    -- [jooq ignore stop]
;

ALTER TABLE game_session_canonical_account_coverage_snapshot_transition
    ALTER COLUMN candidate_issuer_id TYPE character varying(64)
    -- [jooq ignore start]
    USING candidate_issuer_id::text
    -- [jooq ignore stop]
;

ALTER TABLE game_session_canonical_account_coverage_snapshot_reservation
    ALTER COLUMN issuer_id TYPE character varying(64)
    -- [jooq ignore start]
    USING issuer_id::text
    -- [jooq ignore stop]
;

-- Keep the prior identity/state predicates in place. The non-nil check still applies to the
-- retained UUID spelling; the separate NOT VALID checks below gate every new or changed row on
-- the exact Account issuer while leaving unproven historical rows untouched.
ALTER TABLE game_session_canonical_gameplay_binding_inventory
    ADD CONSTRAINT chk_gs_canonical_gameplay_binding_inventory_identity CHECK (
        octet_length(binding_ref) > 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND character_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_id <> '00000000-0000-0000-0000-000000000000'
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        -- [jooq ignore start]
        AND session_id <> ''
        -- [jooq ignore stop]
        AND session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND session_id <> '00000000-0000-0000-0000-000000000000'
        AND session_id !~ '^[[:space:]]*$'
        AND session_id !~ '[[:cntrl:]]'
        AND playable_state_scope IN ('SHARED', 'ISOLATED')
        AND binding_generation > 0
        AND region_epoch > 0
        AND issuer_auth_generation > 0
        AND issuer_index_layout_version > 0
        AND issuer_index_partition_count > 0
        AND issuer_index_partition_capacity > 0
        AND issuer_partition_id >= 0
        AND issuer_partition_id < issuer_index_partition_count
        AND account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND inventory_revision > 0);

ALTER TABLE game_session_canonical_issuer_partition_reservation
    ADD CONSTRAINT chk_gs_canonical_issuer_partition_reservation CHECK (
        reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        AND issuer_id <> '00000000-0000-0000-0000-000000000000'
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND character_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_coverage_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        -- [jooq ignore start]
        AND session_id <> ''
        -- [jooq ignore stop]
        AND session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND session_id <> '00000000-0000-0000-0000-000000000000'
        AND session_id !~ '^[[:space:]]*$'
        AND session_id !~ '[[:cntrl:]]'
        AND binding_generation > 0
        AND issuer_auth_generation > 0
        AND playable_state_scope IN ('SHARED', 'ISOLATED')
        AND region_epoch > 0
        AND partition_id >= 0
        AND issuer_index_layout_version > 0
        AND issuer_index_partition_count > 0
        AND partition_id < issuer_index_partition_count
        AND issuer_index_partition_capacity > 0
        AND lifecycle IN ('RESERVED', 'BOUND', 'RELEASE_PENDING', 'RELEASED')
        AND reservation_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_coverage_operation_fence > 0
        AND coverage_fence > 0
        AND inventory_snapshot_revision > 0
        AND inventory_revision > 0);

ALTER TABLE game_session_canonical_account_coverage_snapshot_inventory
    ADD CONSTRAINT chk_gs_canonical_account_coverage_snapshot_inventory CHECK (
        row_ordinal >= 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND character_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_scope IN ('SHARED', 'ISOLATED')
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        AND binding_generation > 0
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND region_epoch > 0
        AND issuer_id <> '00000000-0000-0000-0000-000000000000'
        AND issuer_auth_generation > 0
        AND issuer_index_layout_version > 0
        AND issuer_index_partition_count > 0
        AND issuer_index_partition_capacity > 0
        AND issuer_partition_id >= 0
        AND issuer_partition_id < issuer_index_partition_count
        AND account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND member_transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND inventory_revision > 0
        AND lifecycle IN (
            'CANDIDATE_PREPARED', 'PROVISIONAL', 'ACTIVE', 'TERMINAL_UNRESOLVED', 'TERMINAL')
        AND account_index_state IN ('REPAIR_REQUIRED', 'ACKNOWLEDGED')
        AND issuer_index_state IN ('REPAIR_REQUIRED', 'ACKNOWLEDGED')
        AND ((action = 'ADD_OR_RETAIN'
                AND expected_readback_state = 'PRESENT'
                AND ((redis_readback_state = 'PENDING' AND redis_readback_member IS NULL)
                    OR (redis_readback_state = 'PRESENT'
                        AND redis_readback_member = exact_member)))
            OR (action = 'REMOVE'
                AND lifecycle = 'TERMINAL'
                AND expected_readback_state = 'ABSENT'
                AND ((redis_readback_state = 'PENDING' AND redis_readback_member IS NULL)
                    OR (redis_readback_state = 'ABSENT' AND redis_readback_member IS NULL)))
            OR (action = 'BLOCKED'
                AND expected_readback_state = 'BLOCKED'
                AND redis_readback_state = 'PENDING'
                AND redis_readback_member IS NULL)));

ALTER TABLE game_session_canonical_account_coverage_snapshot_reservation
    ADD CONSTRAINT chk_gs_canonical_account_coverage_snapshot_reservation CHECK (
        row_ordinal >= 0
        AND reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND binding_generation > 0
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_id <> '00000000-0000-0000-0000-000000000000'
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND character_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_auth_generation > 0
        AND region_epoch > 0
        AND partition_id >= 0
        AND partition_id < issuer_index_partition_count
        AND issuer_index_layout_version > 0
        AND issuer_index_partition_count > 0
        AND issuer_index_partition_capacity > 0
        AND reservation_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_coverage_operation_fence > 0
        AND coverage_fence > 0
        AND inventory_snapshot_revision > 0
        AND inventory_revision > 0);

-- NOT VALID preserves the historical UUID text without admitting it as an Account issuer.
-- PostgreSQL still checks each new or changed row against these exact issuer predicates.
ALTER TABLE game_session_canonical_gameplay_binding_inventory
    ADD CONSTRAINT chk_gs_gameplay_inventory_account_issuer
        CHECK (
            issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C")
        -- [jooq ignore start]
        NOT VALID
        -- [jooq ignore stop]
;

ALTER TABLE game_session_canonical_issuer_partition_reservation
    ADD CONSTRAINT chk_gs_issuer_reservation_account_issuer
        CHECK (
            issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C")
        -- [jooq ignore start]
        NOT VALID
        -- [jooq ignore stop]
;

ALTER TABLE game_session_canonical_account_coverage_snapshot_inventory
    ADD CONSTRAINT chk_gs_snapshot_inventory_account_issuer
        CHECK (
            issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C")
        -- [jooq ignore start]
        NOT VALID
        -- [jooq ignore stop]
;

ALTER TABLE game_session_canonical_account_coverage_snapshot_transition
    ADD CONSTRAINT chk_gs_snapshot_transition_account_issuer
        CHECK (
            candidate_issuer_id COLLATE "C"
                = 'firemud-account-service'::character varying COLLATE "C")
        -- [jooq ignore start]
        NOT VALID
        -- [jooq ignore stop]
;

ALTER TABLE game_session_canonical_account_coverage_snapshot_reservation
    ADD CONSTRAINT chk_gs_snapshot_reservation_account_issuer
        CHECK (
            issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C")
        -- [jooq ignore start]
        NOT VALID
        -- [jooq ignore stop]
;
