-- PENDING JOIN evidence is retained until an exact deterministic outcome is proved.
-- Bounded reconciliation metadata schedules retries without expiring or deleting operations.
ALTER TABLE account_join_operations
    ADD COLUMN reconciliation_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_reconciliation_attempt_at TIMESTAMP,
    ADD COLUMN last_reconciliation_attempt_reason VARCHAR(128),
    ADD COLUMN next_reconciliation_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT account_join_reconciliation_attempt_count_check
        CHECK (reconciliation_attempt_count >= 0),
    ADD CONSTRAINT account_join_reconciliation_attempt_detail_check
        CHECK ((reconciliation_attempt_count = 0
                AND last_reconciliation_attempt_at IS NULL
                AND last_reconciliation_attempt_reason IS NULL)
            OR (reconciliation_attempt_count > 0
                AND last_reconciliation_attempt_at IS NOT NULL
                AND last_reconciliation_attempt_reason IS NOT NULL
                AND btrim(last_reconciliation_attempt_reason) <> ''));

CREATE INDEX idx_account_join_operations_pending_reconciliation
    ON account_join_operations(
        next_reconciliation_attempt_at,
        created_at,
        request_id
    )
    WHERE status = 'PENDING';
