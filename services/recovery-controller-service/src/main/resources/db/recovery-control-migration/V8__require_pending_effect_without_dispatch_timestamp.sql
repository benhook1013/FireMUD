-- Refuse contradictory retained pending rows without clearing dispatch evidence.
ALTER TABLE recovery_control.initial_gameplay_release_outbox
    ADD CONSTRAINT ck_release_outbox_pending_dispatch_timestamp
        CHECK (state <> 'pending' OR last_dispatched_at IS NULL);
