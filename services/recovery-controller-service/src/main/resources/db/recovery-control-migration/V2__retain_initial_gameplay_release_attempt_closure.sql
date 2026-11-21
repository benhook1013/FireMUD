-- Source-only upgrade. Fence and drain all V1 writers before migration and rollout.
-- V1 could overwrite prior closure evidence; never synthesize missing history.
-- [jooq ignore start]
LOCK TABLE recovery_control.initial_gameplay_release_outbox IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM recovery_control.initial_gameplay_release_outbox
        WHERE attempt_count <> 0 OR last_attempt_id IS NOT NULL OR last_dispatched_at IS NOT NULL
              OR readback_payload IS NOT NULL OR state <> 'pending'
    ) THEN
        RAISE EXCEPTION 'Initial gameplay recovery closure migration requires no prior claims';
    END IF;
END $$;
-- [jooq ignore stop]

CREATE TABLE recovery_control.initial_gameplay_release_attempt_closure (
    outbox_id UUID NOT NULL
        REFERENCES recovery_control.initial_gameplay_release_outbox(outbox_id) ON DELETE RESTRICT,
    attempt_id UUID NOT NULL,
    attempt_ordinal INTEGER NOT NULL CHECK (attempt_ordinal > 0),
    readback_payload JSONB NOT NULL,
    readback_digest CHAR(64) NOT NULL,
    dispatched_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CHECK (recorded_at >= dispatched_at),
    PRIMARY KEY (outbox_id, attempt_id)
);

CREATE TABLE recovery_control.initial_gameplay_release_attempt_rejection (
    outbox_id UUID NOT NULL
        REFERENCES recovery_control.initial_gameplay_release_outbox(outbox_id) ON DELETE RESTRICT,
    attempt_id UUID NOT NULL,
    attempt_ordinal INTEGER NOT NULL CHECK (attempt_ordinal > 0),
    PRIMARY KEY (outbox_id, attempt_id)
);
