ALTER TABLE gameplay_command
    ADD COLUMN queue_source_kind VARCHAR(64),
    ADD COLUMN queue_source_state VARCHAR(64),
    ADD COLUMN queue_source_ordinal BIGINT;
