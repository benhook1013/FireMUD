-- Validate existing journal rows; never synthesize an attempt or rewrite retained evidence.
-- A conflicting retained row blocks this migration for Recovery owner disposition.
ALTER TABLE recovery_control.initial_gameplay_release_outbox
    ADD CONSTRAINT ck_release_outbox_non_pending_attempt
        CHECK (
            state = 'pending' OR (
                attempt_count > 0
                AND last_attempt_id IS NOT NULL
                AND last_attempt_id <> '00000000-0000-0000-0000-000000000000'::uuid
            )
        );
