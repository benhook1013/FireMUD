ALTER TABLE gameplay_command
    ADD COLUMN queue_source_due_tick_id BIGINT,
    ADD COLUMN queue_source_due_at_ms BIGINT;
