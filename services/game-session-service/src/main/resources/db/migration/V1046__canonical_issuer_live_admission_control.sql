-- The live tenant-barrier epoch belongs to a separate issuer control domain. The immutable
-- V1030 initial-layout control and installed anchor remain provenance only; neither is reused as
-- a live admission state or epoch.
CREATE TABLE game_session_canonical_issuer_admission_epoch_counter (
    issuer_id character varying(64) NOT NULL,
    last_live_barrier_epoch numeric NOT NULL,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_admission_epoch_counter PRIMARY KEY (issuer_id),
    CONSTRAINT chk_gs_issuer_admission_epoch_counter CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND last_live_barrier_epoch >= 0)
);

INSERT INTO game_session_canonical_issuer_admission_epoch_counter
    (issuer_id, last_live_barrier_epoch)
VALUES ('firemud-account-service', 0);

CREATE TABLE game_session_canonical_issuer_admission_control (
    issuer_id character varying(64) NOT NULL,
    state character varying(32) NOT NULL,
    live_barrier_epoch numeric NOT NULL,
    current_operation_id uuid NOT NULL,
    current_operation_kind character varying(32) NOT NULL,
    bootstrap_operation_id uuid NOT NULL,
    bootstrap_request_id uuid NOT NULL,
    bootstrap_control_epoch numeric NOT NULL,
    anchor_exact_bytes bytea NOT NULL,
    anchor_exact_digest character varying(71) NOT NULL,
    account_issuer_auth_generation numeric NOT NULL,
    account_checkpoint_bytes bytea NOT NULL,
    account_checkpoint_digest character varying(71) NOT NULL,
    account_source_event_bytes bytea,
    account_source_event_digest character varying(71),
    stable_incarnation_bytes bytea NOT NULL,
    stable_incarnation_digest character varying(71) NOT NULL,
    inventory_snapshot_revision numeric NOT NULL,
    cohort_row_count bigint NOT NULL,
    cohort_digest character varying(71) NOT NULL,
    control_exact_bytes bytea NOT NULL,
    control_exact_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_admission_control PRIMARY KEY (issuer_id),
    CONSTRAINT fk_gs_issuer_admission_control_epoch
        FOREIGN KEY (issuer_id)
        REFERENCES game_session_canonical_issuer_admission_epoch_counter (issuer_id),
    CONSTRAINT chk_gs_issuer_admission_control CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND state IN ('OPEN', 'CUTOFF_PENDING', 'INSTALLED')
        AND live_barrier_epoch > 0
        AND current_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND current_operation_kind IN ('INITIAL_ANCHOR_INSTALL', 'CUTOFF')
        AND bootstrap_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND bootstrap_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND bootstrap_control_epoch > 0
        AND octet_length(anchor_exact_bytes) BETWEEN 1 AND 524288
        AND anchor_exact_digest ~ '^sha256:[0-9a-f]{64}$'
        AND account_issuer_auth_generation > 0
        AND octet_length(account_checkpoint_bytes) BETWEEN 1 AND 524288
        AND account_checkpoint_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((account_source_event_bytes IS NULL AND account_source_event_digest IS NULL)
            OR (account_source_event_bytes IS NOT NULL
                AND octet_length(account_source_event_bytes) BETWEEN 1 AND 524288
                AND account_source_event_digest ~ '^sha256:[0-9a-f]{64}$'))
        AND octet_length(stable_incarnation_bytes) BETWEEN 1 AND 524288
        AND stable_incarnation_digest ~ '^sha256:[0-9a-f]{64}$'
        AND inventory_snapshot_revision > 0
        AND cohort_row_count >= 0
        AND cohort_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(control_exact_bytes) BETWEEN 1 AND 524288
        AND control_exact_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((state = 'OPEN' AND current_operation_kind = 'INITIAL_ANCHOR_INSTALL')
            OR (state IN ('CUTOFF_PENDING', 'INSTALLED')
                AND current_operation_kind = 'CUTOFF')))
);

CREATE TABLE game_session_canonical_issuer_admission_operation (
    operation_id uuid NOT NULL,
    request_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    operation_kind character varying(32) NOT NULL,
    operation_state character varying(32) NOT NULL,
    live_barrier_epoch numeric NOT NULL,
    prior_live_barrier_epoch numeric,
    bootstrap_operation_id uuid NOT NULL,
    bootstrap_request_id uuid NOT NULL,
    bootstrap_control_epoch numeric NOT NULL,
    anchor_exact_bytes bytea NOT NULL,
    anchor_exact_digest character varying(71) NOT NULL,
    account_issuer_auth_generation numeric NOT NULL,
    account_checkpoint_bytes bytea NOT NULL,
    account_checkpoint_digest character varying(71) NOT NULL,
    account_source_event_bytes bytea,
    account_source_event_digest character varying(71),
    stable_incarnation_bytes bytea NOT NULL,
    stable_incarnation_digest character varying(71) NOT NULL,
    inventory_snapshot_revision numeric NOT NULL,
    cohort_row_count bigint NOT NULL,
    cohort_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    installed_at timestamp with time zone,
    CONSTRAINT pk_gs_issuer_admission_operation PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_issuer_admission_operation_pair UNIQUE (operation_id, issuer_id),
    CONSTRAINT uq_gs_issuer_admission_operation_request UNIQUE (issuer_id, request_id),
    CONSTRAINT uq_gs_issuer_admission_operation_epoch UNIQUE (issuer_id, live_barrier_epoch),
    CONSTRAINT chk_gs_issuer_admission_operation CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND operation_id <> request_id
        AND issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND operation_kind IN ('INITIAL_ANCHOR_INSTALL', 'CUTOFF')
        AND operation_state IN ('OPEN', 'CUTOFF_PENDING', 'INSTALLED')
        AND live_barrier_epoch > 0
        AND ((operation_kind = 'INITIAL_ANCHOR_INSTALL'
                AND operation_state = 'OPEN'
                AND prior_live_barrier_epoch IS NULL
                AND installed_at IS NOT NULL)
            OR (operation_kind = 'CUTOFF'
                AND operation_state IN ('CUTOFF_PENDING', 'INSTALLED')
                AND prior_live_barrier_epoch > 0
                AND live_barrier_epoch > prior_live_barrier_epoch
                AND ((operation_state = 'CUTOFF_PENDING' AND installed_at IS NULL)
                    OR (operation_state = 'INSTALLED' AND installed_at IS NOT NULL))))
        AND bootstrap_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND bootstrap_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND bootstrap_control_epoch > 0
        AND octet_length(anchor_exact_bytes) BETWEEN 1 AND 524288
        AND anchor_exact_digest ~ '^sha256:[0-9a-f]{64}$'
        AND account_issuer_auth_generation > 0
        AND octet_length(account_checkpoint_bytes) BETWEEN 1 AND 524288
        AND account_checkpoint_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((account_source_event_bytes IS NULL AND account_source_event_digest IS NULL)
            OR (account_source_event_bytes IS NOT NULL
                AND octet_length(account_source_event_bytes) BETWEEN 1 AND 524288
                AND account_source_event_digest ~ '^sha256:[0-9a-f]{64}$'))
        AND octet_length(stable_incarnation_bytes) BETWEEN 1 AND 524288
        AND stable_incarnation_digest ~ '^sha256:[0-9a-f]{64}$'
        AND inventory_snapshot_revision > 0
        AND cohort_row_count >= 0
        AND cohort_digest ~ '^sha256:[0-9a-f]{64}$')
);

ALTER TABLE game_session_canonical_issuer_admission_control
    ADD CONSTRAINT fk_gs_issuer_admission_control_operation
        FOREIGN KEY (current_operation_id, issuer_id)
        REFERENCES game_session_canonical_issuer_admission_operation (operation_id, issuer_id);

CREATE TABLE game_session_canonical_issuer_admission_cohort_row (
    operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    row_ordinal bigint NOT NULL,
    source_kind character varying(48) NOT NULL,
    source_key text NOT NULL,
    source_inventory_revision numeric NOT NULL,
    source_row_json text NOT NULL,
    source_row_digest character varying(71) NOT NULL,
    CONSTRAINT pk_gs_issuer_admission_cohort_row PRIMARY KEY (operation_id, row_ordinal),
    -- [jooq ignore start]
    CONSTRAINT uq_gs_issuer_admission_cohort_source UNIQUE (operation_id, source_kind, source_key),
    -- [jooq ignore stop]
    CONSTRAINT fk_gs_issuer_admission_cohort_operation
        FOREIGN KEY (operation_id, issuer_id)
        REFERENCES game_session_canonical_issuer_admission_operation (operation_id, issuer_id),
    CONSTRAINT chk_gs_issuer_admission_cohort_row CHECK (
        row_ordinal >= 0
        AND issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND source_kind IN (
            'BINDING', 'TRANSITION', 'RESERVATION', 'ACCOUNT_OBLIGATION',
            'ISSUER_OBLIGATION', 'REGION_OBLIGATION', 'LOCAL_OPERATION',
            'BOOTSTRAP_MEMBER_OPERATION', 'PENDING_INITIALIZER')
        AND length(source_key) > 0
        AND source_inventory_revision > 0
        AND length(source_row_json) > 1
        AND source_row_digest ~ '^sha256:[0-9a-f]{64}$')
);

-- The complete original OPEN payload is committed before its one-key Redis operation. It is
-- immutable across retries and remains available to a later cutoff even after a lost reply.
CREATE TABLE game_session_canonical_issuer_admission_initializer (
    operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    tenant_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    tenant_gameplay_tag character varying(44) NOT NULL,
    live_barrier_epoch numeric NOT NULL,
    initialization_state character varying(24) NOT NULL,
    initializer_exact_bytes bytea NOT NULL,
    initializer_digest character varying(71) NOT NULL,
    open_payload_bytes bytea NOT NULL,
    open_payload_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_admission_initializer PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_issuer_admission_initializer_target_epoch
        UNIQUE (issuer_id, tenant_id, playable_state_namespace_id, live_barrier_epoch),
    CONSTRAINT uq_gs_issuer_admission_initializer_operation_pair UNIQUE (operation_id, issuer_id),
    CONSTRAINT fk_gs_issuer_admission_initializer_operation
        FOREIGN KEY (operation_id)
        REFERENCES game_session_canonical_issuer_binding_local_operation (operation_id),
    CONSTRAINT chk_gs_issuer_admission_initializer CHECK (
        issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_gameplay_tag ~ '^g[1-9][0-9]{0,9}-[0-9a-f]{32}$'
        AND live_barrier_epoch > 0
        AND initialization_state IN ('PREPARED', 'INITIALIZING', 'OPEN_CONFIRMED')
        AND octet_length(initializer_exact_bytes) BETWEEN 1 AND 524288
        AND initializer_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(open_payload_bytes) BETWEEN 1 AND 524288
        AND open_payload_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE TABLE game_session_canonical_issuer_admission_initializer_receipt (
    operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    exact_open_payload_bytes bytea NOT NULL,
    exact_open_payload_digest character varying(71) NOT NULL,
    script_outcome smallint NOT NULL,
    readback_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_issuer_admission_initializer_receipt PRIMARY KEY (operation_id),
    CONSTRAINT fk_gs_issuer_admission_initializer_receipt_initializer
        FOREIGN KEY (operation_id, issuer_id)
        REFERENCES game_session_canonical_issuer_admission_initializer (operation_id, issuer_id),
    CONSTRAINT chk_gs_issuer_admission_initializer_receipt CHECK (
        octet_length(exact_open_payload_bytes) BETWEEN 1 AND 524288
        AND exact_open_payload_digest ~ '^sha256:[0-9a-f]{64}$'
        AND script_outcome IN (1, 2))
);

CREATE TABLE game_session_canonical_issuer_admission_cutoff_target (
    operation_id uuid NOT NULL,
    issuer_id character varying(64) NOT NULL,
    target_ordinal bigint NOT NULL,
    tenant_id uuid NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    tenant_gameplay_tag character varying(44) NOT NULL,
    target_source_digest character varying(71) NOT NULL,
    captured_initializer_operation_id uuid,
    captured_initializer_epoch numeric,
    captured_initializer_digest character varying(71),
    captured_open_payload_bytes bytea,
    captured_open_payload_digest character varying(71),
    closed_payload_bytes bytea NOT NULL,
    closed_payload_digest character varying(71) NOT NULL,
    exact_closed_readback_bytes bytea,
    exact_closed_readback_digest character varying(71),
    script_outcome smallint,
    target_state character varying(24) NOT NULL,
    CONSTRAINT pk_gs_issuer_admission_cutoff_target PRIMARY KEY (operation_id, target_ordinal),
    CONSTRAINT uq_gs_issuer_admission_cutoff_target UNIQUE
        (operation_id, tenant_id, playable_state_namespace_id),
    CONSTRAINT fk_gs_issuer_admission_cutoff_target_operation
        FOREIGN KEY (operation_id, issuer_id)
        REFERENCES game_session_canonical_issuer_admission_operation (operation_id, issuer_id),
    CONSTRAINT fk_gs_issuer_admission_cutoff_target_initializer
        FOREIGN KEY (captured_initializer_operation_id)
        REFERENCES game_session_canonical_issuer_admission_initializer (operation_id),
    CONSTRAINT chk_gs_issuer_admission_cutoff_target CHECK (
        target_ordinal >= 0
        AND issuer_id COLLATE "C" = 'firemud-account-service'::character varying COLLATE "C"
        AND tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND tenant_gameplay_tag ~ '^g[1-9][0-9]{0,9}-[0-9a-f]{32}$'
        AND target_source_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((captured_initializer_operation_id IS NULL
                AND captured_initializer_epoch IS NULL
                AND captured_initializer_digest IS NULL
                AND captured_open_payload_bytes IS NULL
                AND captured_open_payload_digest IS NULL)
            OR (captured_initializer_operation_id IS NOT NULL
                AND captured_initializer_epoch > 0
                AND captured_initializer_digest ~ '^sha256:[0-9a-f]{64}$'
                AND octet_length(captured_open_payload_bytes) BETWEEN 1 AND 524288
                AND captured_open_payload_digest ~ '^sha256:[0-9a-f]{64}$'))
        AND octet_length(closed_payload_bytes) BETWEEN 1 AND 524288
        AND closed_payload_digest ~ '^sha256:[0-9a-f]{64}$'
        AND ((target_state = 'REQUIRED'
                AND exact_closed_readback_bytes IS NULL
                AND exact_closed_readback_digest IS NULL
                AND script_outcome IS NULL)
            OR (target_state = 'CLOSED'
                AND octet_length(exact_closed_readback_bytes) BETWEEN 1 AND 524288
                AND exact_closed_readback_digest ~ '^sha256:[0-9a-f]{64}$'
                AND script_outcome IN (1, 2, 3))))
);

CREATE INDEX ix_gs_issuer_admission_cutoff_target_state
    ON game_session_canonical_issuer_admission_cutoff_target (operation_id, target_state, target_ordinal);

-- One issuer-wide live control must be positively installed before any ordinary issuer write can
-- proceed. The row is immutable provenance plus monotone control; absence is always closed.
