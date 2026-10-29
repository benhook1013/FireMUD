-- This migration persists only a pending, issuer-scoped initial-layout bootstrap intent and its
-- immutable durable SQL cohort. It does not publish an active layout pointer, installed anchor,
-- issuer coverage acknowledgement, barrier state, or admission authority.
CREATE TABLE game_session_canonical_issuer_layout_bootstrap_intent (
    operation_id uuid NOT NULL,
    request_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    control_epoch numeric NOT NULL,
    operation_fence numeric NOT NULL,
    coverage_fence numeric NOT NULL,
    inventory_snapshot_revision numeric NOT NULL,
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
    legacy_cohort_id uuid NOT NULL,
    legacy_owner_operation_id uuid NOT NULL,
    legacy_writer_fence uuid NOT NULL,
    legacy_source_snapshot_revision numeric NOT NULL,
    legacy_canonical_inventory_revision numeric NOT NULL,
    legacy_namespace_index_readback_revision numeric NOT NULL,
    legacy_evidence_digest character varying(71) NOT NULL,
    cohort_row_count bigint NOT NULL,
    cohort_digest character varying(71) NOT NULL,
    intent_state character varying(32) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_layout_bootstrap_intent PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_issuer_layout_bootstrap_intent_operation_issuer_pair
        UNIQUE (operation_id, issuer_id),
    CONSTRAINT uq_gs_issuer_layout_bootstrap_intent_operation_issuer
        UNIQUE (operation_id, issuer_id, control_epoch, operation_fence, coverage_fence),
    CONSTRAINT uq_gs_issuer_layout_bootstrap_intent_request
        UNIQUE (issuer_id, request_id),
    CONSTRAINT uq_gs_issuer_layout_bootstrap_intent_fence
        UNIQUE (issuer_id, operation_fence),
    CONSTRAINT chk_gs_issuer_layout_bootstrap_intent CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND operation_id <> request_id
        AND issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND control_epoch > 0
        AND operation_fence = control_epoch
        AND coverage_fence > 0
        AND coverage_fence <> operation_fence
        AND inventory_snapshot_revision > 0
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
        AND legacy_cohort_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_owner_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_writer_fence <> '00000000-0000-0000-0000-000000000000'::uuid
        AND legacy_source_snapshot_revision > 0
        AND legacy_canonical_inventory_revision > 0
        AND legacy_canonical_inventory_revision <= inventory_snapshot_revision
        AND legacy_namespace_index_readback_revision > 0
        AND legacy_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND cohort_row_count >= 0
        AND cohort_digest ~ '^sha256:[0-9a-f]{64}$'
        AND intent_state = 'INITIAL_BOOTSTRAP_INTENT')
);

CREATE TABLE game_session_canonical_issuer_layout_bootstrap_control (
    issuer_id character varying(64) NOT NULL,
    state character varying(32) NOT NULL,
    control_epoch numeric NOT NULL,
    last_issued_fence numeric NOT NULL,
    current_operation_id uuid NOT NULL,
    current_operation_fence numeric NOT NULL,
    current_coverage_fence numeric NOT NULL,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_layout_bootstrap_control PRIMARY KEY (issuer_id),
    CONSTRAINT fk_gs_issuer_layout_bootstrap_control_intent
        FOREIGN KEY (
            current_operation_id, issuer_id, control_epoch,
            current_operation_fence, current_coverage_fence)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent (
            operation_id, issuer_id, control_epoch, operation_fence, coverage_fence),
    CONSTRAINT chk_gs_issuer_layout_bootstrap_control CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND state = 'INITIAL_BOOTSTRAP_INTENT'
        AND control_epoch > 0
        AND last_issued_fence >= current_coverage_fence
        AND current_operation_fence = control_epoch
        AND current_operation_fence > 0
        AND current_coverage_fence > 0
        AND current_coverage_fence <> current_operation_fence)
);

CREATE TABLE game_session_canonical_issuer_layout_bootstrap_cohort_row (
    operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    row_ordinal bigint NOT NULL,
    source_kind character varying(40) NOT NULL,
    source_key text NOT NULL,
    source_inventory_revision numeric NOT NULL,
    source_row_json text NOT NULL,
    source_row_digest character varying(71) NOT NULL,
    CONSTRAINT pk_gs_issuer_layout_bootstrap_cohort_row
        PRIMARY KEY (operation_id, row_ordinal),
    -- [jooq ignore start]
    CONSTRAINT uq_gs_issuer_layout_bootstrap_cohort_source
        UNIQUE (operation_id, source_kind, source_key),
    -- [jooq ignore stop]
    CONSTRAINT fk_gs_issuer_layout_bootstrap_cohort_intent
        FOREIGN KEY (operation_id, issuer_id)
        REFERENCES game_session_canonical_issuer_layout_bootstrap_intent
            (operation_id, issuer_id),
    CONSTRAINT chk_gs_issuer_layout_bootstrap_cohort_row CHECK (
        row_ordinal >= 0
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
