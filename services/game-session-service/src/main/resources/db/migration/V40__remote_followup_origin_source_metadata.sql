ALTER TABLE remote_followup
    ADD COLUMN origin_source_kind VARCHAR(64),
    ADD COLUMN origin_source_state VARCHAR(64),
    ADD COLUMN origin_source_ordinal BIGINT,
    ADD COLUMN origin_source_due_tick_id BIGINT,
    ADD COLUMN origin_source_due_at_ms BIGINT;
