-- Acquisition intent closes Game Session NEW preparation while the exact tenant-local barriers
-- are installed by their separate owner. This record is not an installed barrier or coverage proof.

CREATE TABLE game_session_canonical_account_admission_fence_intent (
    operation_id uuid NOT NULL,
    request_id uuid NOT NULL,
    account_id uuid NOT NULL,
    operation_fence numeric NOT NULL,
    planned_account_admission_fence uuid NOT NULL,
    inventory_snapshot_revision numeric NOT NULL,
    legacy_cohort_id uuid NOT NULL,
    legacy_owner_operation_id uuid NOT NULL,
    legacy_writer_fence uuid NOT NULL,
    legacy_source_snapshot_revision numeric NOT NULL,
    legacy_canonical_inventory_revision numeric NOT NULL,
    legacy_namespace_index_readback_revision numeric NOT NULL,
    legacy_evidence_digest character varying(71) NOT NULL,
    intent_state character varying(32) NOT NULL,
    cohort_row_count bigint NOT NULL,
    cohort_digest character varying(71) NOT NULL,
    barrier_target_count bigint NOT NULL,
    barrier_target_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_canonical_account_admission_fence_intent PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_canonical_account_admission_fence_intent_operation_account
        UNIQUE (operation_id, account_id),
    CONSTRAINT uq_gs_canonical_account_admission_fence_intent_request
        UNIQUE (account_id, request_id),
    CONSTRAINT uq_gs_canonical_account_admission_fence_intent_fence
        UNIQUE (account_id, operation_fence),
    CONSTRAINT fk_gs_canonical_account_admission_fence_intent_account
        FOREIGN KEY (account_id)
        REFERENCES game_session_canonical_account_coverage_control (account_id),
    CONSTRAINT chk_gs_canonical_account_admission_fence_intent CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND operation_fence > 0
        AND planned_account_admission_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND inventory_snapshot_revision > 0
        AND legacy_cohort_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_owner_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_writer_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_source_snapshot_revision > 0
        AND legacy_canonical_inventory_revision > 0
        AND legacy_canonical_inventory_revision <= inventory_snapshot_revision
        AND legacy_namespace_index_readback_revision > 0
        AND legacy_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND intent_state = 'ACQUISITION_INTENT'
        AND cohort_row_count >= 0
        AND cohort_digest ~ '^sha256:[0-9a-f]{64}$'
        AND barrier_target_count >= 0
        AND barrier_target_digest ~ '^sha256:[0-9a-f]{64}$')
);

ALTER TABLE game_session_canonical_account_coverage_control
    ADD COLUMN current_fence_intent_id uuid;

ALTER TABLE game_session_canonical_account_coverage_control
    DROP CONSTRAINT chk_gs_canonical_account_coverage_control_state;

ALTER TABLE game_session_canonical_account_coverage_control
    ADD CONSTRAINT chk_gs_canonical_account_coverage_control_state CHECK (
        last_operation_fence >= 0
        AND ((state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'
                AND current_operation_id IS NULL
                AND current_fence_intent_id IS NULL
                AND current_operation_fence IS NULL
                AND account_admission_fence IS NULL
                AND historical_account_admission_fence IS NULL
                AND coverage_fence IS NULL
                AND inventory_snapshot_revision IS NULL
                AND coverage_generation IS NULL)
            OR (state = 'ACTIVE'
                AND current_operation_id IS NOT NULL
                AND current_fence_intent_id IS NULL
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
                AND current_fence_intent_id IS NULL
                AND current_operation_fence > 0
                AND current_operation_fence = last_operation_fence
                AND account_admission_fence IS NULL
                AND historical_account_admission_fence IS NOT NULL
                AND historical_account_admission_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                AND coverage_fence IS NOT NULL
                AND coverage_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                AND inventory_snapshot_revision > 0
                AND coverage_generation IS NOT NULL
                AND coverage_generation > inventory_snapshot_revision)
            OR (state = 'ACQUISITION_INTENT'
                AND current_operation_id IS NULL
                AND current_fence_intent_id IS NOT NULL
                AND current_operation_fence IS NOT NULL
                AND current_operation_fence > 0
                AND current_operation_fence = last_operation_fence
                AND account_admission_fence IS NOT NULL
                AND account_admission_fence <> '00000000-0000-0000-0000-000000000000'::uuid
                AND historical_account_admission_fence IS NULL
                AND coverage_fence IS NULL
                AND inventory_snapshot_revision IS NOT NULL
                AND inventory_snapshot_revision > 0
                AND coverage_generation IS NULL))
);

ALTER TABLE game_session_canonical_account_coverage_control
    ADD CONSTRAINT fk_gs_canonical_account_coverage_control_fence_intent
        FOREIGN KEY (current_fence_intent_id, account_id)
        REFERENCES game_session_canonical_account_admission_fence_intent (operation_id, account_id);

CREATE TABLE game_session_canonical_account_admission_fence_intent_cohort_row (
    operation_id uuid NOT NULL,
    account_id uuid NOT NULL,
    row_ordinal bigint NOT NULL,
    source_kind character varying(40) NOT NULL,
    source_key text NOT NULL,
    tenant_id uuid,
    playable_state_namespace_id uuid,
    source_row jsonb NOT NULL,
    CONSTRAINT pk_gs_canonical_account_admission_fence_intent_cohort_row
        PRIMARY KEY (operation_id, row_ordinal),
    -- [jooq ignore start]
    CONSTRAINT uq_gs_canonical_account_admission_fence_intent_cohort_source
        UNIQUE (operation_id, source_kind, source_key),
    -- [jooq ignore stop]
    CONSTRAINT fk_gs_canonical_account_admission_fence_intent_cohort_operation
        FOREIGN KEY (operation_id, account_id)
        REFERENCES game_session_canonical_account_admission_fence_intent (operation_id, account_id),
    CONSTRAINT chk_gs_canonical_account_admission_fence_intent_cohort_row CHECK (
        row_ordinal >= 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND source_kind IN (
            'BINDING', 'TRANSITION', 'RESERVATION', 'ACCOUNT_OBLIGATION',
            'ISSUER_OBLIGATION', 'REGION_OBLIGATION', 'MEMBER_REPAIR_CONTROL',
            'MEMBER_REPAIR_OPERATION')
        AND length(source_key) > 0
        AND ((tenant_id IS NULL AND playable_state_namespace_id IS NULL)
            OR (tenant_id IS NOT NULL
                AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
                AND playable_state_namespace_id IS NOT NULL
                AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid))
        AND jsonb_typeof(source_row) = 'object')
);

CREATE TABLE game_session_canonical_account_admission_fence_intent_barrier_target (
    operation_id uuid NOT NULL,
    account_id uuid NOT NULL,
    target_ordinal bigint NOT NULL,
    tenant_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    target_state character varying(16) NOT NULL,
    CONSTRAINT pk_gs_canonical_account_admission_fence_intent_barrier_target
        PRIMARY KEY (operation_id, target_ordinal),
    CONSTRAINT uq_gs_canonical_account_admission_fence_intent_barrier_scope
        UNIQUE (operation_id, tenant_id, playable_state_namespace_id),
    CONSTRAINT fk_gs_canonical_account_admission_fence_intent_barrier_operation
        FOREIGN KEY (operation_id, account_id)
        REFERENCES game_session_canonical_account_admission_fence_intent (operation_id, account_id),
    CONSTRAINT chk_gs_canonical_account_admission_fence_intent_barrier_target CHECK (
        target_ordinal >= 0
        AND account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND target_state = 'REQUIRED')
);

ALTER TABLE game_session_canonical_account_admission_fence_intent_cohort_row
    ADD CONSTRAINT fk_gs_canonical_account_admission_fence_intent_cohort_barrier_target
        FOREIGN KEY (operation_id, tenant_id, playable_state_namespace_id)
        REFERENCES game_session_canonical_account_admission_fence_intent_barrier_target
            (operation_id, tenant_id, playable_state_namespace_id);
