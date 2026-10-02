ALTER TABLE account_audit_outbox
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMP;

-- jOOQ's H2 DDL simulation cannot parse PostgreSQL's AT TIME ZONE expression.
-- Flyway still applies this UTC backfill against PostgreSQL.
-- [jooq ignore start]
UPDATE account_audit_outbox
SET next_attempt_at = now() AT TIME ZONE 'UTC'
WHERE delivery_status = 'PENDING';
-- [jooq ignore stop]

-- [jooq ignore start]
ALTER TABLE account_audit_outbox
    ALTER COLUMN next_attempt_at SET DEFAULT (now() AT TIME ZONE 'UTC');
-- [jooq ignore stop]

ALTER TABLE account_audit_outbox
    ADD CONSTRAINT account_audit_outbox_attempt_count_check
        CHECK (attempt_count >= 0);

ALTER TABLE account_audit_outbox
    ADD CONSTRAINT account_audit_outbox_retry_state_check
        CHECK ((delivery_status = 'PENDING' AND next_attempt_at IS NOT NULL)
            OR (delivery_status IN ('COMMITTED', 'MINIMIZED') AND next_attempt_at IS NULL));

CREATE INDEX idx_account_audit_outbox_due
    ON account_audit_outbox(next_attempt_at, created_at, audit_event_id)
    WHERE delivery_status = 'PENDING';
