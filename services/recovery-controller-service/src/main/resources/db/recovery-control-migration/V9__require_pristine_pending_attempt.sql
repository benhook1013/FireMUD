-- A pending effect has never claimed an attempt. Refuse contradictory rows without editing them.
ALTER TABLE recovery_control.initial_gameplay_release_outbox
    DROP CONSTRAINT ck_release_outbox_pending_dispatch_timestamp;

ALTER TABLE recovery_control.initial_gameplay_release_outbox
    ADD CONSTRAINT ck_release_outbox_pending_dispatch_timestamp
        CHECK (state <> 'pending' OR
               (attempt_count = 0 AND last_attempt_id IS NULL AND last_dispatched_at IS NULL));
