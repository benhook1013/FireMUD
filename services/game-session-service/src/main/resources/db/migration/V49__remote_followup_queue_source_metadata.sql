ALTER TABLE remote_followup
    ADD COLUMN queue_source_kind VARCHAR(64),
    ADD COLUMN queue_source_state VARCHAR(64),
    ADD COLUMN queue_source_ordinal BIGINT,
    ADD COLUMN queue_source_due_tick_id BIGINT,
    ADD COLUMN queue_source_due_at_ms BIGINT;

CREATE INDEX idx_remote_followup_queue_source_due
    ON remote_followup (
        tenant_id,
        queue_source_kind,
        queue_source_state,
        queue_source_due_tick_id,
        due_tick_id
    );
