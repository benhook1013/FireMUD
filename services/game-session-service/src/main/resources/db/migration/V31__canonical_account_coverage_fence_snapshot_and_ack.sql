-- Account-wide coverage is a separate durable Game Session owner. Every cohort requires the same
-- complete legacy-index migration receipt; an empty V29 inventory or Redis key is not that receipt.
CREATE TABLE game_session_canonical_binding_legacy_disposition (
    singleton_id smallint NOT NULL,
    disposition_state character varying(16) NOT NULL,
    cohort_id uuid,
    owner_operation_id uuid,
    legacy_writer_fence uuid,
    source_snapshot_revision numeric,
    canonical_inventory_revision numeric,
    namespace_index_readback_revision numeric,
    evidence_digest character varying(71),
    CONSTRAINT pk_gs_canonical_binding_legacy_disposition PRIMARY KEY (singleton_id),
    CONSTRAINT chk_gs_canonical_binding_legacy_disposition_singleton CHECK (singleton_id = 1),
    CONSTRAINT chk_gs_canonical_binding_legacy_disposition_state CHECK (
        (disposition_state = 'REQUIRED'
            AND cohort_id IS NULL
            AND owner_operation_id IS NULL
            AND legacy_writer_fence IS NULL
            AND source_snapshot_revision IS NULL
            AND canonical_inventory_revision IS NULL
            AND namespace_index_readback_revision IS NULL
            AND evidence_digest IS NULL)
        OR (disposition_state = 'VERIFIED'
            AND cohort_id IS NOT NULL
            AND cohort_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND owner_operation_id IS NOT NULL
            AND owner_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND legacy_writer_fence IS NOT NULL
            AND legacy_writer_fence <> '00000000-0000-0000-0000-000000000000'::uuid
            AND source_snapshot_revision IS NOT NULL
            AND source_snapshot_revision > 0
            AND canonical_inventory_revision IS NOT NULL
            AND canonical_inventory_revision > 0
            AND namespace_index_readback_revision IS NOT NULL
            AND namespace_index_readback_revision > 0
            AND evidence_digest IS NOT NULL
            AND evidence_digest ~ '^sha256:[0-9a-f]{64}$')
    )
);

INSERT INTO game_session_canonical_binding_legacy_disposition (singleton_id, disposition_state)
VALUES (1, 'REQUIRED');

CREATE TABLE game_session_canonical_account_coverage_control (
    account_id uuid NOT NULL,
    state character varying(32) NOT NULL,
    last_operation_fence numeric NOT NULL,
    current_operation_id uuid,
    current_operation_fence numeric,
    account_admission_fence uuid,
    historical_account_admission_fence uuid,
    coverage_fence uuid,
    inventory_snapshot_revision numeric,
    coverage_generation numeric,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_canonical_account_coverage_control PRIMARY KEY (account_id),
    CONSTRAINT chk_gs_canonical_account_coverage_control_account CHECK (
        account_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT chk_gs_canonical_account_coverage_control_state CHECK (
        last_operation_fence >= 0
        AND ((state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
                AND current_operation_id IS NULL
                AND current_operation_fence IS NULL
                AND account_admission_fence IS NULL
                AND historical_account_admission_fence IS NULL
                AND coverage_fence IS NULL
                AND inventory_snapshot_revision IS NULL
                AND coverage_generation IS NULL)
            OR (state = 'ACTIVE'
                AND current_operation_id IS NOT NULL
                AND current_operation_fence > 0
                AND current_operation_fence = last_operation_fence
                AND account_admission_fence IS NOT NULL
                AND account_admission_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                AND historical_account_admission_fence IS NULL
                AND coverage_fence IS NOT NULL
                AND coverage_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                AND (inventory_snapshot_revision IS NULL OR inventory_snapshot_revision > 0)
                AND coverage_generation IS NULL)
            OR (state = 'HISTORICAL'
                AND current_operation_id IS NOT NULL
                AND current_operation_fence > 0
                AND current_operation_fence = last_operation_fence
                AND account_admission_fence IS NULL
                AND historical_account_admission_fence IS NOT NULL
                AND historical_account_admission_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                AND coverage_fence IS NOT NULL
                AND coverage_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                AND inventory_snapshot_revision > 0
                AND coverage_generation IS NOT NULL
                AND coverage_generation > inventory_snapshot_revision))
    )
);

CREATE TABLE game_session_canonical_account_coverage_operation (
    operation_id uuid NOT NULL,
    request_id uuid NOT NULL,
    account_id uuid NOT NULL,
    legacy_cohort_id uuid NOT NULL,
    legacy_owner_operation_id uuid NOT NULL,
    legacy_writer_fence uuid NOT NULL,
    legacy_source_snapshot_revision numeric NOT NULL,
    legacy_canonical_inventory_revision numeric NOT NULL,
    legacy_namespace_index_readback_revision numeric NOT NULL,
    legacy_evidence_digest character varying(71) NOT NULL,
    lifecycle character varying(16) NOT NULL,
    operation_fence numeric NOT NULL,
    coverage_fence uuid NOT NULL,
    account_admission_fence uuid,
    historical_account_admission_fence uuid,
    resolved_scope_kind character varying(24) NOT NULL,
    snapshot_state character varying(16) NOT NULL,
    blocked_reason character varying(40),
    inventory_snapshot_revision numeric,
    coverage_generation numeric,
    redis_member_count bigint,
    redis_member_set_digest character varying(71),
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_canonical_account_coverage_operation PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_canonical_account_coverage_request UNIQUE (account_id, request_id),
    CONSTRAINT uq_gs_canonical_account_coverage_fence UNIQUE (account_id, operation_fence),
    CONSTRAINT fk_gs_canonical_account_coverage_operation_account
        FOREIGN KEY (account_id)
        REFERENCES game_session_canonical_account_coverage_control (account_id),
    CONSTRAINT chk_gs_canonical_account_coverage_operation_identity CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_cohort_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_owner_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_writer_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_source_snapshot_revision > 0
        AND legacy_canonical_inventory_revision > 0
        AND legacy_namespace_index_readback_revision > 0
        AND legacy_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND operation_fence > 0
        AND coverage_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND resolved_scope_kind = 'ACCOUNT_WIDE'
        AND ((lifecycle = 'ACTIVE'
                AND account_admission_fence IS NOT NULL
                AND account_admission_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                AND historical_account_admission_fence IS NULL
                AND snapshot_state IN ('COMPLETE', 'BLOCKED')
                AND ((snapshot_state = 'COMPLETE' AND blocked_reason IS NULL)
                    OR (snapshot_state = 'BLOCKED' AND blocked_reason IS NOT NULL))
                AND (inventory_snapshot_revision IS NULL OR inventory_snapshot_revision > 0)
                AND coverage_generation IS NULL
                AND redis_member_count IS NULL
                AND redis_member_set_digest IS NULL)
            OR (lifecycle = 'HISTORICAL'
                AND account_admission_fence IS NULL
                AND historical_account_admission_fence IS NOT NULL
                AND historical_account_admission_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                AND snapshot_state = 'COMPLETE'
                AND blocked_reason IS NULL
                AND inventory_snapshot_revision > 0
                AND coverage_generation IS NOT NULL
                AND coverage_generation > inventory_snapshot_revision
                AND redis_member_count >= 0
                AND redis_member_set_digest IS NOT NULL
                AND redis_member_set_digest ~ '^sha256:[0-9a-f]{64}$'))
    )
);

ALTER TABLE game_session_canonical_account_coverage_control
    ADD CONSTRAINT fk_gs_canonical_account_coverage_control_operation
        FOREIGN KEY (current_operation_id)
        REFERENCES game_session_canonical_account_coverage_operation (operation_id);

INSERT INTO game_session_canonical_account_coverage_control (
    account_id, state, last_operation_fence)
SELECT DISTINCT account_id, 'NO_ACTIVE_ACCOUNT_WIDE_FLOW', 0
  FROM game_session_canonical_gameplay_binding_inventory;

CREATE INDEX ix_gs_canonical_account_coverage_active
    ON game_session_canonical_account_coverage_control (state, account_id);

-- V29/V30 transitions predate this owner and therefore carry the exact typed no-active arm. Future
-- transitions persist the historical account-wide carrier read from the same locked control row.
ALTER TABLE game_session_canonical_binding_transition
    ADD COLUMN account_coverage_state character varying(32) NOT NULL
        DEFAULT 'NO_ACTIVE_ACCOUNT_WIDE_FLOW',
    ADD COLUMN account_coverage_operation_id uuid,
    ADD COLUMN account_coverage_lifecycle character varying(16),
    ADD COLUMN account_coverage_operation_fence numeric,
    ADD COLUMN account_admission_fence_kind character varying(16) NOT NULL
        DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN account_admission_fence uuid,
    ADD COLUMN historical_account_admission_fence_kind character varying(16) NOT NULL
        DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN historical_account_admission_fence uuid,
    ADD COLUMN resolved_scope_kind character varying(24) NOT NULL
        DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN resolved_scope_account_id uuid,
    ADD COLUMN account_coverage_fence uuid,
    ADD COLUMN account_coverage_generation numeric,
    ADD COLUMN account_inventory_snapshot_revision numeric;

ALTER TABLE game_session_canonical_binding_transition
    ADD CONSTRAINT chk_gs_canonical_binding_transition_account_coverage CHECK (
        (account_coverage_state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
            AND account_coverage_operation_id IS NULL
            AND account_coverage_lifecycle IS NULL
            AND account_coverage_operation_fence IS NULL
            AND account_admission_fence_kind = 'NOT_APPLICABLE'
            AND account_admission_fence IS NULL
            AND historical_account_admission_fence_kind = 'NOT_APPLICABLE'
            AND historical_account_admission_fence IS NULL
            AND resolved_scope_kind = 'NOT_APPLICABLE'
            AND resolved_scope_account_id IS NULL
            AND account_coverage_fence IS NULL
            AND account_coverage_generation IS NULL
            AND account_inventory_snapshot_revision IS NULL)
        OR (account_coverage_state = 'HISTORICAL'
            AND account_coverage_operation_id IS NOT NULL
            AND account_coverage_lifecycle = 'HISTORICAL'
            AND account_coverage_operation_fence > 0
            AND account_admission_fence_kind = 'NOT_APPLICABLE'
            AND account_admission_fence IS NULL
            AND historical_account_admission_fence_kind = 'VALUE'
            AND historical_account_admission_fence IS NOT NULL
            AND historical_account_admission_fence <> '00000000-0000-0000-0000-000000000000'::uuid
            AND resolved_scope_kind = 'ACCOUNT_WIDE'
            AND resolved_scope_account_id IS NOT NULL
            AND account_coverage_fence IS NOT NULL
            AND account_coverage_fence <> '00000000-0000-0000-0000-000000000000'::uuid
            AND account_inventory_snapshot_revision IS NOT NULL
            AND account_inventory_snapshot_revision > 0
            AND account_coverage_generation IS NOT NULL
            AND account_coverage_generation > account_inventory_snapshot_revision)
    );

CREATE TABLE game_session_canonical_account_coverage_snapshot_inventory (
    operation_id uuid NOT NULL,
    row_ordinal bigint NOT NULL,
    binding_ref bytea NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    character_id uuid NOT NULL,
    playable_state_scope character varying(16) NOT NULL,
    game_instance_id uuid NOT NULL,
    runtime_game_instance_id bigint NOT NULL,
    session_id text NOT NULL,
    binding_generation numeric NOT NULL,
    region_id uuid NOT NULL,
    region_epoch numeric NOT NULL,
    issuer_id uuid NOT NULL,
    issuer_auth_generation numeric NOT NULL,
    issuer_index_layout_version numeric NOT NULL,
    issuer_index_partition_count numeric NOT NULL,
    issuer_index_partition_capacity numeric NOT NULL,
    issuer_partition_id numeric NOT NULL,
    account_index_fence uuid NOT NULL,
    transition_id uuid NOT NULL,
    member_transition_id uuid NOT NULL,
    lifecycle character varying(24) NOT NULL,
    account_index_state character varying(16) NOT NULL,
    issuer_index_state character varying(16) NOT NULL,
    inventory_revision numeric NOT NULL,
    action character varying(16) NOT NULL,
    exact_member text NOT NULL,
    expected_readback_state character varying(16) NOT NULL,
    redis_readback_state character varying(16) NOT NULL DEFAULT 'PENDING',
    redis_readback_member text,
    CONSTRAINT pk_gs_canonical_account_coverage_snapshot_inventory
        PRIMARY KEY (operation_id, row_ordinal),
    CONSTRAINT fk_gs_canonical_account_coverage_snapshot_inventory_operation
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_account_coverage_operation (operation_id),
    CONSTRAINT chk_gs_canonical_account_coverage_snapshot_inventory CHECK (
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
        AND issuer_id <> '00000000-0000-0000-0000-000000000000'::uuid
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
                AND redis_readback_member IS NULL)))
);

CREATE TABLE game_session_canonical_account_coverage_snapshot_transition (
    operation_id uuid NOT NULL,
    row_ordinal bigint NOT NULL,
    transition_id uuid NOT NULL,
    candidate_binding_ref bytea NOT NULL,
    expected_prior_binding_ref bytea,
    expected_prior_binding_generation numeric,
    candidate_binding_generation numeric NOT NULL,
    candidate_account_index_fence uuid NOT NULL,
    issuer_reservation_id uuid NOT NULL,
    status character varying(24) NOT NULL,
    inventory_revision numeric NOT NULL,
    candidate_account_id uuid NOT NULL,
    candidate_tenant_id uuid NOT NULL,
    candidate_namespace_id uuid NOT NULL,
    candidate_character_id uuid NOT NULL,
    candidate_playable_state_scope character varying(16) NOT NULL,
    candidate_game_instance_id uuid NOT NULL,
    candidate_runtime_game_instance_id bigint NOT NULL,
    candidate_session_id text NOT NULL,
    candidate_region_id uuid NOT NULL,
    candidate_region_epoch numeric NOT NULL,
    candidate_issuer_id uuid NOT NULL,
    candidate_issuer_auth_generation numeric NOT NULL,
    candidate_issuer_index_layout_version numeric NOT NULL,
    candidate_issuer_index_partition_count numeric NOT NULL,
    candidate_issuer_index_partition_capacity numeric NOT NULL,
    account_coverage_state character varying(32) NOT NULL,
    account_coverage_operation_id uuid,
    account_coverage_lifecycle character varying(16),
    account_coverage_operation_fence numeric,
    account_admission_fence_kind character varying(16) NOT NULL,
    account_admission_fence uuid,
    historical_account_admission_fence_kind character varying(16) NOT NULL,
    historical_account_admission_fence uuid,
    resolved_scope_kind character varying(24) NOT NULL,
    resolved_scope_account_id uuid,
    coverage_fence uuid,
    coverage_generation numeric,
    account_inventory_snapshot_revision numeric,
    CONSTRAINT pk_gs_canonical_account_coverage_snapshot_transition
        PRIMARY KEY (operation_id, row_ordinal),
    CONSTRAINT fk_gs_canonical_account_coverage_snapshot_transition_operation
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_account_coverage_operation (operation_id),
    CONSTRAINT chk_gs_canonical_account_coverage_snapshot_transition CHECK (
        row_ordinal >= 0
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND candidate_binding_generation > 0
        AND candidate_account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND inventory_revision > 0
        AND ((account_coverage_state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
                AND account_coverage_operation_id IS NULL
                AND account_coverage_lifecycle IS NULL
                AND account_coverage_operation_fence IS NULL
                AND account_admission_fence_kind = 'NOT_APPLICABLE'
                AND account_admission_fence IS NULL
                AND historical_account_admission_fence_kind = 'NOT_APPLICABLE'
                AND historical_account_admission_fence IS NULL
                AND resolved_scope_kind = 'NOT_APPLICABLE'
                AND resolved_scope_account_id IS NULL
                AND coverage_fence IS NULL
                AND coverage_generation IS NULL
                AND account_inventory_snapshot_revision IS NULL)
            OR (account_coverage_state = 'HISTORICAL'
                AND account_coverage_operation_id IS NOT NULL
                AND account_coverage_lifecycle = 'HISTORICAL'
                AND account_coverage_operation_fence > 0
                AND account_admission_fence_kind = 'NOT_APPLICABLE'
                AND account_admission_fence IS NULL
                AND historical_account_admission_fence_kind = 'VALUE'
                AND historical_account_admission_fence IS NOT NULL
                AND resolved_scope_kind = 'ACCOUNT_WIDE'
                AND resolved_scope_account_id IS NOT NULL
                AND coverage_fence IS NOT NULL
                AND coverage_generation IS NOT NULL
                AND coverage_generation > account_inventory_snapshot_revision
                AND account_inventory_snapshot_revision IS NOT NULL
                AND account_inventory_snapshot_revision > 0))
    )
);

CREATE TABLE game_session_canonical_account_coverage_snapshot_obligation (
    operation_id uuid NOT NULL,
    row_ordinal bigint NOT NULL,
    transition_id uuid NOT NULL,
    obligation_ordinal integer NOT NULL,
    binding_ref bytea NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    action character varying(16) NOT NULL,
    binding_generation numeric NOT NULL,
    expected_prior_generation numeric,
    account_index_fence uuid NOT NULL,
    execution_phase character varying(24) NOT NULL,
    status character varying(16) NOT NULL,
    inventory_revision numeric NOT NULL,
    projection_state character varying(32) NOT NULL,
    projection_member text,
    projection_inventory_revision numeric,
    binding_lifecycle character varying(24) NOT NULL,
    transition_status character varying(24) NOT NULL,
    expected_readback_state character varying(16) NOT NULL,
    exact_member text,
    redis_readback_state character varying(16) NOT NULL DEFAULT 'PENDING',
    redis_readback_member text,
    CONSTRAINT pk_gs_canonical_account_coverage_snapshot_obligation
        PRIMARY KEY (operation_id, row_ordinal),
    CONSTRAINT fk_gs_canonical_account_coverage_snapshot_obligation_operation
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_account_coverage_operation (operation_id),
    CONSTRAINT chk_gs_canonical_account_coverage_snapshot_obligation CHECK (
        row_ordinal >= 0
        AND obligation_ordinal >= 0
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND binding_generation > 0
        AND account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND status = 'REQUIRED'
        AND inventory_revision > 0
        AND expected_readback_state IN ('PRESENT', 'ABSENT', 'BLOCKED')
        AND ((redis_readback_state = 'PENDING' AND redis_readback_member IS NULL)
            OR (redis_readback_state = 'PRESENT' AND redis_readback_member IS NOT NULL)
            OR (redis_readback_state = 'ABSENT' AND redis_readback_member IS NULL)))
);

CREATE TABLE game_session_canonical_account_coverage_snapshot_reservation (
    operation_id uuid NOT NULL,
    row_ordinal bigint NOT NULL,
    reservation_id uuid NOT NULL,
    binding_ref bytea NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    binding_generation numeric NOT NULL,
    transition_id uuid NOT NULL,
    issuer_id uuid NOT NULL,
    issuer_auth_generation numeric NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    character_id uuid NOT NULL,
    playable_state_scope character varying(16) NOT NULL,
    game_instance_id uuid NOT NULL,
    runtime_game_instance_id bigint NOT NULL,
    session_id text NOT NULL,
    region_id uuid NOT NULL,
    region_epoch numeric NOT NULL,
    partition_id numeric NOT NULL,
    issuer_index_layout_version numeric NOT NULL,
    issuer_index_partition_count numeric NOT NULL,
    issuer_index_partition_capacity numeric NOT NULL,
    lifecycle character varying(24) NOT NULL,
    reservation_fence uuid NOT NULL,
    issuer_coverage_operation_id uuid NOT NULL,
    issuer_coverage_operation_fence numeric NOT NULL,
    coverage_fence numeric NOT NULL,
    inventory_snapshot_revision numeric NOT NULL,
    inventory_revision numeric NOT NULL,
    CONSTRAINT pk_gs_canonical_account_coverage_snapshot_reservation
        PRIMARY KEY (operation_id, row_ordinal),
    CONSTRAINT fk_gs_canonical_account_coverage_snapshot_reservation_operation
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_account_coverage_operation (operation_id),
    CONSTRAINT chk_gs_canonical_account_coverage_snapshot_reservation CHECK (
        row_ordinal >= 0
        AND reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND binding_generation > 0
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND issuer_id <> '00000000-0000-0000-0000-000000000000'::uuid
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
        AND inventory_revision > 0)
);

CREATE TABLE game_session_canonical_account_coverage_snapshot_issuer_obligation (
    operation_id uuid NOT NULL,
    row_ordinal bigint NOT NULL,
    transition_id uuid NOT NULL,
    binding_ref bytea NOT NULL,
    binding_generation numeric NOT NULL,
    reservation_id uuid NOT NULL,
    status character varying(16) NOT NULL,
    inventory_revision numeric NOT NULL,
    CONSTRAINT pk_gs_canonical_account_coverage_snapshot_issuer_obligation
        PRIMARY KEY (operation_id, row_ordinal),
    CONSTRAINT fk_gs_canonical_account_coverage_snapshot_issuer_obligation_operation
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_account_coverage_operation (operation_id),
    CONSTRAINT chk_gs_canonical_account_coverage_snapshot_issuer_obligation CHECK (
        row_ordinal >= 0
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND binding_generation > 0
        AND reservation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND status = 'REQUIRED'
        AND inventory_revision > 0)
);

CREATE TABLE game_session_canonical_account_coverage_snapshot_region_obligation (
    operation_id uuid NOT NULL,
    row_ordinal bigint NOT NULL,
    transition_id uuid NOT NULL,
    binding_ref bytea NOT NULL,
    binding_generation numeric NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    game_instance_id uuid NOT NULL,
    runtime_game_instance_id bigint NOT NULL,
    session_id text NOT NULL,
    region_id uuid NOT NULL,
    region_epoch numeric NOT NULL,
    status character varying(16) NOT NULL,
    inventory_revision numeric NOT NULL,
    CONSTRAINT pk_gs_canonical_account_coverage_snapshot_region_obligation
        PRIMARY KEY (operation_id, row_ordinal),
    CONSTRAINT fk_gs_canonical_account_coverage_snapshot_region_obligation_operation
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_account_coverage_operation (operation_id),
    CONSTRAINT chk_gs_canonical_account_coverage_snapshot_region_obligation CHECK (
        row_ordinal >= 0
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND binding_generation > 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND game_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND runtime_game_instance_id > 0
        AND region_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND region_epoch > 0
        AND status = 'REQUIRED'
        AND inventory_revision > 0)
);

CREATE TABLE game_session_canonical_account_index_acknowledgement (
    operation_id uuid NOT NULL,
    member_ordinal bigint NOT NULL,
    schema_version integer NOT NULL,
    account_coverage_state character varying(32) NOT NULL,
    account_coverage_operation_id uuid NOT NULL,
    account_coverage_lifecycle character varying(16) NOT NULL,
    account_coverage_operation_fence numeric NOT NULL,
    account_admission_fence_kind character varying(16) NOT NULL,
    account_admission_fence uuid,
    historical_account_admission_fence_kind character varying(16) NOT NULL,
    historical_account_admission_fence uuid NOT NULL,
    resolved_scope_kind character varying(24) NOT NULL,
    resolved_scope_account_id uuid NOT NULL,
    transition_id uuid NOT NULL,
    binding_ref bytea NOT NULL,
    account_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    binding_generation numeric NOT NULL,
    account_index_fence uuid NOT NULL,
    operation_fence numeric NOT NULL,
    coverage_fence uuid NOT NULL,
    coverage_generation numeric NOT NULL,
    inventory_snapshot_revision numeric NOT NULL,
    action character varying(16) NOT NULL,
    readback_state character varying(16) NOT NULL,
    readback_member text,
    CONSTRAINT pk_gs_canonical_account_index_acknowledgement
        PRIMARY KEY (operation_id, member_ordinal),
    CONSTRAINT fk_gs_canonical_account_index_acknowledgement_operation
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_account_coverage_operation (operation_id),
    CONSTRAINT chk_gs_canonical_account_index_acknowledgement CHECK (
        member_ordinal >= 0
        AND schema_version = 2
        AND account_coverage_state = 'HISTORICAL'
        AND account_coverage_operation_id = operation_id
        AND account_coverage_lifecycle = 'HISTORICAL'
        AND account_coverage_operation_fence = operation_fence
        AND account_coverage_operation_fence > 0
        AND account_admission_fence_kind = 'NOT_APPLICABLE'
        AND account_admission_fence IS NULL
        AND historical_account_admission_fence_kind = 'VALUE'
        AND historical_account_admission_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND resolved_scope_kind = 'ACCOUNT_WIDE'
        AND resolved_scope_account_id = account_id
        AND transition_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND binding_generation > 0
        AND account_index_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND coverage_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND coverage_generation > inventory_snapshot_revision
        AND inventory_snapshot_revision > 0
        AND ((action = 'ADD_OR_RETAIN'
                AND readback_state = 'PRESENT'
                AND readback_member IS NOT NULL)
            OR (action = 'REMOVE'
                AND readback_state = 'ABSENT'
                AND readback_member IS NULL)))
);
