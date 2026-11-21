CREATE SCHEMA IF NOT EXISTS recovery_control;

CREATE TABLE recovery_control.initial_gameplay_release_operation (
    operation_id UUID PRIMARY KEY,
    operation_kind VARCHAR(40) NOT NULL DEFAULT 'initial_gameplay_release'
        CHECK (operation_kind = 'initial_gameplay_release'),
    target_namespace VARCHAR(253) NOT NULL,
    playable_state_namespace_id UUID NOT NULL,
    peer_storage_binding JSONB NOT NULL,
    scope_payload JSONB NOT NULL,
    evidence_ref TEXT NOT NULL,
    scope_digest CHAR(64) NOT NULL,
    maintenance_lock_identity UUID NOT NULL UNIQUE,
    phase VARCHAR(32) NOT NULL
        CHECK (phase IN (
            'collecting',
            'ready_to_reopen',
            'awaiting_resume',
            'resume_authorized',
            'releasing',
            'finalized'
        )),
    evidence_snapshot JSONB,
    evidence_identity TEXT,
    evidence_digest CHAR(64),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized_at TIMESTAMPTZ,
    CHECK (
        (evidence_snapshot IS NULL AND evidence_identity IS NULL AND evidence_digest IS NULL)
        OR
        (evidence_snapshot IS NOT NULL AND evidence_identity IS NOT NULL AND evidence_digest IS NOT NULL)
    ),
    CHECK ((phase = 'finalized') = (finalized_at IS NOT NULL))
);

CREATE TABLE recovery_control.initial_gameplay_release_transition (
    operation_id UUID NOT NULL
        REFERENCES recovery_control.initial_gameplay_release_operation(operation_id),
    verb VARCHAR(24) NOT NULL CHECK (verb IN ('continueRecovery', 'resume')),
    tuple_digest CHAR(64) NOT NULL,
    tuple_payload JSONB NOT NULL,
    expected_phase VARCHAR(32) NOT NULL,
    result_phase VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation_id, verb)
);

CREATE TABLE recovery_control.initial_gameplay_release_outbox (
    outbox_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL
        REFERENCES recovery_control.initial_gameplay_release_operation(operation_id),
    effect_id UUID NOT NULL,
    effect_kind VARCHAR(48) NOT NULL
        CHECK (effect_kind IN ('prepare_playable_state_fenced', 'commit_owner_authorized_handoff')),
    operation_tuple_digest CHAR(64) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    state VARCHAR(20) NOT NULL
        CHECK (state IN ('pending', 'dispatched', 'retryable', 'ambiguous', 'acknowledged')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_attempt_id UUID,
    last_dispatched_at TIMESTAMPTZ,
    last_outcome_code VARCHAR(64),
    readback_identity TEXT,
    readback_payload JSONB,
    readback_digest CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TIMESTAMPTZ,
    UNIQUE (operation_id, effect_id),
    UNIQUE (operation_id, effect_kind),
    CHECK (
        (readback_identity IS NULL AND readback_payload IS NULL AND readback_digest IS NULL)
        OR
        (readback_identity IS NOT NULL AND readback_payload IS NOT NULL AND readback_digest IS NOT NULL)
    ),
    CHECK ((state = 'acknowledged') = (acknowledged_at IS NOT NULL))
);

CREATE INDEX idx_initial_gameplay_release_outbox_pending
    ON recovery_control.initial_gameplay_release_outbox (created_at, outbox_id)
    WHERE state IN ('pending', 'retryable', 'dispatched', 'ambiguous');
